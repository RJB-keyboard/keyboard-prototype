package net.ramdos.keyboard_prototype.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.util.AttributeSet
import android.widget.Button

/** Keep normal Button rendering, but optically center the low-baseline action symbols. */
// InputMethodService uses the platform IME theme, not an AppCompat activity theme.
@SuppressLint("AppCompatCustomView")
class RaisedSymbolButton(context: Context, attrs: AttributeSet? = null) : Button(context, attrs) {
    private val glyphBounds = Rect()

    override fun onDraw(canvas: Canvas) {
        val symbol = text.toString()
        if (symbol != "␣" && symbol != "↵") {
            super.onDraw(canvas)
            return
        }
        paint.getTextBounds(symbol, 0, symbol.length, glyphBounds)
        val targetCenter = height / 2f + if (symbol == "␣") resources.displayMetrics.density else 0f
        val offset = targetCenter - (baseline + glyphBounds.exactCenterY() - scrollY)
        val checkpoint = canvas.save()
        canvas.translate(0f, offset)
        super.onDraw(canvas)
        canvas.restoreToCount(checkpoint)
    }
}
