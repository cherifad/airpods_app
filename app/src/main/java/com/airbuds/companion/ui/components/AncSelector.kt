package com.airbuds.companion.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.airbuds.companion.model.AncMode
import com.airbuds.companion.ui.labelRes

/**
 * Custom segmented control instead of the M3 one: the soft sliding pill and
 * flat surface read closer to iOS while staying fully theme-driven.
 */
@Composable
fun AncSelector(
    selected: AncMode,
    onSelect: (AncMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val selectedContainer = if (scheme.surface.luminance() < 0.5f) {
        scheme.surfaceVariant
    } else {
        scheme.surface
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp)
                .selectableGroup(),
        ) {
            AncMode.entries.forEach { mode ->
                val active = mode == selected
                val container by animateColorAsState(
                    targetValue = if (active) selectedContainer else Color.Transparent,
                    animationSpec = tween(200),
                    label = "ancContainer",
                )
                val content by animateColorAsState(
                    targetValue = if (active) scheme.onSurface else scheme.onSurfaceVariant,
                    animationSpec = tween(200),
                    label = "ancContent",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(container)
                        .selectable(
                            selected = active,
                            role = Role.RadioButton,
                            onClick = { onSelect(mode) },
                        )
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(mode.labelRes()),
                        color = content,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
