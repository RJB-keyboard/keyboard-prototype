package net.ramdos.keyboard_prototype

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ramdos.keyboard_prototype.engine.conversion.SumireKanaKanjiConverter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies packaged zip streams and Kuromoji's dictionary classloader on Android. */
@RunWith(AndroidJUnit4::class)
class SumireConversionInstrumentedTest {
    @Test
    fun packagedOfflineDictionariesConvertAndReadContext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SumireKanaKanjiConverter.open(context).use { converter ->
            assertEquals("日本語", converter.convert("にほんご", 5).first())
            val phrase = converter.convert("きょうはいいてんきです", 5)
            assertTrue(phrase.toString(), phrase.any { "今日" in it && "天気" in it })
            assertEquals("わたしはとうきょうへいきます。", converter.readingOf("私は東京へ行きます。"))
        }
    }
}
