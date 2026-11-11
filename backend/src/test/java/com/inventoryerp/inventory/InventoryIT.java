package com.inventoryerp.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.settings.SettingResolverService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Inventory on Testcontainers Postgres: locations, the stock engine (AVCO, batches/FEFO, serials, transfers,
 * adjustments), direct stock with approver proofs, stock documents with reversals, transit transfers, counts,
 * reservations, posting rules, reports, period lock, idempotency, authorization matrix and tenant isolation.
 */
class InventoryIT extends ApiTestSupport {

    String adminId;
    String category;
    String uom;

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        category = jdbc.sql("SELECT id FROM \"ProductCategory\" WHERE \"organizationId\" = ? ORDER BY \"createdAt\" LIMIT 1")
                .param(demoOrg).query(String.class).optional().orElse(null);
        uom = jdbc.sql("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND code = 'UNIT'")
                .param(demoOrg).query(String.class).optional().orElse(null);
    }

    String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String location(String type) {
        return ok(201, "POST", "/inventory/locations", Map.of("code", "L-" + suffix(), "name", "Loc " + suffix(),
                "type", type)).get("id").asString();
    }

    String product(String costing, boolean batch, boolean serial) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"costingMethod\","
                        + " \"costPrice\", \"categoryId\", \"uomId\", \"batchTracking\", \"serialTracking\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, 'stockable', ?::\"CostingMethod\", 4, ?, ?, ?, ?, now())")
                .params(id, demoOrg, "P-" + suffix(), "Item " + suffix(), costing, category, uom, batch, serial).update();
        return id;
    }

    Map<String, Object> line(String productId, Object qty, Object unitCost) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", productId);
        m.put("quantity", qty);
        if (unitCost != null) {
            m.put("unitCost", unitCost);
        }
        return m;
    }

    JsonNode stockIn(String loc, List<Map<String, Object>> items) {
        return ok(200, "POST", "/inventory/direct-stock/in", Map.of("locationId", loc, "responsibleById", adminId,
                "approvedById", adminId, "items", items));
    }

    BigDecimal onHand(String productId, String loc) {
        return jdbc.sql("SELECT quantity FROM \"StockItem\" WHERE \"productId\" = ? AND \"locationId\" = ?")
                .params(productId, loc).query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("locations: CRUD, user-managed type rule, soft delete")
    void locations() {
        Res transit = as("POST", "/inventory/locations", Map.of("code", "X" + suffix(), "name", "T", "type", "transit"));
        assertThat(transit.status()).isEqualTo(400);
        assertThat(transit.body().get("message").get(0).asString())
                .isEqualTo("type must be a user-managed location type (transit is system-managed)");
        assertThat(validationMessages(as("POST", "/inventory/locations", Map.of("name", "X"))))
                .containsExactly("code should not be empty", "code must be a string");
        // Unknown property + missing fields: one 400 with everything, in class-validator order (Nest parity).
        assertThat(validationMessages(as("POST", "/inventory/stock-outs", Map.of("locationId", "L", "lines", List.of()))))
                .containsExactly("property lines should not exist", "responsibleById should not be empty",
                        "responsibleById must be a string", "approvedById should not be empty",
                        "approvedById must be a string", "items must contain at least 1 elements",
                        "items must be an array");
        String id = location("bar");
        assertThat(ok(200, "GET", "/inventory/locations/" + id, null).get("type").asString()).isEqualTo("bar");
        assertThat(ok(200, "PATCH", "/inventory/locations/" + id, Map.of("name", "Back bar")).get("name").asString())
                .isEqualTo("Back bar");
        JsonNode page = ok(200, "GET", "/inventory/locations?type=bar&pageSize=200", null);
        assertThat(page.get("meta").get("pageSize").asInt()).isEqualTo(200);
        Res bad = as("GET", "/inventory/locations?nope=1", null);
        assertThat(bad.body().get("message").get(0).asString()).isEqualTo("property nope should not exist");
        assertThat(as("DELETE", "/inventory/locations/" + id, null).status()).isEqualTo(204);
        Res gone = as("GET", "/inventory/locations/" + id, null);
        assertThat(gone.status()).isEqualTo(404);
        assertThat(gone.body().get("message").asString()).isEqualTo("InventoryLocation " + id + " not found");
    }

    @Test
    @DisplayName("AVCO: receipts recompute the running average, issues use it, ledger chains, GL posts")
    void avco() {
        String loc = location("warehouse");
        String p = product("AVCO", false, false);
        JsonNode in1 = stockIn(loc, List.of(line(p, 10, 5)));
        assertThat(in1.get("code").asString()).startsWith("DSI-");
        assertThat(in1.get("type").asString()).isEqualTo("IN");
        assertThat(in1.get("totalValue").asInt()).isEqualTo(50);
        stockIn(loc, List.of(line(p, 10, 7)));
        BigDecimal avg = jdbc.sql("SELECT \"runningAverageCost\" FROM \"StockItem\" WHERE \"productId\" = ?").param(p)
                .query(BigDecimal.class).single();
        assertThat(avg).isEqualByComparingTo("6");

        JsonNode out = ok(200, "POST", "/inventory/direct-stock/out", Map.of("locationId", loc, "responsibleById",
                adminId, "approvedById", adminId, "items", List.of(line(p, 4, null))));
        assertThat(out.get("items").get(0).get("unitCost").asString()).isEqualTo("6");
        assertThat(onHand(p, loc)).isEqualByComparingTo("16");

        List<Map<String, Object>> chain = jdbc.sql("SELECT \"qtyBefore\", \"quantityChange\", \"balanceAfter\" FROM"
                + " \"InventoryLedger\" WHERE \"productId\" = ? ORDER BY \"createdAt\"").param(p).query().listOfRows();
        assertThat(chain).hasSize(3);
        assertThat((BigDecimal) chain.get(2).get("qtyBefore")).isEqualByComparingTo("20");
        assertThat((BigDecimal) chain.get(2).get("balanceAfter")).isEqualByComparingTo("16");

        long journals = jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"sourceId\" = ?")
                .params(demoOrg, in1.get("code").asString()).query(Long.class).single();
        assertThat(journals).isEqualTo(1);
        long issueJe = jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"sourceId\" = ?")
                .params(demoOrg, out.get("code").asString()).query(Long.class).single();
        assertThat(issueJe).isEqualTo(1);

        JsonNode item = ok(200, "GET", "/inventory/items/" + p, null);
        assertThat(item.get("totalOnHand").asInt()).isEqualTo(16);
        assertThat(item.get("recentLedger")).hasSize(3);
        String ledgerId = item.get("recentLedger").get(0).get("id").asString();
        JsonNode entry = ok(200, "GET", "/inventory/ledger/" + ledgerId, null);
        assertThat(entry.get("source").get("kind").asString()).isEqualTo("none");
        assertThat(entry.get("source").get("label").asString()).startsWith("Direct Stock Out DSO-");
        JsonNode ledger = ok(200, "GET", "/inventory/ledger?productId=" + p, null);
        assertThat(ledger.get("meta").get("total").asInt()).isEqualTo(3);
        assertThat(ledger.get("data").get(0).get("product").get("id").asString()).isEqualTo(p);
    }

    @Test
    @DisplayName("direct stock: strict mode refuses shortfall; named approver needs PIN; inactive staff rejected")
    void directStockGuards() {
        String loc = location("store");
        String p = product("AVCO", false, false);
        jdbc.sql("UPDATE \"Product\" SET \"stockPolicy\" = 'block' WHERE id = ?").param(p).update();
        String setting = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Setting\" (id, \"organizationId\", key, value, \"scopeType\", \"updatedAt\") VALUES"
                        + " (?, ?, 'inventory.allowNegativeStock', 'false'::jsonb, 'organization', now())")
                .params(setting, demoOrg).update();
        try {
            evictSettings();
            Res shortfall = as("POST", "/inventory/direct-stock/out", Map.of("locationId", loc, "responsibleById",
                    adminId, "approvedById", adminId, "items", List.of(line(p, 1, null))));
            assertThat(shortfall.status()).as(shortfall.raw().body()).isEqualTo(400);
            assertThat(shortfall.body().get("message").asString()).startsWith("Insufficient stock for ");
        } finally {
            jdbc.sql("DELETE FROM \"Setting\" WHERE id = ?").param(setting).update();
            evictSettings();
        }
        String other = createUser(demoOrg, "approver-" + suffix() + "@demo.test", "Approver@123",
                jdbc.sql("SELECT id FROM \"Role\" WHERE \"organizationId\" = ? AND name = 'Administrator'")
                        .param(demoOrg).query(String.class).single());
        Res noPin = as("POST", "/inventory/direct-stock/in", Map.of("locationId", loc, "responsibleById", adminId,
                "approvedById", other, "items", List.of(line(p, 1, 1))));
        assertThat(noPin.body().get("message").asString())
                .isEqualTo("The approver's PIN is required to post a direct stock movement");
        Res withPassword = as("POST", "/inventory/direct-stock/in", Map.of("locationId", loc, "responsibleById",
                adminId, "approvedById", other, "approverPin", "password:Approver@123", "items", List.of(line(p, 1, 1))));
        assertThat(withPassword.status()).as(withPassword.raw().body()).isEqualTo(200);
        Res wrong = as("POST", "/inventory/direct-stock/in", Map.of("locationId", loc, "responsibleById", adminId,
                "approvedById", other, "approverPin", "password:nope", "items", List.of(line(p, 1, 1))));
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(wrong.body().get("message").asString()).isEqualTo("Invalid approver credentials");
        Res foreign = as("POST", "/inventory/direct-stock/in", Map.of("locationId", loc, "responsibleById",
                UUID.randomUUID().toString(), "approvedById", adminId, "items", List.of(line(p, 1, 1))));
        assertThat(foreign.body().get("message").asString())
                .isEqualTo("responsibleById must be an active user of this organization");
        Res missing = as("POST", "/inventory/direct-stock/in", Map.of("locationId", loc, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(line(UUID.randomUUID().toString(), 1, 1))));
        assertThat(missing.status()).isEqualTo(404);
    }

    @Autowired
    SettingResolverService settingResolver;

    void evictSettings() {
        settingResolver.invalidate(demoOrg);
    }

    @Test
    @DisplayName("batches: FEFO issue consumes the earliest expiry first; transfer moves lots")
    void batches() {
        String loc = location("warehouse");
        String dest = location("bar");
        String p = product("AVCO", true, false);
        Map<String, Object> late = line(p, 5, 2);
        late.put("batchNumber", "B-LATE");
        late.put("expiryDate", "2099-12-31");
        Map<String, Object> early = line(p, 5, 3);
        early.put("batchNumber", "B-EARLY");
        early.put("expiryDate", "2098-01-31");
        stockIn(loc, List.of(late, early));
        Res noBatch = as("POST", "/inventory/direct-stock/in", Map.of("locationId", loc, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(line(p, 1, 1))));
        assertThat(noBatch.body().get("message").asString()).startsWith("Batch number required for batch-tracked product");
        JsonNode out = ok(200, "POST", "/inventory/direct-stock/out", Map.of("locationId", loc, "responsibleById",
                adminId, "approvedById", adminId, "items", List.of(line(p, 6, null))));
        // 5 @ 3 (early) + 1 @ 2 (late) = 17 / 6
        assertThat(new BigDecimal(out.get("items").get(0).get("unitCost").asString()).setScale(4, java.math.RoundingMode.HALF_UP))
                .isEqualByComparingTo("2.8333");
        BigDecimal earlyLeft = jdbc.sql("SELECT quantity FROM \"InventoryBatch\" WHERE \"productId\" = ? AND \"batchNumber\" = 'B-EARLY'")
                .param(p).query(BigDecimal.class).single();
        assertThat(earlyLeft).isEqualByComparingTo("0");
        JsonNode trf = ok(200, "POST", "/inventory/transfers", Map.of("fromLocationId", loc, "toLocationId", dest,
                "responsibleById", adminId, "approvedById", adminId, "items", List.of(Map.of("productId", p,
                        "qtyRequested", 2))));
        assertThat(trf.get("status").asString()).isEqualTo("pending");
        JsonNode done = ok(200, "POST", "/inventory/transfers/" + trf.get("id").asString() + "/approve", null);
        assertThat(done.get("status").asString()).isEqualTo("completed");
        assertThat(onHand(p, dest)).isEqualByComparingTo("2");
        long destLots = jdbc.sql("SELECT count(*) FROM \"InventoryBatch\" WHERE \"productId\" = ? AND \"locationId\" = ?")
                .params(p, dest).query(Long.class).single();
        assertThat(destLots).isEqualTo(1);
        JsonNode recon = ok(200, "GET", "/inventory/reports/reconciliation?locationId=" + loc, null);
        assertThat(recon.get("summary").get("clean").asBoolean()).as(recon.toString()).isTrue();
        JsonNode reversed = ok(200, "POST", "/inventory/transfers/" + trf.get("id").asString() + "/reverse",
                Map.of("reason", "wrong bar"));
        assertThat(reversed.get("status").asString()).isEqualTo("reversed");
        assertThat(onHand(p, dest)).isEqualByComparingTo("0");
        Res twice = as("POST", "/inventory/transfers/" + trf.get("id").asString() + "/reverse", Map.of("reason", "x"));
        assertThat(twice.status()).isEqualTo(409);
        assertThat(twice.body().get("message").asString()).isEqualTo("Transfer " + trf.get("transferCode").asString()
                + " is already reversed");
    }

    @Test
    @DisplayName("serials: receipt captures units, issue picks oldest, duplicates refused")
    void serials() {
        String loc = location("warehouse");
        String p = product("SPECIFIC", false, true);
        Map<String, Object> in = line(p, 2, 10);
        in.put("serialNumbers", List.of("SN-1", "SN-2"));
        Res extra = as("POST", "/inventory/direct-stock/in", Map.of("locationId", loc, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(in)));
        assertThat(extra.status()).isEqualTo(400);
        assertThat(extra.body().get("message").get(0).asString()).isEqualTo("items.0.property serialNumbers should not exist");
        stockIn(loc, List.of(line(p, 2, 10)));
        JsonNode out = ok(200, "POST", "/inventory/direct-stock/out", Map.of("locationId", loc, "responsibleById",
                adminId, "approvedById", adminId, "items", List.of(line(p, 1, null))));
        assertThat(out.get("items").get(0).get("unitCost").asString()).isEqualTo("4");
        Res half = as("POST", "/inventory/direct-stock/out", Map.of("locationId", loc, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(line(p, 0.5, null))));
        assertThat(half.body().get("message").asString()).endsWith("is serial-tracked: quantity must be a whole number"
                + " of base units, got 0.5");
    }

    @Test
    @DisplayName("documents: stock-out, waste (+summary/detail), adjustment drift guard, reversals")
    void documents() {
        String loc = location("main_kitchen");
        String p = product("AVCO", false, false);
        stockIn(loc, List.of(line(p, 20, 2)));

        JsonNode so = ok(200, "POST", "/inventory/stock-outs", Map.of("locationId", loc, "responsibleById", adminId,
                "approvedById", adminId, "category", "sample", "items", List.of(Map.of("productId", p, "qty", 3))));
        assertThat(so.get("outCode").asString()).startsWith("SO-");
        assertThat(so.get("items")).hasSize(1);
        JsonNode posted = ok(200, "POST", "/inventory/stock-outs/" + so.get("id").asString() + "/approve", null);
        assertThat(posted.get("status").asString()).isEqualTo("completed");
        assertThat(posted.get("totalValue").asString()).isEqualTo("6");
        String moveType = jdbc.sql("SELECT type::text FROM \"InventoryLedger\" WHERE \"referenceId\" = ?")
                .param(so.get("outCode").asString()).query(String.class).single();
        assertThat(moveType).isEqualTo("promo_sample");
        Res again = as("POST", "/inventory/stock-outs/" + so.get("id").asString() + "/approve", null);
        assertThat(again.body().get("message").asString()).isEqualTo("Document already posted");

        JsonNode waste = ok(200, "POST", "/inventory/waste", Map.of("locationId", loc, "responsibleById", adminId,
                "approvedById", adminId, "category", "spoiled", "items", List.of(Map.of("productId", p, "qty", 2))));
        assertThat(waste.get("totalValue").asString()).isEqualTo("4");
        assertThat(waste.get("location").get("id").asString()).isEqualTo(loc);
        ok(200, "POST", "/inventory/waste/" + waste.get("id").asString() + "/approve", null);
        JsonNode detail = ok(200, "GET", "/inventory/waste/" + waste.get("id").asString(), null);
        assertThat(detail.get("ledger")).hasSize(1);
        assertThat(detail.get("journalEntries")).hasSize(1);
        JsonNode summary = ok(200, "GET", "/inventory/waste/summary?locationId=" + loc, null);
        assertThat(summary.get("postedRecords").asInt()).isEqualTo(1);
        assertThat(summary.get("byCategory").get(0).get("category").asString()).isEqualTo("spoiled");
        JsonNode rev = ok(200, "POST", "/inventory/waste/" + waste.get("id").asString() + "/reverse",
                Map.of("reason", "found it"));
        assertThat(rev.get("status").asString()).isEqualTo("reversed");
        assertThat(onHand(p, loc)).isEqualByComparingTo("17");
        long mirrored = jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"sourceType\" = 'waste_reversal'"
                + " AND \"sourceId\" = ?").param(waste.get("wasteCode").asString()).query(Long.class).single();
        assertThat(mirrored).isEqualTo(1);

        JsonNode adj = ok(200, "POST", "/inventory/adjustments", Map.of("locationId", loc, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(Map.of("productId", p, "qtyActual", 15))));
        assertThat(adj.get("items").get(0).get("qtyDiff").asString()).isEqualTo("-2");
        stockIn(loc, List.of(line(p, 1, 2)));
        Res drift = as("POST", "/inventory/adjustments/" + adj.get("id").asString() + "/approve", Map.of());
        assertThat(drift.status()).isEqualTo(409);
        assertThat(drift.body().get("code").asString()).isEqualTo("ADJUSTMENT_STOCK_DRIFT");
        Res noReason = as("POST", "/inventory/adjustments/" + adj.get("id").asString() + "/approve",
                Map.of("force", true));
        assertThat(noReason.body().get("message").asString())
                .isEqualTo("A reason is required to force-approve an adjustment over stock that moved.");
        JsonNode forced = ok(200, "POST", "/inventory/adjustments/" + adj.get("id").asString() + "/approve",
                Map.of("force", true, "forceReason", "recount"));
        assertThat(forced.get("status").asString()).isEqualTo("completed");
        assertThat(onHand(p, loc)).isEqualByComparingTo("15");
        JsonNode list = ok(200, "GET", "/inventory/adjustments?status=completed", null);
        assertThat(list.get(0).get("location")).isNotNull();
        Res dup = as("POST", "/inventory/adjustments", Map.of("locationId", loc, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(Map.of("productId", p, "qtyActual", 1),
                        Map.of("productId", p, "qtyActual", 2))));
        assertThat(dup.body().get("message").asString()).isEqualTo("Each product can appear only once in an adjustment");
    }

    @Test
    @DisplayName("transit transfers: approve → dispatch → partial receive with damage/short → recall")
    void transit() {
        String from = location("warehouse");
        String to = location("branch");
        String p = product("AVCO", false, false);
        stockIn(from, List.of(line(p, 10, 3)));
        JsonNode trf = ok(200, "POST", "/inventory/transfers", Map.of("fromLocationId", from, "toLocationId", to,
                "mode", "transit", "responsibleById", adminId, "approvedById", adminId,
                "items", List.of(Map.of("productId", p, "qtyRequested", 8))));
        String id = trf.get("id").asString();
        Res early = as("POST", "/inventory/transfers/" + id + "/dispatch", null);
        assertThat(early.body().get("message").asString()).isEqualTo("Only an approved, undispatched transfer can be"
                + " dispatched (" + trf.get("transferCode").asString() + " is pending)");
        assertThat(ok(200, "POST", "/inventory/transfers/" + id + "/approve", null).get("status").asString())
                .isEqualTo("approved");
        JsonNode dispatched = ok(200, "POST", "/inventory/transfers/" + id + "/dispatch", null);
        assertThat(dispatched.get("status").asString()).isEqualTo("in_transit");
        assertThat(onHand(p, from)).isEqualByComparingTo("2");
        String itemId = dispatched.get("items").get(0).get("id").asString();
        JsonNode partial = ok(200, "POST", "/inventory/transfers/" + id + "/receive", Map.of("lines",
                List.of(Map.of("itemId", itemId, "received", 4, "damaged", 1, "short", 1))));
        assertThat(partial.get("status").asString()).isEqualTo("partially_received");
        assertThat(partial.get("receipts")).hasSize(1);
        assertThat(onHand(p, to)).isEqualByComparingTo("4");
        Res over = as("POST", "/inventory/transfers/" + id + "/receive", Map.of("lines",
                List.of(Map.of("itemId", itemId, "received", 5))));
        assertThat(over.body().get("message").asString()).contains("exceeds the 2 still in transit");
        JsonNode recalled = ok(200, "POST", "/inventory/transfers/" + id + "/recall", Map.of("reason", "truck broke"));
        assertThat(recalled.get("status").asString()).isEqualTo("completed");
        assertThat(onHand(p, from)).isEqualByComparingTo("4");
        Res cancel = as("POST", "/inventory/transfers/" + id + "/cancel", null);
        assertThat(cancel.body().get("message").asString()).endsWith("has already moved stock; recall or reverse it instead");
    }

    @Test
    @DisplayName("counts: preview, start, blind masking, draft, reason required, submit posts the variance")
    void counts() {
        String loc = location("storage_room");
        String p = product("AVCO", false, false);
        stockIn(loc, List.of(line(p, 5, 2)));
        JsonNode preview = ok(200, "GET", "/inventory/counts/preview?locationId=" + loc + "&countType=spot&scopeProductIds="
                + p, null);
        assertThat(preview.get("status").asString()).isEqualTo("preview");
        assertThat(preview.get("lines").get(0).get("systemQty").asString()).isEqualTo("5");
        Res cycle = as("POST", "/inventory/counts/start", Map.of("locationId", loc, "countType", "cycle"));
        assertThat(cycle.body().get("message").asString())
                .isEqualTo("A cycle count needs a scope: pick categories or products.");
        JsonNode session = ok(201, "POST", "/inventory/counts/start", Map.of("locationId", loc, "countType", "spot",
                "blind", true, "scopeProductIds", List.of(p)));
        assertThat(session.get("systemHidden").asBoolean()).isTrue();
        assertThat(session.get("lines").get(0).get("systemQty").isNull()).isTrue();
        String id = session.get("id").asString();
        assertThat(ok(200, "GET", "/inventory/counts/" + id + "/review", null).get("lines").get(0).get("systemQty")
                .asString()).isEqualTo("5");
        String lineId = session.get("lines").get(0).get("id").asString();
        ok(200, "PATCH", "/inventory/counts/" + id + "/draft", Map.of("lines", List.of(Map.of("lineId", lineId,
                "countedQty", 3))));
        Res noReason = as("POST", "/inventory/counts/" + id + "/submit", Map.of());
        assertThat(noReason.body().get("message").asString()).isEqualTo("A reason is required for 1 line(s) with a variance.");
        ok(200, "PATCH", "/inventory/counts/" + id + "/draft", Map.of("lines", List.of(Map.of("lineId", lineId,
                "countedQty", 3, "reason", "breakage"))));
        JsonNode submitted = ok(200, "POST", "/inventory/counts/" + id + "/submit", Map.of());
        assertThat(submitted.get("status").asString()).isEqualTo("submitted");
        assertThat(submitted.get("adjustmentId").isNull()).isFalse();
        assertThat(onHand(p, loc)).isEqualByComparingTo("3");
        assertThat(ok(200, "GET", "/inventory/counts", null).get(0).get("_count").get("lines").asInt()).isEqualTo(1);
        Res modify = as("POST", "/inventory/counts/" + id + "/cancel", null);
        assertThat(modify.body().get("message").asString()).isEqualTo("Cannot modify a submitted count");
    }

    JsonNode startSpot(String loc, List<String> products) {
        return ok(201, "POST", "/inventory/counts/start", Map.of("locationId", loc, "countType", "spot",
                "scopeProductIds", products));
    }

    String lineOf(JsonNode session, String productId, String batchNumber) {
        for (JsonNode l : session.get("lines")) {
            boolean batchMatch = batchNumber == null ? l.get("batchNumber").isNull()
                    : !l.get("batchNumber").isNull() && batchNumber.equals(l.get("batchNumber").asString());
            if (productId.equals(l.get("productId").asString()) && batchMatch) {
                return l.get("id").asString();
            }
        }
        throw new AssertionError("line not found for " + productId + " / " + batchNumber);
    }

    static Map<String, Object> counted(String lineId, Object qty, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lineId", lineId);
        m.put("countedQty", qty);
        if (reason != null) {
            m.put("reason", reason);
        }
        return m;
    }

    @Test
    @DisplayName("counts post against the count time: movements before the count are not a variance, later ones stay")
    void countsAgainstCountTime() {
        String loc = location("warehouse");
        String a = product("AVCO", false, false);
        String b = product("AVCO", false, false);
        stockIn(loc, List.of(line(a, 10, 2), line(b, 10, 2)));
        JsonNode session = startSpot(loc, List.of(a, b));
        String id = session.get("id").asString();
        // a sale between start and the count of A: the snapshot (10) is stale, the shelf really holds 9
        ok(200, "POST", "/inventory/direct-stock/out", Map.of("locationId", loc, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(line(a, 1, null))));
        ok(200, "PATCH", "/inventory/counts/" + id + "/draft", Map.of("lines", List.of(counted(lineOf(session, a, null),
                9, null), counted(lineOf(session, b, null), 8, "breakage"))));
        // sales after the count: they must survive the posting
        ok(200, "POST", "/inventory/direct-stock/out", Map.of("locationId", loc, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(line(a, 3, null), line(b, 3, null))));
        JsonNode review = ok(200, "GET", "/inventory/counts/" + id + "/review", null);
        for (JsonNode l : review.get("lines")) {
            boolean isA = a.equals(l.get("productId").asString());
            assertThat(new BigDecimal(l.get("variance").asString())).isEqualByComparingTo(isA ? "-1" : "-2");
            assertThat(new BigDecimal(l.get("postingDelta").asString())).isEqualByComparingTo(isA ? "0" : "-2");
            assertThat(new BigDecimal(l.get("movementsSinceCount").asString())).isEqualByComparingTo("-3");
        }
        Res forcedNoReason = as("POST", "/inventory/counts/" + id + "/submit", Map.of("force", true));
        assertThat(forcedNoReason.body().get("message").asString())
                .isEqualTo("A reason is required to force-submit a count over lines that moved after being counted.");
        JsonNode submitted = ok(200, "POST", "/inventory/counts/" + id + "/submit", Map.of());
        assertThat(submitted.get("status").asString()).isEqualTo("submitted");
        assertThat(onHand(a, loc)).isEqualByComparingTo("6");
        assertThat(onHand(b, loc)).isEqualByComparingTo("5");
        String adj = submitted.get("adjustmentId").asString();
        assertThat(jdbc.sql("SELECT count(*) FROM \"StockAdjustmentItem\" WHERE \"adjId\" = ?").param(adj)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("count approval: a value rule holds large variances, validation runs first, maker-checker applies")
    void countApprovalAndSegregation() {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "Count checker " + suffix(), new String[] {
                    "inventory_count:read", "inventory_count:submit", "approvals:read", "approvals:decide"}).update();
        String email = "checker-" + suffix() + "@demo.test";
        createUser(demoOrg, email, "Check@12345", role);
        String checker = login("DEMO", email, "Check@12345");

        String loc = location("warehouse");
        String p = product("AVCO", false, false);
        stockIn(loc, List.of(line(p, 10, 10)));
        String wf = ok(201, "POST", "/approval-workflows", Map.of("name", "Count variance " + suffix(), "entityType",
                "inventory_count_submit", "minAmount", 50, "steps", List.of(Map.of("stepOrder", 1, "name",
                        "Controller", "approverPermissions", List.of("approvals:decide"), "requiredCount", 1))))
                .get("id").asString();
        try {
            // 1 unit × 10 = 10 < 50: no rule matches, posts at once
            JsonNode small = startSpot(loc, List.of(p));
            ok(200, "PATCH", "/inventory/counts/" + small.get("id").asString() + "/draft", Map.of("lines",
                    List.of(counted(lineOf(small, p, null), 9, "spillage"))));
            ok(200, "POST", "/inventory/counts/" + small.get("id").asString() + "/submit", Map.of());
            assertThat(onHand(p, loc)).isEqualByComparingTo("9");

            // a rejected submit (no reason) leaves no approval request behind
            JsonNode big = startSpot(loc, List.of(p));
            String bigId = big.get("id").asString();
            ok(200, "PATCH", "/inventory/counts/" + bigId + "/draft", Map.of("lines",
                    List.of(counted(lineOf(big, p, null), 2, null))));
            assertThat(as("POST", "/inventory/counts/" + bigId + "/submit", Map.of()).status()).isEqualTo(400);
            assertThat(jdbc.sql("SELECT count(*) FROM \"ApprovalRequest\" WHERE \"entityId\" = ?").param(bigId)
                    .query(Long.class).single()).isZero();

            // 7 × 10 = 70 ≥ 50: held for approval with the variance value on the request
            ok(200, "PATCH", "/inventory/counts/" + bigId + "/draft", Map.of("lines",
                    List.of(counted(lineOf(big, p, null), 2, "theft"))));
            Res held = as("POST", "/inventory/counts/" + bigId + "/submit", Map.of());
            assertThat(held.status()).isEqualTo(403);
            String message = held.body().get("message").asString();
            assertThat(message).startsWith("Inventory count submission requires approval. Request ID: ");
            String requestId = message.substring(message.indexOf("Request ID: ") + 12, message.length() - 1);
            assertThat(jdbc.sql("SELECT (snapshot->>'amount')::numeric FROM \"ApprovalRequest\" WHERE id = ?")
                    .param(requestId).query(BigDecimal.class).single()).isEqualByComparingTo("70");
            assertThat(onHand(p, loc)).isEqualByComparingTo("9");
            assertThat(call("POST", "/approvals/" + requestId + "/decide", Map.of("status", "approved"), checker)
                    .status()).isLessThan(300);
            ok(200, "POST", "/inventory/counts/" + bigId + "/submit", Map.of());
            assertThat(onHand(p, loc)).isEqualByComparingTo("2");
        } finally {
            ok(200, "DELETE", "/approval-workflows/" + wf, null);
        }

        // maker-checker: with the stock adjustment pair on, whoever counted cannot submit; another user can
        String rule = "/approval-controls/sod-rules/stock_adjustment";
        ok(200, "PUT", rule, Map.of("isActive", true));
        try {
            JsonNode sod = startSpot(loc, List.of(p));
            String sodId = sod.get("id").asString();
            ok(200, "PATCH", "/inventory/counts/" + sodId + "/draft", Map.of("lines",
                    List.of(counted(lineOf(sod, p, null), 3, "found one"))));
            Res own = as("POST", "/inventory/counts/" + sodId + "/submit", Map.of());
            assertThat(own.status()).isEqualTo(403);
            assertThat(own.body().get("message").asString()).startsWith("Separation of duties");
            assertThat(onHand(p, loc)).isEqualByComparingTo("2");
            Res other = call("POST", "/inventory/counts/" + sodId + "/submit", Map.of(), checker);
            assertThat(other.status()).as(other.raw().body()).isEqualTo(200);
            assertThat(onHand(p, loc)).isEqualByComparingTo("3");
        } finally {
            ok(200, "PUT", rule, Map.of("isActive", false));
        }
    }

    @Test
    @DisplayName("batch-tracked products are counted per lot; serial losses must name the missing units")
    void lotsAndSerials() {
        String loc = location("warehouse");
        String p = product("AVCO", true, false);
        Map<String, Object> la = line(p, 5, 2);
        la.put("batchNumber", "LOT-A");
        la.put("expiryDate", "2098-01-31");
        Map<String, Object> lb = line(p, 3, 2);
        lb.put("batchNumber", "LOT-B");
        lb.put("expiryDate", "2099-12-31");
        stockIn(loc, List.of(la, lb));
        JsonNode preview = ok(200, "GET", "/inventory/counts/preview?locationId=" + loc + "&countType=spot"
                + "&scopeProductIds=" + p, null);
        assertThat(preview.get("lines")).hasSize(3);
        JsonNode session = startSpot(loc, List.of(p));
        assertThat(session.get("lines")).hasSize(3);
        String id = session.get("id").asString();
        ok(200, "PATCH", "/inventory/counts/" + id + "/draft", Map.of("lines", List.of(
                counted(lineOf(session, p, "LOT-A"), 4, "damaged"),
                counted(lineOf(session, p, "LOT-B"), 3, null),
                counted(lineOf(session, p, null), 0, null))));
        ok(200, "POST", "/inventory/counts/" + id + "/submit", Map.of());
        BigDecimal lotA = jdbc.sql("SELECT quantity FROM \"InventoryBatch\" WHERE \"productId\" = ? AND \"batchNumber\" ="
                + " 'LOT-A'").param(p).query(BigDecimal.class).single();
        BigDecimal lotB = jdbc.sql("SELECT quantity FROM \"InventoryBatch\" WHERE \"productId\" = ? AND \"batchNumber\" ="
                + " 'LOT-B'").param(p).query(BigDecimal.class).single();
        assertThat(lotA).isEqualByComparingTo("4");
        assertThat(lotB).isEqualByComparingTo("3");
        assertThat(onHand(p, loc)).isEqualByComparingTo("7");

        String sp = product("SPECIFIC", false, true);
        stockIn(loc, List.of(line(sp, 2, 10)));
        for (String sn : List.of("SN-" + suffix(), "SN-" + suffix())) {
            jdbc.sql("INSERT INTO \"InventorySerial\" (id, \"organizationId\", \"productId\", \"locationId\","
                    + " \"serialNumber\", \"unitCost\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, 10, now())")
                    .params(UUID.randomUUID().toString(), demoOrg, sp, loc, sn).update();
        }
        String lost = jdbc.sql("SELECT \"serialNumber\" FROM \"InventorySerial\" WHERE \"productId\" = ?"
                + " ORDER BY \"serialNumber\" LIMIT 1").param(sp).query(String.class).single();
        JsonNode sc = startSpot(loc, List.of(sp));
        String scId = sc.get("id").asString();
        String scLine = lineOf(sc, sp, null);
        ok(200, "PATCH", "/inventory/counts/" + scId + "/draft", Map.of("lines", List.of(counted(scLine, 1, "missing"))));
        Res noSerials = as("POST", "/inventory/counts/" + scId + "/submit", Map.of());
        assertThat(noSerials.status()).isEqualTo(400);
        assertThat(noSerials.body().get("message").asString()).endsWith("is serial-tracked: a loss of 1 needs the 1"
                + " missing serial number(s) on the count line, got 0");
        Map<String, Object> wrong = counted(scLine, 1, "missing");
        wrong.put("missingSerials", List.of("NOPE-" + suffix()));
        ok(200, "PATCH", "/inventory/counts/" + scId + "/draft", Map.of("lines", List.of(wrong)));
        assertThat(as("POST", "/inventory/counts/" + scId + "/submit", Map.of()).body().get("message").asString())
                .contains("not in stock at this location");
        Map<String, Object> right = counted(scLine, 1, "missing");
        right.put("missingSerials", List.of(lost));
        ok(200, "PATCH", "/inventory/counts/" + scId + "/draft", Map.of("lines", List.of(right)));
        ok(200, "POST", "/inventory/counts/" + scId + "/submit", Map.of());
        assertThat(onHand(sp, loc)).isEqualByComparingTo("1");
        assertThat(jdbc.sql("SELECT status::text FROM \"InventorySerial\" WHERE \"productId\" = ? AND \"serialNumber\" = ?")
                .params(sp, lost).query(String.class).single()).isEqualTo("issued");
    }

    @Test
    @DisplayName("count preview / draft / submit / cancel stay inside the user's data scope")
    void countScope() {
        String inside = location("warehouse");
        String outside = location("warehouse");
        String p = product("AVCO", false, false);
        stockIn(outside, List.of(line(p, 2, 1)));
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "Scoped counter " + suffix(), new String[] {
                    "inventory_count:read", "inventory_count:count", "inventory_count:submit"}).update();
        String email = "counter-" + suffix() + "@demo.test";
        String userId = createUser(demoOrg, email, "Count@12345", role);
        ok(200, "PUT", "/user-data-scopes/" + userId, Map.of("branchIds", List.of(), "locationIds", List.of(inside)));
        String token = login("DEMO", email, "Count@12345");
        JsonNode session = startSpot(outside, List.of(p));
        String id = session.get("id").asString();
        assertThat(call("GET", "/inventory/counts/preview?locationId=" + outside + "&countType=spot&scopeProductIds="
                + p, null, token).status()).isEqualTo(403);
        assertThat(call("GET", "/inventory/counts/preview?locationId=" + inside + "&countType=spot&scopeProductIds="
                + p, null, token).status()).isEqualTo(200);
        assertThat(call("PATCH", "/inventory/counts/" + id + "/draft", Map.of("lines", List.of(counted(
                lineOf(session, p, null), 2, null))), token).status()).isEqualTo(403);
        assertThat(call("POST", "/inventory/counts/" + id + "/submit", Map.of(), token).status()).isEqualTo(403);
        assertThat(call("POST", "/inventory/counts/" + id + "/cancel", null, token).status()).isEqualTo(403);
        assertThat(ok(201, "POST", "/inventory/counts/" + id + "/cancel", null).get("ok").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("direct stock and count submit refuse a request without an Idempotency-Key")
    void idempotencyKeyRequired() {
        String loc = location("warehouse");
        String p = product("AVCO", false, false);
        Map<String, String> noKey = Map.of("Authorization", "Bearer " + adminToken, NO_IDEMPOTENCY_KEY, "1");
        String msg = "An Idempotency-Key header is required for this operation so a retry can never charge twice.";
        Res in = call("POST", "/inventory/direct-stock/in", Map.of("locationId", loc, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(line(p, 3, 1))), noKey);
        assertThat(in.status()).isEqualTo(400);
        assertThat(in.body().get("message").asString()).isEqualTo(msg);
        assertThat(onHand(p, loc)).isEqualByComparingTo("0");
        Res out = call("POST", "/inventory/direct-stock/out", Map.of("locationId", loc, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(line(p, 1, null))), noKey);
        assertThat(out.body().get("message").asString()).isEqualTo(msg);
        stockIn(loc, List.of(line(p, 3, 1)));
        JsonNode session = startSpot(loc, List.of(p));
        String id = session.get("id").asString();
        ok(200, "PATCH", "/inventory/counts/" + id + "/draft", Map.of("lines", List.of(counted(lineOf(session, p,
                null), 1, "lost"))));
        Res submit = call("POST", "/inventory/counts/" + id + "/submit", Map.of(), noKey);
        assertThat(submit.body().get("message").asString()).isEqualTo(msg);
        // a replayed submit answers the cached response and posts once
        Map<String, String> key = Map.of("Authorization", "Bearer " + adminToken, "Idempotency-Key",
                UUID.randomUUID().toString());
        Res first = call("POST", "/inventory/counts/" + id + "/submit", Map.of(), key);
        Res again = call("POST", "/inventory/counts/" + id + "/submit", Map.of(), key);
        assertThat(first.status()).isEqualTo(200);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().get("adjustmentId").asString()).isEqualTo(first.body().get("adjustmentId").asString());
        assertThat(onHand(p, loc)).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("reservations: reserve is idempotent per line, ATP, release")
    void reservations() {
        String loc = location("warehouse");
        String p = product("AVCO", false, false);
        stockIn(loc, List.of(line(p, 10, 1)));
        ok(201, "POST", "/inventory/reservations", Map.of("productId", p, "locationId", loc, "quantity", 3,
                "sourceType", "order", "sourceId", "SO-1"));
        ok(201, "POST", "/inventory/reservations", Map.of("productId", p, "locationId", loc, "quantity", 4,
                "sourceType", "order", "sourceId", "SO-1"));
        JsonNode atp = ok(200, "GET", "/inventory/reservations/atp?productId=" + p + "&locationId=" + loc, null);
        assertThat(atp.get("reserved").asString()).isEqualTo("4");
        assertThat(atp.get("available").asString()).isEqualTo("6");
        assertThat(ok(201, "POST", "/inventory/reservations/release", Map.of("sourceType", "order", "sourceId", "SO-1"))
                .get("released").asInt()).isEqualTo(1);
        assertThat(ok(200, "GET", "/inventory/reservations?sourceType=order&sourceId=SO-1", null).get(0).get("status")
                .asString()).isEqualTo("released");
    }

    @Test
    @DisplayName("posting rules: restore defaults, overview, preview resolution, CRUD")
    void postingRules() {
        JsonNode restored = ok(201, "POST", "/inventory/posting-rules/restore-defaults", null);
        assertThat(restored.get("created").asInt() + restored.get("skipped").asInt()).isGreaterThanOrEqualTo(30);
        assertThat(ok(201, "POST", "/inventory/posting-rules/restore-defaults", null).get("created").asInt()).isZero();
        JsonNode overview = ok(200, "GET", "/inventory/posting-rules/overview", null);
        assertThat(overview.get("STOCK_IN")).hasSize(2);
        JsonNode types = ok(200, "GET", "/inventory/posting-rules/movement-types", null);
        assertThat(types.get(0).get("label").asString()).isEqualTo("STOCK IN");
        String p = product("AVCO", false, false);
        JsonNode preview = ok(200, "GET", "/inventory/posting-rules/resolve-preview?productId=" + p
                + "&movementType=STOCK_OUT", null);
        assertThat(preview.get("resolutionLevel").asString()).isEqualTo("org_default");
        assertThat(preview.get("lines").get(0).get("debit").get("code").asString()).isEqualTo("5100");
        JsonNode created = ok(201, "POST", "/inventory/posting-rules", Map.of("movementType", "STOCK_OUT", "lineIndex", 0,
                "debitOrCredit", "debit", "accountSource", "account_mapping", "accountMappingKey", "default_expense",
                "productId", p));
        assertThat(ok(200, "GET", "/inventory/posting-rules/resolve-preview?productId=" + p + "&movementType=STOCK_OUT",
                null).get("resolutionLevel").asString()).isEqualTo("product_override");
        assertThat(ok(200, "PATCH", "/inventory/posting-rules/" + created.get("id").asString(), Map.of("isActive", false))
                .get("isActive").asBoolean()).isFalse();
        assertThat(as("DELETE", "/inventory/posting-rules/" + created.get("id").asString(), null).status()).isEqualTo(204);
        Res missing = as("DELETE", "/inventory/posting-rules/" + created.get("id").asString(), null);
        assertThat(missing.body().get("message").asString()).isEqualTo("Posting rule not found");
    }

    @Test
    @DisplayName("posting rules: validated bodies, literal account of this organisation and postable")
    void postingRuleValidation() {
        ok(201, "POST", "/inventory/posting-rules/restore-defaults", null);
        String p = product("AVCO", false, false);
        Map<String, Object> base = new LinkedHashMap<>(Map.of("movementType", "WASTE", "lineIndex", 0,
                "debitOrCredit", "debit", "accountSource", "account_mapping", "accountMappingKey", "default_expense",
                "productId", p));
        Map<String, Object> unknown = new LinkedHashMap<>(base);
        unknown.put("colour", "red");
        assertThat(as("POST", "/inventory/posting-rules", unknown).status()).isEqualTo(400);
        Map<String, Object> badType = new LinkedHashMap<>(base);
        badType.put("movementType", "TELEPORT");
        assertThat(as("POST", "/inventory/posting-rules", badType).status()).isEqualTo(400);
        Map<String, Object> badSide = new LinkedHashMap<>(base);
        badSide.put("debitOrCredit", "both");
        assertThat(as("POST", "/inventory/posting-rules", badSide).status()).isEqualTo(400);
        Map<String, Object> noLine = new LinkedHashMap<>(base);
        noLine.remove("lineIndex");
        assertThat(as("POST", "/inventory/posting-rules", noLine).status()).isEqualTo(400);

        String foreign = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Account\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?, ?, now())")
                .params(foreign, otherOrg, "9" + suffix().substring(0, 5), "Foreign account").update();
        String group = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Account\" (id, \"organizationId\", code, name, \"isPostable\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, false, now())").params(group, demoOrg, "G" + suffix().substring(0, 5),
                "Group account").update();
        String postable = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Account\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?, ?, now())")
                .params(postable, demoOrg, "L" + suffix().substring(0, 5), "Literal expense").update();

        Map<String, Object> literal = new LinkedHashMap<>(base);
        literal.put("accountSource", "literal");
        literal.remove("accountMappingKey");
        assertThat(as("POST", "/inventory/posting-rules", literal).body().get("message").asString())
                .isEqualTo("A literal posting rule needs a literalAccountId");
        literal.put("literalAccountId", foreign);
        Res other = as("POST", "/inventory/posting-rules", literal);
        assertThat(other.status()).isEqualTo(400);
        assertThat(other.body().get("message").asString()).isEqualTo("literalAccountId " + foreign
                + " is not an account of this organization");
        literal.put("literalAccountId", UUID.randomUUID().toString());
        assertThat(as("POST", "/inventory/posting-rules", literal).status()).isEqualTo(400);
        literal.put("literalAccountId", group);
        Res grp = as("POST", "/inventory/posting-rules", literal);
        assertThat(grp.status()).isEqualTo(400);
        assertThat(grp.body().get("message").asString()).endsWith("is a summary/group account and cannot be posted to");

        literal.put("literalAccountId", postable);
        JsonNode created = ok(201, "POST", "/inventory/posting-rules", literal);
        assertThat(created.get("literalAccountId").asString()).isEqualTo(postable);
        assertThat(created.get("accountSource").asString()).isEqualTo("literal");
        String id = created.get("id").asString();
        assertThat(as("PATCH", "/inventory/posting-rules/" + id, Map.of("literalAccountId", foreign)).status())
                .isEqualTo(400);
        assertThat(as("PATCH", "/inventory/posting-rules/" + id, Map.of("accountSource", "nowhere")).status())
                .isEqualTo(400);
        assertThat(as("PATCH", "/inventory/posting-rules/" + id, Map.of("isActive", false, "lineIndex", 1)).status())
                .isEqualTo(400);
        JsonNode patched = ok(200, "PATCH", "/inventory/posting-rules/" + id, Map.of("accountSource", "account_mapping",
                "accountMappingKey", "default_expense", "isActive", false));
        assertThat(patched.get("accountSource").asString()).isEqualTo("account_mapping");
        assertThat(patched.get("isActive").asBoolean()).isFalse();
        // Omitted accountSource takes the column default, as Prisma does.
        Map<String, Object> defaulted = new LinkedHashMap<>(base);
        defaulted.remove("accountSource");
        defaulted.put("lineIndex", 1);
        assertThat(ok(201, "POST", "/inventory/posting-rules", defaulted).get("accountSource").asString())
                .isEqualTo("account_mapping");
    }

    @Test
    @DisplayName("reports: valuation, item movements, analysis, registers, stock health, exceptions, stats")
    void reports() {
        String loc = location("warehouse");
        String p = product("AVCO", false, false);
        stockIn(loc, List.of(line(p, 8, 2.5)));
        JsonNode valuation = ok(200, "GET", "/inventory/reports/valuation?locationId=" + loc, null);
        assertThat(valuation.get("data").get(0).get("value").asDouble()).isEqualTo(20.0);
        JsonNode moves = ok(200, "GET", "/inventory/reports/item-movements?locationId=" + loc, null);
        assertThat(moves.get("data").get(0).get("qtyIn").asInt()).isEqualTo(8);
        assertThat(moves.get("totals").get("driftItems").asInt()).isZero();
        JsonNode analysis = ok(200, "GET", "/inventory/reports/movement-analysis?locationId=" + loc, null);
        assertThat(analysis.get("trend")).hasSize(30);
        Res badType = as("GET", "/inventory/reports/movement-analysis?moveTypes=bogus", null);
        assertThat(badType.body().get("message").asString()).isEqualTo("Unknown move type(s): bogus");
        JsonNode register = ok(200, "GET", "/inventory/reports/register/stock_in?locationId=" + loc, null);
        assertThat(register.get("summary").get("lines").asInt()).isEqualTo(1);
        assertThat(register.get("rows").get(0).get("sourceLabel").asString()).isEqualTo("Direct stock in");
        Res unknown = as("GET", "/inventory/reports/register/nope", null);
        assertThat(unknown.body().get("message").asString()).isEqualTo("Unknown report: nope");
        JsonNode health = ok(200, "GET", "/inventory/reports/stock-health?locationId=" + loc, null);
        assertThat(health.get("rows").get(0).get("status").asString()).isEqualTo("dead");
        assertThat(ok(200, "GET", "/inventory/reports/negative-stock", null).get("summary")).isNotNull();
        assertThat(ok(200, "GET", "/inventory/reports/expiring?days=10", null).isArray()).isTrue();
        assertThat(ok(200, "GET", "/inventory/reports/reorder", null).isArray()).isTrue();
        assertThat(ok(200, "GET", "/inventory/reports/movements?locationId=" + loc, null).get(0).get("netQty").asInt())
                .isEqualTo(8);
        assertThat(ok(200, "GET", "/inventory/stats", null).get("totalLocations").asInt()).isGreaterThan(0);
        JsonNode levels = ok(200, "GET", "/inventory/product-stock-levels?locationId=" + loc + "&pageSize=1000", null);
        assertThat(levels.get("meta").get("pageSize").asInt()).isEqualTo(200);
        JsonNode items = ok(200, "GET", "/inventory/items?locationId=" + loc, null);
        assertThat(items.get("data").get(0).get("location").get("id").asString()).isEqualTo(loc);
    }

    @Test
    @DisplayName("period lock: stock cannot move on or before the books lock date")
    void periodLock() {
        String loc = location("warehouse");
        String p = product("AVCO", false, false);
        jdbc.sql("UPDATE \"Organization\" SET \"booksLockDate\" = now() + interval '1 day' WHERE id = ?")
                .param(demoOrg).update();
        try {
            Res locked = as("POST", "/inventory/direct-stock/in", Map.of("locationId", loc, "responsibleById", adminId,
                    "approvedById", adminId, "items", List.of(line(p, 1, 1))));
            assertThat(locked.status()).isEqualTo(400);
            assertThat(locked.body().get("message").asString()).startsWith("Books are locked through ");
            assertThat(onHand(p, loc)).isEqualByComparingTo("0");
        } finally {
            jdbc.sql("UPDATE \"Organization\" SET \"booksLockDate\" = NULL WHERE id = ?").param(demoOrg).update();
        }
    }

    @Test
    @DisplayName("idempotency: a replayed direct stock-in returns the cached response and moves stock once")
    void idempotentReplay() {
        String loc = location("warehouse");
        String p = product("AVCO", false, false);
        Map<String, String> h = Map.of("Authorization", "Bearer " + adminToken, "Idempotency-Key",
                UUID.randomUUID().toString());
        Map<String, Object> body = Map.of("locationId", loc, "responsibleById", adminId, "approvedById", adminId,
                "items", List.of(line(p, 3, 1)));
        Res first = call("POST", "/inventory/direct-stock/in", body, h);
        Res second = call("POST", "/inventory/direct-stock/in", body, h);
        assertThat(first.status()).isEqualTo(200);
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.body().get("code").asString()).isEqualTo(first.body().get("code").asString());
        assertThat(onHand(p, loc)).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("tenant isolation: another organization cannot read or move this org's stock")
    void tenantIsolation() {
        String loc = location("warehouse");
        Res read = call("GET", "/inventory/locations/" + loc, null, otherToken);
        assertThat(read.status()).isEqualTo(404);
        Res move = call("POST", "/inventory/direct-stock/in", Map.of("locationId", loc, "responsibleById", adminId,
                "items", List.of(line(UUID.randomUUID().toString(), 1, 1))), otherToken);
        assertThat(move.status()).isEqualTo(404);
        assertThat(call("GET", "/inventory/stats", null, otherToken).body().get("totalProducts").asInt()).isZero();
    }

    @Test
    @DisplayName("authorization: every inventory route answers 401 without a token and 403 without its permission")
    void authorizationMatrix() {
        assertThat(assertAuthorizationMatrix("com.inventoryerp.inventory")).isEqualTo(142);
    }
}
