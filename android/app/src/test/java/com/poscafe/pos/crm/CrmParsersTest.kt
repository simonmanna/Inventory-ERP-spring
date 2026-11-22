package com.poscafe.pos.crm

import com.poscafe.pos.data.crm.CrmLoginResult
import com.poscafe.pos.data.crm.CrmParsers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.ZoneId

class CrmParsersTest {

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `login with tokens`() {
        val r = CrmParsers.login(json("""{"accessToken":"a","refreshToken":"r","user":{"id":"u1","firstName":"Ann","lastName":null,"email":"ann@x"},"permissions":["crm:lead:write","crm:deal:read"]}"""))
        r as CrmLoginResult.Tokens
        assertEquals("a", r.accessToken)
        assertEquals("u1", r.userId)
        assertEquals("Ann", r.displayName)
        assertEquals(setOf("crm:lead:write", "crm:deal:read"), r.permissions)
    }

    @Test
    fun `login that needs MFA`() {
        val r = CrmParsers.login(json("""{"mfaToken":"m","requiresMfa":true}"""))
        assertEquals(CrmLoginResult.NeedsMfa("m"), r)
    }

    @Test
    fun `planner rows and counts`() {
        val p = CrmParsers.planner(json("""{"data":[{"id":"a1","type":"call","title":"Call back","dueAt":"2026-11-22T06:30:00Z","state":"overdue","dealId":"d1","dealName":"Office coffee","partnerId":null,"partnerName":null}],"counts":{"overdue":1,"today":2,"upcoming":3}}"""))
        assertEquals(1, p.activities.size)
        assertEquals("overdue", p.activities[0].state)
        assertEquals(null, p.activities[0].partnerId)
        assertEquals(Triple(1, 2, 3), Triple(p.overdue, p.today, p.upcoming))
    }

    @Test
    fun `customer 360 keeps money exact and tolerates missing parts`() {
        val c = CrmParsers.customer(json("""{"partner":{"id":"p1","name":"Acme","code":"C001","contacts":[{"firstName":"Bo","lastName":"Lee","isPrimary":true}]},
            "opportunities":[{"id":"d1","name":"Deal","status":"open","stageName":"Proposal","amount":1234.5,"currencyCode":"UGX"}],
            "invoices":[{"documentNumber":"INV-1","status":"posted","totalAmount":"100.10","amountResidual":0.1}],
            "credit":{"creditLimit":5000,"creditHold":true,"openReceivables":0.1,"overdueReceivables":null}}"""))
        assertEquals("Acme", c.name)
        assertEquals("Bo Lee", c.contacts.single().name)
        assertEquals(BigDecimal("1234.5"), c.deals.single().amount)
        assertEquals(BigDecimal("0.1"), c.invoices.single().residual)
        assertEquals(BigDecimal.ZERO, c.overdueReceivables)
        assertTrue(c.creditHold)
        assertTrue(c.history.isEmpty())
    }

    @Test
    fun `duplicates hide what the user may not open`() {
        val d = CrmParsers.duplicates(json("""{"partners":[{"id":"p1","name":"Acme","matchedOn":["phone"]}],
            "deals":[{"id":null,"kind":"lead","name":null,"companyName":null,"ownerName":"Bo","visible":false,"matchedOn":["email"]}]}"""))
        assertEquals("Customer: Acme", d[0].label)
        assertEquals(listOf("phone"), d[0].matchedOn)
        assertEquals("Lead: (owned by Bo)", d[1].label)
    }

    @Test
    fun `server error messages`() {
        assertEquals("Invalid credentials", CrmParsers.errorMessage("""{"statusCode":401,"message":"Invalid credentials"}""", 401))
        assertEquals("name should not be empty; email must be an email", CrmParsers.errorMessage("""{"message":["name should not be empty","email must be an email"]}""", 400))
        assertEquals("You do not have permission for this.", CrmParsers.errorMessage("<html>", 403))
        assertFalse(CrmParsers.errorMessage(null, 502).isBlank())
    }

    @Test
    fun `money and dates for display`() {
        assertEquals("1,234,567", CrmParsers.money(BigDecimal("1234567.00")))
        assertEquals("UGX 12.50", CrmParsers.money(BigDecimal("12.5"), "UGX"))
        assertEquals("2026-11-22 09:30", CrmParsers.shortDate("2026-11-22T06:30:00Z", ZoneId.of("Africa/Kampala")))
        assertEquals("2026-11-22", CrmParsers.shortDate("2026-11-22", ZoneId.of("UTC")))
        assertEquals("", CrmParsers.shortDate(null))
    }
}
