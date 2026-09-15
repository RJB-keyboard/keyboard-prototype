package net.ramdos.keyboard_prototype

import net.ramdos.keyboard_prototype.ui.GojuonLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GojuonLayoutTest {
    @Test
    fun allModernKanaArePresentAndCentersHitTheDisplayedKey() {
        val expected = "あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわをんー"
        assertEquals(expected.map { it.toString() }.toSet(), GojuonLayout.keys.map { it.kana }.toSet())
        assertEquals(47, GojuonLayout.keys.size)
        GojuonLayout.keys.forEach { key ->
            assertEquals(key, GojuonLayout.keyAt((key.left + key.right) / 2, (key.top + key.bottom) / 2))
        }
        assertEquals("あ", GojuonLayout.keyAt(9.5f / 10, 0.1f)?.kana)
        assertEquals("お", GojuonLayout.keyAt(9.5f / 10, 0.9f)?.kana)
    }

    @Test
    fun blankCellsAndOutsideCoordinatesAreNotEdgeKeys() {
        for ((row, kana) in listOf("わ", "を", "ん").withIndex()) {
            assertEquals(kana, GojuonLayout.keyAt(0.05f, (row + 0.5f) / 5)?.kana)
        }
        assertNull(GojuonLayout.keyAt(0.05f, 0.7f))
        assertEquals("ー", GojuonLayout.keyAt(0.05f, 0.9f)?.kana)
        assertNull(GojuonLayout.keyAt(0.25f, 0.3f))
        assertNull(GojuonLayout.keyAt(0.25f, 0.7f))
        assertNull(GojuonLayout.keyAt(-0.01f, 0.1f))
        assertNull(GojuonLayout.keyAt(1f, 0.1f))
        assertNull(GojuonLayout.keyAt(0.95f, 1f))
    }

    @Test
    fun sharedEdgesBelongToExactlyOneKey() {
        // The boundary between か/あ and あ/い must not select two keys.
        for ((x, y, expected) in listOf(
            Triple(0.9f, 0.1f, "あ"),
            Triple(0.95f, 0.2f, "い"),
            Triple(0.9f, 0.2f, "い"),
            Triple(0f, 0f, "わ"),
        )) {
            assertEquals(expected, GojuonLayout.keyAt(x, y)?.kana)
            assertEquals(1, GojuonLayout.keys.count { it.contains(x, y) })
        }
    }

    @Test
    fun nonfiniteCoordinatesNeverSelectAKey() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertNull(GojuonLayout.keyAt(invalid, 0.1f))
            assertNull(GojuonLayout.keyAt(0.95f, invalid))
        }
    }
}
