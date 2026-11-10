package com.airbuds.companion.data.repository

import com.airbuds.companion.data.ble.BudsSession
import com.airbuds.companion.data.ble.BudsSnapshot
import com.airbuds.companion.data.ble.GattException
import com.airbuds.companion.data.ble.RaceBatteryException
import com.airbuds.companion.data.ble.RaceCommandTimeoutException
import com.airbuds.companion.data.ble.RaceInitializationException
import com.airbuds.companion.data.ble.RaceProtocolException
import com.airbuds.companion.domain.model.BudsConnection
import com.airbuds.companion.domain.model.BudsError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Product-level session state on top of [BudsSession].
 *
 * The caller owns the Android link: it connects the GATT transport, then calls
 * [attach] so the repository can run the RACE initialization and read battery.
 * [detach] is called when the link is closed.
 */
class BudsRepository(private val session: BudsSession) {

    private val _connection = MutableStateFlow<BudsConnection>(BudsConnection.Disconnected)
    val connection: StateFlow<BudsConnection> = _connection.asStateFlow()

    private val _snapshot = MutableStateFlow<BudsSnapshot?>(null)
    val snapshot: StateFlow<BudsSnapshot?> = _snapshot.asStateFlow()

    /** Marks the link as being established, before [attach]. */
    fun markConnecting(address: String) {
        _connection.value = BudsConnection.Connecting(address)
    }

    /**
     * Runs initialization and the first battery read on an already-connected
     * link. Failures are exposed through [connection] instead of thrown.
     */
    suspend fun attach(address: String) {
        _snapshot.value = null
        _connection.value = BudsConnection.Initializing(address)
        try {
            val snapshot = session.handshake()
            _snapshot.value = snapshot
            _connection.value = BudsConnection.Ready(address)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _connection.value = BudsConnection.Failed(address, error.toBudsError())
        }
    }

    /** Refreshes the battery without re-running initialization. */
    suspend fun refreshBattery() {
        val current = _snapshot.value ?: return
        val battery = session.readBattery(current.deviceInfo, current.agentIsRight)
        _snapshot.value = current.copy(battery = battery)
    }

    fun detach() {
        _snapshot.value = null
        _connection.value = BudsConnection.Disconnected
    }

    /** Publishes a link-level failure before [attach] could run. */
    fun markFailed(address: String?, error: Throwable) {
        _connection.value = BudsConnection.Failed(address, error.toBudsError())
    }
}

/** Maps BLE/protocol exceptions to the product error model. */
internal fun Throwable.toBudsError(): BudsError = when (this) {
    is RaceCommandTimeoutException -> BudsError.Timeout
    is SecurityException -> BudsError.PermissionDenied
    is RaceProtocolException,
    is RaceInitializationException,
    is RaceBatteryException,
    -> BudsError.Protocol(message ?: javaClass.simpleName)

    is GattException -> {
        if (message?.contains("disabled", ignoreCase = true) == true) {
            BudsError.BluetoothDisabled
        } else {
            BudsError.Transport(message ?: "GATT failure")
        }
    }

    else -> BudsError.Unknown(message ?: javaClass.simpleName)
}
