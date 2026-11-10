package com.airbuds.companion.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.airbuds.companion.R
import com.airbuds.companion.model.AncMode
import com.airbuds.companion.ui.components.AncSelector
import com.airbuds.companion.ui.components.BatteryPill
import com.airbuds.companion.ui.components.BudsIllustration
import com.airbuds.companion.ui.theme.AccentGreen
import com.airbuds.companion.ui.theme.AccentRed

/**
 * In-app "open the case" popup: a floating card anchored above the navigation
 * bar, dimmed backdrop and spring entry. The bottom placement keeps the
 * controls within thumb reach while preserving the iOS-like card silhouette.
 */
@Composable
fun BudsPopup(
    visible: Boolean,
    state: BudsUiState,
    onAncChange: (AncMode) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(160)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.38f))
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { onDismiss() })
                    },
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(180)) +
                scaleIn(
                    initialScale = 0.92f,
                    animationSpec = spring(dampingRatio = 0.78f, stiffness = 360f),
                ) +
                slideInVertically(
                    initialOffsetY = { it / 3 },
                    animationSpec = spring(dampingRatio = 0.82f, stiffness = 340f),
                ),
            exit = fadeOut(animationSpec = tween(140)) +
                scaleOut(targetScale = 0.96f, animationSpec = tween(140)) +
                slideOutVertically(targetOffsetY = { it / 4 }, animationSpec = tween(160)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 20.dp, bottom = 16.dp, end = 20.dp),
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
    }
}

@Composable
internal fun BudsPopupCard(
    state: BudsUiState,
    onAncChange: (AncMode) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val closeLabel = stringResource(R.string.close)
    // Leave breathing room for the status bar and the bottom navigation bar;
    // the content scrolls if the available height is smaller (landscape).
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp - 112.dp
    Surface(
        modifier = modifier
            .heightIn(max = maxHeight)
            .pointerInput(Unit) {
                // Taps on the card background must not fall through to the scrim.
                detectTapGestures(onTap = {})
            },
        shape = RoundedCornerShape(32.dp),
        color = scheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 24.dp,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(22.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = state.deviceName ?: stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (state.connected) AccentGreen else AccentRed),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(
                                if (state.connected) {
                                    R.string.status_connected
                                } else {
                                    R.string.status_disconnected
                                },
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state.connected) AccentGreen else AccentRed,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(scheme.surfaceVariant.copy(alpha = 0.7f))
                        .clickable(onClick = onDismiss)
                        .semantics { contentDescription = closeLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = scheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(126.dp),
                contentAlignment = Alignment.Center,
            ) {
                BudsIllustration(
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1.25f),
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                BatteryColumn(
                    label = stringResource(
                        if (state.battery.leftPercent == null &&
                            state.battery.rightPercent == null &&
                            (state.battery.agentPercent != null ||
                                state.battery.partnerPercent != null)
                        ) {
                            R.string.battery_earbud_one
                        } else {
                            R.string.battery_left_short
                        },
                    ),
                    level = state.battery.leftPercent ?: state.battery.agentPercent,
                    modifier = Modifier.weight(1f),
                )
                BatteryColumn(
                    label = stringResource(
                        if (state.battery.leftPercent == null &&
                            state.battery.rightPercent == null &&
                            (state.battery.agentPercent != null ||
                                state.battery.partnerPercent != null)
                        ) {
                            R.string.battery_earbud_two
                        } else {
                            R.string.battery_right_short
                        },
                    ),
                    level = state.battery.rightPercent ?: state.battery.partnerPercent,
                    modifier = Modifier.weight(1f),
                )
                BatteryColumn(
                    label = stringResource(R.string.battery_case),
                    level = state.battery.casePercent,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = scheme.outlineVariant)
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.listening_mode),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            AncSelector(
                selected = state.anc,
                onSelect = onAncChange,
                enabled = state.connected,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun BatteryColumn(label: String, level: Int?, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        BatteryPill(level = level)
        Spacer(Modifier.height(8.dp))
        Text(
            text = level?.let { stringResource(R.string.battery_percent, it) }
                ?: stringResource(R.string.battery_unknown),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
