package ca.pwalker.ultratone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.abs
import kotlin.math.max

/**
 * A conservative, two-frame fallback for phones without a CameraX Night extension.
 *
 * Work stays on the photo-processing executor. We only blend pixels that remain
 * close between frames, so moving details fall back to the first exposure.
 */
object NightBurstMerger {
    private const val MAX_DECODE_PIXELS = 16_000_000L
    private const val ALIGNMENT_SCALE = 16
    private const val DETAIL_ALIGNMENT_SCALE = 8
    private const val MAX_LUMA_DELTA = 24
    private const val MAX_RGB_DELTA = 72

    data class Frame(
        val jpegBytes: ByteArray,
        val rotationDegrees: Int,
        val width: Int = 0,
        val height: Int = 0
    )

    /**
     * Two-frame photo stack for the optional deblur mode. Start with whichever
     * capture has more fine detail, then blend only aligned, stable pixels from
     * its partner. This keeps moving edges from becoming double images.
     */
    fun mergeDetail(frames: List<Frame>): Bitmap {
        require(frames.size == 2) { "Photo stack expects two captures" }
        val firstScore = sharpnessScore(frames[0])
        val secondScore = sharpnessScore(frames[1])
        val referenceFirst = secondScore <= firstScore * 1.02
        return mergeInternal(if (referenceFirst) frames else frames.reversed(), detailAlignment = true)
    }

    fun merge(frames: List<Frame>): Bitmap = mergeInternal(frames, detailAlignment = false)

    /**
     * Combines a short burst into a light-trail photograph. Frames are aligned to
     * the accumulated scene, then only significantly brighter moving pixels are
     * retained so stationary areas remain close to the first exposure.
     */
    private fun mergeInternal(frames: List<Frame>, detailAlignment: Boolean): Bitmap {
        require(frames.size == 2) { "Burst merge expects two captures" }
        // Decode the reference mutable so we can reuse it directly. This avoids
        // a full-resolution bitmap copy on the capture-processing path.
        val base = decodeUpright(frames[0], mutable = true)
        var aligned: Bitmap? = null
        try {
            val width = base.width
            val height = base.height
            val reference = IntArray(width * height)
            base.getPixels(reference, 0, width, 0, 0, width, height)

            val second = decodeUpright(frames[1], mutable = false)
            val alignedFrame = if (second.width != width || second.height != height) {
                Bitmap.createScaledBitmap(second, width, height, true).also {
                    if (it !== second) second.recycle()
                }
            } else second
            aligned = alignedFrame

            val (dx, dy) = estimateShift(reference, alignedFrame, width, height,
                if (detailAlignment) DETAIL_ALIGNMENT_SCALE else ALIGNMENT_SCALE)
            val secondRow = IntArray(width)
            for (y in 0 until height) {
                val sourceY = y + dy
                if (sourceY !in 0 until height) continue
                alignedFrame.getPixels(secondRow, 0, width, 0, sourceY, width, 1)
                val rowOffset = y * width
                for (x in 0 until width) {
                    val sourceX = x + dx
                    if (sourceX !in 0 until width) continue
                    val index = rowOffset + x
                    val original = reference[index]
                    val candidate = secondRow[sourceX]
                    if (isStable(original, candidate)) {
                        reference[index] = averageRgb(original, candidate)
                    }
                }
            }
            // One full bitmap write replaces the previous per-row bitmap writes.
            base.setPixels(reference, 0, width, 0, 0, width, height)
            return base
        } catch (e: Exception) {
            base.recycle()
            throw e
        } finally {
            aligned?.recycle()
        }
    }

    private fun sharpnessScore(frame: Frame): Double {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(frame.jpegBytes, 0, frame.jpegBytes.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 256) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            frame.jpegBytes, 0, frame.jpegBytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: return 0.0
        val upright = if (frame.rotationDegrees % 360 != 0) {
            Bitmap.createBitmap(
                decoded, 0, 0, decoded.width, decoded.height,
                android.graphics.Matrix().apply { postRotate(frame.rotationDegrees.toFloat()) }, true
            ).also { if (it !== decoded) decoded.recycle() }
        } else decoded
        try {
            val pixels = IntArray(upright.width * upright.height)
            upright.getPixels(pixels, 0, upright.width, 0, 0, upright.width, upright.height)
            var score = 0L
            var count = 0
            for (y in 1 until upright.height - 1) {
                val row = y * upright.width
                for (x in 1 until upright.width - 1) {
                    val center = luma(pixels[row + x])
                    if (center !in 12..243) continue
                    val laplacian = 4 * center - luma(pixels[row + x - 1]) -
                        luma(pixels[row + x + 1]) - luma(pixels[row - upright.width + x]) -
                        luma(pixels[row + upright.width + x])
                    score += abs(laplacian)
                    count++
                }
            }
            return if (count == 0) 0.0 else score.toDouble() / count
        } finally {
            upright.recycle()
        }
    }

    private fun decodeUpright(frame: Frame, mutable: Boolean): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(frame.jpegBytes, 0, frame.jpegBytes.size, bounds)
        var sample = 1
        while (bounds.outWidth.toLong() / sample * (bounds.outHeight.toLong() / sample) > MAX_DECODE_PIXELS) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(frame.jpegBytes, 0, frame.jpegBytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = mutable
        }) ?: error("Could not decode night capture")
        val rotation = ((frame.rotationDegrees % 360) + 360) % 360
        if (rotation == 0) return decoded
        return Bitmap.createBitmap(
            decoded, 0, 0, decoded.width, decoded.height,
            android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }, true
        ).also { if (it !== decoded) decoded.recycle() }
    }

    private fun estimateShift(reference: IntArray, frame: Bitmap, width: Int, height: Int, scale: Int): Pair<Int, Int> {
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
        val searchRadius = if (scale == DETAIL_ALIGNMENT_SCALE) 4 else 3
        for (dy in -searchRadius..searchRadius) for (dx in -searchRadius..searchRadius) {
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

    private fun isStable(a: Int, b: Int): Boolean {
        val redA = a ushr 16 and 0xff
        val greenA = a ushr 8 and 0xff
        val blueA = a and 0xff
        val redB = b ushr 16 and 0xff
        val greenB = b ushr 8 and 0xff
        val blueB = b and 0xff
        val lumaDelta = abs(54 * (redA - redB) + 183 * (greenA - greenB) + 19 * (blueA - blueB)) shr 8
        return lumaDelta <= MAX_LUMA_DELTA &&
            abs(redA - redB) + abs(greenA - greenB) + abs(blueA - blueB) <= MAX_RGB_DELTA
    }

    private fun averageRgb(a: Int, b: Int): Int {
        val red = ((a ushr 16 and 0xff) + (b ushr 16 and 0xff)) ushr 1
        val green = ((a ushr 8 and 0xff) + (b ushr 8 and 0xff)) ushr 1
        val blue = ((a and 0xff) + (b and 0xff)) ushr 1
        return (0xff shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun luma(color: Int): Int =
        (54 * (color ushr 16 and 0xff) + 183 * (color ushr 8 and 0xff) + 19 * (color and 0xff)) shr 8
}
