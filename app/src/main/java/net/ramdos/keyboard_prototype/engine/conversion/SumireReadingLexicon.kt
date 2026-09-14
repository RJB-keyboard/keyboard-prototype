package net.ramdos.keyboard_prototype.engine.conversion

import com.kazumaproject.Louds.with_term_id.LOUDSWithTermId
import com.kazumaproject.dictionary.TokenArray
import com.kazumaproject.mozc.ConnectionMatrix
import net.ramdos.keyboard_prototype.engine.LexiconScore
import net.ramdos.keyboard_prototype.engine.ReadingLexicon
import net.ramdos.keyboard_prototype.engine.ReadingLexiconSession
import java.util.concurrent.CancellationException

/** Tunable dictionary energies, in the same raw units as Sumire's word/POS costs. */
data class SumireLexiconConfig(
    val costScale: Double = 1.0 / 5000.0,
    val wordBoundaryCost: Double = 1500.0,
    val unknownCharacterCost: Double = 20000.0,
    val unfinishedCharacterCost: Double = 1000.0,
    val maxCachedPrefixes: Int = 256,
    val maxCachedTokenReadings: Int = 512,
    val maxPosStates: Int = 64,
) {
    init {
        require(costScale.isFinite() && costScale > 0.0)
        require(wordBoundaryCost.isFinite() && wordBoundaryCost >= 0.0)
        require(unknownCharacterCost.isFinite() && unknownCharacterCost > 0.0)
        require(unfinishedCharacterCost.isFinite() && unfinishedCharacterCost >= 0.0)
        require(maxCachedPrefixes in 1..4096 && maxCachedTokenReadings in 1..4096)
        require(maxPosStates in 1..2672)
    }
}

/**
 * Incremental reading-side Viterbi scores over the converter's existing dictionaries.
 * Sessions are confined to one decode/worker and discarded with that decode. No surfaces,
 * full-vocabulary scan, tokenizer, or N-best conversion are needed during beam expansion.
 *
 * Completed paths pay word, POS transition, boundary and EOS costs. Unfinished trie paths
 * have a provisional per-character estimate so a long word survives before its terminal.
 * This estimate is a search heuristic, not a probability or an admissible A* bound.
 */
class SumireReadingLexicon internal constructor(
    private val yomi: LOUDSWithTermId,
    private val tokens: TokenArray,
    private val connections: ConnectionMatrix,
    val config: SumireLexiconConfig = SumireLexiconConfig(),
    private val ensureAvailable: () -> Unit = {},
) : ReadingLexicon {
    override fun newSession(): Session {
        ensureAvailable()
        return Session()
    }

    private data class Path(val rightId: Int, val cost: Double, val unknown: Int)
    private data class WordToken(val leftId: Int, val rightId: Int, val cost: Int)
    private data class PendingWord(val node: Int, val length: Int, val preceding: List<Path>)
    private data class PrefixState(val completed: List<Path>, val pending: List<PendingWord>)

    inner class Session internal constructor() : ReadingLexiconSession {
        private val prefixes = boundedCache<String, PrefixState>(config.maxCachedPrefixes)
        private val tokenReadings = boundedCache<Int, List<WordToken>>(config.maxCachedTokenReadings)
        private val bos = PrefixState(listOf(Path(0, 0.0, 0)), emptyList())

        internal val cachedPrefixCount: Int get() = prefixes.size
        internal val cachedTokenReadingCount: Int get() = tokenReadings.size
        internal val largestPosStateCount: Int get() = prefixes.values.maxOfOrNull { it.completed.size } ?: 0

        override fun evaluate(reading: String, complete: Boolean): LexiconScore {
            checkActive()
            require(reading.length <= MAX_READING_LENGTH)
            require(reading.all { it in '\u3041'..'\u3096' || it == '\u30fc' })
            if (reading.isEmpty()) return LexiconScore(0.0)
            val state = prefixState(reading)
            var best = state.completed.minWithOrNull(compareBy<Path> {
                it.cost + if (complete) connections.getCost(it.rightId, 0) else 0
            }.thenBy { it.unknown })!!
            var bestCost = best.cost + if (complete) connections.getCost(best.rightId, 0) else 0
            if (!complete) {
                for (pending in state.pending) {
                    val preceding = pending.preceding.minWithOrNull(pathOrder)!!
                    val provisional = preceding.cost + config.wordBoundaryCost +
                        pending.length * config.unfinishedCharacterCost
                    if (provisional < bestCost || (provisional == bestCost && preceding.unknown < best.unknown)) {
                        best = preceding
                        bestCost = provisional
                    }
                }
            }
            return LexiconScore(bestCost * config.costScale, best.unknown)
        }

        private fun prefixState(reading: String): PrefixState {
            prefixes[reading]?.let { return it }
            var cachedLength = reading.length - 1
            var state = bos
            while (cachedLength > 0) {
                val cached = prefixes[reading.substring(0, cachedLength)]
                if (cached != null) {
                    state = cached
                    break
                }
                cachedLength--
            }
            for (index in cachedLength until reading.length) {
                checkActive()
                state = advance(state, reading[index])
                prefixes[reading.substring(0, index + 1)] = state
            }
            return state
        }

        private fun advance(previous: PrefixState, letter: Char): PrefixState {
            val pending = ArrayList<PendingWord>(previous.pending.size + 1)
            val byRightId = HashMap<Int, Path>()
            fun retain(path: Path) {
                val old = byRightId[path.rightId]
                if (old == null || pathOrder.compare(path, old) < 0) byRightId[path.rightId] = path
            }

            // Always preserve a finite unknown path, including dictionary-absent names/kana.
            for (path in previous.completed) {
                retain(Path(0, path.cost + connections.getCost(path.rightId, 0) +
                    config.unknownCharacterCost + config.wordBoundaryCost, path.unknown + 1))
            }

            fun extend(node: Int, length: Int, preceding: List<Path>) {
                checkActive()
                val nextNode = yomi.advancePrefix(node, letter)
                if (nextNode < 0) return
                if (yomi.hasContinuation(nextNode)) pending += PendingWord(nextNode, length + 1, preceding)
                if (!yomi.isTerminal(nextNode)) return
                val termId = yomi.getTermId(nextNode)
                val wordTokens = tokenReadings.getOrPut(termId) { minimumPosTokens(termId) }
                for (token in wordTokens) {
                    checkActive()
                    var best: Path? = null
                    var bestCost = Double.POSITIVE_INFINITY
                    for (path in preceding) {
                        val cost = path.cost + connections.getCost(path.rightId, token.leftId)
                        if (cost < bestCost || (cost == bestCost && path.unknown < (best?.unknown ?: Int.MAX_VALUE))) {
                            best = path
                            bestCost = cost
                        }
                    }
                    retain(Path(token.rightId, bestCost + token.cost + config.wordBoundaryCost, best!!.unknown))
                }
            }

            extend(0, 0, previous.completed)
            for (word in previous.pending) extend(word.node, word.length, word.preceding)
            // POS alternatives are bounded independently of the outer kana beam. Negative
            // connection costs make this a deliberate approximation when the cap is reached.
            val completed = byRightId.values.sortedWith(pathOrder).take(config.maxPosStates)
            return PrefixState(completed, pending)
        }

        private fun minimumPosTokens(termId: Int): List<WordToken> {
            val best = HashMap<Int, WordToken>()
            for (token in tokens.getListDictionaryByYomiTermId(termId)) {
                checkActive()
                val index = token.posTableIndex.toInt()
                val left = tokens.leftIds[index].toInt()
                val right = tokens.rightIds[index].toInt()
                val key = (left shl 16) or right
                if (token.wordCost.toInt() < (best[key]?.cost ?: Int.MAX_VALUE)) {
                    best[key] = WordToken(left, right, token.wordCost.toInt())
                }
            }
            return best.values.toList()
        }

        private fun checkActive() {
            ensureAvailable()
            if (Thread.currentThread().isInterrupted) throw CancellationException("Reading search interrupted")
        }
    }

    companion object {
        const val MAX_READING_LENGTH = 128
        private val pathOrder = compareBy<Path> { it.cost }.thenBy { it.unknown }.thenBy { it.rightId }

        private fun <K, V> boundedCache(limit: Int) = object : LinkedHashMap<K, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > limit
        }
    }
}
