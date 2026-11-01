package ca.pwalker.ultratone

import android.graphics.Bitmap
import android.graphics.Color
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/** Person-aware background blur for portrait photos. The bundled ML Kit model runs on-device. */
object PortraitBlur {
    data class Result(val bitmap: Bitmap, val subjectDetected: Boolean)

    fun apply(source: Bitmap): Result {
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
        val segmenter = Segmentation.getClient(options)
        val mask = try {
            Tasks.await(segmenter.process(InputImage.fromBitmap(source, 0)), 30, TimeUnit.SECONDS)
        } finally {
            segmenter.close()
        }

        val maskWidth = mask.width
        val maskHeight = mask.height
        val floatMask = mask.buffer.order(ByteOrder.nativeOrder()).asFloatBuffer().apply { position(0) }
        val background = blurredBackground(source)
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val row = IntArray(source.width)
        val bgRow = IntArray(source.width)
        val confidenceRow = FloatArray(maskWidth)
        var confidentForeground = 0

        for (y in 0 until source.height) {
            source.getPixels(row, 0, source.width, 0, y, source.width, 1)
            background.getPixels(bgRow, 0, source.width, 0, y, source.width, 1)
            val my = min(maskHeight - 1, y * maskHeight / source.height)
            floatMask.position(my * maskWidth)
            floatMask.get(confidenceRow, 0, maskWidth)
            for (x in 0 until source.width) {
                val mx = min(maskWidth - 1, x * maskWidth / source.width)
                val confidence = confidenceRow[mx].coerceIn(0f, 1f)
                if (confidence > 0.55f) confidentForeground++
                // Keep a soft transition at hair and clothing edges to avoid a cut-out halo.
                val t = ((confidence - 0.22f) / 0.58f).coerceIn(0f, 1f)
                val foreground = t * t * (3f - 2f * t)
                row[x] = mix(bgRow[x], row[x], foreground)
            }
            output.setPixels(row, 0, source.width, 0, y, source.width, 1)
        }
        background.recycle()
        val detected = confidentForeground > source.width * source.height / 100
        return if (detected) Result(output, true) else {
            output.recycle()
            Result(source, false)
        }
    }

    private fun blurredBackground(source: Bitmap): Bitmap {
        val scale = 8
        val width = max(1, source.width / scale)
        val height = max(1, source.height / scale)
        val small = Bitmap.createScaledBitmap(source, width, height, true)
        var pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        small.recycle()
        repeat(3) {
            pixels = boxBlur(pixels, width, height, 2)
        }
        val blurredSmall = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        blurredSmall.setPixels(pixels, 0, width, 0, 0, width, height)
        val full = Bitmap.createScaledBitmap(blurredSmall, source.width, source.height, true)
        blurredSmall.recycle()
        return full
    }

    private fun boxBlur(input: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val horizontal = IntArray(input.size)
        val output = IntArray(input.size)
        val diameter = radius * 2 + 1
        for (y in 0 until height) {
            var rs = 0; var gs = 0; var bs = 0; var count = 0
            for (i in -radius..radius) {
                val c = input[y * width + i.coerceIn(0, width - 1)]
                rs += Color.red(c); gs += Color.green(c); bs += Color.blue(c); count++
            }
            for (x in 0 until width) {
                horizontal[y * width + x] = Color.rgb(rs / count, gs / count, bs / count)
                val remove = input[y * width + (x - radius).coerceIn(0, width - 1)]
                val add = input[y * width + (x + radius + 1).coerceIn(0, width - 1)]
                rs += Color.red(add) - Color.red(remove)
                gs += Color.green(add) - Color.green(remove)
                bs += Color.blue(add) - Color.blue(remove)
            }
        }
        for (x in 0 until width) {
            var rs = 0; var gs = 0; var bs = 0; var count = 0
            for (i in -radius..radius) {
                val c = horizontal[i.coerceIn(0, height - 1) * width + x]
                rs += Color.red(c); gs += Color.green(c); bs += Color.blue(c); count++
            }
            for (y in 0 until height) {
                output[y * width + x] = Color.rgb(rs / count, gs / count, bs / count)
                val remove = horizontal[(y - radius).coerceIn(0, height - 1) * width + x]
                val add = horizontal[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                rs += Color.red(add) - Color.red(remove)
                gs += Color.green(add) - Color.green(remove)
                bs += Color.blue(add) - Color.blue(remove)
            }
        }
        return output
    }

    private fun mix(background: Int, foreground: Int, amount: Float): Int {
        val inv = 1f - amount
        return Color.rgb(
            (Color.red(background) * inv + Color.red(foreground) * amount).toInt(),
            (Color.green(background) * inv + Color.green(foreground) * amount).toInt(),
            (Color.blue(background) * inv + Color.blue(foreground) * amount).toInt()
        )
    }
}
