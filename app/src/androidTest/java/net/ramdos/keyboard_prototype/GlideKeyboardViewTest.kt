package net.ramdos.keyboard_prototype

import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
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
    fun tapAndGlideUseAnInterchangeableEngineAndCommitOnlyOnCandidateClick() = withKeyboard { keyboard, board ->
        val requests = mutableListOf<GlideTrace>()
        val selections = mutableListOf<String>()
        val engine = CandidateEngine { trace ->
            requests.add(trace)
            listOf("仮候補一", "仮候補二", "仮候補三")
        }
        keyboard.onTraceCompleted = { keyboard.showCandidates(engine.generateCandidates(it)) }
        keyboard.onCandidateSelected = { selections.add(it); keyboard.reset() }
        val row = keyboard.findViewById<LinearLayout>(R.id.candidate_row)

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
        assertEquals(46, requests[1].keys.size)
        assertEquals(listOf("あ", "い"), StubCandidateEngine().generateCandidates(requests[1]))
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
        val row = keyboard.findViewById<LinearLayout>(R.id.candidate_row)
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
        touch(board, MotionEvent.ACTION_DOWN, 0.5f / 10, 0.3f, 100)
        touch(board, MotionEvent.ACTION_UP, 0.5f / 10, 0.3f, 120)
        assertTrue(requests.isEmpty())
        val key = (0 until board.childCount).map { board.getChildAt(it) as TextView }.first { it.text == "ん" }
        key.performClick()
        assertEquals(1, requests.size)
        val point = requests.single().points.single()
        assertEquals("ん", requests.single().keys.single { it.contains(point.x, point.y) }.kana)
    }
}
