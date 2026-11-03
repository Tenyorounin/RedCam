package ca.pwalker.ultratone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
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
    data class PreviewGuides(
        val depth: Bitmap,
        val bokeh: Bitmap,
        val depthValues: IntArray,
        val width: Int,
        val height: Int
    )

    fun apply(context: Context, source: Bitmap, focusX: Float = 0.5f, focusY: Float = 0.5f, bokehLevel: Int = 0): Result {
        val segmenter = Segmentation.getClient(
            SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build()
        )
        return try { apply(context, source, segmenter, focusX, focusY, bokehLevel) } finally { segmenter.close() }
    }

    /** Runs at a reduced frame rate on live preview frames; depth inference is intentionally best-effort. */
    class StreamProcessor(context: Context) : AutoCloseable {
        private val appContext = context.applicationContext
        private val segmenter = Segmentation.getClient(
            SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.STREAM_MODE).build()
        )
        private var latestDepthValues: IntArray? = null
        private var latestGuideWidth = 0
        private var latestGuideHeight = 0

        fun apply(source: Bitmap, focusX: Float = 0.5f, focusY: Float = 0.5f, bokehLevel: Int = 0): Result =
            PortraitBlur.apply(appContext, source, segmenter, focusX, focusY, bokehLevel)

        /** Builds compact depth and photographic bokeh guides for the GPU live preview. */
        fun createDepthGuide(source: Bitmap, focusX: Float, focusY: Float, bokehLevel: Int): PreviewGuides? {
            val guides = PortraitBlur.createDepthGuide(appContext, source, segmenter, focusX, focusY, bokehLevel)
            if (guides != null) {
                latestDepthValues = guides.depthValues
                latestGuideWidth = guides.width
                latestGuideHeight = guides.height
            }
            return guides
        }

        fun createBokehGuide(source: Bitmap, focusX: Float, focusY: Float, bokehLevel: Int): Bitmap =
            PortraitBlur.createBokehGuide(source, latestDepthValues, latestGuideWidth, latestGuideHeight, focusX, focusY, bokehLevel)

        override fun close() {
            latestDepthValues = null
            latestGuideWidth = 0
            latestGuideHeight = 0
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
        else if (mask != null) applyMaskFallback(source, mask, bokehLevel)
        else Result(source, false, false)
    }

    private fun createDepthGuide(
        context: Context,
        source: Bitmap,
        segmenter: Segmenter,
        focusX: Float,
        focusY: Float,
        bokehLevel: Int
    ): PreviewGuides? {
        val mask = try {
            Tasks.await(segmenter.process(InputImage.fromBitmap(source, 0)), 10, TimeUnit.SECONDS)
        } catch (_: Exception) { null }
        val depth = try { DepthHolder.get(context).estimate(source) } catch (_: Exception) { null }
        if (depth == null && mask == null) return null
        val ordered = depth?.values?.sortedArray()?.takeIf { it.isNotEmpty() }
        val low = if (ordered != null) ordered[(ordered.size * 0.06f).toInt().coerceIn(0, ordered.lastIndex)] else 0f
        val high = if (ordered != null) ordered[(ordered.size * 0.94f).toInt().coerceIn(0, ordered.lastIndex)] else 1f
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
            val depthY = if (depth != null) min(depth.height - 1, y * depth.height / guideHeight) else 0
            val maskY = if (mask != null) min(maskHeight - 1, y * maskHeight / guideHeight) else 0
            if (confidence != null) {
                confidence.position(maskY * maskWidth)
                confidence.get(confidenceRow, 0, maskWidth)
            }
            for (x in 0 until guideWidth) {
                val person = if (confidence != null && mask != null) {
                    val maskX = min(maskWidth - 1, x * maskWidth / guideWidth)
                    confidenceRow[maskX].coerceIn(0f, 1f)
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
        val bokeh = createBokehGuide(source, guide, guideWidth, guideHeight, focusX, focusY, bokehLevel)
        return PreviewGuides(depthBitmap, bokeh, guide, guideWidth, guideHeight)
    }

    private fun createBokehGuide(
        source: Bitmap,
        depthValues: IntArray?,
        guideWidth: Int,
        guideHeight: Int,
        focusX: Float,
        focusY: Float,
        bokehLevel: Int
    ): Bitmap {
        val scale = 256f / max(source.width, source.height)
        val width = max(1, (source.width * scale).toInt())
        val height = max(1, (source.height * scale).toInt())
        val small = Bitmap.createScaledBitmap(source, width, height, true)
        if (depthValues == null || guideWidth <= 0 || guideHeight <= 0) {
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                it.eraseColor(Color.TRANSPARENT)
                if (small !== source) small.recycle()
            }
        }
        val focusXpx = (focusX.coerceIn(0f, 1f) * (guideWidth - 1)).toInt()
        val focusYpx = (focusY.coerceIn(0f, 1f) * (guideHeight - 1)).toInt()
        val focusDepth = Color.red(depthValues[focusYpx * guideWidth + focusXpx]) / 255f
        val bokeh = createBokehCircles(small, bokehLevel) { x, y ->
            val gx = min(guideWidth - 1, x * guideWidth / small.width)
            val gy = min(guideHeight - 1, y * guideHeight / small.height)
            liveGuideBlurAmount(depthValues[gy * guideWidth + gx], focusDepth)
        }
        if (small !== source) small.recycle()
        return bokeh
    }

    private fun liveGuideBlurAmount(sample: Int, focusDepth: Float): Float {
        val separation = abs(Color.red(sample) / 255f - focusDepth)
        var blur = smoothstep(((separation - 0.055f) / (0.42f - 0.055f)).coerceIn(0f, 1f))
        blur = blur * blur * 0.88f
        return blur * (1f - smoothstep(((Color.green(sample) / 255f - 0.30f) / (0.78f - 0.30f)).coerceIn(0f, 1f)))
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
        val background = blurredBackground(source, bokehLevel)
        val bokeh = createBokehCircles(source, bokehLevel) { x, y ->
            val mx = min(depth.width - 1, x * depth.width / source.width)
            val my = min(depth.height - 1, y * depth.height / source.height)
            val relative = ((depth.values[my * depth.width + mx] - low) / span).coerceIn(0f, 1f)
            val focused = ((focusDepth - low) / span).coerceIn(0f, 1f)
            val separation = abs(relative - focused)
            var blur = smoothstep(((separation - 0.055f) / (0.42f - 0.055f)).coerceIn(0f, 1f))
            blur = blur * blur * 0.88f
            if (confidence != null && mask != null) {
                val maskX = min(maskWidth - 1, x * maskWidth / source.width)
                val maskY = min(maskHeight - 1, y * maskHeight / source.height)
                val person = confidence.get(maskY * maskWidth + maskX).coerceIn(0f, 1f)
                blur *= 1f - smoothstep(((person - 0.30f) / 0.50f).coerceIn(0f, 1f))
            }
            blur
        }
        compositeBokeh(background, bokeh)
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
        mask: com.google.mlkit.vision.segmentation.SegmentationMask,
        bokehLevel: Int
    ): Result {
        val maskWidth = mask.width
        val maskHeight = mask.height
        val values = mask.buffer.order(ByteOrder.nativeOrder()).asFloatBuffer().apply { position(0) }
        val background = blurredBackground(source, bokehLevel)
        val bokeh = createBokehCircles(source, bokehLevel) { x, y ->
            val mx = min(maskWidth - 1, x * maskWidth / source.width)
            val my = min(maskHeight - 1, y * maskHeight / source.height)
            val person = values.get(my * maskWidth + mx).coerceIn(0f, 1f)
            1f - smoothstep(((person - 0.22f) / (0.58f - 0.22f)).coerceIn(0f, 1f))
        }
        compositeBokeh(background, bokeh)
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

    private fun blurredBackground(source: Bitmap, bokehLevel: Int): Bitmap {
        val scale = 8
        val blurRadius = when (bokehLevel.coerceIn(0, 2)) { 0 -> 3; 1 -> 7; else -> 12 }
        val width = max(1, source.width / scale)
        val height = max(1, source.height / scale)
        val small = Bitmap.createScaledBitmap(source, width, height, true)
        var pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        small.recycle()
        repeat(3) { pixels = boxBlur(pixels, width, height, blurRadius) }
        val blurredSmall = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        blurredSmall.setPixels(pixels, 0, width, 0, 0, width, height)
        return Bitmap.createScaledBitmap(blurredSmall, source.width, source.height, true).also { blurredSmall.recycle() }
    }

    /** Adds colored, soft-edged discs from bright local highlights inside out-of-focus areas. */
    private fun createBokehCircles(
        source: Bitmap,
        bokehLevel: Int,
        blurAt: (Int, Int) -> Float
    ): Bitmap {
        val workingScale = minOf(1f, 512f / max(source.width, source.height).toFloat())
        val width = max(1, (source.width * workingScale).toInt())
        val height = max(1, (source.height * workingScale).toInt())
        val workingSource = if (width == source.width && height == source.height) source
            else Bitmap.createScaledBitmap(source, width, height, true)
        val pixels = IntArray(width * height)
        workingSource.getPixels(pixels, 0, width, 0, 0, width, height)
        if (workingSource !== source) workingSource.recycle()
        val luminance = FloatArray(pixels.size) { index ->
            val color = pixels[index]
            (0.2126f * Color.red(color) + 0.7152f * Color.green(color) + 0.0722f * Color.blue(color)) / 255f
        }
        val points = ArrayList<BokehPoint>()
        for (y in 2 until height - 2 step 2) {
            for (x in 2 until width - 2 step 2) {
                val index = y * width + x
                val light = luminance[index]
                if (light < 0.72f) continue
                var localMaximum = true
                loop@ for (dy in -2..2) for (dx in -2..2) {
                    if (dx == 0 && dy == 0) continue
                    val neighbor = (y + dy) * width + x + dx
                    if (luminance[neighbor] > light || (luminance[neighbor] == light && neighbor < index)) {
                        localMaximum = false
                        break@loop
                    }
                }
                if (!localMaximum) continue
                val sourceX = min(source.width - 1, x * source.width / width)
                val sourceY = min(source.height - 1, y * source.height / height)
                val blur = blurAt(sourceX, sourceY).coerceIn(0f, 1f)
                if (blur < 0.20f) continue
                points += BokehPoint(x.toFloat(), y.toFloat(), pixels[index], blur, light * blur)
            }
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (points.isEmpty()) return bitmap
        points.sortByDescending { it.score }
        val baseRadius = when (bokehLevel.coerceIn(0, 2)) { 0 -> 2.5f; 1 -> 4.0f; else -> 5.8f }
        val accepted = ArrayList<BokehPoint>(min(points.size, 500))
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (point in points) {
            if (accepted.size >= 500) break
            val radius = baseRadius * (0.55f + point.blur * 0.8f)
            val separation = max(3f, radius * 0.85f)
            if (accepted.any { dxdyWithin(it, point, separation) }) continue
            accepted += point
            val alpha = (255f * (0.18f + (point.score - 0.72f * point.blur).coerceAtLeast(0f) * 1.3f)
                .coerceIn(0.20f, 0.68f)).toInt()
            val color = Color.argb(alpha, Color.red(point.color), Color.green(point.color), Color.blue(point.color))
            paint.shader = RadialGradient(
                point.x, point.y, radius,
                intArrayOf(color, color, Color.argb(alpha * 3 / 5, Color.red(point.color), Color.green(point.color), Color.blue(point.color)), Color.TRANSPARENT),
                floatArrayOf(0f, 0.58f, 0.82f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawCircle(point.x, point.y, radius, paint)
        }
        paint.shader = null
        return bitmap
    }

    private data class BokehPoint(val x: Float, val y: Float, val color: Int, val blur: Float, val score: Float)

    private fun dxdyWithin(a: BokehPoint, b: BokehPoint, distance: Float): Boolean {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy < distance * distance
    }

    private fun compositeBokeh(background: Bitmap, bokeh: Bitmap) {
        val canvas = Canvas(background)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        }
        canvas.drawBitmap(bokeh, null, Rect(0, 0, background.width, background.height), paint)
        paint.xfermode = null
        bokeh.recycle()
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
