package com.inventoryerp.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.inventory.audit.InventoryAuditSupport;
import com.inventoryerp.inventory.web.OpeningStockImportController;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * IPR R5 / ADR-S090 (finding S-5): opening-stock import — preview with a token, approval, atomic batches of at most
 * 2,000 rows per location, org-scoped idempotency, the import hash, stale-preview refusal, empty-history locations only,
 * batch retry and the run reconciliation (Σ ledger value = journal amount = valuation delta). Every scenario ends with
 * the integrity report and the GL tie-out green.
 */
class OpeningStockImportIT extends InventoryAuditSupport {

    static final String BASE = "/inventory/opening-stock";

    static String sfx() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    /** A new empty stock location; returns {id, code}. */
    String[] newLocation() {
        String code = "OSL-" + sfx();
        return new String[] {location(code, "Opening " + code, "warehouse"), code};
    }

    String code(String productId) {
        return asOrg(() -> jdbc.sql("SELECT code FROM \"Product\" WHERE id = ?").param(productId).query(String.class)
                .single());
    }

    static Map<String, Object> row(int no, String product, String location, Object qty, Object cost, String lot,
            String expiry, List<String> serials) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rowNo", no);
        m.put("productCode", product);
        m.put("locationCode", location);
        m.put("quantity", qty == null ? null : String.valueOf(qty));
        m.put("unitCost", cost == null ? null : String.valueOf(cost));
        m.put("batchNumber", lot);
        m.put("expiryDate", expiry);
        m.put("serialNumbers", serials);
        return m;
    }

    JsonNode preview(List<Map<String, Object>> rows) {
        return okA(200, "POST", BASE + "/preview", Map.of("rows", rows));
    }

    Res importFile(List<Map<String, Object>> rows, String token, String key) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rows", rows);
        body.put("previewToken", token);
        body.put("approve", true);
        body.put("fileName", "opening.csv");
        return call("POST", BASE + "/import", body, Map.of("Authorization", "Bearer " + auditToken, "Idempotency-Key",
                key));
    }

    Res retry(String runId, String batchId, String batchToken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("approve", true);
        body.put("batchToken", batchToken);
        return call("POST", BASE + "/runs/" + runId + "/batches/" + batchId + "/retry", body, Map.of("Authorization",
                "Bearer " + auditToken, "Idempotency-Key", UUID.randomUUID().toString()));
    }

    long tenantCount(String sql, Object... params) {
        return asOrg(() -> jdbc.sql(sql).params(List.of(params)).query(Long.class).single());
    }

    void assertReconciled(String runId, String expectedValue) {
        JsonNode rec = okA(200, "GET", BASE + "/runs/" + runId + "/reconciliation", null);
        assertThat(rec.get("reconciled").asBoolean()).as(rec.toString()).isTrue();
        assertThat(rec.get("complete").asBoolean()).isTrue();
        assertThat(num(rec.get("ledgerValue"))).isEqualByComparingTo(expectedValue);
        assertThat(num(rec.get("journalAmount"))).isEqualByComparingTo(expectedValue);
        assertThat(num(rec.get("equityCredit"))).isEqualByComparingTo(expectedValue);
        assertThat(num(rec.get("valuationDelta"))).isEqualByComparingTo(expectedValue);
    }

    @Test
    @DisplayName("happy path: plain, lot+expiry, serial and STANDARD rows post one atomic batch, journal to opening equity")
    void happyPathWithLotAndSerialProducts() {
        String[] loc = newLocation();
        String plain = product("OS-PLAIN");
        String lot = product("OS-LOT", Map.of("batchTracking", true, "expiryTracking", true));
        String serial = product("OS-SER", Map.of("serialTracking", true));
        String std = product("OS-STD", Map.of("costingMethod", "STANDARD", "costPrice", 7));
        List<Map<String, Object>> rows = List.of(
                row(1, code(plain), loc[1], 10, "2.5", null, null, null),
                row(2, code(lot), loc[1], 4, 3, "LOT-A", "2027-12-31", null),
                row(3, code(serial), loc[1], 2, 100, null, null, List.of("SN-" + sfx(), "SN-" + sfx())),
                row(4, code(std), loc[1], 3, null, null, null, null));
        JsonNode p = preview(rows);
        assertThat(p.get("valid").asBoolean()).as(p.toString()).isTrue();
        assertThat(p.get("previewToken").isNull()).isFalse();
        // 25 + 12 + 200 + 21 (STANDARD at its standard, cost source "standard")
        assertThat(num(p.get("totalValue"))).isEqualByComparingTo("258");
        assertThat(p.get("rows").get(3).get("costSource").asString()).isEqualTo("standard");
        assertThat(p.get("batches")).hasSize(1);
        JsonNode journal = p.get("batches").get(0).get("journal");
        assertThat(num(journal.get("totalDebit"))).isEqualByComparingTo("258");
        assertThat(num(journal.get("totalCredit"))).isEqualByComparingTo("258");
        long journalsBefore = journalEntries();

        Res r = importFile(rows, p.get("previewToken").asString(), UUID.randomUUID().toString());
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        assertThat(r.body().get("status").asString()).isEqualTo("completed");
        String runId = r.body().get("id").asString();
        JsonNode batch = r.body().get("batches").get(0);
        String batchId = batch.get("id").asString();
        assertThat(batch.get("status").asString()).isEqualTo("posted");

        assertThat(tenantCount("SELECT count(*) FROM \"InventoryLedger\" WHERE \"referenceType\" = 'opening_stock_import'"
                + " AND \"referenceId\" = ? AND type = 'opening_balance'", batchId)).isEqualTo(4);
        assertThat(tenantCount("SELECT count(*) FROM \"InventorySerial\" WHERE \"productId\" = ? AND status = 'in_stock'"
                + " AND \"locationId\" = ?", serial, loc[0])).isEqualTo(2);
        assertThat(tenantCount("SELECT count(*) FROM \"InventoryBatch\" WHERE \"productId\" = ? AND \"batchNumber\" ="
                + " 'LOT-A' AND \"expiryDate\" IS NOT NULL", lot)).isEqualTo(1);
        assertThat(journalEntries() - journalsBefore).as("one journal per batch").isEqualTo(1);
        String equity = account("retained_earnings");
        BigDecimal credit = asOrg(() -> jdbc.sql("SELECT COALESCE(SUM(l.credit), 0) FROM \"JournalLine\" l JOIN"
                + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE e.\"sourceType\" = 'opening_stock_import' AND"
                + " e.\"sourceId\" = ? AND l.\"accountId\" = ?").params(batchId, equity).query(BigDecimal.class).single());
        assertThat(credit).as("opening stock is opening equity (policy P1)").isEqualByComparingTo("258");
        assertReconciled(runId, "258");

        JsonNode detail = okA(200, "GET", BASE + "/runs/" + runId + "/batches/" + batchId, null);
        assertThat(detail.get("rows")).hasSize(4);
        assertThat(detail.get("ledger")).hasSize(4);
        assertThat(detail.has("revalidation")).isFalse();
        assertThat(okA(200, "GET", BASE + "/runs", null).toString()).contains(runId);
        assertIntegrityGreen();
        assertGlTieOut();
    }

    @Test
    @DisplayName("idempotency: a replay with the same key answers the first result; one run, one posting")
    void replaySameKeyReturnsFirstResult() {
        String[] loc = newLocation();
        String p = product("OS-REPLAY");
        List<Map<String, Object>> rows = List.of(row(1, code(p), loc[1], 5, 2, null, null, null));
        String token = preview(rows).get("previewToken").asString();
        String key = UUID.randomUUID().toString();
        Res first = importFile(rows, token, key);
        Res second = importFile(rows, token, key);
        assertThat(first.status()).as(first.raw().body()).isEqualTo(200);
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.body().get("runCode").asString()).isEqualTo(first.body().get("runCode").asString());
        assertThat(onHand(p, loc[0])).isEqualByComparingTo("5");
        assertThat(tenantCount("SELECT count(*) FROM \"InventoryLedger\" WHERE \"productId\" = ?", p)).isEqualTo(1);
        assertIntegrityGreen();
        assertGlTieOut();
    }

    @Test
    @DisplayName("the same file twice (new key) is refused by the import hash, naming the first run")
    void sameFileTwiceRefusedByImportHash() {
        String[] loc = newLocation();
        String p = product("OS-TWICE");
        List<Map<String, Object>> rows = List.of(row(1, code(p), loc[1], 3, 4, null, null, null));
        String token = preview(rows).get("previewToken").asString();
        Res first = importFile(rows, token, UUID.randomUUID().toString());
        assertThat(first.status()).as(first.raw().body()).isEqualTo(200);
        Res again = importFile(rows, token, UUID.randomUUID().toString());
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.body().get("message").asString()).isEqualTo("This file was already imported (run "
                + first.body().get("runCode").asString() + ")");
        assertThat(onHand(p, loc[0])).isEqualByComparingTo("3");
        assertIntegrityGreen();
        assertGlTieOut();
    }

    @Test
    @DisplayName("stale preview: the product's costing changed after the preview → 409, nothing written")
    void stalePreviewIsRefused() {
        String[] loc = newLocation();
        String p = product("OS-STALE");
        List<Map<String, Object>> rows = List.of(row(1, code(p), loc[1], 3, 4, null, null, null));
        String token = preview(rows).get("previewToken").asString();
        asOrgRun(() -> jdbc.sql("UPDATE \"Product\" SET \"costingMethod\" = 'STANDARD', \"costPrice\" = 4,"
                + " \"updatedAt\" = now() + interval '1 second' WHERE id = ?").param(p).update());
        Res r = importFile(rows, token, UUID.randomUUID().toString());
        assertThat(r.status()).as(r.raw().body()).isEqualTo(409);
        assertThat(r.body().get("message").asString()).isEqualTo("Preview is stale — preview and approve again");
        assertThat(tenantCount("SELECT count(*) FROM \"InventoryLedger\" WHERE \"productId\" = ?", p)).isZero();
        assertThat(tenantCount("SELECT count(*) FROM \"OpeningStockImportBatch\" WHERE \"locationId\" = ?", loc[0]))
                .isZero();
        // Preview again: a new token, which imports.
        String fresh = preview(rows).get("previewToken").asString();
        assertThat(fresh).isNotEqualTo(token);
        assertThat(importFile(rows, fresh, UUID.randomUUID().toString()).status()).isEqualTo(200);
        assertIntegrityGreen();
        assertGlTieOut();
    }

    @Test
    @DisplayName("a location with stock history is refused (row error; the import is 400)")
    void locationWithHistoryIsRefused() {
        String[] loc = newLocation();
        String p = product("OS-HIST");
        receive(p, loc[0], 1, 5, null);
        List<Map<String, Object>> rows = List.of(row(1, code(p), loc[1], 3, 4, null, null, null));
        JsonNode prev = preview(rows);
        assertThat(prev.get("valid").asBoolean()).isFalse();
        assertThat(prev.get("previewToken").isNull()).isTrue();
        assertThat(prev.get("errors").get(0).get("message").asString()).contains("already has stock movements");
        Res r = importFile(rows, "x", UUID.randomUUID().toString());
        assertThat(r.status()).isEqualTo(400);
        assertThat(onHand(p, loc[0])).isEqualByComparingTo("1");

        // Row validation: unknown product, missing lot, bad numbers, serial on a plain product, duplicates.
        String[] empty = newLocation();
        String lot = product("OS-VLOT", Map.of("batchTracking", true));
        JsonNode bad = preview(List.of(row(1, "NOPE-" + sfx(), empty[1], 1, 1, null, null, null),
                row(2, code(lot), empty[1], 1, 1, null, null, null),
                row(3, code(p), empty[1], "abc", "-1", null, null, List.of("S1")),
                row(4, code(p), empty[1], 1, 1, null, null, null),
                row(5, code(p), empty[1], 2, 1, null, null, null)));
        assertThat(bad.get("valid").asBoolean()).isFalse();
        String all = bad.get("errors").toString();
        assertThat(all).contains("not found", "is lot-tracked: enter a lot number", "Quantity must be a number",
                "Unit cost must be greater than 0", "is not serial-tracked", "Duplicate of row 4");
        assertIntegrityGreen();
        assertGlTieOut();
    }

    @Test
    @DisplayName("a failed batch: the others post, the run is partial; a stale retry is re-approved at the new token")
    void failedBatchIsRetried() throws Exception {
        String[] a = newLocation();
        String[] b = newLocation();
        String pa = product("OS-FA");
        String pb = product("OS-FB");
        List<Map<String, Object>> rows = List.of(row(1, code(pa), a[1], 2, 10, null, null, null),
                row(2, code(pb), b[1], 3, 10, null, null, null));
        JsonNode prev = preview(rows);
        assertThat(prev.get("batches")).hasSize(2);
        Res r;
        try (AutoCloseable fault = injectFault("InventoryLedger", "INSERT", "productId", pb, null, null)) {
            r = importFile(rows, prev.get("previewToken").asString(), UUID.randomUUID().toString());
        }
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        assertThat(r.body().get("status").asString()).isEqualTo("partial");
        String runId = r.body().get("id").asString();
        JsonNode failed = r.body().get("batches").get(1);
        assertThat(failed.get("status").asString()).isEqualTo("failed");
        assertThat(failed.get("error").asString()).contains("injected crash");
        String batchId = failed.get("id").asString();
        assertThat(onHand(pa, a[0])).isEqualByComparingTo("2");
        assertThat(tenantCount("SELECT count(*) FROM \"InventoryLedger\" WHERE \"productId\" = ?", pb))
                .as("the failed batch left nothing").isZero();
        JsonNode rec = okA(200, "GET", BASE + "/runs/" + runId + "/reconciliation", null);
        assertThat(rec.get("complete").asBoolean()).isFalse();
        assertThat(rec.get("reconciled").asBoolean()).isFalse();

        // An input changes before the retry: the retry is stale until the batch is approved at its new token.
        asOrgRun(() -> jdbc.sql("UPDATE \"Product\" SET \"updatedAt\" = now() + interval '1 second' WHERE id = ?")
                .param(pb).update());
        Res stale = retry(runId, batchId, null);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.body().get("message").asString()).isEqualTo("Preview is stale — preview and approve again");
        JsonNode detail = okA(200, "GET", BASE + "/runs/" + runId + "/batches/" + batchId, null);
        JsonNode reval = detail.get("revalidation");
        assertThat(reval.get("stale").asBoolean()).isTrue();
        assertThat(reval.get("valid").asBoolean()).isTrue();
        assertThat(num(reval.get("journal").get("totalDebit"))).isEqualByComparingTo("30");

        Res ok = retry(runId, batchId, reval.get("batchToken").asString());
        assertThat(ok.status()).as(ok.raw().body()).isEqualTo(200);
        assertThat(ok.body().get("status").asString()).isEqualTo("completed");
        Res twice = retry(runId, batchId, null);
        assertThat(twice.status()).isEqualTo(409);
        assertThat(onHand(pb, b[0])).isEqualByComparingTo("3");
        assertReconciled(runId, "50");
        assertIntegrityGreen();
        assertGlTieOut();
    }

    @Test
    @DisplayName("2,500 rows of one location split into 2 atomic batches (2,000 + 500), reconciled")
    void largeImportSplitsIntoBatches() {
        String[] loc = newLocation();
        String p = product("OS-BIG", Map.of("batchTracking", true));
        String c = code(p);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 2500; i++) {
            rows.add(row(i, c, loc[1], 1, 2, "L" + i, null, null));
        }
        JsonNode prev = preview(rows);
        assertThat(prev.get("valid").asBoolean()).isTrue();
        assertThat(prev.get("batches")).hasSize(2);
        assertThat(prev.get("batches").get(0).get("rowCount").asInt()).isEqualTo(2000);
        assertThat(prev.get("batches").get(1).get("rowCount").asInt()).isEqualTo(500);
        Res r = importFile(rows, prev.get("previewToken").asString(), UUID.randomUUID().toString());
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        assertThat(r.body().get("status").asString()).isEqualTo("completed");
        assertThat(r.body().get("batchCount").asInt()).isEqualTo(2);
        assertThat(onHand(p, loc[0])).isEqualByComparingTo("2500");
        assertThat(lotSum(p, loc[0])).isEqualByComparingTo("2500");
        assertReconciled(r.body().get("id").asString(), "5000");
        assertIntegrityGreen();
        assertGlTieOut();
    }

    @Test
    @DisplayName("concurrency: parallel submits of one file post once; parallel retries of one batch post once")
    void parallelSubmitOfTheSameRun() throws Exception {
        String[] loc = newLocation();
        String p = product("OS-RACE");
        List<Map<String, Object>> rows = List.of(row(1, code(p), loc[1], 4, 5, null, null, null));
        String token = preview(rows).get("previewToken").asString();
        List<Callable<Res>> racers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            racers.add(() -> importFile(rows, token, UUID.randomUUID().toString()));
        }
        List<Raced<Res>> out = race(Duration.ofSeconds(60), racers);
        assertThat(out).allMatch(Raced::ok);
        assertThat(out.stream().filter(o -> o.value().status() == 200).count()).isEqualTo(1);
        assertThat(out.stream().filter(o -> o.value().status() == 409).count()).isEqualTo(3);
        assertThat(onHand(p, loc[0])).isEqualByComparingTo("4");

        // A failed batch retried by four requests at once posts exactly once.
        String[] loc2 = newLocation();
        String q = product("OS-RACE2");
        List<Map<String, Object>> rows2 = List.of(row(1, code(q), loc2[1], 6, 5, null, null, null));
        String token2 = preview(rows2).get("previewToken").asString();
        Res partial;
        try (AutoCloseable fault = injectFault("InventoryLedger", "INSERT", "productId", q, null, null)) {
            partial = importFile(rows2, token2, UUID.randomUUID().toString());
        }
        String runId = partial.body().get("id").asString();
        String batchId = partial.body().get("batches").get(0).get("id").asString();
        assertThat(partial.body().get("status").asString()).isEqualTo("failed");
        List<Callable<Res>> retries = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            retries.add(() -> retry(runId, batchId, null));
        }
        List<Raced<Res>> rs = race(Duration.ofSeconds(60), retries);
        assertThat(rs).allMatch(Raced::ok);
        assertThat(rs.stream().filter(o -> o.value().status() == 200).count()).isEqualTo(1);
        assertThat(rs.stream().filter(o -> o.value().status() == 409).count()).isEqualTo(3);
        assertThat(onHand(q, loc2[0])).isEqualByComparingTo("6");
        assertReconciled(runId, "30");
        assertIntegrityGreen();
        assertGlTieOut();
    }

    @Test
    @DisplayName("authorization: 7 routes; import needs inventory_valuation:post and an Idempotency-Key; tenant isolation")
    void authorization() {
        assertThat(assertAuthorizationMatrix(OpeningStockImportController.class)).isEqualTo(7);
        String manageOnly = tokenHolding("inventory_valuation:manage");
        Map<String, Object> body = Map.of("rows", List.of(row(1, "X", "Y", 1, 1, null, null, null)), "previewToken", "t",
                "approve", true);
        Res denied = call("POST", BASE + "/import", body, Map.of("Authorization", "Bearer " + manageOnly,
                "Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(denied.status()).isEqualTo(403);
        Res noKey = call("POST", BASE + "/import", body, Map.of("Authorization", "Bearer " + auditToken,
                NO_IDEMPOTENCY_KEY, "1"));
        assertThat(noKey.status()).isEqualTo(400);
        Res notApproved = call("POST", BASE + "/import", Map.of("rows", body.get("rows"), "previewToken", "t",
                "approve", false), Map.of("Authorization", "Bearer " + auditToken, "Idempotency-Key",
                UUID.randomUUID().toString()));
        assertThat(notApproved.status()).isEqualTo(400);
        assertThat(notApproved.body().get("message").asString()).contains("approve: true");

        String[] loc = newLocation();
        String p = product("OS-TEN");
        List<Map<String, Object>> rows = List.of(row(1, code(p), loc[1], 1, 1, null, null, null));
        Res r = importFile(rows, preview(rows).get("previewToken").asString(), UUID.randomUUID().toString());
        String runId = r.body().get("id").asString();
        assertThat(call("GET", BASE + "/runs/" + runId, null, adminToken).status()).as("DEMO cannot read it")
                .isEqualTo(404);
        assertThat(call("GET", BASE + "/runs", null, adminToken).body().toString()).doesNotContain(runId);
    }
}
