package com.airbuds.companion.ui

import com.airbuds.companion.data.ble.ScannedDevice
import com.airbuds.companion.domain.model.BudsBattery
import com.airbuds.companion.domain.model.BudsConnection
import com.airbuds.companion.domain.model.BudsError
import com.airbuds.companion.model.AncMode

/** State of the product screens, driven by the BLE session. */
data class BudsUiState(
    val connection: BudsConnection = BudsConnection.Disconnected,
    val scanning: Boolean = false,
    val devices: List<ScannedDevice> = emptyList(),
    val pairedDevices: List<ScannedDevice> = emptyList(),
    val connectedDevices: List<ScannedDevice> = emptyList(),
    val lastDevice: ScannedDevice? = null,
    val bluetoothEnabled: Boolean = true,
    val deviceName: String? = null,
    val chipName: String? = null,
    val battery: BudsBattery = BudsBattery.Unknown,
    val anc: AncMode = AncMode.NoiseCancelling,
    val ancAvailable: Boolean = false,
    val error: BudsError? = null,
    val connectSheetVisible: Boolean = false,
) {
    val connected: Boolean get() = connection is BudsConnection.Ready
    val busy: Boolean
        get() = connection is BudsConnection.Connecting ||
            connection is BudsConnection.Initializing

    /** Error to show, including a failure published by the repository. */
    val visibleError: BudsError?
        get() = error ?: (connection as? BudsConnection.Failed)?.error
}
