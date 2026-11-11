package com.airbuds.companion.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Android BLE transport for the Airoha RACE service.
 *
 * Responsibilities:
 * - scan with a filter on [AirohaUuids.service];
 * - connect, discover the service and enable RX notifications (CCCD);
 * - request MTU 240 and expose the negotiated payload size;
 * - reassemble notifications through [RaceParser];
 * - chunk outgoing frames so each GATT write fits the negotiated MTU.
 *
 * This class only speaks bytes: command serialization and response matching
 * stay in [RaceCommandQueue]. Callers must hold the BLE runtime permissions
 * described by [BlePermissions] before calling [scan] or [connect].
 */
@SuppressLint("MissingPermission")
class GattTransport(
    context: Context,
    private val onFrame: (RaceFrame) -> Unit,
    private val onTx: (ByteArray) -> Unit = {},
) : RaceTransport {

    private val appContext = context.applicationContext
    private val bluetoothManager: BluetoothManager? =
        appContext.getSystemService(BluetoothManager::class.java)
    private val parser = RaceParser()

    private val _state = MutableStateFlow<GattState>(GattState.Idle)
    val state: StateFlow<GattState> = _state.asStateFlow()

    private val connectionMutex = Mutex()
    private val writeMutex = Mutex()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var txCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var rxCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var ready: CompletableDeferred<Unit>? = null
    @Volatile private var pendingWrite: CancellableContinuation<Unit>? = null
    @Volatile private var maxWriteSize: Int = MIN_WRITE_SIZE

    /**
     * Bonded audio devices that can be picked without scanning.
     *
     * Earbuds that are already connected to the phone often stop advertising,
     * so the scan cannot see them; the paired list makes them selectable.
     */
    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<ScannedDevice> {
        val adapter = adapter()
        if (!adapter.isEnabled) return emptyList()
        return adapter.bondedDevices.orEmpty()
            .filter {
                it.bluetoothClass?.majorDeviceClass ==
                    android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO
            }
            .map { device ->
                ScannedDevice(
                    device = device,
                    name = device.name,
                    rssi = PAIRED_RSSI,
                )
            }
    }

    /** Audio/GATT devices currently connected to this phone. */
    @SuppressLint("MissingPermission")
    fun connectedDevices(): List<ScannedDevice> {
        val manager = bluetoothManager ?: return emptyList()
        val adapter = manager.adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()

        return listOf(
            BluetoothProfile.A2DP,
            BluetoothProfile.HEADSET,
            BluetoothProfile.GATT,
        ).flatMap { profile ->
            runCatching { manager.getConnectedDevices(profile) }.getOrDefault(emptyList())
        }
            .distinctBy { it.address }
            .map { device ->
                ScannedDevice(
                    device = device,
                    name = device.name,
                    rssi = CONNECTED_RSSI,
                )
            }
    }

    /** True when the Bluetooth adapter exists and is enabled. */
    fun isBluetoothEnabled(): Boolean =
        runCatching { adapter().isEnabled }.getOrDefault(false)

    /** Resolves an address remembered from a previous session. */
    @SuppressLint("MissingPermission")
    fun remoteDevice(address: String): BluetoothDevice? =
        runCatching { adapter().getRemoteDevice(address) }.getOrNull()

    /** Emits devices advertising the Airoha service until [timeoutMillis] elapses. */
    fun scan(
        timeoutMillis: Long = DEFAULT_SCAN_TIMEOUT_MS,
        filterByService: Boolean = true,
    ): Flow<ScannedDevice> = callbackFlow {
        ensurePermissions(BlePermissions.scan)
        val adapter = adapter()
        if (!adapter.isEnabled) throw GattException("Bluetooth is disabled")
        val scanner = adapter.bluetoothLeScanner
            ?: throw GattException("BLE scanner is not available")

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(
                    ScannedDevice(
                        device = result.device,
                        name = result.scanRecord?.deviceName ?: result.device.name,
                        rssi = result.rssi,
                    ),
                )
            }

            override fun onScanFailed(errorCode: Int) {
                close(GattException("BLE scan failed (error $errorCode)"))
            }
        }

        val filters = if (filterByService) {
            listOf(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(AirohaUuids.service))
                    .build(),
            )
        } else {
            emptyList()
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(filters, settings, callback)
        val timeoutJob = launch {
            delay(timeoutMillis)
            close()
        }
        awaitClose {
            timeoutJob.cancel()
            scanner.stopScan(callback)
        }
    }

    /**
     * Connects to [device] and completes once notifications and MTU are ready.
     *
     * A previous connection is closed first. On failure the GATT instance is
     * released and the state moves to [GattState.Failed].
     */
    suspend fun connect(
        device: BluetoothDevice,
        timeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    ) {
        ensurePermissions(BlePermissions.connect)
        val adapter = adapter()
        if (!adapter.isEnabled) throw GattException("Bluetooth is disabled")

        connectionMutex.withLock {
            closeInternal(notify = false)
            try {
                connectOnce(device, timeoutMillis, BluetoothDevice.TRANSPORT_LE)
            } catch (cancelled: CancellationException) {
                closeInternal(notify = false)
                throw cancelled
            } catch (first: Exception) {
                closeInternal(notify = false)
                if (!shouldRetry(first)) throw first
                Log.w(TAG, "LE connect failed, retrying with TRANSPORT_AUTO", first)
                delay(RETRY_DELAY_MS)
                connectOnce(device, timeoutMillis, BluetoothDevice.TRANSPORT_AUTO)
            }
        }
    }

    private suspend fun connectOnce(
        device: BluetoothDevice,
        timeoutMillis: Long,
        transport: Int,
    ) {
        parser.reset()

        val address = device.address
        val signal = CompletableDeferred<Unit>()
        ready = signal
        _state.value = GattState.Connecting(address)

        val newGatt = try {
            device.connectGatt(
                appContext,
                false,
                gattCallback,
                transport,
            )
        } catch (error: SecurityException) {
            ready = null
            _state.value = GattState.Failed(address, "Bluetooth permission denied")
            throw GattException("Bluetooth permission denied", error)
        }

        if (newGatt == null) {
            ready = null
            _state.value = GattState.Failed(address, "connectGatt returned null")
            throw GattException("connectGatt returned null")
        }
        gatt = newGatt

        try {
            withTimeout(timeoutMillis) { signal.await() }
        } catch (timeout: TimeoutCancellationException) {
            closeInternal(notify = false)
            val reason = "Connection timed out"
            _state.value = GattState.Failed(address, reason)
            throw GattException("$reason after $timeoutMillis ms", timeout)
        } catch (cancelled: CancellationException) {
            closeInternal(notify = false)
            throw cancelled
        } catch (error: GattException) {
            closeInternal(notify = false)
            if (_state.value !is GattState.Failed) {
                _state.value = GattState.Failed(
                    address,
                    error.message ?: "Connection failed",
                )
            }
            throw error
        }
    }

    /** status 133 and timeouts are often transient; a second transport may help. */
    private fun shouldRetry(error: Exception): Boolean {
        val message = error.message.orEmpty()
        return message.contains("133") ||
            message.contains("timed out", ignoreCase = true) ||
            message.contains("returned null", ignoreCase = true)
    }

    /** Writes one encoded RACE frame, splitting it across GATT writes if needed. */
    override suspend fun write(bytes: ByteArray) {
        ensurePermissions(BlePermissions.connect)

        writeMutex.withLock {
            val currentGatt = gatt ?: throw GattException("Not connected")
            val characteristic = txCharacteristic
                ?: throw GattException("TX characteristic is not ready")
            if (_state.value !is GattState.Ready) {
                throw GattException("GATT connection is not ready")
            }

            onTx(bytes)
            try {
                for (chunk in FrameChunker.chunk(bytes, maxWriteSize)) {
                    writeChunk(currentGatt, characteristic, chunk)
                }
            } catch (timeout: TimeoutCancellationException) {
                closeInternal(notify = true)
                throw GattException("GATT write timed out", timeout)
            }
        }
    }

    /** Disconnects and releases the GATT instance. */
    fun close() {
        closeInternal(notify = true)
    }

    private fun adapter(): BluetoothAdapter =
        bluetoothManager?.adapter ?: throw GattException("Bluetooth is not available")

    private fun ensurePermissions(permissions: Array<String>) {
        val missing = permissions.filter {
            appContext.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            throw GattException("Missing Bluetooth permission: ${missing.joinToString()}")
        }
    }

    private suspend fun writeChunk(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        chunk: ByteArray,
    ) {
        withTimeout(WRITE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                pendingWrite = continuation
                continuation.invokeOnCancellation {
                    if (pendingWrite === continuation) pendingWrite = null
                }

                @Suppress("DEPRECATION")
                val started = try {
                    characteristic.setValue(chunk) && gatt.writeCharacteristic(characteristic)
                } catch (_: SecurityException) {
                    false
                }

                if (!started) {
                    pendingWrite = null
                    continuation.resumeWithException(
                        GattException("GATT write could not start"),
                    )
                }
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int,
        ) {
            if (gatt !== this@GattTransport.gatt) {
                runCatching { gatt.close() }
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(gatt, "GATT connection error (status $status)")
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _state.value = GattState.Discovering(gatt.device.address)
                    if (!gatt.discoverServices()) {
                        fail(gatt, "Service discovery could not start")
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    val error = GattException("Disconnected from ${gatt.device.address}")
                    ready?.completeExceptionally(error)
                    ready = null
                    pendingWrite?.let {
                        if (it.isActive) it.resumeWithException(error)
                    }
                    pendingWrite = null
                    cleanup(gatt)
                    if (_state.value !is GattState.Failed) {
                        _state.value = GattState.Disconnected(gatt.device.address)
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== this@GattTransport.gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(gatt, "Service discovery failed (status $status)")
                return
            }

            val service = gatt.getService(AirohaUuids.service)
            if (service == null) {
                fail(gatt, "Airoha service not found")
                return
            }
            val tx = service.getCharacteristic(AirohaUuids.tx)
            val rx = service.getCharacteristic(AirohaUuids.rx)
            if (tx == null || rx == null) {
                fail(gatt, "Airoha TX/RX characteristic not found")
                return
            }

            txCharacteristic = tx
            rxCharacteristic = rx
            tx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

            if (!gatt.setCharacteristicNotification(rx, true)) {
                fail(gatt, "Could not enable notifications")
                return
            }
            val descriptor = rx.getDescriptor(AirohaUuids.cccd)
            if (descriptor == null) {
                fail(gatt, "CCCD descriptor not found")
                return
            }

            _state.value = GattState.Configuring(gatt.device.address)
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            val started = gatt.writeDescriptor(descriptor)
            if (!started) {
                fail(gatt, "Could not write the CCCD descriptor")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (gatt !== this@GattTransport.gatt) return
            if (descriptor.uuid != AirohaUuids.cccd) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(gatt, "Could not enable notifications (status $status)")
                return
            }

            if (!gatt.requestMtu(AirohaUuids.REQUESTED_MTU)) {
                markReady(gatt, AirohaUuids.DEFAULT_ATT_MTU)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (gatt !== this@GattTransport.gatt) return
            val negotiated = if (status == BluetoothGatt.GATT_SUCCESS) {
                mtu
            } else {
                AirohaUuids.DEFAULT_ATT_MTU
            }
            markReady(gatt, negotiated)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (gatt !== this@GattTransport.gatt) return
            dispatch(value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            // The value overload is used on API 33+, this one on older versions.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (gatt !== this@GattTransport.gatt) return
            characteristic.value?.let(::dispatch)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (gatt !== this@GattTransport.gatt) return
            val continuation = pendingWrite ?: return
            pendingWrite = null
            if (!continuation.isActive) return

            if (status == BluetoothGatt.GATT_SUCCESS) {
                continuation.resume(Unit)
            } else {
                continuation.resumeWithException(
                    GattException("GATT write failed (status $status)"),
                )
            }
        }
    }

    private fun markReady(gatt: BluetoothGatt, mtu: Int) {
        maxWriteSize = (mtu - AirohaUuids.ATT_HEADER_SIZE).coerceAtLeast(MIN_WRITE_SIZE)
        _state.value = GattState.Ready(gatt.device.address, mtu)
        ready?.complete(Unit)
        ready = null
    }

    private fun dispatch(bytes: ByteArray) {
        try {
            parser.feed(bytes).forEach(onFrame)
        } catch (error: Exception) {
            // Never let a malformed notification kill the GATT callback thread.
            Log.e(TAG, "Failed to process a RACE notification", error)
        }
    }

    private fun fail(gatt: BluetoothGatt, reason: String) {
        val error = GattException(reason)
        _state.value = GattState.Failed(gatt.device.address, reason)
        ready?.completeExceptionally(error)
        ready = null
        pendingWrite?.let {
            if (it.isActive) it.resumeWithException(error)
        }
        pendingWrite = null
        cleanup(gatt)
    }

    private fun closeInternal(notify: Boolean) {
        val current = gatt
        val address = current?.device?.address

        ready?.cancel()
        ready = null
        pendingWrite?.cancel()
        pendingWrite = null

        if (current != null) {
            cleanup(current)
        }
        if (notify) {
            _state.value = GattState.Disconnected(address)
        }
    }

    private fun cleanup(gatt: BluetoothGatt) {
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
        if (this.gatt === gatt) {
            this.gatt = null
            txCharacteristic = null
            rxCharacteristic = null
            maxWriteSize = MIN_WRITE_SIZE
            parser.reset()
        }
    }

    companion object {
        const val DEFAULT_SCAN_TIMEOUT_MS = 10_000L
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000L
        const val WRITE_TIMEOUT_MS = 5_000L
        const val MIN_WRITE_SIZE = 20

        /** Sentinel RSSI for bonded devices that were not seen in a scan. */
        const val PAIRED_RSSI = Int.MIN_VALUE

        /** Sentinel RSSI for devices already connected to this phone. */
        const val CONNECTED_RSSI = Int.MAX_VALUE

        /** Sentinel RSSI for the last device remembered from a previous session. */
        const val LAST_RSSI = Int.MAX_VALUE - 1

        private const val TAG = "GattTransport"
        private const val RETRY_DELAY_MS = 350L
    }
}
