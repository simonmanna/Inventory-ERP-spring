package com.inventoryerp.inventory.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.inventory.application.PosStockLocation;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService.GlReceiptContext;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Port of {@code inv-audit-tenancy-location.spec.ts}: org B can neither read nor move org A's stock — through the HTTP
 * API, through the engine in B's tenant scope, nor with a hostile explicit {@code organizationId} in SQL (row-level
 * security); an org-A posting rule never resolves for org B (H2); the POS stock location follows the register and is
 * never guessed between several warehouses (H10).
 */
class InventoryAuditTenancyLocationIT extends InventoryAuditSupport {

    @Autowired
    PosStockLocation posStockLocation;

    /** Org A = the suite's audit org; org B = a second isolated audit org. */
    AuditOrg orgB;

    String productA;

    @BeforeAll
    void tenants() {
        orgB = newOrg();
        productA = product("TEN-A-ITEM");
        receive(productA, 500, 7);
    }

    <T> T asB(Supplier<T> body) {
        return TenantContext.run(TenantStore.user(orgB.id(), orgB.userId()),
                () -> new TransactionTemplate(txm).execute(st -> body.get()));
    }

    Res asBHttp(String method, String path, Object body) {
        return call(method, path, body, orgB.token());
    }

    long countAsB(String sql, Object... params) {
        return asB(() -> jdbc.sql(sql).params(params).query(Long.class).single());
    }

    @Test
    @DisplayName("INV-INVARIANT-18: org B cannot read org A stock (API, engine scope and a hostile SQL filter)")
    void noCrossTenantReads() {
        assertThat(asBHttp("GET", "/inventory/locations/" + main, null).status()).isEqualTo(404);
        assertThat(asBHttp("GET", "/products/" + productA, null).status()).isEqualTo(404);
        Res item = asBHttp("GET", "/inventory/items/" + productA, null);
        assertThat(item.status() == 404 || item.raw().body().equals("[]") || item.raw().body().equals("null"))
                .as(item.raw().body()).isTrue();
        JsonNode ledger = asBHttp("GET", "/inventory/ledger?productId=" + productA, null).body();
        assertThat(ledger.has("data") ? ledger.get("data") : ledger).isEmpty();

        // RLS: inside B's tenant transaction even an explicit foreign organizationId sees nothing.
        Map<String, Long> leaks = new LinkedHashMap<>();
        leaks.put("stockItems", countAsB("SELECT count(*) FROM \"StockItem\" WHERE \"productId\" = ?", productA));
        leaks.put("ledger", countAsB("SELECT count(*) FROM \"InventoryLedger\" WHERE \"productId\" = ?", productA));
        leaks.put("batches", countAsB("SELECT count(*) FROM \"InventoryBatch\" WHERE \"productId\" = ?", productA));
        leaks.put("product", countAsB("SELECT count(*) FROM \"Product\" WHERE id = ?", productA));
        leaks.put("locations", countAsB("SELECT count(*) FROM \"InventoryLocation\" WHERE id = ?", main));
        leaks.put("forced", countAsB("SELECT count(*) FROM \"StockItem\" WHERE \"organizationId\" = ?", org));
        assertThat(leaks).containsOnly(Map.entry("stockItems", 0L), Map.entry("ledger", 0L), Map.entry("batches", 0L),
                Map.entry("product", 0L), Map.entry("locations", 0L), Map.entry("forced", 0L));
    }

    @Test
    @DisplayName("INV-INVARIANT-18: org B cannot issue org A stock")
    void noCrossTenantIssue() {
        Res http = asBHttp("POST", "/inventory/direct-stock/out", Map.of("locationId", main, "responsibleById",
                orgB.userId(), "approvedById", orgB.userId(), "items", List.of(item(productA, 1, null))));
        assertThat(http.status()).isEqualTo(404);
        assertThatThrownBy(() -> asB(() -> stock.issue(StockMove.of(productA, main, BigDecimal.ONE)
                .source("audit_attack", "x")))).isNotNull();
        assertThat(onHand(productA, main)).isEqualByComparingTo("500");
        assertThat(ledgerRows(productA)).isEqualTo(1);
    }

    @Test
    @DisplayName("H2: an org-A literal posting rule never resolves for org B")
    void postingRuleStaysInItsTenant() {
        for (Object[] rule : List.of(new Object[] {0, "debit", account("cogs")},
                new Object[] {1, "credit", account("stock_valuation")})) {
            okA(201, "POST", "/inventory/posting-rules", Map.of("movementType", "STOCK_OUT", "lineIndex", rule[0],
                    "debitOrCredit", rule[1], "accountSource", "literal", "literalAccountId", rule[2]));
        }
        String productB = product(orgB, "TEN-B-ITEM", Map.of());
        asB(() -> stock.receiveForDocument(StockMove.of(productB, orgB.main(), BigDecimal.TEN).unitCost(new BigDecimal("5")),
                new GlReceiptContext("audit_seed", "seedB", Instant.now(), null)));
        asB(() -> stock.issue(StockMove.of(productB, orgB.main(), new BigDecimal("4")).source("audit_sale", "sB")));
        List<String> bAccounts = jdbc.sql("SELECT l.\"accountId\" FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON"
                + " e.id = l.\"journalEntryId\" WHERE e.\"organizationId\" = ?").param(orgB.id()).query(String.class).list();
        assertThat(bAccounts).isNotEmpty();
        List<String> aAccounts = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ?").param(org)
                .query(String.class).list();
        assertThat(bAccounts).doesNotContainAnyElementsOf(aAccounts);
    }

    @Test
    @DisplayName("H2-architecture: InventoryPostingRule is covered by the tenant row-level security policy")
    void postingRuleUnderRls() {
        assertThat(jdbc.sql("SELECT relrowsecurity FROM pg_class WHERE relname = 'InventoryPostingRule'")
                .query(Boolean.class).single()).isTrue();
        assertThat(count("SELECT count(*) FROM pg_policies WHERE tablename = 'InventoryPostingRule' AND policyname ="
                + " 'tenant_isolation'")).isEqualTo(1);
    }

    @Test
    @DisplayName("H10: the POS stock location follows the register's location, not one per organisation")
    void posLocationPerRegister() {
        String branchStore = location(orgB.id(), "BRANCH-2-" + UUID.randomUUID().toString().substring(0, 4),
                "Branch 2 Store", "warehouse");
        String cash = jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key ="
                + " 'default_cash'").param(orgB.id()).query(String.class).single();
        String register = insert("CashRegister", Map.of("organizationId", orgB.id(), "code", "TILL-2-"
                + UUID.randomUUID().toString().substring(0, 4), "name", "Branch 2 Till", "locationId", branchStore,
                "defaultAccountId", cash, "updatedAt", now()));
        String registerLocation = jdbc.sql("SELECT \"locationId\" FROM \"CashRegister\" WHERE id = ?").param(register)
                .query(String.class).single();
        assertThat(asB(() -> posStockLocation.resolve(orgB.id(), registerLocation))).isEqualTo(branchStore);
        assertThat(asB(() -> posStockLocation.resolve(orgB.id(), null))).isEqualTo(orgB.main());
    }

    @Test
    @DisplayName("H10: without pos.stockLocationId and several warehouses the resolver refuses to guess")
    void noGuessBetweenWarehouses() {
        AuditOrg c = newOrg();
        jdbc.sql("DELETE FROM \"Setting\" WHERE \"organizationId\" = ? AND key = 'pos.stockLocationId'").param(c.id())
                .update();
        settingResolver.invalidate(c.id());
        long warehouses = count("SELECT count(*) FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND type ="
                + " 'warehouse' AND \"isActive\" = true", c.id());
        assertThat(warehouses).isGreaterThan(1);
        String resolved = TenantContext.run(TenantStore.organization(c.id()), () -> posStockLocation.resolve(c.id(), null));
        assertThat(resolved).isNull();
    }
}
