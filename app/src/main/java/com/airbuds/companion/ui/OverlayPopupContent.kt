package com.airbuds.companion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.airbuds.companion.model.AncMode

/**
 * Content hosted in the system overlay window.
 *
 * The card is reused from the in-app popup, but without the full-screen scrim:
 * the overlay must only cover the floating card and leave the app below
 * interactive.
 */
@Composable
fun OverlayPopupContent(
    state: BudsUiState,
    onAncChange: (AncMode) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        BudsPopupCard(
            state = state,
            onAncChange = onAncChange,
            onDismiss = onDismiss,
            modifier = Modifier
                .widthIn(max = 400.dp)
                .fillMaxWidth(),
        )
    }
}
