package net.ramdos.keyboard_prototype

import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ramdos.keyboard_prototype.ui.GlideKeyboardView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackspaceRepeatTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var keyboard: GlideKeyboardView
    private lateinit var key: Button
    private var deletions = 0
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun setup() = main {
        keyboard = GlideKeyboardView(ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Keyboardprototype))
        keyboard.measure(View.MeasureSpec.makeMeasureSpec(1100, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST))
        keyboard.layout(0, 0, keyboard.measuredWidth, keyboard.measuredHeight)
        key = keyboard.findViewById(R.id.backspace_key)
        keyboard.onBackspace = { deletions++ }
    }
    private fun touch(action: Int, outside: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action,
            if (outside) -1f else key.width / 2f, key.height / 2f, 0)
        try { assertTrue(key.dispatchTouchEvent(event)) } finally { event.recycle() }
    }
    @Test fun quickTapAndAccessibleClickDeleteOnceEach() {
        setup()
        try {
            main {
                touch(MotionEvent.ACTION_DOWN)
                assertEquals(1, deletions)
                touch(MotionEvent.ACTION_UP)
                assertEquals(1, deletions)
                key.performClick()
                assertEquals(2, deletions)
            }
            SystemClock.sleep(550)
            main { assertEquals(2, deletions) }
        } finally { main { keyboard.reset() } }
    }
    @Test fun holdRepeatsAndReleaseDoesNotDeleteAgain() {
        setup()
        try {
            main { touch(MotionEvent.ACTION_DOWN) }
            SystemClock.sleep(650)
            var count = 0
            main {
                assertTrue("A hold must repeat", deletions >= 3)
                count = deletions
                touch(MotionEvent.ACTION_UP)
                assertEquals(count, deletions)
                assertFalse(key.isPressed)
            }
            SystemClock.sleep(200)
            main { assertEquals(count, deletions) }
        } finally { main { keyboard.reset() } }
    }
    @Test fun cancellationLeavingKeyMultitouchAndResetCancelPendingRepeat() {
        setup()
        try {
            val stops: List<() -> Unit> = listOf(
                { touch(MotionEvent.ACTION_CANCEL) },
                { touch(MotionEvent.ACTION_MOVE, outside = true) },
                { touch(MotionEvent.ACTION_POINTER_DOWN) },
                { keyboard.reset() },
                { keyboard.dispatchWindowVisibilityChanged(View.GONE) },
            )
            for (stop in stops) {
                var count = 0
                main {
                    touch(MotionEvent.ACTION_DOWN)
                    count = deletions
                    stop()
                    touch(MotionEvent.ACTION_MOVE)
                    touch(MotionEvent.ACTION_UP)
                    assertFalse(key.isPressed)
                }
                SystemClock.sleep(550)
                main { assertEquals(count, deletions) }
            }
        } finally { main { keyboard.reset() } }
    }
    @Test fun resetDuringRepeatCallbackDoesNotScheduleAnotherDeletion() {
        setup()
        try {
            main {
                keyboard.onBackspace = {
                    deletions++
                    if (deletions == 2) keyboard.reset()
                }
                touch(MotionEvent.ACTION_DOWN)
            }
            SystemClock.sleep(700)
            main { assertEquals(2, deletions) }
        } finally { main { keyboard.reset() } }
    }
}
