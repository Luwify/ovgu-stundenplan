package de.luwify.stundenplanmd.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SnackbarsTest {
    @Test fun durations() {
        assertEquals(SnackbarDuration.Long, snackbarDuration(hasAction = true))
        assertEquals(SnackbarDuration.Short, snackbarDuration(hasAction = false))
        assertEquals(SnackbarDuration.Indefinite, snackbarDuration(hasAction = false, sticky = true))
        assertEquals(SnackbarDuration.Indefinite, snackbarDuration(hasAction = true, sticky = true))
    }

    @Test fun undoKeepsTenSecondsAndTheCloseButtonOnlyWhenSticky() = runTest {
        val host = SnackbarHostState()
        launch { host.notify("Ausgeblendet", "Rückgängig") }
        runCurrent()
        val v = host.currentSnackbarData!!.visuals as AppSnackbarVisuals
        assertEquals(SnackbarDuration.Long, v.timeout)
        assertEquals(10_000L, v.timeout.millis())
        // The host's own timer is off: the snackbar counts itself (and can pause).
        assertEquals(SnackbarDuration.Indefinite, v.duration)
        assertFalse(v.withDismissAction)
        host.currentSnackbarData!!.dismiss()
        launch { host.notify("Kaputt", sticky = true) }
        runCurrent()
        assertTrue(host.currentSnackbarData!!.visuals.withDismissAction)
        host.currentSnackbarData!!.dismiss()
    }

    /** A swipe (and a time-out) ends the snackbar with dismiss(): the undo must not run. */
    @Test fun dismissDoesNotRunTheAction() = runTest {
        val host = SnackbarHostState()
        var undone = false
        val job = launch { host.notify("Ausgeblendet", "Rückgängig") { undone = true } }
        runCurrent()
        host.currentSnackbarData!!.dismiss()
        job.join()
        assertFalse(undone)
        assertEquals(null, host.currentSnackbarData)
    }

    @Test fun actionRunsTheUndo() = runTest {
        val host = SnackbarHostState()
        var undone = false
        val job = launch { host.notify("Ausgeblendet", "Rückgängig") { undone = true } }
        runCurrent()
        host.currentSnackbarData!!.performAction()
        job.join()
        assertTrue(undone)
    }

    private val width = 1000
    private val density = 2.6f

    @Test fun shortSlowDragSnapsBack() {
        assertEquals(0, SnackbarSwipe.settle(150f, 200f, width, density))
        assertEquals(0, SnackbarSwipe.settle(-150f, -200f, width, density))
        assertEquals(0, SnackbarSwipe.settle(0f, 0f, width, density))
    }

    @Test fun farDragDismissesInItsDirection() {
        assertEquals(1, SnackbarSwipe.settle(450f, 0f, width, density))
        assertEquals(-1, SnackbarSwipe.settle(-450f, 0f, width, density))
    }

    @Test fun flingDismissesInItsDirection() {
        assertEquals(1, SnackbarSwipe.settle(60f, 3000f, width, density))
        assertEquals(-1, SnackbarSwipe.settle(-60f, -3000f, width, density))
    }

    @Test fun flingBackSnapsBack() {
        assertEquals(0, SnackbarSwipe.settle(500f, -3000f, width, density))
        assertEquals(0, SnackbarSwipe.settle(-500f, 3000f, width, density))
    }

    @Test fun durationMillis() {
        assertEquals(4_000L, SnackbarDuration.Short.millis())
        assertEquals(null, SnackbarDuration.Indefinite.millis())
    }

    /** Runs [timer] in 100 ms steps; [paused] says per step (time in ms since start) whether it is paused. */
    private fun runOut(timer: SnackbarTimer, paused: (Long) -> Boolean): Long {
        var t = 0L
        while (!timer.advance(100, paused(t))) { t += 100; assertTrue(t < 120_000) }
        return t + 100
    }

    @Test fun timerRunsOutUnpaused() {
        assertEquals(10_000L, runOut(SnackbarTimer(10_000)) { false })
    }

    @Test fun touchPausesAndLeavesTimeToReact() {
        // Touched from 9.0 s to 12.0 s: paused there, then at least 4 s more.
        val end = runOut(SnackbarTimer(10_000)) { it in 9_000 until 12_000 }
        assertEquals(16_000L, end)
        // Touched early: the rest of the time stays (more than 4 s left), nothing extra.
        assertEquals(13_000L, runOut(SnackbarTimer(10_000)) { it in 1_000 until 4_000 })
    }

    @Test fun expandedPausesUntilLeftAlone() {
        val idle = SnackbarTimer.EXPANDED_IDLE
        assertTrue(SnackbarTimer.paused(touching = false, expanded = true, sinceTouch = 0))
        assertTrue(SnackbarTimer.paused(touching = false, expanded = true, sinceTouch = idle - 1))
        // Expanded but nobody touched it for a few seconds: the clock runs again (it can still be swiped away).
        assertFalse(SnackbarTimer.paused(touching = false, expanded = true, sinceTouch = idle))
        assertFalse(SnackbarTimer.paused(touching = false, expanded = false, sinceTouch = 0))
        assertTrue(SnackbarTimer.paused(touching = true, expanded = false, sinceTouch = 99_999))
        // Expanded by a tap at 2 s, left alone: runs again from 7 s with the 8 s that were left.
        val end = runOut(SnackbarTimer(10_000)) { t -> t >= 2_000 && SnackbarTimer.paused(false, true, t - 2_000) }
        assertEquals(15_000L, end)
    }

    @Test fun foldOnlyWhenTheTextIsCut() {
        var f = SnackbarFold()
        // Fits in two lines: no hint, a tap does nothing.
        f = f.laidOut(overflow = false)
        assertFalse(f.truncated)
        assertEquals(f, f.toggled())
        // Cut: hint, a tap expands; the expanded layout (no overflow) keeps it expandable, a second tap collapses.
        f = f.laidOut(overflow = true)
        assertTrue(f.truncated && !f.expanded)
        f = f.toggled()
        assertTrue(f.expanded)
        f = f.laidOut(overflow = false)
        assertTrue(f.truncated && f.expanded)
        f = f.toggled()
        assertTrue(f.truncated && !f.expanded)
        assertEquals(2, SnackbarFold.COLLAPSED_LINES)
    }

    @Test fun fadesOutOnTheWay() {
        assertEquals(1f, SnackbarSwipe.alpha(0f, width), 0f)
        assertTrue(SnackbarSwipe.alpha(375f, width) in 0.45f..0.55f)
        assertEquals(0f, SnackbarSwipe.alpha(-900f, width), 0f)
        assertEquals(1f, SnackbarSwipe.alpha(300f, 0), 0f)
    }
}
