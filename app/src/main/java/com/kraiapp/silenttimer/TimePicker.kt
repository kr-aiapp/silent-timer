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
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Custom scroll picker that shows time values (hours as large text, quarter-hours as small text).
 * Items are in 15-minute steps. isEndTime=true formats as clock (HH:MM), false as duration (H or :MM).
 */
class TimePicker @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // Each step = 15 minutes. Range: 0..95 (24h * 4 = 96 steps, index 0 = 0:00)
    private val totalSteps = 96
    private val itemHeightDp = 44f
    private val itemHeight get() = itemHeightDp * resources.displayMetrics.density

    var isEndTime: Boolean = true
    var onSelectionChanged: ((Int) -> Unit)? = null

    // selectedIndex in 0..95 (steps of 15 min)
    var selectedIndex: Int = 4  // default: 1 hour
        private set

    private var scrollY = 0f  // pixels scrolled (top of list)
    private val scroller = OverScroller(context)
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scrollY += dy
            clampScroll()
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(0, scrollY.toInt(), 0, (-vy).toInt(),
                0, 0, 0, ((totalSteps - 1) * itemHeight).toInt())
            invalidate()
            return true
        }

        override fun onDown(e: MotionEvent): Boolean = true
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

    fun setSelectedIndex(index: Int, smooth: Boolean = false) {
        val clamped = index.coerceIn(0, totalSteps - 1)
        if (smooth) {
            scroller.startScroll(0, scrollY.toInt(), 0,
                (clamped * itemHeight - scrollY - height / 2f + itemHeight / 2f).toInt(), 300)
        } else {
            scrollY = clamped * itemHeight - height / 2f + itemHeight / 2f
            clampScroll()
        }
        selectedIndex = clamped
        invalidate()
    }

    private fun clampScroll() {
        val maxScroll = (totalSteps - 1) * itemHeight - height / 2f + itemHeight / 2f
        val minScroll = -height / 2f + itemHeight / 2f
        scrollY = scrollY.coerceIn(minScroll, maxScroll)
    }

    private fun snapToNearest() {
        val centerY = scrollY + height / 2f - itemHeight / 2f
        val nearest = (centerY / itemHeight).roundToInt().coerceIn(0, totalSteps - 1)
        val targetScroll = nearest * itemHeight - height / 2f + itemHeight / 2f
        scroller.startScroll(0, scrollY.toInt(), 0, (targetScroll - scrollY).toInt(), 200)
        selectedIndex = nearest
        onSelectionChanged?.invoke(nearest)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestureDetector.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            if (!scroller.isFinished) {
                // let fling finish then snap
            } else {
                snapToNearest()
            }
        }
        return handled || super.onTouchEvent(event)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollY = scroller.currY.toFloat()
            clampScroll()
            if (scroller.isFinished) {
                snapToNearest()
            }
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val centerY = height / 2f

        // Selection highlight
        canvas.drawRect(0f, centerY - itemHeight / 2f, width.toFloat(),
            centerY + itemHeight / 2f, paintSelectionBg)

        val firstVisible = ((scrollY - height / 2f) / itemHeight).toInt().coerceAtLeast(0)
        val lastVisible = ((scrollY + height * 1.5f) / itemHeight).toInt().coerceAtMost(totalSteps - 1)

        for (i in firstVisible..lastVisible) {
            val itemCenterY = i * itemHeight - scrollY + centerY
            val isHour = (i % 4 == 0)
            val isSelected = (i == selectedIndex)

            val label = formatLabel(i)
            val textSize: Float
            val paint: Paint

            when {
                isSelected -> {
                    textSize = if (isHour) itemHeight * 0.62f else itemHeight * 0.38f
                    paint = paintSelected
                    paint.textSize = textSize
                }
                isHour -> {
                    textSize = itemHeight * 0.58f
                    paint = paintHour
                    paint.color = colorHour
                    paint.textSize = textSize
                }
                else -> {
                    textSize = itemHeight * 0.32f
                    paint = paintQuarter
                    paint.color = colorQuarter
                    paint.textSize = textSize
                }
            }

            val textY = itemCenterY + paint.textSize * 0.38f
            canvas.drawText(label, cx, textY, paint)
        }
    }

    private fun formatLabel(index: Int): String {
        val totalMinutes = index * 15
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (isEndTime) {
            // Show as clock time HH:MM — but only show "HH" for full hours, "MM" for quarters
            if (minutes == 0) String.format("%d", hours)
            else String.format("%d", minutes)
        } else {
            // Duration: show hours as "H", quarters as "MM"
            if (minutes == 0) String.format("%d", hours)
            else String.format("%d", minutes)
        }
    }
}
