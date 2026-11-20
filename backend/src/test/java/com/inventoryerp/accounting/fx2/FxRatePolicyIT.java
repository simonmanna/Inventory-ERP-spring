package com.inventoryerp.accounting.fx2;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.support.AccountingFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** FX-2 B: rate types, rate approval (maker-checker), per-context policies, tolerance and override snapshots. */
class FxRatePolicyIT extends AccountingFixtures {

    record Setup(Books b, String base, String ccy, String supplier, String customer, String service) {}

    Setup setup() {
        Books b = freshBooks();
        String base = baseCurrency(b.org());
        String ccy = testCurrency();
        String sfx = UUID.randomUUID().toString().substring(0, 6);
        String supplier = okAs(b, 201, "POST", "/partners", Map.of("code", "S-" + sfx, "name", "FX2 supplier " + sfx,
                "isSupplier", true)).get("id").asString();
        String customer = okAs(b, 201, "POST", "/partners", Map.of("code", "C-" + sfx, "name", "FX2 customer " + sfx,
                "isCustomer", true)).get("id").asString();
        String service = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                        + " \"costPrice\", \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, 'FX2 service', 'service',"
                        + " 100, 0, false, now())").params(service, b.org(), "FX2-" + sfx).update();
        return new Setup(b, base, ccy, supplier, customer, service);
    }

    Res typedRate(Setup s, String date, String rate, String type) {
        return as(s.b(), "POST", "/currencies/rates", Map.of("fromCode", s.ccy(), "toCode", s.base(), "asOf", date,
                "rate", new BigDecimal(rate), "rateType", type));
    }

    String postedBill(Setup s, String date) {
        String bill = okAs(s.b(), 200, "POST", "/vendor-bills", Map.of("partnerId", s.supplier(), "issueDate", date,
                "currencyId", s.ccy(), "lines", List.of(Map.of("description", "Svc", "quantity", 1, "unitPrice", 10))))
                .get("id").asString();
        okAs(s.b(), 200, "POST", "/vendor-bills/" + bill + "/post", Map.of());
        return bill;
    }

    Map<String, Object> snapshot(String sourceId) {
        return jdbc.sql("SELECT r.rate, r.source, r.\"rateType\", r.context, r.\"policyRate\", r.\"overrideReason\" FROM"
                + " \"JournalEntryFxRate\" r JOIN \"JournalEntry\" e ON e.id = r.\"journalEntryId\" WHERE e.\"sourceId\" = ?"
                + " ORDER BY r.\"createdAt\" LIMIT 1").param(sourceId).query().singleRow();
    }

    void policy(Setup s, String context, Map<String, Object> body) {
        okAs(s.b(), 200, "PUT", "/fx-rate-policies/" + context, body);
    }

    @Test
    @DisplayName("without policies every context is official, unapproved rates allowed, stated rates accepted")
    void defaults() {
        Setup s = setup();
        JsonNode list = okAs(s.b(), 200, "GET", "/fx-rate-policies", null);
        assertThat(list).hasSize(10);
        for (JsonNode p : list) {
            assertThat(p.get("rateType").asString()).isEqualTo("official");
            assertThat(p.get("configured").asBoolean()).isFalse();
        }
        Setup other = setup();
        policy(other, "purchase", Map.of("rateType", "bank"));
        assertThat(okAs(s.b(), 200, "GET", "/fx-rate-policies", null).get(1).get("rateType").asString())
                .as("another company's policy never applies").isEqualTo("official");
    }

    @Test
    @DisplayName("a purchase policy on the bank rate values bills at the bank rate and snapshots type and context")
    void rateTypePerContext() {
        Setup s = setup();
        String d0 = LocalDate.now().minusDays(5).toString();
        typedRate(s, d0, "3700", "official");
        typedRate(s, d0, "3800", "bank");
        policy(s, "purchase", Map.of("rateType", "bank"));
        Map<String, Object> snap = snapshot(postedBill(s, LocalDate.now().toString()));
        assertThat((BigDecimal) snap.get("rate")).isEqualByComparingTo("3800");
        assertThat(snap.get("rateType")).isEqualTo("bank");
        assertThat(snap.get("context")).isEqualTo("purchase");
        assertThat(snap.get("source")).isEqualTo("table");
    }

    @Test
    @DisplayName("approval required: pending rates are ignored, the enterer cannot approve, a second person can")
    void approvalMakerChecker() {
        Setup s = setup();
        String d0 = LocalDate.now().minusDays(5).toString();
        String d1 = LocalDate.now().minusDays(1).toString();
        typedRate(s, d0, "3800", "bank");
        policy(s, "purchase", Map.of("rateType", "bank", "requireApprovedRate", true));
        typedRate(s, d1, "3900", "bank");
        JsonNode pending = okAs(s.b(), 200, "GET", "/currencies/rates?status=pending", null);
        assertThat(pending).hasSize(1);
        String id = pending.get(0).get("id").asString();
        assertThat((BigDecimal) snapshot(postedBill(s, LocalDate.now().toString())).get("rate"))
                .as("the pending 3,900 is not used").isEqualByComparingTo("3800");

        Res self = as(s.b(), "POST", "/currencies/rates/" + id + "/approve", null);
        assertThat(self.status()).as(self.raw().body()).isEqualTo(403);
        jdbc.sql("UPDATE \"CurrencyRate\" SET \"createdBy\" = 'another-user' WHERE id = ?").param(id).update();
        assertThat(okAs(s.b(), 200, "POST", "/currencies/rates/" + id + "/approve", null).get("status").asString())
                .isEqualTo("approved");
        assertThat((BigDecimal) snapshot(postedBill(s, LocalDate.now().toString())).get("rate"))
                .isEqualByComparingTo("3900");
    }

    @Test
    @DisplayName("a stated rate beyond the tolerance needs a reason; the reason is kept on the snapshot")
    void toleranceAndOverride() {
        Setup s = setup();
        typedRate(s, LocalDate.now().minusDays(3).toString(), "3700", "official");
        policy(s, "sales", Map.of("rateType", "official", "statedRateTolerancePct", 2));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerId", s.customer());
        body.put("issueDate", today());
        body.put("currencyId", s.ccy());
        body.put("exchangeRate", 3900);
        body.put("lines", List.of(Map.of("productId", s.service(), "quantity", 1, "unitPrice", 100)));
        String inv = okAs(s.b(), 200, "POST", "/invoices", body).get("id").asString();
        Res refused = as(s.b(), "POST", "/invoices/" + inv + "/post", null);
        assertThat(refused.status()).as(refused.raw().body()).isEqualTo(400);
        assertThat(refused.raw().body()).contains("differs from the official rate 3700 by more than 2%");

        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + s.b().token());
        h.put("X-Fx-Rate-Override-Reason", "Bank slip 4471 shows 3,900");
        Res posted = call("POST", "/invoices/" + inv + "/post", null, h);
        assertThat(posted.status()).as(posted.raw().body()).isEqualTo(200);
        Map<String, Object> snap = snapshot(inv);
        assertThat((BigDecimal) snap.get("rate")).isEqualByComparingTo("3900");
        assertThat((BigDecimal) snap.get("policyRate")).isEqualByComparingTo("3700");
        assertThat(snap.get("overrideReason")).isEqualTo("Bank slip 4471 shows 3,900");
    }

    @Test
    @DisplayName("a policy can forbid stated rates")
    void statedRateForbidden() {
        Setup s = setup();
        typedRate(s, LocalDate.now().minusDays(3).toString(), "3700", "official");
        policy(s, "sales", Map.of("allowStatedRate", false));
        String inv = okAs(s.b(), 200, "POST", "/invoices", Map.of("partnerId", s.customer(), "issueDate", today(),
                "currencyId", s.ccy(), "exchangeRate", 3650, "lines", List.of(Map.of("productId", s.service(),
                        "quantity", 1, "unitPrice", 100)))).get("id").asString();
        Res r = as(s.b(), "POST", "/invoices/" + inv + "/post", null);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.raw().body()).contains("Stated exchange rates are not allowed for sales transactions");
    }

    @Test
    @DisplayName("rate type must be known; a rate of zero is still refused")
    void validation() {
        Setup s = setup();
        assertThat(typedRate(s, today(), "3700", "street").status()).isEqualTo(400);
        assertThat(typedRate(s, today(), "0", "bank").status()).isEqualTo(400);
    }
}
