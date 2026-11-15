package ca.pwalker.ultratone

import android.app.Activity
import android.app.Dialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** A modal tour: highlighted controls remain visible but cannot take accidental input. */
internal class HelpTour(
    private val activity: Activity,
    private val steps: List<Step>,
    private val prepare: (Int) -> Unit,
    private val finished: () -> Unit
) {
    data class Step(val title: String, val text: String, val extraTargets: () -> List<View> = { emptyList() }, val target: () -> View)
    private var index = 0
    private val dialog = Dialog(activity)
    private val title = TextView(activity).apply { textSize = 18f; setTextColor(0xffffd052.toInt()) }
    private val body = TextView(activity).apply { textSize = 15f; setTextColor(Color.WHITE); setPadding(0, dp(8), 0, dp(8)) }
    private val card = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(8))
        setBackgroundColor(0xff191b20.toInt())
    }
    private val textScroll = ScrollView(activity).apply {
        addView(LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; addView(title); addView(body) })
    }
    private val back = Button(activity).apply { text = "Back"; setOnClickListener { index--; showStep() } }
    private val next = Button(activity).apply {
        setOnClickListener { if (index == steps.lastIndex) dialog.dismiss() else { index++; showStep() } }
    }
    private val root = object : FrameLayout(activity) {
        val hole = RectF()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        init { setWillNotDraw(false) }
        override fun onDraw(canvas: Canvas) {
            val path = Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
                if (!hole.isEmpty) addRoundRect(hole, dp(8).toFloat(), dp(8).toFloat(), Path.Direction.CW)
            }
            paint.style = Paint.Style.FILL
            paint.color = 0xcc000000.toInt()
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(3).toFloat()
            paint.color = 0xffffd052.toInt()
            canvas.drawRoundRect(hole, dp(8).toFloat(), dp(8).toFloat(), paint)
        }
    }
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { positionCard() }
    private val drawListener = ViewTreeObserver.OnPreDrawListener { positionCard(); true }

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(false)
        card.addView(textScroll, LinearLayout.LayoutParams(-1, dp(140)))
        card.addView(LinearLayout(activity).apply {
            addView(Button(activity).apply { text = "Exit"; setOnClickListener { dialog.dismiss() } }, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(back, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(next, LinearLayout.LayoutParams(0, dp(48), 1f))
        })
        root.addView(card, FrameLayout.LayoutParams(-1, -2))
        dialog.setContentView(root)
        dialog.setOnDismissListener {
            activity.window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
            activity.window.decorView.viewTreeObserver.removeOnPreDrawListener(drawListener)
            root.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
            finished()
        }
    }

    fun show() {
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setLayout(-1, -1)
        }
        activity.window.decorView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        activity.window.decorView.viewTreeObserver.addOnPreDrawListener(drawListener)
        root.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        showStep()
    }

    fun dismiss() = dialog.dismiss()

    private fun showStep() {
        prepare(index)
        title.text = "${index + 1}/${steps.size} · ${steps[index].title}"
        body.text = steps[index].text
        back.isEnabled = index > 0
        next.text = if (index == steps.lastIndex) "Done" else "Next"
        textScroll.scrollTo(0, 0)
        root.post { positionCard(); title.announceForAccessibility("${title.text}. ${body.text}") }
    }

    private fun positionCard() {
        if (root.width == 0 || root.height == 0) return
        // Visible global bounds include rotation, scaling and parent clipping. Raw width/height
        // do not describe the on-screen shape of the rotated landscape mode selector.
        val origin = IntArray(2)
        root.getLocationOnScreen(origin)
        root.hole.setEmpty()
        for (target in listOf(steps[index].target()) + steps[index].extraTargets()) {
            val visible = Rect()
            if (!target.isShown || !target.getGlobalVisibleRect(visible)) continue
            val targetOrigin = IntArray(2)
            target.rootView.getLocationOnScreen(targetOrigin)
            visible.offset(targetOrigin[0] - origin[0], targetOrigin[1] - origin[1])
            root.hole.union(RectF(visible))
        }
        if (!root.hole.isEmpty) root.hole.inset(-dp(3).toFloat(), -dp(3).toFloat())
        if (!root.hole.intersect(0f, 0f, root.width.toFloat(), root.height.toFloat())) root.hole.setEmpty()
        val lp = card.layoutParams as FrameLayout.LayoutParams
        val landscape = root.width > root.height
        val gravity = if (landscape) Gravity.CENTER_VERTICAL or
            (if (root.hole.centerX() > root.width / 2f) Gravity.LEFT else Gravity.RIGHT)
            else if (root.hole.centerY() > root.height / 2f) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        val width = minOf(if (landscape) root.width / 2 - dp(24) else root.width - dp(24), dp(420))
        if (lp.gravity != gravity || lp.width != width) {
            lp.gravity = gravity; lp.width = width; lp.topMargin = dp(12); lp.bottomMargin = dp(12); lp.leftMargin = dp(12); lp.rightMargin = dp(12)
            card.layoutParams = lp
        }
        val textHeight = minOf(dp(150), (root.height * 0.36f).toInt())
        if (textScroll.layoutParams.height != textHeight) {
            textScroll.layoutParams = LinearLayout.LayoutParams(-1, textHeight)
        }
        root.invalidate()
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
