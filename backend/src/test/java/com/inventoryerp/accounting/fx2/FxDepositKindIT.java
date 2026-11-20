package com.inventoryerp.accounting.fx2;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.support.AccountingFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** FX-2 D: advance vs refundable deposits; review queue; classification transfer at carrying value. */
class FxDepositKindIT extends AccountingFixtures {

    record Setup(Books b, String base, String ccy, String bank, String customer) {}

    Setup setup(String rateOn, String rate) {
        Books b = freshBooks();
        String base = baseCurrency(b.org());
        String ccy = testCurrency();
        rate(b, ccy, base, rateOn, rate);
        String bank = okAs(b, 201, "POST", "/accounts/cash-flow", Map.of("code", "12" + UUID.randomUUID().toString()
                .substring(0, 6), "name", "FX2 deposit bank " + ccy, "accountType", "bank", "currencyId", ccy))
                .get("id").asString();
        String customer = okAs(b, 201, "POST", "/partners", Map.of("code", "D-" + UUID.randomUUID().toString()
                .substring(0, 6), "name", "FX2 deposit customer", "isCustomer", true)).get("id").asString();
        return new Setup(b, base, ccy, bank, customer);
    }

    JsonNode receive(Setup s, String kind, int amount, String date) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerId", s.customer());
        body.put("amount", amount);
        body.put("paymentMethod", "bank");
        body.put("accountId", s.bank());
        body.put("currencyCode", s.ccy());
        body.put("receivedDate", date);
        if (kind != null) {
            body.put("kind", kind);
        }
        Res r = moneyAs(s.b(), "POST", "/customer-deposits", body);
        assertThat(r.status()).as(r.raw().body()).isIn(200, 201);
        return r.body();
    }

    String mappedOrNull(String org, String key) {
        return jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key = ? AND"
                + " \"deletedAt\" IS NULL").params(org, key).query(String.class).optional().orElse(null);
    }

    BigDecimal base(String org, String accountId, String partnerId) {
        return jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = ? AND l.\"accountId\" = ?"
                + " AND l.\"partnerId\" = ? AND e.status::text IN ('posted', 'reversed')")
                .params(org, accountId, partnerId).query(BigDecimal.class).single();
    }

    @Test
    @DisplayName("refundable: own account, cannot settle invoices, revalued per partner; advance never revalued")
    void refundableVersusAdvance() {
        LocalDate d0 = LocalDate.now().minusDays(20);
        LocalDate d1 = LocalDate.now().minusDays(5);
        Setup s = setup(d0.toString(), "3700");
        JsonNode refundable = receive(s, "refundable", 100, d0.plusDays(1).toString());
        JsonNode advance = receive(s, null, 50, d0.plusDays(1).toString());
        assertThat(refundable.get("kind").asString()).isEqualTo("refundable");
        assertThat(advance.get("kind").asString()).as("default kind").isEqualTo("advance");
        String refundAcct = mappedOrNull(s.b().org(), "customer_refundable_deposit");
        String advanceAcct = mappedOrNull(s.b().org(), "customer_deposit");
        assertThat(refundAcct).isNotNull().isNotEqualTo(advanceAcct);
        assertThat(base(s.b().org(), refundAcct, s.customer())).isEqualByComparingTo("-370000");

        Res apply = moneyAs(s.b(), "POST", "/customer-deposits/" + refundable.get("id").asString() + "/apply",
                Map.of("documentId", UUID.randomUUID().toString()));
        assertThat(apply.status()).isEqualTo(400);
        assertThat(apply.raw().body()).contains("is a refundable deposit; classify it as an advance");

        rate(s.b(), s.ccy(), s.base(), d1.toString(), "3800");
        JsonNode preview = okAs(s.b(), 200, "POST", "/fx-revaluations/preview", Map.of("asOf", d1.toString()));
        boolean refundableRow = false;
        for (JsonNode l : preview) {
            assertThat(l.get("accountId").asString()).as("advance deposits are never revalued").isNotEqualTo(advanceAcct);
            if (l.get("accountId").asString().equals(refundAcct)) {
                refundableRow = true;
                assertThat(l.get("partnerId").asString()).isEqualTo(s.customer());
                assertThat(new BigDecimal(l.get("toPost").asString())).as("100 × 100 more owed").isEqualByComparingTo(
                        "-10000");
            }
        }
        assertThat(refundableRow).isTrue();

        Res refund = moneyAs(s.b(), "POST", "/customer-deposits/" + refundable.get("id").asString() + "/refund",
                Map.of("paymentMethod", "bank", "accountId", s.bank(), "reason", "contract ended"));
        assertThat(refund.status()).as(refund.raw().body()).isEqualTo(200);
        assertThat(base(s.b().org(), refundAcct, s.customer())).as("cleared at carrying value").isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("review queue and classification: legacy deposit transferred at carrying value, no FX, reversible")
    void classification() {
        LocalDate d0 = LocalDate.now().minusDays(10);
        Setup s = setup(d0.toString(), "3700");
        JsonNode dep = receive(s, null, 40, d0.plusDays(1).toString());
        String id = dep.get("id").asString();
        jdbc.sql("UPDATE \"CustomerDeposit\" SET kind = NULL WHERE id = ?").param(id).update(); // a pre-FX-2 row

        JsonNode review = okAs(s.b(), 200, "GET", "/customer-deposits/classification-review", null);
        assertThat(review).hasSize(1);
        assertThat(review.get(0).get("id").asString()).isEqualTo(id);
        JsonNode warnings = okAs(s.b(), 200, "POST", "/fx-revaluations/warnings", Map.of("asOf", today()));
        assertThat(warnings.toString()).contains("unclassified_foreign_deposits");

        Res noReason = moneyAs(s.b(), "POST", "/customer-deposits/" + id + "/classify", Map.of("kind", "refundable"));
        assertThat(noReason.status()).isEqualTo(400);
        assertThat(noReason.raw().body()).contains("A reason is required to classify a deposit");

        BigDecimal fx0 = fxNet(s.b().org());
        Res classified = moneyAs(s.b(), "POST", "/customer-deposits/" + id + "/classify", Map.of("kind", "refundable",
                "reason", "Security deposit per lease 12"));
        assertThat(classified.status()).as(classified.raw().body()).isEqualTo(200);
        String refundAcct = mappedOrNull(s.b().org(), "customer_refundable_deposit");
        String advanceAcct = mappedOrNull(s.b().org(), "customer_deposit");
        assertThat(base(s.b().org(), refundAcct, s.customer())).isEqualByComparingTo("-148000");
        assertThat(base(s.b().org(), advanceAcct, s.customer())).isEqualByComparingTo("0");
        assertThat(fxNet(s.b().org())).as("no FX on classification").isEqualByComparingTo(fx0);
        assertThat(okAs(s.b(), 200, "GET", "/customer-deposits/classification-review", null)).isEmpty();

        Res same = moneyAs(s.b(), "POST", "/customer-deposits/" + id + "/classify", Map.of("kind", "refundable",
                "reason", "again"));
        assertThat(same.status()).isEqualTo(400);
        Res back = moneyAs(s.b(), "POST", "/customer-deposits/" + id + "/classify", Map.of("kind", "advance",
                "reason", "Customer will take goods instead"));
        assertThat(back.status()).as(back.raw().body()).isEqualTo(200);
        assertThat(base(s.b().org(), refundAcct, s.customer())).isEqualByComparingTo("0");
        assertThat(base(s.b().org(), advanceAcct, s.customer())).isEqualByComparingTo("-148000");
    }

    BigDecimal fxNet(String org) {
        return jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l WHERE"
                + " l.\"organizationId\" = ? AND l.\"accountId\" IN (SELECT \"accountId\" FROM \"AccountMapping\" WHERE"
                + " \"organizationId\" = ? AND key IN ('fx_gain', 'fx_loss'))").params(org, org)
                .query(BigDecimal.class).single();
    }
}
