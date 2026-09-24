package com.devfahim00.netcam.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.devfahim00.netcam.ui.theme.Accent
import com.devfahim00.netcam.ui.theme.AccentSoft

/** The big two-stage shutter: outer glass ring + inner fill + busy ring. */
@Composable
fun ShutterButton(
    isBusy: Boolean,
    isPortrait: Boolean,
    modifier: Modifier = Modifier,
    onCapture: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 700f),
        label = "shutterScale"
    )

    val innerBrush = if (isPortrait) {
        Brush.linearGradient(listOf(AccentSoft, Accent))
    } else {
        Brush.linearGradient(listOf(Color.White, Color(0xFFE8EDF2)))
    }

    Box(
        modifier = modifier
            .size(84.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f))
            .border(2.5.dp, Color.White.copy(alpha = 0.95f), CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { if (!isBusy) onCapture() }
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(innerBrush)
        )
        if (isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(76.dp),
                color = Accent,
                strokeWidth = 3.dp
            )
        }
    }
}
