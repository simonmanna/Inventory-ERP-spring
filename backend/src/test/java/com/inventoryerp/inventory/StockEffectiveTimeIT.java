package com.inventoryerp.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.audit.InventoryAuditSupport;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;

/**
 * IPR 1b (ADR-S088, T10 / R3, SW-6, SW-7, M-1, M-30 reader): every new ledger row records when the goods moved
 * ({@code effectiveAt}). Offline device work moves at its device time, accepted and flagged (never refused) when its
 * clock is ahead or it syncs late; a back-office adjustment or transfer posts its journal on its own date; the ledger
 * note and journal memo trace a movement whose posting date differs from when it happened.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StockEffectiveTimeIT extends InventoryAuditSupport {

    String deviceToken;
    String deviceId;
    String managerPin;
    int seq;

    @BeforeAll
    void device() {
        JsonNode d = okA(201, "POST", "/sync/devices/register", Map.of("name", "Effective-time tablet", "platform", "web"));
        deviceToken = d.get("deviceToken").asString();
        deviceId = d.get("id").asString();
        managerPin = String.valueOf(10_000_000 + (int) (Math.random() * 89_999_999));
        okA(201, "POST", "/pos/override/pin", Map.of("pin", managerPin));
    }

    // ------------------------------------------------------------------ helpers

    Map<String, Object> op(String type, Map<String, Object> payload, Instant occurredAt) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("opId", "op-" + UUID.randomUUID());
        o.put("deviceSeq", ++seq);
        o.put("type", type);
        o.put("actorUserId", auditUserId);
        o.put("occurredAt", occurredAt.toString());
        o.put("payload", payload);
        return o;
    }

    JsonNode push(Map<String, Object> op) {
        Res r = call("POST", "/sync/push", Map.of("deviceId", deviceId, "ops", List.of(op)),
                Map.of("X-Device-Token", deviceToken));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        return r.body().get("results").get(0);
    }

    Map<String, Object> stockOut(String product, int qty) {
        return Map.of("locationId", main, "responsibleById", auditUserId, "approvedById", auditUserId, "approverPin",
                managerPin, "items", List.of(Map.of("productId", product, "quantity", qty)));
    }

    /** The product's ledger rows (oldest first), read in the audit org's tenant scope. */
    List<Map<String, Object>> rows(String product) {
        return asOrg(() -> jdbc.sql("SELECT \"ledgerCode\", \"quantityChange\", \"effectiveAt\", \"createdAt\", notes,"
                + " \"referenceType\" FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                + " ORDER BY seq").params(org, product).query().listOfRows());
    }

    static Instant ts(Object v) {
        return v == null ? null : ((Timestamp) v).toLocalDateTime().toInstant(ZoneOffset.UTC);
    }

    long openExceptions(String kind) {
        return asOrg(() -> jdbc.sql("SELECT count(*) FROM \"InventoryException\" WHERE \"organizationId\" = ? AND kind = ?"
                + " AND payload->>'deviceId' = ?").params(org, kind, deviceId).query(Long.class).single());
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("SW-7 / M-1: an offline stock-out synced 30 days late is accepted at its device time, flagged lateSync")
    void lateOfflineOpAcceptedAtDeviceTime() {
        String p = product("ET-LATE");
        receive(p, 10, 5);
        Instant device = Instant.now().minus(Duration.ofDays(30)).truncatedTo(ChronoUnit.MILLIS);
        long flagged = openExceptions("offline_late_sync");
        JsonNode r = push(op("stock.out", stockOut(p, 2), device));
        assertThat(r.get("status").asString()).as("accepted, never refused: " + r).isEqualTo("applied");
        Map<String, Object> out = rows(p).getLast();
        assertThat((BigDecimal) out.get("quantityChange")).isEqualByComparingTo("-2");
        assertThat(ts(out.get("effectiveAt"))).as("moved at the device time").isEqualTo(device);
        assertThat(openExceptions("offline_late_sync")).isEqualTo(flagged + 1);
        assertThat(onHand(p, main)).isEqualByComparingTo("8");
        assertIntegrityGreen();
        assertGlTieOut();
    }

    @Test
    @DisplayName("SW-7: an op stamped ahead of the server is stored at the receive time and flagged clockSkew")
    void futureOpStoredAtReceiveTime() {
        String p = product("ET-SKEW");
        receive(p, 10, 5);
        Instant before = Instant.now().minusSeconds(1);
        long flagged = openExceptions("offline_clock_skew");
        JsonNode r = push(op("stock.out", stockOut(p, 1), Instant.now().plus(Duration.ofHours(3))));
        assertThat(r.get("status").asString()).as(r.toString()).isEqualTo("applied");
        Instant eff = ts(rows(p).getLast().get("effectiveAt"));
        assertThat(eff).as("receive time, not the device's future time").isBetween(before, Instant.now().plusSeconds(1));
        assertThat(openExceptions("offline_clock_skew")).isEqualTo(flagged + 1);
        assertIntegrityGreen();
    }

    @Test
    @DisplayName("SW-7: a replayed op (same opId) is not applied twice")
    void replayKeepsIdempotency() {
        String p = product("ET-REPLAY");
        receive(p, 10, 5);
        Map<String, Object> o = op("stock.out", stockOut(p, 3), Instant.now().minus(Duration.ofDays(9)));
        assertThat(push(o).get("status").asString()).isEqualTo("applied");
        assertThat(push(o).get("status").asString()).isEqualTo("replayed");
        assertThat(onHand(p, main)).isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("T10: a back-office move has no explicit effective time (readers fall back to createdAt); every new row"
            + " has the column")
    void backOfficeMoveUsesNow() {
        String p = product("ET-BACK");
        Instant before = Instant.now().minusSeconds(1);
        receive(p, 10, 5);
        Map<String, Object> row = rows(p).getFirst();
        Instant eff = ts(row.get("effectiveAt"));
        assertThat(eff).as("NOW for a move without a business time").isBetween(before, Instant.now().plusSeconds(1));
        assertThat(eff).isEqualTo(ts(row.get("createdAt")));
    }

    @Test
    @DisplayName("V700 (R1): the effective-time column is nullable with no default and the append-only trigger stays"
            + " enabled")
    void v700KeepsLedgerGuard() {
        Map<String, Object> col = jdbc.sql("SELECT is_nullable, column_default FROM information_schema.columns WHERE"
                + " table_name = 'InventoryLedger' AND column_name = 'effectiveAt'").query().singleRow();
        assertThat(col.get("is_nullable")).isEqualTo("YES");
        assertThat(col.get("column_default")).isNull();
        String enabled = jdbc.sql("SELECT t.tgenabled::text FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid"
                + " WHERE c.relname = 'InventoryLedger' AND t.tgname = 'inventory_ledger_append_only'")
                .query(String.class).single();
        assertThat(enabled).as("trigger enabled (O = origin/local)").isEqualTo("O");
    }

    @Test
    @DisplayName("SW-6: an adjustment dated 3 days ago posts its journal on that date; the memo names the ledger code")
    void adjustmentHonoursItsDate() {
        String p = product("ET-SW6");
        receive(p, 10, 5);
        Instant date = Instant.now().minus(Duration.ofDays(3)).truncatedTo(ChronoUnit.MILLIS);
        StockMove m = StockMove.of(p, main, null);
        m.deltaQuantity = new BigDecimal("-2");
        m.date = date;
        m.responsibleById = auditUserId;
        asOrg(() -> stock.adjust(m));
        Map<String, Object> adj = rows(p).getLast();
        String code = String.valueOf(adj.get("ledgerCode"));
        Map<String, Object> je = asOrg(() -> jdbc.sql("SELECT \"postingDate\", description FROM \"JournalEntry\" WHERE"
                + " \"organizationId\" = ? AND description LIKE ?").params(org, "%" + code + "%").query().singleRow());
        assertThat(ts(je.get("postingDate")).atOffset(ZoneOffset.UTC).toLocalDate())
                .as("journal on the adjustment's date").isEqualTo(date.atOffset(ZoneOffset.UTC).toLocalDate());
        assertThat(String.valueOf(je.get("description"))).contains(code);
        assertIntegrityGreen();
        assertGlTieOut();
    }

    @Test
    @DisplayName("SW-6: a transfer dated 2 days ago posts on that date")
    void transferHonoursItsDate() {
        String p = product("ET-TRF");
        receive(p, 10, 5);
        Instant date = Instant.now().minus(Duration.ofDays(2)).truncatedTo(ChronoUnit.MILLIS);
        StockMove m = StockMove.of(p, null, new BigDecimal("4"));
        m.fromLocationId = main;
        m.toLocationId = alt;
        m.date = date;
        String code = asOrg(() -> stock.transfer(m)).ledgerCode();
        List<Map<String, Object>> jes = asOrg(() -> jdbc.sql("SELECT \"postingDate\" FROM \"JournalEntry\" WHERE"
                + " \"organizationId\" = ? AND description LIKE ?").params(org, "%" + code + "%").query().listOfRows());
        for (Map<String, Object> je : jes) {
            assertThat(ts(je.get("postingDate")).atOffset(ZoneOffset.UTC).toLocalDate())
                    .isEqualTo(date.atOffset(ZoneOffset.UTC).toLocalDate());
        }
        assertThat(onHand(p, alt)).isEqualByComparingTo("4");
        assertIntegrityGreen();
        assertGlTieOut();
    }

    @Test
    @DisplayName("ADR-S088 limit 2: a goods receipt dated more than 7 days back is refused; a date-only past receipt"
            + " moves at the end of that day")
    void goodsReceiptBackdating() {
        String p = product("ET-GRN");
        Res old = api("POST", "/procurement/goods-receipts/adhoc", Map.of("partnerId",
                com.inventoryerp.TestSuppliers.supplier(jdbc, org), "warehouseId", main, "receivedAt",
                Instant.now().minus(Duration.ofDays(30)).toString(), "lines", List.of(Map.of("productId", p,
                        "description", "Goods", "quantity", 5, "unitCost", 10))));
        assertThat(old.status()).as(old.raw().body()).isEqualTo(400);
        assertThat(old.body().get("message").asString()).isEqualTo("Goods receipt date is more than 7 days in the past");

        LocalDate day = LocalDate.now(ZoneOffset.UTC).minusDays(2);
        okA(200, "POST", "/procurement/goods-receipts/adhoc", Map.of("partnerId",
                com.inventoryerp.TestSuppliers.supplier(jdbc, org), "warehouseId", main, "receivedAt",
                day.toString(), "lines", List.of(Map.of("productId", p, "description", "Goods", "quantity", 5,
                        "unitCost", 10))));
        Instant eff = ts(rows(p).getLast().get("effectiveAt"));
        assertThat(eff).isEqualTo(day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusMillis(1));
        assertIntegrityGreen();
        assertGlTieOut();
    }
}
