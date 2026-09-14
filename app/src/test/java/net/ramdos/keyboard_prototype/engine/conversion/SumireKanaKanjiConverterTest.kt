package net.ramdos.keyboard_prototype.engine.conversion

import java.io.File
import java.io.ByteArrayInputStream
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Test

/** Exercises the actual shipped dictionaries and morphological analyzer, without network access. */
class SumireKanaKanjiConverterTest {
    @Test
    fun dictionaryProducesRankedJapaneseConversion() {
        val candidates = converter.convert("にほんご", 8)
        assertEquals("日本語", candidates.first())
        assertEquals(candidates.distinct(), candidates)
        assertTrue(candidates.size <= 8)
    }

    @Test
    fun convertsMultipleWordsAndPreservesKana() {
        val candidates = converter.convert("きょうはいいてんきです", 8)
        assertTrue(candidates.toString(), candidates.any { "今日" in it && "天気" in it })
        assertTrue(candidates.all { !it.contains("BOS") && !it.contains("EOS") })
    }

    @Test
    fun limitsAndUnknownReadingsAreHandled() {
        assertTrue(converter.convert("にほんご", 0).isEmpty())
        assertTrue(converter.convert("", 5).isEmpty())
        assertEquals(1, converter.convert("にほんご", 1).size)
        assertTrue(converter.convert("ゔゔゔ", 2).isNotEmpty())
    }

    @Test
    fun contextUsesReadingsInsteadOfPronunciation() {
        assertEquals("わたしはとうきょうへいきます。", converter.readingOf("私は東京へ行きます。"))
        assertEquals("こーひー", converter.readingOf("コーヒー"))
        assertEquals("", converter.readingOf(""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonKanaConversionInput() {
        converter.convert("東京", 5)
    }

    @Test
    fun corruptDictionaryFailsLoudly() {
        assertThrows(Exception::class.java) {
            SumireKanaKanjiConverter.fromAssets { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
        }
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
