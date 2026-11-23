package com.inventoryerp.crm;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * CRM production-readiness fixes, PR A (docs/spec/crm.md "Production-readiness fixes"): P0-1 no organization-wide
 * lock on CRM reads, P0-2 converting a quotation never rolls back for a converter who cannot see the deal, P0-3 bad
 * numbers are a 400. P0-4 (quote links), P0-5 (form abuse) and P0-6 (email outbox) are covered in
 * {@link CrmLeadFormQuoteLinkIT}, {@link CrmDealExtrasIT} and {@code RateLimitInterceptorTest}.
 */
class CrmProdReadinessIT extends ApiTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    String adminId;
    String customer;
    String service;

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"isCompany\","
                + " \"creditLimit\", \"updatedAt\") VALUES (?, ?, ?, ?, true, true, 1000000, now())").params(customer,
                        demoOrg, "PR-" + sfx, "Readiness Customer " + sfx).update();
        service = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                + " \"costPrice\", \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, ?, 'service', 100, 0, false,"
                + " now())").params(service, demoOrg, "PRS-" + sfx, "Readiness service " + sfx).update();
        ok(200, "GET", "/crm/config/lookups", null); // the organization is seeded
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

    @Test
    @DisplayName("P0-1: CRM lists, kanban, report and dashboard do not wait on the organization's CrmSettings row lock")
    void readsDoNotQueueOnSettingsLock() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofPlatform().start(() -> new TransactionTemplate(txm).executeWithoutResult(s -> {
            jdbc.sql("SELECT 1 FROM \"CrmSettings\" WHERE \"organizationId\" = ? FOR UPDATE").param(demoOrg).query()
                    .listOfRows();
            locked.countDown();
            try {
                release.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            for (String path : List.of("/crm/opportunities", "/crm/leads", "/crm/opportunities/kanban",
                    "/crm/opportunities/grouped?groupBy=owner", "/crm/reports", "/crm/dashboard")) {
                HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                        .header("Authorization", "Bearer " + adminToken).timeout(Duration.ofSeconds(8)).GET().build();
                long t0 = System.nanoTime();
                HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
                assertThat(r.statusCode()).as(path).isEqualTo(200);
                assertThat(Duration.ofNanos(System.nanoTime() - t0)).as(path + " did not wait for the lock")
                        .isLessThan(Duration.ofSeconds(5));
            }
        } finally {
            release.countDown();
            holder.join(10_000);
        }
    }

    @Test
    @DisplayName("P0-2: a converter who cannot see the deal converts the quotation; the document persists, the deal is"
            + " won and the audit names the converter")
    void convertWithoutDealVisibility() {
        ok(200, "PUT", "/crm/config/settings", Map.of("autoWinOnConversion", true));
        String deal = ok(201, "POST", "/crm/opportunities", Map.of("name", "Hidden deal " + sfx, "partnerId", customer,
                "ownerId", adminId, "amount", 200)).get("id").asString();
        String q = ok(201, "POST", "/crm/quotations", Map.of("dealId", deal, "lines", List.of(Map.of("productId",
                service, "quantity", 2, "unitPrice", 100)))).get("id").asString();
        JsonNode submitted = ok(200, "POST", "/crm/quotations/" + q + "/submit", null);
        if ("pending_approval".equals(submitted.get("status").asString())) {
            ok(200, "POST", "/crm/quotations/" + q + "/approve", null);
        }
        String[] converter = userWith("Converter", "crm:quotation:read", "crm:quotation:convert");
        jdbc.sql("UPDATE \"SalesQuotation\" SET \"ownerId\" = ? WHERE id = ?").params(converter[0], q).update();
        assertThat(call("GET", "/crm/opportunities/" + deal, null, converter[1]).status())
                .as("the converter cannot open the deal").isIn(403, 404);

        Res converted = call("POST", "/crm/quotations/" + q + "/convert", Map.of("target", "proforma"), converter[1]);
        assertThat(converted.status()).as(converted.body().toString()).isEqualTo(200);
        assertThat(converted.body().get("status").asString()).isEqualTo("converted");
        String docId = converted.body().get("converted").get("id").asString();
        assertThat(jdbc.sql("SELECT count(*) FROM \"Document\" WHERE id = ?").param(docId).query(Long.class).single())
                .as("the proforma persisted exactly once").isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM \"Deal\" WHERE id = ?").param(deal).query(String.class).single())
                .isEqualTo("won");
        Map<String, Object> audit = jdbc.sql("SELECT \"actorId\", \"newValues\"::text AS nv FROM \"AuditLog\" WHERE"
                + " entity = 'Deal' AND \"entityId\" = ? AND \"newValues\"->>'status' = 'won'").param(deal).query()
                .singleRow();
        assertThat(audit.get("actorId")).isEqualTo(converter[0]);
        assertThat((String) audit.get("nv")).contains("quotation_conversion");
        // still not a general bypass: the converter still cannot open or move the deal
        assertThat(call("GET", "/crm/opportunities/" + deal, null, converter[1]).status()).isIn(403, 404);
        // a second convert is refused, no second document
        assertThat(call("POST", "/crm/quotations/" + q + "/convert", Map.of("target", "proforma"), converter[1])
                .status()).isEqualTo(400);
    }

    @Test
    @DisplayName("P0-3: a value that is not a number is a 400 with the field name, never a 500")
    void badNumbersAre400() {
        assertThat(call("GET", "/crm/opportunities?priority=abc", null, adminToken).status()).isEqualTo(400);
        assertThat(call("GET", "/crm/opportunities?priorityExact=1.5", null, adminToken).status()).isEqualTo(400);
        Res amount = call("POST", "/crm/opportunities", Map.of("name", "Bad " + sfx, "amount", "x"), adminToken);
        assertThat(amount.status()).isEqualTo(400);
        assertThat(amount.body().get("message").toString()).contains("amount");
        assertThat(call("POST", "/crm/opportunities", Map.of("name", "Bad " + sfx, "probability", List.of(1)),
                adminToken).status()).isEqualTo(400);
        String deal = ok(201, "POST", "/crm/opportunities", Map.of("name", "Numbers " + sfx)).get("id").asString();
        assertThat(call("PATCH", "/crm/opportunities/" + deal, Map.of("priority", "high"), adminToken).status())
                .isEqualTo(400);
        assertThat(ok(200, "PATCH", "/crm/opportunities/" + deal, Map.of("priority", "2")).get("priority").asInt())
                .as("a numeric string is still accepted").isEqualTo(2);
        assertThat(call("POST", "/crm/config/stages", Map.of("name", "S " + sfx, "sequence", "first"), adminToken)
                .status()).isEqualTo(400);
        assertThat(call("PUT", "/crm/config/settings", Map.of("rottingDays", "soon"), adminToken).status())
                .isEqualTo(400);
    }
}
