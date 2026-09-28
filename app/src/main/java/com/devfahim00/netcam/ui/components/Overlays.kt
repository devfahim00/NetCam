package com.devfahim00.netcam.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devfahim00.netcam.camera.GridOption
import com.devfahim00.netcam.camera.WhiteBalanceOption
import com.devfahim00.netcam.ui.theme.Accent
import kotlin.math.abs
import kotlin.math.min

private val LevelYellow = Color(0xFFFFD60A)

/** Rule-of-thirds (3x3) or 4x4 composition grid. */
@Composable
fun GridOverlay(
    option: GridOption,
    squareOnly: Boolean,
    modifier: Modifier = Modifier
) {
    if (option == GridOption.OFF) return
    val divisions = if (option == GridOption.THIRDS) 3 else 4
    Canvas(modifier.fillMaxSize()) {
        val side = min(size.width, size.height)
        val w = if (squareOnly) side else size.width
        val h = if (squareOnly) side else size.height
        val left = (size.width - w) / 2f
        val top = (size.height - h) / 2f
        val stroke = 1.dp.toPx()
        val color = Color.White.copy(alpha = 0.35f)
        for (i in 1 until divisions) {
            val x = left + w * i / divisions
            val y = top + h * i / divisions
            drawLine(color, Offset(x, top), Offset(x, top + h), strokeWidth = stroke)
            drawLine(color, Offset(left, y), Offset(left + w, y), strokeWidth = stroke)
        }
    }
}

/**
 * Horizon level: two fixed reference ticks and a line that counter-rotates
 * with the phone. Everything turns yellow when within 1 degree of level.
 */
@Composable
fun LevelIndicator(
    tiltDegrees: Float,
    modifier: Modifier = Modifier
) {
    val level = abs(tiltDegrees) < 1f
    val color = if (level) LevelYellow else Color.White.copy(alpha = 0.85f)
    Canvas(modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val stroke = 2.dp.toPx()
        val tick = 18.dp.toPx()
        val gap = 84.dp.toPx()
        drawLine(color, Offset(cx - gap - tick, cy), Offset(cx - gap, cy), strokeWidth = stroke)
        drawLine(color, Offset(cx + gap, cy), Offset(cx + gap + tick, cy), strokeWidth = stroke)
        val half = if (level) gap else 60.dp.toPx()
        rotate(degrees = tiltDegrees, pivot = Offset(cx, cy)) {
            drawLine(color, Offset(cx - half, cy), Offset(cx + half, cy), strokeWidth = stroke)
        }
    }
}

/** Live luminance histogram. [bins] are already normalized to 0..1. */
@Composable
fun HistogramView(
    bins: FloatArray?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(width = 104.dp, height = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(4.dp)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val data = bins ?: return@Canvas
            if (data.isEmpty()) return@Canvas
            val barW = size.width / data.size
            for (i in data.indices) {
                val bh = (data[i].coerceIn(0f, 1f) * size.height).coerceAtLeast(1f)
                drawRect(
                    color = Color.White.copy(alpha = 0.8f),
                    topLeft = Offset(i * barW, size.height - bh),
                    size = androidx.compose.ui.geometry.Size((barW - 0.5f).coerceAtLeast(0.5f), bh)
                )
            }
        }
    }
}

/** Big self-timer number in the middle of the preview. */
@Composable
fun CountdownOverlay(
    seconds: Int?,
    modifier: Modifier = Modifier
) {
    if (seconds == null) return
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = seconds.toString(),
            style = TextStyle(
                fontSize = 110.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                shadow = Shadow(Color.Black.copy(alpha = 0.5f), Offset(0f, 4f), 14f)
            )
        )
    }
}

/** Small glass pill used for the extra quick toggles (timer, grid, level...). */
@Composable
fun ToggleChip(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .glassBackground(
                shape = RoundedCornerShape(percent = 50),
                tintAlpha = if (active) 0.30f else 0.12f
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            color = if (active) Accent else Color.White.copy(alpha = 0.9f)
        )
    }
}

/** Shown while AE/AF are locked; tap to release. */
@Composable
fun LockChip(
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    ToggleChip(label = "AE/AF LOCK", active = true, modifier = modifier, onClick = onClick)
}

/**
 * Pro-mode control panel: manual exposure (ISO + shutter speed) and white
 * balance presets. Sliders work on a 0..1 fraction; the caller maps it to a
 * log-scaled ISO / shutter value.
 */
@Composable
fun ProPanel(
    manualSupported: Boolean,
    manualExposure: Boolean,
    onManualToggle: () -> Unit,
    isoFraction: Float,
    isoText: String,
    onIsoFraction: (Float) -> Unit,
    shutterFraction: Float,
    shutterText: String,
    onShutterFraction: (Float) -> Unit,
    wbOptions: List<WhiteBalanceOption>,
    wb: WhiteBalanceOption,
    onWbChange: (WhiteBalanceOption) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .glassBackground(shape = RoundedCornerShape(20.dp), tintAlpha = 0.10f)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (manualSupported) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProLabel("EXP")
                ToggleChip(
                    label = if (manualExposure) "MANUAL" else "AUTO",
                    active = manualExposure,
                    onClick = onManualToggle
                )
            }
            if (manualExposure) {
                ProSliderRow("ISO", isoFraction, isoText, onIsoFraction)
                ProSliderRow("SS", shutterFraction, shutterText, onShutterFraction)
            }
        }
        if (wbOptions.size > 1) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProLabel("WB")
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    wbOptions.forEach { option ->
                        ToggleChip(
                            label = option.label,
                            active = option == wb,
                            onClick = { onWbChange(option) }
                        )
                    }
                }
            }
        }
        if (!manualSupported && wbOptions.size <= 1) {
            Text(
                text = "Manual controls are not supported by this camera",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.75f)
            )
        }
    }
}

@Composable
private fun ProLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color.White.copy(alpha = 0.85f),
        modifier = Modifier.width(38.dp)
    )
}

@Composable
private fun ProSliderRow(
    label: String,
    fraction: Float,
    valueText: String,
    onFraction: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ProLabel(label)
        GlassSliderHorizontal(
            fraction = fraction,
            onFractionChange = onFraction,
            modifier = Modifier
                .weight(1f)
                .height(24.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = valueText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.85f),
            textAlign = TextAlign.End,
            modifier = Modifier.width(52.dp)
        )
    }
}
