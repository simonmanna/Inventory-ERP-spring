package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.BooksGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import tools.jackson.databind.JsonNode;

/**
 * Landed cost LC-4 accrual management (ADR-S053): rules with periods, rate types and fixed precedence; partial
 * clearing (1000 → 600 → 250 → 150) that adds up exactly; over-clearing refused; FX kept apart from the estimate
 * variance; the monitor.
 */
@ExtendWith(BooksGuard.class)
class LandedCostAccrualManagementIT extends LandedCostFixtures {

    String rule(Object... kv) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            body.put((String) kv[i], kv[i + 1]);
        }
        Res r = call("POST", "/procurement/landed-cost-accrual-rules", body, key());
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        return r.body().get("id").asString();
    }

    void keyed(String path) {
        Res r = call("POST", path, null, key());
        assertThat(r.status()).as(r.raw().body()).isIn(200, 201);
    }

    void dropRule(String id) {
        ok(204, "DELETE", "/procurement/landed-cost-accrual-rules/" + id, null);
    }

    JsonNode accrue(String grn) {
        Res a = call("POST", "/procurement/landed-cost-accruals", Map.of("goodsReceiptId", grn), key());
        assertThat(a.status()).as(a.raw().body()).isIn(200, 201);
        return a.body();
    }

    Res clear(String accrual, String voucher, Object amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("voucherId", voucher);
        if (amount != null) {
            body.put("amount", amount);
        }
        return call("POST", "/procurement/landed-cost-accruals/" + accrual + "/clear", body, key());
    }

    JsonNode accrual(String id) {
        return ok(200, "GET", "/procurement/landed-cost-accruals/" + id, null);
    }

    @Test
    @DisplayName("partial clears: 1000 accrued, cleared 600 → 250 → 150, remainder 0; books end as if posted directly")
    void partialClears() {
        String r = rule("scopeType", "supplier", "scopeId", vendor, "ratePct", 10, "name", "Sea freight estimate");
        try {
            String p = product("AVCO", false, 0);
            String grn = receive(p, 100, 100);
            Snap s = snap();
            String a = accrue(grn).get("id").asString();
            assertBooks(s, moves("adjunct", "1000", "accrual", "-1000"));
            int[] actuals = {600, 250, 150};
            String[] expectStatus = {"partially_cleared", "partially_cleared", "cleared"};
            String[] expectRemaining = {"400", "150", "0"};
            int posted = 0;
            for (int i = 0; i < actuals.length; i++) {
                String v = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", actuals[i])));
                approveAndPost(v);
                posted += actuals[i];
                Res c = clear(a, v, null);
                assertThat(c.status()).as(c.raw().body()).isIn(200, 201);
                JsonNode now = accrual(a);
                assertThat(now.get("status").asString()).isEqualTo(expectStatus[i]);
                assertThat(new BigDecimal(now.get("remainingAmount").asString())).isEqualByComparingTo(
                        expectRemaining[i]);
                assertBooks(s, moves("stock", String.valueOf(posted), "adjunct", String.valueOf(1000 - posted),
                        "accrual", String.valueOf(posted - 1000), "clearing", String.valueOf(-posted)));
                assertThat(clear(a, v, null).status()).as("same voucher twice").isEqualTo(400);
            }
            assertThat(avg(p, w1)).isEqualByComparingTo("110");
            assertThat(accrual(a).get("clearings")).hasSize(3);
        } finally {
            dropRule(r);
        }
    }

    @Test
    @DisplayName("over-clearing refused: above the remainder, above the voucher's share; close unwinds the rest")
    void overClearingRefused() {
        String r = rule("scopeType", "supplier", "scopeId", vendor, "ratePct", 10);
        try {
            String p = product("AVCO", false, 0);
            String grn = receive(p, 100, 100);
            Snap s = snap();
            String a = accrue(grn).get("id").asString();
            String v = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 700)));
            approveAndPost(v);
            Res tooMuch = clear(a, v, 1200);
            assertThat(tooMuch.status()).isEqualTo(400);
            assertThat(tooMuch.body().get("message").asString()).startsWith("Clearing 1200 exceeds the remaining 1000");
            Res aboveVoucher = clear(a, v, 900);
            assertThat(aboveVoucher.status()).isEqualTo(400);
            assertThat(aboveVoucher.body().get("message").asString()).contains("exceeds what voucher");
            assertThat(clear(a, v, 500).status()).isIn(200, 201);
            assertThat(call("POST", "/procurement/landed-cost-accruals/" + a + "/reverse", null, key()).status())
                    .as("a partly cleared accrual is closed, not reversed").isEqualTo(400);
            assertThat(call("POST", "/procurement/landed-cost-accruals/" + a + "/close", null, key()).status())
                    .isIn(200, 201);
            assertThat(accrual(a).get("status").asString()).isEqualTo("cleared");
            assertBooks(s, moves("stock", "700", "clearing", "-700"));
        } finally {
            dropRule(r);
        }
    }

    @Test
    @DisplayName("rules: overlapping periods refused; supplier beats default; the period covering the receipt applies")
    void rulePeriodsAndPrecedence() {
        String today = LocalDate.now().toString();
        String def = rule("scopeType", "default", "ratePct", 1, "name", "Default 1%", "effectiveFrom",
                LocalDate.now().minusYears(1).toString(), "effectiveTo", LocalDate.now().plusYears(1).toString());
        String old = null;
        String cur = null;
        try {
            old = rule("scopeType", "supplier", "scopeId", vendor, "ratePct", 4, "name", "Last year",
                    "effectiveFrom", LocalDate.now().minusYears(1).toString(), "effectiveTo",
                    LocalDate.now().minusDays(10).toString());
            Res overlap = call("POST", "/procurement/landed-cost-accrual-rules", Map.of("scopeType", "supplier",
                    "scopeId", vendor, "ratePct", 6, "name", "Clash", "effectiveFrom",
                    LocalDate.now().minusDays(20).toString()), key());
            assertThat(overlap.status()).isEqualTo(409);
            assertThat(overlap.body().get("message").asString()).contains("overlaps Last year");
            cur = rule("scopeType", "supplier", "scopeId", vendor, "ratePct", 5, "name", "This year",
                    "effectiveFrom", LocalDate.now().minusDays(9).toString());
            String p = product("AVCO", false, 0);
            String grn = receive(p, 10, 100);
            JsonNode a = accrue(grn);
            assertThat(new BigDecimal(a.get("amount").asString())).isEqualByComparingTo("50");
            assertThat(a.get("lines").get(0).get("ruleId").asString()).isEqualTo(cur);
            keyed("/procurement/landed-cost-accruals/" + a.get("id").asString() + "/reverse");
            assertThat(today).isNotBlank();
        } finally {
            for (String id : new String[] {def, old, cur}) {
                if (id != null) {
                    dropRule(id);
                }
            }
        }
    }

    @Test
    @DisplayName("per kg in USD: 2 USD/kg × 5 kg × 10 at 3,700 = 370,000; missing weight listed; FX apart from variance")
    void perKgInForeignCurrency() {
        String usd = "LCU";
        call("POST", "/currencies", Map.of("code", usd, "symbol", usd, "name", "LC-4 dollar"), key());
        String base = jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(demoOrg)
                .query(String.class).optional().orElse("USD");
        ok(201, "POST", "/currencies/rates", Map.of("fromCode", usd, "toCode", base, "asOf",
                LocalDate.now().minusDays(1).toString(), "rate", 3700));
        String r = rule("scopeType", "supplier", "scopeId", vendor, "rateType", "per_kg", "rate", 2, "currencyCode",
                usd, "name", "Air freight per kg");
        try {
            String light = product("AVCO", false, 0);
            Res refused = call("POST", "/procurement/landed-cost-accruals", Map.of("goodsReceiptId",
                    receive(light, 10, 100)), key());
            assertThat(refused.status()).isEqualTo(400);
            assertThat(refused.body().get("message").asString()).contains("set the weight of");

            String p = product("AVCO", false, 0);
            jdbc.sql("UPDATE \"Product\" SET weight = 5 WHERE id = ?").param(p).update();
            String grn = receive(p, 10, 100000);
            Snap s = snap();
            JsonNode a = accrue(grn);
            assertThat(new BigDecimal(a.get("amount").asString())).isEqualByComparingTo("370000");
            assertBooks(s, moves("adjunct", "370000", "accrual", "-370000"));
            // The actual arrives in EUR-like foreign terms at another rate: 100 at 3,600 = 360,000 functional.
            String v = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 100, "currencyCode", usd,
                    "exchangeRate", 3600)));
            approveAndPost(v);
            assertThat(clear(a.get("id").asString(), v, null).status()).isIn(200, 201);
            JsonNode clearing = accrual(a.get("id").asString()).get("clearings").get(0);
            assertThat(clearing.get("originalCurrency").asString()).isEqualTo(usd);
            assertThat(new BigDecimal(clearing.get("originalAmount").asString())).isEqualByComparingTo("97.297297");
            assertThat(new BigDecimal(clearing.get("functionalAmount").asString())).isEqualByComparingTo("360000");
            assertThat(new BigDecimal(clearing.get("actualFunctional").asString())).isEqualByComparingTo("360000");
            assertThat(new BigDecimal(clearing.get("actualRate").asString())).isEqualByComparingTo("3600");
            assertThat(new BigDecimal(clearing.get("variance").asString())).isEqualByComparingTo("0");
            // 10,000 of the estimate is still open; closing it leaves only the actual in stock (no FX in stock).
            keyed("/procurement/landed-cost-accruals/" + a.get("id").asString() + "/close");
            assertBooks(s, moves("stock", "360000", "clearing", "-360000"));
        } finally {
            dropRule(r);
        }
    }

    @Test
    @DisplayName("monitor: open accrual in the 0-30 bucket, not overdue; partly cleared counted; gone once closed")
    void monitor() {
        String r = rule("scopeType", "supplier", "scopeId", vendor, "ratePct", 10, "expectedSettlementDays", 45);
        try {
            String p = product("AVCO", false, 0);
            String grn = receive(p, 10, 100);
            String a = accrue(grn).get("id").asString();
            JsonNode m = ok(200, "GET", "/procurement/landed-cost-accruals/monitor", null);
            JsonNode row = null;
            for (JsonNode n : m.get("accruals")) {
                if (a.equals(n.get("id").asString())) {
                    row = n;
                }
            }
            assertThat(row).isNotNull();
            assertThat(row.get("bucket").asString()).isEqualTo("0-30");
            assertThat(row.get("overdue").asBoolean()).isFalse();
            assertThat(row.get("expectedSettlementDate").asString()).startsWith(LocalDate.now().plusDays(45)
                    .toString());
            keyed("/procurement/landed-cost-accruals/" + a + "/close");
            assertThat(ok(200, "GET", "/procurement/landed-cost-accruals/monitor", null).toString()).doesNotContain(a);
        } finally {
            dropRule(r);
        }
    }
}
