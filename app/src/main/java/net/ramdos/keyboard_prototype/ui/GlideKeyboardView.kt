package net.ramdos.keyboard_prototype.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.LayoutInflater
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
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
    var onPunctuation: (() -> Unit)? = null
    var onSpace: (() -> Unit)? = null
    var onEnter: (() -> Unit)? = null
    var onCursorLeft: (() -> Unit)? = null
    var onCursorRight: (() -> Unit)? = null
    var onBackspace: (() -> Unit)? = null
    var onSwitchKeyboard: (() -> Unit)? = null
    var onChooseKeyboard: (() -> Unit)? = null

    private val board: GojuonBoardView
    private val candidateRow: CandidateRowsView
    private val candidateScroll: HorizontalScrollView
    private val hint: TextView
    private val expandedScroll: ScrollView
    private val expandedRows: LinearLayout
    private val expandKey: Button
    private var displayedCandidates: List<String> = emptyList()
    private var candidatesExpanded = false
    private var expandedCandidateWidth = 0

    private var punctuationHeld = false
    private lateinit var punctuationKey: Button
    private val punctuationGuide = PunctuationFlickGuide(
        resources.displayMetrics.density, resources.displayMetrics.scaledDensity,
    )

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
        expandedScroll = findViewById(R.id.expanded_candidates_scroll)
        expandedRows = findViewById(R.id.expanded_candidates_rows)
        expandKey = findViewById(R.id.expand_candidates_key)
        expandKey.setOnClickListener { setCandidatesExpanded(!candidatesExpanded) }
        expandedScroll.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            if (candidatesExpanded && right - left - expandedScroll.paddingLeft - expandedScroll.paddingRight != expandedCandidateWidth) {
                renderExpandedCandidates()
            }
        }
        bindBackspaceKey()
        findViewById<Button>(R.id.switch_keyboard_key).apply {
            setOnClickListener {
                reset()
                onSwitchKeyboard?.invoke()
            }
            setOnLongClickListener {
                reset()
                onChooseKeyboard?.invoke()
                true
            }
        }

        findViewById<Button>(R.id.enter_key).setOnClickListener {
            reset()
            onEnter?.invoke()
        }

        bindPunctuationKey()

        findViewById<Button>(R.id.space_key).setOnClickListener {
            reset()
            onSpace?.invoke()
        }

        findViewById<Button>(R.id.cursor_left_key).setOnClickListener {
            reset()
            onCursorLeft?.invoke()
        }
        findViewById<Button>(R.id.cursor_right_key).setOnClickListener {
            reset()
            onCursorRight?.invoke()
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

    // Use the dominant axis for diagonal flicks; release determines the symbol.
    @SuppressLint("ClickableViewAccessibility")
    private fun bindPunctuationKey() {
        punctuationKey = findViewById(R.id.punctuation_key)
        val threshold = maxOf(
            ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
            16 * resources.displayMetrics.density,
        )
        var startX = 0f
        var startY = 0f
        fun symbol(event: MotionEvent): String {
            val dx = event.x - startX
            val dy = event.y - startY
            if (dx * dx + dy * dy < threshold * threshold) return "、"
            return if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) {
                if (dx < 0) "。" else "？"
            } else {
                if (dy < 0) "！" else "..."
            }
        }
        fun commit(symbol: String) {
            reset()
            onPunctuation?.invoke()
            onCandidateSelected?.invoke(symbol)
        }
        punctuationKey.setOnClickListener { commit("、") }
        for ((label, value) in listOf(
            R.string.punctuation_full_stop to "。",
            R.string.punctuation_exclamation to "！",
            R.string.punctuation_question to "？",
            R.string.punctuation_dots to "...",
        )) {
            ViewCompat.addAccessibilityAction(
                punctuationKey, context.getString(label),
            ) { _, _ -> commit(value); true }
        }
        punctuationKey.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    reset()
                    onPunctuation?.invoke()
                    startX = event.x
                    startY = event.y
                    punctuationHeld = true
                    view.isPressed = true
                    punctuationKey.text = "、"
                    invalidate()
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> if (punctuationHeld) {
                    punctuationKey.text = symbol(event)
                    invalidate()
                }
                MotionEvent.ACTION_UP -> if (punctuationHeld) {
                    val selected = symbol(event)
                    stopPunctuationGesture()
                    if (selected == "、") view.performClick() else commit(selected)
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_POINTER_UP -> stopPunctuationGesture()
            }
            true
        }
    }

    private fun stopPunctuationGesture() {
        punctuationHeld = false
        invalidate()
        if (::punctuationKey.isInitialized) {
            punctuationKey.isPressed = false
            punctuationKey.setText(R.string.punctuation_key)
            punctuationKey.parent?.requestDisallowInterceptTouchEvent(false)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!punctuationHeld) return
        val keyBounds = Rect()
        punctuationKey.getDrawingRect(keyBounds)
        offsetDescendantRectToMyCoords(punctuationKey, keyBounds)
        val density = resources.displayMetrics.density
        val cellSize = minOf(48 * density, (width - paddingLeft - paddingRight) / 3f)
        if (cellSize <= 0) return
        // Keep all five directions above the finger and out of the OS navigation band.
        val left = (keyBounds.exactCenterX() - cellSize * 1.5f).coerceIn(
            paddingLeft.toFloat(), maxOf(paddingLeft.toFloat(), width - paddingRight - cellSize * 3),
        )
        val top = maxOf(paddingTop.toFloat(), keyBounds.top - 6 * density - cellSize * 3)
        punctuationGuide.draw(canvas, left, top, cellSize, punctuationKey.text.toString())
    }

    // Button already implements performClick; touch and accessibility share its click listener.
    @SuppressLint("ClickableViewAccessibility")
    private fun bindBackspaceKey() {
        backspaceKey = findViewById(R.id.backspace_key)
        backspaceKey.setOnClickListener {
            clearPendingInput()
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
        setCandidatesExpanded(false)
        displayedCandidates = candidates.toList()
        expandedRows.removeAllViews()
        expandKey.visibility = if (candidates.isEmpty()) View.GONE else View.VISIBLE
        candidateRow.removeAllViews()
        hint.visibility = if (candidates.isEmpty()) View.VISIBLE else View.GONE
        hint.setText(R.string.candidate_hint)
        candidateScroll.visibility = if (candidates.isEmpty()) View.GONE else View.VISIBLE
        candidates.forEach { candidate ->
            candidateRow.addView(Button(context).apply {
                text = candidate
                textSize = 20f
                gravity = Gravity.CENTER
                includeFontPadding = false
                val density = resources.displayMetrics.density
                minWidth = (48 * density).toInt()
                minimumWidth = minWidth
                minHeight = (48 * density).toInt()
                minimumHeight = minHeight
                setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
                setSingleLine(true)
                isAllCaps = false
                setTextColor(Color.rgb(23, 33, 46))
                backgroundTintList = null
                setBackgroundResource(R.drawable.candidate_background)
                contentDescription = context.getString(R.string.commit_candidate, candidate)
                setOnClickListener { onCandidateSelected?.invoke(candidate) }
            })
        }
        candidateScroll.scrollTo(0, 0)
    }

    private fun setCandidatesExpanded(expanded: Boolean) {
        candidatesExpanded = expanded && displayedCandidates.isNotEmpty()
        board.visibility = if (candidatesExpanded) View.INVISIBLE else View.VISIBLE
        findViewById<View>(R.id.compact_candidates).visibility =
            if (candidatesExpanded) View.INVISIBLE else View.VISIBLE
        expandedScroll.visibility = if (candidatesExpanded) View.VISIBLE else View.GONE
        findViewById<View>(R.id.expanded_candidates_title).visibility = expandedScroll.visibility
        expandKey.setText(if (candidatesExpanded) R.string.collapse_candidates_key else R.string.expand_candidates_key)
        expandKey.contentDescription = context.getString(
            if (candidatesExpanded) R.string.collapse_candidates else R.string.expand_candidates,
        )
        if (candidatesExpanded) {
            expandedScroll.layoutParams = expandedScroll.layoutParams.apply {
                height = resources.getDimensionPixelSize(R.dimen.gojuon_board_height) +
                    (48 * resources.displayMetrics.density).toInt()
            }
            board.clearTrace()
            renderExpandedCandidates()
            expandedScroll.scrollTo(0, 0)
        }
    }

    private fun renderExpandedCandidates() {
        val density = resources.displayMetrics.density
        val margins = expandedScroll.layoutParams as android.view.ViewGroup.MarginLayoutParams
        val viewportWidth = expandedScroll.width.takeIf { it > 0 }
            ?: ((expandedScroll.parent as View).width - margins.leftMargin - margins.rightMargin)
        val availableWidth = viewportWidth - expandedScroll.paddingLeft - expandedScroll.paddingRight
        expandedCandidateWidth = availableWidth
        expandedRows.removeAllViews()
        if (availableWidth <= 0) return // Rebuilt when the visible viewport is laid out.
        var row = LinearLayout(context).apply { orientation = HORIZONTAL }
        var usedWidth = 0
        fun appendRow() {
            expandedRows.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        displayedCandidates.forEach { candidate ->
            val button = Button(context).apply {
                text = candidate
                textSize = 18f
                isAllCaps = false
                gravity = Gravity.CENTER
                includeFontPadding = false
                minWidth = (48 * density).toInt()
                minimumWidth = minWidth
                minHeight = (48 * density).toInt()
                minimumHeight = minHeight
                setPadding((4 * density).toInt(), (2 * density).toInt(),
                    (4 * density).toInt(), (2 * density).toInt())
                setTextColor(Color.rgb(23, 33, 46))
                backgroundTintList = null
                setBackgroundResource(R.drawable.candidate_background)
                contentDescription = context.getString(R.string.commit_candidate, candidate)
                setOnClickListener {
                    setCandidatesExpanded(false)
                    onCandidateSelected?.invoke(candidate)
                }
            }
            // Natural widths pack short candidates tightly; long text wraps within the viewport.
            button.measure(
                View.MeasureSpec.makeMeasureSpec(availableWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val width = button.measuredWidth
            if (row.childCount > 0 && usedWidth + width > availableWidth) {
                appendRow()
                row = LinearLayout(context).apply { orientation = HORIZONTAL }
                usedWidth = 0
            }
            row.addView(button, LayoutParams(width, LayoutParams.WRAP_CONTENT))
            usedWidth += width
        }
        if (row.childCount > 0) appendRow()
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
        stopPunctuationGesture()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != View.VISIBLE) {
            if (::backspaceKey.isInitialized) stopBackspaceRepeat()
            stopPunctuationGesture()
        }
    }

    fun reset() {
        stopBackspaceRepeat()
        clearPendingInput()
    }

    /** Selection updates also follow our own deletions; keep an active key hold. */
    fun clearPendingInput() {
        stopPunctuationGesture()
        board.clearTrace()
        showCandidates(emptyList())
    }

    fun showStatus(message: Int) {
        showCandidates(emptyList())
        hint.setText(message)
    }
}
