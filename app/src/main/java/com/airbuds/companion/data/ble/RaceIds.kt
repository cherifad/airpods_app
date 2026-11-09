package com.airbuds.companion.data.ble

/** RACE command and NVKEY identifiers used by Buds Companion. */
object RaceIds {
    const val NVKEY_READ = 0x0A00
    const val GET_ROLE = 0x0CC4
    const val GET_AVAILABLE_DESTINATIONS = 0x0D00

    const val NVKEY_NVDM_VERSION = 0x1001
    const val NVKEY_CHIP_NAME = 0x1002
    const val NVKEY_DEVICE_TYPE_PRIMARY = 0x183D
    const val NVKEY_DEVICE_TYPE_FALLBACK = 0xF2B0

    /** Maximum number of bytes requested in one NVKEY read. */
    const val NVKEY_REQUESTED_BYTES = 1000

    /** Destination type of the TWS peer in a `0x0D00` response. */
    const val DESTINATION_TYPE_AWSPEER = 5
}
