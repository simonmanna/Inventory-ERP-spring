package com.poscafe.pos

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.poscafe.pos.data.DeviceConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the till remembers about the licence, and when it puts it on the screen.
 *
 * The persistence is the point: a terminal restarted with no network must still know whether it may
 * sell. Assuming the best would let an expired licence be cleared by pulling the plug; assuming the
 * worst would stop a shop every time it rebooted before its first pull.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LicenseStateTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("licence-test", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun config() = DeviceConfig(
        context,
        context.getSharedPreferences("licence-test", Context.MODE_PRIVATE),
    )

    @Test
    fun `a till that has never heard about a licence sells and shows nothing`() {
        val licence = config().license.value

        assertFalse("never heard", licence.known)
        assertTrue("must not block a shop that has not synced yet", licence.writable)
        assertFalse("nothing to say", licence.warrants)
    }

    @Test
    fun `an active licence is silent`() {
        val config = config()
        config.applyLicense("ACTIVE", writable = true, message = null, daysRemaining = 200,
            graceUntil = null)

        val licence = config.license.value
        assertTrue(licence.known)
        assertTrue(licence.writable)
        assertFalse("a healthy licence is not worth a word", licence.warrants)
    }

    @Test
    fun `an expiry inside a fortnight is worth saying`() {
        val config = config()
        config.applyLicense("ACTIVE", writable = true, message = null, daysRemaining = 14,
            graceUntil = null)

        assertTrue(config.license.value.warrants)
        assertTrue("still sells", config.license.value.writable)
    }

    @Test
    fun `grace warns while still selling`() {
        val config = config()
        config.applyLicense("GRACE", writable = true, message = null, daysRemaining = -2,
            graceUntil = "2026-07-01T00:00:00Z")

        val licence = config.license.value
        assertEquals("GRACE", licence.mode)
        assertTrue(licence.writable)
        assertTrue(licence.warrants)
    }

    @Test
    fun `read-only blocks the sale and carries the server's own wording`() {
        val config = config()
        config.applyLicense(
            "READ_ONLY",
            writable = false,
            message = "Your licence has expired. The system is read-only until it is renewed.",
            daysRemaining = -40,
            graceUntil = "2026-06-01T00:00:00Z",
        )

        val licence = config.license.value
        assertFalse(licence.writable)
        assertTrue(licence.warrants)
        assertEquals(
            "Your licence has expired. The system is read-only until it is renewed.",
            licence.message,
        )
    }

    @Test
    fun `the answer survives a restart with no network`() {
        config().applyLicense("READ_ONLY", writable = false, message = "Expired", daysRemaining = -5,
            graceUntil = null)

        // A fresh instance, as after a reboot: no pull has happened yet.
        val afterRestart = config().license.value

        assertTrue(afterRestart.known)
        assertFalse("pulling the plug must not clear an expiry", afterRestart.writable)
        assertEquals("Expired", afterRestart.message)
        assertNull(afterRestart.graceUntil)
    }

    @Test
    fun `renewing while offline is picked up on the next pull`() {
        val config = config()
        config.applyLicense("READ_ONLY", writable = false, message = "Expired", daysRemaining = -5,
            graceUntil = null)
        assertFalse(config.license.value.writable)

        config.applyLicense("ACTIVE", writable = true, message = null, daysRemaining = 30,
            graceUntil = null)

        val licence = config.license.value
        assertTrue(licence.writable)
        assertNull("the old refusal must not linger", licence.message)
        assertFalse(licence.warrants)
    }
}
