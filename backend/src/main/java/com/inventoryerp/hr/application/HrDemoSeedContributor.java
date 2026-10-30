package com.inventoryerp.hr.application;

import com.inventoryerp.kernel.seed.SeedContributor;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * hr part of the opt-in construction-materials demo ({@code SEED_FLEET_DEMO=true}, fleet 1.9): three truck drivers, a
 * loader operator and a workshop mechanic ({@code EMP-FD01} .. {@code EMP-FD05}). Off by default and never part of
 * the POS demo; idempotent per employee code.
 */
@Component
public class HrDemoSeedContributor implements SeedContributor {

    static final String[][] EMPLOYEES = {
        {"EMP-FD01", "Okello", "Joseph", "650000"},
        {"EMP-FD02", "Namubiru", "Sarah", "650000"},
        {"EMP-FD03", "Mugisha", "Peter", "650000"},
        {"EMP-FD04", "Kato", "Ivan", "700000"},
        {"EMP-FD05", "Ssali", "Moses", "800000"},
    };

    private final JdbcClient jdbc;
    private final boolean enabled;

    public HrDemoSeedContributor(JdbcClient jdbc, @Value("${SEED_FLEET_DEMO:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    @Override
    public int order() {
        return 850;
    }

    @Override
    public void seed(String organizationId) {
        if (enabled) {
            seedDemo(organizationId);
        }
    }

    /** The demo employees (also called by the fleet demo IT on a fresh organization). */
    public void seedDemo(String organizationId) {
        for (String[] e : EMPLOYEES) {
            jdbc.sql("INSERT INTO \"HrEmployee\" (id, \"organizationId\", \"employeeCode\", \"lastName\", \"firstName\","
                            + " \"baseSalary\", \"updatedAt\") SELECT ?, ?, ?, ?, ?, ?::numeric, now() WHERE NOT EXISTS"
                            + " (SELECT 1 FROM \"HrEmployee\" WHERE \"organizationId\" = ? AND \"employeeCode\" = ?)")
                    .params(UUID.randomUUID().toString(), organizationId, e[0], e[1], e[2], e[3], organizationId, e[0])
                    .update();
        }
    }
}
