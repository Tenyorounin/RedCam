package ca.pwalker.ultratone

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln

/** A device-range zoom slider with markers at CameraX physical-lens ratios. */
class LensZoomControl(context: Context) : LinearLayout(context) {
    private val zoomLabel = TextView(context)
    private val slider = SeekBar(context)
    private val tickOverlay: View
    private var minRatio = 1f
    private var maxRatio = 1f
    private var majorZoomStops = emptyList<Float>()
    private var updatingFromCamera = false
    var onZoomChanged: ((Float) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(10), 0)
        background = GradientDrawable().apply {
            setColor(0xcc111216.toInt())
            cornerRadius = dp(18).toFloat()
        }
        zoomLabel.apply {
            text = "1×"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "Current camera zoom"
        }
        addView(zoomLabel, LayoutParams(dp(46), LayoutParams.MATCH_PARENT))

        val track = FrameLayout(context)
        addView(track, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        slider.apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(0xffffc857.toInt())
            thumbTintList = ColorStateList.valueOf(0xffffd46f.toInt())
            progressBackgroundTintList = ColorStateList.valueOf(0xff85858a.toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val ratio = ratioAt(progress)
                    updateLabel(ratio)
                    if (fromUser && !updatingFromCamera) onZoomChanged?.invoke(ratio)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    val current = bar?.progress ?: return
                    val nearest = majorZoomStops.minByOrNull { kotlin.math.abs(progressAt(it) - current) } ?: return
                    val stopProgress = progressAt(nearest)
                    val snapDistance = (dp(20).toFloat() / bar.width.coerceAtLeast(1) * bar.max).toInt().coerceAtLeast(1)
                    if (kotlin.math.abs(stopProgress - current) <= snapDistance) {
                        updatingFromCamera = true
                        bar.progress = stopProgress
                        updateLabel(nearest)
                        updatingFromCamera = false
                        onZoomChanged?.invoke(nearest)
                    }
                }
            })
        }
        track.addView(slider, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        tickOverlay = object : View(context) {
            private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                strokeWidth = dp(1).toFloat()
                strokeCap = Paint.Cap.ROUND
                alpha = 205
            }
            private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xffffd052.toInt()
                strokeWidth = dp(4).toFloat()
                strokeCap = Paint.Cap.ROUND
            }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val left = slider.left + slider.paddingLeft.toFloat()
                val right = slider.right - slider.paddingRight.toFloat()
                val width = right - left
                if (width <= 0f) return
                val centerY = height / 2f
                // Fine white divisions make the continuous range readable at a glance.
                for (progress in 0..slider.max step 50) {
                    val x = left + width * progress / slider.max
                    canvas.drawLine(x, centerY - dp(3), x, centerY + dp(3), minorTickPaint)
                }
                // Mark the 1× reference, supported range limits, useful fallback
                // steps, and all physical-lens ratios reported by this device.
                majorZoomStops.forEach { ratio ->
                    val x = left + width * progressAt(ratio) / slider.max
                    canvas.drawLine(x, centerY - dp(10), x, centerY + dp(10), majorTickPaint)
                }
            }
        }.apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        track.addView(tickOverlay, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setZoomRange(min: Float, max: Float, lensRatios: List<Float>) {
        minRatio = min.takeIf { it.isFinite() && it > 0f } ?: 1f
        maxRatio = max.takeIf { it.isFinite() && it >= minRatio } ?: minRatio
        majorZoomStops = (lensRatios + listOf(minRatio, 1f, 2f, 5f, maxRatio))
            .filter { it.isFinite() && it >= minRatio && it <= maxRatio }
            .distinctBy { (it * 100).toInt() }
            .sorted()
        tickOverlay.invalidate()
        setZoomRatio(slider.progress.let(::ratioAt))
    }

    fun setZoomRatio(ratio: Float) {
        updatingFromCamera = true
        val safeRatio = ratio.coerceIn(minRatio, maxRatio)
        slider.progress = progressAt(safeRatio)
        updateLabel(safeRatio)
        updatingFromCamera = false
    }

    private fun progressAt(ratio: Float): Int {
        if (maxRatio <= minRatio) return 0
        val denominator = ln(maxRatio / minRatio)
        if (!denominator.isFinite() || denominator <= 0f) return 0
        return (ln(ratio.coerceIn(minRatio, maxRatio) / minRatio) / denominator * slider.max)
            .toInt().coerceIn(0, slider.max)
    }

    private fun ratioAt(progress: Int): Float {
        if (maxRatio <= minRatio) return minRatio
        val fraction = progress.toFloat() / slider.max.coerceAtLeast(1)
        return (minRatio * exp(ln(maxRatio / minRatio) * fraction)).coerceIn(minRatio, maxRatio)
    }

    private fun updateLabel(ratio: Float) {
        val value = if (ratio < 1f) String.format(Locale.US, "%.1f×", ratio)
        else String.format(Locale.US, if (ratio < 10f) "%.1f×" else "%.0f×", ratio)
        zoomLabel.text = value
        zoomLabel.contentDescription = "Current camera zoom, $value"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
