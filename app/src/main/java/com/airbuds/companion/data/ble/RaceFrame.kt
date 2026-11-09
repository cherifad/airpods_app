package com.airbuds.companion.data.ble

/** First byte of a RACE frame. Only MMI and FOTA frames are valid application frames. */
enum class RaceHead(val value: Int) {
    Mmi(0x05),
    Fota(0x15),
    ;

    companion object {
        fun fromValue(value: Int): RaceHead? = entries.firstOrNull { it.value == value }
    }
}

/** Frame type byte. See `PROTOCOL.md` §3.2. */
enum class RaceType(val value: Int) {
    Request(0x5A),
    Response(0x5B),
    CommandNoResponse(0x5C),
    Indication(0x5D),
    ;

    companion object {
        fun fromValue(value: Int): RaceType? = entries.firstOrNull { it.value == value }
    }
}

/**
 * A decoded RACE frame.
 *
 * The wire `length` field is derived from [payload]: it covers the 16-bit
 * command ID plus the payload (`length = 2 + payload.size`). Keeping it derived
 * makes it impossible to build a frame whose header disagrees with its body.
 */
data class RaceFrame(
    val type: RaceType,
    val commandId: Int,
    val payload: ByteArray = EMPTY_PAYLOAD,
    val head: RaceHead = RaceHead.Mmi,
) {
    init {
        require(commandId in 0..MAX_COMMAND_ID) {
            "RACE command ID must fit in 16 bits: 0x${commandId.toString(16)}"
        }
        require(payload.size <= MAX_PAYLOAD_SIZE) {
            "RACE payload is ${payload.size} bytes, maximum is $MAX_PAYLOAD_SIZE"
        }
    }

    /** Value of the wire `length` field. */
    val length: Int get() = COMMAND_ID_SIZE + payload.size

    /** Total encoded size, including the 4-byte header. */
    val size: Int get() = HEADER_SIZE + length

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RaceFrame) return false
        return head == other.head &&
            type == other.type &&
            commandId == other.commandId &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = head.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + commandId
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String =
        "RaceFrame(head=0x%02X, type=0x%02X, commandId=0x%04X, payload=%s)".format(
            head.value,
            type.value,
            commandId,
            payload.toHex(),
        )

    companion object {
        const val HEADER_SIZE = 4
        const val COMMAND_ID_SIZE = 2
        const val MAX_COMMAND_ID = 0xFFFF

        /** The SDK rejects frames outside the 6..2000 byte range. */
        const val MIN_FRAME_SIZE = HEADER_SIZE + COMMAND_ID_SIZE
        const val MAX_FRAME_SIZE = 2000
        const val MAX_PAYLOAD_SIZE = MAX_FRAME_SIZE - MIN_FRAME_SIZE

        val EMPTY_PAYLOAD = ByteArray(0)
    }
}
