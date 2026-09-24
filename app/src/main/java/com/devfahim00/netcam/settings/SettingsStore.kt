package com.devfahim00.netcam.settings

import android.content.Context
import com.devfahim00.netcam.camera.AspectRatioOption
import com.devfahim00.netcam.camera.PictureQuality

/** User-adjustable camera settings (persisted via SharedPreferences). */
data class AppSettings(
    val aspect: AspectRatioOption = AspectRatioOption.R4_3,
    val hdr: Boolean = false,
    val quality: PictureQuality = PictureQuality.STANDARD,
    val mirrorFront: Boolean = true,
    val autoEnhance: Boolean = true,
    val bokehStrength: Float = 0.6f
)

/** Tiny persistence helper — writes are rare (settings changes only). */
object SettingsStore {

    private const val PREFS = "netcam_settings"
    private const val KEY_ASPECT = "aspect"
    private const val KEY_HDR = "hdr"
    private const val KEY_QUALITY = "quality"
    private const val KEY_MIRROR_FRONT = "mirror_front"
    private const val KEY_AUTO_ENHANCE = "auto_enhance"
    private const val KEY_BOKEH = "bokeh_strength"

    fun load(context: Context): AppSettings {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AppSettings(
            aspect = runCatching {
                AspectRatioOption.valueOf(prefs.getString(KEY_ASPECT, null) ?: "")
            }.getOrDefault(AspectRatioOption.R4_3),
            hdr = prefs.getBoolean(KEY_HDR, false),
            quality = runCatching {
                PictureQuality.valueOf(prefs.getString(KEY_QUALITY, null) ?: "")
            }.getOrDefault(PictureQuality.STANDARD),
            mirrorFront = prefs.getBoolean(KEY_MIRROR_FRONT, true),
            autoEnhance = prefs.getBoolean(KEY_AUTO_ENHANCE, true),
            bokehStrength = prefs.getFloat(KEY_BOKEH, 0.6f)
        )
    }

    fun save(context: Context, settings: AppSettings) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ASPECT, settings.aspect.name)
            .putBoolean(KEY_HDR, settings.hdr)
            .putString(KEY_QUALITY, settings.quality.name)
            .putBoolean(KEY_MIRROR_FRONT, settings.mirrorFront)
            .putBoolean(KEY_AUTO_ENHANCE, settings.autoEnhance)
            .putFloat(KEY_BOKEH, settings.bokehStrength)
            .apply()
    }
}
