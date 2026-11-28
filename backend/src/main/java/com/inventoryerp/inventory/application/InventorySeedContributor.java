package com.inventoryerp.inventory.application;

import com.inventoryerp.kernel.seed.ConstructionDemoPart;
import com.inventoryerp.kernel.seed.SeedContributor;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inventory part of the demo seed ({@code seed.ts} "Inventory (Phase 4)"): the {@code MAIN} warehouse and the
 * widget's opening stock (100 @ 6) written once with its {@code opening_balance} ledger row.
 *
 * <p>Also the inventory part of the {@code SEED_CONSTRUCTION_DEMO} organization: after the plant data exists, the depot
 * stockyard {@code KLA-YARD} and every location assigned to its branch (the quarry yard and stockpiles to {@code QRY}).
 */
@Component
public class InventorySeedContributor implements SeedContributor, ConstructionDemoPart {

    private final JdbcClient jdbc;

    public InventorySeedContributor(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int order() {
        return 300;
    }

    @Override
    public int constructionOrder() {
        return 350;
    }

    @Override
    @Transactional
    public void seedConstructionDemo(String organizationId) {
        jdbc.sql("INSERT INTO \"InventoryLocation\" (id, \"organizationId\", code, name, type, \"branchId\", \"updatedAt\")"
                        + " SELECT ?, ?, 'KLA-YARD', 'Kampala depot stockyard', 'warehouse'::\"LocationType\", b.id, now()"
                        + " FROM \"Branch\" b WHERE b.\"organizationId\" = ? AND b.code = 'KLA'"
                        + " ON CONFLICT (\"organizationId\", code) DO NOTHING")
                .params(UUID.randomUUID().toString(), organizationId, organizationId).update();
        jdbc.sql("UPDATE \"InventoryLocation\" l SET name = CASE WHEN l.code = 'MAIN' THEN 'Quarry main yard' ELSE"
                        + " l.name END, \"branchId\" = b.id, \"updatedAt\" = now() FROM \"Branch\" b WHERE b.code = 'QRY' AND"
                        + " b.\"organizationId\" = l.\"organizationId\" AND l.\"organizationId\" = ? AND l.\"branchId\" IS NULL")
                .param(organizationId).update();
    }

    @Override
    public void seed(String organizationId) {
        jdbc.sql("INSERT INTO \"InventoryLocation\" (id, \"organizationId\", code, name, type, \"updatedAt\")"
                        + " VALUES (?, ?, 'MAIN', 'Main Warehouse', 'warehouse', now())"
                        + " ON CONFLICT (\"organizationId\", code) DO NOTHING")
                .params(UUID.randomUUID().toString(), organizationId).update();
        String warehouse = jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND code = 'MAIN'")
                .param(organizationId).query(String.class).single();
        String widget = jdbc.sql("SELECT id FROM \"Product\" WHERE \"organizationId\" = ? AND code = 'PRD-001'")
                .param(organizationId).query(String.class).optional().orElse(null);
        if (widget == null) {
            return;
        }
        jdbc.sql("UPDATE \"Product\" SET \"trackInventory\" = true, \"minQuantity\" = 10, \"updatedAt\" = now()"
                + " WHERE id = ?").param(widget).update();
        long cells = jdbc.sql("SELECT count(*) FROM \"StockItem\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                + " AND \"variantKey\" = '' AND \"locationId\" = ?").params(organizationId, widget, warehouse)
                .query(Long.class).single();
        long ledger = jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                + " AND \"locationId\" = ?").params(organizationId, widget, warehouse).query(Long.class).single();
        if (cells == 0 && ledger == 0) {
            jdbc.sql("INSERT INTO \"StockItem\" (id, \"organizationId\", \"productId\", \"variantKey\", \"locationId\","
                            + " quantity, \"runningAverageCost\", \"updatedAt\") VALUES (?, ?, ?, '', ?, 100, 6, now())")
                    .params(UUID.randomUUID().toString(), organizationId, widget, warehouse).update();
            jdbc.sql("INSERT INTO \"InventoryLedger\" (id, \"organizationId\", \"ledgerCode\", \"productId\", \"locationId\","
                            + " type, \"qtyBefore\", \"quantityChange\", \"balanceAfter\", \"unitCost\", \"totalValue\","
                            + " \"referenceType\", \"referenceId\", notes) VALUES (?, ?, 'STK/OPENING', ?, ?,"
                            + " 'opening_balance', 0, 100, 100, 6, 600, 'opening_balance', 'seed', 'Opening balance from seed')")
                    .params(UUID.randomUUID().toString(), organizationId, widget, warehouse).update();
            postOpeningJournal(organizationId, new java.math.BigDecimal("600"));
        }
    }

    /**
     * IPR D-1: the opening stock written above is journaled with it (Dr Stock Valuation / Cr Retained Earnings), in
     * the same seed step. It used to be journaled only by {@code PosSeedContributor} (order 400), which
     * {@code SeedRunner.base()} never runs (it stops at order 300), so an organisation seeded through {@code base()}
     * (GQDEMO) got the ledger row without a journal: a 600 stock-account variance. Only rows written now are journaled;
     * an existing organisation is never repaired here (no auto-repair, IPR R1).
     */
    private void postOpeningJournal(String org, java.math.BigDecimal value) {
        String postingKey = "seed_opening_inventory:" + org + ":inventory";
        if (one("SELECT id FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"postingKey\" = ? LIMIT 1", org,
                postingKey) != null) {
            return;
        }
        String stockAccount = one("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key ="
                + " 'stock_valuation' LIMIT 1", org);
        String equityAccount = one("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key ="
                + " 'retained_earnings' LIMIT 1", org);
        String journal = one("SELECT id FROM \"Journal\" WHERE \"organizationId\" = ? AND code = 'INV' LIMIT 1", org);
        if (journal == null) {
            journal = one("SELECT id FROM \"Journal\" WHERE \"organizationId\" = ? AND code = 'GEN' LIMIT 1", org);
        }
        if (stockAccount == null || equityAccount == null || journal == null) {
            throw new IllegalStateException("Opening stock seeded for organisation " + org + " but its journal cannot"
                    + " be posted: map stock_valuation and retained_earnings and create the INV journal first");
        }
        String entry = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"JournalEntry\" (id, \"organizationId\", \"journalId\", \"entryNumber\", \"postingDate\","
                + " status, \"postedAt\", description, \"sourceType\", \"sourceId\", \"postingKey\", \"updatedAt\") VALUES"
                + " (?, ?, ?, 'SEED-OPEN-INV-STK', now(), 'posted', now(), 'Opening inventory (seeded widget stock)',"
                + " 'opening_balance', 'seed', ?, now())").params(entry, org, journal, postingKey).update();
        jdbc.sql("INSERT INTO \"JournalLine\" (id, \"organizationId\", \"journalEntryId\", \"accountId\", debit,"
                + " \"baseDebit\", description, \"lineNumber\") VALUES (?, ?, ?, ?, ?, ?, 'Opening inventory', 1)")
                .params(UUID.randomUUID().toString(), org, entry, stockAccount, value, value).update();
        jdbc.sql("INSERT INTO \"JournalLine\" (id, \"organizationId\", \"journalEntryId\", \"accountId\", credit,"
                + " \"baseCredit\", description, \"lineNumber\") VALUES (?, ?, ?, ?, ?, ?, 'Opening inventory', 2)")
                .params(UUID.randomUUID().toString(), org, entry, equityAccount, value, value).update();
    }

    private String one(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(String.class).optional().orElse(null);
    }
}
