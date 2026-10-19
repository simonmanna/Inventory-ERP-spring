package com.inventoryerp.core.application;

import com.inventoryerp.kernel.seed.SeedContributor;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Core part of the demo seed: UoM categories + units and the two sample products. */
@Component
public class CoreSeedContributor implements SeedContributor {

    private final JdbcClient jdbc;

    public CoreSeedContributor(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public void seed(String organizationId) {
        UomSeed.seedUomCategories(jdbc, organizationId);
        String uom = jdbc.sql("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND code = 'UNIT'")
                .param(organizationId).query(String.class).optional().orElse(null);
        String category = jdbc.sql("SELECT id FROM \"ProductCategory\" WHERE \"organizationId\" = ? AND name = 'General'")
                .param(organizationId).query(String.class).optional().orElse(null);
        Object[][] products = {
            {"PRD-001", "Generic Widget", "stockable", 10, 6},
            {"SRV-001", "Consulting Hour", "service", 50, 0},
        };
        for (Object[] p : products) {
            jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                            + " \"costPrice\", \"categoryId\", \"uomId\", \"updatedAt\")"
                            + " VALUES (?, ?, ?, ?, ?::\"ProductType\", ?, ?, ?, ?, now())"
                            + " ON CONFLICT (\"organizationId\", code) DO NOTHING")
                    .params(UUID.randomUUID().toString(), organizationId, p[0], p[1], p[2], p[3], p[4], category, uom)
                    .update();
        }
    }
}
