package com.devfahim00.netcam.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devfahim00.netcam.ui.theme.Accent
import java.util.Locale
import kotlin.math.roundToInt

/** A slim glassy vertical slider (used for manual focus distance). */
@Composable
fun GlassSliderVertical(
    fraction: Float,
    modifier: Modifier = Modifier,
    onFractionChange: (Float) -> Unit
) {
    var trackPx by remember { mutableFloatStateOf(1f) }

    BoxWithConstraints(
        modifier = modifier
            .width(46.dp)
            .height(216.dp)
            .clip(RoundedCornerShape(23.dp))
            .glassBackground(shape = RoundedCornerShape(23.dp))
            .onSizeChanged { trackPx = it.height.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onFractionChange((offset.y / trackPx).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onFractionChange(
                        (fraction + dragAmount.y / trackPx).coerceIn(0f, 1f)
                    )
                }
            }
    ) {
        val knobSize = 30.dp
        val trackLen = maxHeight - knobSize
        val knobOffset by animateFloatAsState(
            targetValue = fraction,
            animationSpec = spring(dampingRatio = 0.85f, stiffness = 900f),
            label = "mfKnob"
        )

        // Fill from the top (infinity) down to the knob.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = knobSize / 2)
                .width(4.dp)
                .height(trackLen * fraction)
                .background(Accent.copy(alpha = 0.8f), RoundedCornerShape(2.dp))
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = trackLen * knobOffset)
                .size(knobSize)
                .background(Color.White, CircleShape)
        )
    }
}

/** Human-readable focus distance from diopters. */
fun focusDistanceLabel(diopters: Float, maxDiopters: Float): String {
    val clamped = diopters.coerceIn(0f, maxDiopters)
    if (clamped < 0.05f) return "∞"
    val meters = 1f / clamped
    return if (meters >= 1f) {
        String.format(Locale.US, "%.1fm", meters)
    } else {
        "${(meters * 100).roundToInt()}cm"
    }
}

/** Horizontal glass slider with a leading label (used for bokeh strength). */
@Composable
fun GlassSliderHorizontal(
    fraction: Float,
    modifier: Modifier = Modifier,
    onFractionChange: (Float) -> Unit
) {
    var trackPx by remember { mutableFloatStateOf(1f) }

    BoxWithConstraints(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .glassBackground(shape = RoundedCornerShape(20.dp))
            .onSizeChanged { trackPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onFractionChange((offset.x / trackPx).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onFractionChange(
                        (fraction + dragAmount.x / trackPx).coerceIn(0f, 1f)
                    )
                }
            }
    ) {
        val knobSize = 26.dp
        val trackLen = maxWidth - knobSize
        val knobOffset by animateFloatAsState(
            targetValue = fraction,
            animationSpec = spring(dampingRatio = 0.85f, stiffness = 900f),
            label = "bokehKnob"
        )

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = knobSize / 2)
                .width(trackLen * fraction)
                .height(4.dp)
                .background(Accent.copy(alpha = 0.8f), RoundedCornerShape(2.dp))
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = trackLen * knobOffset)
                .size(knobSize)
                .background(Color.White, CircleShape)
        )
    }
}

/** Portrait-mode bokeh strength slider with a faux aperture readout. */
@Composable
fun BokehSlider(
    strength: Float,
    modifier: Modifier = Modifier,
    onChange: (Float) -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .glassBackground(shape = RoundedCornerShape(20.dp), tintAlpha = 0.10f)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Blur",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.85f)
        )
        Spacer(Modifier.width(10.dp))
        GlassSliderHorizontal(
            fraction = strength,
            onFractionChange = onChange,
            modifier = Modifier
                .weight(1f)
                .height(24.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = String.format(Locale.US, "f/%.1f", 5.6f - 4.2f * strength),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.75f),
            textAlign = TextAlign.Center
        )
    }
}
