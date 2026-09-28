package com.devfahim00.netcam.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Horizon tilt in degrees relative to the nearest 90 degree orientation
 * (-45..45). The value is what the horizon line has to be rotated by on
 * screen (Compose rotates clockwise for positive angles) to stay level.
 * Null while disabled, while the phone lies flat, or without an accelerometer.
 */
@Composable
fun rememberTiltDegrees(enabled: Boolean): State<Float?> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val tilt = remember { mutableStateOf<Float?>(null) }

    DisposableEffect(enabled, lifecycleOwner) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val listener = object : SensorEventListener {
            private var fx = 0f
            private var fy = 0f
            private var fz = 0f
            private var primed = false

            override fun onSensorChanged(event: SensorEvent) {
                val v = event.values
                if (!primed) {
                    fx = v[0]; fy = v[1]; fz = v[2]
                    primed = true
                } else {
                    val a = 0.15f // low-pass to calm hand jitter
                    fx += a * (v[0] - fx)
                    fy += a * (v[1] - fy)
                    fz += a * (v[2] - fz)
                }
                val g = sqrt(fx * fx + fy * fy + fz * fz).coerceAtLeast(0.001f)
                if (abs(fz) / g > 0.9f) {
                    // Lying flat: roll is meaningless.
                    if (tilt.value != null) tilt.value = null
                    return
                }
                val raw = Math.toDegrees(atan2(fx.toDouble(), fy.toDouble())).toFloat()
                val normalized = ((raw + 45f) % 90f + 90f) % 90f - 45f
                val current = tilt.value
                if (current == null || abs(current - normalized) >= 0.4f) {
                    tilt.value = normalized
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        var registered = false
        fun register() {
            if (!registered && enabled && sm != null && sensor != null) {
                registered = sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            }
        }
        fun unregister() {
            if (registered) {
                sm?.unregisterListener(listener)
                registered = false
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> register()
                Lifecycle.Event.ON_PAUSE -> unregister()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) register()
        if (!enabled) tilt.value = null

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            unregister()
        }
    }
    return tilt
}
