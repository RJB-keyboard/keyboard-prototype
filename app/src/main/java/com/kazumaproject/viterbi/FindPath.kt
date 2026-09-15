// Adapted from KazumaProject/kotlin-kana-kanji-converter FindPath.kt, MIT.
// Dictionary word/POS costs and lattice semantics are preserved. See docs/conversion.md.
package com.kazumaproject.viterbi

import com.kazumaproject.graph.Node
import com.kazumaproject.mozc.ConnectionMatrix
import java.util.IdentityHashMap
import java.util.TreeSet

class FindPath {
    /** Complete path cost, including BOS/EOS connections, in raw dictionary units. */
    data class ScoredPath(val text: String, val cost: Long, val wordCost: Long, val connectionCost: Long)

    private data class State(
        val node: Node,
        val text: String,
        val cost: Long,
        val wordCost: Long,
        val estimate: Long,
        val sequence: Long,
    )

    /**
     * N-best dictionary paths with an exact reverse Viterbi heuristic.
     * Mozc edges may have negative costs: ordering partial paths by cost alone
     * (the upstream uniform-cost implementation) is not a valid A* heuristic.
     * This acyclic lattice supports a reverse dynamic-programming pass instead.
     */
    fun backwardAStar(
        graph: List<MutableList<MutableList<Node>>>,
        length: Int,
        connectionMatrix: ConnectionMatrix,
        n: Int,
    ): List<String> = backwardAStarCandidates(graph, length, connectionMatrix, n).map { it.text }

    fun backwardAStarCandidates(
        graph: List<MutableList<MutableList<Node>>>,
        length: Int,
        connectionMatrix: ConnectionMatrix,
        n: Int,
    ): List<ScoredPath> {
        if (n <= 0) return emptyList()
        val outgoing = List(length + 1) { mutableListOf<Node>() }
        for (end in 1..length) {
            graph[end].forEach { group -> group.forEach { outgoing[it.sPos].add(it) } }
        }
        val eos = graph[length + 1].flatten().single()
        outgoing[length].add(eos)
        val bos = graph[0].flatten().single()
        val remaining = IdentityHashMap<Node, Long>()
        remaining[eos] = 0L
        fun bestRemaining(node: Node, end: Int): Long = outgoing[end].minOfOrNull { next ->
            val suffix = remaining[next] ?: UNREACHABLE
            if (suffix == UNREACHABLE) UNREACHABLE else suffix + next.wcost +
                connectionMatrix.getCost(node.r.toInt(), next.l.toInt())
        } ?: UNREACHABLE
        for (position in length - 1 downTo 0) {
            for (node in outgoing[position]) {
                remaining[node] = bestRemaining(node, node.sPos + node.len)
            }
        }
        remaining[bos] = bestRemaining(bos, 0)
        if (remaining[bos] == UNREACHABLE) return emptyList()

        val queue = TreeSet(compareBy<State> { it.estimate }.thenBy { it.sequence })
        var sequence = 0L
        queue += State(bos, "", 0L, 0L, remaining.getValue(bos), sequence++)
        val result = linkedMapOf<String, ScoredPath>()
        var expanded = 0
        while (queue.isNotEmpty() && result.size < n && expanded < MAX_EXPANSIONS) {
            check(!Thread.currentThread().isInterrupted) { "Conversion interrupted" }
            val state = queue.pollFirst()!!
            if (state.node === eos) {
                // A* visits complete paths by cost; keep the cheapest path for each surface.
                result.putIfAbsent(state.text, ScoredPath(state.text, state.cost, state.wordCost, state.cost - state.wordCost))
                continue
            }
            expanded++
            val end = if (state.node === bos) 0 else state.node.sPos + state.node.len
            for (next in outgoing[end]) {
                val suffix = remaining[next] ?: continue
                if (suffix == UNREACHABLE) continue
                val cost = state.cost + next.wcost +
                    connectionMatrix.getCost(state.node.r.toInt(), next.l.toInt())
                queue += State(next, if (next === eos) state.text else state.text + next.tango,
                    cost, state.wordCost + next.wcost, cost + suffix, sequence++)
                // A bounded search is important for very ambiguous readings on an IME worker.
                if (queue.size > MAX_QUEUED_PATHS) queue.pollLast()
            }
        }
        return result.values.toList()
    }

    companion object {
        private const val UNREACHABLE = Long.MAX_VALUE / 4
        private const val MAX_EXPANSIONS = 20000
        private const val MAX_QUEUED_PATHS = 10000
    }
}
