package com.inventoryerp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.seed.SeedContributor;
import com.inventoryerp.manufacturing.application.ManufacturingDemoSeedContributor;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** MFG-4: the opt-in manufacturing demo is off by default and leaves a balanced month on a fresh organization. */
class ManufacturingDemoSeedIT extends ApiTestSupport {

    @Autowired
    ManufacturingDemoSeedContributor demo;

    long count(String sql, String org) {
        return jdbc.sql(sql).param(org).query(Long.class).single();
    }

    @Test
    @DisplayName("demo seed: off by default; on a fresh organization a month of balanced quarry and plant data")
    void demoSeed() {
        assertThat(count("SELECT count(*) FROM \"CrushingPlant\" WHERE \"organizationId\" = ? AND code = 'DM-CR1'",
                demoOrg)).isZero();

        String code = "MD" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String org = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Organization\" (id, code, name, \"currencyCode\", \"updatedAt\") VALUES (?, ?,"
                + " 'Plant Demo', 'UGX', now())").params(org, code).update();
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, 'Administrator', true, ?, now())").params(role, org,
                Permissions.ALL.toArray(String[]::new)).update();
        createUser(org, "owner-" + code.toLowerCase() + "@plant.test", "Plant@12345", role);
        new TransactionTemplate(txm).executeWithoutResult(s -> {
            for (SeedContributor c : seedContributors) {
                if (c.order() <= 300) {
                    c.seed(org);
                }
            }
        });

        Map<String, Object> out = demo.seedDemo(org);
        assertThat(out.get("skipped")).as(String.valueOf(out)).isNull();
        assertThat((Integer) out.get("crushingRuns")).isGreaterThanOrEqualTo(25);
        assertThat((Integer) out.get("blockOrders")).isGreaterThanOrEqualTo(8);
        assertThat((Integer) out.get("released")).isPositive();

        assertThat(count("SELECT count(*) FROM \"CrushingRun\" WHERE \"organizationId\" = ? AND status = 'posted'", org))
                .isEqualTo(((Integer) out.get("crushingRuns")).longValue());
        assertThat(count("SELECT count(*) FROM \"PrecastCuringBatch\" WHERE \"organizationId\" = ? AND status = 'curing'",
                org)).isPositive();
        BigDecimal rom = jdbc.sql("SELECT coalesce(sum(s.quantity), 0) FROM \"StockItem\" s JOIN \"Product\" p ON"
                + " p.id = s.\"productId\" WHERE s.\"organizationId\" = ? AND p.code = 'DM-ROM'").param(org)
                .query(BigDecimal.class).single();
        assertThat(rom).isPositive();
        assertThat(count("SELECT count(*) FROM (SELECT je.id FROM \"JournalEntry\" je JOIN \"JournalLine\" jl ON"
                + " jl.\"journalEntryId\" = je.id WHERE je.\"organizationId\" = ? GROUP BY je.id HAVING sum(jl.debit) <>"
                + " sum(jl.credit)) x", org)).isZero();

        assertThat(demo.seedDemo(org).get("skipped")).isEqualTo(true);
    }
}
