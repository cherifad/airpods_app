package com.airbuds.companion.model

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/**
 * Snapshot of the earbuds state shown by the UI.
 *
 * Values are mocked for now. When the RACE/BLE layer lands, this state will be
 * fed by the protocol: battery from command 0x0CD6, ANC from 0x0E06.
 */
data class BudsState(
    val name: String = "Buds Pro",
    val model: String = "Airoha AB1565",
    val connected: Boolean = true,
    val leftBattery: Int = 82,
    val rightBattery: Int = 79,
    val caseBattery: Int = 64,
    val anc: AncMode = AncMode.NoiseCancelling,
)

/** Keeps the mock device state across configuration changes and process death. */
val BudsStateSaver: Saver<BudsState, Any> = listSaver(
    save = {
        listOf(
            it.name,
            it.model,
            it.connected,
            it.leftBattery,
            it.rightBattery,
            it.caseBattery,
            it.anc.name,
        )
    },
    restore = {
        BudsState(
            name = it[0] as String,
            model = it[1] as String,
            connected = it[2] as Boolean,
            leftBattery = it[3] as Int,
            rightBattery = it[4] as Int,
            caseBattery = it[5] as Int,
            anc = AncMode.valueOf(it[6] as String),
        )
    },
)
