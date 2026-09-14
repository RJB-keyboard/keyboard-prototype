package net.ramdos.keyboard_prototype

import android.text.Selection
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackspaceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun assertDeletion(text: String, start: Int, end: Int = start, expected: String, cursor: Int) {
        instrumentation.runOnMainSync {
            val connection = BaseInputConnection(View(instrumentation.targetContext), true)
            val editable = requireNotNull(connection.editable)
            editable.append(text)
            Selection.setSelection(editable, start, end)
            connection.backspace()
            assertEquals(expected, editable.toString())
            assertEquals(cursor, Selection.getSelectionStart(editable))
            assertEquals(cursor, Selection.getSelectionEnd(editable))
        }
    }

    @Test
    fun deletesKanaAndAsciiBeforeCursor() {
        assertDeletion("あいう", 2, expected = "あう", cursor = 1)
        assertDeletion("abc", 3, expected = "ab", cursor = 2)
    }

    @Test
    fun deletesSelectionInEitherDirectionWithoutDeletingAdjacentText() {
        assertDeletion("あいうえ", 1, 3, expected = "あえ", cursor = 1)
        assertDeletion("あいうえ", 3, 1, expected = "あえ", cursor = 1)
    }

    @Test
    fun emptyInputAndStartOfInputAreUnchanged() {
        assertDeletion("", 0, expected = "", cursor = 0)
        assertDeletion("あ", 0, expected = "あ", cursor = 0)
    }

    @Test
    fun deletesSupplementaryCharacterWithoutSplittingSurrogatePair() {
        assertDeletion("あ😀い", 3, expected = "あい", cursor = 1)
    }

    @Test
    fun unsupportedCodePointDeletionFallsBackToDeleteKeyEvents() {
        instrumentation.runOnMainSync {
            val events = mutableListOf<KeyEvent>()
            val connection = object : BaseInputConnection(View(instrumentation.targetContext), true) {
                override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) = false
                override fun sendKeyEvent(event: KeyEvent): Boolean {
                    events.add(event)
                    return true
                }
            }
            connection.backspace()
            assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), events.map { it.action })
            assertEquals(listOf(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_DEL), events.map { it.keyCode })
        }
    }
}
