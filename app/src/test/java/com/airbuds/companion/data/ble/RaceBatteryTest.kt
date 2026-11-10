package com.airbuds.companion.data.ble

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceBatteryTest {

    @Test
    fun `reads the agent level`() = runTest {
        val executor = FakeExecutor(response = batteryPayload(level = 82))

        val battery = RaceBatteryReader(executor).readLocal()

        assertEquals(EarbudBattery(82), battery)
        assertEquals(RaceIds.TWS_GET_BATTERY, executor.requests.single().commandId)
        assertEquals("00", executor.requests.single().payload.toHex())
    }

    @Test
    fun `accepts an empty battery`() = runTest {
        val executor = FakeExecutor(response = batteryPayload(level = 0))

        assertEquals(EarbudBattery(0), RaceBatteryReader(executor).readLocal())
    }

    @Test
    fun `reports an unknown level outside 0 to 100`() = runTest {
        val executor = FakeExecutor(response = batteryPayload(level = 101))

        val battery = RaceBatteryReader(executor).readLocal()

        assertNull(battery.percent)
        assertFalse(battery.isKnown)
    }

    @Test
    fun `rejects a non-zero status`() {
        assertThrows(RaceBatteryException::class.java) {
            RaceBatteryReader.parseBatteryResponse(
                RaceFrame(RaceType.Response, RaceIds.TWS_GET_BATTERY, batteryPayload(level = 50, status = 1)),
            )
        }
    }

    @Test
    fun `rejects a truncated response`() {
        assertThrows(RaceBatteryException::class.java) {
            RaceBatteryReader.parseBatteryResponse(
                RaceFrame(RaceType.Response, RaceIds.TWS_GET_BATTERY, byteArrayOf(0, 0)),
            )
        }
    }

    @Test
    fun `rejects an unexpected command`() {
        assertThrows(RaceBatteryException::class.java) {
            RaceBatteryReader.parseBatteryResponse(
                RaceFrame(RaceType.Response, RaceIds.GET_ROLE, batteryPayload(level = 50)),
            )
        }
    }

    @Test
    fun `builds the documented relay request`() = runTest {
        val executor = FakeExecutor(response = relayPayload(peerId = 9, level = 64))

        RaceBatteryReader(executor).readPartner(peerId = 9)

        val request = executor.requests.single()
        assertEquals(RaceIds.RELAY_PASS_TO_DST, request.commandId)
        assertEquals("05 09 05 5A 03 00 D6 0C 00", request.payload.toHex())
    }

    @Test
    fun `reads the partner level from the relay response`() = runTest {
        val executor = FakeExecutor(response = relayPayload(peerId = 9, level = 64))

        val battery = RaceBatteryReader(executor).readPartner(peerId = 9)

        assertEquals(EarbudBattery(64), battery)
        assertTrue(battery.isKnown)
    }

    @Test
    fun `rejects a relay for another destination`() {
        assertThrows(RaceBatteryException::class.java) {
            RaceBatteryReader.parseRelayResponse(
                RaceFrame(
                    RaceType.Response,
                    RaceIds.RELAY_PASS_TO_DST,
                    relayPayload(peerId = 7, level = 64),
                ),
                expectedPeerId = 9,
            )
        }
    }

    @Test
    fun `rejects a relay for a non-peer destination type`() {
        val payload = relayPayload(peerId = 9, level = 64).also { it[0] = 2 }

        assertThrows(RaceBatteryException::class.java) {
            RaceBatteryReader.parseRelayResponse(
                RaceFrame(RaceType.Response, RaceIds.RELAY_PASS_TO_DST, payload),
            )
        }
    }

    @Test
    fun `rejects a relay without a complete inner frame`() {
        assertThrows(RaceBatteryException::class.java) {
            RaceBatteryReader.parseRelayResponse(
                RaceFrame(
                    RaceType.Response,
                    RaceIds.RELAY_PASS_TO_DST,
                    byteArrayOf(5, 9, 0x05, 0x5B),
                ),
            )
        }
    }

    @Test
    fun `propagates the inner battery failure`() {
        val inner = RaceFrame(
            RaceType.Response,
            RaceIds.TWS_GET_BATTERY,
            batteryPayload(level = 64, status = 2),
        )
        val payload = byteArrayOf(5, 9) + RaceCodec.encode(inner)

        assertThrows(RaceBatteryException::class.java) {
            RaceBatteryReader.parseRelayResponse(
                RaceFrame(RaceType.Response, RaceIds.RELAY_PASS_TO_DST, payload),
            )
        }
    }

    @Test
    fun `rejects an out-of-range peer id`() = runTest {
        val reader = RaceBatteryReader(FakeExecutor(response = batteryPayload(level = 50)))

        val error = try {
            reader.readPartner(peerId = 256)
            null
        } catch (exception: IllegalArgumentException) {
            exception
        }

        assertTrue(error is IllegalArgumentException)
    }

    private class FakeExecutor(private val response: ByteArray) : RaceCommandExecutor {
        val requests = mutableListOf<RaceFrame>()

        override suspend fun execute(request: RaceFrame): RaceFrame {
            requests += request
            return RaceFrame(RaceType.Response, request.commandId, response)
        }
    }
}

/** `status, agentOrClient, level` as returned by `0x0CD6`. */
private fun batteryPayload(level: Int, status: Int = 0): ByteArray =
    byteArrayOf(status.toByte(), 0, level.toByte())

/** `type, destinationId, <inner 0x0CD6 response>` as returned by `0x0D01`. */
private fun relayPayload(peerId: Int, level: Int): ByteArray {
    val inner = RaceFrame(
        RaceType.Response,
        RaceIds.TWS_GET_BATTERY,
        batteryPayload(level = level),
    )
    return byteArrayOf(RaceIds.DESTINATION_TYPE_AWSPEER.toByte(), peerId.toByte()) +
        RaceCodec.encode(inner)
}
