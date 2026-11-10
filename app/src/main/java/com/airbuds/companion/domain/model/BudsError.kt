package com.airbuds.companion.domain.model

/** Product-level error, independent from the BLE/protocol exceptions. */
sealed interface BudsError {
    data object BluetoothDisabled : BudsError
    data object PermissionDenied : BudsError
    data object Timeout : BudsError
    data class Protocol(val message: String) : BudsError
    data class Transport(val message: String) : BudsError
    data class Unknown(val message: String) : BudsError
}
