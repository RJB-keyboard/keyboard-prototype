package net.ramdos.keyboard_prototype

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import net.ramdos.keyboard_prototype.engine.CandidateSession
import net.ramdos.keyboard_prototype.engine.GlideCandidateEngine
import net.ramdos.keyboard_prototype.engine.conversion.SumireKanaKanjiConverter
import net.ramdos.keyboard_prototype.engine.lm.OnnxHiraganaLanguageModel
import net.ramdos.keyboard_prototype.ui.GlideKeyboardView

class JapaneseInputMethodService : InputMethodService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var keyboardView: GlideKeyboardView? = null
    private var allowContext = true
    private val candidates by lazy {
        CandidateSession(
            createEngine = {
                try {
                    val model = OnnxHiraganaLanguageModel.open(applicationContext)
                    try {
                        GlideCandidateEngine(model, SumireKanaKanjiConverter.open(applicationContext))
                    } catch (failure: Throwable) {
                        model.close()
                        throw failure
                    }
                } catch (failure: Exception) {
                    throw EngineInitializationException(failure)
                }
            },
            dispatch = { callback -> mainHandler.post { callback() } },
        )
    }

    override fun onCreateInputView(): View {
        candidates.invalidate()
        return GlideKeyboardView(this).apply {
            onGestureStarted = { candidates.invalidate() }
            onGestureCancelled = { candidates.invalidate() }
            onTraceCompleted = { trace ->
                val connection = currentInputConnection
                if (connection != null) {
                    val context = if (allowContext) connection.getTextBeforeCursor(256, 0)?.toString().orEmpty() else ""
                    showStatus(R.string.engine_working)
                    candidates.request(trace, context) { result ->
                        if (keyboardView === this && currentInputConnection === connection) {
                            // Reject edits that replaced text without moving the cursor, too.
                            val latest = if (allowContext) connection.getTextBeforeCursor(256, 0)?.toString().orEmpty() else ""
                            if (latest != context) {
                                reset()
                            } else {
                                result.fold(
                                    onSuccess = { showCandidates(it) },
                                    onFailure = { failure ->
                                        // Never log input text or trace coordinates.
                                        Log.e("GlideEngine", "Candidate generation failed: ${failure.javaClass.simpleName}")
                                        showStatus(if (failure is EngineInitializationException || failure is LinkageError) {
                                            R.string.engine_unavailable
                                        } else R.string.engine_failed)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            onCandidateSelected = { candidate ->
                candidates.invalidate()
                if (currentInputConnection?.commitText(candidate, 1) == true) {
                    reset()
                }
            }
            onBackspace = { currentInputConnection?.backspace() }
            keyboardView = this
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val inputType = attribute?.inputType ?: 0
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val password = (inputClass == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )) || (inputClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val noPersonalization = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            ((attribute?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        allowContext = !password && !noPersonalization
        resetSession()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        resetSession()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (oldSelStart != newSelStart || oldSelEnd != newSelEnd) resetSession()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        resetSession()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        resetSession()
        super.onFinishInput()
    }

    override fun onDestroy() {
        keyboardView = null
        candidates.close()
        super.onDestroy()
    }

    private fun resetSession() {
        candidates.invalidate()
        keyboardView?.reset()
    }

    private class EngineInitializationException(cause: Throwable) : Exception("Input engine initialization failed", cause)

    // Keep the editor visible, including when the device is in landscape.
    override fun onEvaluateFullscreenMode(): Boolean = false
}
