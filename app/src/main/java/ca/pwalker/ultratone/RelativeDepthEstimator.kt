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

/** On-device monocular relative-depth inference. It estimates ordering, not metric distance. */
internal class RelativeDepthEstimator(context: Context) : AutoCloseable {
    data class DepthMap(val width: Int, val height: Int, val values: FloatArray)

    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    private val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val tensorBytes = ByteBuffer.allocateDirect(pixels.size * 3).order(ByteOrder.nativeOrder())

    init {
        val model = context.applicationContext.assets.open(MODEL_NAME).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
        session = environment.createSession(model, options)
        options.close()
        inputName = session.inputNames.first()
    }

    @Synchronized
    fun estimate(bitmap: Bitmap): DepthMap {
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
                for (y in 0 until mapHeight) {
                    for (x in 0 until mapWidth) {
                        // Rotate the model output clockwise into camera coordinates.
                        values[x * mapHeight + mapHeight - 1 - y] = (buffer.get().toInt() and 255) / 255f
                    }
                }
                return DepthMap(mapHeight, mapWidth, refineDarkEdges(values, mapHeight, mapWidth))
            }
        }
    }

    // Pull uncertain dark boundary pixels toward nearby dark fur on the nearer
    // side, but only where the image itself has a visible silhouette.
    private fun refineDarkEdges(depth: FloatArray, width: Int, height: Int): FloatArray {
        fun brightness(x: Int, y: Int): Int {
            val c = pixels[(y * INPUT_SIZE / height) * INPUT_SIZE + x * INPUT_SIZE / width]
            return (54 * Color.red(c) + 183 * Color.green(c) + 19 * Color.blue(c)) shr 8
        }
        val result = depth.copyOf()
        for (y in 2 until height - 2) for (x in 2 until width - 2) {
            val center = brightness(x, y)
            if (center > 80) continue
            val i = y * width + x
            var nearest = depth[i]
            var contrast = 0
            for (dy in -2..2 step 2) for (dx in -2..2 step 2) {
                val neighbor = brightness(x + dx, y + dy)
                contrast = maxOf(contrast, neighbor - center)
                if (kotlin.math.abs(neighbor - center) <= 16)
                    nearest = maxOf(nearest, depth[i + dy * width + dx])
            }
            if (contrast >= 20) result[i] = nearest
        }
        return result
    }

    override fun close() { session.close() }

    companion object {
        const val MODEL_NAME = "fused_model_uint8_256.onnx"
        private const val INPUT_SIZE = 256
    }
}
