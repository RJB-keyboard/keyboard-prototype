// Adapted runtime subset of KazumaProject/kotlin-kana-kanji-converter, MIT.
package com.kazumaproject.dictionary

import com.kazumaproject.bitset.SuccinctBitVector
import java.io.ObjectInput
import java.util.BitSet

class TokenArray {
    private lateinit var posIndices: ShortArray
    private lateinit var wordCosts: ShortArray
    private lateinit var nodeIds: IntArray
    private lateinit var postings: SuccinctBitVector
    lateinit var leftIds: ShortArray
        private set
    lateinit var rightIds: ShortArray
        private set

    data class TokenEntry(val posTableIndex: Short, val wordCost: Short, val nodeId: Int)

    fun readExternalNotCompress(input: ObjectInput) {
        posIndices = input.readObject() as ShortArray
        wordCosts = input.readObject() as ShortArray
        nodeIds = input.readObject() as IntArray
        val bits = input.readObject() as BitSet
        require(posIndices.isNotEmpty() && posIndices.size == wordCosts.size &&
            posIndices.size == nodeIds.size && bits.cardinality() == nodeIds.size) {
            "Invalid Sumire token dictionary"
        }
        postings = SuccinctBitVector(bits)
    }

    fun readPOSTable(input: ObjectInput) {
        leftIds = input.readObject() as ShortArray
        rightIds = input.readObject() as ShortArray
        require(leftIds.isNotEmpty() && leftIds.size == rightIds.size &&
            posIndices.all { it.toInt() in leftIds.indices }) { "Invalid Sumire POS table" }
    }

    fun getListDictionaryByYomiTermId(termId: Int): List<TokenEntry> {
        val start = postings.rank1(postings.select0(termId))
        val end = postings.rank1(postings.select0(termId + 1))
        return (start until end).map { TokenEntry(posIndices[it], wordCosts[it], nodeIds[it]) }
    }
}
