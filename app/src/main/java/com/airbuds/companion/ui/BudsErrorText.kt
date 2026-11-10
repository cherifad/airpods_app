package com.airbuds.companion.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
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

/**
 * Keeps the transport/protocol detail visible: "Airoha service not found" or
 * "GATT connection error (status 133)" is what makes a connection failure
 * actionable, while the generic fallback is only used for empty messages.
 */
@Composable
fun BudsError.userMessage(): String {
    val detail = when (this) {
        is BudsError.Protocol -> message
        is BudsError.Transport -> message
        is BudsError.Unknown -> message
        else -> ""
    }
    return detail.takeIf { it.isNotBlank() } ?: stringResource(messageRes())
}
