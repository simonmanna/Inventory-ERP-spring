package com.inventoryerp.inventory.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService.GlReceiptContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Ports of the Nest concurrency audits: {@code inv-audit-concurrency.spec.ts} (INV-024 strict / permissive, INV-025),
 * the INV-002 concurrent-approval cases of {@code inv-p0-remediation.spec.ts}, and P0-3 (last unit) / P2 (concurrent
 * count submits) of {@code inv-readiness-remediation.spec.ts}. Parallelism stays at or below 10 so the waiting
 * transactions never exhaust the 20-connection pool (Nest: 20 / 5 workers).
 */
class InventoryAuditConcurrencyIT extends InventoryAuditSupport {

    static final int CONCURRENCY = 5;

    @AfterEach
    void permissive() {
        orgSetting("inventory.allowNegativeStock", "true");
    }

    /** Issue through the engine; returns null on success, else the failure message. */
    String tryIssue(String productId, int qty) {
        try {
            issue(productId, main, qty, null);
            return null;
        } catch (RuntimeException e) {
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    // ------------------------------------------------------------------ inv-audit-concurrency.spec.ts

    @Test
    @DisplayName("INV-024 strict: 10 concurrent sales against 5 units → exactly 5 succeed, on-hand 0")
    void strictOversellRace() throws Exception {
        orgSetting("inventory.allowNegativeStock", "false");
        String p = product("CONC-STRICT", Map.of("stockPolicy", "block"));
        receive(p, 5, 10);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> tryIssue(p, 1));
        }
        List<String> results = parallel(tasks);
        List<String> rejected = results.stream().filter(r -> r != null).toList();
        assertThat(results.size() - rejected.size()).isEqualTo(5);
        assertThat(rejected).hasSize(5).allMatch(m -> m.startsWith("Insufficient stock for "));
        assertThat(onHand(p, main)).isEqualByComparingTo("0");
        assertThat(ledgerSum(p, main)).isEqualByComparingTo("0");
        assertThat(count("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                + " AND type = 'issue'", org, p)).isEqualTo(5);
    }

    @Test
    @DisplayName("INV-024 permissive (shipped default): oversell allowed, the ledger chain stays coherent")
    void permissiveOversellChain() throws Exception {
        String p = product("CONC-PERMISSIVE", Map.of("stockPolicy", "block"));
        receive(p, 4, 10);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> tryIssue(p, 1));
        }
        assertThat(parallel(tasks)).allMatch(r -> r == null);
        assertThat(onHand(p, main)).isEqualByComparingTo("-6");
        assertThat(ledgerSum(p, main)).isEqualByComparingTo(onHand(p, main));
        assertThat(quantInvariantBreaks()).isEmpty();
    }

    @Test
    @DisplayName("INV-025: concurrent receipts blend the moving average without losing value")
    void concurrentReceipts() throws Exception {
        String p = product("CONC-RECEIPT");
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            int n = i;
            tasks.add(() -> asOrg(() -> stock.receiveForDocument(StockMove.of(p, main, BigDecimal.TEN)
                    .unitCost(BigDecimal.TEN), new GlReceiptContext("audit_receipt", "cr-" + n + "-" + p, Instant.now(),
                            null))) != null);
        }
        assertThat(parallel(tasks)).allMatch(Boolean::booleanValue);
        Map<String, Object> quant = jdbc.sql("SELECT quantity, \"runningAverageCost\" FROM \"StockItem\" WHERE"
                + " \"organizationId\" = ? AND \"productId\" = ? AND \"locationId\" = ?").params(org, p, main).query()
                .singleRow();
        assertThat((BigDecimal) quant.get("quantity")).isEqualByComparingTo("100");
        assertThat(((BigDecimal) quant.get("runningAverageCost")).setScale(6, RoundingMode.HALF_UP))
                .isEqualByComparingTo("10");
        assertThat(ledgerSum(p, main)).isEqualByComparingTo("100");
    }

    // ------------------------------------------------------------------ inv-p0-remediation.spec.ts (INV-002)

    List<Integer> approveConcurrently(String path) throws Exception {
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < CONCURRENCY; i++) {
            tasks.add(() -> api("POST", path, null).status());
        }
        return parallel(tasks);
    }

    @Test
    @DisplayName("INV-002: concurrent approvals of one stock-out issue once (one ledger row, one journal entry)")
    void concurrentStockOutApproval() throws Exception {
        String p = product("P0-SO", Map.of("costPrice", 10));
        receive(p, 50, 10);
        JsonNode doc = okA(200, "POST", "/inventory/stock-outs", Map.of("locationId", main, "category", "general_use",
                "responsibleById", auditUserId, "approvedById", auditUserId, "items", List.of(Map.of("productId", p,
                        "qty", 4))));
        List<Integer> statuses = approveConcurrently("/inventory/stock-outs/" + doc.get("id").asString() + "/approve");
        assertThat(statuses.stream().filter(s -> s == 200).count()).as(statuses.toString()).isEqualTo(1);
        assertThat(statuses).allMatch(s -> s == 200 || s == 400 || s == 409);
        String code = doc.get("outCode").asString();
        assertThat(count("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND \"referenceType\" ="
                + " 'stock_out' AND \"referenceId\" = ?", org, code)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"sourceType\" ="
                + " 'stock_out' AND \"sourceId\" = ?", org, code)).isEqualTo(1);
        assertThat(onHand(p, main)).isEqualByComparingTo("46");
    }

    @Test
    @DisplayName("INV-002: concurrent approvals of one transfer move stock once")
    void concurrentTransferApproval() throws Exception {
        String p = product("P0-TRF", Map.of("costPrice", 10));
        receive(p, 30, 10);
        JsonNode doc = okA(200, "POST", "/inventory/transfers", Map.of("fromLocationId", main, "toLocationId", alt,
                "responsibleById", auditUserId, "approvedById", auditUserId, "items", List.of(Map.of("productId", p,
                        "qtyRequested", 5))));
        List<Integer> statuses = approveConcurrently("/inventory/transfers/" + doc.get("id").asString() + "/approve");
        assertThat(statuses.stream().filter(s -> s == 200).count()).as(statuses.toString()).isEqualTo(1);
        assertThat(onHand(p, main)).isEqualByComparingTo("25");
        assertThat(onHand(p, alt)).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("INV-002: concurrent approvals of one adjustment post once")
    void concurrentAdjustmentApproval() throws Exception {
        String p = product("P0-ADJ", Map.of("costPrice", 10));
        receive(p, 20, 10);
        JsonNode doc = okA(200, "POST", "/inventory/adjustments", Map.of("locationId", main, "reason", "cycle_count",
                "responsibleById", auditUserId, "approvedById", auditUserId, "items", List.of(Map.of("productId", p,
                        "qtyActual", 17))));
        List<Integer> statuses = approveConcurrently("/inventory/adjustments/" + doc.get("id").asString() + "/approve");
        assertThat(statuses.stream().filter(s -> s == 200).count()).as(statuses.toString()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                + " AND type IN ('adjustment_in', 'adjustment_out')", org, p)).isEqualTo(1);
        assertThat(onHand(p, main)).isEqualByComparingTo("17");
    }

    // ------------------------------------------------------------------ inv-readiness-remediation.spec.ts

    @Test
    @DisplayName("P0-3: concurrent direct stock-outs of the last unit — exactly one succeeds, never negative")
    void lastUnitDirectStockOut() throws Exception {
        // Owner decision (direct-stock.service.ts, deviation 7): with allowNegativeStock on a direct stock-out simply
        // proceeds, so "never negative" is the strict-mode contract; the spec runs it with the setting off.
        orgSetting("inventory.allowNegativeStock", "false");
        String p = product("RDY-LAST", Map.of("costPrice", 10));
        receive(p, 1, 10);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < CONCURRENCY; i++) {
            tasks.add(() -> api("POST", "/inventory/direct-stock/out", direct(main, List.of(item(p, 1, null))))
                    .status());
        }
        List<Integer> statuses = parallel(tasks);
        assertThat(statuses.stream().filter(s -> s == 200).count()).as(statuses.toString()).isEqualTo(1);
        assertThat(onHand(p, main)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("P2: concurrent submits of one count post a single adjustment")
    void concurrentCountSubmits() throws Exception {
        String loc = location("CNT-" + UUID.randomUUID().toString().substring(0, 6), "Count store", "warehouse");
        String p = product("RDY-CNT", Map.of("costPrice", 10));
        receive(p, loc, 10, 10, null);
        JsonNode session = okA(201, "POST", "/inventory/counts/start", Map.of("locationId", loc, "restart", true));
        String lineId = null;
        for (JsonNode l : session.get("lines")) {
            if (p.equals(l.get("productId").asString())) {
                lineId = l.get("id").asString();
            }
        }
        assertThat(lineId).isNotNull();
        okA(200, "PATCH", "/inventory/counts/" + session.get("id").asString() + "/draft", Map.of("lines",
                List.of(Map.of("lineId", lineId, "countedQty", 7, "reason", "breakage"))));
        // Each submit carries its own Idempotency-Key (deviation 16), so only the status claim keeps it single.
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> api("POST", "/inventory/counts/" + session.get("id").asString() + "/submit",
                    Map.of("force", true, "forceReason", "test")).status());
        }
        List<Integer> statuses = parallel(tasks);
        assertThat(statuses.stream().filter(s -> s == 200).count()).as(statuses.toString()).isEqualTo(1);
        assertThat(onHand(p, loc)).isEqualByComparingTo("7");
        assertThat(count("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                + " AND type IN ('adjustment_in', 'adjustment_out')", org, p)).isEqualTo(1);
    }
}
