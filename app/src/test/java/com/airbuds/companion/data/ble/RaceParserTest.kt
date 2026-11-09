package com.airbuds.companion.data.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceParserTest {

    private val first = RaceFrame(
        type = RaceType.Response,
        commandId = 0x0CD6,
        payload = byteArrayOf(0x01, 0x02),
    )
    private val second = RaceFrame(
        type = RaceType.Indication,
        commandId = 0x5D,
        payload = byteArrayOf(0x0A, 0x0B, 0x0C),
    )

    @Test
    fun `parses one complete frame`() {
        val parser = RaceParser()

        assertEquals(listOf(first), parser.feed(RaceCodec.encode(first)))
        assertEquals(0, parser.discardedBytes)
    }

    @Test
    fun `reassembles a frame split into single-byte chunks`() {
        val parser = RaceParser()
        val frames = mutableListOf<RaceFrame>()

        for (byte in RaceCodec.encode(first)) {
            frames += parser.feed(byteArrayOf(byte))
        }

        assertEquals(listOf(first), frames)
        assertEquals(0, parser.discardedBytes)
    }

    @Test
    fun `reassembles a frame from arbitrary fragment boundaries`() {
        val bytes = RaceCodec.encode(first)
        val parser = RaceParser()
        val frames = mutableListOf<RaceFrame>()

        for (index in bytes.indices) {
            frames += parser.feed(bytes.copyOfRange(index, index + 1))
            frames += parser.feed(byteArrayOf())
        }

        assertEquals(listOf(first), frames)
    }

    @Test
    fun `parses several concatenated frames from one notification`() {
        val parser = RaceParser()

        val frames = parser.feed(RaceCodec.encode(first) + RaceCodec.encode(second))

        assertEquals(listOf(first, second), frames)
    }

    @Test
    fun `parses a frame followed by the beginning of the next one`() {
        val parser = RaceParser()
        val secondBytes = RaceCodec.encode(second)

        assertEquals(listOf(first), parser.feed(RaceCodec.encode(first) + secondBytes.copyOfRange(0, 3)))
        assertEquals(listOf(second), parser.feed(secondBytes.copyOfRange(3, secondBytes.size)))
    }

    @Test
    fun `skips leading garbage and counts it`() {
        val parser = RaceParser()

        val frames = parser.feed(byteArrayOf(0x00, 0x01, 0x02, 0x03) + RaceCodec.encode(first))

        assertEquals(listOf(first), frames)
        assertEquals(4, parser.discardedBytes)
    }

    @Test
    fun `resynchronizes after a zero-length header`() {
        val parser = RaceParser()

        val frames = parser.feed(byteArrayOf(0x05, 0x5A, 0x00, 0x00) + RaceCodec.encode(first))

        assertEquals(listOf(first), frames)
        assertEquals(4, parser.discardedBytes)
    }

    @Test
    fun `resynchronizes after a length above the SDK limit`() {
        val parser = RaceParser()

        val frames = parser.feed(byteArrayOf(0x05, 0x5A, 0xFF.toByte(), 0x07) + RaceCodec.encode(second))

        assertEquals(listOf(second), frames)
        assertEquals(4, parser.discardedBytes)
    }

    @Test
    fun `resynchronizes after an unknown frame type`() {
        val parser = RaceParser()

        val frames = parser.feed(byteArrayOf(0x05, 0x5F, 0x02, 0x00, 0x00, 0x0A) + RaceCodec.encode(first))

        assertEquals(listOf(first), frames)
        assertEquals(6, parser.discardedBytes)
    }

    @Test
    fun `keeps an incomplete frame buffered`() {
        val parser = RaceParser()
        val bytes = RaceCodec.encode(first)

        assertEquals(emptyList<RaceFrame>(), parser.feed(bytes.copyOfRange(0, bytes.size - 1)))
        assertTrue(parser.discardedBytes == 0L)
        assertEquals(listOf(first), parser.feed(bytes.copyOfRange(bytes.size - 1, bytes.size)))
    }

    @Test
    fun `reset drops a partial frame`() {
        val parser = RaceParser()
        val bytes = RaceCodec.encode(first)

        parser.feed(bytes.copyOfRange(0, 3))
        parser.reset()

        assertEquals(listOf(first), parser.feed(bytes))
    }
}
