package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.inventory.application.StockMove;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Review 2026-11-17 (ACR-8 / ACR-14): late cost that reached the WIP of a production order and was carried on to the
 * order's output by a cost adjustment run is never taken out of WIP a second time — not by an accrual clearing (it is
 * taken out of the output instead) and not by reversing a run another run built on. Adjunct released into an order's
 * WIP is carried on to the output like any other late cost, and an accrual clearing takes it back from there.
 * <p>The organisation is shared, so each step is checked on this scenario's own rows and journal entries.
 */
class LateCostWipCascadeIT extends LandedCostFixtures {

    final String today = LocalDate.now(ZoneOffset.UTC).toString();

    String rule() {
        Res r = call("POST", "/procurement/landed-cost-accrual-rules", Map.of("scopeType", "supplier", "scopeId", vendor,
                "ratePct", 10), key());
        assertThat(r.status()).as(r.raw().body()).isIn(200, 201);
        return r.body().get("id").asString();
    }

    String order(String outputProduct) {
        String code = "PO-LCW-" + suffix();
        jdbc.sql("INSERT INTO \"ProductionOrder\" (id, \"organizationId\", \"orderCode\", \"outputProductId\","
                + " \"locationId\", \"plannedQty\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, 1, now())")
                .params(UUID.randomUUID().toString(), demoOrg, code, outputProduct, w1).update();
        return code;
    }

    void consume(String p, String code, int qty) {
        asAdmin(() -> stock.issue(StockMove.of(p, w1, new BigDecimal(qty)).moveType("production_consume")
                .source("production_order", code)));
    }

    void produce(String fg, String code, int qty, int unitCost) {
        asAdmin(() -> stock.receive(StockMove.of(fg, w1, new BigDecimal(qty)).unitCost(new BigDecimal(unitCost))
                .source("production_order", code)));
    }

    void complete(String code) {
        jdbc.sql("UPDATE \"ProductionOrder\" SET status = 'completed' WHERE \"organizationId\" = ? AND \"orderCode\" = ?")
                .params(demoOrg, code).update();
    }

    JsonNode costRun() {
        Res r = call("POST", "/costing/cost-adjustment-runs", Map.of("from", today, "to", today), key());
        assertThat(r.status()).as(r.raw().body()).isIn(200, 201);
        return r.body();
    }

    /** What posted run lines carried to {@code code}: lineage lines, or (release = true) adjunct released into WIP. */
    BigDecimal carried(String code, boolean release) {
        return jdbc.sql("SELECT coalesce(sum(amount), 0) FROM \"CostAdjustmentRunLine\" WHERE \"organizationId\" = ? AND"
                + " \"orderCode\" = ? AND status = 'posted' AND \"" + (release ? "releaseId" : "adjustmentLineId")
                + "\" IS NOT NULL").params(demoOrg, code).query(BigDecimal.class).single();
    }

    String accrue(String grn) {
        Res r = call("POST", "/procurement/landed-cost-accruals", Map.of("goodsReceiptId", grn), key());
        assertThat(r.status()).as(r.raw().body()).isIn(200, 201);
        return r.body().get("id").asString();
    }

    void clear(String accrual, String voucher) {
        Res r = call("POST", "/procurement/landed-cost-accruals/" + accrual + "/clear", Map.of("voucherId", voucher),
                key());
        assertThat(r.status()).as(r.raw().body()).isIn(200, 201);
    }

    /** Net debit per account role of one journal entry (accounts outside the landed-cost roles by id). */
    Map<String, BigDecimal> entry(String journalEntryId) {
        Map<String, String> roleOf = new HashMap<>();
        accounts().forEach((role, id) -> {
            if (id != null) {
                roleOf.putIfAbsent(id, role);
            }
        });
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.sql("SELECT \"accountId\", sum(\"baseDebit\" - \"baseCredit\") AS net FROM"
                + " \"JournalLine\" WHERE \"journalEntryId\" = ? GROUP BY 1 HAVING sum(\"baseDebit\" - \"baseCredit\") <> 0")
                .param(journalEntryId).query().listOfRows()) {
            String account = (String) r.get("accountId");
            out.merge(roleOf.getOrDefault(account, account), (BigDecimal) r.get("net"), BigDecimal::add);
        }
        return out;
    }

    Map<String, BigDecimal> clearingEntry(String accrual) {
        return entry(jdbc.sql("SELECT \"journalEntryId\" FROM \"LandedCostAccrualClearing\" WHERE \"accrualId\" = ? ORDER"
                + " BY \"createdAt\" DESC LIMIT 1").param(accrual).query(String.class).single());
    }

    @Test
    @DisplayName("accrual 500 (150 into an order's WIP), order done, run carries 150 on; clearing takes 150 out of the"
            + " order's cost of sales, not out of WIP again")
    void clearingAfterCascade() {
        String rule = rule();
        try {
            String p = product("AVCO", false, 0);
            String grn = receive(p, 100, 50);
            String code = order(p);
            consume(p, code, 30);
            String accrual = accrue(grn); // 10% of 5,000: 70 on hand → adjunct 350, 30 in the order → WIP 150
            complete(code);
            costRun();
            assertThat(carried(code, false)).isEqualByComparingTo("150");

            String voucher = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 500)));
            approveAndPost(voucher);
            clear(accrual, voucher);
            Map<String, BigDecimal> je = clearingEntry(accrual);
            assertThat(je).as(je.toString()).containsOnlyKeys("accrual", "adjunct", "cogs");
            assertThat(je.get("accrual")).isEqualByComparingTo("500");
            assertThat(je.get("adjunct")).isEqualByComparingTo("-350");
            assertThat(je.get("cogs")).as("the order had no output: its carried share was cost of sales")
                    .isEqualByComparingTo("-150");
            // the actual's own WIP share is carried on next, so WIP of the order nets to zero
            costRun();
            assertThat(carried(code, false)).isEqualByComparingTo("300");
        } finally {
            ok(204, "DELETE", "/procurement/landed-cost-accrual-rules/" + rule, null);
        }
    }

    @Test
    @DisplayName("partial clearing before the run: the run carries only what is still in WIP (150 − 90 = 60)")
    void partialClearingBeforeCascade() {
        String rule = rule();
        try {
            String p = product("AVCO", false, 0);
            String grn = receive(p, 100, 50);
            String code = order(p);
            consume(p, code, 30);
            String accrual = accrue(grn);
            // the voucher capitalised 300 on the receipt: clears 300 of 500 (60%), 90 of the 150 WIP share
            String voucher = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 300)));
            approveAndPost(voucher);
            clear(accrual, voucher);
            assertThat(clearingEntry(accrual).get("wip")).isEqualByComparingTo("-90");
            complete(code);
            costRun();
            assertThat(carried(code, false)).as("accrual 60 left in WIP + the voucher's 90").isEqualByComparingTo("150");

            // the rest of the estimate is closed: its WIP share was carried on, so it leaves through cost of sales
            Res closed = call("POST", "/procurement/landed-cost-accruals/" + accrual + "/close", null, key());
            assertThat(closed.status()).as(closed.raw().body()).isIn(200, 201);
            Map<String, BigDecimal> je = clearingEntry(accrual);
            assertThat(je.get("wip")).as(je.toString()).isNull();
            assertThat(je.get("cogs")).isEqualByComparingTo("-60");
        } finally {
            ok(204, "DELETE", "/procurement/landed-cost-accrual-rules/" + rule, null);
        }
    }

    @Test
    @DisplayName("adjunct released into an open order's WIP is carried on to its output; clearing takes it back from the"
            + " output's adjunct, not from cost of sales")
    void releasedIntoWipFollowsTheOutput() {
        String rule = rule();
        try {
            String p = product("AVCO", false, 0);
            String fg = product("AVCO", false, 0);
            String grn = receive(p, 100, 50);
            String accrual = accrue(grn); // all 100 on hand → adjunct 500
            String code = order(fg);
            consume(p, code, 30);
            Res released = call("POST", "/inventory/price-differences/release", null, key());
            assertThat(released.status()).as(released.raw().body()).isIn(200, 201);
            assertThat(jdbc.sql("SELECT coalesce(sum(r.amount), 0) FROM \"CostValueAdjustmentRelease\" r JOIN"
                    + " \"CostValueAdjustment\" a ON a.id = r.\"adjustmentId\" WHERE a.\"sourceId\" = ? AND r.target = 'wip'"
                    + " AND r.\"targetRef\" IN (?, (SELECT id FROM \"ProductionOrder\" WHERE \"orderCode\" = ?))")
                    .params(accrual, code, code).query(BigDecimal.class).single()).isEqualByComparingTo("150");

            produce(fg, code, 10, 150);
            complete(code);
            costRun();
            assertThat(carried(code, true)).as("the release into WIP is carried on").isEqualByComparingTo("150");

            String voucher = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 500)));
            approveAndPost(voucher);
            clear(accrual, voucher);
            Map<String, BigDecimal> je = clearingEntry(accrual);
            assertThat(je).as(je.toString()).containsOnlyKeys("accrual", "adjunct");
            assertThat(je.get("accrual")).isEqualByComparingTo("500");
            assertThat(je.get("adjunct")).as("350 still open on the material + 150 carried to the output's adjunct")
                    .isEqualByComparingTo("-500");
            costRun(); // the voucher's own WIP share: leave nothing waiting in WIP for other classes
        } finally {
            ok(204, "DELETE", "/procurement/landed-cost-accrual-rules/" + rule, null);
        }
    }

    /** On-hand adjunct a cost adjustment run left per location of {@code product}. */
    Map<String, BigDecimal> runAdjunct(String runId, String product) {
        Map<String, BigDecimal> out = new HashMap<>();
        for (Map<String, Object> r : jdbc.sql("SELECT \"locationId\", sum(\"onHandAmount\") AS amount, sum(\"openAmount\")"
                + " AS open FROM \"CostValueAdjustment\" WHERE \"organizationId\" = ? AND \"sourceType\" ="
                + " 'cost_adjustment_run' AND \"sourceId\" = ? AND \"productId\" = ? GROUP BY 1").params(demoOrg, runId,
                        product).query().listOfRows()) {
            out.put(r.get("locationId") + "", (BigDecimal) r.get("amount"));
            out.put(r.get("locationId") + ":open", (BigDecimal) r.get("open"));
        }
        return out;
    }

    void releaseRun() {
        Res released = call("POST", "/inventory/price-differences/release", null, key());
        assertThat(released.status()).as(released.raw().body()).isIn(200, 201);
    }

    @Test
    @DisplayName("ACR-9: cost carried to an output that moved to another warehouse lands on that warehouse's pool and is"
            + " released when the goods are sold there")
    void carriedCostFollowsTransferredOutput() {
        String p = product("AVCO", false, 0);
        String fg = product("AVCO", false, 0);
        String grn = receive(p, 100, 50);
        String code = order(fg);
        consume(p, code, 30);
        produce(fg, code, 10, 150);
        asAdmin(() -> stock.transfer(StockMove.transfer(fg, w1, w2, new BigDecimal(10)).source("audit", suffix())));
        complete(code);
        approveAndPost(voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000)))); // 300 → WIP
        String runId = costRun().get("id").asString();
        assertThat(carried(code, false)).isEqualByComparingTo("300");

        Map<String, BigDecimal> adjunct = runAdjunct(runId, fg);
        assertThat(adjunct.get(w2)).as("on the goods, at W2: " + adjunct).isEqualByComparingTo("300");
        assertThat(adjunct.getOrDefault(w1, BigDecimal.ZERO)).as("nothing left on the emptied W1 pool").isZero();

        releaseRun();
        assertThat(runAdjunct(runId, fg).get(w2 + ":open")).as("still on hand at W2: nothing released")
                .isEqualByComparingTo("300");
        sell(fg, w2, 10);
        releaseRun();
        assertThat(runAdjunct(runId, fg).get(w2 + ":open")).isZero();
        assertThat(jdbc.sql("SELECT coalesce(sum(r.amount), 0) FROM \"CostValueAdjustmentRelease\" r JOIN"
                + " \"CostValueAdjustment\" a ON a.id = r.\"adjustmentId\" WHERE a.\"sourceType\" = 'cost_adjustment_run' AND"
                + " a.\"sourceId\" = ? AND r.target = 'cogs'").param(runId).query(BigDecimal.class).single())
                .as("released to cost of sales when sold at W2").isEqualByComparingTo("300");
    }

    @Test
    @DisplayName("a run another run built on cannot be reversed first; reversing them in order puts WIP back")
    void reverseRunsInOrder() {
        String p = product("AVCO", false, 0);
        String fg = product("AVCO", false, 0);
        String grn = receive(p, 100, 50);
        String first = order(fg);
        consume(p, first, 30);
        produce(fg, first, 10, 150);
        String second = order(p);
        complete(first);
        approveAndPost(voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000)))); // 300 → WIP of first
        // the first order's output goes into the second (open) order: the carried 300 lands in its WIP
        consume(fg, second, 10);
        JsonNode run1 = costRun();
        complete(second);
        JsonNode run2 = costRun();
        assertThat(carried(second, false)).isEqualByComparingTo("300");

        Res refused = call("POST", "/costing/cost-adjustment-runs/" + run1.get("id").asString() + "/reverse", null,
                key());
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body().get("message").asString()).isEqualTo("Part of this amount was cascaded from WIP by"
                + " cost adjustment run " + run2.get("number").asString() + "; reverse that run first");
        Res r2 = call("POST", "/costing/cost-adjustment-runs/" + run2.get("id").asString() + "/reverse", null, key());
        assertThat(r2.status()).as(r2.raw().body()).isEqualTo(200);
        Res r1 = call("POST", "/costing/cost-adjustment-runs/" + run1.get("id").asString() + "/reverse", null, key());
        assertThat(r1.status()).as(r1.raw().body()).isEqualTo(200);
        assertThat(carried(first, false)).isZero();
        assertThat(carried(second, false)).isZero();
        costRun(); // carried on again (the second order is done now): leave nothing waiting in WIP
    }
}
