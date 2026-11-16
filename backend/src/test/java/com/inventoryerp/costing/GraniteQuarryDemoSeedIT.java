package com.inventoryerp.costing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.costing.application.GraniteQuarryDemoSeed;
import com.inventoryerp.kernel.seed.SeedRunnerAccess;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The granite quarry costing demo ({@code SEED_COSTING_DEMO}) lands every figure of its worksheet
 * ({@code docs/guides/granite-quarry-costing-demo.md}) in the engines and the GL, to the cent where the worksheet
 * has cents.
 */
class GraniteQuarryDemoSeedIT extends ApiTestSupport {

    private static final BigDecimal CENT = new BigDecimal("0.02");

    @Autowired
    GraniteQuarryDemoSeed demo;

    private String org;

    private BigDecimal one(String sql, Object... params) {
        BigDecimal v = jdbc.sql(sql).params(params).query(BigDecimal.class).single();
        return v == null ? BigDecimal.ZERO : v;
    }

    /** Debit − credit (base currency, UGX) of the posted lines on an account code. */
    private BigDecimal balance(String code) {
        return one("SELECT COALESCE(sum(l.\"baseDebit\"), 0) - COALESCE(sum(l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" JOIN \"Account\" a ON a.id = l.\"accountId\""
                + " WHERE e.\"organizationId\" = ? AND e.status = 'posted' AND a.code = ?", org, code);
    }

    /** Cost of the product's issues to customers (stock out on sales deliveries). */
    private BigDecimal cogs(String productCode) {
        return one("SELECT sum(l.\"totalValue\") FROM \"InventoryLedger\" l JOIN \"Product\" p ON p.id = l.\"productId\""
                + " WHERE l.\"organizationId\" = ? AND p.code = ? AND l.\"referenceType\" = 'fleet_trip'", org,
                productCode);
    }

    private static void money(BigDecimal actual, String expected) {
        assertThat(actual).isCloseTo(new BigDecimal(expected), within(CENT));
    }

    @Test
    @DisplayName("granite quarry demo: worksheet figures in the engines, the GL balanced, idempotent")
    void worksheet() {
        org = SeedRunnerAccess.seedDemoOrganization(jdbc, passwords, txm, seedContributors, demo);
        assertThat(org).isNotEqualTo(demoOrg);

        // Demo one: extraction 19,000/t, crushing 27,000,000 over 950 t.
        money(one("SELECT \"unitCost\" FROM \"QuarryExtractionLog\" WHERE \"organizationId\" = ?", org), "19000");
        Map<String, Object> run = jdbc.sql("SELECT \"feedCost\", \"conversionCost\", \"totalCost\", \"costPerTonne\","
                + " \"lossTonnes\" FROM \"CrushingRun\" WHERE \"organizationId\" = ? AND status = 'posted'").param(org)
                .query().singleRow();
        money((BigDecimal) run.get("feedCost"), "19000000");
        money((BigDecimal) run.get("conversionCost"), "8000000");
        money((BigDecimal) run.get("totalCost"), "27000000");
        money((BigDecimal) run.get("costPerTonne"), "28421.05");
        assertThat((BigDecimal) run.get("lossTonnes")).isEqualByComparingTo("50");

        // Demo three: 1,000 blocks at 1,818,684.21.
        money(one("SELECT sum(l.\"totalValue\") FROM \"InventoryLedger\" l JOIN \"Product\" p ON p.id = l.\"productId\""
                + " WHERE l.\"organizationId\" = ? AND p.code = 'GQ-BLOCK-6' AND l.\"quantityChange\" > 0", org),
                "1818684.21");

        // Demo four: landed basalt at 81,400/t.
        money(one("SELECT s.\"runningAverageCost\" FROM \"StockItem\" s JOIN \"Product\" p ON p.id = s.\"productId\""
                + " WHERE s.\"organizationId\" = ? AND p.code = 'GQ-BASALT'", org), "81400");

        // Product profitability: cost of sales per product.
        money(cogs("GQ-AGG-20"), "16938947.37");
        money(cogs("GQ-AGG-10"), "7105263.16");
        money(cogs("GQ-DUST"), "2842105.26");
        money(cogs("GQ-BLOCK-6"), "1818684.21");
        money(cogs("GQ-BASALT"), "24420000");

        // The books: revenue, cost of sales, freight, absorption netting out, stock, receivables.
        money(balance("4100").negate(), "69820000");
        money(balance("5100"), "53125000");
        money(balance("6460"), "500000");
        money(balance("5361").negate(), "19000000");
        money(balance("5350").negate(), "8430000");
        BigDecimal actualCosts = balance("6310").add(balance("6320")).add(balance("6330")).add(balance("6340"))
                .add(balance("6410")).add(balance("6420")).add(balance("5710")).add(balance("5600"));
        money(actualCosts, "27430000");
        money(balance("1400"), "17555000");
        money(balance("1420"), "0");
        money(balance("1300"), "30820000");
        money(balance("1200"), "136000000");
        // Payables: UGX 6,000,000 + USD 3,000 of landed-cost bills at 3,700; the clearing account nets to zero.
        money(balance("2100").negate(), "17100000");
        money(balance("2155"), "0");
        BigDecimal profit = one("SELECT COALESCE(sum(l.\"baseCredit\"), 0) - COALESCE(sum(l.\"baseDebit\"), 0) FROM \"JournalLine\" l"
                + " JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" JOIN \"Account\" a ON a.id = l.\"accountId\""
                + " WHERE e.\"organizationId\" = ? AND e.status = 'posted' AND a.code ~ '^[4-9][0-9]{3}$'", org);
        money(profit, "16195000");

        // Every entry balances.
        List<Map<String, Object>> unbalanced = jdbc.sql("SELECT e.id FROM \"JournalEntry\" e JOIN \"JournalLine\" l ON"
                + " l.\"journalEntryId\" = e.id WHERE e.\"organizationId\" = ? GROUP BY e.id HAVING"
                + " COALESCE(sum(l.\"baseDebit\"), 0) <> COALESCE(sum(l.\"baseCredit\"), 0)").param(org).query()
                .listOfRows();
        assertThat(unbalanced).isEmpty();

        // A second run adds nothing.
        long docs = one("SELECT count(*) FROM \"Document\" WHERE \"organizationId\" = ?", org).longValue();
        assertThat(SeedRunnerAccess.seedDemoOrganization(jdbc, passwords, txm, seedContributors, demo)).isEqualTo(org);
        assertThat(one("SELECT count(*) FROM \"Document\" WHERE \"organizationId\" = ?", org).longValue())
                .isEqualTo(docs);
    }
}
