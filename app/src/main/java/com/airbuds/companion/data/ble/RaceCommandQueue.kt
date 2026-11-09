package com.airbuds.companion.data.ble

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Raised when no response arrived after every allowed attempt. */
class RaceCommandTimeoutException(
    val commandId: Int,
    val attempts: Int,
) : Exception(
    "RACE command 0x%04X timed out after %d attempt(s)".format(commandId, attempts),
)

/**
 * Serializes RACE commands and matches responses by command ID.
 *
 * The firmware expects one outstanding request at a time, so [execute] holds a
 * mutex for the whole write/wait cycle. Each attempt waits at most
 * [timeoutMillis]; after [maxAttempts] consecutive timeouts the command fails.
 *
 * [onFrame] is called by the transport (usually the GATT callback thread).
 * Indications are forwarded to [onIndication] and never complete a request.
 */
class RaceCommandQueue(
    private val transport: RaceTransport,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val onIndication: (RaceFrame) -> Unit = {},
) {
    init {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
    }

    private val mutex = Mutex()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<RaceFrame>>()

    /** Sends [request] and returns the matching response, retrying on timeout. */
    suspend fun execute(request: RaceFrame): RaceFrame {
        require(request.type == RaceType.Request) {
            "Only request frames can be executed, got ${request.type}"
        }

        return mutex.withLock {
            var attempts = 0
            while (attempts < maxAttempts) {
                attempts++
                val deferred = CompletableDeferred<RaceFrame>()
                pending[request.commandId] = deferred
                try {
                    transport.write(RaceCodec.encode(request))
                    return@withLock withTimeout(timeoutMillis) { deferred.await() }
                } catch (_: TimeoutCancellationException) {
                    // Retry until maxAttempts is reached.
                } finally {
                    if (pending[request.commandId] === deferred) {
                        pending.remove(request.commandId)
                    }
                }
            }
            throw RaceCommandTimeoutException(request.commandId, attempts)
        }
    }

    /** Feeds one decoded frame coming from the transport. */
    fun onFrame(frame: RaceFrame) {
        when (frame.type) {
            RaceType.Response -> pending[frame.commandId]?.complete(frame)
            RaceType.Indication -> onIndication(frame)
            RaceType.Request, RaceType.CommandNoResponse -> Unit
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 1_000L
        const val DEFAULT_MAX_ATTEMPTS = 3
    }
}
