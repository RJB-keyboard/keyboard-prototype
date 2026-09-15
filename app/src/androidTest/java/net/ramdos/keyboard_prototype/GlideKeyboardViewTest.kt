package net.ramdos.keyboard_prototype

import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ramdos.keyboard_prototype.engine.CandidateEngine
import net.ramdos.keyboard_prototype.engine.GlideTrace
import net.ramdos.keyboard_prototype.engine.StubCandidateEngine
import net.ramdos.keyboard_prototype.ui.GlideKeyboardView
import net.ramdos.keyboard_prototype.ui.GojuonBoardView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlideKeyboardViewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun withKeyboard(test: (GlideKeyboardView, GojuonBoardView) -> Unit) {
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Keyboardprototype)
            val keyboard = GlideKeyboardView(context)
            keyboard.measure(
                View.MeasureSpec.makeMeasureSpec(1100, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST),
            )
            keyboard.layout(0, 0, keyboard.measuredWidth, keyboard.measuredHeight)
            test(keyboard, keyboard.findViewById(R.id.gojuon_board))
        }
    }

    private fun touch(board: GojuonBoardView, action: Int, x: Float, y: Float, time: Long) {
        val event = MotionEvent.obtain(100, time, action, x * board.width, y * board.height, 0)
        try {
            assertTrue(board.dispatchTouchEvent(event))
        } finally {
            event.recycle()
        }
    }

    @Test
    fun punctuationTapAndFlickCommitOnReleaseAndCanBeCancelled() = withKeyboard { keyboard, _ ->
        val key = keyboard.findViewById<Button>(R.id.punctuation_key)
        val selections = mutableListOf<String>()
        keyboard.onCandidateSelected = { selections.add(it); keyboard.reset() }
        val distance = 40 * keyboard.resources.displayMetrics.density
        fun send(action: Int, dx: Float = 0f, dy: Float = 0f) {
            val event = MotionEvent.obtain(100, 120, action, key.width / 2f + dx, key.height / 2f + dy, 0)
            try { assertTrue(key.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
        for ((dx, dy) in listOf(0f to 0f, distance to 0f, -distance to 0f, 0f to distance, 0f to -distance)) {
            val before = selections.size
            keyboard.showCandidates(listOf("未確定"))
            send(MotionEvent.ACTION_DOWN)
            send(MotionEvent.ACTION_MOVE, dx, dy)
            assertEquals(before, selections.size)
            assertEquals(0, keyboard.findViewById<GridLayout>(R.id.candidate_row).childCount)
            assertEquals(if (dx == 0f && dy == 0f) "、" else "。", key.text.toString())
            send(MotionEvent.ACTION_UP, dx, dy)
            assertEquals(before + 1, selections.size)
            assertFalse(key.isPressed)
        }
        assertEquals(listOf("、", "。", "。", "。", "。"), selections)
        for (cancel in listOf<() -> Unit>(
            { send(MotionEvent.ACTION_CANCEL) },
            { send(MotionEvent.ACTION_POINTER_DOWN) },
            { keyboard.reset() },
        )) {
            send(MotionEvent.ACTION_DOWN)
            send(MotionEvent.ACTION_MOVE, distance)
            cancel()
            send(MotionEvent.ACTION_UP, distance)
        }
        assertEquals(5, selections.size)
        send(MotionEvent.ACTION_DOWN)
        send(MotionEvent.ACTION_MOVE, distance)
        send(MotionEvent.ACTION_UP, 1f, 1f)
        assertEquals("、", selections.last())
        key.performClick()
        assertEquals(7, selections.size)
        assertEquals("、", selections.last())
    }

    @Test
    fun tapAndGlideUseAnInterchangeableEngineAndCommitOnlyOnCandidateClick() = withKeyboard { keyboard, board ->
        val requests = mutableListOf<GlideTrace>()
        val selections = mutableListOf<String>()
        val engine = CandidateEngine { trace ->
            requests.add(trace)
            listOf("仮候補一", "仮候補二", "仮候補三")
        }
        keyboard.onTraceCompleted = { keyboard.showCandidates(engine.generateCandidates(it)) }
        keyboard.onCandidateSelected = { selections.add(it); keyboard.reset() }
        val row = keyboard.findViewById<GridLayout>(R.id.candidate_row)

        touch(board, MotionEvent.ACTION_DOWN, 0.95f, 0.1f, 100)
        assertTrue(requests.isEmpty())
        touch(board, MotionEvent.ACTION_UP, 0.95f, 0.1f, 120)
        assertEquals(1, requests.size)
        assertEquals(3, row.childCount)
        assertTrue(selections.isEmpty())
        assertEquals("仮候補二", (row.getChildAt(1) as Button).text.toString())
        row.getChildAt(1).performClick()
        assertEquals(listOf("仮候補二"), selections)
        assertEquals(0, row.childCount)

        // A second gesture produces a new snapshot without changing the first one.
        touch(board, MotionEvent.ACTION_DOWN, 0.95f, 0.1f, 200)
        touch(board, MotionEvent.ACTION_MOVE, 0.85f, 0.3f, 220)
        assertEquals(1, requests.size)
        touch(board, MotionEvent.ACTION_UP, 0.75f, 0.5f, 240)
        assertEquals(2, requests.size)
        assertEquals(2, requests[0].points.size)
        assertEquals(3, requests[1].points.size)
        assertEquals(47, requests[1].keys.size)
        assertEquals(listOf("あ", "い"), StubCandidateEngine().generateCandidates(requests[1]))
    }

    @Test
    fun keyboardSwitchAndPickerCancelTraceWithoutCommittingCandidates() = withKeyboard { keyboard, board ->
        var switches = 0
        var pickers = 0
        var commits = 0
        var traces = 0
        keyboard.onSwitchKeyboard = { switches++ }
        keyboard.onChooseKeyboard = { pickers++ }
        keyboard.onCandidateSelected = { commits++ }
        keyboard.onTraceCompleted = { traces++ }
        val key = keyboard.findViewById<Button>(R.id.switch_keyboard_key)
        for (longPress in listOf(false, true)) {
            touch(board, MotionEvent.ACTION_DOWN, 0.95f, 0.1f, 100)
            keyboard.showCandidates(listOf("未確定"))
            if (longPress) assertTrue(key.performLongClick()) else key.performClick()
            assertEquals(0, keyboard.findViewById<GridLayout>(R.id.candidate_row).childCount)
            touch(board, MotionEvent.ACTION_UP, 0.95f, 0.1f, 120)
        }
        assertEquals(1, switches)
        assertEquals(1, pickers)
        assertEquals(0, commits)
        assertEquals(0, traces)
        assertTrue(key.bottom <= keyboard.findViewById<View>(R.id.backspace_key).top)
    }

    @Test
    fun backspaceClearsCandidatesAndCancelsAnActiveTrace() = withKeyboard { keyboard, board ->
        var deletions = 0
        var traces = 0
        keyboard.onBackspace = { deletions++ }
        keyboard.onTraceCompleted = { traces++ }
        val key = keyboard.findViewById<Button>(R.id.backspace_key)
        keyboard.showCandidates(listOf("あ", "い"))
        key.performClick()
        assertEquals(1, deletions)
        assertEquals(0, keyboard.findViewById<GridLayout>(R.id.candidate_row).childCount)
        assertEquals(View.VISIBLE, keyboard.findViewById<TextView>(R.id.candidate_hint).visibility)

        touch(board, MotionEvent.ACTION_DOWN, 0.95f, 0.1f, 100)
        key.performClick()
        touch(board, MotionEvent.ACTION_UP, 0.95f, 0.1f, 120)
        assertEquals(2, deletions)
        assertEquals(0, traces)
    }

    @Test
    fun spaceClearsCandidatesAndCancelsAnActiveTrace() = withKeyboard { keyboard, board ->
        var spaces = 0
        var traces = 0
        keyboard.onSpace = { spaces++ }
        keyboard.onTraceCompleted = { traces++ }
        val key = keyboard.findViewById<Button>(R.id.space_key)
        keyboard.showCandidates(listOf("あ", "い"))
        key.performClick()
        assertEquals(1, spaces)
        assertEquals(0, keyboard.findViewById<GridLayout>(R.id.candidate_row).childCount)
        assertEquals(View.VISIBLE, keyboard.findViewById<TextView>(R.id.candidate_hint).visibility)

        touch(board, MotionEvent.ACTION_DOWN, 0.95f, 0.1f, 100)
        key.performClick()
        touch(board, MotionEvent.ACTION_UP, 0.95f, 0.1f, 120)
        assertEquals(2, spaces)
        assertEquals(0, traces)
    }

    @Test
    fun enterClearsCandidatesAndCancelsTraceFromTheBottomRow() = withKeyboard { keyboard, board ->
        var enters = 0
        var traces = 0
        keyboard.onEnter = { enters++ }
        keyboard.onTraceCompleted = { traces++ }
        val key = keyboard.findViewById<Button>(R.id.enter_key)
        val left = keyboard.findViewById<Button>(R.id.cursor_left_key)
        val right = keyboard.findViewById<Button>(R.id.cursor_right_key)
        val keyBounds = android.graphics.Rect()
        key.getDrawingRect(keyBounds)
        keyboard.offsetDescendantRectToMyCoords(key, keyBounds)
        assertTrue(keyBounds.top >= board.bottom)
        val space = keyboard.findViewById<Button>(R.id.space_key)
        val punctuation = keyboard.findViewById<Button>(R.id.punctuation_key)
        assertTrue(left.right <= punctuation.left)
        assertTrue(punctuation.right <= space.left)
        assertTrue(space.right <= key.left)
        assertTrue(key.right <= right.left)
        assertTrue(kotlin.math.abs(left.width - right.width) <= 1)
        assertTrue(kotlin.math.abs(space.width - 2 * left.width) <= 2)
        assertTrue(kotlin.math.abs(key.width - space.width) <= 1)
        assertEquals((48 * keyboard.resources.displayMetrics.density).toInt(), key.height)
        keyboard.setEnterLabel("検索")
        assertEquals("検索", key.text.toString())
        assertEquals("検索", key.contentDescription.toString())
        keyboard.showCandidates(listOf("あ", "い"))
        key.performClick()
        assertEquals(1, enters)
        assertEquals(0, keyboard.findViewById<GridLayout>(R.id.candidate_row).childCount)
        touch(board, MotionEvent.ACTION_DOWN, 0.95f, 0.1f, 100)
        key.performClick()
        touch(board, MotionEvent.ACTION_UP, 0.95f, 0.1f, 120)
        assertEquals(2, enters)
        assertEquals(0, traces)
    }

    @Test
    fun cursorKeysClearCandidatesAndCancelActiveTraces() = withKeyboard { keyboard, board ->
        val moves = mutableListOf<String>()
        var traces = 0
        keyboard.onCursorLeft = { moves.add("left") }
        keyboard.onCursorRight = { moves.add("right") }
        keyboard.onTraceCompleted = { traces++ }
        for (id in listOf(R.id.cursor_left_key, R.id.cursor_right_key)) {
            keyboard.showCandidates(listOf("候補"))
            keyboard.findViewById<Button>(id).performClick()
            assertEquals(0, keyboard.findViewById<GridLayout>(R.id.candidate_row).childCount)
            touch(board, MotionEvent.ACTION_DOWN, 0.95f, 0.1f, 100)
            keyboard.findViewById<Button>(id).performClick()
            touch(board, MotionEvent.ACTION_UP, 0.95f, 0.1f, 120)
        }
        assertEquals(listOf("left", "left", "right", "right"), moves)
        assertEquals(0, traces)
    }

    @Test
    fun deleteStaysAboveBoardAndOutsideTwoCandidateRows() = withKeyboard { keyboard, board ->
        keyboard.showCandidates(listOf("一", "二", "長い候補の表示", "四"))
        keyboard.measure(View.MeasureSpec.makeMeasureSpec(1100, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        keyboard.layout(0, 0, keyboard.measuredWidth, keyboard.measuredHeight)
        fun bounds(id: Int): android.graphics.Rect {
            val view = keyboard.findViewById<View>(id)
            return android.graphics.Rect().also {
                view.getDrawingRect(it)
                keyboard.offsetDescendantRectToMyCoords(view, it)
            }
        }
        val delete = bounds(R.id.backspace_key)
        val candidates = bounds(R.id.candidate_scroll)
        assertTrue(delete.bottom <= board.top)
        assertEquals(candidates.bottom, delete.bottom)
        assertTrue(candidates.right <= delete.left)
        val row = keyboard.findViewById<GridLayout>(R.id.candidate_row)
        assertEquals(row.getChildAt(0).top, row.getChildAt(2).top)
        assertEquals(row.getChildAt(1).top, row.getChildAt(3).top)
        assertTrue(row.getChildAt(0).bottom <= row.getChildAt(1).top)
        keyboard.setEnterLabel(keyboard.context.getString(R.string.enter_newline))
        val enter = keyboard.findViewById<Button>(R.id.enter_key)
        assertEquals("改行", enter.text.toString())
        assertEquals("改行", enter.contentDescription.toString())
    }

    @Test
    fun navigationInsetsReserveSpaceWithoutAccumulatingPadding() = withKeyboard { keyboard, _ ->
        val insets = androidx.core.view.WindowInsetsCompat.Builder()
            .setInsets(
                androidx.core.view.WindowInsetsCompat.Type.navigationBars(),
                androidx.core.graphics.Insets.of(12, 0, 8, 64),
            ).build()
        repeat(2) {
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(keyboard, insets)
            assertEquals(12, keyboard.paddingLeft)
            assertEquals(8, keyboard.paddingRight)
            assertEquals(maxOf(64, (48 * keyboard.resources.displayMetrics.density).toInt()) +
                (8 * keyboard.resources.displayMetrics.density).toInt(), keyboard.paddingBottom)
        }
        val cleared = androidx.core.view.WindowInsetsCompat.Builder()
            .setInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars(), androidx.core.graphics.Insets.NONE)
            .build()
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(keyboard, cleared)
        assertEquals((48 * keyboard.resources.displayMetrics.density).toInt() +
            (8 * keyboard.resources.displayMetrics.density).toInt(), keyboard.paddingBottom)
        assertEquals(0, keyboard.paddingLeft)
        assertEquals(0, keyboard.paddingRight)
    }

    @Test
    fun allKeysStayOutsideCaptionAndSideNavigationAreasInBothOrientations() = withKeyboard { keyboard, _ ->
        val density = keyboard.resources.displayMetrics.density
        keyboard.showCandidates(listOf("あ", "い"))
        val captionHeight = (80 * density).toInt()
        for (width in listOf(1100, 2200)) {
            val sideWidth = if (width == 2200) 100 else 0
            val insets = androidx.core.view.WindowInsetsCompat.Builder()
                .setInsetsIgnoringVisibility(androidx.core.view.WindowInsetsCompat.Type.captionBar(),
                    androidx.core.graphics.Insets.of(0, 0, 0, captionHeight))
                .setInsetsIgnoringVisibility(androidx.core.view.WindowInsetsCompat.Type.navigationBars(),
                    androidx.core.graphics.Insets.of(0, 0, sideWidth, 24))
                .build()
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(keyboard, insets)
            keyboard.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            keyboard.layout(0, 0, width, keyboard.measuredHeight)
            fun checkKeys(view: View) {
                if (view.isClickable && view.visibility == View.VISIBLE) {
                    val bounds = android.graphics.Rect()
                    view.getDrawingRect(bounds)
                    keyboard.offsetDescendantRectToMyCoords(view, bounds)
                    assertTrue("Key overlaps system band: $bounds",
                        bounds.bottom <= keyboard.height - captionHeight - (8 * density).toInt())
                    assertTrue("Key overlaps side navigation: $bounds", bounds.right <= width - sideWidth)
                }
                if (view is android.view.ViewGroup) {
                    for (i in 0 until view.childCount) checkKeys(view.getChildAt(i))
                }
            }
            checkKeys(keyboard)
        }
    }

    @Test
    fun longVowelBelowWaWoNSubmitsTrace() = withKeyboard { keyboard, board ->
        val requests = mutableListOf<GlideTrace>()
        keyboard.onTraceCompleted = { requests.add(it) }
        for ((index, y) in listOf(0.9f).withIndex()) {
            touch(board, MotionEvent.ACTION_DOWN, 0.05f, y, 100L + index * 100)
            touch(board, MotionEvent.ACTION_UP, 0.05f, y, 120L + index * 100)
        }
        assertEquals(listOf("ー"), requests.map { trace ->
            val point = trace.points.first()
            trace.keys.single { it.contains(point.x, point.y) }.kana
        })
    }

    @Test
    fun batchedHistoricalPointsKeepTheirCoordinatesAndTime() = withKeyboard { keyboard, board ->
        var trace: GlideTrace? = null
        keyboard.onTraceCompleted = { trace = it }
        touch(board, MotionEvent.ACTION_DOWN, 0.95f, 0.1f, 100)
        val move = MotionEvent.obtain(100, 110, MotionEvent.ACTION_MOVE, board.width * 0.85f, board.height * 0.3f, 0)
        move.addBatch(120, board.width * 0.75f, board.height * 0.5f, 1f, 1f, 0)
        try {
            board.dispatchTouchEvent(move)
        } finally {
            move.recycle()
        }
        touch(board, MotionEvent.ACTION_UP, 0.65f, 0.7f, 130)
        val points = requireNotNull(trace).points
        assertEquals(listOf(0L, 10L, 20L, 30L), points.map { it.elapsedMillis })
        assertEquals(0.85f, points[1].x, 0.0001f)
        assertEquals(0.5f, points[2].y, 0.0001f)
    }

    @Test
    fun cancellationOutsideReleaseAndResetDoNotProduceCandidates() = withKeyboard { keyboard, board ->
        var completed = 0
        keyboard.onTraceCompleted = { completed++; keyboard.showCandidates(listOf("あ", "い")) }
        val row = keyboard.findViewById<GridLayout>(R.id.candidate_row)
        touch(board, MotionEvent.ACTION_DOWN, 0.95f, 0.1f, 100)
        touch(board, MotionEvent.ACTION_UP, 0.95f, 0.1f, 120)
        assertEquals(2, row.childCount)

        touch(board, MotionEvent.ACTION_DOWN, 0.85f, 0.1f, 200)
        assertEquals(0, row.childCount)
        touch(board, MotionEvent.ACTION_CANCEL, 0.85f, 0.1f, 220)
        touch(board, MotionEvent.ACTION_UP, 0.85f, 0.1f, 240)
        assertEquals(1, completed)

        touch(board, MotionEvent.ACTION_DOWN, 0.95f, 0.1f, 300)
        touch(board, MotionEvent.ACTION_UP, 0.95f, -0.1f, 320)
        assertEquals(1, completed)

        touch(board, MotionEvent.ACTION_DOWN, 0.95f, 0.1f, 400)
        keyboard.reset()
        touch(board, MotionEvent.ACTION_UP, 0.95f, 0.1f, 420)
        assertEquals(1, completed)
        assertEquals(0, row.childCount)
    }

    @Test
    fun secondFingerCancelsUntilANewGestureBegins() = withKeyboard { keyboard, board ->
        var completed = 0
        keyboard.onTraceCompleted = { completed++ }
        touch(board, MotionEvent.ACTION_DOWN, 0.95f, 0.1f, 100)
        val properties = Array(2) { id -> MotionEvent.PointerProperties().apply { this.id = id } }
        val coordinates = Array(2) { index ->
            MotionEvent.PointerCoords().apply { x = board.width * (0.95f - index * 0.1f); y = board.height * 0.1f }
        }
        val secondFinger = MotionEvent.obtain(
            100, 110, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, properties, coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0,
        )
        try {
            board.dispatchTouchEvent(secondFinger)
        } finally {
            secondFinger.recycle()
        }
        touch(board, MotionEvent.ACTION_UP, 0.95f, 0.1f, 120)
        assertEquals(0, completed)
        touch(board, MotionEvent.ACTION_DOWN, 0.95f, 0.1f, 200)
        touch(board, MotionEvent.ACTION_UP, 0.95f, 0.1f, 220)
        assertEquals(1, completed)
    }

    @Test
    fun blanksAreIgnoredAndAccessibleKeyClicksSubmitATap() = withKeyboard { keyboard, board ->
        val requests = mutableListOf<GlideTrace>()
        keyboard.onTraceCompleted = { requests.add(it) }
        touch(board, MotionEvent.ACTION_DOWN, 0.5f / 10, 0.7f, 100)
        touch(board, MotionEvent.ACTION_UP, 0.5f / 10, 0.7f, 120)
        assertTrue(requests.isEmpty())
        val key = (0 until board.childCount).map { board.getChildAt(it) as TextView }.first { it.text == "ん" }
        key.performClick()
        assertEquals(1, requests.size)
        val point = requests.single().points.single()
        assertEquals("ん", requests.single().keys.single { it.contains(point.x, point.y) }.kana)
    }
}
