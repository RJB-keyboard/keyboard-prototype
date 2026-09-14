package net.ramdos.keyboard_prototype.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import net.ramdos.keyboard_prototype.R
import net.ramdos.keyboard_prototype.engine.GlideTrace

/** Frontend API: emit a trace, display supplied candidates, emit a selection. */
class GlideKeyboardView(context: Context) : LinearLayout(context) {
    var onTraceCompleted: ((GlideTrace) -> Unit)? = null
    var onCandidateSelected: ((String) -> Unit)? = null
    var onGestureStarted: (() -> Unit)? = null
    var onGestureCancelled: (() -> Unit)? = null
    var onSpace: (() -> Unit)? = null
    var onEnter: (() -> Unit)? = null
    var onBackspace: (() -> Unit)? = null

    private val board: GojuonBoardView
    private val candidateRow: GridLayout
    private val candidateScroll: HorizontalScrollView
    private val hint: TextView

    private val repeatHandler = Handler(Looper.getMainLooper())
    private var backspaceHeld = false
    private lateinit var backspaceKey: Button
    private val repeatBackspace = object : Runnable {
        override fun run() {
            if (!backspaceHeld) return
            backspaceKey.performClick()
            if (backspaceHeld) repeatHandler.postDelayed(this, 80L)
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.rgb(232, 236, 241))
        // The IME switcher uses captionBar insets on recent Android versions.
        // Keep a dedicated system-control band even before insets arrive.
        val minimumSystemBand = (48 * resources.displayMetrics.density).toInt()
        val systemKeyGap = (8 * resources.displayMetrics.density).toInt()
        setPadding(0, 0, 0, minimumSystemBand + systemKeyGap)
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val systemTypes = WindowInsetsCompat.Type.navigationBars() or
                WindowInsetsCompat.Type.captionBar() or WindowInsetsCompat.Type.displayCutout()
            val safeArea = androidx.core.graphics.Insets.max(
                insets.getInsets(systemTypes), insets.getInsetsIgnoringVisibility(systemTypes),
            )
            view.setPadding(
                safeArea.left, safeArea.top, safeArea.right,
                maxOf(safeArea.bottom, minimumSystemBand) + systemKeyGap,
            )
            insets
        }
        LayoutInflater.from(context).inflate(R.layout.keyboard_view, this, true)
        board = findViewById(R.id.gojuon_board)
        candidateRow = findViewById(R.id.candidate_row)
        candidateScroll = findViewById(R.id.candidate_scroll)
        hint = findViewById(R.id.candidate_hint)
        bindBackspaceKey()

        findViewById<Button>(R.id.enter_key).setOnClickListener {
            reset()
            onEnter?.invoke()
        }

        findViewById<Button>(R.id.space_key).setOnClickListener {
            reset()
            onSpace?.invoke()
        }

        board.onGestureStarted = {
            onGestureStarted?.invoke()
            showCandidates(emptyList())
            hint.setText(R.string.glide_in_progress)
        }
        board.onGestureCancelled = {
            onGestureCancelled?.invoke()
            showCandidates(emptyList())
        }
        board.onTraceCompleted = { onTraceCompleted?.invoke(it) }
    }

    // Button already implements performClick; touch and accessibility share its click listener.
    @SuppressLint("ClickableViewAccessibility")
    private fun bindBackspaceKey() {
        backspaceKey = findViewById(R.id.backspace_key)
        backspaceKey.setOnClickListener {
            board.clearTrace()
            showCandidates(emptyList())
            onBackspace?.invoke()
        }
        backspaceKey.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    stopBackspaceRepeat()
                    backspaceHeld = true
                    view.isPressed = true
                    view.performClick()
                    if (backspaceHeld) repeatHandler.postDelayed(repeatBackspace, 400L)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.x < 0 || event.x >= view.width ||
                        event.y < 0 || event.y >= view.height) stopBackspaceRepeat()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL,
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> stopBackspaceRepeat()
            }
            true
        }
    }

    fun showCandidates(candidates: List<String>) {
        candidateRow.removeAllViews()
        hint.visibility = if (candidates.isEmpty()) View.VISIBLE else View.GONE
        hint.setText(R.string.candidate_hint)
        candidateScroll.visibility = if (candidates.isEmpty()) View.GONE else View.VISIBLE
        candidates.forEachIndexed { index, candidate ->
            candidateRow.addView(Button(context).apply {
                text = candidate
                textSize = 22f
                isAllCaps = false
                setTextColor(Color.rgb(23, 33, 46))
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                contentDescription = context.getString(R.string.commit_candidate, candidate)
                setOnClickListener { onCandidateSelected?.invoke(candidate) }
            }, GridLayout.LayoutParams(
                GridLayout.spec(index % 2),
                GridLayout.spec(index / 2, GridLayout.FILL),
            ).apply {
                width = LayoutParams.WRAP_CONTENT
                height = (48 * resources.displayMetrics.density).toInt()
            })
        }
        candidateScroll.scrollTo(0, 0)
    }

    fun setEnterLabel(label: CharSequence) {
        findViewById<Button>(R.id.enter_key).apply {
            text = label
            contentDescription = label
        }
    }

    private fun stopBackspaceRepeat() {
        backspaceHeld = false
        repeatHandler.removeCallbacks(repeatBackspace)
        backspaceKey.isPressed = false
    }

    override fun onDetachedFromWindow() {
        stopBackspaceRepeat()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != View.VISIBLE && ::backspaceKey.isInitialized) stopBackspaceRepeat()
    }

    fun reset() {
        stopBackspaceRepeat()
        board.clearTrace()
        showCandidates(emptyList())
    }

    fun showStatus(message: Int) {
        showCandidates(emptyList())
        hint.setText(message)
    }
}
