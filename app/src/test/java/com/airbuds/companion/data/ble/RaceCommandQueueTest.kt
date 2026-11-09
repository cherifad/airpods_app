package com.airbuds.companion.data.ble

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceCommandQueueTest {

    @Test
    fun `execute returns the matching response`() = runTest {
        val transport = FakeRaceTransport()
        val queue = RaceCommandQueue(transport)
        transport.onWrite = { request ->
            queue.onFrame(RaceFrame(RaceType.Response, request.commandId, byteArrayOf(0x00)))
        }

        val response = queue.execute(request(0x0CD6))

        assertEquals(0x0CD6, response.commandId)
        assertEquals(RaceType.Response, response.type)
        assertEquals(listOf(0x0CD6), transport.writtenIds)
    }

    @Test
    fun `ignores responses for another command id`() = runTest {
        val transport = FakeRaceTransport()
        val queue = RaceCommandQueue(transport)
        val pending = async { queue.execute(request(0x0CD6)) }
        runCurrent()

        queue.onFrame(RaceFrame(RaceType.Response, 0x1234))
        runCurrent()

        assertFalse(pending.isCompleted)
        queue.onFrame(RaceFrame(RaceType.Response, 0x0CD6))
        assertEquals(0x0CD6, pending.await().commandId)
    }

    @Test
    fun `forwards indications without completing a pending request`() = runTest {
        val indications = mutableListOf<RaceFrame>()
        val transport = FakeRaceTransport()
        val queue = RaceCommandQueue(transport, onIndication = indications::add)
        val pending = async { queue.execute(request(0x0CD6)) }
        runCurrent()
        val indication = RaceFrame(RaceType.Indication, 0x5D, byteArrayOf(0x01))

        queue.onFrame(indication)
        runCurrent()

        assertEquals(listOf(indication), indications)
        assertFalse(pending.isCompleted)
        queue.onFrame(RaceFrame(RaceType.Response, 0x0CD6))
        pending.await()
    }

    @Test
    fun `retries after a timeout and succeeds on the next attempt`() = runTest {
        val transport = FakeRaceTransport()
        val queue = RaceCommandQueue(transport, timeoutMillis = 1_000, maxAttempts = 3)
        var attempts = 0
        transport.onWrite = { request ->
            attempts++
            if (attempts == 2) {
                queue.onFrame(RaceFrame(RaceType.Response, request.commandId))
            }
        }

        val response = queue.execute(request(0x0901))

        assertEquals(0x0901, response.commandId)
        assertEquals(2, attempts)
        assertEquals(2, transport.writtenIds.size)
    }

    @Test
    fun `fails after the configured number of attempts`() = runTest {
        val transport = FakeRaceTransport()
        val queue = RaceCommandQueue(transport, timeoutMillis = 1_000, maxAttempts = 3)

        var error: RaceCommandTimeoutException? = null
        try {
            queue.execute(request(0x0E06))
        } catch (exception: RaceCommandTimeoutException) {
            error = exception
        }

        assertNotNull(error)
        assertEquals(0x0E06, error?.commandId)
        assertEquals(3, error?.attempts)
        assertEquals(3, transport.writtenIds.size)
    }

    @Test
    fun `serializes concurrent commands`() = runTest {
        val transport = FakeRaceTransport()
        val queue = RaceCommandQueue(transport)

        val first = async { queue.execute(request(0x0CD6)) }
        runCurrent()
        assertEquals(listOf(0x0CD6), transport.writtenIds)

        val second = async { queue.execute(request(0x0901)) }
        runCurrent()
        assertEquals(1, transport.writtenIds.size)

        queue.onFrame(RaceFrame(RaceType.Response, 0x0CD6))
        runCurrent()
        assertEquals(listOf(0x0CD6, 0x0901), transport.writtenIds)

        queue.onFrame(RaceFrame(RaceType.Response, 0x0901))
        assertEquals(0x0CD6, first.await().commandId)
        assertEquals(0x0901, second.await().commandId)
    }

    @Test
    fun `rejects non-request frames`() = runTest {
        val queue = RaceCommandQueue(FakeRaceTransport())

        var error: IllegalArgumentException? = null
        try {
            queue.execute(RaceFrame(RaceType.Response, 0x0CD6))
        } catch (exception: IllegalArgumentException) {
            error = exception
        }

        assertTrue(error != null)
    }

    private fun request(commandId: Int) = RaceFrame(RaceType.Request, commandId)

    private class FakeRaceTransport : RaceTransport {
        val writes = mutableListOf<RaceFrame>()
        var onWrite: ((RaceFrame) -> Unit)? = null

        val writtenIds: List<Int> get() = writes.map { it.commandId }

        override suspend fun write(bytes: ByteArray) {
            val frame = RaceCodec.decode(bytes)
            writes += frame
            onWrite?.invoke(frame)
        }
    }
}
