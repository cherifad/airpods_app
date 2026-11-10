package com.airbuds.companion.data.ble

import kotlinx.coroutines.test.runTest
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
        assertEquals(82, snapshot.battery.agentPercent)
        assertEquals(79, snapshot.battery.partnerPercent)
        assertEquals(
            listOf(
                RaceIds.NVKEY_READ,
                RaceIds.NVKEY_READ,
                RaceIds.NVKEY_READ,
                RaceIds.GET_ROLE,
                RaceIds.GET_AVAILABLE_DESTINATIONS,
                RaceIds.TWS_GET_BATTERY,
                RaceIds.RELAY_PASS_TO_DST,
            ),
            executor.requests.map { it.commandId },
        )
    }

    @Test
    fun `does not read the partner without a peer`() = runTest {
        val executor = FakeExecutor(localLevel = 55, peerId = null)

        val battery = BudsSession(executor).readBattery(executor.initialize())

        assertEquals(55, battery.agentPercent)
        assertNull(battery.partnerPercent)
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

        val battery = BudsSession(executor).readBattery(executor.initialize())

        assertNull(battery.agentPercent)
        assertEquals(79, battery.partnerPercent)
    }

    @Test
    fun `keeps the agent level when the partner read fails`() = runTest {
        val executor = FakeExecutor(
            localLevel = 82,
            partnerLevel = 79,
            peerId = 9,
            failPartner = true,
        )

        val battery = BudsSession(executor).readBattery(executor.initialize())

        assertEquals(82, battery.agentPercent)
        assertNull(battery.partnerPercent)
    }

    @Test
    fun `reports an out-of-range level as unknown`() = runTest {
        val executor = FakeExecutor(localLevel = 200, peerId = null)

        val battery = BudsSession(executor).readBattery(executor.initialize())

        assertNull(battery.agentPercent)
    }

    private class FakeExecutor(
        private val localLevel: Int,
        private val partnerLevel: Int = 0,
        private val peerId: Int? = null,
        private val failLocal: Boolean = false,
        private val failPartner: Boolean = false,
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
