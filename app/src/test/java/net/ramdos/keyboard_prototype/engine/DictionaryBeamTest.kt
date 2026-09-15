package net.ramdos.keyboard_prototype.engine

import org.junit.Assert.*
import org.junit.Test

class DictionaryBeamTest {
    private val model = object : KanaLanguageModel {
        override fun nextLogProbabilities(context: String, prefixes: List<String>): List<Map<Char, Double>> =
            error("No LM needed to isolate dictionary/geometry interaction")
    }
    private val keys = listOf(KanaKey("か", 0f, 0f, 0.5f, 1f), KanaKey("な", 0.5f, 0f, 1f, 1f))
    private val geometry = StrokeModel(StrokeModelConfig(nearbyKeyCount = 1))
    private val trace = GlideTrace(listOf(TracePoint(0.25f, 0.5f, 0), TracePoint(0.75f, 0.5f, 100)), keys)

    @Test
    fun dictionaryKeepsAValidWordBeforeItsPrefixWouldBePruned() {
        val lexicon = lexicon { reading, _ -> LexiconScore(if ("がな".startsWith(reading)) 0.0 else 8.0) }
        val config = GlideDecoderConfig(maxBeamWidth = 1, maxCandidates = 1, languageWeight = 0.0, characterInsertionBonus = 0.0)
        val plain = GlideDecoder(geometry, model, config).decode(trace)
        val guided = GlideDecoder(geometry, model, config, lexicon).decode(trace)
        assertEquals("かな", plain.single().reading)
        assertEquals("がな", guided.single().reading)
        // Reranking the old width-one result after decoding could not recover がな.
        assertFalse(plain.any { it.reading == "がな" })
    }

    @Test
    fun completeCostIsUsedBeforeTheLastPruneAndDoesNotAccumulatePerEvent() {
        val observed = mutableListOf<Pair<String, Boolean>>()
        val lexicon = lexicon { reading, complete ->
            observed += reading to complete
            LexiconScore(if (reading == "がな") 0.4 else if (complete) 8.0 else 0.2)
        }
        val config = GlideDecoderConfig(languageWeight = 0.0, characterInsertionBonus = 0.0, dictionaryWeight = 1.5)
        val result = GlideDecoder(geometry, model, config, lexicon).decode(trace)
        assertEquals("がな", result.first().reading)
        assertTrue(observed.any { it.first == "が" && !it.second })
        assertTrue(observed.any { it.first == "がな" && it.second })
        assertEquals(0.4, result.first().dictionaryCost, 0.0)
        assertEquals(1.0, result.sumOf { it.posterior }, 1e-10)
        result.forEach { assertEquals(it.strokeLogProbability - 1.5 * it.dictionaryCost, it.score, 1e-10) }
        val baseline = GlideDecoder(geometry, model, config).decode(trace).associateBy { it.reading }
        result.forEach { assertEquals(baseline.getValue(it.reading).strokeLogProbability, it.strokeLogProbability, 1e-10) }
    }

    @Test
    fun unknownNameSurvivesInTheReservedFallbackSlot() {
        val lexicon = lexicon { reading, _ -> LexiconScore(if (reading.startsWith("か")) 100.0 else 0.0,
            if (reading.startsWith("か")) reading.length else 0) }
        val config = GlideDecoderConfig(maxBeamWidth = 3, maxCandidates = 2, languageWeight = 0.0, characterInsertionBonus = 0.0, unknownBeamSlots = 1)
        val result = GlideDecoder(geometry, model, config, lexicon).decode(trace)
        assertEquals("がな", result.first().reading)
        assertTrue(result.any { it.reading == "かな" && it.unknownCharacters == 2 })
    }

    @Test
    fun zeroDictionaryWeightSkipsAllDictionaryWork() {
        val lexicon = object : ReadingLexicon {
            override fun newSession(): ReadingLexiconSession = error("Dictionary must not be consulted")
        }
        val config = GlideDecoderConfig(languageWeight = 0.0, characterInsertionBonus = 0.0, dictionaryWeight = 0.0)
        val result = GlideDecoder(geometry, model, config, lexicon).decode(trace)
        assertEquals("かな", result.first().reading)
        assertTrue(result.all { it.dictionaryCost == 0.0 })
    }

    @Test
    fun eachDecodeCreatesAFreshLexiconSession() {
        var sessions = 0
        val lexicon = object : ReadingLexicon {
            override fun newSession(): ReadingLexiconSession {
                sessions++
                return object : ReadingLexiconSession {
                    override fun evaluate(reading: String, complete: Boolean) = LexiconScore(0.0)
                }
            }
        }
        val decoder = GlideDecoder(geometry, model, GlideDecoderConfig(languageWeight = 0.0, characterInsertionBonus = 0.0), lexicon)
        decoder.decode(trace)
        decoder.decode(trace)
        assertEquals(2, sessions)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonfiniteDictionaryScores() {
        GlideDecoder(geometry, model, GlideDecoderConfig(languageWeight = 0.0, characterInsertionBonus = 0.0),
            lexicon { _, _ -> LexiconScore(Double.NaN) }).decode(trace)
    }

    private fun lexicon(score: (String, Boolean) -> LexiconScore) = object : ReadingLexicon {
        override fun newSession() = object : ReadingLexiconSession {
            override fun evaluate(reading: String, complete: Boolean) = score(reading, complete)
        }
    }
}
