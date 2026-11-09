package com.airbuds.companion.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.airbuds.companion.R
import com.airbuds.companion.ui.theme.AccentAmber
import com.airbuds.companion.ui.theme.AccentGreen
import com.airbuds.companion.ui.theme.AccentRed

fun batteryColor(level: Int): Color = when {
    level <= 10 -> AccentRed
    level <= 20 -> AccentAmber
    else -> AccentGreen
}

/**
 * Vertical iOS-style battery glyph. The fill animates from empty whenever the
 * glyph enters composition, which makes the popup feel alive on every open.
 */
@Composable
fun BatteryPill(level: Int, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.outline
    val fillColor = batteryColor(level)
    var target by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(level) {
        target = (level / 100f).coerceIn(0f, 1f)
    }
    val fill by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(
            durationMillis = 650,
            delayMillis = 120,
            easing = FastOutSlowInEasing,
        ),
        label = "batteryFill",
    )

    Canvas(modifier.size(width = 27.dp, height = 50.dp)) {
        val capWidth = size.width * 0.36f
        val capHeight = 3.5.dp.toPx()
        val gap = 2.dp.toPx()
        val bodyTop = capHeight + gap
        val bodyHeight = size.height - bodyTop
        val radius = 5.dp.toPx()
        val strokeWidth = 1.5.dp.toPx()

        drawRoundRect(
            color = outline,
            topLeft = Offset((size.width - capWidth) / 2f, 0f),
            size = Size(capWidth, capHeight),
            cornerRadius = CornerRadius(1.5.dp.toPx()),
        )
        drawRoundRect(
            color = outline,
            topLeft = Offset(0f, bodyTop),
            size = Size(size.width, bodyHeight),
            cornerRadius = CornerRadius(radius),
            style = Stroke(width = strokeWidth),
        )

        val inset = 3.5.dp.toPx()
        val innerWidth = size.width - inset * 2f
        val innerHeight = (bodyHeight - inset * 2f) * fill
        if (innerHeight > 0.5f) {
            drawRoundRect(
                color = fillColor,
                topLeft = Offset(inset, bodyTop + bodyHeight - inset - innerHeight),
                size = Size(innerWidth, innerHeight),
                cornerRadius = CornerRadius(radius * 0.55f),
            )
        }
    }
}

@Composable
fun BatteryRow(label: String, level: Int, modifier: Modifier = Modifier) {
    val color = batteryColor(level)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Box(
            modifier = Modifier
                .width(96.dp)
                .height(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth((level / 100f).coerceIn(0f, 1f))
                    .clip(CircleShape)
                    .background(color),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.battery_percent, level),
            modifier = Modifier.width(46.dp),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
