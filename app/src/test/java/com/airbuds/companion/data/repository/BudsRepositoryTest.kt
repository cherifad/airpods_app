package com.airbuds.companion.data.repository

import com.airbuds.companion.data.ble.BudsSession
import com.airbuds.companion.data.ble.GattException
import com.airbuds.companion.data.ble.RaceBatteryException
import com.airbuds.companion.data.ble.RaceCodec
import com.airbuds.companion.data.ble.RaceCommandExecutor
import com.airbuds.companion.data.ble.RaceCommandTimeoutException
import com.airbuds.companion.data.ble.RaceFrame
import com.airbuds.companion.data.ble.RaceIds
import com.airbuds.companion.data.ble.RaceType
import com.airbuds.companion.data.ble.readUInt16Le
import com.airbuds.companion.data.ble.writeUInt16Le
import com.airbuds.companion.domain.model.BudsConnection
import com.airbuds.companion.domain.model.BudsError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BudsRepositoryTest {

    @Test
    fun `attach initializes and publishes the snapshot`() = runTest {
        val repository = BudsRepository(BudsSession(FakeExecutor()))

        repository.attach("AA:BB:CC:DD:EE:FF")

        assertEquals(BudsConnection.Ready("AA:BB:CC:DD:EE:FF"), repository.connection.value)
        val snapshot = repository.snapshot.value
        assertEquals("AB1565", snapshot?.deviceInfo?.chipName)
        assertEquals(82, snapshot?.battery?.agentPercent)
        assertEquals(79, snapshot?.battery?.partnerPercent)
    }

    @Test
    fun `attach exposes a protocol error and clears the snapshot`() = runTest {
        val repository = BudsRepository(
            BudsSession(FakeExecutor(rolePayload = byteArrayOf(1, 0))),
        )

        repository.attach("AA:BB")

        val state = repository.connection.value
        assertTrue(state is BudsConnection.Failed)
        assertTrue((state as BudsConnection.Failed).error is BudsError.Protocol)
        assertNull(repository.snapshot.value)
    }

    @Test
    fun `maps a command timeout`() = runTest {
        val repository = BudsRepository(
            BudsSession(
                FakeExecutor(
                    failCommand = RaceIds.GET_ROLE,
                    failure = RaceCommandTimeoutException(RaceIds.GET_ROLE, 3),
                ),
            ),
        )

        repository.attach("AA:BB")

        val state = repository.connection.value as BudsConnection.Failed
        assertEquals(BudsError.Timeout, state.error)
    }

    @Test
    fun `maps a GATT failure`() = runTest {
        val repository = BudsRepository(
            BudsSession(
                FakeExecutor(
                    failCommand = RaceIds.GET_ROLE,
                    failure = GattException("Bluetooth is disabled"),
                ),
            ),
        )

        repository.attach("AA:BB")

        val state = repository.connection.value as BudsConnection.Failed
        assertEquals(BudsError.BluetoothDisabled, state.error)
    }

    @Test
    fun `refreshBattery updates only the battery`() = runTest {
        val executor = FakeExecutor()
        val repository = BudsRepository(BudsSession(executor))
        repository.attach("AA:BB")
        executor.localLevel = 42
        executor.partnerLevel = 41

        repository.refreshBattery()

        assertEquals(42, repository.snapshot.value?.battery?.agentPercent)
        assertEquals(41, repository.snapshot.value?.battery?.partnerPercent)
        assertEquals(BudsConnection.Ready("AA:BB"), repository.connection.value)
    }

    @Test
    fun `refreshBattery does nothing before attach`() = runTest {
        val repository = BudsRepository(BudsSession(FakeExecutor()))

        repository.refreshBattery()

        assertNull(repository.snapshot.value)
        assertEquals(BudsConnection.Disconnected, repository.connection.value)
    }

    @Test
    fun `detach resets state and snapshot`() = runTest {
        val repository = BudsRepository(BudsSession(FakeExecutor()))
        repository.attach("AA:BB")

        repository.detach()

        assertEquals(BudsConnection.Disconnected, repository.connection.value)
        assertNull(repository.snapshot.value)
    }

    @Test
    fun `markConnecting publishes the address`() {
        val repository = BudsRepository(BudsSession(FakeExecutor()))

        repository.markConnecting("AA:BB")

        assertEquals(BudsConnection.Connecting("AA:BB"), repository.connection.value)
    }

    private class FakeExecutor(        private val rolePayload: ByteArray = byteArrayOf(0, 1),
        private val failCommand: Int? = null,
        private val failure: Exception = RaceBatteryException("failed"),
    ) : RaceCommandExecutor {

        var localLevel: Int = 82
        var partnerLevel: Int = 79

        override suspend fun execute(request: RaceFrame): RaceFrame {
            if (request.commandId == failCommand) throw failure
            return when (request.commandId) {
                RaceIds.NVKEY_READ -> when (val nvkeyId = readUInt16Le(request.payload, 0)) {
                    RaceIds.NVKEY_CHIP_NAME ->
                        nvkeyResponse("AB1565".toByteArray(Charsets.US_ASCII))

                    RaceIds.NVKEY_DEVICE_TYPE_PRIMARY,
                    RaceIds.NVKEY_DEVICE_TYPE_FALLBACK,
                    -> nvkeyResponse(byteArrayOf(1, *ByteArray(38), 0))

                    RaceIds.NVKEY_NVDM_VERSION -> nvkeyResponse(byteArrayOf(0x0C))
                    else -> error("Unexpected NVKEY 0x${nvkeyId.toString(16)}")
                }

                RaceIds.GET_ROLE -> RaceFrame(RaceType.Response, RaceIds.GET_ROLE, rolePayload)

                RaceIds.GET_AVAILABLE_DESTINATIONS -> RaceFrame(
                    RaceType.Response,
                    RaceIds.GET_AVAILABLE_DESTINATIONS,
                    byteArrayOf(RaceIds.DESTINATION_TYPE_AWSPEER.toByte(), 9),
                )

                RaceIds.TWS_GET_BATTERY -> RaceFrame(
                    RaceType.Response,
                    RaceIds.TWS_GET_BATTERY,
                    byteArrayOf(0, 0, localLevel.toByte()),
                )

                RaceIds.RELAY_PASS_TO_DST -> {
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
    }
}

private fun nvkeyResponse(data: ByteArray): RaceFrame {
    val payload = ByteArray(2 + data.size)
    writeUInt16Le(payload, 0, data.size)
    data.copyInto(payload, destinationOffset = 2)
    return RaceFrame(RaceType.Response, RaceIds.NVKEY_READ, payload)
}
