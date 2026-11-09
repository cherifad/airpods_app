package com.airbuds.companion.data.ble

/**
 * Reassembles RACE frames from a byte stream.
 *
 * GATT notifications may contain a fragment, a complete frame, or several
 * concatenated frames, so callers must feed every chunk to [feed] instead of
 * assuming one notification equals one frame.
 *
 * Invalid headers and impossible lengths are skipped byte by byte so a single
 * corrupted byte cannot poison the rest of the stream. Skipped bytes are
 * counted in [discardedBytes] for diagnostics.
 */
class RaceParser {

    private var buffer = ByteArray(0)

    /** Number of bytes dropped because they could not start a valid frame. */
    @Volatile
    var discardedBytes: Long = 0
        private set

    /** Appends [chunk] and returns every frame completed by it, in order. */
    @Synchronized
    fun feed(chunk: ByteArray): List<RaceFrame> {
        if (chunk.isEmpty()) return emptyList()

        buffer = buffer + chunk
        val frames = mutableListOf<RaceFrame>()
        var offset = 0

        while (offset < buffer.size) {
            val headerIndex = findHeader(buffer, offset)
            if (headerIndex > offset) {
                discardedBytes += headerIndex - offset
                offset = headerIndex
            }
            if (buffer.size - offset < RaceFrame.HEADER_SIZE) break

            val type = RaceType.fromValue(buffer[offset + 1].toInt() and 0xFF)
            val length = readUInt16Le(buffer, offset + 2)
            val total = RaceFrame.HEADER_SIZE + length
            val lengthIsValid = length >= RaceFrame.COMMAND_ID_SIZE &&
                total <= RaceFrame.MAX_FRAME_SIZE

            if (type == null || !lengthIsValid) {
                discardedBytes++
                offset++
                continue
            }
            if (buffer.size - offset < total) break

            val frame = RaceCodec.tryDecode(buffer.copyOfRange(offset, offset + total))
            if (frame == null) {
                discardedBytes++
                offset++
                continue
            }

            frames += frame
            offset += total
        }

        buffer = buffer.copyOfRange(offset, buffer.size)
        return frames
    }

    /** Drops any partial frame, for example after a disconnection. */
    @Synchronized
    fun reset() {
        buffer = ByteArray(0)
    }

    private fun findHeader(bytes: ByteArray, from: Int): Int {
        var index = from
        while (index < bytes.size && RaceHead.fromValue(bytes[index].toInt() and 0xFF) == null) {
            index++
        }
        return index
    }
}
