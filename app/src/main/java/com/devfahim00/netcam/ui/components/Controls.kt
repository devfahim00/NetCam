package com.devfahim00.netcam.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devfahim00.netcam.R
import com.devfahim00.netcam.camera.FlashMode
import com.devfahim00.netcam.ui.theme.Accent
import java.util.Locale

/** Flash toggle pill: icon + current mode label, cycles Off -> Auto -> On -> Torch. */
@Composable
fun FlashPill(
    flashMode: FlashMode,
    modifier: Modifier = Modifier,
    onCycle: () -> Unit
) {
    GlassIconButton(
        modifier = modifier,
        size = 48.dp,
        shape = RoundedCornerShape(percent = 50),
        onClick = onCycle
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_flash),
                contentDescription = stringResource(R.string.cd_flash_mode),
                tint = if (flashMode == FlashMode.OFF) {
                    Color.White.copy(alpha = 0.85f)
                } else {
                    Accent
                },
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = flashMode.label,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.92f),
                maxLines = 1
            )
        }
    }
}

/** Zoom chip; tap to reset zoom back to 1x. */
@Composable
fun ZoomChip(
    zoom: Float,
    modifier: Modifier = Modifier,
    onReset: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .glassBackground(shape = RoundedCornerShape(percent = 50))
            .clickable(onClick = onReset)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text = String.format(Locale.US, "%.1fx", zoom),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
    }
}

/** Gallery shortcut: shows the last captured photo as a circular thumbnail. */
@Composable
fun GalleryButton(
    thumbnail: ImageBitmap?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(52.dp)
            .clip(CircleShape)
            .glassBackground(shape = CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail,
                contentDescription = stringResource(R.string.cd_gallery),
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_gallery),
                contentDescription = stringResource(R.string.cd_gallery),
                tint = Color.White.copy(alpha = 0.92f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
