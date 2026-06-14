package com.kraiapp.silenttimer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * Scroll picker in 15-minute steps (index 0..95 = 0:00..23:45).
 * Hours shown large, quarter-hours small. Reports position live during scroll
 * so a paired picker can mirror it in real time.
 */
class TimePicker @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val totalSteps = 96
    private val itemHeightDp = 44f
    private val itemHeight get() = itemHeightDp * resources.displayMetrics.density

    var isEndTime: Boolean = true

    /** Fired continuously while scrolling — fractional index (e.g. 4.3). */
    var onPositionChanged: ((Float) -> Unit)? = null
    /** Fired once the picker snaps to a final integer index (on release/fling end). */
    var onSelectionChanged: ((Int) -> Unit)? = null

    var selectedIndex: Int = 4
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

    private val paintHour = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val paintQuarter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val paintSelected = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.selected_text)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val paintSelectionBg = Paint().apply {
        color = ContextCompat.getColor(context, R.color.selected_bg)
    }

    private val colorHour get() = ContextCompat.getColor(context, R.color.text_hour)
    private val colorQuarter get() = ContextCompat.getColor(context, R.color.text_quarter)

    private fun minScroll() = -height / 2f + itemHeight / 2f
    private fun maxScroll() = (totalSteps - 1) * itemHeight - height / 2f + itemHeight / 2f
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
        selectedIndex = currentPosition().roundToInt().coerceIn(0, totalSteps - 1)
        suppressCallback = false
        invalidate()
    }

    fun setSelectedIndex(index: Int) {
        val clamped = index.coerceIn(0, totalSteps - 1)
        setPosition(clamped.toFloat())
        selectedIndex = clamped
    }

    private fun snapToNearest() {
        val nearest = currentPosition().roundToInt().coerceIn(0, totalSteps - 1)
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

        canvas.drawRect(0f, centerY - itemHeight / 2f, width.toFloat(),
            centerY + itemHeight / 2f, paintSelectionBg)

        val firstVisible = ((scrollY - height / 2f) / itemHeight).toInt().coerceAtLeast(0)
        val lastVisible = ((scrollY + height * 1.5f) / itemHeight).toInt().coerceAtMost(totalSteps - 1)

        for (i in firstVisible..lastVisible) {
            val itemCenterY = i * itemHeight - scrollY + centerY
            val isHour = (i % 4 == 0)
            val isSelected = (i == selectedIndex)
            val label = formatLabel(i)
            val paint: Paint

            when {
                isSelected -> {
                    paint = paintSelected
                    paint.textSize = if (isHour) itemHeight * 0.62f else itemHeight * 0.38f
                }
                isHour -> {
                    paint = paintHour
                    paint.color = colorHour
                    paint.textSize = itemHeight * 0.58f
                }
                else -> {
                    paint = paintQuarter
                    paint.color = colorQuarter
                    paint.textSize = itemHeight * 0.32f
                }
            }
            canvas.drawText(label, cx, itemCenterY + paint.textSize * 0.38f, paint)
        }
    }

    private fun formatLabel(index: Int): String {
        val totalMinutes = index * 15
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (minutes == 0) hours.toString() else minutes.toString()
    }
}
