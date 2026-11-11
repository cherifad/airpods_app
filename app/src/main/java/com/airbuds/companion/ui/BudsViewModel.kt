package com.airbuds.companion.ui

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airbuds.companion.data.ble.ActiveRaceTransport
import com.airbuds.companion.data.ble.BudsSession
import com.airbuds.companion.data.ble.GattTransport
import com.airbuds.companion.data.ble.RaceCommandQueue
import com.airbuds.companion.data.ble.RaceFrame
import com.airbuds.companion.data.ble.ScannedDevice
import com.airbuds.companion.data.ble.SppTransport
import com.airbuds.companion.data.ble.YfyBatteryParser
import com.airbuds.companion.data.preferences.DeviceMemory
import com.airbuds.companion.data.repository.BudsRepository
import com.airbuds.companion.data.repository.toBudsError
import com.airbuds.companion.domain.model.BudsBattery
import com.airbuds.companion.domain.model.BudsConnection
import com.airbuds.companion.domain.model.BudsError
import com.airbuds.companion.model.AncMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the BLE session for the product screens.
 *
 * The repository holds the connection/init/battery state; this ViewModel adds
 * scan results, the ANC mode and the connect-sheet visibility, and keeps the
 * optimistic ANC update with rollback.
 */
class BudsViewModel(application: Application) : AndroidViewModel(application) {

    private val gatt: GattTransport = GattTransport(
        context = application,
        onFrame = { frame: RaceFrame -> queue.onFrame(frame) },
    )
    private val spp: SppTransport = SppTransport(
        context = application,
        onFrame = { frame: RaceFrame -> queue.onFrame(frame) },
    )
    private val active = ActiveRaceTransport(gatt)
    private val queue: RaceCommandQueue =
        RaceCommandQueue(active, onIndication = ::onIndication)
    private val session = BudsSession(queue)
    private val repository = BudsRepository(session)
    private val memory = DeviceMemory(application)

    private val _uiState = MutableStateFlow(BudsUiState())
    val uiState: StateFlow<BudsUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null
    private var connectJob: Job? = null
    private var lastAttemptAddress: String? = null

    init {
        viewModelScope.launch {
            repository.connection.collect { connection ->
                _uiState.update { it.copy(connection = connection) }
                if (connection is BudsConnection.Ready) {
                    Log.i(TAG, "Ready: $connection")
                    memory.markSupported(connection.address, _uiState.value.deviceName)
                    readAnc()
                    if (!_uiState.value.battery.isKnown) {
                        // Some firmware answers the first battery command late.
                        delay(500)
                        repository.refreshBattery()
                    }
                }
            }
        }
        viewModelScope.launch {
            repository.snapshot.collect { snapshot ->
                if (snapshot == null) return@collect
                Log.i(
                    TAG,
                    "Snapshot: chip=${snapshot.deviceInfo.chipName} " +
                        "side=${snapshot.agentIsRight} battery=${snapshot.battery}",
                )
                _uiState.update { current ->
                    current.copy(
                        battery = BudsBattery(
                            leftPercent = snapshot.battery.leftPercent
                                ?: current.battery.leftPercent,
                            rightPercent = snapshot.battery.rightPercent
                                ?: current.battery.rightPercent,
                            casePercent = snapshot.battery.casePercent
                                ?: current.battery.casePercent,
                            agentPercent = snapshot.battery.agentPercent
                                ?: current.battery.agentPercent,
                            partnerPercent = snapshot.battery.partnerPercent
                                ?: current.battery.partnerPercent,
                        ),
                        chipName = snapshot.deviceInfo.chipName,
                    )
                }
            }
        }
    }

    fun showConnectSheet() {
        val connected = gatt.connectedDevices()
            .filterNot { memory.isUnsupported(it.device.address) }
        val paired = gatt.bondedDevices()
            .filterNot { memory.isUnsupported(it.device.address) }
        val lastAddress = memory.lastAddress
        val last = lastAddress
            ?.let { gatt.remoteDevice(it) }
            ?.let { ScannedDevice(it, memory.lastName, GattTransport.LAST_RSSI) }
            ?.takeIf { candidate ->
                connected.none { it.device.address == candidate.device.address } &&
                    paired.none { it.device.address == candidate.device.address }
            }

        _uiState.update {
            it.copy(
                connectSheetVisible = true,
                error = null,
                bluetoothEnabled = gatt.isBluetoothEnabled(),
                connectedDevices = connected,
                pairedDevices = paired,
                lastDevice = last,
            )
        }
    }

    /** Re-reads the Bluetooth state and the device lists after the user enables it. */
    fun refreshBluetooth() {
        if (!_uiState.value.connectSheetVisible) return
        showConnectSheet()
    }

    fun hideConnectSheet() {
        _uiState.update { it.copy(connectSheetVisible = false) }
    }

    fun scan() {
        scanJob?.cancel()
        val paired = runCatching { gatt.bondedDevices() }.getOrDefault(emptyList())
        _uiState.update {
            it.copy(
                scanning = true,
                devices = emptyList(),
                pairedDevices = paired,
                error = null,
            )
        }
        scanJob = viewModelScope.launch {
            try {
                gatt.scan().collect { device ->
                    _uiState.update { current ->
                        current.copy(
                            devices = (current.devices + device)
                                .distinctBy { it.device.address }
                                .sortedByDescending { it.rssi },
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(error = error.toBudsError(), scanning = false) }
            } finally {
                _uiState.update { it.copy(scanning = false) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        stopScan()
        connectJob?.cancel()
        lastAttemptAddress = device.address
        _uiState.update {
            it.copy(
                deviceName = device.name ?: it.deviceName,
                connectSheetVisible = false,
                error = null,
            )
        }
        repository.markConnecting(device.address)

        connectJob = viewModelScope.launch {
            val sppDevice = if (device.bondState == BluetoothDevice.BOND_BONDED) {
                device
            } else {
                gatt.pairedAudioDevice(device.name) ?: gatt.currentAudioDevice()
            }
            var gattUnsupported = false

            try {
                active.delegate = gatt
                try {
                    gatt.connect(device)
                    Log.i(TAG, "Connected via GATT to ${device.address}")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (gattError: Exception) {
                    gattUnsupported =
                        gattError.toBudsError() is BudsError.UnsupportedDevice
                    if (sppDevice == null) throw gattError
                    Log.i(
                        TAG,
                        "GATT failed, falling back to SPP on ${sppDevice.address}",
                        gattError,
                    )
                    active.delegate = spp
                    spp.connect(sppDevice)
                    Log.i(TAG, "Connected via SPP to ${sppDevice.address}")
                }
                repository.attach(device.address)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Connection to ${device.address} failed", error)
                if (gattUnsupported || error.toBudsError() is BudsError.UnsupportedDevice) {
                    memory.markUnsupported(device.address)
                }
                repository.markFailed(device.address, error)
            }
        }
    }

    /** Retries the last address after a failed connection. */
    fun retry() {
        val address = lastAttemptAddress ?: return
        val device = gatt.remoteDevice(address) ?: return
        connect(device)
    }

    fun disconnect() {
        stopScan()
        connectJob?.cancel()
        connectJob = null
        gatt.close()
        spp.close()
        repository.detach()
        _uiState.update {
            it.copy(
                deviceName = null,
                chipName = null,
                battery = BudsBattery.Unknown,
                ancAvailable = false,
            )
        }
    }

    fun setAnc(mode: AncMode) {
        val previous = _uiState.value.anc
        _uiState.update { it.copy(anc = mode, error = null) }
        viewModelScope.launch {
            try {
                session.setAnc(mode)
                readAnc()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(anc = previous, error = error.toBudsError()) }
            }
        }
    }

    fun hideError() {
        _uiState.update { it.copy(error = null) }
    }

    /** Re-reads battery and ANC on the current connection. */
    fun refresh() {
        if (!_uiState.value.connected) return
        viewModelScope.launch {
            repository.refreshBattery()
            readAnc()
        }
    }

    private suspend fun readAnc() {
        val state = session.readAncState()
        _uiState.update {
            it.copy(
                anc = state?.mode ?: it.anc,
                ancAvailable = state?.mode != null,
            )
        }
    }

    private fun onIndication(frame: RaceFrame) {
        val status = YfyBatteryParser.parse(frame) ?: return
        _uiState.update { current ->
            current.copy(
                battery = BudsBattery(
                    leftPercent = status.left.percent ?: current.battery.leftPercent,
                    rightPercent = status.right.percent ?: current.battery.rightPercent,
                    casePercent = status.case.percent ?: current.battery.casePercent,
                ),
            )
        }
    }

    private fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _uiState.update { it.copy(scanning = false) }
    }

    override fun onCleared() {
        gatt.close()
        spp.close()
        super.onCleared()
    }

    companion object {
        private const val TAG = "BudsViewModel"
    }
}
