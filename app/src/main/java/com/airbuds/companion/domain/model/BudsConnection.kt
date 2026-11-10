package com.airbuds.companion.domain.model

/** High-level connection state of the earbuds session. */
sealed interface BudsConnection {
    data object Disconnected : BudsConnection
    data class Connecting(val address: String) : BudsConnection
    data class Initializing(val address: String) : BudsConnection
    data class Ready(val address: String) : BudsConnection
    data class Failed(val address: String?, val error: BudsError) : BudsConnection
}
