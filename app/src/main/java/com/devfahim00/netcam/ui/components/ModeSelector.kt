package com.devfahim00.netcam.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devfahim00.netcam.R
import com.devfahim00.netcam.camera.CameraMode

private val ModeItemWidth = 96.dp

/** Photo / Portrait segmented control with a sliding glass pill. */
@Composable
fun ModeSelector(
    mode: CameraMode,
    modifier: Modifier = Modifier,
    onModeChange: (CameraMode) -> Unit
) {
    GlassSurface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        contentPadding = 5.dp,
        tintAlpha = 0.08f
    ) {
        Box {
            val pillOffset by animateDpAsState(
                targetValue = if (mode == CameraMode.PHOTO) 0.dp else ModeItemWidth,
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 600f),
                label = "modePillOffset"
            )
            Box(
                modifier = Modifier
                    .offset(x = pillOffset)
                    .size(width = ModeItemWidth, height = 38.dp)
                    .background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(percent = 50))
            )
            Row {
                ModeItem(
                    label = stringResource(R.string.mode_photo),
                    selected = mode == CameraMode.PHOTO,
                    onClick = { if (mode != CameraMode.PHOTO) onModeChange(CameraMode.PHOTO) }
                )
                ModeItem(
                    label = stringResource(R.string.mode_portrait),
                    selected = mode == CameraMode.PORTRAIT,
                    onClick = { if (mode != CameraMode.PORTRAIT) onModeChange(CameraMode.PORTRAIT) }
                )
            }
        }
    }
}

@Composable
private fun ModeItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .width(ModeItemWidth)
            .height(38.dp)
            .clip(RoundedCornerShape(percent = 50))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.55f),
            letterSpacing = 0.4.sp
        )
    }
}
