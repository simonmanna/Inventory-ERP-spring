package com.inventoryerp.inventory.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.kernel.security.Permissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * IPR Phase 3, gate G3: the branch-scope matrix (S-1) and the cost-visibility matrix (S-2) on the inventory reads.
 *
 * <ul>
 *   <li>S-1: a user restricted to one location, to two locations, and an unrestricted user read the stock levels, the
 *       item detail, every inventory report and the integrity report. A blank location filter covers the caller's
 *       locations (was 400 "Choose a location" with two or more); a location outside the access is 403.</li>
 *   <li>S-2: a user without {@code inventory_valuation:read} gets every cost and value field as {@code null} on the
 *       stock items, item detail, stock levels, register, waste and stock-out reads; quantities stay.</li>
 * </ul>
 */
class IprBranchScopeCostIT extends InventoryAuditSupport {

    private static final String PASSWORD = "Scope@12345";

    String third;
    String product;
    String oneToken;
    String twoToken;
    String noCostToken;
    String wasteId;

    @BeforeAll
    void fixtures() {
        third = location("THIRD", "Third Store", "warehouse");
        product = product("IPR-SCOPE", Map.of("costPrice", 2));
        okA(200, "POST", "/inventory/direct-stock/in", direct(main, List.of(item(product, 5, 2))));
        okA(200, "POST", "/inventory/direct-stock/in", direct(alt, List.of(item(product, 3, 2))));
        okA(200, "POST", "/inventory/direct-stock/in", direct(third, List.of(item(product, 7, 2))));
        wasteId = okA(200, "POST", "/inventory/waste", Map.of("locationId", main, "responsibleById", auditUserId,
                "approvedById", auditUserId, "category", "spoiled", "items", List.of(Map.of("productId", product,
                        "qty", 1)))).get("id").asString();
        okA(200, "POST", "/inventory/waste/" + wasteId + "/approve", null);
        JsonNode out = okA(200, "POST", "/inventory/stock-outs", Map.of("locationId", alt, "responsibleById",
                auditUserId, "approvedById", auditUserId, "category", "sample", "items", List.of(Map.of("productId",
                        product, "qty", 1))));
        okA(200, "POST", "/inventory/stock-outs/" + out.get("id").asString() + "/approve", null);

        oneToken = scopedUser(List.of(main), Permissions.ALL.toArray(String[]::new));
        twoToken = scopedUser(List.of(main, alt), Permissions.ALL.toArray(String[]::new));
        noCostToken = scopedUser(null, everyPermissionBut(Set.of(Permissions.INVENTORY_VALUATION__READ)));
    }

    /** A user of the audit org with a role of {@code permissions}, restricted to {@code locations} (null: none). */
    String scopedUser(List<String> locations, String[] permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, org, "Scope " + role, permissions).update();
        String email = "scope-" + UUID.randomUUID().toString().substring(0, 8) + "@audit.test";
        String userId = createUser(org, email, PASSWORD, role);
        if (locations != null) {
            okA(200, "PUT", "/user-data-scopes/" + userId, Map.of("branchIds", List.of(), "locationIds", locations));
        }
        return login(orgCode, email, PASSWORD);
    }

    Res get(String token, String path) {
        return call("GET", path, null, token);
    }

    JsonNode ok(String token, String path) {
        Res r = get(token, path);
        assertThat(r.status()).as("GET " + path + " → " + r.raw().body()).isEqualTo(200);
        return r.body();
    }

    JsonNode stockLevel(String token, String query) {
        JsonNode page = ok(token, "/inventory/product-stock-levels?search=IPR-SCOPE" + query);
        for (JsonNode row : page.get("data")) {
            if (product.equals(row.get("id").asString())) {
                return row;
            }
        }
        throw new AssertionError("product not listed: " + page);
    }

    /** Every inventory read that takes an optional location filter (S-1 list). */
    static final List<String> SCOPED_READS = List.of(
            "/inventory/product-stock-levels",
            "/inventory/reports/expiring",
            "/inventory/reports/reorder",
            "/inventory/reports/movements",
            "/inventory/reports/item-movements",
            "/inventory/reports/valuation",
            "/inventory/reports/movement-analysis",
            "/inventory/reports/reconciliation",
            "/inventory/reports/stock-health",
            "/inventory/reports/negative-stock",
            "/inventory/reports/integrity");

    // ------------------------------------------------------------------ S-1

    @Test
    @DisplayName("S-1: a user restricted to two locations reads every scoped report over both, not a 400")
    void twoLocationUserGetsScopedReads() {
        List<String> refused = new ArrayList<>();
        for (String path : SCOPED_READS) {
            Res r = get(twoToken, path);
            if (r.status() != 200) {
                refused.add(path + " → " + r.status() + " " + r.raw().body());
            }
        }
        Res detail = get(twoToken, "/inventory/items/" + product);
        if (detail.status() != 200) {
            refused.add("/inventory/items/{id} → " + detail.status() + " " + detail.raw().body());
        }
        assertThat(refused).as("reads refused to a two-location user").isEmpty();
    }

    @Test
    @DisplayName("S-1 matrix: one location, two locations and all locations see exactly their stock")
    void branchScopeMatrix() {
        // main 5 - 1 waste = 4; alt 3 - 1 stock-out = 2; third 7.
        assertThat(num(stockLevel(oneToken, "").get("totalQuantity"))).isEqualByComparingTo("4");
        assertThat(num(stockLevel(twoToken, "").get("totalQuantity"))).isEqualByComparingTo("6");
        assertThat(num(stockLevel(auditToken, "").get("totalQuantity"))).isEqualByComparingTo("13");
        assertThat(stockLevel(twoToken, "").get("locationBreakdown")).hasSize(2);
        assertThat(num(stockLevel(twoToken, "&locationId=" + alt).get("totalQuantity"))).isEqualByComparingTo("2");

        JsonNode detail = ok(twoToken, "/inventory/items/" + product);
        assertThat(num(detail.get("totalOnHand"))).isEqualByComparingTo("6");
        assertThat(detail.get("items")).hasSize(2);
        for (JsonNode l : detail.get("recentLedger")) {
            assertThat(l.get("locationId").asString()).isIn(main, alt);
        }

        JsonNode valuation = ok(twoToken, "/inventory/reports/valuation?productId=" + product);
        assertThat(num(valuation.get("totals").get("quantity"))).isEqualByComparingTo("6");
        JsonNode movements = ok(twoToken, "/inventory/reports/item-movements?productId=" + product);
        assertThat(num(movements.get("data").get(0).get("closingQty"))).isEqualByComparingTo("6");
        JsonNode recon = ok(twoToken, "/inventory/reports/reconciliation?includeMatched=true");
        for (JsonNode row : recon.get("rows")) {
            assertThat(row.get("locationId").asString()).isIn(main, alt);
        }
        JsonNode integrity = ok(twoToken, "/inventory/reports/integrity");
        assertThat(integrity.get("ok").asBoolean()).as(integrity.toString()).isTrue();
        assertThat(integrity.path("locationId").isNull() || integrity.path("locationId").isMissingNode()).isTrue();

        // A location outside the access is refused, whatever the number of locations.
        for (String token : List.of(oneToken, twoToken)) {
            Res outside = get(token, "/inventory/product-stock-levels?locationId=" + third);
            assertThat(outside.status()).as(outside.raw().body()).isEqualTo(403);
            assertThat(get(token, "/inventory/items/" + product + "?locationId=" + third).status()).isEqualTo(403);
            assertThat(get(token, "/inventory/reports/integrity?locationId=" + third).status()).isEqualTo(403);
        }
        assertIntegrityGreen();
        assertGlTieOut();
    }

    // ------------------------------------------------------------------ S-2

    @Test
    @DisplayName("S-2 matrix: without inventory_valuation:read every cost and value is null; with it they are shown")
    void costVisibilityMatrix() {
        JsonNode level = stockLevel(noCostToken, "");
        assertThat(level.get("averageCost").isNull()).as(level.toString()).isTrue();
        assertThat(level.get("totalValue").isNull()).isTrue();
        assertThat(num(level.get("totalQuantity"))).isEqualByComparingTo("13");
        JsonNode seen = stockLevel(auditToken, "");
        assertThat(num(seen.get("averageCost"))).isEqualByComparingTo("2");
        assertThat(num(seen.get("totalValue"))).isEqualByComparingTo("26");

        for (JsonNode row : ok(noCostToken, "/inventory/items?search=IPR-SCOPE").get("data")) {
            assertThat(row.get("runningAverageCost").isNull()).as(row.toString()).isTrue();
            assertThat(row.get("quantity").isNull()).isFalse();
        }
        assertThat(ok(auditToken, "/inventory/items?search=IPR-SCOPE").get("data").get(0).get("runningAverageCost")
                .isNull()).isFalse();

        JsonNode detail = ok(noCostToken, "/inventory/items/" + product);
        assertThat(detail.get("totalValue").isNull()).isTrue();
        assertThat(detail.get("product").get("costPrice").isNull()).isTrue();
        assertThat(detail.get("items").get(0).get("runningAverageCost").isNull()).isTrue();
        for (JsonNode l : detail.get("recentLedger")) {
            assertThat(l.get("unitCost").isNull()).isTrue();
            assertThat(l.get("totalValue").isNull()).isTrue();
        }
        assertThat(ok(auditToken, "/inventory/items/" + product).get("totalValue").isNull()).isFalse();

        JsonNode register = ok(noCostToken, "/inventory/reports/register/all");
        assertThat(register.get("rows").size()).isGreaterThan(0);
        for (JsonNode row : register.get("rows")) {
            assertThat(row.get("unitCost").isNull()).as(row.toString()).isTrue();
            assertThat(row.get("value").isNull()).isTrue();
            assertThat(row.get("qty").isNull()).isFalse();
        }
        assertThat(register.get("summary").get("grossValue").isNull()).isTrue();
        assertThat(ok(auditToken, "/inventory/reports/register/all").get("rows").get(0).get("value").isNull())
                .isFalse();

        JsonNode waste = ok(noCostToken, "/inventory/waste/" + wasteId);
        assertThat(waste.get("totalValue").isNull()).isTrue();
        assertThat(waste.get("items").get(0).get("unitCost").isNull()).isTrue();
        assertThat(waste.get("items").get(0).get("totalCost").isNull()).isTrue();
        for (JsonNode l : waste.get("ledger")) {
            assertThat(l.get("unitCost").isNull()).isTrue();
            assertThat(l.get("totalValue").isNull()).isTrue();
        }
        for (JsonNode je : waste.get("journalEntries")) {
            for (JsonNode line : je.get("lines")) {
                assertThat(line.get("debit").isNull()).isTrue();
                assertThat(line.get("credit").isNull()).isTrue();
            }
        }
        assertThat(ok(auditToken, "/inventory/waste/" + wasteId).get("totalValue").isNull()).isFalse();
        assertThat(ok(noCostToken, "/inventory/waste/summary").get("totalValue").isNull()).isTrue();

        for (JsonNode so : ok(noCostToken, "/inventory/stock-outs")) {
            assertThat(so.get("totalValue").isNull()).as(so.toString()).isTrue();
            for (JsonNode line : so.get("items")) {
                assertThat(line.get("unitCost").isNull()).isTrue();
                assertThat(line.get("totalCost").isNull()).isTrue();
            }
        }
        assertIntegrityGreen();
        assertGlTieOut();
    }
}
