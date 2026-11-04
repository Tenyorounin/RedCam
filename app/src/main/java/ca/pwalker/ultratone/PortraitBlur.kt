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
import kotlin.math.pow
import kotlin.math.roundToInt

/** Relative-depth background blur, with person segmentation to help preserve subject edges. */
object PortraitBlur {
    private data class Highlight(val x: Int, val y: Int, val color: Int, val strength: Float, val blur: Float)
    private val linearChannel = FloatArray(256) { value ->
        val encoded = value / 255f
        if (encoded <= 0.04045f) encoded / 12.92f
        else ((encoded + 0.055f) / 1.055f).pow(2.4f)
    }

    data class Result(val bitmap: Bitmap, val subjectDetected: Boolean, val usedDepth: Boolean)
    data class PreviewGuides(
        val depth: Bitmap,
        val circles: Bitmap,
        val focusDepth: Float,
        val focusOnPerson: Boolean
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

        /** Builds compact depth and foreground guides for the GPU live preview. */
        fun createDepthGuide(source: Bitmap, focusX: Float, focusY: Float, level: Int): PreviewGuides? {
            return PortraitBlur.createDepthGuide(appContext, source, segmenter, focusX, focusY, level)
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

    private fun depthRange(values: FloatArray): Pair<Float, Float> {
        val bins = IntArray(256)
        for (value in values) bins[(value * 255f).roundToInt()]++
        fun percentile(fraction: Float): Float {
            val index = (values.size * fraction).toInt().coerceAtMost(values.lastIndex)
            var count = 0
            for (level in bins.indices) {
                count += bins[level]
                if (count > index) return level / 255f
            }
            return 1f
        }
        return percentile(0.06f) to percentile(0.94f)
    }

    private fun createDepthGuide(
        context: Context,
        source: Bitmap,
        segmenter: Segmenter,
        focusX: Float,
        focusY: Float,
        level: Int
    ): PreviewGuides? {
        val maskTask = try { segmenter.process(InputImage.fromBitmap(source, 0)) } catch (_: Exception) { null }
        val depth = try { DepthHolder.get(context).estimate(source) } catch (_: Exception) { null }
        val mask = try { maskTask?.let { Tasks.await(it, 10, TimeUnit.SECONDS) } } catch (_: Exception) { null }
        // A person mask alone cannot tell whether the background is actually farther
        // away. Do not invent shallow depth of field when depth inference is unavailable.
        if (depth == null) return null
        val (low, high) = depthRange(depth.values)
        val span = (high - low).coerceAtLeast(0.035f)
        val maskWidth = mask?.width ?: 0
        val maskHeight = mask?.height ?: 0
        val confidence = mask?.buffer?.order(ByteOrder.nativeOrder())?.asFloatBuffer()?.apply { position(0) }
        val focusOnPerson = focusIsOnPerson(confidence, maskWidth, maskHeight, focusX, focusY)
        val confidenceRows = Array(3) { FloatArray(maskWidth) }
        val guideScale = 256f / max(source.width, source.height)
        val guideWidth = max(1, (source.width * guideScale).toInt())
        val guideHeight = max(1, (source.height * guideScale).toInt())
        val guide = IntArray(guideWidth * guideHeight)
        for (y in 0 until guideHeight) {
            val depthY = min(depth.height - 1, y * depth.height / guideHeight)
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
                val depthX = min(depth.width - 1, x * depth.width / guideWidth)
                val relative = ((depth.values[depthY * depth.width + depthX] - low) / span).coerceIn(0f, 1f)
                val depthByte = (relative * 255f + 0.5f).toInt().coerceIn(0, 255)
                val personByte = (person * 255f + 0.5f).toInt().coerceIn(0, 255)
                guide[y * guideWidth + x] = Color.rgb(depthByte, personByte, 0)
            }
        }
        val depthBitmap = Bitmap.createBitmap(guide, guideWidth, guideHeight, Bitmap.Config.ARGB_8888)
        val focusDepth = focusDepthAt(guide, guideWidth, guideHeight, focusX, focusY)
        val small = Bitmap.createScaledBitmap(source, guideWidth, guideHeight, true)
        val pixels = IntArray(guide.size)
        small.getPixels(pixels, 0, guideWidth, 0, 0, guideWidth, guideHeight)
        if (small !== source) small.recycle()
        val amounts = FloatArray(guide.size) { index ->
            val point = guide[index]
            val blur = if (focusOnPerson) 0.88f else defocusAmount(Color.red(point) / 255f, focusDepth)
            blur * (1f - smoothstep(((Color.green(point) / 255f - 0.12f) / 0.36f).coerceIn(0f, 1f)))
        }
        val eligible = BooleanArray(guide.size) { amounts[it] >= 0.16f }
        val circles = Bitmap.createBitmap(guideWidth, guideHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(circles)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        for (light in findBackgroundHighlights(pixels, guideWidth, guideHeight, amounts, eligible)) {
            paint.color = light.color
            paint.alpha = (255f * discOpacity(level) * light.strength).roundToInt()
            canvas.drawCircle(light.x + 0.5f, light.y + 0.5f,
                max(1f, blurRadius(level) * guideWidth.coerceAtLeast(guideHeight) / 768f * light.blur * 0.82f), paint)
        }
        return PreviewGuides(depthBitmap, circles, focusDepth, focusOnPerson)
    }

    private fun focusIsOnPerson(
        confidence: java.nio.FloatBuffer?,
        maskWidth: Int,
        maskHeight: Int,
        focusX: Float,
        focusY: Float
    ): Boolean {
        if (confidence == null || maskWidth <= 0 || maskHeight <= 0) return false
        val centerX = (focusX.coerceIn(0f, 1f) * (maskWidth - 1)).toInt()
        val centerY = (focusY.coerceIn(0f, 1f) * (maskHeight - 1)).toInt()
        var localConfidence = 0f
        for (dy in -4..4) for (dx in -4..4) {
            val x = (centerX + dx).coerceIn(0, maskWidth - 1)
            val y = (centerY + dy).coerceIn(0, maskHeight - 1)
            localConfidence = max(localConfidence, confidence.get(y * maskWidth + x))
        }
        return localConfidence >= 0.28f
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
        val (low, high) = depthRange(depth.values)
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
        val focusOnPerson = focusIsOnPerson(confidence, maskWidth, maskHeight, focusX, focusY)
        val focused = ((focusDepth - low) / span).coerceIn(0f, 1f)
        val background = renderApertureBlur(
            source, depth, low, span, focused, confidence, maskWidth, maskHeight, bokehLevel, focusOnPerson
        )
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
                val relative = ((sampleDepth(
                    depth,
                    x.toFloat() / max(1, source.width - 1),
                    y.toFloat() / max(1, source.height - 1)
                ) - low) / span).coerceIn(0f, 1f)
                val focused = ((focusDepth - low) / span).coerceIn(0f, 1f)
                val separation = (focused - relative).coerceAtLeast(0f)
                // When focus lands on a person, their matte gives a cleaner foreground
                // boundary than monocular depth alone. Treat the non-person scene as
                // background; otherwise use depth to preserve other near objects.
                var blurAmount = if (focusOnPerson) 0.88f else {
                    smoothstep(((separation - 0.12f) / (0.50f - 0.12f)).coerceIn(0f, 1f)).let { it * it * 0.88f }
                }
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

    /** Layered, occlusion-aware circular-aperture renderer on a reduced working image.
     * The sharp layer is excluded from the blur input. Its color is extended under the
     * foreground boundary before the background layer is blurred, then the sharp source
     * is composited back according to the depth and person mattes. */
    private fun renderApertureBlur(
        source: Bitmap,
        depth: RelativeDepthEstimator.DepthMap,
        low: Float,
        span: Float,
        focus: Float,
        personMask: java.nio.FloatBuffer?,
        maskWidth: Int,
        maskHeight: Int,
        bokehLevel: Int,
        focusOnPerson: Boolean
    ): Bitmap {
        val scale = minOf(1f, 768f / max(source.width, source.height).toFloat())
        val width = max(1, (source.width * scale).toInt())
        val height = max(1, (source.height * scale).toInt())
        val small = Bitmap.createScaledBitmap(source, width, height, true)
        val input = IntArray(width * height)
        small.getPixels(input, 0, width, 0, 0, width, height)
        if (small !== source) small.recycle()
        val maxRadius = blurRadius(bokehLevel)
        val blurAmounts = FloatArray(input.size)
        val backgroundEligible = BooleanArray(input.size)
        val offsets = ArrayList<Pair<Float, Float>>(49)
        for (iy in -4..4) for (ix in -4..4) {
            val ox = ix / 4f
            val oy = iy / 4f
            if (ox * ox + oy * oy <= 1.001f) offsets += ox to oy
        }
        fun depthAt(x: Int, y: Int): Float {
            val nx = x.toFloat() / max(1, width - 1)
            val ny = y.toFloat() / max(1, height - 1)
            return ((sampleDepth(depth, nx, ny) - low) / span).coerceIn(0f, 1f)
        }
        fun personWeightAt(x: Int, y: Int): Float {
            if (personMask == null || maskWidth <= 0 || maskHeight <= 0) return 0f
            val sx = min(maskWidth - 1, x * maskWidth / width)
            val sy = min(maskHeight - 1, y * maskHeight / height)
            val confidence = personMask.get(sy * maskWidth + sx).coerceIn(0f, 1f)
            return smoothstep(((confidence - 0.12f) / (0.48f - 0.12f)).coerceIn(0f, 1f))
        }
        for (y in 0 until height) for (x in 0 until width) {
            val center = y * width + x
            val person = personWeightAt(x, y)
            val amount = (if (focusOnPerson) 0.88f else defocusAmount(depthAt(x, y), focus)) * (1f - person)
            blurAmounts[center] = amount
            backgroundEligible[center] = amount > 0.04f && person < 0.18f
        }
        val highlights = findBackgroundHighlights(input, width, height, blurAmounts, backgroundEligible)

        // Dr.Bokeh's layered representation needs RGB behind foreground edges. Fill
        // those pixels from the exact nearest farther-background pixel, bounded by the
        // largest circle of confusion, before blurring the background layer.
        val nearestBackground = propagateNearestBackground(backgroundEligible, width, height, ceilRadius(maxRadius))
        val backgroundLayer = IntArray(input.size)
        for (i in input.indices) {
            val nearest = nearestBackground[i]
            backgroundLayer[i] = if (nearest >= 0) input[nearest] else input[i]
        }

        val output = IntArray(input.size)
        for (y in 0 until height) for (x in 0 until width) {
            val center = y * width + x
            val amount = blurAmounts[center]
            if (amount < 0.04f) {
                output[center] = backgroundLayer[center]
                continue
            }
            val radius = maxRadius * amount
            var red = 0f; var green = 0f; var blue = 0f; var total = 0f
            for ((ox, oy) in offsets) {
                val sx = (x + ox * radius).roundToInt().coerceIn(0, width - 1)
                val sy = (y + oy * radius).roundToInt().coerceIn(0, height - 1)
                val c = backgroundLayer[sy * width + sx]
                val distance = kotlin.math.sqrt(ox * ox + oy * oy)
                val weight = 1f - smoothstep(((distance - 0.78f) / (1f - 0.78f)).coerceIn(0f, 1f))
                red += Color.red(c) * weight; green += Color.green(c) * weight; blue += Color.blue(c) * weight; total += weight
            }
            val blurred = if (total > 0f) Color.rgb((red / total).toInt(), (green / total).toInt(), (blue / total).toInt()) else backgroundLayer[center]
            output[center] = blurred
        }
        renderHighlightDiscs(output, width, height, blurAmounts, backgroundEligible, highlights, maxRadius, bokehLevel)
        val blurred = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        blurred.setPixels(output, 0, width, 0, 0, width, height)
        return if (width == source.width && height == source.height) blurred
            else Bitmap.createScaledBitmap(blurred, source.width, source.height, true).also { blurred.recycle() }
    }

    private fun findBackgroundHighlights(
        pixels: IntArray,
        width: Int,
        height: Int,
        blurAmounts: FloatArray,
        backgroundEligible: BooleanArray
    ): List<Highlight> {
        if (width < 9 || height < 9) return emptyList()
        fun luma(color: Int) =
            0.2126f * linearChannel[Color.red(color)] +
                0.7152f * linearChannel[Color.green(color)] +
                0.0722f * linearChannel[Color.blue(color)]

        val luminance = FloatArray(pixels.size) { luma(pixels[it]) }
        val suppressed = BooleanArray(pixels.size)
        val highlights = ArrayList<Highlight>(32)
        val ring = intArrayOf(-4, -3, 3, 4)
        for (y in 4 until height - 4) {
            for (x in 4 until width - 4) {
                val index = y * width + x
                val peak = luminance[index]
                if (suppressed[index] || !backgroundEligible[index] || blurAmounts[index] < 0.22f || peak < 0.62f) continue

                var ringLuma = 0f
                var ringSamples = 0
                for (dy in ring) for (dx in ring) {
                    ringLuma += luminance[(y + dy) * width + x + dx]
                    ringSamples++
                }
                val contrast = peak - ringLuma / ringSamples
                if (contrast < 0.14f) continue

                var localPeak = true
                for (dy in -2..2) for (dx in -2..2) {
                    if (!localPeak || (dx == 0 && dy == 0)) continue
                    val neighborIndex = (y + dy) * width + x + dx
                    val neighbor = luminance[neighborIndex]
                    if (neighbor > peak + 0.003f ||
                        (kotlin.math.abs(neighbor - peak) <= 0.003f && neighborIndex < index)
                    ) localPeak = false
                }
                if (!localPeak) continue

                var red = 0f; var green = 0f; var blue = 0f; var colorWeight = 0f
                for (dy in -2..2) for (dx in -2..2) {
                    val colorIndex = (y + dy) * width + x + dx
                    val weight = (luminance[colorIndex] - 0.42f).coerceAtLeast(0f)
                    if (weight <= 0f) continue
                    val color = pixels[colorIndex]
                    red += Color.red(color) * weight
                    green += Color.green(color) * weight
                    blue += Color.blue(color) * weight
                    colorWeight += weight
                }
                if (colorWeight <= 0f) continue
                val sourceColor = Color.rgb(
                    (red / colorWeight).roundToInt().coerceIn(0, 255),
                    (green / colorWeight).roundToInt().coerceIn(0, 255),
                    (blue / colorWeight).roundToInt().coerceIn(0, 255)
                )
                val peakStrength = ((peak - 0.56f) / 0.44f).coerceIn(0f, 1f)
                val contrastStrength = ((contrast - 0.08f) / 0.42f).coerceIn(0f, 1f)
                val strength = (0.25f + 0.75f * peakStrength) * (0.35f + 0.65f * contrastStrength)
                highlights += Highlight(x, y, sourceColor, strength.coerceIn(0.18f, 1f), blurAmounts[index])
                for (dy in -5..5) for (dx in -5..5) {
                    if (dx * dx + dy * dy <= 25 && x + dx in 0 until width && y + dy in 0 until height) suppressed[(y + dy) * width + x + dx] = true
                }
                if (highlights.size >= 96) return highlights
            }
        }
        return highlights
    }

    private fun blurRadius(level: Int) = when (level) { 0 -> 5f; 1 -> 16f; else -> 24f }
    private fun discOpacity(level: Int) = when (level) { 0 -> 0.34f; 1 -> 0.58f; else -> 0.68f }

    private fun renderHighlightDiscs(
        pixels: IntArray,
        width: Int,
        height: Int,
        blurAmounts: FloatArray,
        backgroundEligible: BooleanArray,
        highlights: List<Highlight>,
        maxRadius: Float,
        bokehLevel: Int
    ) {
        val opacity = discOpacity(bokehLevel)
        for (highlight in highlights) {
            val radius = max(2f, maxRadius * highlight.blur * 0.82f)
            val reach = kotlin.math.ceil(radius.toDouble()).toInt()
            for (dy in -reach..reach) for (dx in -reach..reach) {
                val normalized = kotlin.math.sqrt((dx * dx + dy * dy).toFloat()) / radius
                if (normalized >= 1f) continue
                val x = highlight.x + dx
                val y = highlight.y + dy
                if (x !in 0 until width || y !in 0 until height) continue
                val index = y * width + x
                if (!backgroundEligible[index] || blurAmounts[index] < 0.16f) continue
                val edge = 1f - smoothstep(((normalized - 0.70f) / 0.30f).coerceIn(0f, 1f))
                val amountScale = (blurAmounts[index] / highlight.blur).coerceIn(0f, 1f)
                val alpha = (highlight.strength * opacity * edge * amountScale).coerceIn(0f, 0.62f)
                if (alpha > 0.002f) pixels[index] = mix(pixels[index], highlight.color, alpha)
            }
        }
    }

    private fun ceilRadius(radius: Float): Int = kotlin.math.ceil(radius.toDouble()).toInt() + 1

    /** Exact Euclidean nearest-background fill, limited to the largest CoC radius. */
    private fun propagateNearestBackground(eligible: BooleanArray, width: Int, height: Int, maxRadius: Int): IntArray {
        val infinity = Int.MAX_VALUE / 4
        val horizontalDistance = IntArray(eligible.size) { infinity }
        val horizontalSeedX = IntArray(eligible.size) { -1 }
        val input = IntArray(max(width, height))
        val distances = IntArray(max(width, height))
        val argmin = IntArray(max(width, height))
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) input[x] = if (eligible[row + x]) 0 else infinity
            squaredDistanceTransform(input, width, distances, argmin, infinity)
            for (x in 0 until width) {
                horizontalDistance[row + x] = distances[x]
                horizontalSeedX[row + x] = argmin[x]
            }
        }

        val nearest = IntArray(eligible.size) { -1 }
        val columnInput = IntArray(height)
        val columnDistances = IntArray(height)
        val columnArgmin = IntArray(height)
        val maxDistanceSquared = maxRadius * maxRadius
        for (x in 0 until width) {
            for (y in 0 until height) columnInput[y] = horizontalDistance[y * width + x]
            squaredDistanceTransform(columnInput, height, columnDistances, columnArgmin, infinity)
            for (y in 0 until height) {
                val index = y * width + x
                if (columnDistances[y] > maxDistanceSquared) continue
                val seedY = columnArgmin[y]
                if (seedY < 0) continue
                val seedX = horizontalSeedX[seedY * width + x]
                if (seedX >= 0) nearest[index] = seedY * width + seedX
            }
        }
        return nearest
    }

    /** 1D squared Euclidean distance transform, retaining the source index. */
    private fun squaredDistanceTransform(input: IntArray, length: Int, distances: IntArray, argmin: IntArray, infinity: Int) {
        val vertices = IntArray(length)
        val boundaries = DoubleArray(length + 1)
        var first = -1
        for (i in 0 until length) if (input[i] < infinity) { first = i; break }
        if (first < 0) {
            for (i in 0 until length) { distances[i] = infinity; argmin[i] = -1 }
            return
        }
        var k = 0
        vertices[0] = first
        boundaries[0] = Double.NEGATIVE_INFINITY
        boundaries[1] = Double.POSITIVE_INFINITY
        for (q in first + 1 until length) {
            if (input[q] >= infinity) continue
            var intersection = ((input[q].toDouble() + q.toDouble() * q - input[vertices[k]].toDouble() - vertices[k].toDouble() * vertices[k]) /
                (2.0 * (q - vertices[k])))
            while (intersection <= boundaries[k]) {
                k--
                intersection = ((input[q].toDouble() + q.toDouble() * q - input[vertices[k]].toDouble() - vertices[k].toDouble() * vertices[k]) /
                    (2.0 * (q - vertices[k])))
            }
            k++
            vertices[k] = q
            boundaries[k] = intersection
            boundaries[k + 1] = Double.POSITIVE_INFINITY
        }
        k = 0
        for (q in 0 until length) {
            while (boundaries[k + 1] < q) k++
            val source = vertices[k]
            val delta = q - source
            distances[q] = input[source] + delta * delta
            argmin[q] = source
        }
    }

    private fun sampleDepth(depth: RelativeDepthEstimator.DepthMap, x: Float, y: Float): Float {
        val fx = x.coerceIn(0f, 1f) * (depth.width - 1)
        val fy = y.coerceIn(0f, 1f) * (depth.height - 1)
        val x0 = fx.toInt(); val y0 = fy.toInt()
        val x1 = min(depth.width - 1, x0 + 1); val y1 = min(depth.height - 1, y0 + 1)
        val tx = fx - x0; val ty = fy - y0
        val top = depth.values[y0 * depth.width + x0] * (1f - tx) + depth.values[y0 * depth.width + x1] * tx
        val bottom = depth.values[y1 * depth.width + x0] * (1f - tx) + depth.values[y1 * depth.width + x1] * tx
        return top * (1f - ty) + bottom * ty
    }

    private fun defocusAmount(relative: Float, focus: Float): Float {
        // Inverse depth is higher for near objects. Bokeh mode deliberately
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
