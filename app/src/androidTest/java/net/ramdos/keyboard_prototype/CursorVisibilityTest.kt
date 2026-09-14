package net.ramdos.keyboard_prototype

import android.widget.EditText
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CursorVisibilityTest {
    @Test
    fun cursorLineRemainsVisibleAboveKeyboardAfterMultipleNewlines() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val input = activity.findViewById<EditText>(R.id.test_input)
                input.showSoftInputOnFocus = false
                input.requestFocus()
                input.setText((1..12).joinToString("\n") { "入力中の文字 $it" })
                input.setSelection(input.length())
                val root = activity.findViewById<View>(R.id.main_content)
                val insets = WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 900))
                    .setVisible(WindowInsetsCompat.Type.ime(), true).build()
                ViewCompat.dispatchApplyWindowInsets(root, insets)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val input = activity.findViewById<EditText>(R.id.test_input)
                val viewport = activity.findViewById<View>(R.id.setup_scroll)
                val inputLocation = IntArray(2)
                val viewportLocation = IntArray(2)
                input.getLocationOnScreen(inputLocation)
                viewport.getLocationOnScreen(viewportLocation)
                val line = input.layout.getLineForOffset(input.selectionEnd)
                val top = inputLocation[1] + input.totalPaddingTop + input.layout.getLineTop(line) - input.scrollY
                val bottom = inputLocation[1] + input.totalPaddingTop + input.layout.getLineBottom(line) - input.scrollY
                assertTrue("Cursor above viewport", top >= viewportLocation[1])
                assertTrue("Cursor hidden by keyboard", bottom <= viewportLocation[1] + viewport.height)
            }
        }
    }
}
