package ca.pwalker.ultratone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import kotlin.math.roundToInt

/** Builds a long exposure from normally metered frames so static highlights do not accumulate. */
internal class LongExposureMerger {
    data class Frame(val jpegBytes: ByteArray, val rotationDegrees: Int)

    private var reference: FrameAlignment? = null
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
                reference = FrameAlignment(pixels, w, h)
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
            val transform = reference!!.transformTo(FrameAlignment(pixels, w, h)) ?: return
            val (cosine, sine, tx, ty) = transform
            val nextCount = frameCount + 1
            for (y in 0 until height) {
                val targetRow = y * width
                val startX = tx - sine * y
                val startY = ty + cosine * y
                for (x in 0 until width) {
                    val sx = (startX + cosine * x).roundToInt()
                    val sy = (startY + sine * x).roundToInt()
                    if (sx !in 0 until width || sy !in 0 until height) continue
                    val index = targetRow + x
                    val sample = pixels[sy * width + sx]
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
