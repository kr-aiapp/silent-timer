package com.kraiapp.silenttimer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Scroll picker in 5-minute steps (index 0..287 = 0:00..23:55).
 * The item nearest the center is emphasised (larger + bold) live while scrolling,
 * and the picker reports its fractional position continuously so a paired picker
 * can mirror it in real time.
 */
class TimePicker @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val STEP_MINUTES = 5
        const val TOTAL_STEPS = 24 * 60 / STEP_MINUTES   // 288
    }

    // Each 5-minute step is 1/3 of the gap between 15-minute numbers, so the numbers
    // sit close together (like the old every-5-min layout) while scrolling still
    // snaps every 5 minutes.
    private val itemHeightDp = 14f
    private val itemHeight get() = itemHeightDp * resources.displayMetrics.density

    var isEndTime: Boolean = true

    /** Fired continuously while scrolling — fractional index (e.g. 4.3). */
    var onPositionChanged: ((Float) -> Unit)? = null
    /** Fired once the picker snaps to a final integer index (on release / fling end). */
    var onSelectionChanged: ((Int) -> Unit)? = null

    var selectedIndex: Int = 0
        private set

    private var scrollY = 0f
    private var suppressCallback = false
    private val scroller = OverScroller(context)

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scrollY += dy
            clampScroll()
            firePosition()
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(0, scrollY.toInt(), 0, (-vy).toInt(),
                0, 0, minScrollInt(), maxScrollInt())
            invalidate()
            return true
        }

        override fun onDown(e: MotionEvent): Boolean {
            if (!scroller.isFinished) scroller.abortAnimation()
            return true
        }
    })

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val typefaceBold = Typeface.DEFAULT_BOLD
    private val typefaceNormal = Typeface.DEFAULT

    private val boxFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.pick_box_fill)
    }
    private val boxStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.pick_box_stroke)
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val boxRect = RectF()

    private val colorActive get() = ContextCompat.getColor(context, R.color.selected_bg)
    private val colorHour get() = ContextCompat.getColor(context, R.color.text_hour)
    private val colorQuarter get() = ContextCompat.getColor(context, R.color.text_quarter)

    private fun minScroll() = -height / 2f + itemHeight / 2f
    private fun maxScroll() = (TOTAL_STEPS - 1) * itemHeight - height / 2f + itemHeight / 2f
    private fun minScrollInt() = minScroll().toInt()
    private fun maxScrollInt() = maxScroll().toInt()

    private fun clampScroll() {
        scrollY = scrollY.coerceIn(minScroll(), maxScroll())
    }

    /** Current fractional index based on scroll position. */
    private fun currentPosition(): Float = (scrollY + height / 2f - itemHeight / 2f) / itemHeight

    private fun firePosition() {
        if (!suppressCallback) onPositionChanged?.invoke(currentPosition())
    }

    /** Mirror to a fractional index without firing callbacks (used by the paired picker). */
    fun setPosition(fractionalIndex: Float) {
        suppressCallback = true
        scrollY = fractionalIndex * itemHeight - height / 2f + itemHeight / 2f
        clampScroll()
        selectedIndex = currentPosition().roundToInt().coerceIn(0, TOTAL_STEPS - 1)
        suppressCallback = false
        invalidate()
    }

    fun setSelectedIndex(index: Int) {
        val clamped = index.coerceIn(0, TOTAL_STEPS - 1)
        setPosition(clamped.toFloat())
        selectedIndex = clamped
    }

    private fun snapToNearest() {
        val nearest = currentPosition().roundToInt().coerceIn(0, TOTAL_STEPS - 1)
        val targetScroll = nearest * itemHeight - height / 2f + itemHeight / 2f
        scroller.startScroll(0, scrollY.toInt(), 0, (targetScroll - scrollY).toInt(), 200)
        selectedIndex = nearest
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestureDetector.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            if (scroller.isFinished) {
                snapToNearest()
                onSelectionChanged?.invoke(selectedIndex)
            }
        }
        return handled || super.onTouchEvent(event)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollY = scroller.currY.toFloat()
            clampScroll()
            firePosition()
            if (scroller.isFinished) {
                snapToNearest()
                onSelectionChanged?.invoke(selectedIndex)
            }
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        setSelectedIndex(selectedIndex)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val centerY = height / 2f
        val ih = itemHeight

        // The item closest to the center right now (follows the finger live).
        val activePos = currentPosition()

        // Decorated rounded selection box in the center band (≈ one 15-min gap tall).
        val density = resources.displayMetrics.density
        val boxMarginX = 10f * density
        val boxHalfH = ih * 1.5f
        val radius = 11f * density
        boxRect.set(boxMarginX, centerY - boxHalfH, width - boxMarginX, centerY + boxHalfH)
        canvas.drawRoundRect(boxRect, radius, radius, boxFill)
        canvas.drawRoundRect(boxRect, radius, radius, boxStroke)

        val firstVisible = ((scrollY - height / 2f) / ih).toInt().coerceAtLeast(0)
        val lastVisible = ((scrollY + height * 1.5f) / ih).toInt().coerceAtMost(TOTAL_STEPS - 1)

        for (i in firstVisible..lastVisible) {
            val itemCenterY = i * ih - scrollY + centerY

            val totalMin = i * STEP_MINUTES
            val minutes = totalMin % 60
            val isHour = (minutes == 0)
            val isLabeled = (minutes % 15 == 0)   // hours + :15 / :30 / :45 get a number

            // How "active" is this item: 1.0 at center, fading out toward the next mark.
            val distance = abs(i - activePos)
            val activeness = (1f - distance / 1.5f).coerceIn(0f, 1f)

            // Numbers only at 15-minute marks; the 5-minute snap stops in between
            // are left blank (no dots) so scrolling reads as hours passing by.
            // Text sizes are in dp (independent of the small step height).
            if (isLabeled) {
                val baseSize = if (isHour) 19f * density else 13f * density
                val activeSize = if (isHour) 26f * density else 19f * density
                paint.textSize = baseSize + (activeSize - baseSize) * activeness
                paint.typeface = if (isHour || activeness > 0.5f) typefaceBold else typefaceNormal
                val baseColor = if (isHour) colorHour else colorQuarter
                paint.color = blendColor(baseColor, colorActive, activeness)
                canvas.drawText(formatLabel(i), cx, itemCenterY + paint.textSize * 0.36f, paint)
            }
        }
    }

    private fun blendColor(from: Int, to: Int, t: Float): Int {
        val a = ((from ushr 24 and 0xFF) + (((to ushr 24 and 0xFF) - (from ushr 24 and 0xFF)) * t)).toInt()
        val r = ((from ushr 16 and 0xFF) + (((to ushr 16 and 0xFF) - (from ushr 16 and 0xFF)) * t)).toInt()
        val g = ((from ushr 8 and 0xFF) + (((to ushr 8 and 0xFF) - (from ushr 8 and 0xFF)) * t)).toInt()
        val b = ((from and 0xFF) + (((to and 0xFF) - (from and 0xFF)) * t)).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun formatLabel(index: Int): String {
        val totalMin = index * STEP_MINUTES
        val hours = totalMin / 60
        val minutes = totalMin % 60
        return if (minutes == 0) hours.toString() else minutes.toString()
    }
}
