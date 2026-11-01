package ca.pwalker.ultratone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
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
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        input.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (input !== bitmap) input.recycle()

        val tensorBytes = ByteBuffer.allocateDirect(pixels.size * 3).order(ByteOrder.nativeOrder())
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
                val raw = Bitmap.createBitmap(mapWidth, mapHeight, Bitmap.Config.ALPHA_8)
                raw.copyPixelsFromBuffer(buffer)
                // The exported model's output axes are rotated relative to the input image.
                val rotated = Bitmap.createBitmap(
                    raw, 0, 0, mapWidth, mapHeight, Matrix().apply { postRotate(90f) }, false
                )
                if (rotated !== raw) raw.recycle()
                val width = rotated.width
                val height = rotated.height
                val argb = IntArray(width * height)
                rotated.getPixels(argb, 0, width, 0, 0, width, height)
                rotated.recycle()
                return DepthMap(width, height, FloatArray(argb.size) { (Color.alpha(argb[it]) / 255f) })
            }
        }
    }

    override fun close() { session.close() }

    companion object {
        const val MODEL_NAME = "fused_model_uint8_256.onnx"
        private const val INPUT_SIZE = 256
    }
}
