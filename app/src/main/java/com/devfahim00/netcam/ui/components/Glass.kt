package com.devfahim00.netcam.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Lightweight frosted-glass background: a translucent white gradient plus a
 * hairline light-catching border. No runtime backdrop blur is used on purpose —
 * this renders identically and smoothly on every device from Android 7 up.
 */
fun Modifier.glassBackground(
    shape: Shape = RectangleShape,
    tintAlpha: Float = 0.12f
): Modifier = this
    .background(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = (tintAlpha * 1.35f).coerceAtMost(1f)),
                Color.White.copy(alpha = (tintAlpha * 0.6f).coerceAtMost(1f)),
                Color.White.copy(alpha = tintAlpha.coerceAtMost(1f))
            ),
            start = Offset.Zero,
            end = Offset(140f, 190f)
        ),
        shape = shape
    )
    .border(
        width = 0.75.dp,
        brush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.38f),
                Color.White.copy(alpha = 0.08f),
                Color.White.copy(alpha = 0.28f)
            )
        ),
        shape = shape
    )

/** A glass panel used for cards, pills and dialogs. */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    tintAlpha: Float = 0.10f,
    contentAlignment: Alignment = Alignment.Center,
    contentPadding: Dp = 14.dp,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(shape)
            .glassBackground(shape = shape, tintAlpha = tintAlpha),
        contentAlignment = contentAlignment
    ) {
        Box(modifier = Modifier.padding(contentPadding)) { content() }
    }
}

/** A circular glass button with a springy press animation. */
@Composable
fun GlassIconButton(
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    shape: Shape = CircleShape,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 500f),
        label = "glassButtonScale"
    )
    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .clip(shape)
            .glassBackground(shape = shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
