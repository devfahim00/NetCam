package com.devfahim00.netcam.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.devfahim00.netcam.camera.FocusTarget
import com.devfahim00.netcam.ui.theme.Accent
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Animated focus ring shown at the tap position. */
@Composable
fun FocusIndicator(
    target: FocusTarget?,
    modifier: Modifier = Modifier,
    onFinished: () -> Unit
) {
    if (target == null) return

    key(target.id) {
        val alpha = remember { Animatable(0f) }
        val scale = remember { Animatable(1.4f) }

        LaunchedEffect(target.id) {
            alpha.snapTo(0f)
            scale.snapTo(1.4f)
            alpha.animateTo(1f, tween(110))
            scale.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
            delay(420)
            alpha.animateTo(0f, tween(200))
            onFinished()
        }

        Canvas(
            modifier = modifier
                .offset {
                    IntOffset(
                        (target.x - 44f).roundToInt(),
                        (target.y - 44f).roundToInt()
                    )
                }
                .size(88.dp)
                .graphicsLayer {
                    this.alpha = alpha.value
                    scaleX = scale.value
                    scaleY = scale.value
                }
        ) {
            val stroke = 2.4.dp.toPx()
            val radius = size.minDimension / 2f - stroke
            drawCircle(
                color = Color.White,
                radius = radius,
                style = Stroke(width = stroke)
            )
            drawCircle(color = Accent, radius = 2.5.dp.toPx(), center = center)
            val tick = 12f
            val thin = stroke * 0.7f
            drawLine(Color.White, Offset(center.x - radius, center.y), Offset(center.x - radius + tick, center.y), thin)
            drawLine(Color.White, Offset(center.x + radius, center.y), Offset(center.x + radius - tick, center.y), thin)
            drawLine(Color.White, Offset(center.x, center.y - radius), Offset(center.x, center.y - radius + tick), thin)
            drawLine(Color.White, Offset(center.x, center.y + radius), Offset(center.x, center.y + radius - tick), thin)
        }
    }
}
