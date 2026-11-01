package ca.pwalker.ultratone

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Fast, optional Night-only local-light restoration.
 *
 * Uses a multi-scale illumination estimate, then restores local reflectance while
 * gently compressing highlights and reducing chroma in deep shadows. This is a
 * lightweight on-device pass, not a bundled DarkIR neural network.
 */
internal object NightEnhancement {
    private const val MAX_MAP_SIDE = 480

    fun apply(source: Bitmap): Bitmap {
        val scale = min(1f, MAX_MAP_SIDE.toFloat() / max(source.width, source.height))
        val mapWidth = max(1, (source.width * scale).toInt())
        val mapHeight = max(1, (source.height * scale).toInt())
        val small = if (mapWidth == source.width && mapHeight == source.height) source
            else Bitmap.createScaledBitmap(source, mapWidth, mapHeight, true)
        val pixels = IntArray(mapWidth * mapHeight)
        small.getPixels(pixels, 0, mapWidth, 0, 0, mapWidth, mapHeight)
        if (small !== source) small.recycle()

        val luma = FloatArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            luma[i] = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f
        }

        // Blend fine and broad illumination estimates to avoid a single-scale halo.
        val fine = boxBlur(luma, mapWidth, mapHeight, max(2, min(mapWidth, mapHeight) / 64))
        val broad = boxBlur(luma, mapWidth, mapHeight, max(4, min(mapWidth, mapHeight) / 20))
        val illumination = FloatArray(luma.size) { i -> fine[i] * 0.62f + broad[i] * 0.38f }

        // Precompute the non-linear restoration curve to keep the full-size pass cheap.
        val curve = ByteArray(256 * 256)
        for (light in 0..255) {
            val local = light / 255f
            val mappedLight = 0.93f * local.pow(0.78f) + 0.018f
            for (value in 0..255) {
                val y = value / 255f
                val reflectance = (y / (local + 0.045f)).coerceIn(0f, 2.6f)
                val restored = (mappedLight * reflectance.pow(0.90f)).coerceIn(0f, 1f)
                val result = (y + (restored - y) * 0.72f).coerceIn(0f, 1f)
                curve[(light shl 8) or value] = (result * 255f + 0.5f).toInt().toByte()
            }
        }

        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val row = IntArray(source.width)
        val sourceWidth = source.width
        val sourceHeight = source.height
        val xScale = mapWidth.toFloat() / sourceWidth
        val yScale = mapHeight.toFloat() / sourceHeight
        for (y in 0 until sourceHeight) {
            source.getPixels(row, 0, sourceWidth, 0, y, sourceWidth, 1)
            val mapY = ((y + 0.5f) * yScale - 0.5f).coerceIn(0f, (mapHeight - 1).toFloat())
            val y0 = mapY.toInt()
            val y1 = min(mapHeight - 1, y0 + 1)
            val fy = mapY - y0
            val row0 = y0 * mapWidth
            val row1 = y1 * mapWidth
            for (x in 0 until sourceWidth) {
                val c = row[x]
                val red = Color.red(c)
                val green = Color.green(c)
                val blue = Color.blue(c)
                val yByte = ((54 * red + 183 * green + 19 * blue) shr 8).coerceIn(0, 255)
                val mapX = ((x + 0.5f) * xScale - 0.5f).coerceIn(0f, (mapWidth - 1).toFloat())
                val x0 = mapX.toInt()
                val x1 = min(mapWidth - 1, x0 + 1)
                val fx = mapX - x0
                val top = illumination[row0 + x0] + (illumination[row0 + x1] - illumination[row0 + x0]) * fx
                val bottom = illumination[row1 + x0] + (illumination[row1 + x1] - illumination[row1 + x0]) * fx
                val lightByte = ((top + (bottom - top) * fy) * 255f + 0.5f).toInt().coerceIn(0, 255)
                val restoredY = curve[(lightByte shl 8) or yByte].toInt() and 0xff
                val saturation = 0.70f + 0.30f * smoothstep(0.06f, 0.34f, yByte / 255f)
                row[x] = Color.rgb(
                    (restoredY + (red - yByte) * saturation).toInt().coerceIn(0, 255),
                    (restoredY + (green - yByte) * saturation).toInt().coerceIn(0, 255),
                    (restoredY + (blue - yByte) * saturation).toInt().coerceIn(0, 255)
                )
            }
            output.setPixels(row, 0, sourceWidth, 0, y, sourceWidth, 1)
        }
        return output
    }

    private fun boxBlur(input: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val horizontal = FloatArray(input.size)
        val output = FloatArray(input.size)
        val diameter = radius * 2 + 1
        for (y in 0 until height) {
            var sum = 0f
            for (i in -radius..radius) sum += input[y * width + i.coerceIn(0, width - 1)]
            for (x in 0 until width) {
                horizontal[y * width + x] = sum / diameter
                sum += input[y * width + (x + radius + 1).coerceIn(0, width - 1)] -
                    input[y * width + (x - radius).coerceIn(0, width - 1)]
            }
        }
        for (x in 0 until width) {
            var sum = 0f
            for (i in -radius..radius) sum += horizontal[i.coerceIn(0, height - 1) * width + x]
            for (y in 0 until height) {
                output[y * width + x] = sum / diameter
                sum += horizontal[(y + radius + 1).coerceIn(0, height - 1) * width + x] -
                    horizontal[(y - radius).coerceIn(0, height - 1) * width + x]
            }
        }
        return output
    }

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
