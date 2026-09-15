package net.ramdos.keyboard_prototype

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.BaseInputConnection
import android.view.View
import android.text.InputType
import android.text.Selection
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EnterActionTest {
    @Test
    fun newlineReplacesSelectionAndActionDoesNotInsertText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val actions = mutableListOf<Int>()
            val connection = object : BaseInputConnection(View(instrumentation.targetContext), true) {
                override fun performEditorAction(actionCode: Int): Boolean {
                    actions.add(actionCode)
                    return true
                }
            }
            val editable = requireNotNull(connection.editable)
            editable.append("あいう")
            Selection.setSelection(editable, 1, 2)
            connection.enter(EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
            })
            assertEquals("あ\nう", editable.toString())
            assertEquals(2, Selection.getSelectionStart(editable))
            assertTrue(actions.isEmpty())
            connection.enter(EditorInfo().apply { imeOptions = EditorInfo.IME_ACTION_SEARCH })
            assertEquals(listOf(EditorInfo.IME_ACTION_SEARCH), actions)
            assertEquals("あ\nう", editable.toString())
        }
    }

    @Test
    fun standardActionsHaveMatchingLabelsAndIds() {
        val cases = mapOf(
            EditorInfo.IME_ACTION_GO to R.string.enter_go,
            EditorInfo.IME_ACTION_SEARCH to R.string.enter_search,
            EditorInfo.IME_ACTION_SEND to R.string.enter_send,
            EditorInfo.IME_ACTION_NEXT to R.string.enter_next,
            EditorInfo.IME_ACTION_PREVIOUS to R.string.enter_previous,
            EditorInfo.IME_ACTION_DONE to R.string.enter_done,
        )
        cases.forEach { (id, label) ->
            val action = enterAction(EditorInfo().apply { imeOptions = id or EditorInfo.IME_FLAG_NO_FULLSCREEN })
            assertEquals(id, action.id)
            assertEquals(label, action.labelRes)
        }
    }

    @Test
    fun multilineFlagOverridesEvenCustomSendAction() {
        val action = enterAction(EditorInfo().apply {
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
            actionLabel = "投稿"
            actionId = 42
        })
        assertNull(action.id)
        assertEquals(R.string.enter_newline, action.labelRes)
        assertNull(action.customLabel)
    }

    @Test
    fun missingAndUnspecifiedEditorsUseNewline() {
        assertNull(enterAction(null).id)
        listOf(EditorInfo.IME_ACTION_NONE, EditorInfo.IME_ACTION_UNSPECIFIED).forEach {
            assertNull(enterAction(EditorInfo().apply { imeOptions = it }).id)
        }
    }

    @Test
    fun customActionPreservesItsIdAndLabel() {
        val action = enterAction(EditorInfo().apply { actionLabel = "投稿"; actionId = 42 })
        assertEquals(42, action.id)
        assertEquals("投稿", action.customLabel)
    }
}
