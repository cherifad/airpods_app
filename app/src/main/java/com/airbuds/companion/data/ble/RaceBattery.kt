package com.airbuds.companion.data.ble

/** Raised when a battery response is malformed or reports a failure. */
class RaceBatteryException(message: String) : Exception(message)

/**
 * One earbud's battery level.
 *
 * [percent] is null when the firmware reports a value outside `0..100`: the
 * exact semantics of other values are undocumented, so the UI shows "unknown"
 * instead of a false level (`PROTOCOL.md` §5.1).
 */
data class EarbudBattery(val percent: Int?) {
    val isKnown: Boolean get() = percent != null
}

/**
 * Reads earbud battery levels over RACE (`PROTOCOL.md` §5.1).
 *
 * The local earbud answers `0x0CD6` directly. The partner is read by wrapping
 * the same frame in a `0x0D01` relay addressed to the AWSPEER destination; the
 * response embeds the peer's inner `0x0CD6` frame.
 *
 * Calls are serialized by [RaceCommandExecutor] and may repeat every 30 s and
 * after each reconnection.
 */
class RaceBatteryReader(private val executor: RaceCommandExecutor) {

    /** Reads the agent (local earbud) level. */
    suspend fun readLocal(): EarbudBattery {
        val response = executor.execute(
            RaceFrame(
                type = RaceType.Request,
                commandId = RaceIds.TWS_GET_BATTERY,
                payload = byteArrayOf(AGENT_REQUEST.toByte()),
            ),
        )
        return parseBatteryResponse(response)
    }

    /** Reads the partner level through a relay to [peerId] (from `0x0D00`). */
    suspend fun readPartner(peerId: Int): EarbudBattery {
        require(peerId in 0..MAX_DESTINATION_ID) {
            "TWS destination id must fit in one byte: $peerId"
        }

        val inner = RaceFrame(
            type = RaceType.Request,
            commandId = RaceIds.TWS_GET_BATTERY,
            payload = byteArrayOf(AGENT_REQUEST.toByte()),
        )
        val payload = ByteArray(RELAY_ADDRESS_SIZE).apply {
            this[0] = RaceIds.DESTINATION_TYPE_AWSPEER.toByte()
            this[1] = peerId.toByte()
        } + RaceCodec.encode(inner)

        val response = executor.execute(
            RaceFrame(
                type = RaceType.Request,
                commandId = RaceIds.RELAY_PASS_TO_DST,
                payload = payload,
            ),
        )
        return parseRelayResponse(response, expectedPeerId = peerId)
    }

    companion object {
        /** The 1562 layer sends `0` for the agent request. */
        const val AGENT_REQUEST = 0x00

        const val MAX_PERCENT = 100
        const val MAX_DESTINATION_ID = 0xFF

        private const val RELAY_ADDRESS_SIZE = 2
        private const val BATTERY_PAYLOAD_SIZE = 3
        private const val STATUS_OFFSET = 0
        private const val LEVEL_OFFSET = 2

        /** Parses a direct `0x0CD6` response: `status, agentOrClient, level`. */
        internal fun parseBatteryResponse(frame: RaceFrame): EarbudBattery {
            if (frame.commandId != RaceIds.TWS_GET_BATTERY) {
                throw RaceBatteryException(
                    "Unexpected battery command 0x${frame.commandId.toString(16)}",
                )
            }
            val payload = frame.payload
            if (payload.size < BATTERY_PAYLOAD_SIZE) {
                throw RaceBatteryException("Battery response is too short")
            }

            val status = payload[STATUS_OFFSET].toInt() and 0xFF
            if (status != 0) {
                throw RaceBatteryException("Battery request failed (status $status)")
            }

            val level = payload[LEVEL_OFFSET].toInt() and 0xFF
            return EarbudBattery(percent = if (level <= MAX_PERCENT) level else null)
        }

        /**
         * Parses a `0x0D01` relay response: `type, destinationId, <inner frame>`.
         * The inner frame starts after the two address bytes.
         */
        internal fun parseRelayResponse(
            frame: RaceFrame,
            expectedPeerId: Int? = null,
        ): EarbudBattery {
            if (frame.commandId != RaceIds.RELAY_PASS_TO_DST) {
                throw RaceBatteryException(
                    "Unexpected relay command 0x${frame.commandId.toString(16)}",
                )
            }
            val payload = frame.payload
            if (payload.size < RELAY_ADDRESS_SIZE + RaceFrame.MIN_FRAME_SIZE) {
                throw RaceBatteryException("Relay response is too short")
            }

            val destinationType = payload[0].toInt() and 0xFF
            if (destinationType != RaceIds.DESTINATION_TYPE_AWSPEER) {
                throw RaceBatteryException(
                    "Relay answered for destination type $destinationType, expected AWSPEER",
                )
            }

            val destinationId = payload[1].toInt() and 0xFF
            if (expectedPeerId != null && destinationId != expectedPeerId) {
                throw RaceBatteryException(
                    "Relay answered for destination $destinationId, expected $expectedPeerId",
                )
            }

            val innerBytes = payload.copyOfRange(RELAY_ADDRESS_SIZE, payload.size)
            val inner = try {
                RaceCodec.decode(innerBytes)
            } catch (_: RaceProtocolException) {
                throw RaceBatteryException("Relay response does not contain a complete RACE frame")
            }
            return parseBatteryResponse(inner)
        }
    }
}
