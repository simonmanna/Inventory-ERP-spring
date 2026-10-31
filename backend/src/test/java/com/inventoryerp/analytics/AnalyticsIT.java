package com.inventoryerp.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Track C Phase 5 (ADR-S020): every dashboard answers with its tiles, filters are validated, role-based access, tenant
 * isolation and the branch data scope. Formulas belong to the reports the dashboards call and are tested there.
 */
class AnalyticsIT extends ApiTestSupport {

    private static final List<String> DASHBOARDS = List.of("quarry", "aggregates", "blocks", "inventory", "procurement",
            "sales", "fleet", "maintenance", "finance", "branches");

    String sfx = UUID.randomUUID().toString().substring(0, 8);
    String today = LocalDate.now().toString();
    String branchA;
    String branchB;
    String customer;
    String role;

    @BeforeAll
    void fixtures() {
        branchA = ok(201, "POST", "/branches", Map.of("code", "KA-" + sfx, "name", "KPI A")).get("id").asString();
        branchB = ok(201, "POST", "/branches", Map.of("code", "KB-" + sfx, "name", "KPI B")).get("id").asString();
        customer = ok(201, "POST", "/partners", Map.of("code", "KC-" + sfx, "name", "KPI customer " + sfx, "isCustomer",
                true)).get("id").asString();
        String product = ok(201, "POST", "/products", Map.of("code", "KP-" + sfx, "name", "KPI svc " + sfx,
                "productType", "service", "salesPrice", 10, "costPrice", 0)).get("id").asString();
        postInvoice(product, 3, branchA);
        postInvoice(product, 7, branchB);
        role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "KPI viewer " + sfx, new String[] {
                    "analytics:view", "analytics:sales", "analytics:finance", "analytics:branch"}).update();
    }

    private void postInvoice(String product, int quantity, String branch) {
        JsonNode draft = ok(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today, "lines",
                List.of(Map.of("productId", product, "quantity", quantity, "unitPrice", 10))));
        String id = draft.get("id").asString();
        ok(200, "POST", "/invoices/" + id + "/post", null);
        jdbc.sql("UPDATE \"Document\" SET \"branchId\" = ? WHERE id = ?").params(branch, id).update();
    }

    private static Map<String, JsonNode> tiles(JsonNode dashboard) {
        Map<String, JsonNode> out = new HashMap<>();
        dashboard.get("tiles").forEach(t -> out.put(t.get("key").asString(), t.get("value")));
        return out;
    }

    private static BigDecimal dec(JsonNode value) {
        return new BigDecimal(value.asString());
    }

    /** A viewer holding {@link #role}, restricted to {@code branch}. */
    private String restrictedViewer(String branch) {
        String email = "kpi-" + UUID.randomUUID().toString().substring(0, 8) + "@demo.test";
        String id = createUser(demoOrg, email, "Kpi@123456", role);
        ok(200, "PUT", "/user-data-scopes/" + id, Map.of("branchIds", List.of(branch), "locationIds", List.of()));
        return login("DEMO", email, "Kpi@123456");
    }

    @Test
    @DisplayName("every route needs a login and its own permission")
    void authorization() {
        assertThat(assertAuthorizationMatrix("com.inventoryerp.analytics")).isEqualTo(21);
    }

    @Test
    @DisplayName("every dashboard answers an administrator with tiles and its filters")
    void dashboards() {
        for (String path : DASHBOARDS) {
            JsonNode d = ok(200, "GET", "/analytics/" + path + "?from=" + today + "&to=" + today, null);
            assertThat(d.get("tiles").size()).as(path).isGreaterThan(0);
            assertThat(d.get("supportedFilters").size()).as(path).isGreaterThan(0);
        }
    }

    @Test
    @DisplayName("sales figures and their drill-down follow the customer filter")
    void salesAndDrillDown() {
        String q = "?from=" + today + "&to=" + today + "&partnerId=" + customer;
        Map<String, JsonNode> t = tiles(ok(200, "GET", "/analytics/sales" + q, null));
        assertThat(dec(t.get("revenue"))).isEqualByComparingTo("100");
        assertThat(dec(t.get("customerDebt"))).isPositive();
        JsonNode docs = ok(200, "GET", "/analytics/sales/documents" + q + "&kpi=invoices", null);
        assertThat(docs.get("meta").get("total").asLong()).isEqualTo(2);
        assertThat(docs.get("data").get(0).get("type").asString()).isEqualTo("invoice");
    }

    @Test
    @DisplayName("a filter a dashboard cannot apply is reported, not dropped silently")
    void ignoredFilters() {
        JsonNode d = ok(200, "GET", "/analytics/finance?productId=" + UUID.randomUUID(), null);
        assertThat(d.get("ignoredFilters").get(0).asString()).isEqualTo("product");
    }

    @Test
    @DisplayName("bad periods, unknown branches and unknown drill-downs are 400")
    void validation() {
        assertThat(as("GET", "/analytics/sales?from=2026-02-01&to=2026-01-01", null).status()).isEqualTo(400);
        assertThat(as("GET", "/analytics/sales?from=2020-01-01&to=2026-01-01", null).status()).isEqualTo(400);
        assertThat(as("GET", "/analytics/sales?from=nope", null).status()).isEqualTo(400);
        assertThat(as("GET", "/analytics/sales?branchId=" + UUID.randomUUID(), null).status()).isEqualTo(400);
        assertThat(as("GET", "/analytics/sales/documents?kpi=nope", null).status()).isEqualTo(400);
        assertThat(as("GET", "/analytics/sales/documents", null).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("another organization sees none of it")
    void tenantIsolation() {
        JsonNode other = call("GET", "/analytics/sales?from=" + today + "&to=" + today, null, otherToken).body();
        assertThat(dec(tiles(other).get("revenue"))).isEqualByComparingTo("0");
        JsonNode docs = call("GET", "/analytics/sales/documents?kpi=receivables", null, otherToken).body();
        assertThat(docs.get("meta").get("total").asLong()).isZero();
    }

    @Test
    @DisplayName("a restricted user sees their branch only, and never another branch by filter")
    void branchScope() {
        String token = restrictedViewer(branchA);
        String q = "?from=" + today + "&to=" + today;
        Map<String, JsonNode> t = tiles(call("GET", "/analytics/sales" + q + "&partnerId=" + customer, null, token)
                .body());
        assertThat(dec(t.get("revenue"))).isEqualByComparingTo("30");
        assertThat(call("GET", "/analytics/sales" + q + "&branchId=" + branchB, null, token).status()).isEqualTo(403);
        JsonNode docs = call("GET", "/analytics/sales/documents" + q + "&partnerId=" + customer + "&kpi=invoices", null,
                token).body();
        assertThat(docs.get("meta").get("total").asLong()).isEqualTo(1);
        JsonNode branches = call("GET", "/analytics/branches" + q, null, token).body();
        assertThat(branches.get("tables").get(0).get("rows").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("a restricted user never gets organization-wide cash")
    void restrictedFinance() {
        JsonNode d = call("GET", "/analytics/finance", null, restrictedViewer(branchA)).body();
        assertThat(tiles(d)).doesNotContainKeys("netCashFlow", "closingCash");
        assertThat(d.get("notes").toString()).contains("cashOrgWide");
        assertThat(tiles(ok(200, "GET", "/analytics/finance", null))).containsKey("netCashFlow");
    }

    @Test
    @DisplayName("a dashboard outside the role is refused, and the filter lists follow the scope")
    void roleVisibility() {
        String token = restrictedViewer(branchA);
        assertThat(call("GET", "/analytics/inventory", null, token).status()).isEqualTo(403);
        JsonNode options = call("GET", "/analytics/filter-options", null, token).body();
        assertThat(options.get("branches").size()).isEqualTo(1);
        assertThat(options.get("branches").get(0).get("id").asString()).isEqualTo(branchA);
    }
}
