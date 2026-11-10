package com.airbuds.companion.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.airbuds.companion.R
import com.airbuds.companion.model.AncMode
import com.airbuds.companion.ui.components.AncSelector
import com.airbuds.companion.ui.components.BatteryRow
import com.airbuds.companion.ui.components.BudsIllustration
import com.airbuds.companion.ui.theme.AccentAmber
import com.airbuds.companion.ui.theme.AccentGreen
import com.airbuds.companion.ui.theme.AccentRed

@Composable
fun HomeScreen(
    state: BudsUiState,
    onPrimaryAction: () -> Unit,
    onAncChange: (AncMode) -> Unit,
    onDisconnect: () -> Unit,
    onOpenDiagnostics: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        HomeHeader(state = state, onPrimaryAction = onPrimaryAction)
        state.visibleError?.let { error ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = error.userMessage(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(24.dp))
        HeroCard(state = state, onPrimaryAction = onPrimaryAction)
        Spacer(Modifier.height(28.dp))
        SectionTitle(stringResource(R.string.section_controls))
        Spacer(Modifier.height(12.dp))
        ControlsCard(state = state, onAncChange = onAncChange)
        Spacer(Modifier.height(28.dp))
        SectionTitle(stringResource(R.string.section_battery))
        Spacer(Modifier.height(12.dp))
        BatteryCard(state = state)
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onPrimaryAction,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Text(
                text = stringResource(
                    if (state.connected) R.string.show_popup else R.string.connect_cta,
                ),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        if (state.connected) {
            TextButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.connect_disconnect),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        if (onOpenDiagnostics != null) {
            TextButton(
                onClick = onOpenDiagnostics,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.diagnostics_open),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HomeHeader(state: BudsUiState, onPrimaryAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.home_title),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = state.deviceName ?: stringResource(R.string.status_not_connected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ConnectionChip(
            connected = state.connected,
            busy = state.busy,
            onClick = onPrimaryAction,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeroCard(state: BudsUiState, onPrimaryAction: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onPrimaryAction,
        modifier = Modifier
            .fillMaxWidth()
            .height(310.dp),
        shape = RoundedCornerShape(32.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.65f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(scheme.primary.copy(alpha = 0.10f), scheme.surface),
                    ),
                ),
        ) {
            Column(Modifier.fillMaxSize().padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = state.deviceName ?: stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = state.chipName ?: stringResource(R.string.status_not_connected),
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    ConnectionChip(
                        connected = state.connected,
                        busy = state.busy,
                        onClick = onPrimaryAction,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    BudsIllustration(
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(1.25f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(
                            if (state.connected) R.string.see_popup else R.string.connect_cta,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.primary,
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlsCard(state: BudsUiState, onAncChange: (AncMode) -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.noise_control_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (state.connected && state.ancAvailable) {
                        stringResource(state.anc.labelRes())
                    } else {
                        stringResource(R.string.status_not_connected)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    if (state.connected && state.ancAvailable) {
                        R.string.noise_control_subtitle
                    } else {
                        R.string.anc_unavailable
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            AncSelector(
                selected = state.anc,
                onSelect = onAncChange,
                enabled = state.connected && state.ancAvailable,
            )
        }
    }
}

@Composable
private fun BatteryCard(state: BudsUiState) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
        ),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            BatteryRow(
                label = stringResource(R.string.battery_left),
                level = state.battery.leftPercent,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            BatteryRow(
                label = stringResource(R.string.battery_right),
                level = state.battery.rightPercent,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            BatteryRow(
                label = stringResource(R.string.battery_case),
                level = state.battery.casePercent,
            )
        }
    }
}

@Composable
private fun ConnectionChip(connected: Boolean, busy: Boolean, onClick: () -> Unit) {
    val color = when {
        busy -> AccentAmber
        connected -> AccentGreen
        else -> AccentRed
    }
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = color.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Text(
                text = stringResource(
                    when {
                        busy -> R.string.status_connecting
                        connected -> R.string.status_connected
                        else -> R.string.status_not_connected
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 4.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )
}
