// Adapted runtime subset of KazumaProject/kotlin-kana-kanji-converter, MIT.
package com.kazumaproject.mozc

import java.io.DataInputStream
import java.io.InputStream

class ConnectionMatrix(private val size: Int, private val costs: ShortArray) {
    fun getCost(previousRightId: Int, nextLeftId: Int): Int {
        require(previousRightId in 0 until size && nextLeftId in 0 until size)
        return costs[previousRightId * size + nextLeftId].toInt()
    }

    companion object {
        fun read(input: InputStream, byteCount: Long): ConnectionMatrix {
            require(byteCount > 0 && byteCount % 2L == 0L && byteCount <= 64L * 1024 * 1024)
            val shortCount = (byteCount / 2).toInt()
            val size = kotlin.math.sqrt(shortCount.toDouble()).toInt()
            require(size * size == shortCount) { "Invalid Sumire connection matrix dimensions" }
            val reader = DataInputStream(input)
            val costs = ShortArray(shortCount) { reader.readShort() }
            check(reader.read() == -1) { "Unexpected Sumire connection matrix trailing data" }
            return ConnectionMatrix(size, costs)
        }
    }
}
