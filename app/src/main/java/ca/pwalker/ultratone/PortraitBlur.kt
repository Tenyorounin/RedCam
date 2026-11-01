package ca.pwalker.ultratone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Relative-depth background blur, with person segmentation to help preserve subject edges. */
object PortraitBlur {
    data class Result(val bitmap: Bitmap, val subjectDetected: Boolean, val usedDepth: Boolean)

    fun apply(context: Context, source: Bitmap, focusX: Float = 0.5f, focusY: Float = 0.5f): Result {
        val segmenter = Segmentation.getClient(
            SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build()
        )
        return try { apply(context, source, segmenter, focusX, focusY) } finally { segmenter.close() }
    }

    /** Runs at a reduced frame rate on live preview frames; depth inference is intentionally best-effort. */
    class StreamProcessor(context: Context) : AutoCloseable {
        private val appContext = context.applicationContext
        private val segmenter = Segmentation.getClient(
            SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.STREAM_MODE).build()
        )

        fun apply(source: Bitmap, focusX: Float = 0.5f, focusY: Float = 0.5f): Result =
            PortraitBlur.apply(appContext, source, segmenter, focusX, focusY)

        /** Builds a compact depth/person guide for the GPU live-preview blur. */
        fun createDepthGuide(source: Bitmap): Bitmap? = PortraitBlur.createDepthGuide(appContext, source, segmenter)

        override fun close() = segmenter.close()
    }

    private fun apply(
        context: Context,
        source: Bitmap,
        segmenter: Segmenter,
        focusX: Float,
        focusY: Float
    ): Result {
        val mask = try {
            Tasks.await(segmenter.process(InputImage.fromBitmap(source, 0)), 30, TimeUnit.SECONDS)
        } catch (_: Exception) { null }
        val depth = try { DepthHolder.get(context).estimate(source) } catch (_: Exception) { null }
        return if (depth != null) applyDepth(source, mask, depth, focusX, focusY)
        else if (mask != null) applyMaskFallback(source, mask)
        else Result(source, false, false)
    }

    private fun createDepthGuide(
        context: Context,
        source: Bitmap,
        segmenter: Segmenter
    ): Bitmap? {
        val depth = try { DepthHolder.get(context).estimate(source) } catch (_: Exception) { return null }
        val mask = try {
            Tasks.await(segmenter.process(InputImage.fromBitmap(source, 0)), 10, TimeUnit.SECONDS)
        } catch (_: Exception) { null }
        val ordered = depth.values.sortedArray()
        if (ordered.isEmpty()) return null
        val low = ordered[(ordered.size * 0.06f).toInt().coerceIn(0, ordered.lastIndex)]
        val high = ordered[(ordered.size * 0.94f).toInt().coerceIn(0, ordered.lastIndex)]
        val span = (high - low).coerceAtLeast(0.035f)
        val maskWidth = mask?.width ?: 0
        val maskHeight = mask?.height ?: 0
        val confidence = mask?.buffer?.order(ByteOrder.nativeOrder())?.asFloatBuffer()?.apply { position(0) }
        val confidenceRow = FloatArray(maskWidth)
        val guideScale = 256f / max(source.width, source.height)
        val guideWidth = max(1, (source.width * guideScale).toInt())
        val guideHeight = max(1, (source.height * guideScale).toInt())
        val guide = IntArray(guideWidth * guideHeight)
        for (y in 0 until guideHeight) {
            val depthY = min(depth.height - 1, y * depth.height / guideHeight)
            val maskY = if (mask != null) min(maskHeight - 1, y * maskHeight / guideHeight) else 0
            if (confidence != null) {
                confidence.position(maskY * maskWidth)
                confidence.get(confidenceRow, 0, maskWidth)
            }
            for (x in 0 until guideWidth) {
                val depthX = min(depth.width - 1, x * depth.width / guideWidth)
                val index = depthY * depth.width + depthX
                val relative = ((depth.values[index] - low) / span).coerceIn(0f, 1f)
                val person = if (confidence != null && mask != null) {
                    val maskX = min(maskWidth - 1, x * maskWidth / guideWidth)
                    confidenceRow[maskX].coerceIn(0f, 1f)
                } else 0f
                val depthByte = (relative * 255f + 0.5f).toInt().coerceIn(0, 255)
                val personByte = (person * 255f + 0.5f).toInt().coerceIn(0, 255)
                guide[y * guideWidth + x] = Color.rgb(depthByte, personByte, 0)
            }
        }
        return Bitmap.createBitmap(guide, guideWidth, guideHeight, Bitmap.Config.ARGB_8888)
    }

    private fun applyDepth(
        source: Bitmap,
        mask: com.google.mlkit.vision.segmentation.SegmentationMask?,
        depth: RelativeDepthEstimator.DepthMap,
        focusX: Float,
        focusY: Float
    ): Result {
        val ordered = depth.values.sortedArray()
        val low = ordered[(ordered.size * 0.06f).toInt().coerceIn(0, ordered.lastIndex)]
        val high = ordered[(ordered.size * 0.94f).toInt().coerceIn(0, ordered.lastIndex)]
        val span = (high - low).coerceAtLeast(0.035f)
        val sampleX = (focusX.coerceIn(0f, 1f) * (depth.width - 1)).toInt()
        val sampleY = (focusY.coerceIn(0f, 1f) * (depth.height - 1)).toInt()
        val focusSamples = ArrayList<Float>(81)
        for (dy in -4..4) for (dx in -4..4) {
            val x = (sampleX + dx).coerceIn(0, depth.width - 1)
            val y = (sampleY + dy).coerceIn(0, depth.height - 1)
            focusSamples += depth.values[y * depth.width + x]
        }
        focusSamples.sort()
        val focusDepth = focusSamples[focusSamples.size / 2]
        val background = blurredBackground(source)
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val row = IntArray(source.width)
        val bgRow = IntArray(source.width)
        val maskWidth = mask?.width ?: 0
        val maskHeight = mask?.height ?: 0
        val confidence = mask?.buffer?.order(ByteOrder.nativeOrder())?.asFloatBuffer()?.apply { position(0) }
        val confidenceRow = FloatArray(maskWidth)
        var subjectPixels = 0

        for (y in 0 until source.height) {
            source.getPixels(row, 0, source.width, 0, y, source.width, 1)
            background.getPixels(bgRow, 0, source.width, 0, y, source.width, 1)
            val my = min(depth.height - 1, y * depth.height / source.height)
            val maskY = if (mask != null) min(maskHeight - 1, y * maskHeight / source.height) else 0
            if (confidence != null) {
                confidence.position(maskY * maskWidth)
                confidence.get(confidenceRow, 0, maskWidth)
            }
            for (x in 0 until source.width) {
                val mx = min(depth.width - 1, x * depth.width / source.width)
                val relative = ((depth.values[my * depth.width + mx] - low) / span).coerceIn(0f, 1f)
                val focused = ((focusDepth - low) / span).coerceIn(0f, 1f)
                val separation = abs(relative - focused)
                // A soft focus band avoids making the selected plane look artificially cut out.
                var blurAmount = ((separation - 0.055f) / 0.42f).coerceIn(0f, 1f)
                blurAmount *= blurAmount * 0.88f
                if (confidence != null && mask != null) {
                    val maskX = min(maskWidth - 1, x * maskWidth / source.width)
                    val person = smoothstep(((confidenceRow[maskX] - 0.30f) / 0.50f).coerceIn(0f, 1f))
                    if (person > 0.65f) subjectPixels++
                    blurAmount *= 1f - person
                }
                if (blurAmount > 0.002f) row[x] = mix(row[x], bgRow[x], blurAmount)
            }
            output.setPixels(row, 0, source.width, 0, y, source.width, 1)
        }
        background.recycle()
        val peopleFound = subjectPixels > source.width * source.height / 100
        return Result(output, peopleFound, true)
    }

    /** People-only safety fallback for the rare case where depth model initialization fails. */
    private fun applyMaskFallback(
        source: Bitmap,
        mask: com.google.mlkit.vision.segmentation.SegmentationMask
    ): Result {
        val maskWidth = mask.width
        val maskHeight = mask.height
        val values = mask.buffer.order(ByteOrder.nativeOrder()).asFloatBuffer().apply { position(0) }
        val background = blurredBackground(source)
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val row = IntArray(source.width)
        val bgRow = IntArray(source.width)
        val confidenceRow = FloatArray(maskWidth)
        var count = 0
        for (y in 0 until source.height) {
            source.getPixels(row, 0, source.width, 0, y, source.width, 1)
            background.getPixels(bgRow, 0, source.width, 0, y, source.width, 1)
            val my = min(maskHeight - 1, y * maskHeight / source.height)
            values.position(my * maskWidth)
            values.get(confidenceRow, 0, maskWidth)
            for (x in 0 until source.width) {
                val mx = min(maskWidth - 1, x * maskWidth / source.width)
                val foreground = smoothstep(((confidenceRow[mx] - 0.22f) / 0.58f).coerceIn(0f, 1f))
                if (foreground > 0.55f) count++
                row[x] = mix(bgRow[x], row[x], foreground)
            }
            output.setPixels(row, 0, source.width, 0, y, source.width, 1)
        }
        background.recycle()
        val detected = count > source.width * source.height / 100
        return if (detected) Result(output, true, false) else {
            output.recycle()
            Result(source, false, false)
        }
    }

    private fun blurredBackground(source: Bitmap): Bitmap {
        val scale = 8
        val width = max(1, source.width / scale)
        val height = max(1, source.height / scale)
        val small = Bitmap.createScaledBitmap(source, width, height, true)
        var pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        small.recycle()
        repeat(3) { pixels = boxBlur(pixels, width, height, 2) }
        val blurredSmall = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        blurredSmall.setPixels(pixels, 0, width, 0, 0, width, height)
        return Bitmap.createScaledBitmap(blurredSmall, source.width, source.height, true).also { blurredSmall.recycle() }
    }

    private fun boxBlur(input: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val horizontal = IntArray(input.size)
        val output = IntArray(input.size)
        val diameter = radius * 2 + 1
        for (y in 0 until height) {
            var rs = 0; var gs = 0; var bs = 0
            for (i in -radius..radius) {
                val c = input[y * width + i.coerceIn(0, width - 1)]
                rs += Color.red(c); gs += Color.green(c); bs += Color.blue(c)
            }
            for (x in 0 until width) {
                horizontal[y * width + x] = Color.rgb(rs / diameter, gs / diameter, bs / diameter)
                val remove = input[y * width + (x - radius).coerceIn(0, width - 1)]
                val add = input[y * width + (x + radius + 1).coerceIn(0, width - 1)]
                rs += Color.red(add) - Color.red(remove); gs += Color.green(add) - Color.green(remove); bs += Color.blue(add) - Color.blue(remove)
            }
        }
        for (x in 0 until width) {
            var rs = 0; var gs = 0; var bs = 0
            for (i in -radius..radius) {
                val c = horizontal[i.coerceIn(0, height - 1) * width + x]
                rs += Color.red(c); gs += Color.green(c); bs += Color.blue(c)
            }
            for (y in 0 until height) {
                output[y * width + x] = Color.rgb(rs / diameter, gs / diameter, bs / diameter)
                val remove = horizontal[(y - radius).coerceIn(0, height - 1) * width + x]
                val add = horizontal[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                rs += Color.red(add) - Color.red(remove); gs += Color.green(add) - Color.green(remove); bs += Color.blue(add) - Color.blue(remove)
            }
        }
        return output
    }

    private fun smoothstep(value: Float): Float = value * value * (3f - 2f * value)

    private fun mix(background: Int, foreground: Int, amount: Float): Int {
        val inv = 1f - amount
        return Color.rgb(
            (Color.red(background) * inv + Color.red(foreground) * amount).toInt(),
            (Color.green(background) * inv + Color.green(foreground) * amount).toInt(),
            (Color.blue(background) * inv + Color.blue(foreground) * amount).toInt()
        )
    }

    private object DepthHolder {
        @Volatile private var instance: RelativeDepthEstimator? = null
        fun get(context: Context): RelativeDepthEstimator = instance ?: synchronized(this) {
            instance ?: RelativeDepthEstimator(context.applicationContext).also { instance = it }
        }
    }
}
