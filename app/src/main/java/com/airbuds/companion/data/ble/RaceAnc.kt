package com.airbuds.companion.data.ble

import com.airbuds.companion.model.AncMode

/** Raised when the firmware rejects an ANC command or returns garbage. */
class RaceAncException(message: String) : Exception(message)

/**
 * ANC state read through `0x0901` module 5.
 *
 * [filter] is the raw firmware value: `0` off, `1–4` noise cancelling,
 * `9–11` pass-through, anything else unknown. [gainHundredths] is the 16-bit LE
 * gain divided by 100 by the SDK.
 */
data class AncState(
    val filter: Int,
    val gainHundredths: Int,
) {
    val mode: AncMode?
        get() = when (filter) {
            0 -> AncMode.Off
            in 1..4 -> AncMode.NoiseCancelling
            in 9..11 -> AncMode.Transparency
            else -> null
        }
}

/**
 * Writes ANC modes and reads the current state (`PROTOCOL.md` §5.2).
 *
 * For noise cancelling the controller uses filter 1 with the firmware default
 * (HYBRID) mode; for transparency it uses filter 9 with PASSTHROUGH. These are
 * the simple mode mappings recommended in the protocol; a model that requires a
 * different filter should override the constants.
 */
class RaceAncController(private val executor: RaceCommandExecutor) {

    suspend fun readState(): AncState {
        val response = executor.execute(
            RaceFrame(
                type = RaceType.Request,
                commandId = RaceIds.MMI_GET_STATUS,
                payload = byteArrayOf(MODULE_ANC.toByte(), 0),
            ),
        )
        return parseState(response.payload)
    }

    suspend fun setMode(mode: AncMode) {
        val payload = when (mode) {
            AncMode.Off -> byteArrayOf(
                PREFIX.toByte(),
                SUBCOMMAND_DISABLE.toByte(),
                ENABLED.toByte(),
            )

            AncMode.NoiseCancelling -> byteArrayOf(
                PREFIX.toByte(),
                SUBCOMMAND_ENABLE.toByte(),
                ANC_FILTER.toByte(),
                ANC_MODE.toByte(),
                ENABLED.toByte(),
            )

            AncMode.Transparency -> byteArrayOf(
                PREFIX.toByte(),
                SUBCOMMAND_ENABLE.toByte(),
                PASSTHROUGH_FILTER.toByte(),
                PASSTHROUGH_MODE.toByte(),
                ENABLED.toByte(),
            )
        }
        executeControl(payload)
    }

    /** Sets the pass-through gain; [gainHundredths] is the raw 16-bit value. */
    suspend fun setPassThroughGain(gainHundredths: Int) {
        require(gainHundredths in 0..MAX_GAIN) { "Gain must fit in 16 bits: $gainHundredths" }
        val payload = byteArrayOf(
            PREFIX.toByte(),
            SUBCOMMAND_SET_GAIN.toByte(),
            (gainHundredths and 0xFF).toByte(),
            ((gainHundredths shr 8) and 0xFF).toByte(),
            ENABLED.toByte(),
        )
        executeControl(payload)
    }

    private suspend fun executeControl(payload: ByteArray) {
        val response = executor.execute(
            RaceFrame(
                type = RaceType.Request,
                commandId = RaceIds.ANC_CONTROL,
                payload = payload,
            ),
        )
        val status = response.payload.firstOrNull()?.toInt()?.and(0xFF)
        if (status == null || status != 0) {
            throw RaceAncException("ANC command failed (status ${status ?: "missing"})")
        }
    }

    companion object {
        const val MODULE_ANC = 0x05
        const val PREFIX = 0x00
        const val SUBCOMMAND_ENABLE = 0x0A
        const val SUBCOMMAND_DISABLE = 0x0B
        const val SUBCOMMAND_SET_GAIN = 0x0F
        const val ANC_FILTER = 1
        const val ANC_MODE = 0
        const val PASSTHROUGH_FILTER = 9
        const val PASSTHROUGH_MODE = 4
        const val ENABLED = 0x01
        const val MAX_GAIN = 0xFFFF
        const val DEFAULT_GAIN_HUNDREDTHS = 100

        private const val GAIN_OFFSET = 4
        private const val FILTER_OFFSET = 3
        private const val RESULT_OFFSET = 2
        private const val STATE_PAYLOAD_SIZE = 6

        /** Parses a `0x0901` module-5 response payload. */
        internal fun parseState(payload: ByteArray): AncState {
            if (payload.size >= RESULT_OFFSET + 1) {
                val result = payload[RESULT_OFFSET].toInt() and 0xFF
                if (result != 0) {
                    throw RaceAncException("ANC state read failed (result $result)")
                }
            }
            if (payload.size < STATE_PAYLOAD_SIZE) {
                return AncState(
                    filter = payload.getOrNull(FILTER_OFFSET)?.toInt()?.and(0xFF) ?: 0,
                    gainHundredths = DEFAULT_GAIN_HUNDREDTHS,
                )
            }
            val gain = (payload[GAIN_OFFSET].toInt() and 0xFF) or
                ((payload[GAIN_OFFSET + 1].toInt() and 0xFF) shl 8)
            return AncState(
                filter = payload[FILTER_OFFSET].toInt() and 0xFF,
                gainHundredths = gain,
            )
        }
    }
}
