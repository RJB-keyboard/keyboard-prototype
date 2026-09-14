package net.ramdos.keyboard_prototype.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.TextView
import net.ramdos.keyboard_prototype.engine.GlideTrace
import net.ramdos.keyboard_prototype.engine.KanaKey
import net.ramdos.keyboard_prototype.engine.TracePoint
import kotlin.math.roundToInt

/** Collects touches and renders the board; never generates or commits candidates. */
class GojuonBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {
    var onGestureStarted: (() -> Unit)? = null
    var onGestureCancelled: (() -> Unit)? = null
    var onTraceCompleted: ((GlideTrace) -> Unit)? = null

    private val points = mutableListOf<TracePoint>()
    private val path = Path()
    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 37, 99, 235)
        style = Paint.Style.STROKE
        strokeWidth = 3 * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var startedAt = 0L
    private var highlightedKey: KanaKey? = null
    private val keyViews = GojuonLayout.keys.associateWith { key ->
        TextView(context).apply {
            text = key.kana
            textSize = 20f
            gravity = Gravity.CENTER
            isFocusable = true
            setTextColor(Color.rgb(23, 33, 46))
            background = GradientDrawable().apply {
                cornerRadius = 5 * resources.displayMetrics.density
                setColor(Color.WHITE)
            }
            // Accessibility and hardware-keyboard clicks use the same trace contract.
            setOnClickListener { submitKeyTap(key) }
            addView(this)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(suggestedMinimumHeight, heightMeasureSpec),
        )
        val gap = (2 * resources.displayMetrics.density).roundToInt()
        keyViews.forEach { (key, view) ->
            val keyWidth = ((key.right * measuredWidth).roundToInt() -
                (key.left * measuredWidth).roundToInt() - gap * 2).coerceAtLeast(0)
            val keyHeight = ((key.bottom * measuredHeight).roundToInt() -
                (key.top * measuredHeight).roundToInt() - gap * 2).coerceAtLeast(0)
            view.measure(
                MeasureSpec.makeMeasureSpec(keyWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(keyHeight, MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val gap = (2 * resources.displayMetrics.density).roundToInt()
        keyViews.forEach { (key, view) ->
            val x = (key.left * width).roundToInt() + gap
            val y = (key.top * height).roundToInt() + gap
            view.layout(x, y, x + view.measuredWidth, y + view.measuredHeight)
        }
    }

    // The whole board owns a gesture, so crossing keys never interrupts it.
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (width == 0 || height == 0 ||
                    GojuonLayout.keyAt(event.x / width, event.y / height) == null
                ) return true
                clearTrace()
                activePointerId = event.getPointerId(0)
                startedAt = event.eventTime
                parent?.requestDisallowInterceptTouchEvent(true)
                onGestureStarted?.invoke()
                record(event, 0)
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) return true
                val index = event.findPointerIndex(activePointerId)
                if (index == -1) {
                    cancelGesture()
                    return true
                }
                record(event, index)
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    val last = points.last()
                    if (last.x < 0f || last.x >= 1f || last.y < 0f || last.y >= 1f) {
                        cancelGesture()
                    } else {
                        activePointerId = MotionEvent.INVALID_POINTER_ID
                        parent?.requestDisallowInterceptTouchEvent(false)
                        performClick()
                        onTraceCompleted?.invoke(GlideTrace(points.toList(), GojuonLayout.keys))
                    }
                }
            }
            // Cancel multi-touch instead of mixing trajectories from two fingers.
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> cancelGesture()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun record(event: MotionEvent, pointerIndex: Int) {
        for (historyIndex in 0 until event.historySize) {
            append(
                event.getHistoricalX(pointerIndex, historyIndex),
                event.getHistoricalY(pointerIndex, historyIndex),
                event.getHistoricalEventTime(historyIndex),
            )
        }
        append(event.getX(pointerIndex), event.getY(pointerIndex), event.eventTime)
        val last = points.last()
        highlight(GojuonLayout.keyAt(last.x, last.y))
        invalidate()
    }

    private fun append(x: Float, y: Float, time: Long) {
        points.add(TracePoint(x / width, y / height, time - startedAt))
        if (points.size == 1) path.moveTo(x, y) else path.lineTo(x, y)
    }

    private fun highlight(key: KanaKey?) {
        highlightedKey?.let { (keyViews.getValue(it).background as GradientDrawable).setColor(Color.WHITE) }
        key?.let { (keyViews.getValue(it).background as GradientDrawable).setColor(Color.rgb(191, 219, 254)) }
        highlightedKey = key
    }

    private fun submitKeyTap(key: KanaKey) {
        clearTrace()
        onGestureStarted?.invoke()
        points.add(TracePoint((key.left + key.right) / 2, (key.top + key.bottom) / 2, 0))
        highlight(key)
        invalidate()
        onTraceCompleted?.invoke(GlideTrace(points.toList(), GojuonLayout.keys))
    }

    fun clearTrace() {
        activePointerId = MotionEvent.INVALID_POINTER_ID
        parent?.requestDisallowInterceptTouchEvent(false)
        points.clear()
        path.reset()
        highlight(null)
        invalidate()
    }

    private fun cancelGesture() {
        clearTrace()
        onGestureCancelled?.invoke()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cancelGesture()
    }

    override fun onDetachedFromWindow() {
        cancelGesture()
        super.onDetachedFromWindow()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        canvas.drawPath(path, tracePaint)
        points.lastOrNull()?.let {
            canvas.drawCircle(it.x * width, it.y * height, 5 * resources.displayMetrics.density, tracePaint)
        }
    }
}
