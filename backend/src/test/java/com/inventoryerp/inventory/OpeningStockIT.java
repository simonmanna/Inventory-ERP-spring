package com.inventoryerp.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Owner decision D5 (Phase I5, audit §9.1): opening stock enters only through an approved opening count, whose GL
 * other side is opening-balance equity (the {@code retained_earnings} mapping the GL opening balance balances
 * against), never the stock adjustment income; the GL opening balance refuses inventory accounts.
 */
class OpeningStockIT extends ApiTestSupport {

    static String sfx() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String location() {
        return ok(201, "POST", "/inventory/locations", Map.of("code", "OS-" + sfx(), "name", "Opening " + sfx(),
                "type", "storage_room")).get("id").asString();
    }

    String product() {
        String id = UUID.randomUUID().toString();
        String uom = jdbc.sql("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND code = 'UNIT'")
                .param(demoOrg).query(String.class).optional().orElse(null);
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"costingMethod\","
                        + " \"costPrice\", \"uomId\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable', 'AVCO', 4, ?, now())")
                .params(id, demoOrg, "OS-" + sfx(), "Opening item " + sfx(), uom).update();
        return id;
    }

    String mapped(String key) {
        return jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key = ?")
                .params(demoOrg, key).query(String.class).single();
    }

    /** Count {@code qty} of {@code product} at {@code loc} in a count of {@code type}; the posted GL lines. */
    List<Map<String, Object>> count(String type, String loc, String product, int qty) {
        JsonNode session = ok(201, "POST", "/inventory/counts/start", Map.of("locationId", loc, "countType", type,
                "scopeProductIds", List.of(product)));
        String id = session.get("id").asString();
        ok(200, "PATCH", "/inventory/counts/" + id + "/draft", Map.of("lines", List.of(Map.of("lineId",
                session.get("lines").get(0).get("id").asString(), "countedQty", qty, "reason", "counted"))));
        JsonNode submitted = ok(200, "POST", "/inventory/counts/" + id + "/submit", Map.of());
        String adjCode = jdbc.sql("SELECT \"adjCode\" FROM \"StockAdjustment\" WHERE id = ?")
                .param(submitted.get("adjustmentId").asString()).query(String.class).single();
        return jdbc.sql("SELECT l.\"accountId\", l.debit, l.credit FROM \"JournalLine\" l JOIN \"JournalEntry\" e"
                + " ON e.id = l.\"journalEntryId\" WHERE e.\"organizationId\" = ? AND e.\"sourceId\" = ?"
                + " ORDER BY l.debit DESC").params(demoOrg, adjCode).query().listOfRows();
    }

    /** Inventory subledger − GL inventory control account, org-wide (other tests' history is a constant here). */
    BigDecimal tieOutVariance() {
        return new BigDecimal(ok(200, "GET", "/reports/inventory/gl-tieout", null).get("variance").asString());
    }

    @Test
    void openingCountPostsAgainstOpeningEquity() {
        String loc = location();
        String p = product();
        String equity = mapped("retained_earnings");
        String stock = mapped("stock_valuation");

        BigDecimal varianceBefore = tieOutVariance();
        List<Map<String, Object>> opening = count("opening", loc, p, 10);
        // Reconciliation: the count moved the stock subledger and the GL inventory account by the same 40.
        assertThat(tieOutVariance()).isEqualByComparingTo(varianceBefore);
        assertThat(opening).hasSize(2);
        assertThat(opening.get(0).get("accountId")).isEqualTo(stock);
        assertThat((BigDecimal) opening.get(0).get("debit")).isEqualByComparingTo("40");
        assertThat(opening.get(1).get("accountId")).as("opening stock is opening equity").isEqualTo(equity);
        assertThat((BigDecimal) opening.get(1).get("credit")).isEqualByComparingTo("40");

        // A later cycle count's variance is an ordinary stock adjustment, not opening equity.
        List<Map<String, Object>> cycle = count("spot", loc, p, 12);
        assertThat(cycle).hasSize(2);
        assertThat(cycle.get(0).get("accountId")).isEqualTo(stock);
        assertThat(cycle.get(1).get("accountId")).isNotEqualTo(equity);
    }

    @Test
    void glOpeningBalanceRefusesInventoryAccounts() {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        Res refused = call("POST", "/opening-balances", Map.of("date", today, "accounts",
                List.of(Map.of("accountId", mapped("stock_valuation"), "debit", 100))), Map.of("Authorization", "Bearer " + adminToken,
                        "Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body().get("message").asString()).endsWith("holds inventory value. Enter opening stock"
                + " with an approved opening count so the stock ledger and the GL agree.");
    }
}
