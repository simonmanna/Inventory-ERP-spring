package com.inventoryerp.kernel.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.common.BusinessZone;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The "Construction materials" demo organization of {@code SEED_CONSTRUCTION_DEMO}: nothing of it in {@code DEMO}, the
 * brief's master data, three months of documents, and every journal entry balanced. Slow: it seeds the whole quarter.
 */
class ConstructionDemoSeedIT extends ApiTestSupport {

    @Autowired
    List<ConstructionDemoPart> parts;

    long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    long rows(String table, String org) {
        return count("SELECT count(*) FROM \"" + table + "\" WHERE \"organizationId\" = ?", org);
    }

    @Test
    @DisplayName("construction demo: separate organization, full master data, a balanced quarter of documents")
    void quarter() {
        String org = SeedRunnerAccess.seedConstruction(jdbc, passwords, txm, seedContributors, parts);
        assertThat(org).isNotEqualTo(demoOrg);

        // Never mixed into the POS demo.
        assertThat(count("SELECT count(*) FROM \"FleetVehicle\" WHERE \"organizationId\" = ? AND code LIKE 'CM-%'",
                demoOrg)).isZero();
        assertThat(count("SELECT count(*) FROM \"Partner\" WHERE \"organizationId\" = ? AND code LIKE 'CM-%'", demoOrg))
                .isZero();

        // Master data of the brief.
        assertThat(rows("Branch", org)).isEqualTo(2);
        assertThat(rows("QuarrySite", org)).isEqualTo(1);
        assertThat(rows("CrushingPlant", org)).isEqualTo(1);
        assertThat(rows("FleetVehicle", org)).isEqualTo(13);
        assertThat(rows("FleetDriver", org)).isEqualTo(15);
        assertThat(rows("FleetCustomerSite", org)).isEqualTo(20);
        assertThat(count("SELECT count(*) FROM \"Partner\" WHERE \"organizationId\" = ? AND \"isCustomer\"", org))
                .isEqualTo(20);
        assertThat(count("SELECT count(*) FROM \"Partner\" WHERE \"organizationId\" = ? AND code LIKE 'CM-TRAN-%'",
                org)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND \"branchId\" IS"
                + " NULL", org)).isZero();
        assertThat(count("SELECT count(*) FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key = 'wip'", org))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM \"Role\" WHERE \"organizationId\" = ? AND name = 'Weighbridge Operator'",
                org)).isEqualTo(1);

        // Three months of work.
        long posted = count("SELECT count(*) FROM \"CrushingRun\" WHERE \"organizationId\" = ? AND status = 'posted'",
                org);
        assertThat(posted).isGreaterThanOrEqualTo(70);
        long closed = count("SELECT count(*) FROM \"FleetLoad\" WHERE \"organizationId\" = ? AND status = 'closed'",
                org);
        assertThat(closed).isGreaterThanOrEqualTo(450);
        assertThat(count("SELECT count(*) FROM \"WeighbridgeTicket\" WHERE \"organizationId\" = ? AND status ="
                + " 'completed'", org)).isEqualTo(closed);
        assertThat(count("SELECT count(*) FROM \"FleetFuelLog\" WHERE \"organizationId\" = ? AND status = 'posted'",
                org)).isPositive();
        assertThat(count("SELECT count(*) FROM \"FleetWorkOrder\" WHERE \"organizationId\" = ? AND status ="
                + " 'completed'", org)).isPositive();
        assertThat(count("SELECT count(*) FROM \"Document\" WHERE \"organizationId\" = ? AND \"documentType\" ="
                + " 'sales_invoice' AND status IN ('posted', 'paid')", org)).isEqualTo(closed);
        assertThat(rows("Payment", org)).isPositive();
        assertThat(count("SELECT count(*) FROM \"FleetFreightBill\" WHERE \"organizationId\" = ?", org)).isPositive();

        // The past days carry their own dates, cost of sales included.
        LocalDate today = LocalDate.now(BusinessZone.get());
        Date first = jdbc.sql("SELECT min(\"dispatchedAt\")::date FROM \"FleetLoad\" WHERE \"organizationId\" = ?")
                .param(org).query(Date.class).single();
        assertThat(first.toLocalDate()).isBefore(today.minusDays(80));
        assertThat(count("SELECT count(DISTINCT date_trunc('month', \"postingDate\")) FROM \"JournalEntry\" WHERE"
                + " \"organizationId\" = ? AND \"sourceType\" = 'fleet_trip'", org)).isGreaterThanOrEqualTo(3);

        // Debits equal credits, entry by entry and in total.
        List<Map<String, Object>> entries = jdbc.sql("SELECT e.id, COALESCE(sum(l.debit), 0) AS d,"
                + " COALESCE(sum(l.credit), 0) AS c FROM \"JournalEntry\" e JOIN \"JournalLine\" l ON"
                + " l.\"journalEntryId\" = e.id WHERE e.\"organizationId\" = ? GROUP BY e.id").param(org).query()
                .listOfRows();
        assertThat(entries).hasSizeGreaterThan(1000);
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (Map<String, Object> e : entries) {
            assertThat((BigDecimal) e.get("d")).as("entry " + e.get("id"))
                    .isEqualByComparingTo((BigDecimal) e.get("c"));
            debit = debit.add((BigDecimal) e.get("d"));
            credit = credit.add((BigDecimal) e.get("c"));
        }
        assertThat(debit).isEqualByComparingTo(credit);
        assertThat(debit).isPositive();

        // A second run adds nothing.
        long units = rows("FleetVehicle", org);
        assertThat(SeedRunnerAccess.seedConstruction(jdbc, passwords, txm, seedContributors, parts)).isEqualTo(org);
        assertThat(rows("FleetVehicle", org)).isEqualTo(units);
    }
}
