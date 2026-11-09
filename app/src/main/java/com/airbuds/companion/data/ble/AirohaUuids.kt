package com.airbuds.companion.data.ble

import java.util.UUID

/** GATT identifiers used by the Airoha RACE service. See `PROTOCOL.md` §2.1. */
object AirohaUuids {
    val service: UUID = UUID.fromString("5052494D-2DAB-0341-6972-6F6861424C45")
    val tx: UUID = UUID.fromString("43484152-2DAB-3241-6972-6F6861424C45")
    val rx: UUID = UUID.fromString("43484152-2DAB-3141-6972-6F6861424C45")
    val cccd: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    const val REQUESTED_MTU = 240
    const val DEFAULT_ATT_MTU = 23

    /** ATT writes carry a 3-byte header on top of the usable payload. */
    const val ATT_HEADER_SIZE = 3
}
