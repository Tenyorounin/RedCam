package ca.pwalker.ultratone

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** CPU version of the live GPU grade, used to render full-resolution captures. */
object ReferenceRenderer {
    enum class Mode { DAY, NIGHT, CLOSE_UP, SUNSET, LONG_EXPOSURE, LANDSCAPE }
    enum class Finish { COLOUR, BLACK_AND_WHITE }

    data class Effects(
        val glow: Boolean = false,
        val vignette: Boolean = false
    )

    fun render(
        source: Bitmap,
        strength: Float,
        mode: Mode = Mode.DAY,
        zoomRatio: Float = 1f,
        finish: Finish = Finish.COLOUR,
        effects: Effects = Effects()
    ): Bitmap {
        val amount = strength.coerceIn(0f, 1f)
        val width = source.width
        val height = source.height
        val input = IntArray(width)
        val upper = IntArray(width)
        val lower = IntArray(width)
        val outputRow = IntArray(width)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val detailScale = 0.035f + ((zoomRatio - 1f) / 4f).coerceIn(0f, 1f) * 0.025f
        val night = if (mode == Mode.NIGHT) 1f else 0f
        val sunset = mode == Mode.SUNSET
        val haloX = max(1, width / 150)
        val haloY = max(1, height / 150)
        val needHaloRows = effects.glow

        for (y in 0 until height) {
            source.getPixels(input, 0, width, 0, y, width, 1)
            if (needHaloRows) {
                source.getPixels(upper, 0, width, 0, (y - haloY).coerceAtLeast(0), width, 1)
                source.getPixels(lower, 0, width, 0, (y + haloY).coerceAtMost(height - 1), width, 1)
            }
            val v = if (height <= 1) 0f else (y.toFloat() / (height - 1) - 0.5f) * 2f
            for (x in 0 until width) {
                val pixel = input[x]
                val alpha = pixel ushr 24 and 0xff
                val r = (pixel ushr 16 and 0xff) / 255f
                val g = (pixel ushr 8 and 0xff) / 255f
                val b = (pixel and 0xff) / 255f
                val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                var nr: Float
                var ng: Float
                var nb: Float

                if (finish == Finish.BLACK_AND_WHITE) {
                    val mono = 0.28f * r + 0.62f * g + 0.10f * b
                    val bw = ((mono - 0.46f) * 1.16f + 0.46f).coerceIn(0f, 1f)
                    val value = luma + (bw - luma) * amount
                    nr = value
                    ng = value
                    nb = value
                } else {
                    var target = (luma - 0.46f) * 1.08f + 0.46f + ((0.46f - luma) * 0.08f).coerceIn(-0.025f, 0.025f)
                    target += 0.014f * (1f - smoothstep(0f, 0.30f, target))
                    val shoulder = smoothstep(0.72f, 1f, target)
                    target -= 0.16f * shoulder * max(target - 0.72f, 0f)
                    val detail = ((luma - 0.46f) * detailScale).coerceIn(-0.025f, 0.025f)
                    target = (luma + (target + detail - luma) * amount).coerceIn(0f, 1f)

                    val saturation = max(r, max(g, b)) - min(r, min(g, b))
                    val chromaScale = 1.04f + saturation.coerceIn(0f, 0.6f) * 0.10f
                    nr = target + (r - luma) * chromaScale
                    ng = target + (g - luma) * chromaScale
                    nb = target + (b - luma) * chromaScale

                    val warmTone = smoothstep(0.02f, 0.22f, min(r, g) - b)
                    val greenTone = smoothstep(0.015f, 0.18f, g - max(r, b))
                    val shadow = 1f - smoothstep(0.08f, 0.58f, luma)
                    val highlight = smoothstep(0.56f, 0.96f, luma)
                    nr += (0.012f * warmTone + 0.006f * highlight - 0.003f * shadow) * amount
                    ng += (-0.010f * greenTone + 0.001f * highlight) * amount
                    nb += (-0.008f * warmTone - 0.005f * highlight + (0.004f + 0.004f * night) * shadow) * amount
                    if (night > 0f) {
                        nr *= 1f - 0.025f * shadow
                        nb *= 1f + 0.025f * shadow
                    }
                    if (sunset) {
                        nr += 0.020f * highlight - 0.012f * shadow
                        ng += 0.004f * highlight + 0.003f * shadow
                        nb += -0.024f * highlight + 0.020f * shadow
                    }
                }

                if (effects.glow) {
                    val left = input[(x - haloX).coerceAtLeast(0)]
                    val right = input[(x + haloX).coerceAtMost(width - 1)]
                    val glowSource = max(
                        lumaOf(upper[x]),
                        max(lumaOf(lower[x]), max(lumaOf(left), lumaOf(right)))
                    )
                    if (effects.glow) {
                        val bloom = smoothstep(0.68f, 0.97f, glowSource) * 0.075f
                        nr += bloom
                        ng += bloom * 0.98f
                        nb += bloom * 0.92f
                    }
                }

                if (effects.vignette) {
                    val u = if (width <= 1) 0f else (x.toFloat() / (width - 1) - 0.5f) * 2f
                    val radius = sqrt(u * u + v * v)
                    val falloff = smoothstep(0.48f, 1.32f, radius)
                    val scale = 1f - 0.20f * falloff
                    nr *= scale
                    ng *= scale
                    nb *= scale
                }

                outputRow[x] = Color.argb(alpha, toByte(nr), toByte(ng), toByte(nb))
            }
            output.setPixels(outputRow, 0, width, 0, y, width, 1)
        }
        return output
    }

    private fun lumaOf(color: Int): Float =
        (0.2126f * (color ushr 16 and 0xff) +
            0.7152f * (color ushr 8 and 0xff) +
            0.0722f * (color and 0xff)) / 255f

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun toByte(value: Float) = (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
}
