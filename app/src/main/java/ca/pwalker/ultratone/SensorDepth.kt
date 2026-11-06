package ca.pwalker.ultratone

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics as C
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.camera.camera2.internal.Camera2UseCaseConfigFactory
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.impl.ImmediateSurface
import androidx.camera.core.impl.UseCaseConfig
import androidx.camera.core.impl.UseCaseConfigFactory
import java.nio.ByteOrder
import kotlin.math.abs

/** A bounded history from the same camera session as the still capture. */
internal class SensorDepth private constructor(val reader: ImageReader) {
    data class Frame(val time: Long, val width: Int, val height: Int, val packed: ShortArray)
    data class Sample(val frame: Frame, val rotation: Int) {
        fun fuse(map: RelativeDepthEstimator.DepthMap, width: Int, height: Int): RelativeDepthEstimator.DepthMap {
            val side = rotation == 90 || rotation == 270
            val aspect = if (side) frame.height.toFloat() / frame.width else frame.width.toFloat() / frame.height
            if (abs(aspect * height / width - 1f) > 0.025f) return map
            val measured = FloatArray(map.values.size)
            for (y in 0 until map.height) for (x in 0 until map.width) {
                val u = (x + 0.5f) / map.width
                val v = (y + 0.5f) / map.height
                val sx = when (rotation) { 90 -> v; 180 -> 1f-u; 270 -> 1f-v; else -> u }
                val sy = when (rotation) { 90 -> 1f-u; 180 -> 1f-v; 270 -> u; else -> v }
                val word = frame.packed[(sy * frame.height).toInt().coerceIn(0,frame.height-1) * frame.width +
                    (sx * frame.width).toInt().coerceIn(0,frame.width-1)].toInt() and 65535
                val mm = word and 8191
                val confidence = word ushr 13
                if (mm > 0 && (confidence == 0 || confidence >= 5)) measured[y * map.width + x] = 1000f / mm
            }
            // Fit inverse metres to the model's relative scale. Reject frames whose
            // scene geometry disagrees, including bad alignment or intervening motion.
            var n = 0; var sx = 0.0; var sy = 0.0; var xx = 0.0; var yy = 0.0; var xy = 0.0
            for (i in measured.indices) if (measured[i] > 0f) {
                val x = measured[i].toDouble(); val y = map.values[i].toDouble()
                n++; sx += x; sy += y; xx += x*x; yy += y*y; xy += x*y
            }
            if (n < measured.size / 12) return map
            val vx = xx - sx*sx/n; val vy = yy - sy*sy/n; val cov = xy - sx*sy/n
            if (vx <= 0 || vy <= 0 || cov <= 0 || cov*cov < 0.70 * vx*vy) return map
            val slope = cov/vx; val intercept = (sy - slope*sx)/n
            val values = map.values.copyOf()
            for (i in values.indices) if (measured[i] > 0f) {
                val sensor = (slope*measured[i]+intercept).toFloat().coerceIn(0f,1f)
                if (abs(sensor-values[i]) < 0.30f) values[i] += (sensor-values[i])*0.65f
            }
            return map.copy(values = values)
        }
    }

    private val thread = HandlerThread("Bokeh depth").apply { start() }
    private val history = ArrayDeque<Frame>()
    private val surface = ImmediateSurface(reader.surface, android.util.Size(reader.width, reader.height), ImageFormat.DEPTH16)
    init {
        reader.setOnImageAvailableListener({ r ->
            val image = try { r.acquireLatestImage() } catch (_: IllegalStateException) { null }
            image?.use {
                val plane = it.planes[0]
                val buf = plane.buffer.duplicate().order(ByteOrder.nativeOrder())
                val data = ShortArray(it.width * it.height)
                for (y in 0 until it.height) for (x in 0 until it.width)
                    data[y*it.width+x] = buf.getShort(y*plane.rowStride+x*plane.pixelStride)
                synchronized(history) {
                    if (history.size >= 12) history.removeFirst()
                    history.addLast(Frame(it.timestamp,it.width,it.height,data))
                }
            }
        }, Handler(thread.looper))
        surface.terminationFuture.addListener({ reader.close(); thread.quitSafely() }, { it.run() })
    }
    fun match(time: Long, rotation: Int): Sample? = synchronized(history) {
        history.minByOrNull { abs(it.time-time) }?.takeIf { abs(it.time-time) <= 65_000_000L }?.let { Sample(it,rotation) }
    }
    fun attach(context: Context, builder: Preview.Builder, failed: () -> Unit) {
        val defaults = Camera2UseCaseConfigFactory(context).getConfig(UseCaseConfigFactory.CaptureType.PREVIEW,
            ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        val unpack = requireNotNull(defaults.retrieveOption(UseCaseConfig.OPTION_SESSION_CONFIG_UNPACKER))
        builder.setSessionOptionUnpacker { size, config, session ->
            unpack.unpack(size,config,session)
            session.addSurface(surface)
            session.addSessionStateCallback(object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {}
                override fun onConfigureFailed(session: CameraCaptureSession) { failed() }
            })
        }
    }
    fun close() = surface.close()
    companion object {
        fun open(info: C): SensorDepth? {
            if (info.get(C.DEPTH_DEPTH_IS_EXCLUSIVE) != false) return null
            val sizes = info.get(C.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.DEPTH16) ?: return null
            val size = sizes.filter { it.width * it.height <= 1024*768 && abs(it.width.toFloat()/it.height-4f/3) < 0.035f }
                .minByOrNull { abs(it.width-320) } ?: return null
            return SensorDepth(ImageReader.newInstance(size.width,size.height,ImageFormat.DEPTH16,2))
        }
    }
}
