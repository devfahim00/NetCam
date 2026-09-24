package com.devfahim00.netcam.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devfahim00.netcam.ui.theme.Accent
import java.util.Locale
import kotlin.math.roundToInt

/**
 * GCam-style exposure slider that pops up next to the focus ring after a
 * tap-to-focus. Drag up to brighten, down to darken; the value is expressed
 * in EV stops. The round "A" button returns exposure to auto.
 *
 * Layout: anchor (px, in the camera Box coordinate space) + clamped position
 * so the slider always stays fully on screen and clear of the control bars.
 */
@Composable
fun EvSlider(
    evIndex: Int,
    minIndex: Int,
    maxIndex: Int,
    step: Float,
    anchor: Offset,
    areaWidthPx: Int,
    areaHeightPx: Int,
    modifier: Modifier = Modifier,
    onValueChange: (Int) -> Unit,
    onAutoReset: () -> Unit,
    onInteraction: () -> Unit
) {
    if (maxIndex <= minIndex || step <= 0f) return
    val density = LocalDensity.current

    val trackWidth = 44.dp
    val trackHeight = 128.dp
    val columnWidth = 44.dp
    val columnHeight = 232.dp

    // fraction 0 = bottom (darkest / minIndex), 1 = top (brightest / maxIndex).
    val fraction = 1f - (evIndex - minIndex) / (maxIndex - minIndex).toFloat()

    var trackPx by remember { mutableFloatStateOf(1f) }
    val currentFraction by rememberUpdatedState(fraction)
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentInteract by rememberUpdatedState(onInteraction)

    fun indexForFraction(f: Float): Int =
        (minIndex + (1f - f.coerceIn(0f, 1f)) * (maxIndex - minIndex)).roundToInt()
            .coerceIn(minIndex, maxIndex)

    // Position to the right of the focus ring, clamped inside the safe area.
    val position = with(density) {
        remember(anchor, areaWidthPx, areaHeightPx) {
            val wPx = columnWidth.roundToPx()
            val hPx = columnHeight.roundToPx()
            val gap = 44.dp.roundToPx()
            val margin = 10.dp.roundToPx()
            val x = (anchor.x + gap)
                .coerceIn(margin.toFloat(), (areaWidthPx - wPx - margin).toFloat().coerceAtLeast(margin.toFloat()))
            val y = (anchor.y - hPx / 2f)
                .coerceIn(140.dp.roundToPx().toFloat(), (areaHeightPx - hPx - 150.dp.roundToPx()).coerceAtLeast(140.dp.roundToPx()).toFloat())
            IntOffset(x.roundToInt(), y.roundToInt())
        }
    }

    Column(
        modifier = modifier
            .offset { position }
            .width(columnWidth),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Sun glyph.
        Canvas(Modifier.size(18.dp).padding(bottom = 1.dp)) {
            val r = size.minDimension * 0.24f
            drawCircle(Color.White, radius = r)
            val rayOut = size.minDimension * 0.48f
            val rayIn = size.minDimension * 0.36f
            for (i in 0 until 8) {
                val a = Math.toRadians((i * 45).toDouble())
                drawLine(
                    color = Color.White,
                    start = Offset(center.x + (rayIn * kotlin.math.cos(a)).toFloat(), center.y + (rayIn * kotlin.math.sin(a)).toFloat()),
                    end = Offset(center.x + (rayOut * kotlin.math.cos(a)).toFloat(), center.y + (rayOut * kotlin.math.sin(a)).toFloat()),
                    strokeWidth = 1.6.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .width(trackWidth)
                .height(trackHeight)
                .clip(RoundedCornerShape(16.dp))
                .glassBackground(shape = RoundedCornerShape(16.dp), tintAlpha = 0.14f)
                .onSizeChanged { trackPx = it.height.toFloat().coerceAtLeast(1f) }
                .pointerInput(minIndex, maxIndex) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            currentInteract()
                            currentOnChange(indexForFraction(1f - pos.y / trackPx))
                        },
                        onDragEnd = { currentInteract() },
                        onDragCancel = { currentInteract() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            currentInteract()
                            // Moving up (negative dy) raises the fraction = brighter.
                            currentOnChange(indexForFraction(currentFraction - dragAmount.y / trackPx))
                        }
                    )
                }
        ) {
            val knobSize = 22.dp
            val trackLen = trackHeight - knobSize
            val knobOffset by animateFloatAsState(
                targetValue = fraction,
                animationSpec = spring(dampingRatio = 0.85f, stiffness = 900f),
                label = "evKnob"
            )

            // Center (EV 0) tick.
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(width = 10.dp, height = 1.6.dp)
                    .background(Color.White.copy(alpha = 0.45f), RoundedCornerShape(1.dp))
            )
            // Fill from the knob to the bottom.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = knobSize / 2)
                    .width(3.5.dp)
                    .height(trackLen * fraction)
                    .background(Accent.copy(alpha = 0.85f), RoundedCornerShape(2.dp))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = trackLen * knobOffset)
                    .size(knobSize)
                    .background(Color.White, CircleShape)
            )
        }

        Spacer(Modifier.height(6.dp))
        // EV readout in stops.
        Text(
            text = String.format(Locale.US, "%+.1f", evIndex * step),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(Color.Black.copy(alpha = 0.4f))
                .padding(horizontal = 7.dp, vertical = 3.dp)
        )
        Spacer(Modifier.height(6.dp))

        // Back-to-auto button.
        val isAdjusted = evIndex != 0
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .glassBackground(shape = CircleShape, tintAlpha = if (isAdjusted) 0.42f else 0.12f)
                .clickable {
                    onInteraction()
                    if (isAdjusted) onAutoReset()
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "A",
                fontSize = 13.sp,
                fontWeight = FontWeight.Black,
                color = if (isAdjusted) Color.White else Color.White.copy(alpha = 0.6f)
            )
        }
    }
}
