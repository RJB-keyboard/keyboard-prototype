package net.ramdos.keyboard_prototype.ui

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import net.ramdos.keyboard_prototype.R
import net.ramdos.keyboard_prototype.engine.GlideTrace

/** Frontend API: emit a trace, display supplied candidates, emit a selection. */
class GlideKeyboardView(context: Context) : LinearLayout(context) {
    var onTraceCompleted: ((GlideTrace) -> Unit)? = null
    var onCandidateSelected: ((String) -> Unit)? = null
    var onGestureStarted: (() -> Unit)? = null
    var onGestureCancelled: (() -> Unit)? = null

    private val board: GojuonBoardView
    private val candidateRow: LinearLayout
    private val candidateScroll: HorizontalScrollView
    private val hint: TextView

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.rgb(232, 236, 241))
        LayoutInflater.from(context).inflate(R.layout.keyboard_view, this, true)
        board = findViewById(R.id.gojuon_board)
        candidateRow = findViewById(R.id.candidate_row)
        candidateScroll = findViewById(R.id.candidate_scroll)
        hint = findViewById(R.id.candidate_hint)

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

    fun showCandidates(candidates: List<String>) {
        candidateRow.removeAllViews()
        hint.visibility = if (candidates.isEmpty()) View.VISIBLE else View.GONE
        hint.setText(R.string.candidate_hint)
        candidateScroll.visibility = if (candidates.isEmpty()) View.GONE else View.VISIBLE
        candidates.forEach { candidate ->
            candidateRow.addView(Button(context).apply {
                text = candidate
                textSize = 22f
                isAllCaps = false
                setTextColor(Color.rgb(23, 33, 46))
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                contentDescription = context.getString(R.string.commit_candidate, candidate)
                setOnClickListener { onCandidateSelected?.invoke(candidate) }
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
        candidateScroll.scrollTo(0, 0)
    }

    fun reset() {
        board.clearTrace()
        showCandidates(emptyList())
    }

    fun showStatus(message: Int) {
        showCandidates(emptyList())
        hint.setText(message)
    }
}
