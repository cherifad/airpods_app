package com.airbuds.companion.data.ble

/** Raised when bytes cannot be interpreted as one well-formed RACE frame. */
class RaceProtocolException(message: String) : Exception(message)

/**
 * Encodes and decodes single complete RACE frames.
 *
 * Stream reassembly is the job of [RaceParser]: this codec only accepts one
 * exact frame at a time and refuses truncated or trailing bytes.
 */
object RaceCodec {

    fun encode(frame: RaceFrame): ByteArray {
        val bytes = ByteArray(frame.size)
        bytes[0] = frame.head.value.toByte()
        bytes[1] = frame.type.value.toByte()
        writeUInt16Le(bytes, 2, frame.length)
        writeUInt16Le(bytes, 4, frame.commandId)
        frame.payload.copyInto(bytes, destinationOffset = RaceFrame.MIN_FRAME_SIZE)
        return bytes
    }

    /** Decodes exactly one frame, throwing [RaceProtocolException] otherwise. */
    fun decode(bytes: ByteArray): RaceFrame =
        tryDecode(bytes)
            ?: throw RaceProtocolException(
                "Not a complete RACE frame: ${bytes.toHex()}",
            )

    /** Returns the decoded frame, or null when [bytes] is not exactly one valid frame. */
    fun tryDecode(bytes: ByteArray): RaceFrame? {
        if (bytes.size < RaceFrame.MIN_FRAME_SIZE) return null

        val head = RaceHead.fromValue(bytes[0].toInt() and 0xFF) ?: return null
        val type = RaceType.fromValue(bytes[1].toInt() and 0xFF) ?: return null
        val length = readUInt16Le(bytes, 2)
        if (length < RaceFrame.COMMAND_ID_SIZE) return null

        val total = RaceFrame.HEADER_SIZE + length
        if (total != bytes.size) return null
        if (total > RaceFrame.MAX_FRAME_SIZE) return null

        val commandId = readUInt16Le(bytes, 4)
        val payload = bytes.copyOfRange(RaceFrame.MIN_FRAME_SIZE, bytes.size)
        return RaceFrame(type = type, commandId = commandId, payload = payload, head = head)
    }
}

internal fun readUInt16Le(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

internal fun writeUInt16Le(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value and 0xFF).toByte()
    bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
}

internal fun ByteArray.toHex(): String =
    if (isEmpty()) "<empty>" else joinToString(" ") { "%02X".format(it) }
