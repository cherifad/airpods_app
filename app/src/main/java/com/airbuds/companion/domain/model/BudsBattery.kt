package com.airbuds.companion.domain.model

/**
 * Battery levels for the product UI.
 *
 * Airoha reports the agent and the partner; `BudsSession` maps them to
 * left/right using the audio-channel NVKEY (`0xF2B5`). A null field means
 * "unknown", never 0 %.
 */
data class BudsBattery(
    val leftPercent: Int? = null,
    val rightPercent: Int? = null,
    val casePercent: Int? = null,
    val agentPercent: Int? = null,
    val partnerPercent: Int? = null,
) {
    val isKnown: Boolean
        get() = leftPercent != null ||
            rightPercent != null ||
            casePercent != null ||
            agentPercent != null ||
            partnerPercent != null

    companion object {
        val Unknown = BudsBattery()
    }
}
