package com.poscafe.pos.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dashboard's app grid: what a person may open, by permission and by till mode. */
class DashboardAppsTest {

    private fun ids(perms: Set<String>, standalone: Boolean = false, retail: Boolean = false) =
        dashboardApps(perms, standalone, retail).map { it.id }

    @Test
    fun `an admin sees every app, point of sale first`() {
        val all = ids(setOf("admin"))
        assertEquals("pos", all.first())
        assertTrue(all.containsAll(listOf("tables", "menu", "crm", "settings", "backup", "sync", "barcode", "products")))
    }

    @Test
    fun `a cashier sees front-of-house apps only`() {
        val cashier = ids(setOf("pos:checkout", "pos:read", "partner:read", "cash_session:read"))
        assertTrue(cashier.containsAll(listOf("pos", "orders", "customers", "shifts", "barcode")))
        assertFalse("settings" in cashier)
        assertFalse("staff" in cashier)
        assertFalse("products" in cashier)
    }

    @Test
    fun `retail has no tables, menu or floor setup`() {
        val retail = ids(setOf("admin"), retail = true)
        assertFalse(retail.any { it in setOf("tables", "menu", "floor") })
        assertTrue("products" in retail)
    }

    @Test
    fun `standalone has no online-only apps`() {
        val standalone = ids(setOf("admin"), standalone = true)
        assertFalse(standalone.any { it in setOf("crm", "sync", "inbox") })
    }

    @Test
    fun `the till apps open the PIN-gated POS on their tab`() {
        val apps = dashboardApps(setOf("admin"), standalone = false, isRetail = false).associateBy { it.id }
        assertEquals(AppTarget.Pos(HomeTab.Sell), apps.getValue("pos").target)
        assertEquals(AppTarget.Pos(HomeTab.Orders), apps.getValue("orders").target)
        assertEquals(AppTarget.Route("barcode"), apps.getValue("barcode").target)
    }
}
