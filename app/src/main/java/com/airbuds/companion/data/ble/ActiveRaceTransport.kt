package com.airbuds.companion.data.ble

/** Writes through whichever transport is currently connected (GATT or SPP). */
class ActiveRaceTransport(initial: RaceTransport) : RaceTransport {

    @Volatile
    var delegate: RaceTransport = initial

    override suspend fun write(bytes: ByteArray) {
        delegate.write(bytes)
    }
}
