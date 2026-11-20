package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Landed-cost profitability corrections: revenue counts posted back-office sales invoices less credit notes as well as
 * POS receipts less POS refunds, in base currency, each document once; COGS includes landed cost that reached units
 * already sold (post-sale adjustment) exactly once; freight history shows goods value for non-value allocations. Both
 * reports are checked: {@code analysis/profitability} (LC-6) and {@code product-profitability} (ADR-S007 §7).
 */
class LandedCostProfitabilityIT extends LandedCostFixtures {

    String today = LocalDate.now().toString();

    // ------------------------------------------------------------------ helpers

    JsonNode analysis(String productId) {
        JsonNode rows = ok(200, "GET", "/procurement/landed-cost-reports/analysis/profitability?from=" + today + "&to="
                + today, null).get("products");
        return find(rows, productId);
    }

    JsonNode classic(String productId) {
        return find(ok(200, "GET", "/procurement/landed-cost-reports/product-profitability?from=" + today + "&to="
                + today, null), productId);
    }

    static JsonNode find(JsonNode rows, String productId) {
        for (JsonNode r : rows) {
            if (productId.equals(r.get("productId").asString())) {
                return r;
            }
        }
        return null;
    }

    static BigDecimal num(JsonNode row, String field) {
        return new BigDecimal(row.get(field).asString());
    }

    String customer() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(id, demoOrg, "LCP-" + suffix(), "LCP cust " + suffix())
                .update();
        return id;
    }

    /** A draft back-office sales invoice; {@code currency} null = base currency. */
    String salesInvoice(String partner, String product, int qty, long price, Map<String, Object> currency) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerId", partner);
        body.put("issueDate", today);
        if (currency != null) {
            body.putAll(currency);
        }
        body.put("lines", List.of(Map.of("productId", product, "description", "Goods", "quantity", qty, "unitPrice",
                price)));
        Res r = call("POST", "/invoices", body, key());
        assertThat(r.status()).as(r.raw().body()).isIn(200, 201);
        return r.body().get("id").asString();
    }

    String postedInvoice(String partner, String product, int qty, long price, Map<String, Object> currency) {
        String id = salesInvoice(partner, product, qty, price, currency);
        Res r = call("POST", "/invoices/" + id + "/post", null, key());
        assertThat(r.status()).as(r.raw().body()).isIn(200, 201);
        return id;
    }

    String creditNote(String partner, String invoice, String product, int qty, long price, boolean post) {
        Res r = call("POST", "/credit-notes", Map.of("partnerId", partner, "issueDate", today, "reversedDocumentId",
                invoice, "lines", List.of(Map.of("productId", product, "description", "Return", "quantity", qty,
                        "unitPrice", price))), key());
        assertThat(r.status()).as(r.raw().body()).isIn(200, 201);
        String id = r.body().get("id").asString();
        if (post) {
            Res p = call("POST", "/credit-notes/" + id + "/post", null, key());
            assertThat(p.status()).as(p.raw().body()).isIn(200, 201);
        }
        return id;
    }

    /** A POS receipt (the {@code Invoice} ledger) with one line, written directly: status and rate are the subject. */
    String posReceipt(String partner, String product, int qty, long price, String status, BigDecimal rate) {
        String id = UUID.randomUUID().toString();
        BigDecimal sub = BigDecimal.valueOf(qty * price);
        jdbc.sql("INSERT INTO \"Invoice\" (id, \"organizationId\", \"invoiceNumber\", \"partnerId\", status,"
                + " \"issueDate\", \"exchangeRate\", subtotal, \"totalAmount\", \"amountResidual\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, ?::\"InvoiceStatus\", now(), ?, ?, ?, 0, now())").params(id, demoOrg,
                "LCP-" + suffix(), partner, status, rate, sub, sub).update();
        jdbc.sql("INSERT INTO \"InvoiceItem\" (id, \"organizationId\", \"invoiceId\", \"productId\", description, quantity,"
                + " \"unitPrice\", subtotal, total, \"lineNumber\") VALUES (?, ?, ?, ?, 'Goods', ?, ?, ?, ?, 1)").params(
                UUID.randomUUID().toString(), demoOrg, id, product, qty, price, sub, sub).update();
        return id;
    }

    void posRefund(String invoice, int qty, long subtotal) {
        String line = jdbc.sql("SELECT id FROM \"InvoiceItem\" WHERE \"invoiceId\" = ?").param(invoice)
                .query(String.class).single();
        jdbc.sql("INSERT INTO \"PosRefund\" (id, \"organizationId\", \"invoiceId\", amount, reason, \"stockDisposition\","
                + " items, payments, \"approvedById\") VALUES (?, ?, ?, ?, 'Return', 'restock', ?::jsonb, '[]'::jsonb, ?)")
                .params(UUID.randomUUID().toString(), demoOrg, invoice, subtotal, "[{\"lineId\":\"" + line
                        + "\",\"quantity\":\"" + qty + "\",\"subtotal\":\"" + subtotal + "\",\"taxAmount\":\"0\"}]",
                        adminId).update();
    }

    Map<String, Object> foreignCurrency(int rate) {
        String base = jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(demoOrg)
                .query(String.class).single();
        String id = jdbc.sql("SELECT id FROM \"Currency\" WHERE code <> ? ORDER BY code LIMIT 1").param(base)
                .query(String.class).single();
        return Map.of("currencyId", id, "exchangeRate", rate);
    }

    // ------------------------------------------------------------------ acceptance

    @Test
    @DisplayName("cement: 1,000 bags at 12,000 sold at 16,000 on a sales invoice, 1,520,000 freight posted after the"
            + " sale → revenue 16,000,000, purchase 12,000,000, landed 1,520,000, COGS 13,520,000, GP 2,480,000; ties to GL")
    void cementAcceptance() {
        Map<String, String> acc = accounts();
        BigDecimal cogsBefore = balance(acc.get("cogs"));
        String cement = product("AVCO", false, 0);
        String grn = receive(cement, 1000, 12000);
        sell(cement, w1, 1000);
        String inv = postedInvoice(customer(), cement, 1000, 16000, null);
        approveAndPost(voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1520000))));

        JsonNode a = analysis(cement);
        assertThat(a).as("analysis row").isNotNull();
        assertThat(num(a, "revenue")).isEqualByComparingTo("16000000");
        assertThat(num(a, "purchase")).isEqualByComparingTo("12000000");
        assertThat(num(a, "landed")).isEqualByComparingTo("1520000");
        assertThat(num(a, "cogs")).isEqualByComparingTo("13520000");
        assertThat(num(a, "grossProfit")).isEqualByComparingTo("2480000");
        assertThat(num(a, "grossProfitBeforeLanded")).isEqualByComparingTo("4000000");

        JsonNode c = classic(cement);
        assertThat(c).as("product-profitability row").isNotNull();
        assertThat(num(c, "revenue")).isEqualByComparingTo("16000000");
        assertThat(num(c, "cogs")).isEqualByComparingTo("13520000");
        assertThat(num(c, "landedCostInCogs")).isEqualByComparingTo("1520000");
        assertThat(num(c, "grossProfit")).isEqualByComparingTo("2480000");
        assertThat(num(c, "quantity")).isEqualByComparingTo("1000");

        // the books: revenue of the invoice's own entry, and what the scenario moved through the COGS account
        BigDecimal glRevenue = jdbc.sql("SELECT coalesce(sum(jl.\"baseCredit\" - jl.\"baseDebit\"), 0) FROM"
                + " \"JournalLine\" jl JOIN \"Account\" ac ON ac.id = jl.\"accountId\" JOIN \"AccountCategory\" cat ON"
                + " cat.id = ac.\"categoryId\" WHERE jl.\"journalEntryId\" = (SELECT \"journalEntryId\" FROM \"Document\""
                + " WHERE id = ?) AND cat.\"reportSection\"::text IN ('revenue', 'contra_revenue')").param(inv)
                .query(BigDecimal.class).single();
        assertThat(glRevenue).as("GL revenue").isEqualByComparingTo(num(a, "revenue"));
        assertThat(balance(acc.get("cogs")).subtract(cogsBefore)).as("GL COGS movement")
                .isEqualByComparingTo(num(a, "cogs"));
    }

    // ------------------------------------------------------------------ revenue

    @Test
    @DisplayName("revenue: a posted sales invoice counts in both reports; a draft and a cancelled one do not")
    void salesInvoiceRevenueAndStatuses() {
        String p = product("AVCO", false, 0);
        String cust = customer();
        postedInvoice(cust, p, 10, 100, null);
        salesInvoice(cust, p, 5, 100, null); // draft
        String cancelled = postedInvoice(cust, p, 7, 100, null);
        Res cancel = call("POST", "/invoices/" + cancelled + "/cancel", Map.of("reason", "Wrong customer"), key());
        assertThat(cancel.status()).as(cancel.raw().body()).isIn(200, 201);

        assertThat(num(analysis(p), "revenue")).isEqualByComparingTo("1000");
        JsonNode c = classic(p);
        assertThat(num(c, "revenue")).isEqualByComparingTo("1000");
        assertThat(num(c, "quantity")).isEqualByComparingTo("10");
        assertThat(num(c, "cogs")).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("credit notes: invoice 10 × 100, posted credit note 3 × 100 → revenue 700, qty 7; a draft note ignored")
    void creditNotes() {
        String p = product("AVCO", false, 0);
        String cust = customer();
        String inv = postedInvoice(cust, p, 10, 100, null);
        creditNote(cust, inv, p, 3, 100, true);
        creditNote(cust, inv, p, 2, 100, false);

        assertThat(num(analysis(p), "revenue")).isEqualByComparingTo("700");
        JsonNode c = classic(p);
        assertThat(num(c, "revenue")).isEqualByComparingTo("700");
        assertThat(num(c, "quantity")).isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("POS + invoice: POS 5 × 100 and invoice 10 × 100 → 1500, each once; a draft / cancelled receipt, and an"
            + " invoice raised from a POS receipt, add nothing; a POS refund of 2 → 1300")
    void posAndInvoiceCountedOnce() {
        String p = product("AVCO", false, 0);
        String cust = customer();
        String pos = posReceipt(cust, p, 5, 100, "paid", BigDecimal.ONE);
        posReceipt(cust, p, 4, 100, "draft", BigDecimal.ONE);
        posReceipt(cust, p, 4, 100, "cancelled", BigDecimal.ONE);
        postedInvoice(cust, p, 10, 100, null);
        // the same POS sale re-issued as a back-office invoice must not count twice
        String copy = postedInvoice(cust, p, 5, 100, null);
        jdbc.sql("UPDATE \"Document\" SET \"sourceType\" = 'pos_invoice', \"sourceId\" = ? WHERE id = ?").params(pos,
                copy).update();

        assertThat(num(analysis(p), "revenue")).isEqualByComparingTo("1500");
        assertThat(num(classic(p), "revenue")).isEqualByComparingTo("1500");
        assertThat(num(classic(p), "quantity")).isEqualByComparingTo("15");

        posRefund(pos, 2, 200);
        assertThat(num(analysis(p), "revenue")).isEqualByComparingTo("1300");
        assertThat(num(classic(p), "revenue")).isEqualByComparingTo("1300");
        assertThat(num(classic(p), "quantity")).isEqualByComparingTo("13");
    }

    @Test
    @DisplayName("foreign currency: invoice 10 × 100 at rate 4, its credit note 1 × 100, POS 5 × 100 at rate 2 → 4600 base")
    void foreignCurrencyInBase() {
        String p = product("AVCO", false, 0);
        String cust = customer();
        String inv = postedInvoice(cust, p, 10, 100, foreignCurrency(4));
        creditNote(cust, inv, p, 1, 100, true);
        posReceipt(cust, p, 5, 100, "posted", new BigDecimal(2));

        assertThat(num(analysis(p), "revenue")).isEqualByComparingTo("4600");
        assertThat(num(classic(p), "revenue")).isEqualByComparingTo("4600");
    }

    // ------------------------------------------------------------------ COGS

    @Test
    @DisplayName("late charge exactly once: 40 sold, 1000 posted (400 to COGS), 30 more sold carrying 300 → COGS 4200 ="
            + " purchase 3500 + landed 700")
    void lateChargeExactlyOnce() {
        Map<String, String> acc = accounts();
        BigDecimal cogsBefore = balance(acc.get("cogs"));
        String p = product("AVCO", false, 0);
        String grn = receive(p, 100, 50);
        sell(p, w1, 40);
        approveAndPost(voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000))));
        sell(p, w1, 30);

        JsonNode a = analysis(p);
        assertThat(num(a, "cogs")).isEqualByComparingTo("4200");
        assertThat(num(a, "landed")).isEqualByComparingTo("700");
        assertThat(num(a, "purchase")).isEqualByComparingTo("3500");
        assertThat(balance(acc.get("cogs")).subtract(cogsBefore)).as("GL COGS movement").isEqualByComparingTo("4200");
        JsonNode c = classic(p);
        assertThat(num(c, "cogs")).isEqualByComparingTo("4200");
        assertThat(num(c, "landedCostInCogs")).as("post-sale part only").isEqualByComparingTo("400");
    }

    @Test
    @DisplayName("late charge reversed the same day: posted +400 and reversed −400 → COGS 2000, landed 0; ties to GL")
    void lateChargeReversed() {
        Map<String, String> acc = accounts();
        BigDecimal cogsBefore = balance(acc.get("cogs"));
        String p = product("AVCO", false, 0);
        String grn = receive(p, 100, 50);
        sell(p, w1, 40);
        String v = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000)));
        approveAndPost(v);
        reverse(v);

        JsonNode a = analysis(p);
        assertThat(num(a, "cogs")).isEqualByComparingTo("2000");
        assertThat(num(a, "landed")).isEqualByComparingTo("0");
        assertThat(balance(acc.get("cogs")).subtract(cogsBefore)).as("GL COGS movement").isEqualByComparingTo("2000");
        JsonNode c = classic(p);
        assertThat(num(c, "cogs")).isEqualByComparingTo("2000");
        assertThat(num(c, "landedCostInCogs")).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------ freight history

    @Test
    @DisplayName("freight history: 250 allocated by quantity on a 100 × 50 receipt → goods value 5000, 5 % of goods,"
            + " 2.5 per unit")
    void freightHistoryQuantityAllocation() {
        String carrier = partner("LCP carrier");
        String name = jdbc.sql("SELECT name FROM \"Partner\" WHERE id = ?").param(carrier).query(String.class).single();
        String p = product("AVCO", false, 0);
        String grn = receive(p, 100, 50);
        approveAndPost(voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 250, "supplierId", carrier,
                "allocationMethod", "quantity", "billMode", "none"))));
        JsonNode row = null;
        for (JsonNode r : ok(200, "GET", "/procurement/landed-cost-reports/analysis/freight-history?from=" + today
                + "&to=" + today, null)) {
            if (name.equals(r.get("supplier").asString())) {
                row = r;
            }
        }
        assertThat(row).isNotNull();
        assertThat(num(row, "amount")).isEqualByComparingTo("250");
        assertThat(num(row, "quantity")).isEqualByComparingTo("100");
        assertThat(num(row, "perUnit")).isEqualByComparingTo("2.5");
        assertThat(row.get("goodsValue").isNull()).as("goods value populated").isFalse();
        assertThat(num(row, "goodsValue")).isEqualByComparingTo("5000");
        assertThat(num(row, "pctOfGoods")).isEqualByComparingTo("5");
    }
}
