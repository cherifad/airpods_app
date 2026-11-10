package com.airbuds.companion.ui

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airbuds.companion.data.ble.AncState
import com.airbuds.companion.data.ble.BudsSession
import com.airbuds.companion.data.ble.GattState
import com.airbuds.companion.data.ble.GattTransport
import com.airbuds.companion.data.ble.RaceCodec
import com.airbuds.companion.data.ble.RaceCommandQueue
import com.airbuds.companion.data.ble.RaceDeviceInfo
import com.airbuds.companion.data.ble.RaceFrame
import com.airbuds.companion.data.ble.ScannedDevice
import com.airbuds.companion.data.ble.YfyBatteryLevel
import com.airbuds.companion.data.ble.YfyBatteryParser
import com.airbuds.companion.data.ble.YfyBatteryStatus
import com.airbuds.companion.domain.model.BudsBattery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** State of the debug diagnostics screen. */
data class DiagnosticsUiState(
    val gattState: GattState = GattState.Idle,
    val scanning: Boolean = false,
    val filterByService: Boolean = true,
    val devices: List<ScannedDevice> = emptyList(),
    val deviceInfo: RaceDeviceInfo? = null,
    val battery: BudsBattery = BudsBattery.Unknown,
    val yfyBattery: YfyBatteryStatus? = null,
    val ancState: AncState? = null,
    val logs: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * Drives the BLE stack from the diagnostics screen.
 *
 * Kept as a ViewModel so a scan, a connection or the initialization sequence
 * survives a configuration change while testing on a real device.
 */
class DiagnosticsViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(DiagnosticsUiState())
    val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()

    private val transport = GattTransport(
        context = application,
        onFrame = { frame -> appendLog("RX ${hex(RaceCodec.encode(frame))}") },
        onTx = { bytes -> appendLog("TX ${hex(bytes)}") },
    )
    private val queue = RaceCommandQueue(transport, onIndication = ::handleIndication)
    private val session = BudsSession(queue)

    private var scanJob: Job? = null
    private var connectJob: Job? = null
    private var scanGeneration = 0

    init {
        viewModelScope.launch {
            transport.state.collect { state ->
                _uiState.update { it.copy(gattState = state) }
                appendLog("STATE $state")
            }
        }
    }

    fun scan() {
        scanJob?.cancel()
        scanGeneration++
        val generation = scanGeneration

        _uiState.update {
            it.copy(
                scanning = true,
                devices = emptyList(),
                error = null,
            )
        }
        appendLog("SCAN start")

        scanJob = viewModelScope.launch {
            try {
                transport.scan(filterByService = _uiState.value.filterByService).collect { device ->
                    _uiState.update { current ->
                        val devices = (current.devices + device)
                            .distinctBy { it.device.address }
                            .sortedByDescending { it.rssi }
                        current.copy(devices = devices)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail("Scan failed", error)
            } finally {
                if (scanGeneration == generation) {
                    _uiState.update { it.copy(scanning = false) }
                }
                appendLog("SCAN stop")
            }
        }
    }

    fun connect(device: BluetoothDevice) {
        stopScan()
        connectJob?.cancel()

        connectJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    error = null,
                    deviceInfo = null,
                    battery = BudsBattery.Unknown,
                    yfyBattery = null,
                    ancState = null,
                )
            }
            appendLog("CONNECT ${device.address}")

            try {
                transport.connect(device)
                appendLog("INIT start")
                val info = session.initialize()
                _uiState.update { it.copy(deviceInfo = info) }
                appendLog(
                    "INIT chip=${info.chipName} type=${info.deviceType} " +
                        "nvdm=${info.nvdmVersion} role=${info.role} " +
                        "peer=${info.peerId ?: "none"}",
                )

                val battery = session.readBattery(info)
                _uiState.update { it.copy(battery = battery) }
                appendLog(
                    "BATTERY agent=${battery.agentPercent ?: "unknown"} " +
                        "partner=${battery.partnerPercent ?: "unknown"}",
                )

                val anc = session.readAncState()
                _uiState.update { it.copy(ancState = anc) }
                appendLog(
                    "ANC mode=${anc?.mode ?: "unknown"} " +
                        "filter=${anc?.filter ?: "unknown"} " +
                        "gain=${anc?.gainHundredths ?: "unknown"}",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail("Connection failed", error)
            }
        }
    }

    fun disconnect() {
        stopScan()
        connectJob?.cancel()
        connectJob = null
        transport.close()
        _uiState.update {
            it.copy(
                deviceInfo = null,
                battery = BudsBattery.Unknown,
                yfyBattery = null,
                ancState = null,
            )
        }
        appendLog("DISCONNECT")
    }

    fun onPermissionsDenied() {
        _uiState.update {
            it.copy(error = "Bluetooth permissions are required to scan and connect.")
        }
        appendLog("PERMISSION denied")
    }

    fun setFilterByService(enabled: Boolean) {
        _uiState.update { it.copy(filterByService = enabled) }
    }

    fun clearLog() {
        _uiState.update { it.copy(logs = emptyList()) }
    }

    override fun onCleared() {
        transport.close()
        super.onCleared()
    }

    private fun stopScan() {
        scanGeneration++
        scanJob?.cancel()
        scanJob = null
        _uiState.update { it.copy(scanning = false) }
    }

    private fun fail(context: String, error: Exception) {
        val detail = error.message ?: error.javaClass.simpleName
        _uiState.update { it.copy(error = "$context: $detail") }
        appendLog("ERROR $context: $detail")
    }

    private fun handleIndication(frame: RaceFrame) {
        val status = YfyBatteryParser.parse(frame) ?: return
        _uiState.update { it.copy(yfyBattery = status) }
        appendLog(
            "YFY battery event=${status.event} " +
                "right=${status.right.describe()} " +
                "left=${status.left.describe()} " +
                "case=${status.case.describe()}",
        )
    }

    private fun YfyBatteryLevel.describe(): String {
        val value = percent?.let { "$it %" } ?: "unknown"
        return if (charging) "$value charging" else value
    }

    private fun appendLog(line: String) {
        _uiState.update {
            it.copy(logs = (it.logs + line).takeLast(MAX_LOG_LINES))
        }
    }

    private fun hex(bytes: ByteArray): String =
        if (bytes.isEmpty()) {
            "<empty>"
        } else {
            bytes.joinToString(" ") { "%02X".format(it) }
        }

    companion object {
        private const val MAX_LOG_LINES = 300
    }
}
