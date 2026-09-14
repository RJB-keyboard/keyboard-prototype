// Adapted from KazumaProject/kotlin-kana-kanji-converter Extenstions.kt, MIT.
package com.kazumaproject

fun String.hiraToKata(): String = map {
    if (it in '\u3041'..'\u3096') it + 0x60 else it
}.joinToString("")
