package com.airbuds.companion.data.ble

import com.airbuds.companion.domain.model.BudsBattery
import kotlinx.coroutines.CancellationException

/** Result of one initialization + battery read cycle. */
data class BudsSnapshot(
    val deviceInfo: RaceDeviceInfo,
    val battery: BudsBattery,
)

/**
 * Builds the product-level snapshot on top of the RACE executor.
 *
 * The session assumes the GATT link is already ready. Initialization is
 * mandatory; battery reads are best effort so a single unsupported or timed-out
 * command does not hide the device information.
 */
class BudsSession(private val executor: RaceCommandExecutor) {

    private val initializer = RaceInitializer(executor)
    private val batteryReader = RaceBatteryReader(executor)

    suspend fun handshake(): BudsSnapshot {
        val info = initializer.initialize()
        return BudsSnapshot(deviceInfo = info, battery = readBattery(info))
    }

    suspend fun initialize(): RaceDeviceInfo = initializer.initialize()

    /**
     * Reads the agent and, when a peer exists, the partner level.
     *
     * Failures are isolated per earbud: a failed partner read keeps the agent
     * value, and vice versa.
     */
    suspend fun readBattery(info: RaceDeviceInfo): BudsBattery {
        val agent = readPercent { batteryReader.readLocal() }
        val partner = info.peerId?.let { peerId ->
            readPercent { batteryReader.readPartner(peerId) }
        }
        return BudsBattery(agentPercent = agent, partnerPercent = partner)
    }

    private suspend fun readPercent(read: suspend () -> EarbudBattery): Int? =
        try {
            read().percent
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
}
