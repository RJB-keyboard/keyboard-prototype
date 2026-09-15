package net.ramdos.keyboard_prototype

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ramdos.keyboard_prototype.engine.GlideTrace
import net.ramdos.keyboard_prototype.ui.GojuonBoardView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GojuonTrailTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun withBoard(test: (GojuonBoardView, Bitmap) -> Unit) {
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Keyboardprototype)
            val board = GojuonBoardView(context)
            board.measure(
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
            )
            board.layout(0, 0, 1000, 500)
            val bitmap = Bitmap.createBitmap(1000, 500, Bitmap.Config.ARGB_8888)
            try {
                test(board, bitmap)
            } finally {
                board.clearTrace()
                bitmap.recycle()
            }
        }
    }

    private fun touch(board: GojuonBoardView, action: Int, x: Float, start: Long, time: Long) {
        // Keep the horizontal trail below the first row's glyphs.
        val event = MotionEvent.obtain(start, time, action, x * board.width, 85f, 0)
        try {
            assertTrue(board.dispatchTouchEvent(event))
        } finally {
            event.recycle()
        }
    }

    private fun render(board: GojuonBoardView, bitmap: Bitmap) {
        bitmap.eraseColor(Color.WHITE)
        board.draw(Canvas(bitmap))
    }

    private fun trailThickness(bitmap: Bitmap, x: Int): Int = (60..99).count { y ->
        val color = bitmap.getPixel(x, y)
        // Exclude white keys, dark glyphs, and the pale blue key highlight.
        Color.blue(color) - Color.red(color) > 80
    }

    @Test
    fun olderTrailIsThinnerAndExpiresWhileHeldWithoutLosingDecoderPoints() = withBoard { board, bitmap ->
        var completed: GlideTrace? = null
        board.onTraceCompleted = { completed = it }
        val now = SystemClock.uptimeMillis()
        val start = now - 400
        touch(board, MotionEvent.ACTION_DOWN, 0.95f, start, start)
        touch(board, MotionEvent.ACTION_MOVE, 0.05f, start, now)
        render(board, bitmap)
        val olderWidth = trailThickness(bitmap, 750)
        val newerWidth = trailThickness(bitmap, 250)
        assertTrue("The older trail should still be visible", olderWidth > 0)
        assertTrue("The newer trail should be thicker: $olderWidth / $newerWidth", newerWidth > olderWidth)

        // No further touch events: the same held gesture must lose its visible trail.
        SystemClock.sleep(500)
        render(board, bitmap)
        assertEquals(0, trailThickness(bitmap, 750))
        assertEquals(0, trailThickness(bitmap, 250))
        assertNull(completed)
        touch(board, MotionEvent.ACTION_UP, 0.05f, start, SystemClock.uptimeMillis())
        val points = requireNotNull(completed).points
        assertEquals(3, points.size)
        assertEquals(0.95f, points.first().x, 0.0001f)
        assertEquals(400L, points[1].elapsedMillis)
    }

    @Test
    fun expiredPrefixDisappearsAndResetStartsAFreshTrail() = withBoard { board, bitmap ->
        val now = SystemClock.uptimeMillis()
        val start = now - 900
        touch(board, MotionEvent.ACTION_DOWN, 0.95f, start, start)
        touch(board, MotionEvent.ACTION_MOVE, 0.5f, start, now - 200)
        touch(board, MotionEvent.ACTION_MOVE, 0.05f, start, now)
        render(board, bitmap)
        assertEquals(0, trailThickness(bitmap, 850))
        assertTrue(trailThickness(bitmap, 250) > 0)

        touch(board, MotionEvent.ACTION_CANCEL, 0.05f, start, now)
        render(board, bitmap)
        assertEquals(0, trailThickness(bitmap, 250))

        val nextStart = SystemClock.uptimeMillis()
        touch(board, MotionEvent.ACTION_DOWN, 0.95f, nextStart, nextStart)
        touch(board, MotionEvent.ACTION_MOVE, 0.05f, nextStart, nextStart)
        render(board, bitmap)
        assertTrue(trailThickness(bitmap, 850) > 0)
        assertTrue(trailThickness(bitmap, 250) > 0)
    }
}
