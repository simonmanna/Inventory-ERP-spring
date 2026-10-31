package com.inventoryerp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * FIN-3: profit and loss and cash flow figures, on journals posted in a month no other suite uses (March 2009), so the
 * expected values are exact even though DEMO is shared.
 */
class FinancialStatementsIT extends ApiTestSupport {

    static final String FROM = "2009-03-01";
    static final String TO = "2009-03-31";

    String checkerToken;

    String accountId(String code) {
        return jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = ?").params(demoOrg, code)
                .query(String.class).single();
    }

    void journal(String debitCode, String creditCode, int amount) {
        String id = ok(200, "POST", "/journal-entries", Map.of("journalCode", "GEN", "date", "2009-03-15",
                "description",
                "Statement test", "lines", List.of(Map.of("accountId", accountId(debitCode), "debit", amount),
                        Map.of("accountId", accountId(creditCode), "credit", amount)))).get("id").asString();
        Res posted = call("POST", "/journal-entries/" + id + "/post", null, checkerToken);
        assertThat(posted.status()).as(posted.raw().body()).isEqualTo(200);
    }

    static void assertMoney(JsonNode node, String field, String expected) {
        assertThat(new BigDecimal(node.get(field).asString())).as(field).isEqualByComparingTo(expected);
    }

    @BeforeAll
    void journals() {
        String role = jdbc.sql("SELECT id FROM \"Role\" WHERE \"organizationId\" = ? AND name = 'Administrator'")
                .param(demoOrg).query(String.class).single();
        String email = "statements-" + UUID.randomUUID().toString().substring(0, 8) + "@demo.test";
        createUser(demoOrg, email, "Checker@12345", role);
        checkerToken = login("DEMO", email, "Checker@12345");
        jdbc.sql("INSERT INTO \"FiscalPeriod\" (id, \"organizationId\", name, \"startDate\", \"endDate\", status, "
                + "\"updatedAt\")"
                        + " VALUES (?, ?, ?, ?::timestamp, ?::timestamp, 'open', now())")
                .params(UUID.randomUUID().toString(), demoOrg, "Statements 2009-03", FROM, TO).update();

        journal("1200", "3100", 1000); // capital in: financing
        journal("1200", "4100", 400); // cash sale: operating
        journal("5100", "1500", 150); // cost of sale: no cash
        journal("5200", "1200", 90); // overhead paid: operating
        journal("1600", "1200", 200); // plant bought: investing
    }

    @Test
    @DisplayName("P&L: revenue 400, cost of sales 150, overheads 90 give gross 250 and net 160")
    void profitAndLoss() {
        JsonNode pnl = ok(200, "GET", "/reports/accounting/profit-and-loss?from=" + FROM + "&to=" + TO, null);
        assertThat(pnl.get("source").asString()).isEqualTo("live");
        assertMoney(pnl, "revenue", "400");
        assertMoney(pnl, "contraRevenue", "0");
        assertMoney(pnl, "netRevenue", "400");
        assertMoney(pnl, "cogs", "150");
        assertMoney(pnl, "grossProfit", "250");
        assertMoney(pnl, "expense", "90");
        assertMoney(pnl, "operatingProfit", "160");
        assertMoney(pnl, "netProfit", "160");
    }

    @Test
    @DisplayName("cash flow: operating 310, investing -200, financing 1000, and closing cash reconciles to the ledger")
    void cashFlow() {
        JsonNode flow = ok(200, "GET", "/reports/accounting/cash-flow?from=" + FROM + "&to=" + TO, null);
        assertMoney(flow, "operating", "310");
        assertMoney(flow, "investing", "-200");
        assertMoney(flow, "financing", "1000");
        assertMoney(flow, "netCashFlow", "1110");
        assertThat(new BigDecimal(flow.get("closingCash").asString())
                .subtract(new BigDecimal(flow.get("openingCash").asString()))).isEqualByComparingTo("1110");
        assertThat(flow.get("reconciled").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("a window before the journals shows none of them")
    void emptyWindow() {
        JsonNode pnl = ok(200, "GET", "/reports/accounting/profit-and-loss?from=2009-02-01&to=2009-02-28", null);
        assertMoney(pnl, "netProfit", "0");
        JsonNode flow = ok(200, "GET", "/reports/accounting/cash-flow?from=2009-02-01&to=2009-02-28", null);
        assertMoney(flow, "netCashFlow", "0");
    }
}
