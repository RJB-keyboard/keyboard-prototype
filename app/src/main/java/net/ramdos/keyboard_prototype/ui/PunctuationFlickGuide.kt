package net.ramdos.keyboard_prototype.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/** A visual guide only; the original key keeps receiving the entire gesture. */
class PunctuationFlickGuide(private val density: Float, private val scaledDensity: Float) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cells = listOf(
        Triple("、", 1, 1), Triple("。", 0, 1), Triple("！", 1, 0),
        Triple("？", 2, 1), Triple("…", 1, 2),
    )

    fun draw(canvas: Canvas, left: Float, top: Float, cellSize: Float, selected: String) {
        for ((symbol, column, row) in cells) {
            val rect = RectF(left + column * cellSize, top + row * cellSize,
                left + (column + 1) * cellSize, top + (row + 1) * cellSize)
            rect.inset(density, density)
            paint.style = Paint.Style.FILL
            paint.color = if (symbol == selected) Color.rgb(0, 112, 240) else Color.WHITE
            canvas.drawRoundRect(rect, 6 * density, 6 * density, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density
            paint.color = Color.rgb(174, 187, 202)
            canvas.drawRoundRect(rect, 6 * density, 6 * density, paint)
            paint.style = Paint.Style.FILL
            paint.color = if (symbol == selected) Color.WHITE else Color.rgb(23, 33, 46)
            paint.textSize = minOf(26 * scaledDensity, cellSize * 0.55f)
            paint.textAlign = Paint.Align.CENTER
            val baseline = rect.centerY() - (paint.ascent() + paint.descent()) / 2
            canvas.drawText(symbol, rect.centerX(), baseline, paint)
        }
    }
}
