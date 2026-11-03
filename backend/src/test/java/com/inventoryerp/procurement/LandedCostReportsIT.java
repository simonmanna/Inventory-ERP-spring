package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.inventory.application.StockMove;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Landed cost LC-6 reports (ADR-S053): every figure hand-computed from the scenario in the display name. */
class LandedCostReportsIT extends LandedCostFixtures {

    String today = LocalDate.now().toString();

    JsonNode report(String kind) {
        return ok(200, "GET", "/procurement/landed-cost-reports/analysis/" + kind + "?from=" + today + "&to=" + today,
                null);
    }

    JsonNode row(JsonNode rows, String field, String value) {
        for (JsonNode r : rows) {
            if (value.equals(r.get(field).asString())) {
                return r;
            }
        }
        return null;
    }

    @Test
    @DisplayName("valuation impact / by warehouse: 60 of 100 moved to W2, 1000 posted → W1 400, W2 600")
    void valuationImpactByWarehouse() {
        String p = product("AVCO", false, 0);
        String grn = receive(p, 100, 50);
        asAdmin(() -> stock.transfer(StockMove.transfer(p, w1, w2, new BigDecimal(60)).source("audit", suffix())));
        approveAndPost(voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000))));
        JsonNode impact = report("valuation-impact");
        BigDecimal atW1 = BigDecimal.ZERO;
        BigDecimal atW2 = BigDecimal.ZERO;
        for (JsonNode r : impact) {
            if (p.equals(r.get("productId").asString())) {
                assertThat(r.get("costingMethod").asString()).isEqualTo("AVCO");
                if (w1.equals(r.get("locationId").asString())) {
                    atW1 = new BigDecimal(r.get("stock").asString());
                } else if (w2.equals(r.get("locationId").asString())) {
                    atW2 = new BigDecimal(r.get("stock").asString());
                }
            }
        }
        assertThat(atW1).isEqualByComparingTo("400");
        assertThat(atW2).isEqualByComparingTo("600");
        assertThat(row(report("by-warehouse"), "locationId", w2)).isNotNull();
    }

    @Test
    @DisplayName("profitability: 40 sold after a 1000 charge on 100 → COGS 2400 = purchase 2000 + landed 400")
    void profitabilityByLayer() {
        String p = product("AVCO", false, 0);
        String grn = receive(p, 100, 50);
        approveAndPost(voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000))));
        sell(p, w1, 40);
        JsonNode prof = report("profitability");
        JsonNode r = row(prof.get("products"), "productId", p);
        assertThat(r).isNotNull();
        assertThat(new BigDecimal(r.get("cogs").asString())).isEqualByComparingTo("2400");
        assertThat(new BigDecimal(r.get("landed").asString())).isEqualByComparingTo("400");
        assertThat(new BigDecimal(r.get("purchase").asString())).isEqualByComparingTo("2000");
        assertThat(prof.get("sellingAndAdminExpenses").asString()).isNotBlank();
    }

    @Test
    @DisplayName("profitability: a late charge on 40 already sold → landed 400 in those units' COGS line")
    void profitabilityLateCharge() {
        String p = product("AVCO", false, 0);
        String grn = receive(p, 100, 50);
        sell(p, w1, 40);
        approveAndPost(voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000))));
        JsonNode r = row(report("profitability").get("products"), "productId", p);
        assertThat(new BigDecimal(r.get("landed").asString())).isEqualByComparingTo("400");
        assertThat(new BigDecimal(r.get("cogs").asString())).isEqualByComparingTo("2000");
    }

    @Test
    @DisplayName("by cost centre: the charge's cost centre wins over the voucher's")
    void byCostCentre() {
        String cc = UUID.randomUUID().toString();
        String code = "CC-LC6-" + suffix();
        jdbc.sql("INSERT INTO \"CostCenter\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?, ?, now())")
                .params(cc, demoOrg, code, "Import project").update();
        String p = product("AVCO", false, 0);
        approveAndPost(voucher(List.of(receive(p, 10, 50)), List.of(charge("kind", "freight", "amount", 300,
                "costCenterId", cc), charge("kind", "handling", "amount", 50, "treatment", "expense", "costCenterId",
                cc))));
        JsonNode r = row(report("by-cost-centre"), "code", code);
        assertThat(new BigDecimal(r.get("capitalised").asString())).isEqualByComparingTo("300");
        assertThat(new BigDecimal(r.get("expensed").asString())).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("variance: accrual 1000, actual 1200 → estimate 1000, actual 1200, variance 200, FX column apart")
    void estimateVariance() {
        String rule = call("POST", "/procurement/landed-cost-accrual-rules", Map.of("scopeType", "supplier", "scopeId",
                vendor, "ratePct", 10), key()).body().get("id").asString();
        try {
            String p = product("AVCO", false, 0);
            String grn = receive(p, 100, 100);
            Res a = call("POST", "/procurement/landed-cost-accruals", Map.of("goodsReceiptId", grn), key());
            String v = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1200)));
            approveAndPost(v);
            assertThat(call("POST", "/procurement/landed-cost-accruals/" + a.body().get("id").asString() + "/clear",
                    Map.of("voucherId", v), key()).status()).isIn(200, 201);
            JsonNode r = row(report("variance"), "voucherId", v);
            assertThat(new BigDecimal(r.get("estimate").asString())).isEqualByComparingTo("1000");
            assertThat(new BigDecimal(r.get("actual").asString())).isEqualByComparingTo("1200");
            assertThat(new BigDecimal(r.get("variance").asString())).isEqualByComparingTo("200");
            assertThat(new BigDecimal(r.get("realisedFx").asString())).isEqualByComparingTo("0");
        } finally {
            ok(204, "DELETE", "/procurement/landed-cost-accrual-rules/" + rule, null);
        }
    }

    @Test
    @DisplayName("production structure: 40 issued, 10 good output → yield 25 %, landed in materials, cost per output unit")
    void productionStructure() {
        String raw = product("AVCO", false, 0);
        String fg = product("AVCO", false, 0);
        String grn = receive(raw, 100, 50);
        String order = "PO-LC6-" + suffix();
        jdbc.sql("INSERT INTO \"ProductionOrder\" (id, \"organizationId\", \"orderCode\", \"outputProductId\","
                + " \"locationId\", \"plannedQty\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, 10, now())")
                .params(UUID.randomUUID().toString(), demoOrg, order, fg, w1).update();
        asAdmin(() -> stock.issue(StockMove.of(raw, w1, new BigDecimal(40)).moveType("production_consume")
                .source("production_order", order)));
        approveAndPost(voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000))));
        asAdmin(() -> stock.receive(StockMove.of(fg, w1, new BigDecimal(10)).moveType("production_output")
                .source("production_order", order).unitCost(new BigDecimal(200))));
        jdbc.sql("UPDATE \"ProductionOrder\" SET status = 'completed', \"updatedAt\" = now() WHERE \"orderCode\" = ?")
                .param(order).update();
        JsonNode r = row(report("production-structure"), "orderCode", order);
        assertThat(new BigDecimal(r.get("yieldPct").asString())).isEqualByComparingTo("25");
        assertThat(new BigDecimal(r.get("materials").asString())).isEqualByComparingTo("2000");
        assertThat(new BigDecimal(r.get("landedInMaterials").asString())).isEqualByComparingTo("400");
        // Output 10 × 200 = 2000 at receipt + 400 landed waiting in WIP → 2400 / 10 = 240 per output unit.
        assertThat(new BigDecimal(r.get("costPerOutputUnit").asString())).isEqualByComparingTo("240");
    }
}
