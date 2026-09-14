package net.ramdos.keyboard_prototype

import android.view.KeyEvent
import android.view.inputmethod.InputConnection

/** Delete the selection, or one Unicode code point before the cursor. */
internal fun InputConnection.backspace() {
    beginBatchEdit()
    try {
        if (!getSelectedText(0).isNullOrEmpty()) {
            commitText("", 1)
        } else if (!deleteSurroundingTextInCodePoints(1, 0)) {
            // Some editors only support key events for deletion.
            sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
    } finally {
        endBatchEdit()
    }
}
