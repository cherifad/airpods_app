package com.airbuds.companion.data.ble

/** Device form factor derived from the type NVKEY and its AWS mode. */
enum class AirohaDeviceType {
    Headset,
    Earbuds,
}

/** TWS role reported by `0x0CC4`. */
sealed interface TwsRole {
    data object Normal : TwsRole
    data object Master : TwsRole
    data object Slave : TwsRole
    data class Unknown(val code: Int) : TwsRole
}

/** Information collected by the initialization sequence (`PROTOCOL.md` §4). */
data class RaceDeviceInfo(
    val chipName: String,
    val deviceType: AirohaDeviceType,
    val nvdmVersion: Int,
    val role: TwsRole,
    val peerId: Int?,
) {
    /** True when a TWS peer was listed in the `0x0D00` destinations. */
    val hasPeer: Boolean get() = peerId != null
}
