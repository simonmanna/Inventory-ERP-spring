package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Phase S9 of ADR-S011: agreements price and track their releases (over-release goes to approval), rebate accruals
 * post only the difference and settle by credit note against the accrual, commission statements claw back credit
 * notes and become payroll earnings, targets compare with net revenue.
 */
class SalesAgreementIT extends ApiTestSupport {

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    final String today = LocalDate.now().toString();
    String product;
    SalesLedgerAssertions ledger;

    @BeforeAll
    void fixtures() {
        product = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                + " \"costPrice\", \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, ?, 'service'::\"ProductType\", 1000,"
                + " 600, false, now())").params(product, demoOrg, "AG-" + sfx, "Aggregate 20mm " + sfx).update();
        ledger = new SalesLedgerAssertions(jdbc, demoOrg);
    }

    String customer() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"isCompany\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, true, true, now())").params(id, demoOrg, "AC-" + UUID.randomUUID()
                        .toString().substring(0, 8), "Contractor " + sfx).update();
        return id;
    }

    Map<String, String> money() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    String postedInvoice(String partner, int qty, String issueDate) {
        String inv = ok(200, "POST", "/invoices", Map.of("partnerId", partner, "issueDate", issueDate, "lines", List.of(
                Map.of("productId", product, "quantity", qty, "unitPrice", 1000)))).get("id").asString();
        ok(200, "POST", "/invoices/" + inv + "/post", null);
        return inv;
    }

    String postedCreditNote(String partner, String invoice, int qty, String issueDate) {
        JsonNode cn = call("POST", "/credit-notes", Map.of("partnerId", partner, "issueDate", issueDate,
                "reversedDocumentId", invoice, "lines", List.of(Map.of("productId", product, "quantity", qty, "unitPrice",
                        1000))), money()).body();
        assertThat(call("POST", "/credit-notes/" + cn.get("id").asString() + "/post", null, money()).status())
                .isEqualTo(200);
        return cn.get("id").asString();
    }

    @Test
    @DisplayName("agreement: price tier 0, released balance, over-release approval, wrong customer, cancel / close")
    void agreements() {
        String c = customer();
        JsonNode a = ok(201, "POST", "/sales/agreements", Map.of("partnerId", c, "name", "Road project " + sfx, "kind",
                "contract", "startDate", LocalDate.now().minusDays(1).toString(), "endDate", LocalDate.now().plusMonths(6)
                        .toString(), "lines", List.of(Map.of("productId", product, "contractQty", 100, "unitPrice", 800))));
        String id = a.get("id").asString();
        assertThat(a.get("number").asString()).startsWith("AGR-");
        assertThat(a.get("status").asString()).isEqualTo("draft");
        assertThat(as("POST", "/sales/orders", Map.of("partnerId", c, "agreementId", id, "lines", List.of(Map.of(
                "productId", product, "quantity", 1)))).status()).isEqualTo(400);
        ok(200, "POST", "/sales/agreements/" + id + "/activate", null);

        JsonNode so = ok(201, "POST", "/sales/orders", Map.of("partnerId", c, "agreementId", id, "lines", List.of(Map.of(
                "productId", product, "quantity", 60))));
        JsonNode line = so.get("lines").get(0);
        assertThat(line.get("unitPrice").asDouble()).isEqualTo(800);
        assertThat(line.get("priceSource").asString()).isEqualTo("agreement");
        String soId = so.get("id").asString();
        assertThat(ok(200, "POST", "/sales/orders/" + soId + "/confirm", null).get("status").asString())
                .isEqualTo("confirmed");
        JsonNode got = ok(200, "GET", "/sales/agreements/" + id, null);
        assertThat(got.get("lines").get(0).get("releasedQty").asDouble()).isEqualTo(60);
        assertThat(got.get("lines").get(0).get("balanceQty").asDouble()).isEqualTo(40);
        assertThat(got.get("releases").size()).isEqualTo(1);

        String over = ok(201, "POST", "/sales/orders", Map.of("partnerId", c, "agreementId", id, "lines", List.of(Map.of(
                "productId", product, "quantity", 50)))).get("id").asString();
        JsonNode pending = ok(200, "POST", "/sales/orders/" + over + "/confirm", null);
        assertThat(pending.get("status").asString()).isEqualTo("pending_approval");
        assertThat(pending.get("approvalReasons").toString()).contains("exceeds the agreement balance of 40");

        assertThat(as("POST", "/sales/orders", Map.of("partnerId", customer(), "agreementId", id, "lines", List.of(
                Map.of("productId", product, "quantity", 1)))).status()).isEqualTo(400);
        assertThat(as("PUT", "/sales/agreements/" + id, Map.of("lines", List.of())).status()).isEqualTo(400);
        assertThat(ok(200, "PUT", "/sales/agreements/" + id, Map.of("allowOverRelease", true)).get("allowOverRelease")
                .asBoolean()).isTrue();
        assertThat(as("POST", "/sales/agreements/" + id + "/cancel", null).status()).isEqualTo(400);
        assertThat(ok(200, "POST", "/sales/agreements/" + id + "/close", null).get("status").asString())
                .isEqualTo("closed");
        assertThat(ok(200, "GET", "/sales/agreements?partnerId=" + c, null).size()).isEqualTo(1);
    }

    @Test
    @DisplayName("rebates: tiered accrual posts the delta, settlement credit note clears the accrual")
    void rebates() {
        String c = customer();
        LocalDate lastMonth = LocalDate.now().minusMonths(1).withDayOfMonth(5);
        ok(201, "POST", "/sales/rebates/programs", Map.of("name", "Volume " + sfx, "partnerId", c, "productId", product,
                "periodType", "month", "basis", "quantity", "startDate", lastMonth.withDayOfMonth(1).toString(), "tiers",
                List.of(Map.of("from", 200, "rate", 80), Map.of("from", 100, "rate", 50))));
        assertThat(as("POST", "/sales/rebates/programs", Map.of("name", "bad", "startDate", today, "tiers", List.of()))
                .status()).isEqualTo(400);
        String inv = postedInvoice(c, 120, lastMonth.toString());
        JsonNode run = ok(200, "POST", "/sales/rebates/accrue", Map.of("asOf", lastMonth.toString()));
        JsonNode acc = null;
        for (JsonNode x : run.get("accruals")) {
            if (c.equals(x.get("partnerId").asString())) {
                acc = x;
            }
        }
        assertThat(acc).isNotNull();
        assertThat(acc.get("amount").asDouble()).isEqualTo(6000);
        String accId = acc.get("id").asString();
        assertThat(ledger.balance("rebate_accrual", c)).isEqualByComparingTo(new BigDecimal("-6000"));
        assertThat(ledger.balance("sales_rebates", c)).isEqualByComparingTo(new BigDecimal("6000"));

        // a second run without new sales posts nothing; a return dropping below the tier reverses it
        ok(200, "POST", "/sales/rebates/accrue", Map.of("asOf", lastMonth.toString()));
        assertThat(ledger.entries("rebate_accrual", accId)).hasSize(1);
        postedCreditNote(c, inv, 30, lastMonth.toString());
        ok(200, "POST", "/sales/rebates/accrue", Map.of("asOf", lastMonth.toString()));
        assertThat(ledger.balance("rebate_accrual", c)).isEqualByComparingTo(BigDecimal.ZERO);
        postedInvoice(c, 140, lastMonth.toString());
        ok(200, "POST", "/sales/rebates/accrue", Map.of("asOf", lastMonth.toString()));
        // 120 - 30 + 140 = 230 units ≥ 200 → 80 per unit
        assertThat(ledger.balance("rebate_accrual", c)).isEqualByComparingTo(new BigDecimal("-18400"));
        ledger.entriesBalanced("rebate_accrual", accId);

        JsonNode settled = call("POST", "/sales/rebates/accruals/" + accId + "/settle", null, money()).body();
        assertThat(settled.get("status").asString()).isEqualTo("settled");
        String cn = settled.get("creditNoteId").asString();
        assertThat(ok(200, "GET", "/credit-notes/" + cn, null).get("totalAmount").asDouble()).isEqualTo(18400);
        assertThat(ledger.balance("rebate_accrual", c)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(ledger.balance("sales_rebates", c)).isEqualByComparingTo(new BigDecimal("18400"));
        assertThat(call("POST", "/sales/rebates/accruals/" + accId + "/settle", null, money()).status()).isEqualTo(400);
        // settled accruals are final
        postedInvoice(c, 10, lastMonth.toString());
        ok(200, "POST", "/sales/rebates/accrue", Map.of("asOf", lastMonth.toString()));
        assertThat(ledger.balance("rebate_accrual", c)).isEqualByComparingTo(BigDecimal.ZERO);
        ledger.receivableTiesOut(c);
        ledger.trialBalanceZero();
    }

    @Test
    @DisplayName("commissions: rule by salesperson, clawback by credit note, approval into payroll; targets")
    void commissionsAndTargets() {
        String rep = createUser(demoOrg, "rep-" + sfx.toLowerCase() + "@demo.test", "Rep@12345", null);
        String employee = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"HrEmployee\" (id, \"organizationId\", \"employeeCode\", \"userId\", \"firstName\","
                + " \"lastName\", \"updatedAt\") VALUES (?, ?, ?, ?, 'Sam', 'Seller', now())").params(employee, demoOrg,
                        "EMP-" + sfx, rep).update();
        String c = customer();
        jdbc.sql("UPDATE \"Partner\" SET \"salespersonId\" = ? WHERE id = ?").params(rep, c).update();
        ok(201, "POST", "/sales/commissions/rules", Map.of("name", "Rep 5% " + sfx, "salespersonId", rep, "method",
                "percent_revenue", "rate", 5, "priority", 10));
        assertThat(as("POST", "/sales/commissions/rules", Map.of("name", "x", "rate", 1, "method", "bogus")).status())
                .isEqualTo(400);
        String inv = postedInvoice(c, 10, today);
        postedCreditNote(c, inv, 2, today);
        String month = today.substring(0, 7);
        JsonNode preview = ok(200, "GET", "/sales/commissions/preview?month=" + month + "&salespersonId=" + rep, null);
        assertThat(preview.get("salespeople").get(0).get("total").asDouble()).isEqualTo(400);
        JsonNode stmts = ok(200, "POST", "/sales/commissions/statements", Map.of("month", month, "salespersonId", rep));
        JsonNode st = stmts.get(0);
        assertThat(st.get("total").asDouble()).isEqualTo(400);
        assertThat(st.get("lines").size()).isEqualTo(2);
        String stId = st.get("id").asString();
        JsonNode approved = ok(200, "POST", "/sales/commissions/statements/" + stId + "/approve", null);
        assertThat(approved.get("status").asString()).isEqualTo("approved");
        BigDecimal earning = jdbc.sql("SELECT amount FROM \"HrPayrollInput\" WHERE id = ?").param(approved.get(
                "payrollInputId").asString()).query(BigDecimal.class).single();
        assertThat(earning).isEqualByComparingTo("400");
        assertThat(as("POST", "/sales/commissions/statements/" + stId + "/approve", null).status()).isEqualTo(400);
        // regenerating leaves the approved statement alone
        assertThat(ok(200, "POST", "/sales/commissions/statements", Map.of("month", month, "salespersonId", rep)).get(0)
                .get("status").asString()).isEqualTo("approved");

        ok(201, "POST", "/sales/targets", Map.of("month", month, "salespersonId", rep, "amount", 16000));
        assertThat(as("POST", "/sales/targets", Map.of("month", month, "amount", -1)).status()).isEqualTo(400);
        JsonNode row = null;
        for (JsonNode t : ok(200, "GET", "/sales/targets/achievement?month=" + month, null)) {
            if (rep.equals(t.path("salespersonId").asString(null))) {
                row = t;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("actualAmount").asDouble()).isEqualTo(8000);
        assertThat(row.get("achievementPercent").asDouble()).isEqualTo(50);
    }
}
