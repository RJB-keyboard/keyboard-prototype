package net.ramdos.keyboard_prototype.ui

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup

/** Two compact rows: a long candidate never stretches the candidate in the other row. */
class CandidateRowsView(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {
    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widths = intArrayOf(0, 0)
        var rowHeight = (48 * resources.displayMetrics.density).toInt()
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            child.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            widths[index % 2] += child.measuredWidth
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            child.measure(
                MeasureSpec.makeMeasureSpec(child.measuredWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(
            resolveSize(widths.max(), widthMeasureSpec),
            resolveSize(rowHeight * 2, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val offsets = intArrayOf(0, 0)
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val row = index % 2
            val y = row * child.measuredHeight
            child.layout(offsets[row], y, offsets[row] + child.measuredWidth, y + child.measuredHeight)
            offsets[row] += child.measuredWidth
        }
    }
}
