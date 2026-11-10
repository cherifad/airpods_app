package com.airbuds.companion.data.ble

import kotlinx.coroutines.test.runTest
import com.airbuds.companion.model.AncMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BudsSessionTest {

    @Test
    fun `handshake returns device info and both battery levels`() = runTest {
        val executor = FakeExecutor(localLevel = 82, partnerLevel = 79, peerId = 9)

        val snapshot = BudsSession(executor).handshake()

        assertEquals("AB1565", snapshot.deviceInfo.chipName)
        assertEquals(9, snapshot.deviceInfo.peerId)
        assertEquals(true, snapshot.agentIsRight)
        assertEquals(79, snapshot.battery.leftPercent)
        assertEquals(82, snapshot.battery.rightPercent)
        assertEquals(
            listOf(
                RaceIds.NVKEY_READ,
                RaceIds.NVKEY_READ,
                RaceIds.NVKEY_READ,
                RaceIds.GET_ROLE,
                RaceIds.GET_AVAILABLE_DESTINATIONS,
                RaceIds.NVKEY_READ,
                RaceIds.TWS_GET_BATTERY,
                RaceIds.RELAY_PASS_TO_DST,
            ),
            executor.requests.map { it.commandId },
        )
    }

    @Test
    fun `maps the agent to the left when the audio channel says so`() = runTest {
        val executor = FakeExecutor(
            localLevel = 82,
            partnerLevel = 79,
            peerId = 9,
            audioChannelByte = 1,
        )

        val snapshot = BudsSession(executor).handshake()

        assertEquals(false, snapshot.agentIsRight)
        assertEquals(82, snapshot.battery.leftPercent)
        assertEquals(79, snapshot.battery.rightPercent)
    }

    @Test
    fun `does not read the partner without a peer`() = runTest {
        val executor = FakeExecutor(localLevel = 55, peerId = null, audioChannelByte = 1)

        val battery = BudsSession(executor).readBattery(
            executor.initialize(),
            agentIsRight = false,
        )

        assertEquals(55, battery.leftPercent)
        assertNull(battery.rightPercent)
        assertEquals(1, executor.requests.count { it.commandId == RaceIds.TWS_GET_BATTERY })
    }

    @Test
    fun `keeps the partner level when the agent read fails`() = runTest {
        val executor = FakeExecutor(
            localLevel = 82,
            partnerLevel = 79,
            peerId = 9,
            failLocal = true,
        )

        val battery = BudsSession(executor).readBattery(
            executor.initialize(),
            agentIsRight = true,
        )

        assertNull(battery.rightPercent)
        assertEquals(79, battery.leftPercent)
    }

    @Test
    fun `keeps the agent level when the partner read fails`() = runTest {
        val executor = FakeExecutor(
            localLevel = 82,
            partnerLevel = 79,
            peerId = 9,
            failPartner = true,
        )

        val battery = BudsSession(executor).readBattery(
            executor.initialize(),
            agentIsRight = false,
        )

        assertEquals(82, battery.leftPercent)
        assertNull(battery.rightPercent)
    }

    @Test
    fun `reports an out-of-range level as unknown`() = runTest {
        val executor = FakeExecutor(localLevel = 200, peerId = null, audioChannelByte = 1)

        val battery = BudsSession(executor).readBattery(
            executor.initialize(),
            agentIsRight = false,
        )

        assertNull(battery.leftPercent)
    }

    @Test
    fun `reports unknown when the audio channel cannot be read`() = runTest {
        val executor = FakeExecutor(localLevel = 82, peerId = null, audioChannelByte = null)

        val snapshot = BudsSession(executor).handshake()

        assertNull(snapshot.agentIsRight)
        assertNull(snapshot.battery.leftPercent)
    }

    @Test
    fun `reads the ANC state when available`() = runTest {
        val executor = FakeExecutor(localLevel = 50, peerId = null, ancFilter = 2)

        val state = BudsSession(executor).readAncState()

        assertEquals(2, state?.filter)
        assertEquals(AncMode.NoiseCancelling, state?.mode)
    }

    @Test
    fun `returns null when the firmware does not answer the ANC read`() = runTest {
        val executor = FakeExecutor(localLevel = 50, peerId = null)

        assertNull(BudsSession(executor).readAncState())
    }

    private class FakeExecutor(
        private val localLevel: Int,
        private val partnerLevel: Int = 0,
        private val peerId: Int? = null,
        private val failLocal: Boolean = false,
        private val failPartner: Boolean = false,
        private val ancFilter: Int? = null,
        private val audioChannelByte: Int? = 2,
    ) : RaceCommandExecutor {

        val requests = mutableListOf<RaceFrame>()

        override suspend fun execute(request: RaceFrame): RaceFrame {
            requests += request
            return when (request.commandId) {
                RaceIds.NVKEY_READ -> {
                    when (val nvkeyId = readUInt16Le(request.payload, 0)) {
                        RaceIds.NVKEY_CHIP_NAME ->
                            nvkeyResponse("AB1565".toByteArray(Charsets.US_ASCII))

                        RaceIds.NVKEY_DEVICE_TYPE_PRIMARY,
                        RaceIds.NVKEY_DEVICE_TYPE_FALLBACK,
                        -> nvkeyResponse(byteArrayOf(1, *ByteArray(38), 0))

                        RaceIds.NVKEY_NVDM_VERSION -> nvkeyResponse(byteArrayOf(0x0C))

                        RaceIds.NVKEY_AUDIO_CHANNEL -> {
                            val value = audioChannelByte
                                ?: throw GattException("audio channel unavailable")
                            nvkeyResponse(byteArrayOf(0, value.toByte()))
                        }

                        else -> error("Unexpected NVKEY 0x${nvkeyId.toString(16)}")
                    }
                }

                RaceIds.GET_ROLE -> RaceFrame(
                    RaceType.Response,
                    RaceIds.GET_ROLE,
                    byteArrayOf(0, 1),
                )

                RaceIds.GET_AVAILABLE_DESTINATIONS -> RaceFrame(
                    RaceType.Response,
                    RaceIds.GET_AVAILABLE_DESTINATIONS,
                    peerId?.let { byteArrayOf(RaceIds.DESTINATION_TYPE_AWSPEER.toByte(), it.toByte()) }
                        ?: ByteArray(0),
                )

                RaceIds.MMI_GET_STATUS -> {
                    val filter = ancFilter
                        ?: error("ANC state not configured for this fake")
                    RaceFrame(
                        RaceType.Response,
                        RaceIds.MMI_GET_STATUS,
                        byteArrayOf(0x05, 0x00, 0x00, filter.toByte(), 0x64, 0x00),
                    )
                }

                RaceIds.TWS_GET_BATTERY -> {
                    if (failLocal) throw GattException("local battery failed")
                    RaceFrame(
                        RaceType.Response,
                        RaceIds.TWS_GET_BATTERY,
                        byteArrayOf(0, 0, localLevel.toByte()),
                    )
                }

                RaceIds.RELAY_PASS_TO_DST -> {
                    if (failPartner) throw GattException("partner battery failed")
                    val inner = RaceFrame(
                        RaceType.Response,
                        RaceIds.TWS_GET_BATTERY,
                        byteArrayOf(0, 0, partnerLevel.toByte()),
                    )
                    RaceFrame(
                        RaceType.Response,
                        RaceIds.RELAY_PASS_TO_DST,
                        byteArrayOf(
                            RaceIds.DESTINATION_TYPE_AWSPEER.toByte(),
                            request.payload[1],
                        ) + RaceCodec.encode(inner),
                    )
                }

                else -> error("Unexpected command 0x${request.commandId.toString(16)}")
            }
        }

        suspend fun initialize(): RaceDeviceInfo = BudsSession(this).initialize()
    }
}

private fun nvkeyResponse(data: ByteArray): RaceFrame {
    val payload = ByteArray(2 + data.size)
    writeUInt16Le(payload, 0, data.size)
    data.copyInto(payload, destinationOffset = 2)
    return RaceFrame(RaceType.Response, RaceIds.NVKEY_READ, payload)
}
