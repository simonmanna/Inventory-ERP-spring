package com.poscafe.pos

import com.poscafe.pos.data.OfflineWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-S083 C4: the till stops new sales past the offline window or with its clock wound back. */
class OfflineWindowTest {
    private val hour = 3_600_000L
    private val synced = 1_000_000_000_000L

    @Test
    fun `inside the window sales are allowed`() {
        assertNull(OfflineWindow.block(synced, 72, synced + 71 * hour))
        assertEquals(1L, OfflineWindow.hoursLeft(synced, 72, synced + 70 * hour + 1))
    }

    @Test
    fun `past the window sales stop until the till syncs`() {
        assertTrue(OfflineWindow.block(synced, 72, synced + 73 * hour)!!.contains("72 hours"))
        assertNull(OfflineWindow.hoursLeft(synced, 72, synced + 73 * hour))
    }

    @Test
    fun `a clock earlier than the last sync blocks`() {
        assertTrue(OfflineWindow.block(synced, 72, synced - 1)!!.contains("clock"))
    }

    @Test
    fun `a till that never synced is not blocked here, and 0 disables the window`() {
        assertNull(OfflineWindow.block(null, 72, synced))
        assertNull(OfflineWindow.block(synced, 0, synced + 9_999 * hour))
        assertNotNull(OfflineWindow.block(synced, 1, synced + 2 * hour))
    }
}
