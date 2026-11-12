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
        val vignette: Boolean = false,
        val sculpt: Boolean = false
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
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val detailScale = 0.035f + ((zoomRatio - 1f) / 4f).coerceIn(0f, 1f) * 0.025f
        val night = if (mode == Mode.NIGHT) 1f else 0f
        val sunset = mode == Mode.SUNSET
        val haloX = max(1, width / 150)
        val haloY = max(1, height / 150)
        val needHaloRows = effects.glow
        val rowsPerBatch = 128
        val sculptX = max(1, width / 32)
        val sculptY = max(1, height / 32)
        val readHalo = max(if (needHaloRows) haloY else 0, if (effects.sculpt) sculptY else 0)
        val inputBatch = IntArray(width * (rowsPerBatch + readHalo * 2))
        val outputBatch = IntArray(width * rowsPerBatch)

        for (batchStart in 0 until height step rowsPerBatch) {
            val batchRows = min(rowsPerBatch, height - batchStart)
            val readTop = (batchStart - readHalo).coerceAtLeast(0)
            val readBottom = (batchStart + batchRows - 1 + readHalo).coerceAtMost(height - 1)
            val readRows = readBottom - readTop + 1
            source.getPixels(inputBatch, 0, width, 0, readTop, width, readRows)
            for (y in batchStart until batchStart + batchRows) {
                val inputRow = (y - readTop) * width
                val upperRow = ((y - haloY).coerceIn(0, height - 1) - readTop) * width
                val lowerRow = ((y + haloY).coerceIn(0, height - 1) - readTop) * width
                val outputRow = (y - batchStart) * width
                val v = if (height <= 1) 0f else (y.toFloat() / (height - 1) - 0.5f) * 2f
                for (x in 0 until width) {
                val pixel = inputBatch[inputRow + x]
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

                if (effects.sculpt) {
                    // Broad, range-weighted light shaping, not edge sharpening.
                    // Ignore strong discontinuities to limit halos at silhouettes.
                    var total = luma * 2f
                    var weights = 2f
                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val sx = (x + dx * sculptX).coerceIn(0, width - 1)
                        val sy = (y + dy * sculptY).coerceIn(0, height - 1)
                        val sample = lumaOf(inputBatch[(sy - readTop) * width + sx])
                        val weight = 1f - smoothstep(0.06f, 0.22f, kotlin.math.abs(sample - luma))
                        total += sample * weight
                        weights += weight
                    }
                    val local = ((luma - total / weights) * 0.35f).coerceIn(-0.035f, 0.035f)
                    val shadow = smoothstep(0.035f, 0.18f, luma) * (1f - smoothstep(0.30f, 0.52f, luma))
                    val light = smoothstep(0.40f, 0.65f, luma) * (1f - smoothstep(0.78f, 0.96f, luma))
                    val protect = smoothstep(0.02f, 0.12f, luma) * (1f - smoothstep(0.82f, 0.98f, luma))
                    val delta = local * protect - 0.035f * shadow + 0.025f * light
                    val gain = 1f + delta / max(luma, 0.08f)
                    // Shared channel gain preserves hue and neutral B&W.
                    val safeGain = min(gain, 1f / max(0.001f, max(nr, max(ng, nb))))
                    nr *= safeGain; ng *= safeGain; nb *= safeGain
                }

                if (effects.glow) {
                    val left = inputBatch[inputRow + (x - haloX).coerceIn(0, width - 1)]
                    val right = inputBatch[inputRow + (x + haloX).coerceIn(0, width - 1)]
                    val glowSource = max(
                        lumaOf(inputBatch[upperRow + x]),
                        max(lumaOf(inputBatch[lowerRow + x]), max(lumaOf(left), lumaOf(right)))
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

                outputBatch[outputRow + x] = Color.argb(alpha, toByte(nr), toByte(ng), toByte(nb))
                }
            }
            output.setPixels(outputBatch, 0, width, 0, batchStart, width, batchRows)
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
