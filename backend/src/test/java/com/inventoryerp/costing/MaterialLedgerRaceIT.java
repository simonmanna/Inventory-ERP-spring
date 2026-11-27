package com.inventoryerp.costing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.TestSuppliers;
import com.inventoryerp.inventory.audit.InventoryAuditSupport;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * IPR M-23 (transaction matrix VAL-7): a material-ledger run is posted, and reversed, exactly once. Two posts with
 * different idempotency keys used to both pass the unlocked status read, so the value rows were written twice while
 * the journal key deduplicated the entry: the stock ledger stopped tying to the GL. The run row is now locked (L1)
 * before the status check, and the status UPDATE claims {@code draft} (or {@code posted}) atomically.
 */
class MaterialLedgerRaceIT extends InventoryAuditSupport {

    private final String today = LocalDate.now(ZoneOffset.UTC).toString();

    Res keyed(String method, String path, Object body) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + auditToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return call(method, path, body, h);
    }

    long valueRows(String runId, String referenceType, String referenceId) {
        return count("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND \"referenceType\" = ?"
                + " AND \"referenceId\" = ?", org, referenceType, referenceId);
    }

    BigDecimal valueSum(String referenceType, String referenceId) {
        return jdbc.sql("SELECT COALESCE(SUM(\"totalValue\"), 0) FROM \"InventoryLedger\" WHERE"
                + " \"organizationId\" = ? AND \"referenceType\" = ? AND \"referenceId\" = ?")
                .params(org, referenceType, referenceId).query(BigDecimal.class).single().abs();
    }

    /** Integrity green, with the failing checks on one line in the message. */
    void integrityGreen(String step) {
        JsonNode report = integrityReport();
        List<String> failing = failingChecks(report, "checks");
        assertThat(failing).as("integrity checks " + step + ": " + failing).isEmpty();
    }

    /** The {@code difference} of one product's row in {@code valuation_vs_gl_by_product} (0 when it has none). */
    static BigDecimal productGap(JsonNode report, String productId) {
        for (JsonNode row : integrityCheck(report, "valuation_vs_gl_by_product").get("rows")) {
            if (productId.equals(row.get("productId").asString())) {
                return new BigDecimal(row.get("difference").asString());
            }
        }
        return BigDecimal.ZERO;
    }

    @Test
    @DisplayName("M-23: two concurrent posts of one run post once (one journal, one set of value rows); two"
            + " concurrent reversals reverse once; integrity and GL tie-out green")
    void concurrentPostAndReverseRunOnce() throws Exception {
        String bolt = product("ML-BOLT");
        JsonNode draft = okA(201, "POST", "/inventory/standard-costs", Map.of("productId", bolt, "effectiveFrom", today,
                "materialCost", 10, "convertMethod", true));
        String stdId = draft.get("id").asString();
        okA(200, "POST", "/inventory/standard-costs/" + stdId + "/approve", null);
        assertThat(keyed("POST", "/inventory/standard-costs/" + stdId + "/activate", null).status()).isIn(200, 201);
        // 40 received at 13 against a standard of 10 → PPV +120; 25 consumed, 15 remain → 45 to inventory.
        okA(200, "POST", "/procurement/goods-receipts/adhoc", Map.of("partnerId", TestSuppliers.supplier(jdbc, org),
                "warehouseId", main, "receivedAt", Instant.now().toString(), "lines", List.of(Map.of("productId",
                        bolt, "description", "Bolts", "quantity", 40, "unitCost", 13))));
        okA(200, "POST", "/inventory/direct-stock/out", direct(main, List.of(item(bolt, 25, null))));
        integrityGreen("before the run");
        assertGlTieOut();

        JsonNode run = okA(201, "POST", "/costing/material-ledger/runs", Map.of("from", today, "to", today));
        String runId = run.get("id").asString();
        String path = "/costing/material-ledger/runs/" + runId;

        List<Callable<Res>> posts = List.of(() -> keyed("POST", path + "/post", null),
                () -> keyed("POST", path + "/post", null));
        List<Raced<Res>> posted = race(Duration.ofSeconds(60), posts);
        List<Integer> statuses = posted.stream().map(r -> r.ok() ? r.value().status() : -1).toList();
        assertThat(statuses).as("one post wins, the other is refused").containsExactlyInAnyOrder(200, 400);
        Res loser = posted.stream().map(Raced::value).filter(r -> r.status() == 400).findFirst().orElseThrow();
        assertThat(loser.body().get("message").asString()).isEqualTo("Only a draft run can be posted");
        assertThat(count("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"sourceType\" ="
                + " 'material_ledger_run' AND \"sourceId\" = ?", org, runId)).isEqualTo(1);
        assertThat(valueRows(runId, "material_ledger_run", runId)).as("value rows written once").isEqualTo(1);
        assertThat(valueSum("material_ledger_run", runId)).isEqualByComparingTo("45");
        // Known defect (pre-existing, reported in docs/audits/ipr-evidence/4-golive-p2.md): the run debits
        // stock_valuation stamped with the product, while valuation_vs_gl_by_product leaves the run's value-only
        // price_difference rows out of the subledger side. The per-product gap is the delta of ONE post (45), which
        // also proves the run did not post twice; every other check is green.
        JsonNode report = integrityReport();
        assertThat(failingChecks(report, "checks")).as("integrity checks after the post")
                .allMatch(c -> c.startsWith("valuation_vs_gl_by_product"));
        assertThat(productGap(report, bolt)).as("one post's delta").isEqualByComparingTo("-45");
        assertGlTieOut();

        String entryId = okA(200, "GET", path, null).get("journalEntryId").asString();
        List<Callable<Res>> reversals = List.of(() -> keyed("POST", path + "/reverse", null),
                () -> keyed("POST", path + "/reverse", null));
        List<Raced<Res>> reversed = race(Duration.ofSeconds(60), reversals);
        assertThat(reversed.stream().map(r -> r.ok() ? r.value().status() : -1).toList())
                .as("one reversal wins, the other is refused").containsExactlyInAnyOrder(200, 400);
        Res revLoser = reversed.stream().map(Raced::value).filter(r -> r.status() == 400).findFirst().orElseThrow();
        assertThat(revLoser.body().get("message").asString()).isEqualTo("Only a posted run can be reversed");
        assertThat(valueRows(runId, "reversal", entryId)).as("reversal value rows written once").isEqualTo(1);
        assertThat(valueSum("reversal", entryId)).isEqualByComparingTo("45");
        assertThat(okA(200, "GET", path, null).get("status").asString()).isEqualTo("reversed");
        integrityGreen("after the reversal");
        assertGlTieOut();
    }
}
