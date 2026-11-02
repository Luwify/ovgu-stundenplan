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
        val v = host.currentSnackbarData!!.visuals
        assertEquals(SnackbarDuration.Long, v.duration)
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

    @Test fun fadesOutOnTheWay() {
        assertEquals(1f, SnackbarSwipe.alpha(0f, width), 0f)
        assertTrue(SnackbarSwipe.alpha(375f, width) in 0.45f..0.55f)
        assertEquals(0f, SnackbarSwipe.alpha(-900f, width), 0f)
        assertEquals(1f, SnackbarSwipe.alpha(300f, 0), 0f)
    }
}
