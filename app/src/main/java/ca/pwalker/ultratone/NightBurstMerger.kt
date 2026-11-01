package ca.pwalker.ultratone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A conservative two-frame fallback for phones without a CameraX Night extension. */
object NightBurstMerger {
    data class Frame(val jpegBytes: ByteArray, val rotationDegrees: Int)

    fun merge(frames: List<Frame>): Bitmap {
        require(frames.size == 2) { "Night fallback expects two captures" }
        val first = decodeUpright(frames[0])
        val base = first.copy(Bitmap.Config.ARGB_8888, true) ?: error("Could not prepare night merge")
        first.recycle()
        val width = base.width
        val height = base.height
        val reference = IntArray(width * height)
        base.getPixels(reference, 0, width, 0, 0, width, height)

        val second = decodeUpright(frames[1])
        val aligned = if (second.width != width || second.height != height) {
            Bitmap.createScaledBitmap(second, width, height, true).also { if (it !== second) second.recycle() }
        } else second
        try {
            val (dx, dy) = estimateShift(reference, aligned, width, height)
            val secondRow = IntArray(width)
            val outputRow = IntArray(width)
            for (y in 0 until height) {
                base.getPixels(outputRow, 0, width, 0, y, width, 1)
                val sourceY = y + dy
                if (sourceY in 0 until height) {
                    aligned.getPixels(secondRow, 0, width, 0, sourceY, width, 1)
                    for (x in 0 until width) {
                        val sourceX = x + dx
                        if (sourceX !in 0 until width) continue
                        val original = reference[y * width + x]
                        val candidate = secondRow[sourceX]
                        // Reject areas that changed substantially between exposures
                        // (moving subjects, foliage, traffic) to avoid obvious ghosts.
                        if (isStable(original, candidate)) {
                            val current = outputRow[x]
                            outputRow[x] = Color.rgb(
                                (Color.red(current) + Color.red(candidate)) / 2,
                                (Color.green(current) + Color.green(candidate)) / 2,
                                (Color.blue(current) + Color.blue(candidate)) / 2
                            )
                        }
                    }
                }
                base.setPixels(outputRow, 0, width, 0, y, width, 1)
            }
            return base
        } catch (e: Exception) {
            base.recycle()
            throw e
        } finally {
            aligned.recycle()
        }
    }

    private fun decodeUpright(frame: Frame): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(frame.jpegBytes, 0, frame.jpegBytes.size, bounds)
        var sample = 1
        while (bounds.outWidth.toLong() / sample * (bounds.outHeight.toLong() / sample) > 16_000_000L) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(frame.jpegBytes, 0, frame.jpegBytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: error("Could not decode night capture")
        val rotation = ((frame.rotationDegrees % 360) + 360) % 360
        if (rotation == 0) return decoded
        return Bitmap.createBitmap(
            decoded, 0, 0, decoded.width, decoded.height,
            android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }, true
        ).also { if (it !== decoded) decoded.recycle() }
    }

    private fun estimateShift(reference: IntArray, frame: Bitmap, width: Int, height: Int): Pair<Int, Int> {
        val scale = 16
        val smallWidth = max(1, width / scale)
        val smallHeight = max(1, height / scale)
        val small = Bitmap.createScaledBitmap(frame, smallWidth, smallHeight, true)
        val pixels = IntArray(smallWidth * smallHeight)
        small.getPixels(pixels, 0, smallWidth, 0, 0, smallWidth, smallHeight)
        if (small !== frame) small.recycle()

        var bestDx = 0
        var bestDy = 0
        var bestCost = Double.POSITIVE_INFINITY
        val left = smallWidth / 8
        val right = smallWidth * 7 / 8
        val top = smallHeight / 8
        val bottom = smallHeight * 7 / 8
        val stride = 4
        for (dy in -3..3) for (dx in -3..3) {
            var cost = 0.0
            var count = 0
            var y = top
            while (y < bottom) {
                val ry = y * scale
                if (ry in 0 until height) {
                    var x = left
                    while (x < right) {
                        val rx = x * scale
                        if (rx in 0 until width) {
                            val base = reference[ry * width + rx]
                            val baseLuma = luma(base)
                            if (baseLuma in 20..235) {
                                val fx = x + dx
                                val fy = y + dy
                                if (fx in 0 until smallWidth && fy in 0 until smallHeight) {
                                    cost += abs(baseLuma - luma(pixels[fy * smallWidth + fx]))
                                    count++
                                }
                            }
                        }
                        x += stride
                    }
                }
                y += stride
            }
            val normalized = if (count < 32) Double.POSITIVE_INFINITY else cost / count
            if (normalized < bestCost) {
                bestCost = normalized
                bestDx = dx * scale
                bestDy = dy * scale
            }
        }
        return bestDx to bestDy
    }

    private fun isStable(a: Int, b: Int): Boolean =
        abs(luma(a) - luma(b)) <= 42 &&
            (abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))) <= 105

    private fun luma(color: Int): Int =
        (0.2126f * Color.red(color) + 0.7152f * Color.green(color) + 0.0722f * Color.blue(color)).toInt()
}
