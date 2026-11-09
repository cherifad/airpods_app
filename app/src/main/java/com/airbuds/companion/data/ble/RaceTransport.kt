package com.airbuds.companion.data.ble

/** Sends raw RACE bytes to the earbuds. Implemented by the GATT layer. */
fun interface RaceTransport {
    suspend fun write(bytes: ByteArray)
}
