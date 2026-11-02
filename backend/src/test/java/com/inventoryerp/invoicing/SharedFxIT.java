package com.inventoryerp.invoicing;

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
 * PC-0 (ADR-S054): shared currency handling and realised FX (owner decisions D-PC1 / D-PC2).
 *
 * <ul>
 *   <li>Rate precedence: an explicit document rate beats the rate table; the table is used when the document states
 *       none; with neither, the posting is refused and nothing is written. Every foreign-currency entry snapshots
 *       pair, rate, date and source.</li>
 *   <li>Realised FX: a foreign-currency settlement relieves each document at its carrying rate; the difference goes to
 *       the FX gain / loss accounts only — partial and full, supplier and customer, credit notes and voids.</li>
 * </ul>
 * Currencies are test-only codes so the global rate table cannot interfere with other classes.
 */
class SharedFxIT extends ApiTestSupport {

    static final String CUR = "QXA";
    static final String NO_RATE = "QXB";

    String base;
    String customer;
    String supplier;
    String service;
    String today;

    @BeforeAll
    void fixtures() {
        base = jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(demoOrg)
                .query(String.class).optional().orElse("USD");
        today = LocalDate.now().toString();
        for (String c : List.of(CUR, NO_RATE)) {
            ok(201, "POST", "/currencies", Map.of("code", c, "symbol", c, "name", "Test " + c));
        }
        rate(3800);
        customer = partner("FX customer", false);
        supplier = partner("FX supplier", true);
        service = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                        + " \"costPrice\", \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, 'FX advisory', 'service',"
                        + " 100, 0, false, now())")
                .params(service, demoOrg, "FX-" + UUID.randomUUID().toString().substring(0, 6)).update();
    }

    void rate(int value) {
        ok(201, "POST", "/currencies/rates", Map.of("fromCode", CUR, "toCode", base, "asOf",
                LocalDate.now().minusDays(1).toString(), "rate", value));
    }

    String partner(String name, boolean isSupplier) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"isCustomer\","
                        + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, now())")
                .params(id, demoOrg, "FX-" + UUID.randomUUID().toString().substring(0, 8), name + " "
                        + UUID.randomUUID().toString().substring(0, 4), isSupplier, !isSupplier).update();
        return id;
    }

    Map<String, String> key() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    String mapped(String key) {
        return jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key = ?"
                + " AND \"deletedAt\" IS NULL").params(demoOrg, key).query(String.class).single();
    }

    BigDecimal balance(String accountId) {
        return jdbc.sql("SELECT coalesce(sum(\"baseDebit\" - \"baseCredit\"), 0) FROM \"JournalLine\" WHERE"
                + " \"organizationId\" = ? AND \"accountId\" = ?").params(demoOrg, accountId).query(BigDecimal.class)
                .single();
    }

    /** Partner's net base balance on the receivable (customer) or payable (supplier) control account. */
    BigDecimal partnerBalance(String partnerId) {
        String control = mapped(partnerId.equals(customer) ? "accounts_receivable" : "accounts_payable");
        return jdbc.sql("SELECT coalesce(sum(\"baseDebit\" - \"baseCredit\"), 0) FROM \"JournalLine\" WHERE"
                + " \"organizationId\" = ? AND \"partnerId\" = ? AND \"accountId\" = ?")
                .params(demoOrg, partnerId, control).query(BigDecimal.class).single();
    }

    BigDecimal fxNet() {
        return balance(mapped("fx_gain")).add(balance(mapped("fx_loss")));
    }

    Map<String, Object> snapshot(String sourceId) {
        return jdbc.sql("SELECT r.\"fromCode\", r.\"toCode\", r.rate, r.source FROM \"JournalEntryFxRate\" r JOIN"
                + " \"JournalEntry\" e ON e.id = r.\"journalEntryId\" WHERE e.\"sourceId\" = ? ORDER BY r.\"createdAt\""
                + " LIMIT 1").param(sourceId).query().singleRow();
    }

    String invoice(Object exchangeRate, int price) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerId", customer);
        body.put("issueDate", today);
        body.put("currencyId", CUR);
        if (exchangeRate != null) {
            body.put("exchangeRate", exchangeRate);
        }
        body.put("lines", List.of(Map.of("productId", service, "quantity", 1, "unitPrice", price)));
        String id = ok(200, "POST", "/invoices", body).get("id").asString();
        ok(200, "POST", "/invoices/" + id + "/post", null);
        return id;
    }

    String bill(String currency, int price) {
        return ok(200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", today, "currencyId",
                currency, "lines", List.of(Map.of("description", "FX service", "quantity", 1, "unitPrice", price))))
                .get("id").asString();
    }

    Res pay(String path, String partner, String docId, int amount, int rate) {
        return call("POST", path, Map.of("partnerId", partner, "paymentDate", today, "amount", amount,
                "paymentMethod", "bank", "currencyCode", CUR, "exchangeRate", rate, "allocations",
                List.of(Map.of("documentId", docId, "amount", amount))), key());
    }

    BigDecimal residual(String docId) {
        return jdbc.sql("SELECT \"amountResidual\" FROM \"Document\" WHERE id = ?").param(docId).query(BigDecimal.class)
                .single();
    }

    // ------------------------------------------------------------------ D-PC1 rate precedence

    @Test
    @DisplayName("an explicit document rate (3,700) beats the table rate (3,800) and is snapshotted as 'document'")
    void explicitRateWins() {
        BigDecimal ar0 = partnerBalance(customer);
        String inv = invoice(3700, 100);
        assertThat(partnerBalance(customer).subtract(ar0)).isEqualByComparingTo("370000");
        Map<String, Object> s = snapshot(inv);
        assertThat(s.get("fromCode")).isEqualTo(CUR);
        assertThat(s.get("toCode")).isEqualTo(base);
        assertThat((BigDecimal) s.get("rate")).isEqualByComparingTo("3700");
        assertThat(s.get("source")).isEqualTo("document");
    }

    @Test
    @DisplayName("without a document rate the table rate is used and snapshotted as 'table'")
    void tableRateWhenNoneStated() {
        BigDecimal ap0 = partnerBalance(supplier);
        String b = bill(CUR, 100);
        ok(200, "POST", "/vendor-bills/" + b + "/post", Map.of());
        assertThat(partnerBalance(supplier).subtract(ap0)).isEqualByComparingTo("-380000");
        assertThat(snapshot(b).get("source")).isEqualTo("table");
    }

    @Test
    @DisplayName("no document rate and no table rate: posting refused, the bill stays draft, nothing is written")
    void refusedWithoutRate() {
        String b = bill(NO_RATE, 100);
        long entries0 = jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ?").param(demoOrg)
                .query(Long.class).single();
        Res r = call("POST", "/vendor-bills/" + b + "/post", Map.of(), adminToken);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).startsWith("No exchange rate for " + NO_RATE + "→" + base);
        assertThat(jdbc.sql("SELECT status::text FROM \"Document\" WHERE id = ?").param(b).query(String.class).single())
                .isEqualTo("draft");
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ?").param(demoOrg)
                .query(Long.class).single()).isEqualTo(entries0);
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"sourceId\" = ?").param(b).query(Long.class)
                .single()).isZero();
    }

    // ------------------------------------------------------------------ D-PC2 realised FX

    @Test
    @DisplayName("supplier: bill 100 @ 3,800; pay 60 @ 3,850 (loss 3,000) then 40 @ 3,700 (gain 4,000); void restores")
    void supplierPartialAndFullSettlement() {
        String b = bill(CUR, 100);
        ok(200, "POST", "/vendor-bills/" + b + "/post", Map.of());
        BigDecimal ap0 = partnerBalance(supplier);
        BigDecimal stock0 = balance(mapped("stock_valuation"));
        BigDecimal cogs0 = balance(mapped("cogs"));
        BigDecimal fx0 = fxNet();

        Res first = pay("/supplier-payments", supplier, b, 60, 3850);
        assertThat(first.status()).as(first.raw().body()).isIn(200, 201);
        assertThat(partnerBalance(supplier).subtract(ap0)).isEqualByComparingTo("228000");
        assertThat(fxNet().subtract(fx0)).as("loss 60 × 50").isEqualByComparingTo("3000");
        assertThat(residual(b)).isEqualByComparingTo("40");

        Res second = pay("/supplier-payments", supplier, b, 40, 3700);
        assertThat(second.status()).as(second.raw().body()).isIn(200, 201);
        assertThat(partnerBalance(supplier).subtract(ap0)).as("bill fully relieved at its carrying rate")
                .isEqualByComparingTo("380000");
        assertThat(fxNet().subtract(fx0)).as("loss 3,000 then gain 4,000").isEqualByComparingTo("-1000");
        assertThat(residual(b)).isEqualByComparingTo("0");
        assertThat(jdbc.sql("SELECT sum(\"fxAmount\") FROM \"PaymentAllocation\" WHERE \"documentId\" = ?").param(b)
                .query(BigDecimal.class).single()).isEqualByComparingTo("1000");

        Res voided = call("POST", "/payments/" + second.body().get("id").asString() + "/void", Map.of("reason",
                "wrong rate"), key());
        assertThat(voided.status()).as(voided.raw().body()).isIn(200, 201);
        assertThat(residual(b)).isEqualByComparingTo("40");
        assertThat(fxNet().subtract(fx0)).as("the void reverses its realised gain").isEqualByComparingTo("3000");
        assertThat(partnerBalance(supplier).subtract(ap0)).isEqualByComparingTo("228000");

        assertThat(balance(mapped("stock_valuation"))).as("FX never reaches stock").isEqualByComparingTo(stock0);
        assertThat(balance(mapped("cogs"))).as("FX never reaches COGS").isEqualByComparingTo(cogs0);
    }

    @Test
    @DisplayName("customer: invoice 100 @ 3,700 collected @ 3,750 → receivable cleared, 5,000 FX gain")
    void customerSettlement() {
        String inv = invoice(3700, 100);
        BigDecimal ar0 = partnerBalance(customer);
        BigDecimal fx0 = fxNet();
        Res r = pay("/payments", customer, inv, 100, 3750);
        assertThat(r.status()).as(r.raw().body()).isIn(200, 201);
        assertThat(partnerBalance(customer).subtract(ar0)).isEqualByComparingTo("-370000");
        assertThat(fxNet().subtract(fx0)).isEqualByComparingTo("-5000");
        assertThat(residual(inv)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("credit note at the table rate 3,800 on an invoice at 3,700: receivable relieved at 3,700, gain 4,000")
    void creditNoteSettlement() {
        String inv = invoice(3700, 100);
        BigDecimal ar0 = partnerBalance(customer);
        BigDecimal fx0 = fxNet();
        String cn = ok(200, "POST", "/credit-notes", Map.of("partnerId", customer, "issueDate", today, "currencyId",
                CUR, "reversedDocumentId", inv, "lines", List.of(Map.of("productId", service, "quantity", 1,
                        "unitPrice", 40)))).get("id").asString();
        ok(200, "POST", "/credit-notes/" + cn + "/post", null);
        assertThat(residual(inv)).isEqualByComparingTo("60");
        assertThat(partnerBalance(customer).subtract(ar0)).as("40 relieved at the invoice's 3,700")
                .isEqualByComparingTo("-148000");
        assertThat(fxNet().subtract(fx0)).isEqualByComparingTo("-4000");
    }

    @Test
    @DisplayName("a base-currency payment cannot settle a foreign-currency document")
    void currencyMismatchRefused() {
        String inv = invoice(3700, 100);
        Res r = call("POST", "/payments", Map.of("partnerId", customer, "paymentDate", today, "amount", 100,
                "paymentMethod", "bank", "allocations", List.of(Map.of("documentId", inv, "amount", 100))), key());
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).endsWith("is in " + CUR + "; settle it with a " + CUR + " payment");
        assertThat(residual(inv)).isEqualByComparingTo("100");
    }
}
