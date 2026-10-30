package com.inventoryerp.core.application;

import com.inventoryerp.kernel.seed.SeedContributor;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Core part of the opt-in construction-materials demo ({@code SEED_FLEET_DEMO=true}, fleet 1.9): two construction
 * customers, a haulage transporter (supplier) and the stocked products a quarry yard sells — aggregate and sand by the
 * tonne, concrete blocks by the piece — plus engine oil for the workshop. Codes {@code FD-*}; off by default and never
 * part of the POS demo; idempotent per code.
 */
@Component
public class ConstructionDemoSeedContributor implements SeedContributor {

    static final Object[][] PARTNERS = {
        {"FD-CUST-01", "Kampala Builders Ltd", true, false},
        {"FD-CUST-02", "Nile Towers Construction", true, false},
        {"FD-TRAN-01", "Lakeside Haulage", false, true},
    };

    static final Object[][] PRODUCTS = {
        {"FD-AGG-20", "Aggregate 20mm (t)", 45000, 18000},
        {"FD-SAND", "Sharp sand (t)", 32000, 12000},
        {"FD-BLOCK-6", "Concrete block 6\" (pcs)", 2500, 1500},
        {"FD-OIL-15W40", "Engine oil 15W-40 (L)", 14000, 9000},
    };

    private final JdbcClient jdbc;
    private final boolean enabled;

    public ConstructionDemoSeedContributor(JdbcClient jdbc, @Value("${SEED_FLEET_DEMO:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    @Override
    public int order() {
        return 860;
    }

    @Override
    public void seed(String organizationId) {
        if (enabled) {
            seedDemo(organizationId);
        }
    }

    /** The demo partners and products (also called by the fleet demo IT on a fresh organization). */
    public void seedDemo(String organizationId) {
        for (Object[] p : PARTNERS) {
            jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"isSupplier\","
                            + " \"updatedAt\") SELECT ?, ?, ?, ?, ?, ?, now() WHERE NOT EXISTS (SELECT 1 FROM \"Partner\""
                            + " WHERE \"organizationId\" = ? AND code = ?)")
                    .params(UUID.randomUUID().toString(), organizationId, p[0], p[1], p[2], p[3], organizationId, p[0])
                    .update();
        }
        String uom = jdbc.sql("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND code = 'UNIT'")
                .param(organizationId).query(String.class).optional().orElse(null);
        for (Object[] p : PRODUCTS) {
            jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                            + " \"costingMethod\", \"salesPrice\", \"costPrice\", \"uomId\", \"updatedAt\")"
                            + " VALUES (?, ?, ?, ?, 'stockable'::\"ProductType\", true, 'AVCO'::\"CostingMethod\", ?, ?, ?,"
                            + " now()) ON CONFLICT (\"organizationId\", code) DO NOTHING")
                    .params(UUID.randomUUID().toString(), organizationId, p[0], p[1], p[2], p[3], uom).update();
        }
    }
}
