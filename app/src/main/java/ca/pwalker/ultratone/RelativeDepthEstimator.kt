package ca.pwalker.ultratone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** On-device monocular relative-depth inference. It estimates ordering, not metric distance. */
internal class RelativeDepthEstimator(context: Context) : AutoCloseable {
    data class DepthMap(val width: Int, val height: Int, val values: FloatArray)

    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    // Preview and saved photos share the session and scratch buffers. FIFO lock
    // admission prevents repeated preview inference overtaking a waiting photo.
    private val inferenceLock = ReentrantLock(true)
    private val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val tensorBytes = ByteBuffer.allocateDirect(pixels.size * 3).order(ByteOrder.nativeOrder())

    init {
        val model = context.applicationContext.assets.open(MODEL_NAME).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
        session = environment.createSession(model, options)
        options.close()
        inputName = session.inputNames.first()
    }

    fun estimate(bitmap: Bitmap): DepthMap = inferenceLock.withLock {
        val input = if (bitmap.width == INPUT_SIZE && bitmap.height == INPUT_SIZE) bitmap
            else Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        input.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (input !== bitmap) input.recycle()

        tensorBytes.clear()
        for (color in pixels) {
            tensorBytes.put(Color.red(color).toByte())
            tensorBytes.put(Color.green(color).toByte())
            tensorBytes.put(Color.blue(color).toByte())
        }
        tensorBytes.rewind()
        val tensor = OnnxTensor.createTensor(
            environment, tensorBytes, longArrayOf(1, INPUT_SIZE.toLong(), INPUT_SIZE.toLong(), 3), OnnxJavaType.UINT8
        )
        tensor.use {
            session.run(mapOf(inputName to tensor)).use { result ->
                val output = result[0] as OnnxTensor
                val shape = output.info.shape
                val mapHeight = shape[shape.size - 2].toInt()
                val mapWidth = shape[shape.size - 1].toInt()
                val buffer = output.byteBuffer.duplicate()
                buffer.rewind()
                val values = FloatArray(mapWidth * mapHeight)
                // The oriented bitmap and the model output both use row-major image coordinates.
                for (i in values.indices) values[i] = (buffer.get().toInt() and 255) / 255f
                return@withLock DepthMap(mapWidth, mapHeight, refineDepthEdges(values, mapWidth, mapHeight))
            }
        }
    }

    // Refine against color agreement without promoting dark objects toward
    // the camera. A dark background frame can otherwise become falsely sharp.
    private fun refineDepthEdges(depth: FloatArray, width: Int, height: Int): FloatArray {
        val result = depth
        val aligned = result.copyOf()
        fun rgb(x: Int, y: Int) = pixels[(y * INPUT_SIZE / height) * INPUT_SIZE + x * INPUT_SIZE / width]
        for (y in 2 until height - 2) for (x in 2 until width - 2) {
            val i = y * width + x
            val near = maxOf(result[i - 2], result[i + 2], result[i - 2 * width], result[i + 2 * width])
            val far = minOf(result[i - 2], result[i + 2], result[i - 2 * width], result[i + 2 * width])
            if (near - far < 0.10f) continue
            val center = rgb(x, y)
            var sum = 0f
            var total = 0f
            for (dy in -2..2) for (dx in -2..2) {
                val c = rgb(x + dx, y + dy)
                val difference = kotlin.math.abs(Color.red(c) - Color.red(center)) +
                    kotlin.math.abs(Color.green(c) - Color.green(center)) +
                    kotlin.math.abs(Color.blue(c) - Color.blue(center))
                val similarity = (1f - difference / 72f).coerceAtLeast(0f)
                val weight = similarity * similarity
                sum += result[i + dy * width + dx] * weight
                total += weight
            }
            aligned[i] = sum / total
        }
        return aligned
    }

    override fun close() { session.close() }

    companion object {
        const val MODEL_NAME = "fused_model_uint8_256.onnx"
        private const val INPUT_SIZE = 256
    }
}
