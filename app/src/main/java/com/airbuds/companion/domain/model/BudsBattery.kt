package com.airbuds.companion.domain.model

/**
 * Battery levels read from the earbuds.
 *
 * Airoha reports the agent and the partner (`0x0CD6` / `0x0D01`); mapping them
 * to left/right requires the agent-side flag (`0x0CD4`), which is not
 * implemented yet. [casePercent] stays null until the case channel is
 * validated. A null field means "unknown", never 0 %.
 */
data class BudsBattery(
    val agentPercent: Int? = null,
    val partnerPercent: Int? = null,
    val casePercent: Int? = null,
) {
    val isKnown: Boolean
        get() = agentPercent != null || partnerPercent != null || casePercent != null

    companion object {
        val Unknown = BudsBattery()
    }
}
