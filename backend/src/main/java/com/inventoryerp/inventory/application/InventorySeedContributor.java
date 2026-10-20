package com.inventoryerp.inventory.application;

import com.inventoryerp.kernel.seed.SeedContributor;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Inventory part of the demo seed ({@code seed.ts} "Inventory (Phase 4)"): the {@code MAIN} warehouse and the
 * widget's opening stock (100 @ 6) written once with its {@code opening_balance} ledger row.
 */
@Component
public class InventorySeedContributor implements SeedContributor {

    private final JdbcClient jdbc;

    public InventorySeedContributor(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int order() {
        return 300;
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
        }
    }
}
