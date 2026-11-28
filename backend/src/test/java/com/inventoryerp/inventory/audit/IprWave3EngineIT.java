package com.inventoryerp.inventory.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.TestBillSettings;
import com.inventoryerp.TestSuppliers;
import com.inventoryerp.inventory.application.StockMove;
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
 * IPR wave 3 (stream E3): engine and accounting closures — AVCO 6 dp drift (E-1), receipt cost policy (C-3 / GL-6,
 * owner decision D3), waste / expiry accounts and goods in transit (S13), bill cancel issue-back (1d F6). Each scenario
 * ends with the integrity report and the GL tie-out.
 */
class IprWave3EngineIT extends InventoryAuditSupport {

    private BigDecimal ledgerValue(String productId, String loc) {
        return jdbc.sql("SELECT COALESCE(SUM(CASE WHEN \"quantityChange\" >= 0 THEN COALESCE(\"totalValue\", 0) ELSE"
                + " -COALESCE(\"totalValue\", 0) END), 0) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND \"locationId\" = ? AND \"valuationComponent\" IS NULL")
                .params(org, productId, loc).query(BigDecimal.class).single();
    }

    /** Integrity checks green, with the failing checks in the assertion description. */
    private void green() {
        java.util.List<String> failing = failingChecks(integrityReport(), "checks");
        assertThat(failing).as("integrity checks " + failing).isEmpty();
    }

    private BigDecimal totalOnHand(String productId) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ?").params(org, productId).query(BigDecimal.class).single();
    }

    // ------------------------------------------------------------------ E-1

    @Test
    @DisplayName("E-1: a receipt at a non-terminating unit cost (6 dp average) ties to the ledger and GL while on hand"
            + " and leaves no value at quantity 0")
    void avcoNonTerminatingCost() {
        String p = product("E1-BEAD");
        // 1,000,000 over 300,000 units: the average is stored as 3.333333, so 300,000 × 3.333333 = 999,999.90.
        receive(p, main, 300_000, new BigDecimal("3.3333333333"), null);
        assertThat(ledgerValue(p, main)).isGreaterThan(new BigDecimal("999999.99"));
        green();
        assertGlTieOut();
        issue(p, main, 100_000, null);
        green();
        assertGlTieOut();
        issue(p, main, 200_000, null);
        assertThat(onHand(p, main)).isEqualByComparingTo("0");
        assertThat(ledgerValue(p, main)).as("no value at quantity 0").isEqualByComparingTo("0");
        green();
        assertGlTieOut();
    }

    // ------------------------------------------------------------------ C-3 / GL-6 (D3)

    @Test
    @DisplayName("C-3 / GL-6 (D3): blank receipt cost = current cost; an explicit 0 needs a reason; AVCO is never"
            + " valued at the cost price")
    void receiptCostPolicy() {
        String p = product("C3-COST", Map.of("costPrice", 9));
        okA(200, "POST", "/inventory/direct-stock/in", direct(main, List.of(item(p, 10, 5))));
        // Blank cost: the quant's current average (5), not the cost price (9).
        okA(200, "POST", "/inventory/direct-stock/in", direct(main, List.of(item(p, 10, null))));
        assertThat(ledgerValue(p, main)).isEqualByComparingTo("100");
        // An explicit 0 without a reason is refused; with a reason it is received at no cost.
        Res zero = api("POST", "/inventory/direct-stock/in", direct(main, List.of(item(p, 5, 0))));
        assertThat(zero.status()).as(zero.raw().body()).isEqualTo(400);
        assertThat(zero.body().get("message").asString()).contains("give a reason (zeroCostReason)");
        Map<String, Object> free = new LinkedHashMap<>(item(p, 5, 0));
        free.put("zeroCostReason", "Supplier sample");
        okA(200, "POST", "/inventory/direct-stock/in", direct(main, List.of(free)));
        assertThat(onHand(p, main)).isEqualByComparingTo("25");
        assertThat(ledgerValue(p, main)).isEqualByComparingTo("100");
        assertThat(count("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                + " AND notes LIKE '%Zero cost: Supplier sample%'", org, p)).isEqualTo(1);
        // AVCO valued at its own average even where the average is 0 (no cost-price fallback).
        String q = product("C3-NOAVG", Map.of("costPrice", 7));
        Map<String, Object> freeQ = new LinkedHashMap<>(item(q, 3, 0));
        freeQ.put("zeroCostReason", "Found stock");
        okA(200, "POST", "/inventory/direct-stock/in", direct(main, List.of(freeQ)));
        green();
        assertGlTieOut();
    }

    // ------------------------------------------------------------------ S13

    @Test
    @DisplayName("S13: waste and expiry write-offs post to their own expense accounts when mapped")
    void wasteAndExpiryAccounts() {
        String adj = account("stock_adjustment_expense");
        String waste = jdbc.sql("SELECT a.id FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\""
                + " WHERE a.\"organizationId\" = ? AND c.key = 'operating_expense' AND a.\"isPostable\" AND a.\"isActive\""
                + " AND a.id <> ? ORDER BY a.code LIMIT 1").params(org, adj).query(String.class).single();
        jdbc.sql("INSERT INTO \"AccountMapping\" (id, \"organizationId\", key, \"accountId\", \"updatedAt\") VALUES (?, ?,"
                + " 'stock_waste_expense', ?, now())").params(UUID.randomUUID().toString(), org, waste).update();
        String p = product("S13-WASTE");
        receive(p, main, 10, 4, null);
        BigDecimal adjBefore = balance("stock_adjustment_expense");
        BigDecimal wasteBefore = balance("stock_waste_expense");
        issue(p, main, 2, m -> m.moveType("waste"));
        assertThat(balance("stock_waste_expense").subtract(wasteBefore)).isEqualByComparingTo("8");
        // Expiry is not mapped: it falls back to stock adjustment expense.
        issue(p, main, 1, m -> m.moveType("expiry_write_off"));
        assertThat(balance("stock_adjustment_expense").subtract(adjBefore)).isEqualByComparingTo("4");
        green();
        assertGlTieOut();
    }

    @Test
    @DisplayName("S13: with goods in transit separated, stock at a transit location is valued in Stock In Transit"
            + " (dispatch, loss in transit, arrival) and the GL ties out by account throughout")
    void goodsInTransit() {
        String transit = location("TRN-" + UUID.randomUUID().toString().substring(0, 4), "In transit", "transit");
        orgSetting("inventory.transitGlSeparation", "true");
        try {
            String p = product("S13-GIT");
            receive(p, main, 10, 5, null);
            BigDecimal sit0 = balance("stock_in_transit");
            asOrg(() -> stock.transfer(StockMove.transfer(p, main, transit, BigDecimal.valueOf(4))
                    .source("audit_transfer", "t-" + UUID.randomUUID())));
            assertThat(balance("stock_in_transit").subtract(sit0)).as("dispatched into transit").isEqualByComparingTo("20");
            JsonNode byAccount = integrityCheck(integrityReport(), "gl_tieout_by_account");
            assertThat(byAccount.get("ok").asBoolean()).as("by account while in transit " + byAccount).isTrue();
            // One unit lost in transit: credited to Stock In Transit.
            issue(p, transit, 1, m -> m.moveType("waste"));
            assertThat(balance("stock_in_transit").subtract(sit0)).isEqualByComparingTo("15");
            asOrg(() -> stock.transfer(StockMove.transfer(p, transit, alt, BigDecimal.valueOf(3))
                    .source("audit_transfer", "t-" + UUID.randomUUID())));
            assertThat(balance("stock_in_transit").subtract(sit0)).as("arrived").isEqualByComparingTo("0");
            assertThat(onHand(p, alt)).isEqualByComparingTo("3");
            green();
            assertGlTieOut();
        } finally {
            orgSetting("inventory.transitGlSeparation", "false");
        }
    }

    // ------------------------------------------------------------------ 1d F6: bill cancel

    @Test
    @DisplayName("1d F6: cancelling an auto-received bill issues its receipt back, linked (reversesLedgerId), with the"
            + " stock credit at the receipt's cost")
    void billCancelIssuesBack() {
        String supplier = TestSuppliers.supplier(jdbc, org);
        String p = product("F6-BILL");
        TestBillSettings.autoReceive(jdbc, org, settingResolver, () -> {
            String bill = okA(200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate",
                    LocalDate.now().toString(), "lines", List.of(Map.of("productId", p, "quantity", 6, "unitPrice",
                            3)))).get("id").asString();
            okA(200, "POST", "/vendor-bills/" + bill + "/post", Map.of());
            assertThat(totalOnHand(p)).isEqualByComparingTo("6");
            assertThat(count("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                    + " AND \"referenceType\" = 'vendor_bill' AND \"sourceLineId\" IS NOT NULL", org, p))
                    .as("bill receipt names its bill line").isEqualTo(1);
            okA(200, "POST", "/vendor-bills/" + bill + "/cancel", Map.of("reason", "Raised in error"));
            assertThat(totalOnHand(p)).isEqualByComparingTo("0");
            assertThat(count("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                    + " AND \"referenceType\" = 'vendor_bill_cancel' AND \"reversesLedgerId\" IS NOT NULL", org, p))
                    .as("issue-back linked to the receipt").isEqualTo(1);
            assertThat(jdbc.sql("SELECT COALESCE(SUM(CASE WHEN \"quantityChange\" >= 0 THEN \"totalValue\" ELSE"
                    + " -\"totalValue\" END), 0) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND \"productId\" = ?")
                    .params(org, p).query(BigDecimal.class).single()).isEqualByComparingTo("0");
            green();
            assertGlTieOut();
        });
    }
}
