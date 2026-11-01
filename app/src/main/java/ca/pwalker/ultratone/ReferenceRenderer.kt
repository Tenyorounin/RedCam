package ca.pwalker.ultratone

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Device-independent Leica M9-inspired rendering applied to the camera JPEG.
 * This is an original adaptive grade; it does not reproduce the M9 sensor, lens,
 * demosaicing, or Leica's proprietary JPEG pipeline.
 */
object ReferenceRenderer {
    enum class Mode { DAY, NIGHT, PORTRAIT }
    enum class Finish { COLOUR, BLACK_AND_WHITE }

    fun render(
        source: Bitmap,
        strength: Float,
        mode: Mode = Mode.DAY,
        zoomRatio: Float = 1f,
        finish: Finish = Finish.COLOUR
    ): Bitmap {
        val amount = strength.coerceIn(0f, 1f)
        val width = source.width
        val height = source.height
        val row = IntArray(width)
        val outputRow = IntArray(width)
        val histogram = IntArray(256)
        var saturationTotal = 0.0
        var neutralRedGreen = 0.0
        var neutralBlueGreen = 0.0
        var neutralSamples = 0L
        var samples = 0L
        val sampleStep = max(1, max(width, height) / 512)
        val sampleRow = IntArray(width)

        // Measure the actual scene before choosing curve strength. This keeps a
        // low-contrast scene from looking flat while avoiding extra punch in a
        // high-contrast night or backlit frame.
        for (y in 0 until height step sampleStep) {
            source.getPixels(sampleRow, 0, width, 0, y, width, 1)
            for (x in 0 until width step sampleStep) {
                val c = sampleRow[x]
                val r = Color.red(c) / 255f
                val g = Color.green(c) / 255f
                val b = Color.blue(c) / 255f
                val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                histogram[(luma * 255f).toInt().coerceIn(0, 255)]++
                val maximum = max(r, max(g, b))
                val minimum = min(r, min(g, b))
                val saturation = if (maximum <= 0.001f) 0f else (maximum - minimum) / maximum
                saturationTotal += saturation.toDouble()
                // Estimate only a restrained illuminant correction from neutral,
                // midtone pixels. Avoid letting saturated lamps dictate AWB.
                if (mode == Mode.NIGHT && saturation < 0.18f && luma in 0.16f..0.78f && g > 0.025f) {
                    neutralRedGreen += kotlin.math.ln((r / g).coerceIn(0.4f, 2.5f).toDouble())
                    neutralBlueGreen += kotlin.math.ln((b / g).coerceIn(0.4f, 2.5f).toDouble())
                    neutralSamples++
                }
                samples++
            }
        }
        val p10 = percentile(histogram, samples, 0.10f) / 255f
        val median = percentile(histogram, samples, 0.50f) / 255f
        val p90 = percentile(histogram, samples, 0.90f) / 255f
        val range = (p90 - p10).coerceIn(0.15f, 0.95f)
        val sceneContrast = (1.10f + (0.58f - range) * 0.10f).coerceIn(1.04f, 1.17f)
        val keyBias = ((0.46f - median) * 0.08f).coerceIn(-0.025f, 0.025f)
        val meanSaturation = if (samples == 0L) 0f else (saturationTotal / samples).toFloat()
        val sceneSaturation = (1.12f - max(0f, meanSaturation - 0.35f) * 0.30f).coerceIn(1.00f, 1.12f)
        val nightRedGain: Float
        val nightBlueGain: Float
        if (mode == Mode.NIGHT && neutralSamples >= 32) {
            // Damped and capped: retain warm/cool night ambience instead of
            // forcing every scene toward neutral white.
            nightRedGain = kotlin.math.exp((-neutralRedGreen / neutralSamples * 0.30).coerceIn(-0.06, 0.06)).toFloat()
            nightBlueGain = kotlin.math.exp((-neutralBlueGreen / neutralSamples * 0.30).coerceIn(-0.06, 0.06)).toFloat()
        } else {
            nightRedGain = 1f
            nightBlueGain = 1f
        }
        val zoomDetail = ((zoomRatio - 1f) / 4f).coerceIn(0f, 1f)
        val detailAmount = when (mode) {
            Mode.DAY -> 0.035f + zoomDetail * 0.035f
            Mode.NIGHT -> 0.018f + zoomDetail * 0.02f
            Mode.PORTRAIT -> 0.022f + zoomDetail * 0.025f
        } * amount

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val above = IntArray(width)
        val below = IntArray(width)
        val hsv = FloatArray(3)
        val whiteBalanceScale = when (mode) {
            Mode.NIGHT -> 0.8f
            else -> 1f
        }

        for (y in 0 until height) {
            source.getPixels(row, 0, width, 0, y, width, 1)
            source.getPixels(above, 0, width, 0, max(0, y - 1), width, 1)
            source.getPixels(below, 0, width, 0, min(height - 1, y + 1), width, 1)
            for (x in 0 until width) {
                val c = row[x]
                val alpha = Color.alpha(c)
                var r = Color.red(c) / 255f
                val g = Color.green(c) / 255f
                var b = Color.blue(c) / 255f
                if (mode == Mode.NIGHT) {
                    r = (r * nightRedGain).coerceIn(0f, 1f)
                    b = (b * nightBlueGain).coerceIn(0f, 1f)
                }
                val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val left = lumaOf(row[max(0, x - 1)])
                val right = lumaOf(row[min(width - 1, x + 1)])
                val top = lumaOf(above[x])
                val bottom = lumaOf(below[x])
                val localDetail = luma - (left + right + top + bottom) * 0.25f

                val bw = finish == Finish.BLACK_AND_WHITE
                // M9-like contrast with a soft toe: preserve form in dark tones,
                // while compressing only the upper end of the mobile JPEG range.
                val contrast = sceneContrast + if (bw) 0.09f else 0f
                var target = (luma - 0.46f) * contrast + 0.46f + keyBias
                val toe = smoothstep(((0.30f - target) / 0.30f).coerceIn(0f, 1f))
                target += 0.014f * toe
                val shoulder = smoothstep(((target - 0.72f) / 0.28f).coerceIn(0f, 1f))
                target -= (if (bw) 0.12f else 0.16f) * shoulder * (target - 0.72f).coerceAtLeast(0f)
                target += localDetail * if (abs(localDetail) < 0.025f) detailAmount * 0.30f else detailAmount
                target = (luma + (target - luma) * amount).coerceIn(0f, 1f)

                if (bw) {
                    // A green-weighted monochrome mix retains leaf and skin
                    // texture; the separate contrast curve gives a print-like
                    // tonal rendering instead of simple desaturation.
                    val mono = ((0.28f * r + 0.62f * g + 0.10f * b - 0.46f) * (contrast + 0.05f) + 0.46f + keyBias)
                        .coerceIn(0f, 1f)
                    val value = luma + (mono - luma) * amount
                    val grey = toByte(value)
                    outputRow[x] = Color.argb(alpha, grey, grey, grey)
                    continue
                }

                val adjustedInput = if (mode == Mode.NIGHT) Color.rgb(toByte(r), toByte(g), toByte(b)) else c
                Color.RGBToHSV(Color.red(adjustedInput), Color.green(adjustedInput), Color.blue(adjustedInput), hsv)
                val hue = hsv[0]
                val saturation = hsv[1]
                val value = hsv[2]
                val hueShift = m9HueShift(hue)
                val greenWeight = gaussianHue(hue, 118f, 38f)
                val saturationBoost = (sceneSaturation - 1f) * smoothstep(((saturation - 0.16f) / 0.62f).coerceIn(0f, 1f))
                val shadowChromaRetention = if (mode == Mode.NIGHT) 0.70f + 0.30f * smoothstep(((luma - 0.035f) / 0.24f).coerceIn(0f, 1f)) else 1f
                val adjustedSaturation = ((saturation + saturationBoost - 0.055f * greenWeight * (1f - smoothstep(((value - 0.25f) / 0.55f).coerceIn(0f, 1f)))) * shadowChromaRetention)
                    .coerceIn(0f, 1f)
                hsv[0] = (hue + hueShift * amount).let { (it % 360f + 360f) % 360f }
                hsv[1] = saturation + (adjustedSaturation - saturation) * amount
                hsv[2] = 1f
                val fullColor = Color.HSVToColor(hsv)
                val fullLuma = lumaOf(fullColor).coerceAtLeast(0.02f)
                val targetValue = (target / fullLuma).coerceIn(0f, 1f)
                hsv[2] = value + (targetValue - value) * amount

                // Mild warm highlights and cooler shadows imitate the M9's
                // film-like colour separation without applying a fixed LUT tint.
                val shadowWeight = 1f - smoothstep(((luma - 0.12f) / 0.48f).coerceIn(0f, 1f))
                val highlightWeight = smoothstep(((luma - 0.56f) / 0.40f).coerceIn(0f, 1f))
                val graded = Color.HSVToColor(hsv)
                var nr = Color.red(graded) / 255f
                var ng = Color.green(graded) / 255f
                var nb = Color.blue(graded) / 255f
                nr += (0.006f * highlightWeight - 0.003f * shadowWeight) * amount * whiteBalanceScale
                ng += (0.001f * highlightWeight) * amount * whiteBalanceScale
                nb += (-0.005f * highlightWeight + 0.004f * shadowWeight) * amount * whiteBalanceScale
                outputRow[x] = Color.argb(alpha, toByte(nr), toByte(ng), toByte(nb))
            }
            out.setPixels(outputRow, 0, width, 0, y, width, 1)
        }
        return out
    }

    private fun percentile(histogram: IntArray, total: Long, fraction: Float): Int {
        val threshold = (total.toFloat() * fraction).toLong()
        var accumulated = 0L
        for (i in histogram.indices) {
            accumulated += histogram[i]
            if (accumulated >= threshold) return i
        }
        return 255
    }

    private fun m9HueShift(hue: Float): Float =
        gaussianHue(hue, 4f, 20f) * 4.5f +
            gaussianHue(hue, 30f, 17f) * 4.0f +
            gaussianHue(hue, 59f, 19f) * 5.0f +
            gaussianHue(hue, 188f, 26f) * 10.0f

    private fun gaussianHue(hue: Float, center: Float, width: Float): Float {
        val direct = abs(hue - center)
        val distance = min(direct, 360f - direct)
        return exp(-(distance * distance) / (2f * width * width))
    }

    private fun lumaOf(color: Int): Float =
        0.2126f * Color.red(color) / 255f + 0.7152f * Color.green(color) / 255f + 0.0722f * Color.blue(color) / 255f

    private fun smoothstep(value: Float): Float = value * value * (3f - 2f * value)
    private fun toByte(value: Float): Int = (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
}
