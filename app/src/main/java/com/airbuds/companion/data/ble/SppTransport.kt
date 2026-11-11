package com.airbuds.companion.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Airoha RACE over the legacy SPP/RFCOMM channel.
 *
 * Some AB156x/YFY earbuds expose the control channel only over SPP, not BLE
 * GATT (FlyCC probes GATT, closes it and falls back to this UUID). The socket
 * carries whole RACE frames: [RaceParser] handles reassembly on RX and writes
 * do not need MTU chunking.
 */
@SuppressLint("MissingPermission")
class SppTransport(
    context: Context,
    private val onFrame: (RaceFrame) -> Unit,
    private val onTx: (ByteArray) -> Unit = {},
) : RaceTransport {

    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(BluetoothManager::class.java)
    private val parser = RaceParser()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val writeMutex = Mutex()

    private val _state = MutableStateFlow<GattState>(GattState.Idle)
    val state: StateFlow<GattState> = _state.asStateFlow()

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var readJob: Job? = null

    /**
     * Opens the SPP channel. [device] must be a classic (BR/EDR) device; a BLE
     * scan address cannot be used for RFCOMM.
     */
    suspend fun connect(
        device: BluetoothDevice,
        timeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    ) {
        close()
        _state.value = GattState.Connecting(device.address)

        val newSocket = withContext(Dispatchers.IO) {
            withTimeout(timeoutMillis) {
                val candidate = device.createRfcommSocketToServiceRecord(SPP_UUID)
                try {
                    candidate.connect()
                    candidate
                } catch (error: IOException) {
                    runCatching { candidate.close() }
                    throw error
                }
            }
        }

        socket = newSocket
        _state.value = GattState.Ready(device.address, DEFAULT_MTU)
        startReading()
    }

    override suspend fun write(bytes: ByteArray) {
        writeMutex.withLock {
            val output = socket?.outputStream
                ?: throw GattException("SPP channel is not connected")
            onTx(bytes)
            try {
                withContext(Dispatchers.IO) {
                    output.write(bytes)
                    output.flush()
                }
            } catch (error: IOException) {
                close()
                throw GattException("SPP write failed", error)
            }
        }
    }

    fun close() {
        readJob?.cancel()
        readJob = null
        socket?.let { runCatching { it.close() } }
        socket = null
        parser.reset()
        _state.value = GattState.Disconnected(null)
    }

    private fun startReading() {
        readJob = scope.launch {
            val input = socket?.inputStream ?: return@launch
            val buffer = ByteArray(READ_BUFFER)
            try {
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    parser.feed(buffer.copyOf(read)).forEach(onFrame)
                }
            } catch (error: IOException) {
                Log.w(TAG, "SPP read loop stopped", error)
            } finally {
                _state.value = GattState.Disconnected(socket?.remoteDevice?.address)
                close()
            }
        }
    }

    override fun toString(): String = "SppTransport"

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00000000-0000-0000-0099-AABBCCDDEEFF")

        const val DEFAULT_MTU = 990
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000L
        private const val READ_BUFFER = 4096
        private const val TAG = "SppTransport"
    }
}
