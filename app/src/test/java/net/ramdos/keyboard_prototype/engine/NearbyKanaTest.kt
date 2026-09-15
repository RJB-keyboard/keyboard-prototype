package net.ramdos.keyboard_prototype.engine

import kotlin.math.exp
import kotlin.math.ln
import org.junit.Assert.*
import org.junit.Test

class NearbyKanaTest {
    // Exact binary coordinates avoid giving any direction a floating-point advantage.
    private val keys = "あいうえなかきくけ".mapIndexed { index, kana ->
        key(kana, index % 3, index / 3)
    } + key('こ', 4, 1)
    private val trace = GlideTrace(listOf(point(1, 0), point(4, 120)), keys)
    private val uniform = object : KanaLanguageModel {
        override fun nextLogProbabilities(context: String, prefixes: List<String>) =
            prefixes.map { ('\u3041'..'\u3096').associateWith { ln(1.0 / 86) } }
    }

    @Test
    fun allAdjacentDirectionsAreAvailableButDistantKeysAreExcluded() {
        val events = StrokeModel().lattice(trace).events
        assertTrue(events.first().characterLogProbabilities.keys.containsAll("あいうえなかきくけ".toList()))
        assertFalse('こ' in events.first().characterLogProbabilities)
        events.forEach { event ->
            assertEquals(1.0, event.characterLogProbabilities.values.sumOf(::exp) + exp(event.skipLogProbability), 1e-10)
        }
    }

    @Test
    fun dictionaryCanRecoverANearbyWordMissingFromTheOldLattice() {
        // The intended か is beside な, but falls outside the old three-key cutoff.
        val lexicon = object : ReadingLexicon {
            override fun newSession() = object : ReadingLexiconSession {
                override fun evaluate(reading: String, complete: Boolean) =
                    LexiconScore(if ("かこ".startsWith(reading)) 0.0 else 4.0)
            }
        }
        val previous = StrokeModel(StrokeModelConfig(nearbyKeyCount = 3, distanceSigma = 0.43))
        assertFalse('か' in previous.lattice(trace).events.first().characterLogProbabilities)
        assertFalse(GlideDecoder(previous, uniform, readingLexicon = lexicon).decode(trace).any { it.reading == "かこ" })
        val result = GlideDecoder(languageModel = uniform, readingLexicon = lexicon).decode(trace)
        assertEquals("かこ", result.first().reading)
    }

    @Test
    fun languageCanCorrectMovingGlidesWhileDeliberatelyHeldKeysStayPrecise() {
        val model = object : KanaLanguageModel {
            override fun nextLogProbabilities(context: String, prefixes: List<String>) = prefixes.map { prefix ->
                // 200:1 is enough at the default 0.35 LM weight only after softening distance.
                mapOf('え' to ln(if (prefix.isEmpty()) 0.98 else 0.001),
                    'な' to ln(0.0049), 'こ' to ln(if (prefix.isEmpty()) 0.001 else 0.98))
            }
        }
        val strict = GlideDecoder(StrokeModel(StrokeModelConfig(distanceSigma = 0.43)), model).decode(trace)
        val tolerant = GlideDecoder(languageModel = model).decode(trace)
        assertEquals("なこ", strict.first().reading)
        assertEquals("えこ", tolerant.first().reading)
        val held = trace.copy(points = listOf(point(1, 0), point(1, 180), point(4, 300)))
        assertEquals("なこ", GlideDecoder(languageModel = model).decode(held).first().reading)
        val tap = trace.copy(points = listOf(point(1, 0)))
        assertEquals("な", GlideDecoder(languageModel = model).decode(tap).first().reading)
    }

    @Test
    fun uninformativeLanguageStillPrefersTracedKeysWithoutExtraCharacters() {
        assertEquals("なこ", GlideDecoder(languageModel = uniform).decode(trace).first().reading)
        val tap = trace.copy(points = listOf(point(1, 0), point(1, 1000)))
        assertEquals("な", GlideDecoder(languageModel = uniform).decode(tap).first().reading)
    }

    private fun key(kana: Char, x: Int, y: Int) =
        KanaKey(kana.toString(), x / 8f, y / 8f, (x + 1) / 8f, (y + 1) / 8f)
    private fun point(x: Int, time: Long) = TracePoint((x + 0.5f) / 8f, 1.5f / 8f, time)
}
