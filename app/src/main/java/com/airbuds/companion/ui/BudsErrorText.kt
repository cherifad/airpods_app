package com.airbuds.companion.ui

import androidx.annotation.StringRes
import com.airbuds.companion.R
import com.airbuds.companion.domain.model.BudsError

/** Maps the product error model to a user-facing string. */
@StringRes
fun BudsError.messageRes(): Int = when (this) {
    BudsError.BluetoothDisabled -> R.string.error_bluetooth_disabled
    BudsError.PermissionDenied -> R.string.error_permission_denied
    BudsError.Timeout -> R.string.error_timeout
    is BudsError.Protocol -> R.string.error_protocol
    is BudsError.Transport -> R.string.error_transport
    is BudsError.Unknown -> R.string.error_unknown
}
