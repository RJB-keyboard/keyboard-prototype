package net.ramdos.keyboard_prototype.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.TextView
import net.ramdos.keyboard_prototype.engine.GlideTrace
import net.ramdos.keyboard_prototype.engine.KanaKey
import net.ramdos.keyboard_prototype.engine.TracePoint
import kotlin.math.hypot
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
    private val trailLifetimeMillis = 450L
    private val trailRadius = 2.5f * resources.displayMetrics.density
    private var firstVisiblePoint = 0
    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 37, 99, 235)
        style = Paint.Style.FILL
    }
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var startedAt = 0L
    private var highlightedKey: KanaKey? = null
    private val keyViews = GojuonLayout.keys.associateWith { key ->
        TextView(context).apply {
            text = key.kana
            textSize = 20f
            gravity = Gravity.CENTER
            includeFontPadding = false
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
    }

    private fun highlight(key: KanaKey?) {
        highlightedKey?.let { (keyViews.getValue(it).background as GradientDrawable).setColor(Color.WHITE) }
        key?.let { (keyViews.getValue(it).background as GradientDrawable).setColor(Color.rgb(191, 219, 254)) }
        highlightedKey = key
    }

    private fun submitKeyTap(key: KanaKey) {
        clearTrace()
        onGestureStarted?.invoke()
        startedAt = SystemClock.uptimeMillis()
        points.add(TracePoint((key.left + key.right) / 2, (key.top + key.bottom) / 2, 0))
        highlight(key)
        invalidate()
        onTraceCompleted?.invoke(GlideTrace(points.toList(), GojuonLayout.keys))
    }

    fun clearTrace() {
        activePointerId = MotionEvent.INVALID_POINTER_ID
        parent?.requestDisallowInterceptTouchEvent(false)
        points.clear()
        firstVisiblePoint = 0
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
        drawTrail(canvas, SystemClock.uptimeMillis())
    }

    private fun drawTrail(canvas: Canvas, now: Long) {
        path.rewind()
        val cutoff = now - startedAt - trailLifetimeMillis
        // Retain the complete trace for decoding; only advance the rendering window.
        while (firstVisiblePoint < points.size &&
            points[firstVisiblePoint].elapsedMillis <= cutoff) {
            firstVisiblePoint++
        }
        if (firstVisiblePoint == points.size) return

        var previousX = 0f
        var previousY = 0f
        var previousRadius = 0f
        val startIndex = maxOf(0, firstVisiblePoint - 1)
        for (index in startIndex until points.size) {
            val point = points[index]
            var x = point.x * width
            var y = point.y * height
            if (index < firstVisiblePoint) {
                // Clip the crossing segment at the cutoff so its tail retreats smoothly.
                val next = points[index + 1]
                val fraction = (cutoff - point.elapsedMillis).toFloat() /
                    (next.elapsedMillis - point.elapsedMillis)
                x += (next.x * width - x) * fraction
                y += (next.y * height - y) * fraction
            }
            val radius = trailRadius *
                ((point.elapsedMillis - cutoff).toFloat() / trailLifetimeMillis).coerceIn(0f, 1f)
            if (index > startIndex) {
                val dx = x - previousX
                val dy = y - previousY
                val length = hypot(dx, dy)
                if (length > 0f) {
                    val nx = dy / length
                    val ny = -dx / length
                    path.moveTo(previousX + nx * previousRadius, previousY + ny * previousRadius)
                    path.lineTo(x + nx * radius, y + ny * radius)
                    path.lineTo(x - nx * radius, y - ny * radius)
                    path.lineTo(previousX - nx * previousRadius, previousY - ny * previousRadius)
                    path.close()
                }
            }
            if (radius > 0f) path.addCircle(x, y, radius, Path.Direction.CW)
            previousX = x
            previousY = y
            previousRadius = radius
        }
        // One filled path keeps joins and crossings at a consistent opacity.
        canvas.drawPath(path, tracePaint)
        postInvalidateOnAnimation()
    }
}
