package com.airbuds.companion.data.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameChunkerTest {

    @Test
    fun `splits a frame into exact chunks`() {
        val bytes = ByteArray(10) { it.toByte() }

        val chunks = FrameChunker.chunk(bytes, maxChunkSize = 5)

        assertEquals(2, chunks.size)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4), chunks[0])
        assertArrayEquals(byteArrayOf(5, 6, 7, 8, 9), chunks[1])
    }

    @Test
    fun `keeps the remainder in a smaller last chunk`() {
        val bytes = ByteArray(7) { it.toByte() }

        val chunks = FrameChunker.chunk(bytes, maxChunkSize = 3)

        assertEquals(3, chunks.size)
        assertEquals(3, chunks[0].size)
        assertEquals(3, chunks[1].size)
        assertArrayEquals(byteArrayOf(6), chunks[2])
    }

    @Test
    fun `returns the input when it already fits`() {
        val bytes = ByteArray(4) { it.toByte() }

        val chunks = FrameChunker.chunk(bytes, maxChunkSize = 20)

        assertEquals(1, chunks.size)
        assertArrayEquals(bytes, chunks[0])
    }

    @Test
    fun `does not emit a write for empty input`() {
        assertTrue(FrameChunker.chunk(ByteArray(0), maxChunkSize = 20).isEmpty())
    }

    @Test
    fun `rejects a non-positive chunk size`() {
        assertThrows(IllegalArgumentException::class.java) {
            FrameChunker.chunk(ByteArray(4), maxChunkSize = 0)
        }
    }
}
