// Adapted runtime subset of KazumaProject/kotlin-kana-kanji-converter, MIT.
package com.kazumaproject.Louds.with_term_id

import com.kazumaproject.Louds.LOUDS
import com.kazumaproject.bitset.SuccinctBitVector
import java.io.ObjectInput

class LOUDSWithTermId : LOUDS() {
    private lateinit var termIds: IntArray
    private lateinit var leafRanks: SuccinctBitVector

    fun readDictionary(input: ObjectInput): LOUDSWithTermId {
        readTrie(input)
        termIds = input.readObject() as IntArray
        require(termIds.isNotEmpty() && termIds.size == leaves.cardinality()) {
            "Invalid Sumire reading dictionary"
        }
        leafRanks = SuccinctBitVector(leaves)
        return this
    }

    fun getTermId(nodeIndex: Int): Int {
        require(nodeIndex >= 0 && leaves[nodeIndex]) { "Unknown Sumire reading" }
        return termIds[leafRanks.rank1(nodeIndex) - 1]
    }
}
