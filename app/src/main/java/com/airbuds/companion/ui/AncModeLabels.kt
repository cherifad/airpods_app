package com.airbuds.companion.ui

import androidx.annotation.StringRes
import com.airbuds.companion.R
import com.airbuds.companion.model.AncMode

/**
 * Keeps the domain enum free of Android resource details while giving both the
 * home screen and the popup a single localized label mapping.
 */
@StringRes
internal fun AncMode.labelRes(): Int = when (this) {
    AncMode.NoiseCancelling -> R.string.anc_noise_cancelling
    AncMode.Transparency -> R.string.anc_transparency
    AncMode.Off -> R.string.anc_off
}
