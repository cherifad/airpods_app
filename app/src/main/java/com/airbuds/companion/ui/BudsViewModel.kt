package com.airbuds.companion.ui

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airbuds.companion.data.ble.BudsSession
import com.airbuds.companion.data.ble.GattTransport
import com.airbuds.companion.data.ble.RaceCommandQueue
import com.airbuds.companion.data.ble.RaceFrame
import com.airbuds.companion.data.ble.YfyBatteryParser
import com.airbuds.companion.data.repository.BudsRepository
import com.airbuds.companion.data.repository.toBudsError
import com.airbuds.companion.domain.model.BudsBattery
import com.airbuds.companion.domain.model.BudsConnection
import com.airbuds.companion.model.AncMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

    private val transport: GattTransport = GattTransport(
        context = application,
        onFrame = { frame: RaceFrame -> queue.onFrame(frame) },
    )
    private val queue: RaceCommandQueue =
        RaceCommandQueue(transport, onIndication = ::onIndication)
    private val session = BudsSession(queue)
    private val repository = BudsRepository(session)

    private val _uiState = MutableStateFlow(BudsUiState())
    val uiState: StateFlow<BudsUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null
    private var connectJob: Job? = null

    init {
        viewModelScope.launch {
            repository.connection.collect { connection ->
                _uiState.update { it.copy(connection = connection) }
                if (connection is BudsConnection.Ready) {
                    readAnc()
                }
            }
        }
        viewModelScope.launch {
            repository.snapshot.collect { snapshot ->
                if (snapshot == null) return@collect
                _uiState.update {
                    it.copy(
                        battery = snapshot.battery,
                        chipName = snapshot.deviceInfo.chipName,
                    )
                }
            }
        }
    }

    fun showConnectSheet() {
        _uiState.update { it.copy(connectSheetVisible = true, error = null) }
    }

    fun hideConnectSheet() {
        _uiState.update { it.copy(connectSheetVisible = false) }
    }

    fun scan() {
        scanJob?.cancel()
        _uiState.update {
            it.copy(scanning = true, devices = emptyList(), error = null)
        }
        scanJob = viewModelScope.launch {
            try {
                transport.scan().collect { device ->
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
        _uiState.update {
            it.copy(
                deviceName = device.name ?: it.deviceName,
                connectSheetVisible = false,
                error = null,
            )
        }
        repository.markConnecting(device.address)

        connectJob = viewModelScope.launch {
            try {
                transport.connect(device)
                repository.attach(device.address)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                repository.markFailed(device.address, error)
            }
        }
    }

    fun disconnect() {
        stopScan()
        connectJob?.cancel()
        connectJob = null
        transport.close()
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
        transport.close()
        super.onCleared()
    }
}
