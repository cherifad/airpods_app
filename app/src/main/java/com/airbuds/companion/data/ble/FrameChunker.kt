package com.airbuds.companion.data.ble

/**
 * Splits encoded RACE bytes into GATT-sized writes.
 *
 * A race frame can exceed the negotiated ATT payload, in which case the
 * firmware reassembles the fragments in order. An empty write produces no
 * chunk at all.
 */
internal object FrameChunker {

    fun chunk(bytes: ByteArray, maxChunkSize: Int): List<ByteArray> {
        require(maxChunkSize > 0) { "maxChunkSize must be positive" }
        if (bytes.isEmpty()) return emptyList()

        val chunks = ArrayList<ByteArray>((bytes.size + maxChunkSize - 1) / maxChunkSize)
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + maxChunkSize, bytes.size)
            chunks += bytes.copyOfRange(offset, end)
            offset = end
        }
        return chunks
    }
}
