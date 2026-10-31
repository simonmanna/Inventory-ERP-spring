package com.inventoryerp.hr.application;

import com.inventoryerp.kernel.seed.ConstructionDemoPart;
import com.inventoryerp.kernel.seed.SeedContributor;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * hr part of the opt-in construction-materials demo ({@code SEED_FLEET_DEMO=true}, fleet 1.9): three truck drivers, a
 * loader operator and a workshop mechanic ({@code EMP-FD01} .. {@code EMP-FD05}). Off by default and never part of
 * the POS demo; idempotent per employee code.
 *
 * <p>Also the hr part of the {@code SEED_CONSTRUCTION_DEMO} organization: 12 drivers, 3 plant operators and 2 mechanics
 * ({@code EMP-CM01} .. {@code EMP-CM17}). The first driver is linked to the {@code driver@construction.test} sign-in so
 * the driver app shows that user's trips.
 */
@Component
public class HrDemoSeedContributor implements SeedContributor, ConstructionDemoPart {

    static final String[][] EMPLOYEES = {
        {"EMP-FD01", "Okello", "Joseph", "650000"},
        {"EMP-FD02", "Namubiru", "Sarah", "650000"},
        {"EMP-FD03", "Mugisha", "Peter", "650000"},
        {"EMP-FD04", "Kato", "Ivan", "700000"},
        {"EMP-FD05", "Ssali", "Moses", "800000"},
    };

    static final String[][] CONSTRUCTION_EMPLOYEES = {
        {"Okello", "Joseph", "650000"}, {"Namubiru", "Sarah", "650000"}, {"Mugisha", "Peter", "650000"},
        {"Kato", "Ivan", "650000"}, {"Nakato", "Ruth", "650000"}, {"Opio", "Samuel", "650000"},
        {"Tumusiime", "Brian", "650000"}, {"Atim", "Grace", "650000"}, {"Wasswa", "Henry", "650000"},
        {"Nabirye", "Esther", "650000"}, {"Odongo", "Paul", "650000"}, {"Kiggundu", "Moses", "650000"},
        {"Bbosa", "Ronald", "700000"}, {"Akello", "Doreen", "700000"}, {"Muwonge", "Isaac", "700000"},
        {"Ssali", "Moses", "800000"}, {"Lubega", "Fred", "800000"},
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
            employee(organizationId, e[0], e[1], e[2], e[3]);
        }
    }

    @Override
    public int constructionOrder() {
        return 200;
    }

    @Override
    @Transactional
    public void seedConstructionDemo(String organizationId) {
        for (int i = 0; i < CONSTRUCTION_EMPLOYEES.length; i++) {
            String[] e = CONSTRUCTION_EMPLOYEES[i];
            employee(organizationId, "EMP-CM%02d".formatted(i + 1), e[0], e[1], e[2]);
        }
        jdbc.sql("UPDATE \"HrEmployee\" SET \"userId\" = (SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email ="
                        + " 'driver@construction.test') WHERE \"organizationId\" = ? AND \"employeeCode\" = 'EMP-CM01'")
                .params(organizationId, organizationId).update();
    }

    private void employee(String organizationId, String code, String lastName, String firstName, String salary) {
        jdbc.sql("INSERT INTO \"HrEmployee\" (id, \"organizationId\", \"employeeCode\", \"lastName\", \"firstName\","
                        + " \"baseSalary\", \"updatedAt\") SELECT ?, ?, ?, ?, ?, ?::numeric, now() WHERE NOT EXISTS"
                        + " (SELECT 1 FROM \"HrEmployee\" WHERE \"organizationId\" = ? AND \"employeeCode\" = ?)")
                .params(UUID.randomUUID().toString(), organizationId, code, lastName, firstName, salary, organizationId,
                        code).update();
    }
}
