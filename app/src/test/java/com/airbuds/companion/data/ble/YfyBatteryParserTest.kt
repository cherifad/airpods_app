package com.airbuds.companion.data.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YfyBatteryParserTest {

    @Test
    fun `parses an indication with charging flags`() {
        val status = YfyBatteryParser.parse(
            frame(
                RaceType.Indication,
                payload(right = 0x52, left = 0x4F, case = 0x80 or 64),
            ),
        )

        assertEquals(82, status?.right?.percent)
        assertFalse(status?.right?.charging ?: true)
        assertEquals(79, status?.left?.percent)
        assertEquals(64, status?.case?.percent)
        assertTrue(status?.case?.charging ?: false)
    }

    @Test
    fun `accepts a response frame`() {
        val status = YfyBatteryParser.parse(
            frame(RaceType.Response, payload(right = 10, left = 20, case = 30)),
        )

        assertEquals(10, status?.right?.percent)
        assertEquals(20, status?.left?.percent)
        assertEquals(30, status?.case?.percent)
    }

    @Test
    fun `rejects requests`() {
        assertNull(
            YfyBatteryParser.parse(
                frame(RaceType.Request, payload(right = 10, left = 20, case = 30)),
            ),
        )
    }

    @Test
    fun `rejects another command id`() {
        val battery = frame(RaceType.Indication, payload(right = 10, left = 20, case = 30))
        val other = RaceFrame(battery.type, RaceIds.TWS_GET_BATTERY, battery.payload)

        assertNull(YfyBatteryParser.parse(other))
    }

    @Test
    fun `rejects another subcommand`() {
        val payload = payload(right = 10, left = 20, case = 30).also { it[0] = 0x09 }

        assertNull(
            YfyBatteryParser.parse(RaceFrame(RaceType.Indication, YfyBatteryParser.COMMAND_ID, payload)),
        )
    }

    @Test
    fun `rejects a short payload`() {
        assertNull(
            YfyBatteryParser.parse(
                RaceFrame(
                    RaceType.Indication,
                    YfyBatteryParser.COMMAND_ID,
                    byteArrayOf(0x0D, 0, 10, 20),
                ),
            ),
        )
    }

    @Test
    fun `reports an out-of-range percentage as unknown`() {
        val status = YfyBatteryParser.parse(
            frame(RaceType.Indication, payload(right = 101, left = 0, case = 0)),
        )

        assertNull(status?.right?.percent)
        assertFalse(status?.right?.isKnown ?: true)
        assertEquals(0, status?.left?.percent)
    }

    private fun frame(type: RaceType, payload: ByteArray): RaceFrame =
        RaceFrame(type, YfyBatteryParser.COMMAND_ID, payload)

    private fun payload(right: Int, left: Int, case: Int): ByteArray = byteArrayOf(
        YfyBatteryParser.BATTERY_SUBCOMMAND.toByte(),
        0,
        right.toByte(),
        left.toByte(),
        case.toByte(),
        0,
    )
}
