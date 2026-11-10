package com.airbuds.companion.data.ble

/** RACE command and NVKEY identifiers used by Sonabud. */
object RaceIds {
    const val NVKEY_READ = 0x0A00
    const val GET_ROLE = 0x0CC4
    const val GET_AVAILABLE_DESTINATIONS = 0x0D00

    /** TWS battery read, local or wrapped in a relay for the partner. */
    const val TWS_GET_BATTERY = 0x0CD6

    /** ANC/pass-through enable, disable and gain (`PROTOCOL.md` §5.2.1). */
    const val ANC_CONTROL = 0x0E06

    /** MMI status read; module 5 carries the ANC filter and gain. */
    const val MMI_GET_STATUS = 0x0901

    /** Relay a frame to a TWS destination (peer or client). */
    const val RELAY_PASS_TO_DST = 0x0D01

    const val NVKEY_NVDM_VERSION = 0x1001
    const val NVKEY_CHIP_NAME = 0x1002
    const val NVKEY_DEVICE_TYPE_PRIMARY = 0x183D
    const val NVKEY_DEVICE_TYPE_FALLBACK = 0xF2B0

    /** Maximum number of bytes requested in one NVKEY read. */
    const val NVKEY_REQUESTED_BYTES = 1000

    /** NVKEY holding the audio-channel/agent-side configuration. */
    const val NVKEY_AUDIO_CHANNEL = 0xF2B5

    /** Destination type of the TWS peer in a `0x0D00` response. */
    const val DESTINATION_TYPE_AWSPEER = 5
}
