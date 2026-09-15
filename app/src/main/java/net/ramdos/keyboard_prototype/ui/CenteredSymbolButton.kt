package net.ramdos.keyboard_prototype.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatButton

/** Center the visible symbol, rather than the font's baseline and invisible spacing. */
class CenteredSymbolButton(context: Context, attrs: AttributeSet? = null) : AppCompatButton(context, attrs) {
    private val symbolBounds = Rect()

    override fun onDraw(canvas: Canvas) {
        val symbol = text.toString()
        if (symbol !in setOf("␣", "↵", "⌫")) {
            super.onDraw(canvas)
            return
        }
        paint.color = currentTextColor
        paint.drawableState = drawableState
        paint.getTextBounds(symbol, 0, symbol.length, symbolBounds)
        canvas.drawText(symbol, width / 2f - symbolBounds.exactCenterX(),
            height / 2f - symbolBounds.exactCenterY(), paint)
    }
}
