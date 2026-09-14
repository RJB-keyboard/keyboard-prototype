package net.ramdos.keyboard_prototype.engine.conversion

import com.kazumaproject.Louds.with_term_id.LOUDSWithTermId
import com.kazumaproject.dictionary.TokenArray
import com.kazumaproject.mozc.ConnectionMatrix
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.BitSet
import java.util.concurrent.CancellationException
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Test

class SumireReadingLexiconTest {
    @Test
    fun realDictionaryPrefersKnownWordToUnknownKana() {
        val session = converter.readingLexicon.newSession()
        val word = session.evaluate("にほんご", complete = true)
        val unknown = session.evaluate("ゖゖゖゖ", complete = true)
        assertEquals(0, word.unknownCharacters)
        assertEquals(4, unknown.unknownCharacters)
        assertTrue("known=$word unknown=$unknown", word.cost < unknown.cost)
    }

    @Test
    fun realDictionarySupportsPhrasesAndInflectedWords() {
        val session = converter.readingLexicon.newSession()
        for (reading in listOf("きょうはいいてんきです", "にほんごをべんきょうしています", "たべました")) {
            val score = session.evaluate(reading, complete = true)
            assertTrue(score.cost.isFinite())
            assertEquals(reading, 0, score.unknownCharacters)
        }
    }

    @Test
    fun unfinishedTriePrefixIsAllowedButIsNotACompletedWord() {
        val session = fixture().newSession()
        assertEquals(0.5, session.evaluate("あ", complete = false).cost, 1e-9)
        val complete = session.evaluate("あ", complete = true)
        assertEquals(4.3, complete.cost, 1e-9)
        assertEquals(1, complete.unknownCharacters)
        assertEquals(0, session.evaluate("あい", complete = true).unknownCharacters)
    }

    @Test
    fun wordPosBoundaryAndEosCostsChooseTheBestSegmentation() {
        val session = fixture().newSession()
        // BOS->left1 100 + word 1000 + boundary 1500 + right1->EOS 500.
        assertEquals(0.62, session.evaluate("あい", complete = true).cost, 1e-9)
        // Two words beat the 10000-cost compound; right1->left2 contributes -300.
        assertEquals(1.24, session.evaluate("あいう", complete = true).cost, 1e-9)
        assertEquals(0, session.evaluate("あいう", complete = true).unknownCharacters)
    }

    @Test
    fun tokenDeduplicationAndPrefixCachingDoNotChangeScores() {
        val lexicon = fixture()
        val session = lexicon.newSession()
        session.evaluate("あ", complete = false)
        session.evaluate("あい", complete = false)
        assertEquals(lexicon.newSession().evaluate("あいう", true), session.evaluate("あいう", true))
        assertEquals(session.evaluate("あいう", true), session.evaluate("あいう", true))
    }

    @Test
    fun unknownPathAndCachesStayBounded() {
        val config = SumireLexiconConfig(maxCachedPrefixes = 4, maxCachedTokenReadings = 1, maxPosStates = 2)
        val session = fixture(config).newSession()
        for (reading in listOf("あいうあいう", "うあいう", "ゖゖゖゖゖ", "あいあいあい", "うううう")) {
            assertTrue(session.evaluate(reading, true).cost.isFinite())
            assertTrue(session.cachedPrefixCount <= 4)
            assertTrue(session.cachedTokenReadingCount <= 1)
            assertTrue(session.largestPosStateCount <= 2)
        }
        assertEquals(5, session.evaluate("ゖゖゖゖゖ", true).unknownCharacters)
        assertEquals(0.0, session.evaluate("", true).cost, 0.0)
        assertEquals(0.0, session.evaluate("", false).cost, 0.0)
    }

    @Test
    fun decodeCancellationDoesNotClearTheInterruptFlag() {
        val session = fixture().newSession()
        Thread.currentThread().interrupt()
        try {
            assertThrows(CancellationException::class.java) { session.evaluate("あいう", true) }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun invalidConfigurationAndOverlongInputAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { SumireLexiconConfig(costScale = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { SumireLexiconConfig(maxCachedPrefixes = 0) }
        val session = fixture().newSession()
        assertEquals(128, session.evaluate("ゖ".repeat(128), true).unknownCharacters)
        assertThrows(IllegalArgumentException::class.java) { session.evaluate("あ".repeat(129), true) }
    }

    private data class TrieNode(
        val children: MutableMap<Char, TrieNode> = sortedMapOf(),
        var termId: Int? = null,
        var index: Int = 0,
    )

    /** Serialize a tiny dictionary in the same format as the shipped runtime reader. */
    private fun fixture(config: SumireLexiconConfig = SumireLexiconConfig()): SumireReadingLexicon {
        val words = listOf("あい", "う", "あいう")
        val root = TrieNode()
        words.forEachIndexed { index, reading ->
            var node = root
            for (letter in reading) node = node.children.getOrPut(letter) { TrieNode() }
            node.termId = index + 1
        }
        val lbs = BitSet().apply { set(0) }
        val leaves = BitSet()
        val labels = mutableListOf('\u0000', '\u0000')
        val termsByNode = sortedMapOf<Int, Int>()
        val queue = ArrayDeque<TrieNode>().apply { add(root) }
        var position = 2
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            node.termId?.let { leaves.set(node.index); termsByNode[node.index] = it }
            for ((letter, child) in node.children) {
                child.index = position
                lbs.set(position++)
                labels += letter
                queue += child
            }
            position++
        }
        val yomi = objects(lbs, leaves, labels.toCharArray(), termsByNode.values.toIntArray()).use {
            LOUDSWithTermId().readDictionary(it)
        }
        // First reading has two same-POS surfaces: minimum word cost must win.
        val postings = BitSet().apply { set(1); set(2); set(4); set(6) }
        val tokens = TokenArray()
        objects(shortArrayOf(1, 1, 2, 3), shortArrayOf(3000, 1000, 2000, 10000),
            intArrayOf(-2, -2, -2, -2), postings).use { tokens.readExternalNotCompress(it) }
        objects(shortArrayOf(0, 1, 2, 3), shortArrayOf(0, 1, 2, 3)).use { tokens.readPOSTable(it) }
        val matrix = ConnectionMatrix(4, ShortArray(16).apply {
            this[1] = 100
            this[1 * 4] = 500
            this[1 * 4 + 2] = -300
            this[2 * 4] = 400
        })
        return SumireReadingLexicon(yomi, tokens, matrix, config)
    }

    private fun objects(vararg values: Any): ObjectInputStream {
        val bytes = ByteArrayOutputStream()
        ObjectOutputStream(bytes).use { output -> values.forEach(output::writeObject) }
        return ObjectInputStream(ByteArrayInputStream(bytes.toByteArray()))
    }

    companion object {
        private val converter by lazy {
            val root = listOf(File("src/main/assets/conversion/sumire"),
                File("app/src/main/assets/conversion/sumire")).first { it.isDirectory }
            SumireKanaKanjiConverter.fromAssets { name -> File(root, name).inputStream() }
        }

        @JvmStatic @AfterClass fun release() = converter.close()
    }
}
