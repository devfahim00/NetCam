package com.devfahim00.netcam.camera

import android.hardware.camera2.CameraMetadata

/** Capture modes supported by the camera. */
enum class CameraMode {
    PHOTO,
    PORTRAIT,
    PRO
}

/** Composition grid drawn over the preview. */
enum class GridOption(val label: String) {
    OFF("Grid"),
    THIRDS("3x3"),
    FOURTHS("4x4");

    fun next(): GridOption = when (this) {
        OFF -> THIRDS
        THIRDS -> FOURTHS
        FOURTHS -> OFF
    }
}

/** Self-timer delay. */
enum class TimerOption(val seconds: Int, val label: String) {
    OFF(0, "Timer"),
    S3(3, "3s"),
    S10(10, "10s");

    fun next(): TimerOption = when (this) {
        OFF -> S3
        S3 -> S10
        S10 -> OFF
    }
}

/** Pro-mode white balance presets (mapped to Camera2 AWB modes). */
enum class WhiteBalanceOption(val label: String, val awbMode: Int) {
    AUTO("Auto", CameraMetadata.CONTROL_AWB_MODE_AUTO),
    INCANDESCENT("2700K", CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
    FLUORESCENT("4000K", CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT),
    DAYLIGHT("5500K", CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
    CLOUDY("6500K", CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT)
}

/** Flash modes cycled through the flash pill. */
enum class FlashMode(val label: String) {
    OFF("Off"),
    AUTO("Auto"),
    ON("On"),
    TORCH("Torch");

    fun next(): FlashMode = when (this) {
        OFF -> AUTO
        AUTO -> ON
        ON -> TORCH
        TORCH -> OFF
    }
}

/** Output aspect ratio options. SQUARE captures a 4:3 stream and center-crops. */
enum class AspectRatioOption(val label: String) {
    R4_3("4:3"),
    R16_9("16:9"),
    SQUARE("1:1");

    fun next(): AspectRatioOption = when (this) {
        R4_3 -> R16_9
        R16_9 -> SQUARE
        SQUARE -> R4_3
    }
}

/** Picture quality tiers: max megapixels + JPEG compression quality. */
enum class PictureQuality(
    val label: String,
    val maxPixels: Int,
    val jpegQuality: Int
) {
    STANDARD("Standard", 12_000_000, 88),
    HIGH("High", 16_000_000, 93),
    MAX("Max", 24_000_000, 97)
}

/** Outcome of a tap-to-focus AF cycle, used to color the focus ring. */
enum class FocusResult { PENDING, SUCCESS, FAIL }

/** A tap-to-focus event with a unique id so the indicator can re-animate. */
data class FocusTarget(
    val x: Float,
    val y: Float,
    val id: Long,
    val result: FocusResult = FocusResult.PENDING
)

/** Live stage feedback while a portrait is being processed. */
enum class PortraitStage { DETECT, REFINE, BOKEH, ENHANCE }
