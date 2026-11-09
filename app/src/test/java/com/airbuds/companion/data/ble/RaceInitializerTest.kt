package com.airbuds.companion.data.ble

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceInitializerTest {

    @Test
    fun `runs the sequence and parses a stereo device`() = runTest {
        val executor = FakeExecutor(
            chipName = "AB1565",
            deviceTypeData = buildDeviceTypeData(type = 1, awsMode = 0),
            nvdmVersion = byteArrayOf(0x0C),
            rolePayload = byteArrayOf(0, 1),
            destinations = byteArrayOf(0, 0, 2, 7, 5, 9),
        )

        val info = RaceInitializer(executor).initialize()

        assertEquals("AB1565", info.chipName)
        assertEquals(AirohaDeviceType.Earbuds, info.deviceType)
        assertEquals(0x0C, info.nvdmVersion)
        assertEquals(TwsRole.Master, info.role)
        assertEquals(9, info.peerId)
        assertTrue(info.hasPeer)
        assertEquals(
            listOf(0x0A00, 0x0A00, 0x0A00, 0x0CC4, 0x0D00),
            executor.requests.map { it.commandId },
        )
        assertEquals(listOf(0x1002, 0xF2B0, 0x1001), executor.nvkeyIds)
    }

    @Test
    fun `uses the 183D NVKEY for newer chips`() = runTest {
        val executor = FakeExecutor(chipName = "AB1585")

        RaceInitializer(executor).initialize()

        assertEquals(listOf(0x1002, 0x183D, 0x1001), executor.nvkeyIds)
    }

    @Test
    fun `uses the F2B0 NVKEY for a plain AB1565`() {
        assertFalse(RaceInitializer.usesPrimaryDeviceTypeNvkey("AB1565"))
    }

    @Test
    fun `selects the device type NVKEY from the chip family`() {
        assertTrue(RaceInitializer.usesPrimaryDeviceTypeNvkey("AB1585"))
        assertTrue(RaceInitializer.usesPrimaryDeviceTypeNvkey("AB1571"))
        assertTrue(RaceInitializer.usesPrimaryDeviceTypeNvkey("AB1568_V3"))
        assertTrue(RaceInitializer.usesPrimaryDeviceTypeNvkey("AB1565_DUAL_V3"))
        assertTrue(RaceInitializer.usesPrimaryDeviceTypeNvkey("AB1568_DUAL_V3"))
        assertFalse(RaceInitializer.usesPrimaryDeviceTypeNvkey("AB1568"))
    }

    @Test
    fun `interprets type and AWS mode as a form factor`() {
        assertEquals(
            AirohaDeviceType.Headset,
            RaceInitializer.parseDeviceType(buildDeviceTypeData(type = 0, awsMode = 2)),
        )
        assertEquals(
            AirohaDeviceType.Earbuds,
            RaceInitializer.parseDeviceType(buildDeviceTypeData(type = 1, awsMode = 0)),
        )
        assertEquals(
            AirohaDeviceType.Earbuds,
            RaceInitializer.parseDeviceType(buildDeviceTypeData(type = 1, awsMode = 2)),
        )
        assertEquals(
            AirohaDeviceType.Headset,
            RaceInitializer.parseDeviceType(buildDeviceTypeData(type = 1, awsMode = 1)),
        )
        assertEquals(
            AirohaDeviceType.Headset,
            RaceInitializer.parseDeviceType(buildDeviceTypeData(type = 1, awsMode = null)),
        )
    }

    @Test
    fun `rejects an empty device type payload`() {
        assertThrows(RaceInitializationException::class.java) {
            RaceInitializer.parseDeviceType(ByteArray(0))
        }
    }

    @Test
    fun `maps the low role values`() {
        assertEquals(TwsRole.Normal, RaceInitializer.parseRole(byteArrayOf(0, 0)))
        assertEquals(TwsRole.Master, RaceInitializer.parseRole(byteArrayOf(0, 1)))
        assertEquals(TwsRole.Slave, RaceInitializer.parseRole(byteArrayOf(0, 2)))
        assertEquals(TwsRole.Unknown(9), RaceInitializer.parseRole(byteArrayOf(0, 9)))
    }

    @Test
    fun `rejects a role response with a non-zero status`() {
        assertThrows(RaceInitializationException::class.java) {
            RaceInitializer.parseRole(byteArrayOf(1, 0))
        }
    }

    @Test
    fun `rejects a truncated role response`() {
        assertThrows(RaceInitializationException::class.java) {
            RaceInitializer.parseRole(byteArrayOf(0))
        }
    }

    @Test
    fun `treats a failed destination listing as solo`() = runTest {
        val executor = FakeExecutor(failDestinations = true)

        val info = RaceInitializer(executor).initialize()

        assertNull(info.peerId)
        assertFalse(info.hasPeer)
        assertEquals("AB1565", info.chipName)
    }

    @Test
    fun `returns no peer when no AWSPEER destination is listed`() = runTest {
        val executor = FakeExecutor(destinations = byteArrayOf(0, 0, 2, 7))

        val info = RaceInitializer(executor).initialize()

        assertNull(info.peerId)
    }

    @Test
    fun `ignores an incomplete destination pair`() {
        assertNull(RaceInitializer.parsePeerId(byteArrayOf(5)))
        assertNull(RaceInitializer.parsePeerId(byteArrayOf(2, 7, 5)))
    }

    @Test
    fun `retries an invalid chip name before succeeding`() = runTest {
        val executor = FakeExecutor(invalidChipResponses = 2, chipName = "AB1565")

        val info = RaceInitializer(executor).initialize()

        assertEquals("AB1565", info.chipName)
        assertEquals(3, executor.chipAttempts)
    }

    @Test
    fun `fails after the configured number of invalid chip names`() = runTest {
        val executor = FakeExecutor(invalidChipResponses = 3)

        var error: RaceInitializationException? = null
        try {
            RaceInitializer(executor).initialize()
        } catch (exception: RaceInitializationException) {
            error = exception
        }

        assertNotNull(error)
        assertEquals(3, executor.chipAttempts)
        assertEquals(3, executor.requests.size)
    }

    @Test
    fun `does not list destinations when the role step fails`() = runTest {
        val executor = FakeExecutor(rolePayload = byteArrayOf(1, 0))

        var error: RaceInitializationException? = null
        try {
            RaceInitializer(executor).initialize()
        } catch (exception: RaceInitializationException) {
            error = exception
        }

        assertNotNull(error)
        assertEquals(listOf(0x0A00, 0x0A00, 0x0A00, 0x0CC4), executor.requests.map { it.commandId })
    }

    private class FakeExecutor(
        private val chipName: String = "AB1565",
        private val deviceTypeData: ByteArray = buildDeviceTypeData(type = 1, awsMode = 0),
        private val nvdmVersion: ByteArray = byteArrayOf(0x0C),
        private val rolePayload: ByteArray = byteArrayOf(0, 1),
        private val destinations: ByteArray = byteArrayOf(5, 9),
        private val failDestinations: Boolean = false,
        private val invalidChipResponses: Int = 0,
    ) : RaceCommandExecutor {

        val requests = mutableListOf<RaceFrame>()
        var chipAttempts = 0
            private set

        val nvkeyIds: List<Int>
            get() = requests
                .filter { it.commandId == RaceIds.NVKEY_READ }
                .map { readUInt16Le(it.payload, 0) }

        override suspend fun execute(request: RaceFrame): RaceFrame {
            requests += request
            return when (request.commandId) {
                RaceIds.NVKEY_READ -> {
                    when (val nvkeyId = readUInt16Le(request.payload, 0)) {
                        RaceIds.NVKEY_CHIP_NAME -> {
                            chipAttempts++
                            if (chipAttempts <= invalidChipResponses) {
                                buildNvkeyResponse(ByteArray(0))
                            } else {
                                buildNvkeyResponse(chipName.toByteArray(Charsets.US_ASCII))
                            }
                        }

                        RaceIds.NVKEY_DEVICE_TYPE_PRIMARY,
                        RaceIds.NVKEY_DEVICE_TYPE_FALLBACK,
                        -> buildNvkeyResponse(deviceTypeData)

                        RaceIds.NVKEY_NVDM_VERSION -> buildNvkeyResponse(nvdmVersion)

                        else -> error("Unexpected NVKEY 0x${nvkeyId.toString(16)}")
                    }
                }

                RaceIds.GET_ROLE -> RaceFrame(
                    RaceType.Response,
                    RaceIds.GET_ROLE,
                    rolePayload,
                )

                RaceIds.GET_AVAILABLE_DESTINATIONS -> {
                    if (failDestinations) throw GattException("Destination listing failed")
                    RaceFrame(
                        RaceType.Response,
                        RaceIds.GET_AVAILABLE_DESTINATIONS,
                        destinations,
                    )
                }

                else -> error("Unexpected command 0x${request.commandId.toString(16)}")
            }
        }
    }
}

private fun buildNvkeyResponse(data: ByteArray): RaceFrame {
    val payload = ByteArray(2 + data.size)
    writeUInt16Le(payload, 0, data.size)
    data.copyInto(payload, destinationOffset = 2)
    return RaceFrame(RaceType.Response, RaceIds.NVKEY_READ, payload)
}

private fun buildDeviceTypeData(type: Int, awsMode: Int?): ByteArray {
    if (awsMode == null) return byteArrayOf(type.toByte())

    val data = ByteArray(40)
    data[0] = type.toByte()
    data[39] = awsMode.toByte()
    return data
}
