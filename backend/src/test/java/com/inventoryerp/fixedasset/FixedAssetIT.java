package com.inventoryerp.fixedasset;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Fixed assets on Testcontainers Postgres (ENABLE_ASSETS on in the test profile): categories, the register,
 * depreciation runs with GL posting, the lifecycle sub-records, dashboards, validation, tenant isolation and the
 * 401/403 matrix (54 routes).
 */
class FixedAssetIT extends ApiTestSupport {

    String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    JsonNode category(String method) {
        return ok(201, "POST", "/asset-categories", Map.of("name", "Cat " + suffix(), "depreciationMethod", method));
    }

    JsonNode asset(Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(Map.of("name", "Laptop " + suffix()));
        body.putAll(extra);
        return ok(201, "POST", "/fixed-assets", body);
    }

    @Test
    @DisplayName("categories: CRUD with parent and _count, root tree, delete refused while assets exist")
    void categories() {
        JsonNode parent = category("straight_line");
        String pid = parent.get("id").asString();
        assertThat(parent.get("_count").get("assets").asLong()).isZero();
        assertThat(parent.get("parent").isNull()).isTrue();
        JsonNode child = ok(201, "POST", "/asset-categories", Map.of("name", "Child " + suffix(), "parentId", pid));
        assertThat(child.get("parent").get("id").asString()).isEqualTo(pid);
        asset(Map.of("categoryId", child.get("id").asString()));

        JsonNode tree = ok(200, "GET", "/asset-categories/all", null);
        JsonNode root = null;
        for (JsonNode r : tree) {
            if (r.get("id").asString().equals(pid)) {
                root = r;
            }
        }
        assertThat(root).isNotNull();
        assertThat(root.get("children")).hasSize(1);
        assertThat(root.get("children").get(0).get("_count").get("assets").asLong()).isEqualTo(1);

        Res refused = as("DELETE", "/asset-categories/" + child.get("id").asString(), null);
        assertThat(refused.status()).isEqualTo(404);
        assertThat(refused.body().get("message").asString()).isEqualTo("Cannot delete category with 1 assigned assets");
        JsonNode renamed = ok(200, "PATCH", "/asset-categories/" + pid, Map.of("name", "Renamed " + suffix()));
        assertThat(renamed.get("name").asString()).startsWith("Renamed");
        assertThat(as("DELETE", "/asset-categories/" + pid, null).status()).isEqualTo(204);
        Res gone = as("GET", "/asset-categories/" + pid, null);
        assertThat(gone.body().get("message").asString()).isEqualTo("AssetCategory " + pid + " not found");
        assertThat(ok(200, "GET", "/asset-categories?pageSize=5", null).get("meta").get("pageSize").asInt())
                .isEqualTo(5);
    }

    @Test
    @DisplayName("register: sequence code, value default, filters, lookups, detail includes, delete / restore")
    void register() {
        String barcode = "BC-" + suffix();
        JsonNode a = asset(Map.of("purchaseCost", 1500, "barcode", barcode, "location", "Front Office"));
        String id = a.get("id").asString();
        String code = a.get("assetCode").asString();
        assertThat(code).startsWith("AST-");
        assertThat(a.get("currentValue").asString()).isEqualTo("1500");
        assertThat(a.get("status").asString()).isEqualTo("active");
        assertThat(a.get("usefulLife").asInt()).isEqualTo(60);
        assertThat(a.get("category").isNull()).isTrue();

        assertThat(ok(200, "GET", "/fixed-assets/by-code/" + code, null).get("id").asString()).isEqualTo(id);
        assertThat(ok(200, "GET", "/fixed-assets/by-barcode/" + barcode, null).get("id").asString()).isEqualTo(id);
        assertThat(as("GET", "/fixed-assets/by-barcode/nope", null).body().get("message").asString())
                .isEqualTo("Asset with barcode nope not found");
        assertThat(ok(200, "GET", "/fixed-assets/search?q=" + barcode.toLowerCase(), null)).hasSize(1);
        JsonNode list = ok(200, "GET", "/fixed-assets?location=front&search=" + barcode, null);
        assertThat(list.get("meta").get("total").asLong()).isEqualTo(1);
        assertThat(as("GET", "/fixed-assets?status=broken", null).status()).isEqualTo(400);

        JsonNode one = ok(200, "GET", "/fixed-assets/" + id, null);
        for (String k : List.of("category", "acquisition", "assignments", "transfers", "depreciations",
                "maintenanceLogs", "repairs", "warranties", "insurance", "inspections", "disposals", "revaluations",
                "checkInOuts", "documents")) {
            assertThat(one.has(k)).as(k).isTrue();
        }
        assertThat(ok(200, "PATCH", "/fixed-assets/" + id, Map.of("notes", "spare")).get("notes").asString())
                .isEqualTo("spare");
        Res bad = as("POST", "/fixed-assets", Map.of("name", ""));
        assertThat(bad.status()).isEqualTo(400);

        assertThat(as("DELETE", "/fixed-assets/" + id, null).status()).isEqualTo(204);
        assertThat(as("GET", "/fixed-assets/" + id, null).status()).isEqualTo(404);
        assertThat(as("PATCH", "/fixed-assets/" + id + "/restore", null).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("depreciation: straight line, idempotent per period, GL posting Dr expense / Cr accumulated")
    void depreciation() {
        String cat = category("straight_line").get("id").asString();
        String id = asset(Map.of("purchaseCost", 1200, "salvageValue", 0, "usefulLife", 12, "categoryId", cat))
                .get("id").asString();
        String base = "/fixed-assets/" + id + "/depreciation";
        JsonNode run = ok(201, "POST", base + "/run", Map.of("period", "2026-01", "assetId", id));
        assertThat(run).hasSize(1);
        assertThat(run.get(0).get("depreciationAmount").asString()).isEqualTo("100");
        assertThat(run.get(0).get("bookValue").asString()).isEqualTo("1100");
        assertThat(run.get(0).get("isPosted").asBoolean()).isFalse();
        assertThat(ok(200, "GET", "/fixed-assets/" + id, null).get("currentValue").asString()).isEqualTo("1100");
        assertThat(ok(201, "POST", base + "/run", Map.of("period", "2026-01", "assetId", id))).isEmpty();

        JsonNode second = ok(201, "POST", base + "/run", Map.of("period", "2026-02", "assetId", id,
                "postEntries", true));
        assertThat(second.get(0).get("accumulatedDepr").asString()).isEqualTo("200");
        String depId = second.get(0).get("id").asString();
        Map<String, Object> posted = jdbc.sql("SELECT \"isPosted\", \"journalEntryId\" FROM \"AssetDepreciation\""
                + " WHERE id = ?").param(depId).query().singleRow();
        assertThat(posted.get("isPosted")).isEqualTo(true);
        String je = (String) posted.get("journalEntryId");
        List<Map<String, Object>> lines = jdbc.sql("SELECT a.code, l.debit, l.credit FROM \"JournalLine\" l JOIN"
                + " \"Account\" a ON a.id = l.\"accountId\" WHERE l.\"journalEntryId\" = ? ORDER BY l.debit DESC")
                .param(je).query().listOfRows();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).get("code")).isEqualTo("5600");
        assertThat(((BigDecimal) lines.get(0).get("debit"))).isEqualByComparingTo("100");
        assertThat(lines.get(1).get("code")).isEqualTo("1490");
        assertThat(((BigDecimal) lines.get(1).get("credit"))).isEqualByComparingTo("100");

        JsonNode again = ok(201, "POST", base + "/" + depId + "/post", null);
        assertThat(again.get("journalEntryId").asString()).isEqualTo(je);
        String firstId = run.get(0).get("id").asString();
        JsonNode manual = ok(201, "POST", base + "/" + firstId + "/post", null);
        assertThat(manual.get("isPosted").asBoolean()).isTrue();
        assertThat(ok(200, "GET", base, null)).hasSize(2);
        JsonNode byPeriod = ok(200, "GET", base + "/period/2026-02", null);
        assertThat(byPeriod.toString()).contains(depId);
        assertThat(as("POST", base + "/" + UUID.randomUUID() + "/post", null).status()).isEqualTo(404);
        assertThat(as("POST", base + "/run", Map.of()).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("lifecycle: maintenance, repair, assignment, check-in/out, warranty, insurance, transfer, revaluation,"
            + " acquisition, disposal")
    void lifecycle() {
        String id = asset(Map.of("purchaseCost", 800, "location", "Store")).get("id").asString();
        String p = "/fixed-assets/" + id;

        JsonNode m = ok(201, "POST", p + "/maintenance", Map.of("title", "Service", "maintenanceType", "preventive"));
        assertThat(m.get("status").asString()).isEqualTo("scheduled");
        assertThat(ok(200, "GET", p, null).get("status").asString()).isEqualTo("under_maintenance");
        ok(200, "PATCH", p + "/maintenance/" + m.get("id").asString(), Map.of("status", "completed"));
        assertThat(ok(200, "GET", p, null).get("status").asString()).isEqualTo("active");

        JsonNode r = ok(201, "POST", p + "/repairs", Map.of("breakdownDate", "2026-03-01"));
        assertThat(ok(200, "GET", p, null).get("status").asString()).isEqualTo("in_repair");
        ok(200, "PATCH", p + "/repairs/" + r.get("id").asString(), Map.of("status", "completed"));
        assertThat(ok(200, "GET", p, null).get("status").asString()).isEqualTo("active");

        JsonNode asg = ok(201, "POST", p + "/assignments", Map.of("assignedToId", "emp-1", "assignedToType",
                "employee"));
        assertThat(ok(200, "GET", p, null).get("assignedToType").asString()).isEqualTo("employee");
        JsonNode returned = ok(200, "PATCH", p + "/assignments/" + asg.get("id").asString()
                + "/return?condition=good", null);
        assertThat(returned.get("conditionAfter").asString()).isEqualTo("good");
        JsonNode co = ok(201, "POST", p + "/checkinout", Map.of("checkedOutTo", "Ann"));
        assertThat(co.get("status").asString()).isEqualTo("checked_out");
        assertThat(ok(200, "PATCH", p + "/checkinout/" + co.get("id").asString() + "/checkin", null)
                .get("status").asString()).isEqualTo("returned");

        String soon = java.time.LocalDate.now().plusDays(10).toString();
        ok(201, "POST", p + "/warranties", Map.of("warrantyStart", "2026-01-01", "warrantyEnd", soon));
        assertThat(ok(200, "GET", p + "/warranties/expiring?days=30", null).toString()).contains(id);
        JsonNode ins = ok(201, "POST", p + "/insurance", Map.of("policyNumber", "P-1", "premium", 50));
        assertThat(ok(200, "PATCH", p + "/insurance/" + ins.get("id").asString(), Map.of("company", "Acme"))
                .get("company").asString()).isEqualTo("Acme");
        ok(201, "POST", p + "/inspections", Map.of("isWorking", true));

        ok(201, "POST", p + "/transfers", Map.of("fromLocation", "Store", "toLocation", "Kitchen"));
        assertThat(ok(200, "GET", p, null).get("location").asString()).isEqualTo("Kitchen");
        ok(201, "POST", p + "/revaluations", Map.of("previousValue", 800, "newValue", 700));
        assertThat(ok(200, "GET", p, null).get("currentValue").asString()).isEqualTo("700");

        assertThat(as("GET", p + "/acquisition", null).status()).isEqualTo(404);
        ok(201, "POST", p + "/acquisition", Map.of("vendor", "Dell", "freight", 20));
        assertThat(ok(201, "POST", p + "/acquisition", Map.of("vendor", "HP")).get("freight").asString())
                .isEqualTo("20");
        assertThat(ok(200, "GET", p + "/acquisition", null).get("vendor").asString()).isEqualTo("HP");
        assertThat(as("DELETE", p + "/acquisition", null).status()).isEqualTo(200);

        JsonNode d = ok(201, "POST", p + "/disposal", Map.of("disposalMethod", "sold", "disposalValue", 750));
        assertThat(d.get("bookValueAtDisposal").asString()).isEqualTo("700");
        assertThat(d.get("gainLoss").asString()).isEqualTo("50");
        JsonNode after = ok(200, "GET", p, null);
        assertThat(after.get("status").asString()).isEqualTo("disposed");
        assertThat(after.get("currentValue").asString()).isEqualTo("0");
        Res twice = as("POST", p + "/disposal", Map.of("disposalMethod", "sold"));
        assertThat(twice.body().get("message").asString()).isEqualTo("Asset " + id + " already disposed");
        assertThat(as("POST", p + "/disposal", Map.of()).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("dashboards and reports")
    void dashboards() {
        asset(Map.of("purchaseCost", 100, "department", "Kitchen", "purchaseDate", "2025-01-15"));
        JsonNode summary = ok(200, "GET", "/fixed-assets-dashboard/summary", null);
        assertThat(summary.get("totalAssets").asLong()).isPositive();
        assertThat(summary.has("byCategory")).isTrue();
        JsonNode upcoming = ok(200, "GET", "/fixed-assets-dashboard/upcoming", null);
        assertThat(upcoming.get("recentAssets").size()).isPositive();
        assertThat(ok(200, "GET", "/fixed-assets-dashboard/reports/by-department", null).has("Kitchen")).isTrue();
        assertThat(ok(200, "GET", "/fixed-assets-dashboard/reports/by-status", null).has("active")).isTrue();
        assertThat(ok(200, "GET", "/fixed-assets-dashboard/reports/age-analysis", null).size()).isPositive();
        assertThat(ok(200, "GET", "/fixed-assets-dashboard/reports/unknown", null).size()).isZero();
        assertThat(ok(200, "GET", "/fixed-assets/dashboard", null).has("disposalCount")).isTrue();
    }

    @Test
    @DisplayName("tenant isolation and 401/403 matrix (54 routes)")
    void isolationAndAuth() {
        String id = asset(Map.of()).get("id").asString();
        assertThat(call("GET", "/fixed-assets/" + id, null, otherToken).status()).isIn(403, 404);
        assertThat(assertAuthorizationMatrix("com.inventoryerp.fixedasset")).isEqualTo(54);
    }
}
