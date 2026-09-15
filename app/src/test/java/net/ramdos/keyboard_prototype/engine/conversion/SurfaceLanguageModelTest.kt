package net.ramdos.keyboard_prototype.engine.conversion

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SurfaceLanguageModelTest {
    @Test
    fun boundingPreservesContextOrderInsteadOfSaturatingGoodCandidates() {
        val config = SurfaceRerankingConfig()
        val adjusted = config.boundAdjustments(listOf(-2.0, -6.0, 0.0, 10.0, 2.0))
        assertTrue(adjusted[1] < adjusted[0])
        assertTrue(adjusted[0] < adjusted[2])
        assertTrue(adjusted[2] < adjusted[4])
        assertTrue(adjusted[4] < adjusted[3])
        assertTrue(adjusted.all { kotlin.math.abs(it) <= config.maxAdjustment })
        assertEquals(0.0, adjusted[2], 0.0)
    }

    @Test
    fun badOutliersDoNotWeakenGoodEvidenceAndSmallCorrectionsAreUnchanged() {
        val config = SurfaceRerankingConfig()
        assertEquals(config.boundAdjustments(listOf(-2.0, -6.0)),
            config.boundAdjustments(listOf(-2.0, -6.0, 100.0)).take(2))
        assertEquals(listOf(-0.2, 0.0, 0.3), config.boundAdjustments(listOf(-0.2, 0.0, 0.3)))
        assertTrue(config.boundAdjustments(emptyList()).isEmpty())
        assertTrue(SurfaceRerankingConfig(maxAdjustment = 0.0)
            .boundAdjustments(listOf(-2.0, 0.0, 3.0)).all { it == 0.0 })
    }

    @Test
    fun corpusScoresDistinguishSeveralHomophonesWithoutPhraseRules() {
        for ((natural, unnatural) in listOf(
            "友達に会う" to "友達に合う",
            "服を着る" to "服を切る",
            "橋を渡る" to "橋をわたる",
            "写真を撮る" to "写真を取る",
            "目が合う" to "目が会う",
        )) assertTrue("$natural / $unnatural", model.cost(natural) < model.cost(unnatural))
    }

    @Test
    fun unseenCharactersAndShortInputsHaveFiniteScoresAndWidthIsNormalized() {
        for (text in listOf("", "あ", "ゖゖ", "𠮷野", "新しい名前")) assertTrue(model.cost(text).isFinite())
        assertEquals(0.0, model.cost(""), 0.0)
        assertEquals(model.cost("コーヒー"), model.cost("ｺｰﾋｰ"), 0.0)
    }

    @Test
    fun interruptionIsPreserved() {
        val loaded = model
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) { loaded.cost("友達に会う") }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test
    fun malformedModelAndInvalidWeightsAreRejected() {
        assertThrows(Exception::class.java) { SurfaceLanguageModel.read(ByteArrayInputStream(byteArrayOf(1, 2, 3))) }
        assertThrows(IllegalArgumentException::class.java) { SurfaceRerankingConfig(weight = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { SurfaceRerankingConfig(maxAdjustment = -1.0) }
    }

    companion object {
        private val model by lazy {
            val file = listOf(File("src/main/assets/conversion/surface/model.bin"),
                File("app/src/main/assets/conversion/surface/model.bin")).first { it.isFile }
            file.inputStream().use(SurfaceLanguageModel::read)
        }
    }
}
