package net.ramdos.keyboard_prototype.engine.conversion

import com.kazumaproject.graph.Node
import com.kazumaproject.mozc.ConnectionMatrix
import com.kazumaproject.viterbi.FindPath
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversionPathTest {
    @Test
    fun negativeConnectionCostsStillProduceCorrectNBestOrder() {
        // Cheap prefix A totals 10; expensive prefix B is followed by a -100 edge and totals -70.
        val matrix = ConnectionMatrix(3, ShortArray(9).apply { this[2 * 3] = -100 })
        val bos = node("BOS", 0, 0, 0)
        val cheap = node("A", 0, 1, 10, right = 1)
        val expensive = node("B", 0, 1, 30, right = 2)
        val eos = node("EOS", 1, 0, 0)
        val graph = listOf(mutableListOf(mutableListOf(bos)),
            mutableListOf(mutableListOf(cheap, expensive)), mutableListOf(mutableListOf(eos)))
        assertEquals(listOf("B", "A"), FindPath().backwardAStar(graph, 1, matrix, 2))
        assertEquals(listOf(FindPath.ScoredPath("B", -70L, 30L, -100L), FindPath.ScoredPath("A", 10L, 10L, 0L)),
            FindPath().backwardAStarCandidates(graph, 1, matrix, 2))
    }

    @Test
    fun duplicateSurfacesKeepCheapestCompleteCostIncludingBosAndEos() {
        val matrix = ConnectionMatrix(2, shortArrayOf(7, 0, -5, 0))
        val bos = node("BOS", 0, 0, 0)
        val cheap = node("同じ", 0, 1, 10, right = 1)
        val expensive = node("同じ", 0, 1, 30)
        val alternative = node("別", 0, 1, 40)
        val eos = node("EOS", 1, 0, 0)
        val graph = listOf(mutableListOf(mutableListOf(bos)),
            mutableListOf(mutableListOf(expensive, cheap, alternative)), mutableListOf(mutableListOf(eos)))
        assertEquals(listOf(FindPath.ScoredPath("同じ", 12L, 10L, 2L), FindPath.ScoredPath("別", 54L, 40L, 14L)),
            FindPath().backwardAStarCandidates(graph, 1, matrix, 2))
    }

    private fun node(text: String, start: Int, length: Short, cost: Int, right: Short = 0): Node =
        Node(l = 0, r = right, score = cost, f = cost, tango = text,
            len = length, sPos = start, wcost = cost)
}
