package ca.pwalker.ultratone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import kotlin.math.abs
import kotlin.math.max

/** Builds a long exposure from normally metered frames so static highlights do not accumulate. */
internal class LongExposureMerger {
    data class Frame(val jpegBytes: ByteArray, val rotationDegrees: Int)

    private var width = 0
    private var height = 0
    private var frameCount = 0
    private var averagePixels: IntArray? = null
    private var brightestPixels: IntArray? = null
    private var brightestLuma: ByteArray? = null

    fun add(frame: Frame) {
        val bitmap = decodeUpright(frame)
        try {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            if (averagePixels == null) {
                width = w
                height = h
                averagePixels = pixels
                brightestPixels = pixels.copyOf()
                brightestLuma = ByteArray(pixels.size) { luma(pixels[it]).toByte() }
                frameCount = 1
                return
            }
            require(w == width && h == height) { "Long exposure frames changed size" }

            val average = averagePixels!!
            val brightest = brightestPixels!!
            val peak = brightestLuma!!
            val (dx, dy) = estimateShift(average, pixels)
            val nextCount = frameCount + 1
            for (y in 0 until height) {
                val sourceY = y + dy
                if (sourceY !in 0 until height) continue
                val targetRow = y * width
                val sourceRow = sourceY * width
                for (x in 0 until width) {
                    val sourceX = x + dx
                    if (sourceX !in 0 until width) continue
                    val index = targetRow + x
                    val sample = pixels[sourceRow + sourceX]
                    val current = average[index]
                    average[index] = runningAverage(current, sample, nextCount)
                    val sampleLuma = luma(sample)
                    if (sampleLuma > (peak[index].toInt() and 0xff)) {
                        peak[index] = sampleLuma.toByte()
                        brightest[index] = sample
                    }
                }
            }
            frameCount = nextCount
        } finally {
            bitmap.recycle()
        }
    }

    fun finish(): Bitmap {
        val average = averagePixels ?: error("No long exposure frames were captured")
        val brightest = brightestPixels ?: error("No long exposure frames were captured")
        val peak = brightestLuma ?: error("No long exposure frames were captured")
        for (i in average.indices) {
            if (frameCount < 2) break
            val meanLuma = luma(average[i])
            val peakLuma = peak[i].toInt() and 0xff
            // Preserve bright moving light sources as trails. This only selects values
            // that were present in an input frame; it cannot add extra exposure gain.
            if (peakLuma >= 205 && peakLuma - meanLuma >= 55) average[i] = brightest[i]
        }
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(average, 0, width, 0, 0, width, height)
        averagePixels = null
        brightestPixels = null
        brightestLuma = null
        return output
    }

    fun frameCount(): Int = frameCount

    fun aspectRatio(): Float? = if (frameCount > 0 && height > 0) width.toFloat() / height else null

    private fun decodeUpright(frame: Frame): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(frame.jpegBytes, 0, frame.jpegBytes.size, bounds)
        var sample = 1
        while (bounds.outWidth.toLong() / sample * (bounds.outHeight.toLong() / sample) > MAX_PIXELS) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(frame.jpegBytes, 0, frame.jpegBytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: error("Could not decode long exposure frame")
        val rotation = ((frame.rotationDegrees % 360) + 360) % 360
        if (rotation == 0) return decoded
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height,
            Matrix().apply { postRotate(rotation.toFloat()) }, true).also { if (it !== decoded) decoded.recycle() }
    }

    /** Small global translation alignment counters hand movement without blurring the whole frame. */
    private fun estimateShift(reference: IntArray, sample: IntArray): Pair<Int, Int> {
        val scale = max(1, max(width, height) / 240)
        val smallWidth = max(1, width / scale)
        val smallHeight = max(1, height / scale)
        val radius = max(1, 3 / scale + 1)
        val left = smallWidth / 8
        val right = smallWidth * 7 / 8
        val top = smallHeight / 8
        val bottom = smallHeight * 7 / 8
        var bestDx = 0
        var bestDy = 0
        var bestCost = Double.POSITIVE_INFINITY
        for (dy in -radius..radius) for (dx in -radius..radius) {
            var cost = 0L
            var count = 0
            var y = top
            while (y < bottom) {
                val ry = y * scale
                val sy = y + dy
                if (sy in 0 until smallHeight) {
                    var x = left
                    while (x < right) {
                        val sx = x + dx
                        if (sx in 0 until smallWidth) {
                            val a = reference[ry * width + x * scale]
                            val b = sample[sy * scale * width + sx * scale]
                            val la = luma(a)
                            if (la in 18..238) {
                                cost += abs(la - luma(b))
                                count++
                            }
                        }
                        x += 3
                    }
                }
                y += 3
            }
            if (count >= 24) {
                val normalized = cost.toDouble() / count
                if (normalized < bestCost) {
                    bestCost = normalized
                    bestDx = dx * scale
                    bestDy = dy * scale
                }
            }
        }
        return bestDx to bestDy
    }

    private fun runningAverage(a: Int, b: Int, count: Int): Int {
        val r = (((a ushr 16 and 0xff).toLong() * (count - 1) + (b ushr 16 and 0xff)) / count).toInt()
        val g = (((a ushr 8 and 0xff).toLong() * (count - 1) + (b ushr 8 and 0xff)) / count).toInt()
        val blue = (((a and 0xff).toLong() * (count - 1) + (b and 0xff)) / count).toInt()
        return (0xff shl 24) or (r shl 16) or (g shl 8) or blue
    }

    private fun luma(color: Int): Int =
        (54 * (color ushr 16 and 0xff) + 183 * (color ushr 8 and 0xff) + 19 * (color and 0xff)) shr 8

    private companion object { const val MAX_PIXELS = 8_000_000L }
}
