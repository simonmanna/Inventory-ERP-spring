package com.inventoryerp.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.application.InventoryTieOutMonitor;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.settings.SettingsService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Inventory hardening (phase/inv-hardening, defects 1, 3, 8, 9): costing method / cost price edits with stock on hand,
 * the GL tie-out date basis and its daily monitor, the ledger guard bypass, and stock events after a rollback.
 */
class InventoryHardeningIT extends ApiTestSupport {

    @Autowired
    StockService stock;

    @Autowired
    InventoryTieOutMonitor tieOutMonitor;

    @Autowired
    SettingsService settings;

    @Autowired
    ObjectProvider<FlywayConnectionDetails> ownerDetails;

    String run;
    String warehouse;

    static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @BeforeAll
    void fixtures() {
        run = "HD" + suffix();
        warehouse = ok(201, "POST", "/inventory/locations", Map.of("code", "HD-" + suffix(), "name", "Hardening "
                + suffix(), "type", "warehouse")).get("id").asString();
    }

    String product(String method, Object costPrice) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", run + "-" + suffix());
        body.put("name", run + " " + method + " " + suffix());
        body.put("productType", "stockable");
        body.put("trackInventory", true);
        body.put("costingMethod", method);
        if (costPrice != null) {
            body.put("costPrice", costPrice);
        }
        return ok(201, "POST", "/products", body).get("id").asString();
    }

    void receive(String productId, Object qty, Object cost, Instant receivedAt) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("productId", productId);
        line.put("description", "goods");
        line.put("quantity", qty);
        line.put("unitCost", cost);
        ok(200, "POST", "/procurement/goods-receipts/adhoc", Map.of("warehouseId", warehouse, "receivedAt",
                receivedAt.toString(), "lines", List.of(line)));
    }

    BigDecimal accountingValue(String productId) {
        for (JsonNode i : ok(200, "GET", "/reports/inventory/valuation", null).get("items")) {
            if (productId.equals(i.get("productId").asString())) {
                return new BigDecimal(i.get("exactValue").asString());
            }
        }
        return BigDecimal.ZERO;
    }

    // ------------------------------------------------------------------ defect 1

    @Test
    @DisplayName("the costing method cannot change while stock is on hand; the same method and a stock-free change pass")
    void costingMethodGuard() {
        String withStock = product("AVCO", 5);
        receive(withStock, 10, 5, Instant.now());
        Res refused = as("PATCH", "/products/" + withStock, Map.of("costingMethod", "FIFO"));
        assertThat(refused.status()).as(refused.raw().body()).isEqualTo(400);
        assertThat(refused.body().get("message").asString()).contains("cannot be changed while it has stock on hand")
                .contains("/inventory/cost-policies");
        // The product form re-sends the current method on every save; an AVCO cost price is only a fallback.
        ok(200, "PATCH", "/products/" + withStock, Map.of("costingMethod", "AVCO", "costPrice", 7));
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"sourceId\" = ? AND \"sourceType\" ="
                + " 'product_cost_price'").param(withStock).query(Long.class).single()).isZero();

        String empty = product("AVCO", 5);
        assertThat(ok(200, "PATCH", "/products/" + empty, Map.of("costingMethod", "FIFO")).get("costingMethod")
                .asString()).isEqualTo("FIFO");
    }

    @Test
    @DisplayName("a STANDARD cost price change revalues the stock on hand (GL + ledger) in the same request")
    void standardCostPriceRevaluation() {
        String p = product("STANDARD", 50);
        receive(p, 10, 60, Instant.now());
        assertThat(accountingValue(p)).isEqualByComparingTo("500");

        ok(200, "PATCH", "/products/" + p, Map.of("costPrice", 55));

        BigDecimal debit = jdbc.sql("SELECT coalesce(sum(jl.debit), 0) FROM \"JournalLine\" jl JOIN \"JournalEntry\" e"
                + " ON e.id = jl.\"journalEntryId\" JOIN \"AccountMapping\" m ON m.\"accountId\" = jl.\"accountId\""
                + " AND m.\"organizationId\" = e.\"organizationId\" AND m.key = 'stock_valuation' WHERE e.\"sourceId\" = ?"
                + " AND e.\"sourceType\" = 'product_cost_price'").param(p).query(BigDecimal.class).single();
        assertThat(debit).isEqualByComparingTo("50");
        BigDecimal ledger = jdbc.sql("SELECT coalesce(sum(\"totalValue\"), 0) FROM \"InventoryLedger\" WHERE"
                + " \"organizationId\" = ? AND \"productId\" = ? AND \"referenceType\" = 'product_cost_price'")
                .params(demoOrg, p).query(BigDecimal.class).single();
        assertThat(ledger).isEqualByComparingTo("50");
        assertThat(accountingValue(p)).isEqualByComparingTo("550");
        JsonNode tie = ok(200, "GET", "/reports/inventory/gl-tieout?tolerance=0.05", null);
        for (JsonNode s : tie.get("bySource")) {
            if ("product_cost_price".equals(s.get("source").asString())) {
                assertThat(s.get("difference").asDouble()).as(s.toString()).isZero();
            }
        }

        // A price decrease posts the other way; an unchanged price posts nothing.
        ok(200, "PATCH", "/products/" + p, Map.of("costPrice", 52, "costingMethod", "STANDARD"));
        ok(200, "PATCH", "/products/" + p, Map.of("costPrice", 52));
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"sourceId\" = ? AND \"sourceType\" ="
                + " 'product_cost_price'").param(p).query(Long.class).single()).isEqualTo(2);
        assertThat(accountingValue(p)).isEqualByComparingTo("520");
    }

    @Test
    @DisplayName("the cost price of a product governed by an active standard cost changes only through a new standard")
    void governedCostPrice() {
        String p = product("STANDARD", 20);
        String std = ok(201, "POST", "/inventory/standard-costs", Map.of("productId", p, "effectiveFrom",
                LocalDate.now(ZoneOffset.UTC).toString(), "materialCost", 20)).get("id").asString();
        ok(200, "POST", "/inventory/standard-costs/" + std + "/approve", null);
        Res activated = call("POST", "/inventory/standard-costs/" + std + "/activate", null, Map.of("Authorization",
                "Bearer " + adminToken, "Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(activated.status()).as(activated.raw().body()).isEqualTo(200);
        Res refused = as("PATCH", "/products/" + p, Map.of("costPrice", 25));
        assertThat(refused.status()).as(refused.raw().body()).isEqualTo(400);
        assertThat(refused.body().get("message").asString()).contains("is set by its active standard cost");
        ok(200, "PATCH", "/products/" + p, Map.of("costPrice", 20, "name", run + " governed"));
    }

    // ------------------------------------------------------------------ defect 3

    @Test
    @DisplayName("tie-out as of a past day dates a backdated receipt by its journal on both sides")
    void tieOutDateBasis() {
        String asOf = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();
        JsonNode before = ok(200, "GET", "/reports/inventory/gl-tieout?asOf=" + asOf, null);
        String p = product("AVCO", null);
        receive(p, 1, "123.45", Instant.now().minus(3, ChronoUnit.DAYS));
        JsonNode after = ok(200, "GET", "/reports/inventory/gl-tieout?asOf=" + asOf, null);
        BigDecimal gl = delta(before, after, "glBalance");
        assertThat(gl).isEqualByComparingTo("123.45");
        // The ledger row was written now but its receipt is dated three days ago: it belongs to the same as-of.
        assertThat(delta(before, after, "ledgerMovementValue")).isEqualByComparingTo(gl);
        assertThat(delta(before, after, "subledgerValue")).isEqualByComparingTo(gl);
        assertThat(delta(before, after, "variance")).isEqualByComparingTo("0");
    }

    static BigDecimal delta(JsonNode before, JsonNode after, String field) {
        return new BigDecimal(after.get(field).toString()).subtract(new BigDecimal(before.get(field).toString()));
    }

    @Test
    @DisplayName("the daily tie-out check raises one inventory exception and an alert per open variance")
    void tieOutMonitor() {
        TenantStore other = TenantStore.organization(otherOrg);
        // Stock without any inventory account: the whole valuation is a variance.
        String productId = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?, ?, now())")
                .params(productId, otherOrg, "TIE-" + suffix(), "Tie-out probe").update();
        String location = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"InventoryLocation\" (id, \"organizationId\", code, name, \"updatedAt\")"
                + " VALUES (?, ?, ?, 'Probe', now())").params(location, otherOrg, "TL-" + suffix()).update();
        jdbc.sql("INSERT INTO \"StockItem\" (id, \"organizationId\", \"productId\", \"locationId\", quantity,"
                        + " \"runningAverageCost\", \"updatedAt\") VALUES (?, ?, ?, ?, 10, 5, now())")
                .params(UUID.randomUUID().toString(), otherOrg, productId, location).update();

        TenantContext.run(other, () -> settings.set("inventory.glTieOut.enabled", false, null, null));
        assertThat(TenantContext.run(other, () -> tieOutMonitor.check())).isNull();
        assertThat(openTieOutExceptions()).isZero();

        TenantContext.run(other, () -> settings.set("inventory.glTieOut.enabled", true, null, null));
        TenantContext.run(other, () -> settings.set("inventory.glTieOut.tolerance", 100, null, null));
        assertThat(TenantContext.run(other, () -> tieOutMonitor.check()).get("withinTolerance")).isEqualTo(true);
        assertThat(openTieOutExceptions()).isZero();

        TenantContext.run(other, () -> settings.set("inventory.glTieOut.tolerance", 1, null, null));
        Map<String, Object> out = TenantContext.run(other, () -> tieOutMonitor.check());
        assertThat(out.get("withinTolerance")).isEqualTo(false);
        assertThat(openTieOutExceptions()).isEqualTo(1);
        TenantContext.run(other, () -> tieOutMonitor.check());
        assertThat(openTieOutExceptions()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"organizationId\" = ? AND category = 'inventory'"
                + " AND title = 'Inventory does not tie to the GL'").param(otherOrg).query(Long.class).single())
                .isEqualTo(1);
    }

    long openTieOutExceptions() {
        return jdbc.sql("SELECT count(*) FROM \"InventoryException\" WHERE \"organizationId\" = ? AND kind = ?"
                + " AND status = 'open'").params(otherOrg, InventoryTieOutMonitor.KIND).query(Long.class).single();
    }

    // ------------------------------------------------------------------ defect 8

    @Test
    @DisplayName("the API role cannot switch the evidence guard off; the schema owner still can")
    void ledgerGuardBypass() throws Exception {
        String p = product("AVCO", null);
        receive(p, 2, 3, Instant.now());
        String ledgerId = jdbc.sql("SELECT id FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                + " LIMIT 1").params(demoOrg, p).query(String.class).single();
        TransactionTemplate tx = new TransactionTemplate(txm);
        assertThatThrownBy(() -> tx.executeWithoutResult(st -> {
            jdbc.sql("SET LOCAL app.evidence_purge = 'on'").update();
            jdbc.sql("UPDATE \"InventoryLedger\" SET notes = 'tampered' WHERE id = ?").param(ledgerId).update();
        })).hasStackTraceContaining("is posted stock evidence");

        FlywayConnectionDetails owner = ownerDetails.getObject();
        try (Connection c = DriverManager.getConnection(owner.getJdbcUrl(), owner.getUsername(), owner.getPassword())) {
            c.setAutoCommit(false);
            c.createStatement().execute("SET LOCAL app.evidence_purge = 'on'");
            try (PreparedStatement ps = c.prepareStatement("UPDATE \"InventoryLedger\" SET notes = 'purge test'"
                    + " WHERE id = ?")) {
                ps.setString(1, ledgerId);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
            c.rollback();
        }
    }

    // ------------------------------------------------------------------ defect 9

    @Test
    @DisplayName("stock events go out only for committed movements")
    void stockEventsAfterRollback() {
        String rolledBack = product("AVCO", null);
        String committed = product("AVCO", null);
        TransactionTemplate tx = new TransactionTemplate(txm);
        TenantContext.run(TenantStore.organization(demoOrg), () -> tx.executeWithoutResult(st -> {
            stock.receive(StockMove.of(rolledBack, warehouse, BigDecimal.ONE).unitCost(BigDecimal.TEN));
            st.setRollbackOnly();
        }));
        TenantContext.run(TenantStore.organization(demoOrg), () -> tx.executeWithoutResult(st ->
                stock.receive(StockMove.of(committed, warehouse, BigDecimal.ONE).unitCost(BigDecimal.TEN))));
        detachedWrites.awaitIdle(java.time.Duration.ofSeconds(10));
        assertThat(received(rolledBack)).isZero();
        assertThat(received(committed)).isEqualTo(1);
    }

    long received(String productId) {
        return jdbc.sql("SELECT count(*) FROM \"EventOutbox\" WHERE \"eventName\" = 'stock.received'"
                + " AND payload->>'productId' = ?").param(productId).query(Long.class).single();
    }
}
