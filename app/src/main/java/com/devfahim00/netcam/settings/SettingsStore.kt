package com.devfahim00.netcam.settings

import android.content.Context
import com.devfahim00.netcam.camera.AspectRatioOption
import com.devfahim00.netcam.camera.GridOption
import com.devfahim00.netcam.camera.TimerOption
import com.devfahim00.netcam.camera.PictureQuality

/** User-adjustable camera settings (persisted via SharedPreferences). */
data class AppSettings(
    val aspect: AspectRatioOption = AspectRatioOption.R4_3,
    val hdr: Boolean = false,
    val quality: PictureQuality = PictureQuality.STANDARD,
    val mirrorFront: Boolean = true,
    val autoEnhance: Boolean = true,
    val bokehStrength: Float = 0.6f,
    val grid: GridOption = GridOption.OFF,
    val level: Boolean = false,
    val histogram: Boolean = false,
    val timer: TimerOption = TimerOption.OFF
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
    private const val KEY_GRID = "grid"
    private const val KEY_LEVEL = "level"
    private const val KEY_HISTOGRAM = "histogram"
    private const val KEY_TIMER = "timer"

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
            bokehStrength = prefs.getFloat(KEY_BOKEH, 0.6f),
            grid = runCatching {
                GridOption.valueOf(prefs.getString(KEY_GRID, null) ?: "")
            }.getOrDefault(GridOption.OFF),
            level = prefs.getBoolean(KEY_LEVEL, false),
            histogram = prefs.getBoolean(KEY_HISTOGRAM, false),
            timer = runCatching {
                TimerOption.valueOf(prefs.getString(KEY_TIMER, null) ?: "")
            }.getOrDefault(TimerOption.OFF)
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
            .putString(KEY_GRID, settings.grid.name)
            .putBoolean(KEY_LEVEL, settings.level)
            .putBoolean(KEY_HISTOGRAM, settings.histogram)
            .putString(KEY_TIMER, settings.timer.name)
            .apply()
    }
}
