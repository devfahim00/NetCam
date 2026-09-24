package com.devfahim00.netcam.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.devfahim00.netcam.R
import com.devfahim00.netcam.camera.AspectRatioOption
import com.devfahim00.netcam.camera.PictureQuality
import com.devfahim00.netcam.settings.AppSettings

/**
 * Bottom glass sheet with all capture settings: aspect ratio, HDR,
 * picture quality, selfie mirroring, auto enhance.
 */
@Composable
fun SettingsSheet(
    visible: Boolean,
    settings: AppSettings,
    onDismiss: () -> Unit,
    onChange: (AppSettings) -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 2 },
        exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 2 }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 16.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .glassBackground(
                        shape = RoundedCornerShape(26.dp),
                        tintAlpha = 0.14f
                    )
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                SettingsRow(title = stringResource(R.string.settings_aspect)) {
                    SegmentedPill(
                        options = AspectRatioOption.entries,
                        selected = settings.aspect,
                        label = { it.label },
                        onSelect = { onChange(settings.copy(aspect = it)) }
                    )
                }

                SettingsRow(
                    title = stringResource(R.string.settings_hdr),
                    subtitle = stringResource(R.string.settings_hdr_hint)
                ) {
                    GlassSwitch(
                        checked = settings.hdr,
                        onCheckedChange = { onChange(settings.copy(hdr = it)) }
                    )
                }

                SettingsRow(title = stringResource(R.string.settings_quality)) {
                    SegmentedPill(
                        options = PictureQuality.entries,
                        selected = settings.quality,
                        label = { it.label },
                        onSelect = { onChange(settings.copy(quality = it)) }
                    )
                }

                SettingsRow(
                    title = stringResource(R.string.settings_mirror),
                    subtitle = stringResource(R.string.settings_mirror_hint)
                ) {
                    GlassSwitch(
                        checked = settings.mirrorFront,
                        onCheckedChange = { onChange(settings.copy(mirrorFront = it)) }
                    )
                }

                SettingsRow(
                    title = stringResource(R.string.settings_enhance),
                    subtitle = stringResource(R.string.settings_enhance_hint)
                ) {
                    GlassSwitch(
                        checked = settings.autoEnhance,
                        onCheckedChange = { onChange(settings.copy(autoEnhance = it)) }
                    )
                }

                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_footer),
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.45f)
                )
            }
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.55f)
                )
            }
        }
        trailing()
    }
}
