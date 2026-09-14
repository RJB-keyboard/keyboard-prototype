package net.ramdos.keyboard_prototype.ui

import net.ramdos.keyboard_prototype.engine.KanaKey

/** Vertical columns read from right to left. Blank cells are not keys. */
object GojuonLayout {
    private val columns = listOf(
        listOf("わ", null, "を", null, "ん"),
        listOf("ら", "り", "る", "れ", "ろ"),
        listOf("や", "っ", "ゆ", "ー", "よ"),
        listOf("ま", "み", "む", "め", "も"),
        listOf("は", "ひ", "ふ", "へ", "ほ"),
        listOf("な", "に", "ぬ", "ね", "の"),
        listOf("た", "ち", "つ", "て", "と"),
        listOf("さ", "し", "す", "せ", "そ"),
        listOf("か", "き", "く", "け", "こ"),
        listOf("あ", "い", "う", "え", "お"),
    )

    val keys: List<KanaKey> = columns.flatMapIndexed { column, kanaColumn ->
        kanaColumn.mapIndexedNotNull { row, kana ->
            kana?.let {
                KanaKey(it, column.toFloat() / columns.size, row / 5f, (column + 1).toFloat() / columns.size, (row + 1) / 5f)
            }
        }
    }

    fun keyAt(x: Float, y: Float): KanaKey? = keys.firstOrNull { it.contains(x, y) }
}
