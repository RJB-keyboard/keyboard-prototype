package net.ramdos.keyboard_prototype

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import net.ramdos.keyboard_prototype.engine.CandidateEngine
import net.ramdos.keyboard_prototype.engine.StubCandidateEngine
import net.ramdos.keyboard_prototype.ui.GlideKeyboardView

class JapaneseInputMethodService : InputMethodService() {
    // Replace this implementation when a real recognition engine is available.
    private val candidateEngine: CandidateEngine = StubCandidateEngine()
    private var keyboardView: GlideKeyboardView? = null

    override fun onCreateInputView(): View {
        return GlideKeyboardView(this).apply {
            onTraceCompleted = { trace ->
                showCandidates(candidateEngine.generateCandidates(trace))
            }
            onCandidateSelected = { candidate ->
                if (currentInputConnection?.commitText(candidate, 1) == true) {
                    reset()
                }
            }
            keyboardView = this
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        keyboardView?.reset()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        keyboardView?.reset()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        keyboardView?.reset()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        keyboardView?.reset()
        super.onFinishInput()
    }

    override fun onDestroy() {
        keyboardView = null
        super.onDestroy()
    }

    // Keep the editor visible, including when the device is in landscape.
    override fun onEvaluateFullscreenMode(): Boolean = false
}
