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

/** A tap-to-focus event with a unique id so the indicator can re-animate. */
data class FocusTarget(
    val x: Float,
    val y: Float,
    val id: Long
)
