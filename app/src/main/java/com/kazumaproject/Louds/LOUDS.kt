// Adapted runtime subset of KazumaProject/kotlin-kana-kanji-converter, MIT.
// See docs/conversion.md for upstream revision and local changes.
package com.kazumaproject.Louds

import com.kazumaproject.bitset.SuccinctBitVector
import java.io.ObjectInput
import java.util.BitSet

open class LOUDS {
    protected lateinit var lbs: BitSet
    protected lateinit var leaves: BitSet
    protected lateinit var labels: CharArray
    protected lateinit var succinct: SuccinctBitVector

    protected fun readTrie(input: ObjectInput) {
        lbs = input.readObject() as BitSet
        leaves = input.readObject() as BitSet
        labels = input.readObject() as CharArray
        require(labels.size >= 2 && lbs.cardinality() == labels.size - 1) {
            "Invalid Sumire LOUDS dictionary"
        }
        succinct = SuccinctBitVector(lbs)
    }

    fun readExternalNotCompress(input: ObjectInput): LOUDS {
        readTrie(input)
        return this
    }

    private fun firstChild(pos: Int): Int {
        val y = succinct.select0(succinct.rank1(pos)) + 1
        return if (y <= 0 || !lbs[y]) -1 else y
    }

    private fun traverse(pos: Int, c: Char): Int {
        var child = firstChild(pos)
        if (child == -1) return -1
        while (lbs[child]) {
            if (c == labels[succinct.rank1(child)]) return child
            child++
        }
        return -1
    }

    /** Advance a known trie prefix without allocating or re-reading its characters. */
    fun advancePrefix(nodeIndex: Int, letter: Char): Int {
        require(nodeIndex >= 0 && (nodeIndex == 0 || lbs[nodeIndex]))
        return traverse(nodeIndex, letter)
    }

    /** A valid prefix is not necessarily a complete dictionary reading. */
    fun isTerminal(nodeIndex: Int): Boolean = nodeIndex >= 0 && leaves[nodeIndex]

    fun hasContinuation(nodeIndex: Int): Boolean = nodeIndex >= 0 && firstChild(nodeIndex) >= 0

    fun commonPrefixSearch(str: String): List<String> {
        val result = mutableListOf<String>()
        var position = 0
        for (index in str.indices) {
            position = traverse(position, str[index])
            // A failed prefix must terminate traversal; resuming could invent a reading.
            if (position < 0) break
            if (leaves[position]) result += str.substring(0, index + 1)
        }
        return result
    }

    fun getNodeIndex(str: String): Int {
        var position = 0
        for (letter in str) {
            position = traverse(position, letter)
            if (position < 0) return -1
        }
        return position
    }

    fun getLetter(nodeIndex: Int): String {
        require(nodeIndex >= 0) { "Invalid Sumire dictionary node" }
        val result = StringBuilder()
        var position = nodeIndex
        while (position != 0) {
            result.append(labels[succinct.rank1(position)])
            position = succinct.select1(succinct.rank0(position))
            check(position >= 0) { "Invalid Sumire dictionary parent" }
        }
        return result.reverse().toString()
    }
}
