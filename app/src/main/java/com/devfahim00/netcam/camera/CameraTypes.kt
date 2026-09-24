package com.devfahim00.netcam.camera

/** Capture modes supported by the camera. */
enum class CameraMode {
    PHOTO,
    PORTRAIT
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
