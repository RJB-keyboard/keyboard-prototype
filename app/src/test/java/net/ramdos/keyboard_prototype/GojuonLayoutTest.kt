package net.ramdos.keyboard_prototype

import net.ramdos.keyboard_prototype.ui.GojuonLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GojuonLayoutTest {
    @Test
    fun allModernKanaArePresentAndCentersHitTheDisplayedKey() {
        val expected = "あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわをん"
        assertEquals(expected.map { it.toString() }.toSet(), GojuonLayout.keys.map { it.kana }.toSet())
        assertEquals(46, GojuonLayout.keys.size)
        GojuonLayout.keys.forEach { key ->
            assertEquals(key, GojuonLayout.keyAt((key.left + key.right) / 2, (key.top + key.bottom) / 2))
        }
        assertEquals("あ", GojuonLayout.keyAt(9.5f / 10, 0.1f)?.kana)
        assertEquals("お", GojuonLayout.keyAt(9.5f / 10, 0.9f)?.kana)
    }

    @Test
    fun blankCellsAndOutsideCoordinatesAreNotEdgeKeys() {
        assertNull(GojuonLayout.keyAt(0.5f / 10, 0.3f))
        assertNull(GojuonLayout.keyAt(0.5f / 10, 0.7f))
        assertNull(GojuonLayout.keyAt(2.5f / 10, 0.3f))
        assertNull(GojuonLayout.keyAt(-0.01f, 0.1f))
        assertNull(GojuonLayout.keyAt(1f, 0.1f))
        assertNull(GojuonLayout.keyAt(0.95f, 1f))
    }
}
