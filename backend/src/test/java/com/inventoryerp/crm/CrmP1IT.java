package com.inventoryerp.crm;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.crm.application.CrmPlannerService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * CRM production-readiness fixes, PR B (P1; docs/spec/crm.md "Production-readiness fixes"): optimistic concurrency,
 * merges that leave nothing behind, a once-a-day digest, sales orders and finance gating on the 360, conversion guards,
 * reopen semantics and a mandatory Idempotency-Key on quotation conversion.
 */
class CrmP1IT extends ApiTestSupport {

    @Autowired
    CrmPlannerService planner;

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    String adminId;
    String service;

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        service = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                + " \"costPrice\", \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, ?, 'service', 100, 0, false,"
                + " now())").params(service, demoOrg, "P1S-" + sfx, "P1 service " + sfx).update();
        ok(200, "GET", "/crm/config/lookups", null);
    }

    String partner(String label) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"isCompany\","
                + " \"creditLimit\", \"updatedAt\") VALUES (?, ?, ?, ?, true, true, 5000, now())").params(id, demoOrg,
                        label + "-" + sfx + "-" + id.substring(0, 4), label + " " + sfx).update();
        return id;
    }

    /** A user holding only {@code perms}; returns {id, token}. */
    String[] userWith(String label, String... perms) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, label + " " + sfx, perms).update();
        String email = label.toLowerCase().replace(' ', '-') + "-" + sfx.toLowerCase() + "@demo.test";
        String id = createUser(demoOrg, email, "Crm@123456", role);
        return new String[] {id, login("DEMO", email, "Crm@123456")};
    }

    String stage(boolean won) {
        for (JsonNode s : ok(200, "GET", "/crm/config/lookups", null).get("stages")) {
            if (s.get("isActive").asBoolean() && s.get("isWon").asBoolean() == won) {
                return s.get("id").asString();
            }
        }
        throw new IllegalStateException("no stage");
    }

    @Test
    @DisplayName("a save from a stale version is a 409 and changes nothing; the current version saves and moves on")
    void optimisticConcurrency() {
        JsonNode deal = ok(201, "POST", "/crm/opportunities", Map.of("name", "Versioned " + sfx, "amount", 100));
        String id = deal.get("id").asString();
        int v0 = deal.get("version").asInt();
        JsonNode saved = ok(200, "PATCH", "/crm/opportunities/" + id, Map.of("amount", 150, "version", v0));
        assertThat(saved.get("version").asInt()).isGreaterThan(v0);
        Res stale = call("PATCH", "/crm/opportunities/" + id, Map.of("amount", 999, "version", v0), adminToken);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.body().get("message").asString()).contains("changed by someone else");
        assertThat(ok(200, "GET", "/crm/opportunities/" + id, null).get("amount").asDouble()).isEqualTo(150);
        // without a version (legacy clients, star rating) it still saves
        ok(200, "PATCH", "/crm/opportunities/" + id, Map.of("priority", 2));
        assertThat(call("POST", "/crm/opportunities/" + id + "/assign", Map.of("ownerId", adminId, "version", v0),
                adminToken).status()).isEqualTo(409);
    }

    @Test
    @DisplayName("merging deals re-points sales orders and projects; nothing still points at the archived source")
    void dealMergeMovesEverything() {
        String customer = partner("DM");
        String target = ok(201, "POST", "/crm/opportunities", Map.of("name", "Keep " + sfx, "partnerId", customer))
                .get("id").asString();
        String source = ok(201, "POST", "/crm/opportunities", Map.of("name", "Dup " + sfx, "partnerId", customer))
                .get("id").asString();
        String so = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"SalesOrder\" (id, \"organizationId\", number, \"partnerId\", \"currencyCode\", \"dealId\")"
                + " VALUES (?, ?, ?, ?, 'UGX', ?)").params(so, demoOrg, "SO-P1-" + sfx, customer, source).update();
        JsonNode merged = ok(200, "POST", "/crm/opportunities/" + target + "/merge", Map.of("sourceId", source));
        assertThat(merged.get("merged").get("salesOrders").asInt()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT \"dealId\" FROM \"SalesOrder\" WHERE id = ?").param(so).query(String.class)
                .single()).isEqualTo(target);
        JsonNode detail = ok(200, "GET", "/crm/opportunities/" + target, null);
        assertThat(detail.get("salesOrders").toString()).contains("SO-P1-" + sfx);
    }

    @Test
    @DisplayName("customer merge needs partner:delete and leaves closed quotations with the archived customer")
    void customerMerge() {
        String keep = partner("CMK");
        String dup = partner("CMD");
        String open = ok(201, "POST", "/crm/quotations", Map.of("partnerId", dup, "lines", List.of(Map.of("productId",
                service, "quantity", 1, "unitPrice", 100)))).get("id").asString();
        String closed = ok(201, "POST", "/crm/quotations", Map.of("partnerId", dup, "lines", List.of(Map.of(
                "productId", service, "quantity", 1, "unitPrice", 100)))).get("id").asString();
        jdbc.sql("UPDATE \"SalesQuotation\" SET status = 'rejected' WHERE id = ?").param(closed).update();

        String[] configurer = userWith("Crm configurer", "crm:configure", "crm:deal:read");
        assertThat(call("POST", "/crm/customers/" + keep + "/merge", Map.of("sourceId", dup), configurer[1]).status())
                .isEqualTo(403);
        JsonNode preview = ok(200, "GET", "/crm/customers/" + keep + "/merge-preview?sourceId=" + dup, null);
        assertThat(preview.get("moves").get("SalesQuotation").asInt()).isEqualTo(1);
        assertThat(preview.get("moves").get("closedQuotationsKept").asInt()).isEqualTo(1);
        ok(200, "POST", "/crm/customers/" + keep + "/merge", Map.of("sourceId", dup));
        assertThat(jdbc.sql("SELECT \"partnerId\" FROM \"SalesQuotation\" WHERE id = ?").param(open).query(String.class)
                .single()).isEqualTo(keep);
        assertThat(jdbc.sql("SELECT \"partnerId\" FROM \"SalesQuotation\" WHERE id = ?").param(closed)
                .query(String.class).single()).as("history stays with the archived customer").isEqualTo(dup);
    }

    @Test
    @DisplayName("the overdue digest reaches each person at most once per business day")
    void digestOncePerDay() {
        String[] rep = userWith("Digest rep", "crm:activity:read");
        String twoDaysAgo = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
        String act = ok(201, "POST", "/crm/activities", Map.of("type", "call", "title", "Overdue " + sfx, "dueAt",
                twoDaysAgo)).get("id").asString();
        jdbc.sql("UPDATE \"Activity\" SET \"assignedToId\" = ? WHERE id = ?").params(rep[0], act).update();
        TenantContext.run(TenantContext.TenantStore.organization(demoOrg), planner::sendOverdueDigest);
        TenantContext.run(TenantContext.TenantStore.organization(demoOrg), planner::sendOverdueDigest);
        assertThat(jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"userId\" = ? AND payload->>'kind' ="
                + " 'crm.activity.overdue_digest'").param(rep[0]).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("Customer 360: sales orders listed; invoices, receivables, credit and spend need invoice:read")
    void customer360FinanceGate() {
        String customer = partner("C360");
        jdbc.sql("INSERT INTO \"SalesOrder\" (id, \"organizationId\", number, \"partnerId\", \"currencyCode\")"
                + " VALUES (?, ?, ?, ?, 'UGX')").params(UUID.randomUUID().toString(), demoOrg, "SO-360-" + sfx,
                        customer).update();
        JsonNode full = ok(200, "GET", "/crm/customers/" + customer, null);
        assertThat(full.get("financeHidden").asBoolean()).isFalse();
        assertThat(full.get("credit").isNull()).isFalse();
        assertThat(full.get("salesOrders").toString()).contains("SO-360-" + sfx);

        String[] rep = userWith("Crm reader", "crm:deal:read");
        Res limited = call("GET", "/crm/customers/" + customer, null, rep[1]);
        assertThat(limited.status()).isEqualTo(200);
        assertThat(limited.body().get("financeHidden").asBoolean()).isTrue();
        assertThat(limited.body().get("credit").isNull()).isTrue();
        assertThat(limited.body().get("invoices").size()).isZero();
        Res list = call("GET", "/crm/customers?q=" + sfx, null, rep[1]);
        assertThat(list.status()).isEqualTo(200);
        for (JsonNode r : list.body().get("data")) {
            assertThat(r.get("openReceivables").isNull()).isTrue();
        }
        Res spend = call("GET", "/crm/analytics/partner/" + customer, null, rep[1]);
        assertThat(spend.status()).isEqualTo(200);
        assertThat(spend.body().get("totalSpent").isNull()).isTrue();
        assertThat(spend.body().get("openReceivable").isNull()).isTrue();
    }

    @Test
    @DisplayName("a lost lead cannot be converted, nor converted into a won stage; dragging a won deal back reopens it")
    void conversionGuardsAndReopen() {
        String lost = ok(201, "POST", "/crm/leads", Map.of("name", "Lost lead " + sfx)).get("id").asString();
        ok(200, "POST", "/crm/leads/" + lost + "/lost", Map.of());
        Res r = call("POST", "/crm/leads/" + lost + "/convert", Map.of(), adminToken);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).contains("Restore the lost lead");

        String lead = ok(201, "POST", "/crm/leads", Map.of("name", "Lead " + sfx)).get("id").asString();
        Res toWon = call("POST", "/crm/leads/" + lead + "/convert", Map.of("stageId", stage(true)), adminToken);
        assertThat(toWon.status()).isEqualTo(400);
        assertThat(toWon.body().get("message").asString()).contains("active, open stage");

        String deal = ok(201, "POST", "/crm/opportunities", Map.of("name", "Won then dragged " + sfx)).get("id")
                .asString();
        ok(200, "POST", "/crm/opportunities/" + deal + "/won", null);
        JsonNode moved = ok(200, "POST", "/crm/opportunities/" + deal + "/move", Map.of("stageId", stage(false)));
        assertThat(moved.get("status").asString()).isEqualTo("open");
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE entity = 'Deal' AND \"entityId\" = ? AND"
                + " \"newValues\"->>'via' = 'move'").param(deal).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("converting a quotation without an Idempotency-Key is refused")
    void convertNeedsKey() {
        String q = ok(201, "POST", "/crm/quotations", Map.of("partnerId", partner("IK"), "lines", List.of(Map.of(
                "productId", service, "quantity", 1, "unitPrice", 100)))).get("id").asString();
        Res r = call("POST", "/crm/quotations/" + q + "/convert", Map.of("target", "proforma"), Map.of("Authorization",
                "Bearer " + adminToken, NO_IDEMPOTENCY_KEY, "1"));
        assertThat(r.status()).isEqualTo(400);
    }
}
