package com.airbuds.companion.data.ble

import com.airbuds.companion.domain.model.BudsBattery
import com.airbuds.companion.model.AncMode
import kotlinx.coroutines.CancellationException

/** Result of one initialization + battery read cycle. */
data class BudsSnapshot(
    val deviceInfo: RaceDeviceInfo,
    val battery: BudsBattery,
    val agentIsRight: Boolean? = null,
)

/**
 * Builds the product-level snapshot on top of the RACE executor.
 *
 * The session assumes the GATT link is already ready. Initialization is
 * mandatory; battery and side reads are best effort so one unsupported command
 * does not hide the device information.
 */
class BudsSession(private val executor: RaceCommandExecutor) {

    private val initializer = RaceInitializer(executor)
    private val batteryReader = RaceBatteryReader(executor)
    private val ancController = RaceAncController(executor)

    suspend fun handshake(): BudsSnapshot {
        val info = initializer.initialize()
        val agentIsRight = readAgentSide()
        return BudsSnapshot(
            deviceInfo = info,
            battery = readBattery(info, agentIsRight),
            agentIsRight = agentIsRight,
        )
    }

    suspend fun initialize(): RaceDeviceInfo = initializer.initialize()

    /**
     * Reads the agent and, when a peer exists, the partner level, then maps
     * them to left/right with the audio-channel NVKEY.
     *
     * Failures are isolated per earbud: a failed partner read keeps the agent
     * value, and vice versa.
     */
    suspend fun readBattery(
        info: RaceDeviceInfo,
        agentIsRight: Boolean? = null,
    ): BudsBattery {
        val agent = readPercent { batteryReader.readLocal() }
        val partner = info.peerId?.let { peerId ->
            readPercent { batteryReader.readPartner(peerId) }
        }
        return when (agentIsRight) {
            true -> BudsBattery(leftPercent = partner, rightPercent = agent)
            false -> BudsBattery(leftPercent = agent, rightPercent = partner)
            null -> BudsBattery.Unknown
        }
    }

    /**
     * Reads NVKEY `0xF2B5`: byte 1 == 2 means the agent is the right earbud.
     *
     * This mirrors the vendor SDK's `MmiStageGetAudioChannel`.
     */
    suspend fun readAgentSide(): Boolean? =
        try {
            val data = initializer.readNvkey(RaceIds.NVKEY_AUDIO_CHANNEL)
            if (data.size >= 2) data[1].toInt() == 2 else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    private suspend fun readPercent(read: suspend () -> EarbudBattery): Int? =
        try {
            read().percent
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    /** Reads the ANC filter and gain; null when the firmware does not answer. */
    suspend fun readAncState(): AncState? =
        try {
            ancController.readState()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    /** Applies a listening mode; throws when the firmware rejects it. */
    suspend fun setAnc(mode: AncMode) {
        ancController.setMode(mode)
    }
}
