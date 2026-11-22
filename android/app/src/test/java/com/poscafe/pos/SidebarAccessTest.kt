package com.poscafe.pos

import com.poscafe.pos.ui.screens.CRM_PERMISSIONS
import com.poscafe.pos.ui.screens.allowed
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SidebarAccessTest {

    @Test
    fun `open items show for everyone`() {
        assertTrue(allowed(emptySet(), null))
        assertTrue(allowed(setOf("pos:checkout"), null))
    }

    @Test
    fun `synced staff need one of the permissions`() {
        val cashier = setOf("pos:checkout", "pos:read", "cash_session:open")
        assertFalse(allowed(cashier, setOf("pos:reports")))
        assertFalse(allowed(cashier, setOf("organization:update")))
        assertTrue(allowed(cashier + "pos:reports", setOf("pos:reports")))
        assertTrue(allowed(setOf("expense:read"), setOf("expense:create", "expense:read")))
    }

    @Test
    fun `standalone role words`() {
        assertTrue(allowed(setOf("admin"), setOf("organization:update")))
        assertTrue(allowed(setOf("cashier"), setOf("cash_session:read", "pos:reports")))
        assertFalse(allowed(setOf("cashier"), setOf("organization:update")))
        assertFalse(allowed(setOf("cashier"), setOf("pos:reports")))
    }

    @Test
    fun `CRM shows for any CRM permission, never for a cashier`() {
        val need = CRM_PERMISSIONS.toSet()
        assertTrue(allowed(setOf("pos:checkout", "crm:lead:write"), need))
        assertTrue(allowed(setOf("crm:activity:read"), need))
        assertFalse(allowed(setOf("pos:checkout", "partner:read"), need))
        assertFalse(allowed(setOf("cashier"), need))
    }
}
