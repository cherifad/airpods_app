package com.airbuds.companion.data.ble

/** Observable state of the GATT connection. */
sealed interface GattState {
    data object Idle : GattState
    data class Connecting(val address: String) : GattState
    data class Discovering(val address: String) : GattState
    data class Configuring(val address: String) : GattState
    data class Ready(val address: String, val mtu: Int) : GattState
    data class Disconnected(val address: String?) : GattState
    data class Failed(val address: String?, val reason: String) : GattState
}

/** Raised when the GATT layer cannot scan, connect, configure or write. */
class GattException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A device found while scanning for the Airoha service. */
data class ScannedDevice(
    val device: android.bluetooth.BluetoothDevice,
    val name: String?,
    val rssi: Int,
)
