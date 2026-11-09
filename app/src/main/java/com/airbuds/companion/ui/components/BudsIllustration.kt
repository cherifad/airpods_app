package com.airbuds.companion.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import com.airbuds.companion.ui.theme.AccentGreen

/**
 * Asset-free earbuds artwork: two buds standing behind a closed charging case.
 * Drawn on a 200x160 design grid and scaled to the available space.
 */
@Composable
fun BudsIllustration(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f

    val caseTopColor = if (dark) Color(0xFF3A3A40) else Color(0xFFFBFBFD)
    val caseBottomColor = if (dark) Color(0xFF232327) else Color(0xFFECECF1)
    val caseStroke = if (dark) Color(0xFF4A4A50) else Color(0xFFD8D8DE)
    val budColor = if (dark) Color(0xFFF7F7FA) else Color(0xFFFFFFFF)
    val budStroke = if (dark) Color(0xFF55555C) else Color(0xFFD0D0D8)
    val shadowColor = Color.Black.copy(alpha = if (dark) 0.35f else 0.10f)

    Canvas(modifier) {
        val scale = minOf(size.width / 200f, size.height / 160f)
        val originX = (size.width - 200f * scale) / 2f
        val originY = (size.height - 160f * scale) / 2f

        fun point(x: Float, y: Float) = Offset(originX + x * scale, originY + y * scale)

        drawOval(
            color = shadowColor,
            topLeft = point(36f, 143f),
            size = Size(128f * scale, 12f * scale),
        )

        // Buds sit behind the case so their stems disappear into it.
        drawBud(
            center = point(78f, 42f),
            unit = 3.2f * scale,
            angle = -13f,
            body = budColor,
            stroke = budStroke,
        )
        drawBud(
            center = point(122f, 36f),
            unit = 3.2f * scale,
            angle = 13f,
            body = budColor,
            stroke = budStroke,
        )

        val caseTopLeft = point(50f, 74f)
        val caseSize = Size(100f * scale, 72f * scale)
        val caseCorner = CornerRadius(20f * scale)

        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(caseTopColor, caseBottomColor),
                startY = caseTopLeft.y,
                endY = caseTopLeft.y + caseSize.height,
            ),
            topLeft = caseTopLeft,
            size = caseSize,
            cornerRadius = caseCorner,
        )
        drawRoundRect(
            color = caseStroke,
            topLeft = caseTopLeft,
            size = caseSize,
            cornerRadius = caseCorner,
            style = Stroke(width = 1.2f * scale),
        )
        drawLine(
            color = caseStroke,
            start = point(58f, 97.8f),
            end = point(142f, 97.8f),
            strokeWidth = 1.2f * scale,
        )
        drawCircle(
            color = AccentGreen,
            radius = 2.2f * scale,
            center = point(100f, 120.5f),
        )
    }
}

private fun DrawScope.drawBud(
    center: Offset,
    unit: Float,
    angle: Float,
    body: Color,
    stroke: Color,
) {
    rotate(degrees = angle, pivot = center) {
        val headRadius = 5.2f * unit
        val stemWidth = 4.6f * unit
        val stemHeight = 11f * unit
        val stemTopLeft = Offset(center.x - stemWidth / 2f, center.y + headRadius * 0.2f)
        val stemSize = Size(stemWidth, stemHeight)
        val stemCorner = CornerRadius(stemWidth / 2f)

        drawRoundRect(
            color = body,
            topLeft = stemTopLeft,
            size = stemSize,
            cornerRadius = stemCorner,
        )
        drawRoundRect(
            color = stroke,
            topLeft = stemTopLeft,
            size = stemSize,
            cornerRadius = stemCorner,
            style = Stroke(width = 0.35f * unit),
        )

        val headSize = Size(headRadius * 1.92f, headRadius * 2.12f)
        val headTopLeft = Offset(
            center.x - headSize.width / 2f,
            center.y - headSize.height / 2f,
        )
        drawOval(color = body, topLeft = headTopLeft, size = headSize)
        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.06f)),
                center = Offset(center.x - headRadius * 0.3f, center.y - headRadius * 0.4f),
                radius = headRadius * 1.7f,
            ),
            topLeft = headTopLeft,
            size = headSize,
        )
        drawOval(
            color = stroke,
            topLeft = headTopLeft,
            size = headSize,
            style = Stroke(width = 0.35f * unit),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.55f),
            radius = headRadius * 0.14f,
            center = Offset(center.x - headRadius * 0.36f, center.y - headRadius * 0.5f),
        )
    }
}
