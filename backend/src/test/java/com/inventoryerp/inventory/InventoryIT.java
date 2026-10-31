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
        assertThat(assertAuthorizationMatrix("com.inventoryerp.inventory")).isEqualTo(86);
    }
}
