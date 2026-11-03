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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Relative-depth background blur, with person segmentation to help preserve subject edges. */
object PortraitBlur {
    data class Result(val bitmap: Bitmap, val subjectDetected: Boolean, val usedDepth: Boolean)
    data class PreviewGuides(
        val depth: Bitmap,
        val focusDepth: Float
    )

    fun apply(context: Context, source: Bitmap, focusX: Float = 0.5f, focusY: Float = 0.5f, bokehLevel: Int = 0): Result {
        val segmenter = Segmentation.getClient(
            SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build()
        )
        return try { apply(context, source, segmenter, focusX, focusY, bokehLevel) } finally { segmenter.close() }
    }

    /** Runs depth and segmentation inference at a reduced rate for the live preview. */
    class StreamProcessor(context: Context) : AutoCloseable {
        private val appContext = context.applicationContext
        private val segmenter = Segmentation.getClient(
            SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.STREAM_MODE).build()
        )
        fun apply(source: Bitmap, focusX: Float = 0.5f, focusY: Float = 0.5f, bokehLevel: Int = 0): Result =
            PortraitBlur.apply(appContext, source, segmenter, focusX, focusY, bokehLevel)

        /** Builds compact depth and photographic bokeh guides for the GPU live preview. */
        fun createDepthGuide(source: Bitmap, focusX: Float, focusY: Float): PreviewGuides? {
            return PortraitBlur.createDepthGuide(appContext, source, segmenter, focusX, focusY)
        }

        override fun close() {
            segmenter.close()
        }
    }

    private fun apply(
        context: Context,
        source: Bitmap,
        segmenter: Segmenter,
        focusX: Float,
        focusY: Float,
        bokehLevel: Int
    ): Result {
        val mask = try {
            Tasks.await(segmenter.process(InputImage.fromBitmap(source, 0)), 30, TimeUnit.SECONDS)
        } catch (_: Exception) { null }
        val depth = try { DepthHolder.get(context).estimate(source) } catch (_: Exception) { null }
        return if (depth != null) applyDepth(source, mask, depth, focusX, focusY, bokehLevel)
        else Result(source, false, false)
    }

    private fun createDepthGuide(
        context: Context,
        source: Bitmap,
        segmenter: Segmenter,
        focusX: Float,
        focusY: Float
    ): PreviewGuides? {
        val mask = try {
            Tasks.await(segmenter.process(InputImage.fromBitmap(source, 0)), 10, TimeUnit.SECONDS)
        } catch (_: Exception) { null }
        val depth = try { DepthHolder.get(context).estimate(source) } catch (_: Exception) { null }
        // A person mask alone cannot tell whether the background is actually farther
        // away. Do not invent shallow depth of field when depth inference is unavailable.
        if (depth == null) return null
        val ordered = depth.values.sortedArray()
        val low = ordered[(ordered.size * 0.06f).toInt().coerceIn(0, ordered.lastIndex)]
        val high = ordered[(ordered.size * 0.94f).toInt().coerceIn(0, ordered.lastIndex)]
        val span = (high - low).coerceAtLeast(0.035f)
        val maskWidth = mask?.width ?: 0
        val maskHeight = mask?.height ?: 0
        val confidence = mask?.buffer?.order(ByteOrder.nativeOrder())?.asFloatBuffer()?.apply { position(0) }
        val confidenceRows = Array(3) { FloatArray(maskWidth) }
        val guideScale = 256f / max(source.width, source.height)
        val guideWidth = max(1, (source.width * guideScale).toInt())
        val guideHeight = max(1, (source.height * guideScale).toInt())
        val guide = IntArray(guideWidth * guideHeight)
        for (y in 0 until guideHeight) {
            val depthY = if (depth != null) min(depth.height - 1, y * depth.height / guideHeight) else 0
            val maskY = if (mask != null) min(maskHeight - 1, y * maskHeight / guideHeight) else 0
            if (confidence != null) {
                for (row in -1..1) {
                    val sampleY = (maskY + row).coerceIn(0, maskHeight - 1)
                    val values = confidenceRows[row + 1]
                    confidence.position(sampleY * maskWidth)
                    confidence.get(values, 0, maskWidth)
                }
            }
            for (x in 0 until guideWidth) {
                val person = if (confidence != null && mask != null) {
                    val maskX = min(maskWidth - 1, x * maskWidth / guideWidth)
                    var edgeProtected = 0f
                    // Expand the foreground confidence by one mask pixel so the
                    // low-resolution matte does not leave a blurred fringe at edges.
                    for (row in 0..2) for (column in -1..1) {
                        val sampleX = (maskX + column).coerceIn(0, maskWidth - 1)
                        edgeProtected = max(edgeProtected, confidenceRows[row][sampleX])
                    }
                    edgeProtected.coerceIn(0f, 1f)
                } else 0f
                // With no depth model output, use the person mask as a two-plane depth guide.
                // That keeps the live Close-up effect working for people instead of silently
                // disabling Bokeh for the whole preview.
                val relative = if (depth != null) {
                    val depthX = min(depth.width - 1, x * depth.width / guideWidth)
                    val index = depthY * depth.width + depthX
                    ((depth.values[index] - low) / span).coerceIn(0f, 1f)
                } else person
                val depthByte = (relative * 255f + 0.5f).toInt().coerceIn(0, 255)
                val personByte = (person * 255f + 0.5f).toInt().coerceIn(0, 255)
                guide[y * guideWidth + x] = Color.rgb(depthByte, personByte, 0)
            }
        }
        val depthBitmap = Bitmap.createBitmap(guide, guideWidth, guideHeight, Bitmap.Config.ARGB_8888)
        val focusDepth = focusDepthAt(guide, guideWidth, guideHeight, focusX, focusY)
        return PreviewGuides(depthBitmap, focusDepth)
    }

    private fun focusDepthAt(values: IntArray, width: Int, height: Int, focusX: Float, focusY: Float): Float {
        val centerX = (focusX.coerceIn(0f, 1f) * (width - 1)).toInt()
        val centerY = (focusY.coerceIn(0f, 1f) * (height - 1)).toInt()
        val samples = FloatArray(9)
        var index = 0
        for (dy in -1..1) for (dx in -1..1) {
            val x = (centerX + dx).coerceIn(0, width - 1)
            val y = (centerY + dy).coerceIn(0, height - 1)
            samples[index++] = Color.red(values[y * width + x]) / 255f
        }
        samples.sort()
        return samples[samples.size / 2]
    }

    private fun applyDepth(
        source: Bitmap,
        mask: com.google.mlkit.vision.segmentation.SegmentationMask?,
        depth: RelativeDepthEstimator.DepthMap,
        focusX: Float,
        focusY: Float,
        bokehLevel: Int
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
        val maskWidth = mask?.width ?: 0
        val maskHeight = mask?.height ?: 0
        val confidence = mask?.buffer?.order(ByteOrder.nativeOrder())?.asFloatBuffer()?.apply { position(0) }
        val focused = ((focusDepth - low) / span).coerceIn(0f, 1f)
        val background = renderApertureBlur(source, depth, low, span, focused, confidence, maskWidth, maskHeight, bokehLevel)
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val row = IntArray(source.width)
        val bgRow = IntArray(source.width)
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
                val separation = (focused - relative).coerceAtLeast(0f)
                // A soft focus band avoids making the selected plane look artificially cut out.
                var blurAmount = smoothstep(((separation - 0.12f) / (0.50f - 0.12f)).coerceIn(0f, 1f))
                blurAmount *= blurAmount * 0.88f
                if (confidence != null && mask != null) {
                    val maskX = min(maskWidth - 1, x * maskWidth / source.width)
                    val person = smoothstep(((confidenceRow[maskX] - 0.12f) / (0.48f - 0.12f)).coerceIn(0f, 1f))
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

    /** Circular-aperture gathering on a reduced working image. Highlight RGB values are
     * preserved by the kernel, so lamps and reflections form naturally colored circles. */
    private fun renderApertureBlur(
        source: Bitmap,
        depth: RelativeDepthEstimator.DepthMap,
        low: Float,
        span: Float,
        focus: Float,
        personMask: java.nio.FloatBuffer?,
        maskWidth: Int,
        maskHeight: Int,
        bokehLevel: Int
    ): Bitmap {
        val scale = minOf(1f, 768f / max(source.width, source.height).toFloat())
        val width = max(1, (source.width * scale).toInt())
        val height = max(1, (source.height * scale).toInt())
        val small = Bitmap.createScaledBitmap(source, width, height, true)
        val input = IntArray(width * height)
        small.getPixels(input, 0, width, 0, 0, width, height)
        if (small !== source) small.recycle()
        val output = IntArray(input.size)
        val maxRadius = when (bokehLevel.coerceIn(0, 2)) { 0 -> 5f; 1 -> 10f; else -> 16f }
        val offsets = ArrayList<Pair<Float, Float>>(29)
        for (iy in -3..3) for (ix in -3..3) {
            val ox = ix / 3f
            val oy = iy / 3f
            if (ox * ox + oy * oy <= 1.001f) offsets += ox to oy
        }
        fun depthAt(x: Int, y: Int): Float {
            val sx = min(depth.width - 1, x * source.width / width)
            val sy = min(depth.height - 1, y * source.height / height)
            return ((depth.values[sy * depth.width + sx] - low) / span).coerceIn(0f, 1f)
        }
        fun personAt(x: Int, y: Int): Float {
            if (personMask == null || maskWidth <= 0 || maskHeight <= 0) return 0f
            val sx = min(maskWidth - 1, x * maskWidth / width)
            val sy = min(maskHeight - 1, y * maskHeight / height)
            return personMask.get(sy * maskWidth + sx).coerceIn(0f, 1f)
        }
        for (y in 0 until height) for (x in 0 until width) {
            val center = y * width + x
            val relative = depthAt(x, y)
            val amount = defocusAmount(relative, focus)
            if (amount < 0.04f || personAt(x, y) > 0.18f) {
                output[center] = input[center]
                continue
            }
            val radius = maxRadius * amount
            var red = 0f; var green = 0f; var blue = 0f; var total = 0f
            for ((ox, oy) in offsets) {
                val sx = (x + ox * radius).roundToInt().coerceIn(0, width - 1)
                val sy = (y + oy * radius).roundToInt().coerceIn(0, height - 1)
                if (defocusAmount(depthAt(sx, sy), focus) < 0.06f || personAt(sx, sy) > 0.18f) continue
                val c = input[sy * width + sx]
                red += Color.red(c); green += Color.green(c); blue += Color.blue(c); total += 1f
            }
            output[center] = if (total > 0f) Color.rgb((red / total).toInt(), (green / total).toInt(), (blue / total).toInt()) else input[center]
        }
        val blurred = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        blurred.setPixels(output, 0, width, 0, 0, width, height)
        return if (width == source.width && height == source.height) blurred
            else Bitmap.createScaledBitmap(blurred, source.width, source.height, true).also { blurred.recycle() }
    }

    private fun defocusAmount(relative: Float, focus: Float): Float {
        // Inverse depth is higher for near objects. Close-up mode deliberately
        // defocuses only the scene behind the selected, near focus plane.
        val separation = (focus - relative).coerceAtLeast(0f)
        val ramp = smoothstep(((separation - 0.12f) / (0.50f - 0.12f)).coerceIn(0f, 1f))
        return ramp * ramp * 0.88f
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
