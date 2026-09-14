package net.ramdos.keyboard_prototype

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ramdos.keyboard_prototype.engine.GlideCandidateEngine
import net.ramdos.keyboard_prototype.engine.GlideTrace
import net.ramdos.keyboard_prototype.engine.TracePoint
import net.ramdos.keyboard_prototype.engine.conversion.SumireKanaKanjiConverter
import net.ramdos.keyboard_prototype.engine.lm.OnnxHiraganaLanguageModel
import net.ramdos.keyboard_prototype.ui.GojuonLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual geometry + GPT-2 + dictionary, with no fake language or conversion provider. */
@RunWith(AndroidJUnit4::class)
class GlideEngineInstrumentedTest {
    @Test
    fun realPipelineConvertsADeliberateGlideAndKeepsTheLiteralReading() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = OnnxHiraganaLanguageModel.open(context)
        val converter = try { SumireKanaKanjiConverter.open(context) } catch (error: Throwable) {
            model.close()
            throw error
        }
        GlideCandidateEngine(model, converter).use { engine ->
            val started = System.nanoTime()
            val candidates = engine.generateCandidates(traceFor("にほんこ"), "私は")
            Log.i("GlideEngineTest", "Fixture decode millis=${(System.nanoTime() - started) / 1_000_000}, candidates=$candidates")
            assertTrue("Expected 日本語 among $candidates", "日本語" in candidates)
            assertTrue(candidates.any { candidate -> candidate.all { it in '\u3041'..'\u3096' } })
            assertEquals(candidates.size, candidates.distinct().size)
            assertTrue(candidates.size <= 12)
        }
    }

    private fun traceFor(baseKeys: String): GlideTrace {
        val points = mutableListOf<TracePoint>()
        var time = 0L
        baseKeys.forEach { kana ->
            val key = GojuonLayout.keys.single { it.kana == kana.toString() }
            val x = (key.left + key.right) / 2
            val y = (key.top + key.bottom) / 2
            points += TracePoint(x, y, time)
            time += 180
            points += TracePoint(x, y, time)
            time += 120
        }
        return GlideTrace(points, GojuonLayout.keys)
    }
}
