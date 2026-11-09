package com.airbuds.companion.data.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RaceCodecTest {

    @Test
    fun `encodes the NVKEY read request from the protocol example`() {
        val frame = RaceFrame(
            type = RaceType.Request,
            commandId = 0x0A00,
            payload = byteArrayOf(0x01, 0x10, 0xE8.toByte(), 0x03),
        )

        val encoded = RaceCodec.encode(frame)

        assertArrayEquals(
            byteArrayOf(
                0x05, 0x5A, 0x06, 0x00,
                0x00, 0x0A,
                0x01, 0x10, 0xE8.toByte(), 0x03,
            ),
            encoded,
        )
    }

    @Test
    fun `decodes the NVKEY response from the protocol example`() {
        val frame = RaceCodec.decode(
            byteArrayOf(
                0x05, 0x5B, 0x05, 0x00,
                0x00, 0x0A,
                0x01, 0x00, 0x0C,
            ),
        )

        assertEquals(RaceHead.Mmi, frame.head)
        assertEquals(RaceType.Response, frame.type)
        assertEquals(0x0A00, frame.commandId)
        assertArrayEquals(byteArrayOf(0x01, 0x00, 0x0C), frame.payload)
        assertEquals(5, frame.length)
        assertEquals(9, frame.size)
    }

    @Test
    fun `round-trips an empty payload`() {
        val frame = RaceFrame(RaceType.Request, 0x0CD6)

        val decoded = RaceCodec.decode(RaceCodec.encode(frame))

        assertEquals(frame, decoded)
        assertEquals(RaceFrame.EMPTY_PAYLOAD.size, decoded.payload.size)
    }

    @Test
    fun `accepts a FOTA frame`() {
        val frame = RaceFrame(RaceType.Request, 0x2C00, head = RaceHead.Fota)

        assertEquals(frame, RaceCodec.decode(RaceCodec.encode(frame)))
    }

    @Test
    fun `decodes a frame with the maximum payload size`() {
        val frame = RaceFrame(RaceType.Response, 0x0CD6, ByteArray(RaceFrame.MAX_PAYLOAD_SIZE))

        val encoded = RaceCodec.encode(frame)

        assertEquals(RaceFrame.MAX_FRAME_SIZE, encoded.size)
        assertEquals(frame, RaceCodec.decode(encoded))
    }

    @Test
    fun `rejects a frame whose payload would exceed the maximum`() {
        assertThrows(IllegalArgumentException::class.java) {
            RaceFrame(RaceType.Request, 0x0CD6, ByteArray(RaceFrame.MAX_PAYLOAD_SIZE + 1))
        }
    }

    @Test
    fun `rejects a command id that does not fit in 16 bits`() {
        assertThrows(IllegalArgumentException::class.java) {
            RaceFrame(RaceType.Request, 0x1_0000)
        }
    }

    @Test
    fun `rejects an unknown head`() {
        assertNull(
            RaceCodec.tryDecode(byteArrayOf(0x07, 0x5A, 0x02, 0x00, 0x00, 0x0A)),
        )
    }

    @Test
    fun `rejects an unknown type`() {
        assertNull(
            RaceCodec.tryDecode(byteArrayOf(0x05, 0x5F, 0x02, 0x00, 0x00, 0x0A)),
        )
    }

    @Test
    fun `rejects a zero length`() {
        assertNull(
            RaceCodec.tryDecode(byteArrayOf(0x05, 0x5A, 0x00, 0x00)),
        )
    }

    @Test
    fun `rejects a truncated frame`() {
        assertThrows(RaceProtocolException::class.java) {
            RaceCodec.decode(byteArrayOf(0x05, 0x5A, 0x06, 0x00, 0x00, 0x0A, 0x01))
        }
    }

    @Test
    fun `rejects trailing bytes`() {
        val frame = RaceCodec.encode(RaceFrame(RaceType.Request, 0x0CD6))

        assertThrows(RaceProtocolException::class.java) {
            RaceCodec.decode(frame + byteArrayOf(0x00))
        }
    }

    @Test
    fun `rejects a length above the SDK limit`() {
        assertThrows(RaceProtocolException::class.java) {
            RaceCodec.decode(byteArrayOf(0x05, 0x5A, 0xD1.toByte(), 0x07, 0x00, 0x0A))
        }
    }
}
