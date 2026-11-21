package ca.pwalker.ultratone

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import kotlin.math.abs

/** Projects the original sight line into the current view while a tap lock is active. */
internal class TapFocusMotion(private val sensors: SensorManager) : SensorEventListener {
    private val matrix = FloatArray(9)
    private var ray = FloatArray(3)
    private var world: FloatArray? = null
    private var rotation = Surface.ROTATION_0
    private var horizontal = 1f
    private var vertical = 1f
    private var forward = -1f
    private var onPosition: ((Float, Float) -> Unit)? = null
    private var onOutside: (() -> Unit)? = null

    fun start(x: Float, y: Float, halfWidth: Float, halfHeight: Float, displayRotation: Int,
              front: Boolean, position: (Float, Float) -> Unit, outside: () -> Unit) {
        stop()
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return
        horizontal = halfWidth
        vertical = halfHeight
        rotation = displayRotation
        forward = if (front) 1f else -1f
        val right = (2f * x - 1f) * horizontal
        val up = (1f - 2f * y) * vertical
        ray = when (rotation) {
            Surface.ROTATION_90 -> floatArrayOf(-up, right, forward)
            Surface.ROTATION_180 -> floatArrayOf(-right, -up, forward)
            Surface.ROTATION_270 -> floatArrayOf(up, -right, forward)
            else -> floatArrayOf(right, up, forward)
        }
        onPosition = position
        onOutside = outside
        sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (onOutside == null) return
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        val target = world
        if (target == null) {
            world = FloatArray(3) { i -> matrix[i*3]*ray[0] + matrix[i*3+1]*ray[1] + matrix[i*3+2]*ray[2] }
            return
        }
        fun component(i: Int) = matrix[i]*target[0] + matrix[i+3]*target[1] + matrix[i+6]*target[2]
        val z = component(2) * forward
        val dx = component(0)
        val dy = component(1)
        val right = when (rotation) { Surface.ROTATION_90 -> dy; Surface.ROTATION_180 -> -dx; Surface.ROTATION_270 -> -dy; else -> dx }
        val up = when (rotation) { Surface.ROTATION_90 -> -dx; Surface.ROTATION_180 -> -dy; Surface.ROTATION_270 -> dx; else -> dy }
        if (z <= 0 || abs(right) > z * horizontal * 1.06f || abs(up) > z * vertical * 1.06f) {
            onOutside?.invoke()
        } else {
            onPosition?.invoke((0.5f + right / (2f * z * horizontal)).coerceIn(0f, 1f),
                (0.5f - up / (2f * z * vertical)).coerceIn(0f, 1f))
        }
    }

    fun stop() {
        sensors.unregisterListener(this)
        onPosition = null
        onOutside = null
        world = null
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
