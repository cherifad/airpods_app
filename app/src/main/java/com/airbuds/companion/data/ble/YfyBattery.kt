package com.airbuds.companion.data.ble

/** One battery byte from the YFY `0x2100/0x0D` channel. */
data class YfyBatteryLevel(
    val percent: Int?,
    val charging: Boolean,
) {
    val isKnown: Boolean get() = percent != null
}

/**
 * Battery/status notification from the YFY vendor channel.
 *
 * The frame is `05 5B/5D <len> 00 21 0D <event> <right> <left> <case> <earStatus>`
 * (`PROTOCOL.md` §5.4). Each battery byte uses bit 7 as the charging flag and
 * bits 0–6 as the percentage; values above 100 are reported as unknown.
 */
data class YfyBatteryStatus(
    val event: Int,
    val right: YfyBatteryLevel,
    val left: YfyBatteryLevel,
    val case: YfyBatteryLevel,
    val earStatus: Int,
)

/** Parses the `0x2100` battery notification sent by YFY firmware. */
object YfyBatteryParser {

    const val COMMAND_ID = 0x2100
    const val BATTERY_SUBCOMMAND = 0x0D

    private const val OFFSET_SUBCOMMAND = 0
    private const val OFFSET_EVENT = 1
    private const val OFFSET_RIGHT = 2
    private const val OFFSET_LEFT = 3
    private const val OFFSET_CASE = 4
    private const val OFFSET_EAR_STATUS = 5
    private const val MIN_PAYLOAD_SIZE = 6
    private const val MAX_PERCENT = 100
    private const val CHARGING_MASK = 0x80
    private const val PERCENT_MASK = 0x7F

    /**
     * Returns the parsed status, or null when [frame] is not a `0x2100/0x0D`
     * response/indication or is too short.
     */
    fun parse(frame: RaceFrame): YfyBatteryStatus? {
        if (frame.commandId != COMMAND_ID) return null
        if (frame.type != RaceType.Response && frame.type != RaceType.Indication) return null
        val payload = frame.payload
        if (payload.size < MIN_PAYLOAD_SIZE) return null
        if (payload[OFFSET_SUBCOMMAND].toInt() and 0xFF != BATTERY_SUBCOMMAND) return null

        return YfyBatteryStatus(
            event = payload[OFFSET_EVENT].toInt() and 0xFF,
            right = level(payload[OFFSET_RIGHT]),
            left = level(payload[OFFSET_LEFT]),
            case = level(payload[OFFSET_CASE]),
            earStatus = payload[OFFSET_EAR_STATUS].toInt() and 0xFF,
        )
    }

    private fun level(byte: Byte): YfyBatteryLevel {
        val raw = byte.toInt() and 0xFF
        val percent = raw and PERCENT_MASK
        return YfyBatteryLevel(
            percent = if (percent <= MAX_PERCENT) percent else null,
            charging = raw and CHARGING_MASK != 0,
        )
    }
}
