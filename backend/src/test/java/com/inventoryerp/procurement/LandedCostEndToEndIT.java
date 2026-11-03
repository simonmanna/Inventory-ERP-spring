package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.BooksGuard;
import com.inventoryerp.inventory.application.StockMove;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import tools.jackson.databind.JsonNode;

/**
 * Landed cost final acceptance (ADR-S053, plan "Final acceptance"; LC-3 on): the real business chain — import,
 * multi-supplier voucher with a generated bill, transfer to the plant, issue to production, a <b>late charge</b> while
 * goods sit in W1, at the plant and in WIP, sales at both places, the order completed, a supplier credit, the
 * reconciliation and trace, the <b>reversal</b> of the late charge after all that, and a corrected re-post. Every amount
 * of each landed step is asserted in its correct current destination; the reconciliation moves by exactly zero.
 */
@ExtendWith(BooksGuard.class)
class LandedCostEndToEndIT extends LandedCostFixtures {

    Map<String, BigDecimal> differences() {
        Map<String, BigDecimal> out = new HashMap<>();
        JsonNode r = ok(200, "GET", "/procurement/landed-cost-reports/reconciliation", null);
        for (JsonNode row : r.get("rows")) {
            out.put(row.get("key").asString(), new BigDecimal(row.get("difference").asString()));
        }
        return out;
    }

    List<String> mismatchCodes(String kind) {
        List<String> out = new ArrayList<>();
        for (JsonNode m : ok(200, "GET", "/procurement/landed-cost-reports/reconciliation", null).get(kind)) {
            out.add(m.get("code").asString());
        }
        return out;
    }

    String code(JsonNode v) {
        return v.get("code").asString();
    }

    JsonNode voucherJson(String id) {
        return ok(200, "GET", "/procurement/landed-cost-vouchers/" + id, null);
    }

    @Test
    @DisplayName("import → vouchers → transfer → production → late charge → sales → credit → reconcile → reverse → re-post")
    void businessChain() {
        String plant = location("E2E plant");
        String raw = product("AVCO", false, 0);
        Map<String, BigDecimal> recon0 = differences();

        // 1–2. Import 100 @ 50 into W1; freight (generated bill) + customs (bill entered later in payables).
        String grn = receive(raw, 100, 50);
        String a = voucher(List.of(grn), List.of(
                charge("kind", "freight", "amount", 1000, "supplierId", forwarder, "supplierInvoiceRef", "E2E-F-"
                        + suffix(), "billMode", "generate"),
                charge("kind", "duty", "amount", 400, "supplierId", vendor, "supplierInvoiceRef", "E2E-C-" + suffix(),
                        "billMode", "none")));
        approveAndPost(a);
        assertThat(avg(raw, w1)).isEqualByComparingTo("64");

        // 3–4. 60 to the plant; 40 of them issued to an open production order.
        transfer(raw, w1, plant, 60);
        String order = "PO-E2E-" + suffix();
        jdbc.sql("INSERT INTO \"ProductionOrder\" (id, \"organizationId\", \"orderCode\", \"outputProductId\","
                + " \"locationId\", \"plannedQty\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, 1, now())")
                .params(UUID.randomUUID().toString(), demoOrg, order, raw, plant).update();
        asAdmin(() -> stock.issue(StockMove.of(raw, plant, new BigDecimal(40)).moveType("production_consume")
                .source("production_order", order)));

        // 5. Late charge 500 = 5 per unit: 40 still in W1 → 200, 20 at the plant → 100, 40 in WIP → 200.
        Snap s = snap();
        String b = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 500)));
        approveAndPost(b);
        assertBooks(s, moves("stock", "300", "wip", "200", "clearing", "-500"));
        assertThat(avg(raw, w1)).isEqualByComparingTo("69");
        assertThat(avg(raw, plant)).isEqualByComparingTo("69");

        // 6. Sales at both places; the order completes (its WIP share waits for the CM-10 cascade).
        sell(raw, w1, 10);
        sell(raw, plant, 10);
        jdbc.sql("UPDATE \"ProductionOrder\" SET status = 'completed' WHERE \"orderCode\" = ?").param(order).update();

        // 7. The customs broker credits 100 (supplier credit): 1 per unit back from where the units are now.
        String dutyCharge = voucherJson(a).get("charges").get(1).get("id").asString();
        Res credit = call("POST", "/procurement/landed-cost-vouchers/" + a + "/credit", Map.of("charges",
                List.of(Map.of("chargeId", dutyCharge, "amount", 100))), key());
        assertThat(credit.status()).as(credit.raw().body()).isIn(200, 201);
        Snap sc = snap();
        approveAndPost(credit.body().get("id").asString());
        // A's layer: 30 units in W1, 10 at the plant, 40 WIP, 20 sold → −30, −10, −40, −20.
        assertBooks(sc, moves("stock", "-40", "wip", "-40", "cogs", "-20", "clearing", "100"));

        // 8. Reconciliation unchanged by all of it; no classification or location mismatch on these vouchers.
        assertThat(differences()).isEqualTo(recon0);
        List<String> ours = List.of(code(voucherJson(a)), code(voucherJson(b)));
        assertThat(mismatchCodes("classificationMismatches")).doesNotContainAnyElementsOf(ours);
        assertThat(mismatchCodes("locationMismatches")).doesNotContainAnyElementsOf(ours);

        // 9. Trace: the late charge's chain reaches the sale, the order's WIP and the plant pool through the hop.
        JsonNode trace = ok(200, "GET", "/procurement/landed-cost-reports/trace?voucherId=" + b, null);
        boolean hop = false;
        boolean wip = false;
        for (JsonNode adj : trace.get("adjustments")) {
            hop |= !adj.get("parentAdjustmentId").isNull() && "transfer_in".equals(adj.get("layerType").asString());
            for (JsonNode l : adj.get("lines")) {
                wip |= "wip".equals(l.get("target").asString()) && order.equals(l.get("targetRef").asString());
            }
        }
        assertThat(hop).as("adjustment on the plant pool, child of W1").isTrue();
        assertThat(wip).as("WIP line tagged with the order").isTrue();
        String line = trace.get("journal").get(0).get("id").asString();
        assertThat(ok(200, "GET", "/procurement/landed-cost-reports/trace?journalLineId=" + line, null)
                .get("voucher").get("id").asString()).isEqualTo(b);

        // 10. Reverse the late charge after two warehouses and production: every target unwinds what it received.
        // Journal reversal −300 stock / −200 WIP; the 10 + 10 units sold since keep their 5 each in COGS → reclass
        // Dr stock 100 / Cr COGS 100. Net: stock −200, WIP −200, COGS −100, clearing +500.
        Snap sr = snap();
        reverse(b);
        assertBooks(sr, moves("stock", "-200", "wip", "-200", "cogs", "-100", "clearing", "500"));
        assertThat(avg(raw, w1)).isEqualByComparingTo("63");
        assertThat(avg(raw, plant)).isEqualByComparingTo("63");
        assertThat(differences()).isEqualTo(recon0);

        // 11. Corrected re-post 450 = 4.5 per unit: W1 30 → 135, plant 10 → 45, WIP (completed order) 40 → 180,
        // sold 20 → 90.
        Snap sp = snap();
        approveAndPost(voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 450))));
        assertBooks(sp, moves("stock", "180", "wip", "180", "cogs", "90", "clearing", "-450"));
        assertThat(differences()).isEqualTo(recon0);
    }

    void transfer(String p, String from, String to, int qty) {
        asAdmin(() -> stock.transfer(StockMove.transfer(p, from, to, new BigDecimal(qty)).source("audit", suffix())));
    }

    @Test
    @DisplayName("trace and reconciliation are tenant-scoped: another organisation gets 404 / sees none of it")
    void tenantScope() {
        String p = product("AVCO", false, 0);
        String v = voucher(List.of(receive(p, 10, 50)), List.of(charge("kind", "freight", "amount", 10)));
        approveAndPost(v);
        String charge = voucherJson(v).get("charges").get(0).get("id").asString();
        String line = ok(200, "GET", "/procurement/landed-cost-reports/trace?voucherId=" + v, null).get("journal").get(0)
                .get("id").asString();
        for (String q : List.of("voucherId=" + v, "chargeId=" + charge, "journalLineId=" + line)) {
            assertThat(call("GET", "/procurement/landed-cost-reports/trace?" + q, null, otherToken).status())
                    .as(q).isEqualTo(404);
        }
        JsonNode other = call("GET", "/procurement/landed-cost-reports/reconciliation", null, otherToken).body();
        assertThat(other.toString()).doesNotContain(code(voucherJson(v)));
    }
}
