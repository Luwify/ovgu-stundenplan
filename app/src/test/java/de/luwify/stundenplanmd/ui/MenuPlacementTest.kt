package de.luwify.stundenplanmd.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MenuPlacementTest {
    // 4 rows of 100 px, 20 px padding: the menu is 440 px high.
    private fun place(anchorCenter: Int, index: Int, count: Int = 4, minTop: Int = 0, maxBottom: Int = 2000) =
        menuPlacement(anchorCenter, index, count, item = 100, pad = 20, minTop = minTop, maxBottom = maxBottom)

    @Test fun currentRowSitsOnTheField() {
        for (i in 0 until 4) {
            val p = place(1000, i)
            assertEquals(440, p.height)
            assertEquals(1000, p.top + 20 + i * 100 + 50)
            assertEquals(0, p.scroll)
        }
    }

    @Test fun staysOnScreenNearTheEdges() {
        assertEquals(100, place(150, 3, minTop = 100).top)
        assertEquals(2000 - 440, place(1950, 0).top)
    }

    @Test fun tallListFillsTheSpaceAndScrollsToTheCurrentRow() {
        // 30 rows = 3040 px in 2000 px: top at 0, current row (index 15, center 1570) scrolled onto the field (1000).
        val p = place(1000, 15, count = 30)
        assertEquals(0, p.top)
        assertEquals(2000, p.height)
        assertEquals(570, p.scroll)
        assertEquals(0, place(1000, 2, count = 30).scroll)
        assertEquals(1040, place(1000, 29, count = 30).scroll)
    }
}
