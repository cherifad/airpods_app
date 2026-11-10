package com.airbuds.companion.data.ble

import com.airbuds.companion.model.AncMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceAncTest {

    @Test
    fun `sends the documented disable payload`() = runTest {
        val executor = FakeExecutor(status = 0)

        RaceAncController(executor).setMode(AncMode.Off)

        assertEquals(RaceIds.ANC_CONTROL, executor.requests.single().commandId)
        assertEquals("00 0B 01", executor.requests.single().payload.toHex())
    }

    @Test
    fun `enables noise cancelling with filter 1 and hybrid mode`() = runTest {
        val executor = FakeExecutor(status = 0)

        RaceAncController(executor).setMode(AncMode.NoiseCancelling)

        assertEquals("00 0A 01 00 01", executor.requests.single().payload.toHex())
    }

    @Test
    fun `enables transparency with filter 9 and pass-through mode`() = runTest {
        val executor = FakeExecutor(status = 0)

        RaceAncController(executor).setMode(AncMode.Transparency)

        assertEquals("00 0A 09 04 01", executor.requests.single().payload.toHex())
    }

    @Test
    fun `writes the pass-through gain little-endian`() = runTest {
        val executor = FakeExecutor(status = 0)

        RaceAncController(executor).setPassThroughGain(0x04D2)

        assertEquals("00 0F D2 04 01", executor.requests.single().payload.toHex())
    }

    @Test
    fun `rejects a gain that does not fit in 16 bits`() = runTest {
        val error = runCatching {
            RaceAncController(FakeExecutor(status = 0)).setPassThroughGain(0x10000)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `rejects a non-zero control status`() = runTest {
        val error = runCatching {
            RaceAncController(FakeExecutor(status = 2)).setMode(AncMode.Off)
        }.exceptionOrNull()

        assertTrue(error is RaceAncException)
    }

    @Test
    fun `reads the ANC state`() = runTest {
        val executor = FakeExecutor(statePayload = statePayload(filter = 2, gain = 0x0960))

        val state = RaceAncController(executor).readState()

        assertEquals(RaceIds.MMI_GET_STATUS, executor.requests.single().commandId)
        assertEquals("05 00", executor.requests.single().payload.toHex())
        assertEquals(2, state.filter)
        assertEquals(0x0960, state.gainHundredths)
        assertEquals(AncMode.NoiseCancelling, state.mode)
    }

    @Test
    fun `maps filter ranges to modes`() {
        assertEquals(AncMode.Off, AncState(filter = 0, gainHundredths = 100).mode)
        assertEquals(AncMode.NoiseCancelling, AncState(filter = 4, gainHundredths = 100).mode)
        assertEquals(AncMode.Transparency, AncState(filter = 9, gainHundredths = 100).mode)
        assertEquals(AncMode.Transparency, AncState(filter = 11, gainHundredths = 100).mode)
        assertNull(AncState(filter = 5, gainHundredths = 100).mode)
    }

    @Test
    fun `rejects a failed state read`() {
        assertThrows(RaceAncException::class.java) {
            RaceAncController.parseState(statePayload(filter = 1, gain = 100, result = 3))
        }
    }

    @Test
    fun `defaults the gain when the payload is short`() {
        val state = RaceAncController.parseState(byteArrayOf(0x05, 0x00, 0x00, 0x02))

        assertEquals(2, state.filter)
        assertEquals(RaceAncController.DEFAULT_GAIN_HUNDREDTHS, state.gainHundredths)
    }

    private class FakeExecutor(
        private val status: Int = 0,
        private val statePayload: ByteArray = statePayload(filter = 0, gain = 100),
    ) : RaceCommandExecutor {
        val requests = mutableListOf<RaceFrame>()

        override suspend fun execute(request: RaceFrame): RaceFrame {
            requests += request
            val payload = if (request.commandId == RaceIds.MMI_GET_STATUS) {
                statePayload
            } else {
                byteArrayOf(status.toByte(), request.payload.getOrElse(1) { 0 })
            }
            return RaceFrame(RaceType.Response, request.commandId, payload)
        }
    }
}

private fun statePayload(filter: Int, gain: Int, result: Int = 0): ByteArray = byteArrayOf(
    0x05,
    0x00,
    result.toByte(),
    filter.toByte(),
    (gain and 0xFF).toByte(),
    ((gain shr 8) and 0xFF).toByte(),
)
