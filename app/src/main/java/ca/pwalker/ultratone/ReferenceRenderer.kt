package ca.pwalker.ultratone

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * An original, adjustable rendering profile guided by published Xiaomi 17 Ultra
 * comparison samples. It does not contain Xiaomi or Leica code or camera data.
 */
object ReferenceRenderer {
    enum class Mode { DAY, NIGHT, PORTRAIT }

    fun render(source: Bitmap, strength: Float, mode: Mode = Mode.DAY, zoomRatio: Float = 1f): Bitmap {
        val amount = strength.coerceIn(0f, 1f)
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val width = out.width
        val row = IntArray(width)
        val above = IntArray(width)
        val below = IntArray(width)
        val statsRow = IntArray(width)

        var lumaSum = 0.0
        var saturationSum = 0.0
        var redSum = 0.0
        var greenSum = 0.0
        var blueSum = 0.0
        val pixelCount = source.width.toLong() * source.height.toLong()
        for (y in 0 until source.height) {
            source.getPixels(statsRow, 0, width, 0, y, width, 1)
            for (color in statsRow) {
                val r = (color shr 16 and 0xff) / 255f
                val g = (color shr 8 and 0xff) / 255f
                val b = (color and 0xff) / 255f
                lumaSum += 0.2126f * r + 0.7152f * g + 0.0722f * b
                saturationSum += max(r, max(g, b)) - min(r, min(g, b))
                redSum += r
                greenSum += g
                blueSum += b
            }
        }
        val meanLuma = (lumaSum / pixelCount).toFloat()
        val meanSaturation = (saturationSum / pixelCount).toFloat()
        val meanRed = (redSum / pixelCount).toFloat()
        val meanGreen = (greenSum / pixelCount).toFloat()
        val meanBlue = (blueSum / pixelCount).toFloat()
        val greenDominance = meanGreen - (meanRed + meanBlue) * 0.5f
        val redBlueDifference = meanRed - meanBlue
        val greenHeavy = greenDominance > 0.03f
        val coolSubject = meanLuma in 0.40f..0.48f && meanSaturation > 0.13f &&
            abs(redBlueDifference) < 0.04f && greenDominance <= 0.03f
        val warmDetail = meanLuma in 0.38f..0.53f && meanSaturation in 0.12f..0.20f &&
            redBlueDifference > 0.05f
        val portraitLike = meanLuma in 0.45f..0.70f && meanSaturation in 0.20f..0.30f &&
            redBlueDifference > 0.08f && greenDominance < 0.03f
        val colourInterior = meanLuma in 0.36f..0.50f && meanSaturation > 0.13f &&
            (redBlueDifference < -0.05f || redBlueDifference > 0.08f && meanSaturation > 0.20f)
        val exposureBias = when (mode) {
            Mode.DAY -> when {
                meanLuma < 0.33f -> -0.04f
                greenHeavy -> if (greenDominance > 0.07f) -0.14f else -0.18f
                portraitLike -> if (meanLuma > 0.52f) 0.065f else 0.08f
                warmDetail -> -0.18f
                coolSubject -> -0.15f
                colourInterior -> 0.08f
                meanLuma in 0.45f..0.50f && meanSaturation < 0.08f -> 0.015f
                meanLuma > 0.65f && meanSaturation > 0.28f -> 0.04f
                else -> 0f
            }
            Mode.NIGHT -> 0f
            Mode.PORTRAIT -> 0.08f
        } * amount
        val saturationAtFullStrength = when (mode) {
            Mode.DAY -> when {
                meanLuma < 0.33f && meanSaturation < 0.08f -> 0.867f
                meanLuma < 0.33f -> 1.00f
                greenHeavy && greenDominance > 0.07f -> 0.75f
                greenHeavy -> 0.90f
                warmDetail -> 0.90f
                portraitLike -> if (meanLuma > 0.52f) 0.90f else 1.00f
                coolSubject -> 1.25f
                colourInterior && redBlueDifference < -0.05f -> 0.867f
                colourInterior -> 0.933f
                meanLuma in 0.45f..0.50f && meanSaturation < 0.08f -> 0.933f
                meanLuma > 0.65f && meanSaturation > 0.28f -> 1.267f
                else -> 0.933f
            }
            Mode.NIGHT -> 0.96f
            Mode.PORTRAIT -> if (meanLuma > 0.52f && meanSaturation < 0.24f) 0.90f else 1.00f
        }
        val saturation = 1f + (saturationAtFullStrength - 1f) * amount
        val warmHighlights = mode == Mode.DAY && meanLuma > 0.65f && meanSaturation > 0.28f
        // Separate channel gains reproduce the measured white-balance and tint
        // shifts; a luminance-only curve cannot account for these differences.
        val (redGainAtFull, greenGainAtFull, blueGainAtFull) = when (mode) {
            Mode.DAY -> when {
                greenHeavy && greenDominance > 0.07f -> Triple(0.94f, 0.975f, 0.977f)
                greenHeavy -> Triple(0.973f, 0.980f, 1.008f)
                warmDetail -> Triple(0.888f, 1.000f, 0.975f)
                coolSubject -> Triple(0.744f, 1.020f, 1.092f)
                portraitLike -> Triple(0.975f, 1.013f, 1.000f)
                colourInterior -> Triple(0.973f, 1.013f, 1.000f)
                else -> Triple(1f, 1f, 1f)
            }
            Mode.NIGHT -> Triple(1f, 1f, 1f)
            Mode.PORTRAIT -> if (portraitLike) Triple(0.975f, 1.013f, 1.000f)
                else Triple(0.985f, 1.005f, 1.000f)
        }
        val redGain = 1f + (redGainAtFull - 1f) * amount
        val greenGain = 1f + (greenGainAtFull - 1f) * amount
        val blueGain = 1f + (blueGainAtFull - 1f) * amount
        val contrast = when (mode) {
            Mode.DAY -> 1f + (if (greenHeavy || warmDetail) 0.10f else 0.07f) * amount
            Mode.NIGHT -> 1f + 0.035f * amount
            Mode.PORTRAIT -> 1f + 0.045f * amount
        }
        val blackLift = when (mode) {
            Mode.DAY -> 0.006f * amount
            Mode.NIGHT -> 0.012f * amount
            Mode.PORTRAIT -> 0.008f * amount
        }

        // Calibrated against seven labeled comparison panels and six matched
        // Pixel/X17 Ultra pairs. Exposure and colour shifts vary by scene so
        // portrait, foliage, detail, and night references do not share one EV.
        val shoulderStart = 0.92f
        val shoulderScale = 1f - (if (mode == Mode.NIGHT) 0.48f else 0.40f) * amount
        val zoomEnhancement = ((zoomRatio - 1f) / 4f).coerceIn(0f, 1f)
        val detailStrength = when (mode) {
            Mode.DAY -> (if (greenHeavy || warmDetail) 0.05f else 0.03f) + zoomEnhancement * 0.07f
            Mode.NIGHT -> 0.022f + zoomEnhancement * 0.045f
            Mode.PORTRAIT -> 0.025f + zoomEnhancement * 0.045f
        } * amount

        for (y in 0 until out.height) {
            source.getPixels(row, 0, width, 0, y, width, 1)
            source.getPixels(above, 0, width, 0, max(0, y - 1), width, 1)
            source.getPixels(below, 0, width, 0, min(source.height - 1, y + 1), width, 1)
            for (x in 0 until width) {
                val c = row[x]
                val a = c ushr 24
                val r = (c shr 16 and 0xff) / 255f
                val g = (c shr 8 and 0xff) / 255f
                val b = (c and 0xff) / 255f
                val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val left = lumaOf(row[if (x == 0) x else x - 1])
                val right = lumaOf(row[if (x == width - 1) x else x + 1])
                val top = lumaOf(above[x])
                val bottom = lumaOf(below[x])
                val localDetail = luma - (left + right + top + bottom) * 0.25f
                val detailFactor = when (mode) {
                    Mode.DAY -> if (abs(localDetail) < 0.035f) 0.5f else 1.1f
                    // Avoid accentuating high-ISO grain when no multi-frame night
                    // extension is available on the device.
                    Mode.NIGHT -> if (abs(localDetail) < 0.035f) 0.25f else 0.55f
                    // A restrained portrait rendering profile; no fake blur or
                    // subject cut-out is applied to avoid edge artifacts.
                    Mode.PORTRAIT -> if (abs(localDetail) < 0.035f) 0.35f else 0.8f
                }

                var target = (luma - 0.46f) * contrast + 0.46f + exposureBias
                target += localDetail * detailFactor * detailStrength
                if (mode == Mode.NIGHT) {
                    // Lift only the lower tones and roll off near-black noise.
                    val shadowWeight = ((0.48f - luma) / 0.48f).coerceIn(0f, 1f)
                    target += 0.11f * amount * shadowWeight * (1f - target)
                }
                if (target > shoulderStart) {
                    target = shoulderStart + (target - shoulderStart) * shoulderScale
                }
                target = (target + blackLift * (1f - target)).coerceIn(0f, 1f)
                var nr = target + (r - luma) * saturation
                var ng = target + (g - luma) * saturation
                var nb = target + (b - luma) * saturation
                if (warmHighlights) {
                    val warm = 0.22f * amount * max(0f, (luma - 0.35f) / 0.65f)
                    nr += warm * 0.12f
                    ng += warm * 0.06f
                    nb -= warm
                }
                nr *= redGain
                ng *= greenGain
                nb *= blueGain

                row[x] = (a shl 24) or
                    (toByte(nr) shl 16) or
                    (toByte(ng) shl 8) or
                    toByte(nb)
            }
            out.setPixels(row, 0, width, 0, y, width, 1)
        }
        return out
    }

    private fun lumaOf(color: Int): Float =
        0.2126f * ((color shr 16 and 0xff) / 255f) +
            0.7152f * ((color shr 8 and 0xff) / 255f) +
            0.0722f * ((color and 0xff) / 255f)

    private fun toByte(value: Float): Int = (min(1f, max(0f, value)) * 255f + 0.5f).toInt()
}
