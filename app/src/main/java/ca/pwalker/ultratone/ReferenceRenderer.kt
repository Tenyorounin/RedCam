package ca.pwalker.ultratone

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/** CPU version of the live GPU grade, used to render full-resolution captures. */
object ReferenceRenderer {
    enum class Mode { DAY, NIGHT, CLOSE_UP }
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
        val input = IntArray(width)
        val outputRow = IntArray(width)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val detailScale = 0.035f + ((zoomRatio - 1f) / 4f).coerceIn(0f, 1f) * 0.025f
        val night = if (mode == Mode.NIGHT) 1f else 0f

        for (y in 0 until height) {
            source.getPixels(input, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val pixel = input[x]
                val alpha = pixel ushr 24 and 0xff
                val r = (pixel ushr 16 and 0xff) / 255f
                val g = (pixel ushr 8 and 0xff) / 255f
                val b = (pixel and 0xff) / 255f
                val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b

                if (finish == Finish.BLACK_AND_WHITE) {
                    val mono = 0.28f * r + 0.62f * g + 0.10f * b
                    val bw = ((mono - 0.46f) * 1.16f + 0.46f).coerceIn(0f, 1f)
                    val value = luma + (bw - luma) * amount
                    val grey = toByte(value)
                    outputRow[x] = Color.argb(alpha, grey, grey, grey)
                    continue
                }

                var target = (luma - 0.46f) * 1.08f + 0.46f + ((0.46f - luma) * 0.08f).coerceIn(-0.025f, 0.025f)
                target += 0.014f * (1f - smoothstep(0f, 0.30f, target))
                val shoulder = smoothstep(0.72f, 1f, target)
                target -= 0.16f * shoulder * max(target - 0.72f, 0f)
                val detail = ((luma - 0.46f) * detailScale).coerceIn(-0.025f, 0.025f)
                target = (luma + (target + detail - luma) * amount).coerceIn(0f, 1f)

                val saturation = max(r, max(g, b)) - min(r, min(g, b))
                val chromaScale = 1.04f + saturation.coerceIn(0f, 0.6f) * 0.10f
                var nr = target + (r - luma) * chromaScale
                var ng = target + (g - luma) * chromaScale
                var nb = target + (b - luma) * chromaScale

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
                outputRow[x] = Color.argb(alpha, toByte(nr), toByte(ng), toByte(nb))
            }
            output.setPixels(outputRow, 0, width, 0, y, width, 1)
        }
        return output
    }

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun toByte(value: Float) = (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
}
