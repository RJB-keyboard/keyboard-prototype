package net.ramdos.keyboard_prototype

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import android.view.inputmethod.EditorInfo

internal data class EnterAction(val id: Int?, val labelRes: Int, val customLabel: CharSequence? = null) {
    fun label(context: Context): CharSequence = customLabel ?: context.getString(labelRes)
}

/** Keep the key label and dispatched action consistent for the current editor. */
internal fun enterAction(info: EditorInfo?): EnterAction {
    val newline = EnterAction(null, R.string.enter_newline)
    if (info == null || info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return newline
    if (!info.actionLabel.isNullOrEmpty()) {
        return EnterAction(info.actionId, R.string.enter_go, info.actionLabel)
    }
    val id = info.imeOptions and EditorInfo.IME_MASK_ACTION
    val label = when (id) {
        EditorInfo.IME_ACTION_GO -> R.string.enter_go
        EditorInfo.IME_ACTION_SEARCH -> R.string.enter_search
        EditorInfo.IME_ACTION_SEND -> R.string.enter_send
        EditorInfo.IME_ACTION_NEXT -> R.string.enter_next
        EditorInfo.IME_ACTION_PREVIOUS -> R.string.enter_previous
        EditorInfo.IME_ACTION_DONE -> R.string.enter_done
        else -> return newline
    }
    return EnterAction(id, label)
}

internal fun InputConnection.enter(info: EditorInfo?) {
    val action = enterAction(info)
    if (action.id != null) {
        performEditorAction(action.id)
    } else if (info?.inputType == InputType.TYPE_NULL || !commitText("\n", 1)) {
        sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }
}
