package com.inventoryerp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** PR-3 (roadmap 2.4, ADR-S025): analytic dimensions, posting validation, account rules, reports, document lines. */
class AnalyticDimensionIT extends ApiTestSupport {

    @Autowired
    PostingService posting;

    record Books(String org, String owner, String ownerId, String code) {}

    Books freshBooks() {
        String code = "ad-" + UUID.randomUUID().toString().substring(0, 8);
        Res boot = call("POST", "/organizations/bootstrap", Map.of("organizationCode", code, "organizationName", "Dim Co",
                "adminEmail", "owner@" + code + ".test", "adminFirstName", "Owner", "adminPassword", "Owner@12345"),
                (String) null);
        assertThat(boot.status()).as(boot.raw().body()).isEqualTo(201);
        String org = boot.body().get("organization").get("id").asString();
        String ownerId = boot.body().get("adminUser").get("id").asString();
        return new Books(org, login(code, "owner@" + code + ".test", "Owner@12345"), ownerId, code);
    }

    String accountBySection(String org, String section) {
        return jdbc.sql("SELECT a.id FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE"
                + " a.\"organizationId\" = ? AND c.\"reportSection\"::text = ? AND a.\"deletedAt\" IS NULL"
                + " ORDER BY a.code LIMIT 1").params(org, section).query(String.class).single();
    }

    String account(String org, String code) {
        return jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = ?").params(org, code)
                .query(String.class).single();
    }

    Map<String, Object> post(Books b, String debit, String credit, int amount, Map<String, Object> dims,
            String sourceType) {
        return TenantContext.run(TenantStore.user(b.org(), b.ownerId()), () -> new TransactionTemplate(txm).execute(s ->
                posting.post(new PostingRequest("GEN", Instant.now(), "Dim test", null, null, sourceType,
                        UUID.randomUUID().toString(), null, null, null, null, null, List.of(
                        new PostingRequest.Line(debit, amount, 0, null, "Dr", null, null, dims),
                        new PostingRequest.Line(credit, 0, amount, null, "Cr", null, null, null))))));
    }

    String message(Runnable r) {
        try {
            r.run();
        } catch (ApiException e) {
            return e.getMessage();
        }
        throw new AssertionError("expected an ApiException");
    }

    JsonNode okAs(Books b, int status, String method, String path, Object body) {
        Res r = call(method, path, body, b.owner());
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(status);
        return r.body();
    }

    @Test
    @DisplayName("dimensions: master data, posting validation, account rules, roll-up reports, filters, drill-down")
    void dimensions() {
        Books b = freshBooks();
        String expense = accountBySection(b.org(), "operating_expense");
        String revenue = accountBySection(b.org(), "revenue");
        String cash = account(b.org(), "1100");

        // master data
        assertThat(call("POST", "/analytic-dimensions", Map.of("code", "costCenter", "name", "x"), b.owner()).body()
                .get("message").asString()).isEqualTo("Dimension code costCenter is reserved");
        assertThat(call("POST", "/analytic-dimensions", Map.of("code", "9bad", "name", "x"), b.owner()).status())
                .isEqualTo(400);
        JsonNode project = okAs(b, 201, "POST", "/analytic-dimensions", Map.of("code", "project", "name", "Project"));
        String dimId = project.get("id").asString();
        assertThat(call("POST", "/analytic-dimensions", Map.of("code", "project", "name", "Again"), b.owner()).status())
                .isEqualTo(409);
        String p1 = okAs(b, 201, "POST", "/analytic-dimensions/" + dimId + "/values",
                Map.of("code", "P1", "name", "Bridge")).get("id").asString();
        okAs(b, 201, "POST", "/analytic-dimensions/" + dimId + "/values",
                Map.of("code", "P1A", "name", "Bridge phase A", "parentId", p1));
        String p2 = okAs(b, 201, "POST", "/analytic-dimensions/" + dimId + "/values",
                Map.of("code", "P2", "name", "Road")).get("id").asString();
        assertThat(okAs(b, 200, "GET", "/analytic-dimensions/" + dimId, null).get("values")).hasSize(3);
        assertThat(okAs(b, 200, "GET", "/analytic-dimensions", null).get(0).get("valueCount").asLong()).isEqualTo(3);
        assertThat(call("PATCH", "/analytic-dimensions/" + dimId + "/values/" + p1, Map.of("parentId", p1), b.owner())
                .body().get("message").asString()).isEqualTo("A value cannot be its own parent");

        // posting validation
        post(b, expense, cash, 100, Map.of("project", "P1A"), "manual");
        post(b, expense, cash, 40, Map.of("project", "P1"), "manual");
        post(b, cash, revenue, 300, null, "manual");
        post(b, expense, cash, 7, Map.of("vehicleId", "V-1"), "manual"); // not a defined dimension: passes through
        assertThat(message(() -> post(b, expense, cash, 1, Map.of("project", "NOPE"), "manual")))
                .isEqualTo("Unknown value NOPE for dimension project");
        okAs(b, 200, "PATCH", "/analytic-dimensions/" + dimId + "/values/" + p2, Map.of("isActive", false));
        assertThat(message(() -> post(b, expense, cash, 1, Map.of("project", "P2"), "manual")))
                .isEqualTo("Value P2 of dimension project is inactive");
        okAs(b, 200, "PATCH", "/analytic-dimensions/" + dimId + "/values/" + p2, Map.of("isActive", true, "validFrom",
                LocalDate.now().plusDays(5).toString()));
        assertThat(message(() -> post(b, expense, cash, 1, Map.of("project", "P2"), "manual")))
                .startsWith("Value P2 of dimension project is not valid on ");
        okAs(b, 200, "PATCH", "/analytic-dimensions/" + dimId + "/values/" + p2, Map.of("clearValidity", true));
        post(b, expense, cash, 25, Map.of("project", "P2"), "manual");

        // account rules
        okAs(b, 200, "PUT", "/analytic-dimensions/account-rules/" + expense, Map.of("dimension", "project", "rule",
                "required"));
        assertThat(message(() -> post(b, expense, cash, 1, null, "manual"))).endsWith("requires dimension project");
        post(b, expense, cash, 1, null, "period_close"); // closing journals are exempt
        okAs(b, 200, "PUT", "/analytic-dimensions/account-rules/" + cash, Map.of("dimension", "project", "rule",
                "forbidden"));
        assertThat(message(() -> post(b, cash, revenue, 1, Map.of("project", "P1"), "manual")))
                .endsWith("does not take dimension project");
        assertThat(okAs(b, 200, "GET", "/analytic-dimensions/account-rules?dimension=project", null)).hasSize(2);
        // generic rules do not leak into cost-centre rules
        assertThat(okAs(b, 200, "GET", "/cost-centers/account-rules", null)).isEmpty();
        okAs(b, 200, "PUT", "/analytic-dimensions/account-rules/" + expense, Map.of("dimension", "project", "rule",
                "optional"));
        okAs(b, 200, "PUT", "/analytic-dimensions/account-rules/" + cash, Map.of("dimension", "project", "rule",
                "optional"));

        // summary by project: P1 rolls up P1A; unassigned holds the rest; totals tie to the P&L
        JsonNode sum = okAs(b, 200, "GET", "/analytic-dimensions/reports/summary?dimension=project", null);
        Map<String, JsonNode> byValue = new HashMap<>();
        sum.get("values").forEach(v -> byValue.put(v.get("value").asString(), v));
        assertThat(new BigDecimal(byValue.get("P1").get("expense").asString())).isEqualByComparingTo("40");
        assertThat(new BigDecimal(byValue.get("P1").get("rollupExpense").asString())).isEqualByComparingTo("140");
        assertThat(new BigDecimal(byValue.get("P1A").get("expense").asString())).isEqualByComparingTo("100");
        assertThat(new BigDecimal(byValue.get("P2").get("expense").asString())).isEqualByComparingTo("25");
        assertThat(new BigDecimal(sum.get("unassigned").get("income").asString())).isEqualByComparingTo("300");
        assertThat(new BigDecimal(sum.get("totals").get("expense").asString())).isEqualByComparingTo("173");

        // filtered trial balance: project P1 includes its child P1A
        JsonNode tb = okAs(b, 200, "GET", "/analytic-dimensions/reports/trial-balance?filter=project:P1", null);
        assertThat(tb.get("accounts")).hasSize(1);
        assertThat(new BigDecimal(tb.get("totals").get("debit").asString())).isEqualByComparingTo("140");
        assertThat(call("GET", "/analytic-dimensions/reports/trial-balance?filter=project", null, b.owner()).status())
                .isEqualTo(400);
        JsonNode lines = okAs(b, 200, "GET", "/analytic-dimensions/reports/lines?filter=project:P1A", null);
        assertThat(lines.get("total").asLong()).isEqualTo(1);
        assertThat(lines.get("data").get(0).get("dimensions").get("project").asString()).isEqualTo("P1A");

        // used values and dimensions cannot be deleted
        assertThat(call("DELETE", "/analytic-dimensions/" + dimId, null, b.owner()).body().get("message").asString())
                .isEqualTo("Dimension project is used on journal entries; deactivate it instead");
        assertThat(call("PATCH", "/analytic-dimensions/" + dimId + "/values/" + p2, Map.of("code", "P9"), b.owner())
                .body().get("message").asString()).isEqualTo("Value P2 is used on journal entries; its code cannot change");

        // tenancy: another organization sees nothing
        Books other = freshBooks();
        assertThat(okAs(other, 200, "GET", "/analytic-dimensions", null)).isEmpty();
        assertThat(call("GET", "/analytic-dimensions/" + dimId, null, other.owner()).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("document lines: invoice line dimensions reach the revenue GL lines, split per value")
    void documentLines() {
        Books b = freshBooks();
        String dimId = okAs(b, 201, "POST", "/analytic-dimensions", Map.of("code", "region", "name", "Region"))
                .get("id").asString();
        okAs(b, 201, "POST", "/analytic-dimensions/" + dimId + "/values", Map.of("code", "EAST", "name", "East"));
        okAs(b, 201, "POST", "/analytic-dimensions/" + dimId + "/values", Map.of("code", "WEST", "name", "West"));
        String customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"isCustomer\","
                + " \"updatedAt\") VALUES (?, ?, ?, 'Customer', false, true, now())").params(customer, b.org(),
                "C-" + customer.substring(0, 6)).update();
        String revenue = accountBySection(b.org(), "revenue");
        JsonNode draft = okAs(b, 200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate",
                LocalDate.now().toString(), "lines", List.of(
                        Map.of("accountId", revenue, "description", "East work", "quantity", 1, "unitPrice", 100),
                        Map.of("accountId", revenue, "description", "West work", "quantity", 1, "unitPrice", 60))));
        String invoice = draft.get("id").asString();
        JsonNode lines = okAs(b, 200, "GET", "/analytic-dimensions/documents/" + invoice + "/lines", null).get("lines");
        String east = lines.get(0).get("id").asString();
        String west = lines.get(1).get("id").asString();
        assertThat(call("PUT", "/analytic-dimensions/documents/" + invoice + "/lines", Map.of("lines", List.of(
                Map.of("lineId", east, "dimensions", Map.of("region", "NORTH")))), b.owner()).body().get("message")
                .asString()).isEqualTo("Unknown value NORTH for dimension region");
        okAs(b, 200, "PUT", "/analytic-dimensions/documents/" + invoice + "/lines", Map.of("lines", List.of(
                Map.of("lineId", east, "dimensions", Map.of("region", "EAST")),
                Map.of("lineId", west, "dimensions", Map.of("region", "WEST")))));
        Res post = call("POST", "/invoices/" + invoice + "/post", null, Map.of("Authorization", "Bearer " + b.owner(),
                "Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(post.status()).as(post.raw().body()).isBetween(200, 201);

        JsonNode sum = okAs(b, 200, "GET", "/analytic-dimensions/reports/summary?dimension=region", null);
        Map<String, String> income = new HashMap<>();
        sum.get("values").forEach(v -> income.put(v.get("value").asString(), v.get("income").asString()));
        assertThat(new BigDecimal(income.get("EAST"))).isEqualByComparingTo("100");
        assertThat(new BigDecimal(income.get("WEST"))).isEqualByComparingTo("60");
        assertThat(call("PUT", "/analytic-dimensions/documents/" + invoice + "/lines", Map.of("lines", List.of()),
                b.owner()).body().get("message").asString()).isEqualTo("Dimensions can only be changed on a draft document");
    }
}
