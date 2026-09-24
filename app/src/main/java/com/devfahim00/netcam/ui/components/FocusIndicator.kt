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
import com.devfahim00.netcam.camera.FocusResult
import com.devfahim00.netcam.camera.FocusTarget
import com.devfahim00.netcam.ui.theme.Accent
import kotlin.math.roundToInt

/** Focus-converged green / failed red, GCam style. */
val FocusSuccess = Color(0xFF8CE99A)
val FocusFail = Color(0xFFFF6B6B)

/**
 * Animated focus ring shown at the tap position. Stays on screen while the
 * exposure slider is visible (parent controls removal) and reports AF result
 * through its color: white = metering, green = locked, red = failed.
 */
@Composable
fun FocusIndicator(
    target: FocusTarget?,
    modifier: Modifier = Modifier
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
        }

        // Confirmation pulse when the AF result arrives.
        LaunchedEffect(target.id, target.result) {
            if (target.result != FocusResult.PENDING) {
                scale.snapTo(1.14f)
                scale.animateTo(1f, tween(200, easing = FastOutSlowInEasing))
            }
        }

        val ringColor = when (target.result) {
            FocusResult.PENDING -> Color.White
            FocusResult.SUCCESS -> FocusSuccess
            FocusResult.FAIL -> FocusFail
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
                color = ringColor,
                radius = radius,
                style = Stroke(width = stroke)
            )
            drawCircle(color = Accent, radius = 2.5.dp.toPx(), center = center)
            val tick = 12f
            val thin = stroke * 0.7f
            drawLine(ringColor, Offset(center.x - radius, center.y), Offset(center.x - radius + tick, center.y), thin)
            drawLine(ringColor, Offset(center.x + radius, center.y), Offset(center.x + radius - tick, center.y), thin)
            drawLine(ringColor, Offset(center.x, center.y - radius), Offset(center.x, center.y - radius + tick), thin)
            drawLine(ringColor, Offset(center.x, center.y + radius), Offset(center.x, center.y + radius - tick), thin)
        }
    }
}
