package net.ramdos.keyboard_prototype

import android.content.Intent
import android.graphics.Rect
import android.widget.EditText
import androidx.core.widget.doAfterTextChanged
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        val input = findViewById<EditText>(R.id.test_input)
        fun revealCursor() {
            input.post {
                if (!input.hasFocus()) return@post
                val layout = input.layout ?: return@post
                val offset = input.selectionEnd.coerceIn(0, input.text.length)
                input.bringPointIntoView(offset)
                val line = layout.getLineForOffset(offset)
                val x = layout.getPrimaryHorizontal(offset).toInt() + input.totalPaddingLeft
                val top = layout.getLineTop(line) + input.totalPaddingTop
                val bottom = layout.getLineBottom(line) + input.totalPaddingTop
                input.requestRectangleOnScreen(Rect(x, top, x + 2, bottom), true)
            }
        }
        input.doAfterTextChanged { revealCursor() }
        input.setOnFocusChangeListener { _, focused -> if (focused) revealCursor() }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_content)) { view, insets ->
            val padding = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            revealCursor()
            insets
        }

        findViewById<Button>(R.id.enable_keyboard).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.choose_keyboard).setOnClickListener {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }
    }
}
