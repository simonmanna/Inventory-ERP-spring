package com.inventoryerp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.support.AccountingFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Review R10: the direct cash-flow statement allocates an entry's cash movement only across its opposite-side
 * non-cash lines, and revaluation of cash balances is the IAS 7 "effect of exchange rate changes", not operating cash.
 */
class CashFlowAllocationIT extends AccountingFixtures {

    static void assertMoney(JsonNode node, String field, String expected) {
        assertThat(new BigDecimal(node.get(field).asString())).as(field).isEqualByComparingTo(expected);
    }

    @Test
    @DisplayName("[R10] multi-line entries classify by the opposite-side counter lines; revaluation is a separate line")
    void oppositeSideAllocationAndFxEffect() {
        Books b = freshBooks();
        String day = LocalDate.now().minusDays(3).toString();
        String bank = mappedCode(b.org(), "default_bank");
        String opex = accountCodeOfCategory(b.org(), "operating_expense");
        // capital 150 in, of which 50 was contributed as plant: cash +100 is all financing (the plant leg is non-cash)
        postJe(b, day, List.of(JeLine.dr(bank, 100), JeLine.dr("1600", 50), JeLine.cr("3100", 150)));
        // one payment of 100 for overheads 30 and plant 70: split by the debit lines only
        postJe(b, day, List.of(JeLine.dr(opex, 30), JeLine.dr("1600", 70), JeLine.cr(bank, 100)));
        // bank → cash transfer of 100 with a 5 fee: only the fee leaves cash
        postJe(b, day, List.of(JeLine.dr("1100", 95), JeLine.dr(opex, 5), JeLine.cr(bank, 100)));
        // period-end revaluation of the bank balance (and its gain): not a cash flow
        postJe(b, "GEN", day, null, null, "fx_revaluation", null, List.of(JeLine.dr(bank, 40),
                JeLine.cr(mappedCode(b.org(), "fx_gain"), 40)));

        JsonNode flow = okAs(b, 200, "GET", "/reports/accounting/cash-flow?from=" + day + "&to=" + day, null);
        assertMoney(flow, "openingCash", "0");
        assertMoney(flow, "operating", "-35");
        assertMoney(flow, "investing", "-70");
        assertMoney(flow, "financing", "100");
        assertMoney(flow, "netCashFlow", "-5");
        assertMoney(flow, "effectOfExchangeRateChanges", "40");
        assertMoney(flow, "closingCash", "35");
        assertMoney(flow, "actualClosingCash", "35");
        assertThat(flow.get("reconciled").asBoolean()).isTrue();
    }
}
