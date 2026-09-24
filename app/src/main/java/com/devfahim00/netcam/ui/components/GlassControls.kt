package com.devfahim00.netcam.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devfahim00.netcam.R
import com.devfahim00.netcam.camera.AspectRatioOption
import com.devfahim00.netcam.ui.theme.Accent

/** Cycles the output aspect ratio: 4:3 -> 16:9 -> 1:1 -> 4:3. */
@Composable
fun AspectChip(
    aspect: AspectRatioOption,
    modifier: Modifier = Modifier,
    onCycle: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .glassBackground(shape = RoundedCornerShape(percent = 50))
            .clickable(onClick = onCycle)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(
            text = aspect.label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (aspect == AspectRatioOption.R4_3) {
                Color.White.copy(alpha = 0.9f)
            } else {
                Accent
            }
        )
    }
}

/** HDR quick toggle. */
@Composable
fun HdrChip(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .glassBackground(
                shape = RoundedCornerShape(percent = 50),
                tintAlpha = if (enabled) 0.30f else 0.12f
            )
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(
            text = stringResource(R.string.hdr_label),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            color = if (enabled) Accent else Color.White.copy(alpha = 0.9f)
        )
    }
}

/** AF/MF quick toggle (only shown when the lens supports manual focus). */
@Composable
fun FocusModeChip(
    manual: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .glassBackground(
                shape = RoundedCornerShape(percent = 50),
                tintAlpha = if (manual) 0.30f else 0.12f
            )
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(
            text = stringResource(if (manual) R.string.mf_label else R.string.af_label),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            color = if (manual) Accent else Color.White.copy(alpha = 0.9f)
        )
    }
}

/** Settings (gear) glass button. */
@Composable
fun SettingsButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    GlassIconButton(
        modifier = modifier,
        size = 44.dp,
        onClick = onClick
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_settings),
            contentDescription = stringResource(R.string.cd_settings),
            tint = Color.White.copy(alpha = 0.92f),
            modifier = Modifier.size(20.dp)
        )
    }
}

/** Small "night mode active" pill shown when the scene is dark. */
@Composable
fun NightChip(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .glassBackground(
                shape = RoundedCornerShape(percent = 50),
                tintAlpha = 0.26f
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Canvas(Modifier.size(11.dp)) {
            // Crescent moon.
            drawArc(
                color = Accent,
                startAngle = 110f,
                sweepAngle = 260f,
                useCenter = false,
                style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
            )
        }
        Text(
            text = stringResource(R.string.night_chip),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            color = Accent
        )
    }
}

/** Minimal glass-styled switch. */
@Composable
fun GlassSwitch(
    checked: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .width(46.dp)
            .height(27.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(
                if (checked) Accent.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.16f)
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = { onCheckedChange(!checked) }
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .padding(start = if (checked) 22.dp else 3.dp)
                .size(21.dp)
                .background(Color.White, CircleShape)
        )
    }
}

/** Small segmented selector used inside the settings sheet. */
@Composable
fun <T> SegmentedPill(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .glassBackground(shape = RoundedCornerShape(percent = 50), tintAlpha = 0.08f)
            .padding(4.dp)
    ) {
        options.forEach { option ->
            val isSel = option == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(
                        if (isSel) Color.White.copy(alpha = 0.24f) else Color.Transparent
                    )
                    .clickable { if (!isSel) onSelect(option) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = label(option),
                    fontSize = 12.sp,
                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSel) Color.White else Color.White.copy(alpha = 0.6f)
                )
            }
        }
    }
}
