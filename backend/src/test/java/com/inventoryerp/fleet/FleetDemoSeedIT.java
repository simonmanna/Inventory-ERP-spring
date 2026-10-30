package com.inventoryerp.fleet;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.core.application.ConstructionDemoSeedContributor;
import com.inventoryerp.fleet.application.FleetDemoSeedContributor;
import com.inventoryerp.hr.application.HrDemoSeedContributor;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.seed.SeedContributor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Fleet 1.9 — the opt-in construction-materials demo seed on a fresh organization: off by default (DEMO untouched),
 * then core / hr / fleet demo data through the module services — trucks, loader, drivers, sites, a week of deliveries,
 * fuel, a maintenance job, a draft freight bill — every journal entry balanced, deterministic counts, idempotent,
 * and the reports show the week.
 */
class FleetDemoSeedIT extends ApiTestSupport {

    @Autowired
    HrDemoSeedContributor hrDemo;

    @Autowired
    ConstructionDemoSeedContributor coreDemo;

    @Autowired
    FleetDemoSeedContributor fleetDemo;

    @Test
    @DisplayName("demo seed: off by default; on a fresh organization a balanced, deterministic week of fleet data")
    void demoSeed() {
        // Off by default: the DEMO organization (seeded with every contributor) has no demo trucks.
        assertThat(jdbc.sql("SELECT count(*) FROM \"FleetVehicle\" WHERE \"organizationId\" = ? AND code = 'TRK-01'")
                .param(demoOrg).query(Long.class).single()).isZero();

        String code = "FD" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String org = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Organization\" (id, code, name, \"currencyCode\", \"updatedAt\") VALUES (?, ?,"
                + " 'Quarry Demo', 'UGX', now())").params(org, code).update();
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, 'Administrator', true, ?, now())").params(role, org,
                Permissions.ALL.toArray(String[]::new)).update();
        String email = "owner-" + code.toLowerCase() + "@quarry.test";
        createUser(org, email, "Quarry@12345", role);
        new TransactionTemplate(txm).executeWithoutResult(s -> {
            for (SeedContributor c : seedContributors) {
                if (c.order() <= 300) {
                    c.seed(org);
                }
            }
            hrDemo.seedDemo(org);
            coreDemo.seedDemo(org);
        });
        Map<String, Object> out = fleetDemo.seedDemo(org);
        assertThat(out.get("skipped")).as(String.valueOf(out)).isNull();
        assertThat(out.get("trips")).isEqualTo(24);

        assertThat(count("FleetVehicle", org)).isEqualTo(4);
        assertThat(count("FleetDriver", org)).isEqualTo(3);
        assertThat(count("FleetCustomerSite", org)).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM \"FleetLoad\" WHERE \"organizationId\" = ? AND status = 'closed'")
                .param(org).query(Long.class).single()).isEqualTo(24);
        assertThat(jdbc.sql("SELECT count(DISTINCT \"plannedDate\") FROM \"FleetLoad\" WHERE \"organizationId\" = ?")
                .param(org).query(Long.class).single()).isEqualTo(7);
        assertThat(jdbc.sql("SELECT count(*) FROM \"FleetFuelLog\" WHERE \"organizationId\" = ? AND status = 'posted'")
                .param(org).query(Long.class).single()).isEqualTo(6);
        assertThat(jdbc.sql("SELECT status FROM \"FleetWorkOrder\" WHERE \"organizationId\" = ?").param(org)
                .query(String.class).single()).isEqualTo("completed");
        assertThat(jdbc.sql("SELECT \"tripCount\" FROM \"FleetFreightBill\" WHERE \"organizationId\" = ? AND status ="
                + " 'draft'").param(org).query(Integer.class).single()).isEqualTo(3);
        // Every journal entry of the demo is balanced, and there are some.
        List<Map<String, Object>> entries = jdbc.sql("SELECT e.id, COALESCE(sum(l.debit), 0) AS d,"
                + " COALESCE(sum(l.credit), 0) AS c FROM \"JournalEntry\" e JOIN \"JournalLine\" l ON"
                + " l.\"journalEntryId\" = e.id WHERE e.\"organizationId\" = ? GROUP BY e.id").param(org).query()
                .listOfRows();
        assertThat(entries).hasSizeGreaterThan(20);
        for (Map<String, Object> e : entries) {
            assertThat((BigDecimal) e.get("d")).as("entry " + e.get("id")).isEqualByComparingTo((BigDecimal) e.get("c"));
        }
        // Idempotent: a second run changes nothing.
        assertThat(fleetDemo.seedDemo(org).get("skipped")).isEqualTo(true);
        assertThat(count("FleetVehicle", org)).isEqualTo(4);

        // The reports show the demo week.
        String token = login(code, email, "Quarry@12345");
        LocalDate today = LocalDate.now(BusinessZone.get());
        JsonNode d = call("GET", "/fleet/reports/deliveries?groupBy=day&from=" + today.minusDays(6) + "&to=" + today,
                null, token).body();
        assertThat(d.get("totals").get("trips").asInt()).isEqualTo(24);
        assertThat(d.get("rows")).hasSize(7);
        JsonNode p = call("GET", "/fleet/reports/delivery-profitability?groupBy=customer&from=" + today.minusDays(6)
                + "&to=" + today, null, token).body();
        assertThat(p.get("rows")).hasSize(2);
        assertThat(new BigDecimal(p.get("totals").get("revenue").asString())).isPositive();
        JsonNode c = call("GET", "/fleet/reports/vehicle-costs?from=" + today.minusDays(6) + "&to=" + today, null,
                token).body();
        assertThat(new BigDecimal(c.get("totals").get("total").asString())).isPositive();
    }

    long count(String table, String org) {
        return jdbc.sql("SELECT count(*) FROM \"" + table + "\" WHERE \"organizationId\" = ?").param(org)
                .query(Long.class).single();
    }
}
