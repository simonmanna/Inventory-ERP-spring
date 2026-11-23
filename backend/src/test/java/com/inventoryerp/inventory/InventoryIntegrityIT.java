package com.inventoryerp.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.inventory.application.InventoryIntegrityMonitor;
import com.inventoryerp.inventory.application.InventoryIntegrityService;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService.GlReceiptContext;
import com.inventoryerp.inventory.audit.InventoryAuditSupport;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Hardening R0: {@code GET /inventory/reports/integrity} and the daily integrity monitor. Each test builds one isolated
 * organisation with a clean stock scenario through the API and the engine (receipts, an issue, a lot, serials, a
 * transit transfer in transit, a reservation) — every check green — and then breaks exactly one invariant by raw SQL,
 * expecting exactly that check to fail.
 */
class InventoryIntegrityIT extends InventoryAuditSupport {

    @Autowired
    InventoryIntegrityMonitor monitor;

    /** One clean scenario: its organisation, products and the open transit transfer. */
    record Scenario(AuditOrg org, String plain, String lot, String serial, String transitProduct, String transferId,
            String transferItemId, String transitLoc) {}

    Map<String, Object> directBody(AuditOrg o, String loc, List<Map<String, Object>> items) {
        return Map.of("locationId", loc, "responsibleById", o.userId(), "approvedById", o.userId(), "items", items);
    }

    JsonNode okAs(AuditOrg o, int status, String method, String path, Object body) {
        Res r = call(method, path, body, o.token());
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(status);
        return r.body();
    }

    Scenario scenario() {
        AuditOrg o = newOrg();
        String plain = product(o, "PLAIN", Map.of());
        String lot = product(o, "LOT", Map.of("batchTracking", true));
        String serial = product(o, "SER", Map.of("serialTracking", true));
        String moving = product(o, "TRN", Map.of());

        okAs(o, 200, "POST", "/inventory/direct-stock/in", directBody(o, o.main(), List.of(item(plain, 10, 5),
                item(moving, 8, 3))));
        Map<String, Object> lotLine = item(lot, 5, 2);
        lotLine.put("batchNumber", "L1");
        okAs(o, 200, "POST", "/inventory/direct-stock/in", directBody(o, o.main(), List.of(lotLine)));
        TenantContext.run(TenantStore.user(o.id(), o.userId()), () -> new TransactionTemplate(txm).execute(st ->
                stock.receiveForDocument(StockMove.of(serial, o.main(), new BigDecimal("3")).unitCost(new BigDecimal("5"))
                        .serials(List.of("SN-1", "SN-2", "SN-3")), new GlReceiptContext("audit_seed",
                                "seed-" + UUID.randomUUID(), Instant.now(), null))));
        okAs(o, 200, "POST", "/inventory/direct-stock/out", directBody(o, o.main(), List.of(item(plain, 3, null),
                item(serial, 1, null), item(lot, 1, null))));

        JsonNode trf = okAs(o, 200, "POST", "/inventory/transfers", Map.of("fromLocationId", o.main(), "toLocationId",
                o.alt(), "mode", "transit", "responsibleById", o.userId(), "approvedById", o.userId(), "items",
                List.of(Map.of("productId", moving, "qtyRequested", 5))));
        String transferId = trf.get("id").asString();
        okAs(o, 200, "POST", "/inventory/transfers/" + transferId + "/approve", null);
        JsonNode dispatched = okAs(o, 200, "POST", "/inventory/transfers/" + transferId + "/dispatch", null);
        String itemId = dispatched.get("items").get(0).get("id").asString();
        JsonNode partial = okAs(o, 200, "POST", "/inventory/transfers/" + transferId + "/receive", Map.of("lines",
                List.of(Map.of("itemId", itemId, "received", 2))));
        assertThat(partial.get("status").asString()).isEqualTo("partially_received");
        okAs(o, 201, "POST", "/inventory/reservations", Map.of("productId", plain, "locationId", o.main(), "quantity",
                2, "sourceType", "order", "sourceId", "SO-" + UUID.randomUUID()));
        String transitLoc = jdbc.sql("SELECT \"transitLocId\" FROM \"StockTransfer\" WHERE id = ?").param(transferId)
                .query(String.class).single();
        return new Scenario(o, plain, lot, serial, moving, transferId, itemId, transitLoc);
    }

    JsonNode report(Scenario s, String query) {
        return okAs(s.org(), 200, "GET", "/inventory/reports/integrity" + query, null);
    }

    static List<String> failing(JsonNode report) {
        List<String> keys = new ArrayList<>();
        report.get("checks").forEach(c -> {
            if (!c.get("ok").asBoolean()) {
                keys.add(c.get("key").asString());
            }
        });
        return keys;
    }

    static JsonNode check(JsonNode report, String key) {
        for (JsonNode c : report.get("checks")) {
            if (key.equals(c.get("key").asString())) {
                return c;
            }
        }
        throw new AssertionError("no check " + key);
    }

    /** The scenario's report with exactly {@code key} failing; returns that check. */
    JsonNode onlyFailing(Scenario s, String key) {
        JsonNode r = report(s, "");
        assertThat(failing(r)).as(r.toString()).containsExactly(key);
        assertThat(r.get("ok").asBoolean()).isFalse();
        JsonNode c = check(r, key);
        assertThat(c.get("failures").asLong()).isPositive();
        assertThat(c.get("rows").size()).isPositive();
        return c;
    }

    Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }

    String ledgerRow(Scenario s, String productId, String loc, int change, BigDecimal before) {
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("organizationId", s.org().id());
        cols.put("ledgerCode", "INJ-" + UUID.randomUUID().toString().substring(0, 8));
        cols.put("productId", productId);
        cols.put("locationId", loc);
        cols.put("type", "adjustment_in");
        cols.put("qtyBefore", before);
        cols.put("quantityChange", BigDecimal.valueOf(change));
        cols.put("balanceAfter", before.add(BigDecimal.valueOf(change)));
        cols.put("unitCost", BigDecimal.ZERO);
        cols.put("totalValue", BigDecimal.ZERO);
        cols.put("referenceType", "integrity_probe");
        cols.put("createdAt", now());
        return insert("InventoryLedger", cols);
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("clean scenario: every check is green; a location filter skips the organisation-level parts")
    void cleanScenario() {
        Scenario s = scenario();
        JsonNode r = report(s, "");
        assertThat(failing(r)).as(r.toString()).isEmpty();
        assertThat(r.get("ok").asBoolean()).isTrue();
        assertThat(r.get("rowLimit").asInt()).isEqualTo(5000);
        List<String> keys = new ArrayList<>();
        r.get("checks").forEach(c -> keys.add(c.get("key").asString()));
        assertThat(keys).containsExactlyElementsOf(InventoryIntegrityService.CHECKS);
        assertThat(check(r, "ledger_vs_quant").get("tolerance").asString()).isEqualTo("0.000001");
        assertThat(check(r, "valuation_vs_subledger").get("tolerance").asString()).isEqualTo("0.01");
        assertThat(check(r, "valuation_vs_gl").get("skipped").asBoolean()).isFalse();

        JsonNode scoped = report(s, "?locationId=" + s.org().alt());
        assertThat(failing(scoped)).as(scoped.toString()).isEmpty();
        assertThat(scoped.get("locationId").asString()).isEqualTo(s.org().alt());
        assertThat(check(scoped, "valuation_vs_gl").get("skipped").asBoolean()).isTrue();

        // Nothing was written: the report is read-only.
        assertThat(count("SELECT count(*) FROM \"InventoryException\" WHERE \"organizationId\" = ?", s.org().id()))
                .isZero();
    }

    @Test
    @DisplayName("ledger vs quant: a ledger row the quant never saw")
    void ledgerVsQuant() {
        Scenario s = scenario();
        ledgerRow(s, s.plain(), s.org().main(), 1, new BigDecimal("7"));
        JsonNode c = onlyFailing(s, "ledger_vs_quant");
        assertThat(c.get("rows").get(0).get("productId").asString()).isEqualTo(s.plain());
        assertThat(c.get("rows").get(0).get("difference").asString()).isEqualTo("-1");
    }

    @Test
    @DisplayName("quant vs lots: lot quantity that is not on hand")
    void quantVsLots() {
        Scenario s = scenario();
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("organizationId", s.org().id());
        cols.put("productId", s.lot());
        cols.put("locationId", s.org().main());
        cols.put("batchNumber", "GHOST-" + UUID.randomUUID().toString().substring(0, 6));
        cols.put("quantity", BigDecimal.ONE);
        cols.put("unitCost", BigDecimal.ZERO);
        cols.put("isActive", false);
        cols.put("receivedAt", now());
        cols.put("updatedAt", now());
        insert("InventoryBatch", cols);
        JsonNode c = onlyFailing(s, "quant_vs_lots");
        assertThat(c.get("rows").get(0).get("productId").asString()).isEqualTo(s.lot());
    }

    @Test
    @DisplayName("quant vs serials: more serials in stock than units on hand")
    void quantVsSerials() {
        Scenario s = scenario();
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("organizationId", s.org().id());
        cols.put("productId", s.serial());
        cols.put("locationId", s.org().main());
        cols.put("serialNumber", "SN-GHOST");
        // At the quant's average, so the valuation (serial costs + remainder at the average) does not move.
        cols.put("unitCost", new BigDecimal("5"));
        cols.put("status", "in_stock");
        cols.put("receivedAt", now());
        cols.put("updatedAt", now());
        insert("InventorySerial", cols);
        JsonNode c = onlyFailing(s, "quant_vs_serials");
        assertThat(c.get("rows").get(0).get("serialsInStock").asString()).isEqualTo("3");
        assertThat(c.get("rows").get(0).get("quantity").asString()).isEqualTo("2");
    }

    @Test
    @DisplayName("serial state: an issued serial marked in stock and an in-stock one marked issued")
    void serialState() {
        Scenario s = scenario();
        String issued = jdbc.sql("SELECT id FROM \"InventorySerial\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                + " AND status = 'issued'").params(s.org().id(), s.serial()).query(String.class).single();
        String inStock = jdbc.sql("SELECT id FROM \"InventorySerial\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                + " AND status = 'in_stock' ORDER BY \"serialNumber\" LIMIT 1").params(s.org().id(), s.serial())
                .query(String.class).single();
        jdbc.sql("UPDATE \"InventorySerial\" SET status = 'in_stock' WHERE id = ?").param(issued).update();
        jdbc.sql("UPDATE \"InventorySerial\" SET status = 'issued' WHERE id = ?").param(inStock).update();
        JsonNode c = onlyFailing(s, "serial_state");
        assertThat(c.get("failures").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("valuation vs sub-ledger: per-product averages moved without a ledger row (net zero for the GL)")
    void valuationVsSubledger() {
        Scenario s = scenario();
        String other = product(s.org(), "PAIR", Map.of());
        okAs(s.org(), 200, "POST", "/inventory/direct-stock/in", directBody(s.org(), s.org().main(),
                List.of(item(other, 7, 5))));
        assertThat(failing(report(s, ""))).isEmpty();
        // +7 on one product, -7 on the other: the organisation total (and so the GL tie-out) is unchanged.
        jdbc.sql("UPDATE \"StockItem\" SET \"runningAverageCost\" = 6 WHERE \"productId\" = ? AND \"locationId\" = ?")
                .params(s.plain(), s.org().main()).update();
        jdbc.sql("UPDATE \"StockItem\" SET \"runningAverageCost\" = 4 WHERE \"productId\" = ? AND \"locationId\" = ?")
                .params(other, s.org().main()).update();
        JsonNode c = onlyFailing(s, "valuation_vs_subledger");
        assertThat(c.get("failures").asLong()).isEqualTo(2);
        List<String> diffs = new ArrayList<>();
        c.get("rows").forEach(r -> diffs.add(r.get("difference").asString()));
        assertThat(diffs).containsExactlyInAnyOrder("7", "-7");
    }

    @Test
    @DisplayName("valuation vs GL: an account with a balance counted as an inventory account")
    void valuationVsGl() {
        Scenario s = scenario();
        // A credit-balance account (the receipts' contra account) mapped as a category inventory account: the GL side
        // of the tie-out moves, the valuation and the stock ledger do not.
        String contra = jdbc.sql("SELECT \"accountId\" FROM \"JournalLine\" WHERE \"organizationId\" = ? GROUP BY 1"
                + " HAVING sum(debit - credit) < -1 ORDER BY 1 LIMIT 1").param(s.org().id()).query(String.class).single();
        jdbc.sql("UPDATE \"ProductCategory\" SET \"inventoryAccountId\" = ? WHERE id = ?")
                .params(contra, s.org().categoryId()).update();
        JsonNode c = onlyFailing(s, "valuation_vs_gl");
        assertThat(c.get("rows").get(0).get("withinTolerance").asBoolean()).isFalse();
        assertThat(c.get("note").asString()).startsWith("variance ");
    }

    @Test
    @DisplayName("transit: a receipt recorded on the line without moving the stock")
    void transit() {
        Scenario s = scenario();
        jdbc.sql("UPDATE \"StockTransferItem\" SET \"qtyReceived\" = \"qtyReceived\" + 1 WHERE id = ?")
                .param(s.transferItemId()).update();
        JsonNode c = onlyFailing(s, "transit");
        List<String> kinds = new ArrayList<>();
        c.get("rows").forEach(r -> kinds.add(r.get("kind").asString()));
        assertThat(kinds).containsExactlyInAnyOrder("transfer", "transit_location");
        assertThat(c.get("rows").get(0).get("openQuantity").asString()).isEqualTo("2");
        assertThat(c.get("rows").get(0).get("transitQuantity").asString()).isEqualTo("3");
    }

    @Test
    @DisplayName("reservations: a negative reservation, one on a missing invoice and one beyond stock and supply")
    void reservations() {
        Scenario s = scenario();
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("organizationId", s.org().id());
        cols.put("productId", s.plain());
        cols.put("variantKey", "");
        cols.put("locationId", s.org().main());
        cols.put("quantity", new BigDecimal("-1"));
        cols.put("status", "active");
        cols.put("sourceType", "manual");
        cols.put("sourceId", "NEG-" + UUID.randomUUID());
        cols.put("updatedAt", now());
        insert("StockReservation", cols);
        cols.put("quantity", BigDecimal.ONE);
        cols.put("sourceType", "invoice");
        cols.put("sourceId", UUID.randomUUID().toString());
        insert("StockReservation", cols);
        cols.put("productId", s.lot());
        cols.put("quantity", new BigDecimal("50"));
        cols.put("sourceType", "manual");
        cols.put("sourceId", "BIG-" + UUID.randomUUID());
        insert("StockReservation", cols);
        JsonNode c = onlyFailing(s, "reservations");
        List<String> kinds = new ArrayList<>();
        c.get("rows").forEach(r -> kinds.add(r.get("kind").asString()));
        assertThat(kinds).containsExactlyInAnyOrder("negative", "over_reserved", "closed_source");
    }

    @Test
    @DisplayName("offline queue: a receipt still waiting a day after its transfer was dispatched")
    void offlineQueue() {
        Scenario s = scenario();
        insertAwaiting(s, Instant.now().minus(25, ChronoUnit.HOURS));
        JsonNode c = onlyFailing(s, "offline_queue");
        assertThat(c.get("rows").get(0).get("transferId").asString()).isEqualTo(s.transferId());
    }

    void insertAwaiting(Scenario s, Instant createdAt) {
        jdbc.sql("INSERT INTO \"InventoryException\" (id, \"organizationId\", \"locationId\", description, quantity, kind,"
                        + " reason, payload, status, \"createdAt\") VALUES (?, ?, ?, 'probe', 0, 'offline_awaiting_dispatch',"
                        + " 'probe', ?::jsonb, 'open', ?)")
                .params(UUID.randomUUID().toString(), s.org().id(), s.org().alt(),
                        "{\"transferId\": \"" + s.transferId() + "\"}", ts(createdAt))
                .update();
    }

    @Test
    @DisplayName("monitor: one exception per failing kind, none for a fresh waiting receipt, no duplicate on a rerun")
    void monitorRaisesOncePerKind() {
        Scenario s = scenario();
        ledgerRow(s, s.plain(), s.org().main(), 1, new BigDecimal("7"));
        insertAwaiting(s, Instant.now().minus(1, ChronoUnit.HOURS));
        jdbc.sql("UPDATE \"StockTransferItem\" SET \"qtyReceived\" = \"qtyReceived\" + 1 WHERE id = ?")
                .param(s.transferItemId()).update();
        TenantStore store = TenantStore.organization(s.org().id());

        InventoryIntegrityMonitor.Result first = TenantContext.run(store, monitor::check);
        assertThat(first.raised()).containsExactly("integrity_ledger_vs_quant", "integrity_transit");
        assertThat(openKinds(s)).containsExactlyInAnyOrder("integrity_ledger_vs_quant", "integrity_transit",
                "offline_awaiting_dispatch");
        JsonNode payload = mapper.readTree(jdbc.sql("SELECT payload::text FROM \"InventoryException\" WHERE"
                + " \"organizationId\" = ? AND kind = 'integrity_transit'").param(s.org().id()).query(String.class)
                .single());
        assertThat(payload.get("check").asString()).isEqualTo("transit");
        assertThat(payload.get("failures").asLong()).isEqualTo(2);

        InventoryIntegrityMonitor.Result second = TenantContext.run(store, monitor::check);
        assertThat(second.raised()).isEmpty();
        assertThat(openKinds(s)).hasSize(3);
        assertThat(count("SELECT count(*) FROM \"Notification\" WHERE \"organizationId\" = ? AND title ="
                + " 'Inventory integrity check failed'", s.org().id())).isEqualTo(1);

        // Resolving a finding re-arms its kind; the monitor still corrected nothing.
        jdbc.sql("UPDATE \"InventoryException\" SET status = 'resolved' WHERE \"organizationId\" = ? AND kind ="
                + " 'integrity_transit'").param(s.org().id()).update();
        assertThat(TenantContext.run(store, monitor::check).raised()).containsExactly("integrity_transit");
        assertThat(ledgerSumOf(s, s.plain())).isEqualByComparingTo("8");
    }

    List<String> openKinds(Scenario s) {
        return jdbc.sql("SELECT kind FROM \"InventoryException\" WHERE \"organizationId\" = ? AND status = 'open'")
                .param(s.org().id()).query(String.class).list();
    }

    BigDecimal ledgerSumOf(Scenario s, String productId) {
        return jdbc.sql("SELECT COALESCE(SUM(\"quantityChange\"), 0) FROM \"InventoryLedger\" WHERE \"organizationId\" = ?"
                + " AND \"productId\" = ? AND \"locationId\" = ?").params(s.org().id(), productId, s.org().main())
                .query(BigDecimal.class).single();
    }

    @Test
    @DisplayName("authorization: 403 without inventory_valuation:read, 401 without a token")
    void authorization() {
        assertThat(call("GET", "/inventory/reports/integrity", null, weakToken()).status()).isEqualTo(403);
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                        + " VALUES (?, ?, ?, false, ?, now())")
                .params(role, demoOrg, "Reader-" + role, new String[] {"inventory:read"}).update();
        String email = "reader-" + UUID.randomUUID() + "@demo.test";
        createUser(demoOrg, email, "Reader@12345", role);
        assertThat(call("GET", "/inventory/reports/integrity", null, login("DEMO", email, "Reader@12345")).status())
                .isEqualTo(403);
        assertThat(call("GET", "/inventory/reports/integrity", null, (String) null).status()).isEqualTo(401);
    }
}
