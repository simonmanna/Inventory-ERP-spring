package com.inventoryerp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Standard costing with GL variances and process costing (ADR-S008) on Testcontainers Postgres: standard activation
 * with revaluation and method conversion, purchase price variance on receipts, production at standard with the WIP
 * residual posted as variances, BOM roll-up → product standard, and a two-process cement line (weighted average) from
 * material issue through transfer to finished stock.
 */
class StandardProcessCostingIT extends MfgTestSupport {

    String today = LocalDate.now().toString();

    Map<String, String> key() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    JsonNode keyed(String path, Object body) {
        Res r = call("POST", path, body, key());
        assertThat(r.status()).as(path + " → " + r.raw().body()).isIn(200, 201);
        return r.body();
    }

    String variance(String kind) {
        String key = switch (kind) {
            case "purchase_price" -> "purchase_price_variance";
            case "yield" -> "production_yield_variance";
            default -> "standard_cost_revaluation";
        };
        return jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key = ?")
                .params(demoOrg, key).query(String.class).optional().orElse(null);
    }

    BigDecimal bal(String accountId) {
        return accountId == null ? BigDecimal.ZERO : balance(accountId);
    }

    JsonNode activateStandard(String productId, Object total, boolean convert) {
        JsonNode draft = ok(201, "POST", "/inventory/standard-costs", Map.of("productId", productId, "effectiveFrom",
                today, "materialCost", total, "convertMethod", convert));
        String id = draft.get("id").asString();
        ok(200, "POST", "/inventory/standard-costs/" + id + "/approve", null);
        return keyed("/inventory/standard-costs/" + id + "/activate", null);
    }

    void receive(String productId, int qty, int unitCost) {
        ok(200, "POST", "/procurement/goods-receipts/adhoc", Map.of("warehouseId", warehouse, "receivedAt",
                java.time.Instant.now().toString(), "lines", List.of(Map.of("productId", productId, "description", "x",
                        "quantity", qty, "unitCost", unitCost))));
    }

    @Test
    @DisplayName("standard: convert AVCO stock 10 @ 100 → 120 (+200 revaluation); receipts at 130 / 110 → PPV ±100")
    void standardReceiptsAndRevaluation() {
        String bolt = product("BOLT");
        stockIn(warehouse, bolt, 10, 100);
        BigDecimal sv0 = balance(mapped("stock_valuation"));

        JsonNode draft = ok(201, "POST", "/inventory/standard-costs", Map.of("productId", bolt, "effectiveFrom", today,
                "materialCost", 100, "labourCost", 20));
        assertThat(draft.get("totalCost").asString()).isEqualTo("120");
        assertThat(draft.get("status").asString()).isEqualTo("draft");
        String id = draft.get("id").asString();
        Res notApproved = call("POST", "/inventory/standard-costs/" + id + "/activate", null, key());
        assertThat(notApproved.body().get("message").asString()).isEqualTo("Only an approved standard cost can be activated");
        ok(200, "POST", "/inventory/standard-costs/" + id + "/approve", null);
        Res noConvert = call("POST", "/inventory/standard-costs/" + id + "/activate", null, key());
        assertThat(noConvert.body().get("message").asString()).endsWith("is costed at AVCO; set convertMethod to switch"
                + " it to standard cost");
        ok(200, "POST", "/inventory/standard-costs/" + id + "/cancel", null);

        JsonNode active = activateStandard(bolt, 120, true);
        assertThat(active.get("status").asString()).isEqualTo("active");
        assertThat(active.get("revaluationAmount").asString()).isEqualTo("200");
        assertThat(active.get("productCostingMethod").asString()).isEqualTo("STANDARD");
        assertThat(balance(mapped("stock_valuation")).subtract(sv0)).isEqualByComparingTo("200");

        BigDecimal sv1 = balance(mapped("stock_valuation"));
        // The DEMO org is shared across suites (other suites post PPV too), so the variance is measured as a delta.
        BigDecimal ppv0 = bal(variance("purchase_price"));
        receive(bolt, 10, 130);
        BigDecimal ppv = bal(variance("purchase_price")).subtract(ppv0);
        assertThat(ppv).isEqualByComparingTo("100");
        assertThat(balance(mapped("stock_valuation")).subtract(sv1)).isEqualByComparingTo("1200");
        BigDecimal ledgerUnit = jdbc.sql("SELECT \"unitCost\" FROM \"InventoryLedger\" WHERE \"productId\" = ? ORDER BY"
                + " \"createdAt\" DESC LIMIT 1").param(bolt).query(BigDecimal.class).single();
        assertThat(ledgerUnit).isEqualByComparingTo("120");
        receive(bolt, 10, 110);
        assertThat(bal(variance("purchase_price")).subtract(ppv0)).isEqualByComparingTo("0");

        JsonNode report = ok(200, "GET", "/inventory/standard-costs/variances?from=" + today + "&to=" + today
                + "&kind=purchase_price&productId=" + bolt, null);
        assertThat(report.get("rows")).hasSize(2);
        assertThat(report.get("total").asString()).isEqualTo("0");

        // A newer version supersedes and revalues the 30 on hand: 30 × (125 − 120) = 150.
        JsonNode v2 = activateStandard(bolt, 125, false);
        assertThat(v2.get("revaluationAmount").asString()).isEqualTo("150");
        assertThat(v2.get("version").asInt()).isEqualTo(3);
        assertThat(ok(200, "GET", "/inventory/standard-costs?productId=" + bolt + "&status=superseded", null))
                .hasSize(1);
        Res future = as("POST", "/inventory/standard-costs", Map.of("productId", bolt, "effectiveFrom",
                LocalDate.now().plusDays(10).toString(), "materialCost", 1));
        String futureId = future.body().get("id").asString();
        ok(200, "POST", "/inventory/standard-costs/" + futureId + "/approve", null);
        assertThat(call("POST", "/inventory/standard-costs/" + futureId + "/activate", null, key()).body().get("message")
                .asString()).startsWith("The standard takes effect on");
        assertThat(call("GET", "/inventory/standard-costs/" + futureId, null, otherToken).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("production at standard: 20 cakes at 900 (actual 1,000) → output 18,000, 2,000 yield variance, WIP 0;"
            + " BOM roll-up proposes 1,000 and revalues")
    void productionAtStandard() {
        String flour = product("SFLOUR");
        String cake = product("SCAKE");
        stockIn(bakery, flour, 10, 5000);
        activateStandard(cake, 900, true);
        BigDecimal wip0 = balance(wipAccount);
        BigDecimal yield0 = bal(variance("yield"));

        String bomId = bom(cake, flour, 20, 4, Map.of());
        JsonNode s = started(bomId);
        String id = s.get("id").asString();
        JsonNode done = ok(201, "POST", "/manufacturing/production-orders/" + id + "/complete", Map.of("qtyProduced", 20));
        assertThat(done.get("outputUnitCost").asString()).isEqualTo("900");
        assertThat(done.get("varianceJournalEntryId").isNull()).isFalse();
        assertThat(onHand(cake, warehouse)).isEqualByComparingTo("20");
        assertThat(balance(wipAccount)).isEqualByComparingTo(wip0);
        assertThat(bal(variance("yield")).subtract(yield0)).isEqualByComparingTo("2000");

        JsonNode proposed = ok(201, "POST", "/manufacturing/boms/" + bomId + "/product-standard", Map.of("effectiveFrom",
                today));
        assertThat(proposed.get("source").asString()).isEqualTo("bom_rollup");
        assertThat(new BigDecimal(proposed.get("totalCost").asString())).isEqualByComparingTo("1000");
        String pid = proposed.get("id").asString();
        ok(200, "POST", "/inventory/standard-costs/" + pid + "/approve", null);
        JsonNode act = keyed("/inventory/standard-costs/" + pid + "/activate", null);
        assertThat(act.get("revaluationAmount").asString()).isEqualTo("2000");
    }

    @Test
    @DisplayName("process costing: MIX (WA) → GRIND → cement stock; EU, transfer, closing WIP carried forward")
    void processCosting() {
        String limestone = product("LIME");
        String cement = product("CEMENT");
        stockIn(bakery, limestone, 1000, 10);
        String applied = mapped("overhead_absorbed");
        BigDecimal wip0 = balance(wipAccount);
        String sfx = suffix();

        JsonNode grind = ok(201, "POST", "/manufacturing/process-costing/processes", Map.of("code", "GRIND-" + sfx,
                "name", "Grinding", "sequence", 2, "outputProductId", cement, "outputLocationId", warehouse));
        JsonNode mix = ok(201, "POST", "/manufacturing/process-costing/processes", Map.of("code", "MIX-" + sfx, "name",
                "Mixing", "sequence", 1, "method", "weighted_average", "nextProcessId", grind.get("id").asString()));
        String mixId = mix.get("id").asString();
        assertThat(as("POST", "/manufacturing/process-costing/processes", Map.of("code", "MIX-" + sfx, "name", "x"))
                .status()).isEqualTo(409);
        assertThat(as("PATCH", "/manufacturing/process-costing/processes/" + mixId, Map.of("nextProcessId", mixId))
                .body().get("message").asString()).isEqualTo("A process cannot feed itself");

        String from = LocalDate.now().withDayOfMonth(1).toString();
        String to = LocalDate.now().toString();
        String period = ok(201, "POST", "/manufacturing/process-costing/processes/" + mixId + "/periods", Map.of(
                "periodFrom", from, "periodTo", to)).get("id").asString();
        assertThat(as("POST", "/manufacturing/process-costing/processes/" + mixId + "/periods", Map.of("periodFrom",
                from, "periodTo", to)).status()).isEqualTo(409);

        keyed("/manufacturing/process-costing/periods/" + period + "/materials", Map.of("productId", limestone,
                "locationId", bakery, "quantity", 500));
        keyed("/manufacturing/process-costing/periods/" + period + "/costs", Map.of("element", "labour", "amount", 1500,
                "creditAccountId", applied));
        keyed("/manufacturing/process-costing/periods/" + period + "/costs", Map.of("element", "overhead", "amount",
                1000, "creditAccountId", applied));
        assertThat(as("POST", "/manufacturing/process-costing/periods/" + period + "/costs", Map.of("element", "tea",
                "amount", 1)).status()).isEqualTo(400);
        assertThat(onHand(limestone, bakery)).isEqualByComparingTo("500");

        ok(200, "PATCH", "/manufacturing/process-costing/periods/" + period + "/units", Map.of("unitsStarted", 1000,
                "unitsCompleted", 700, "closingUnits", 200, "closingMaterialPct", 100, "closingConversionPct", 50));
        JsonNode unbalanced = ok(200, "GET", "/manufacturing/process-costing/periods/" + period, null);
        assertThat(unbalanced.get("costsAdded").get("conversion").asString()).isEqualTo("2500");
        Res bad = as("GET", "/manufacturing/process-costing/periods/" + period + "/preview", null);
        assertThat(bad.body().get("message").asString()).startsWith("Units do not balance");
        ok(200, "PATCH", "/manufacturing/process-costing/periods/" + period + "/units", Map.of("unitsCompleted", 800));

        JsonNode preview = ok(200, "GET", "/manufacturing/process-costing/periods/" + period + "/preview", null);
        JsonNode r = preview.get("result");
        assertThat(r.get("equivalentUnits").get("material").asString()).isEqualTo("1000");
        assertThat(r.get("equivalentUnits").get("conversion").asString()).isEqualTo("900");
        assertThat(r.get("completedCost").asString()).isEqualTo("6222.222222");
        assertThat(r.get("closingWipCost").asString()).isEqualTo("1277.777778");

        JsonNode closed = keyed("/manufacturing/process-costing/periods/" + period + "/close", null);
        assertThat(closed.get("status").asString()).isEqualTo("closed");
        String nextMix = closed.get("nextPeriodId").asString();
        JsonNode carried = ok(200, "GET", "/manufacturing/process-costing/periods/" + nextMix, null);
        assertThat(new BigDecimal(carried.get("openingUnits").asString())).isEqualByComparingTo("200");
        assertThat(new BigDecimal(carried.get("openingMaterialCost").asString())).isEqualByComparingTo("1000");
        assertThat(call("POST", "/manufacturing/process-costing/periods/" + period + "/close", null, key()).body()
                .get("message").asString()).isEqualTo("The process period is closed");

        JsonNode grindProc = ok(200, "GET", "/manufacturing/process-costing/processes/" + grind.get("id").asString(), null);
        String grindPeriod = grindProc.get("periods").get(0).get("id").asString();
        JsonNode gp = ok(200, "GET", "/manufacturing/process-costing/periods/" + grindPeriod, null);
        assertThat(gp.get("costsAdded").get("transferredIn").asString()).isEqualTo("6222.222222");
        assertThat(new BigDecimal(gp.get("unitsStarted").asString())).isEqualByComparingTo("800");
        keyed("/manufacturing/process-costing/periods/" + grindPeriod + "/costs", Map.of("element", "labour", "amount",
                800, "creditAccountId", applied));
        ok(200, "PATCH", "/manufacturing/process-costing/periods/" + grindPeriod + "/units", Map.of("unitsCompleted", 800));
        JsonNode gClosed = keyed("/manufacturing/process-costing/periods/" + grindPeriod + "/close", null);
        assertThat(new BigDecimal(gClosed.get("unitCost").asString())).isEqualByComparingTo("8.777778");
        assertThat(onHand(cement, warehouse)).isEqualByComparingTo("800");
        // WIP keeps exactly MIX's closing work in progress.
        assertThat(balance(wipAccount).subtract(wip0)).isEqualByComparingTo("1277.777778");

        JsonNode report = ok(200, "GET", "/manufacturing/process-costing/reports/cost-of-production?from=" + from
                + "&to=" + to, null);
        assertThat(report.toString()).contains("MIX-" + sfx).contains("GRIND-" + sfx);
        assertThat(call("GET", "/manufacturing/process-costing/periods/" + period, null, otherToken).status())
                .isEqualTo(404);
        assertThat(call("GET", "/manufacturing/process-costing/processes", null, otherToken).body()).isEmpty();
    }
}
