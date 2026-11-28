package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.kernel.security.Permissions;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * ADR-S094 C7 central sales reporting (§9): every billed sale carries its channel (a credit note takes the channel of
 * the invoice it credits), POS sales and refunds are in the report center's sales, a cash Sale is in the branch cash
 * reports, the reporting views agree with the report center, and the ledger reconciliation (R5a–R5g) finds no
 * difference on documents posted by the application — and does find one planted without a journal.
 */
class SalesCentralReportingIT extends CashSaleTestBase {

    static final String PIN = "4321";

    String buyer;
    String cashSale;
    String cashInvoice;
    String service;
    String orderInvoice;
    String orderCredit;
    String manualInvoice;
    String manualCredit;
    String posProduct;
    String posInvoice;

    String period() {
        // Documents carry the organization's business date; a day either side keeps the test independent of UTC.
        LocalDate today = LocalDate.now();
        return "from=" + today.minusDays(1) + "&to=" + today.plusDays(1);
    }

    JsonNode run(String key, String query) {
        return ok(200, "GET", "/sales/report-center/run/" + key + "?" + period() + (query.isEmpty() ? "" : "&" + query),
                null);
    }

    JsonNode money(String path, Object body) {
        Res r = call("POST", path, body, key(adminToken));
        assertThat(r.status()).as(path + " → " + r.raw().body()).isLessThan(300);
        return r.body();
    }

    String number(String documentId) {
        return jdbc.sql("SELECT \"documentNumber\" FROM \"Document\" WHERE id = ?").param(documentId).query(String.class)
                .single();
    }

    String posted(String path, Map<String, Object> body) {
        String id = ok(200, "POST", path, body).get("id").asString();
        ok(200, "POST", path + "/" + id + "/post", null);
        return id;
    }

    @BeforeAll
    void reportingFixtures() {
        freshVat();
        buyer = partner("Reporting buyer " + sfx, null);
        String today = LocalDate.now().toString();

        // A cash Sale: 3 × 100 + VAT, paid in cash, invoiced at the counter.
        cashSale = paidDraft(buyer, 3);
        confirmed(cashSale);
        cashInvoice = invoiceOf(cashSale);

        // An order-type Sale of a service billed on the ordered quantity: 4 × 500, then 1 × 500 credited.
        service = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                + " \"costPrice\", \"trackInventory\", \"invoicePolicy\", \"categoryId\", \"updatedAt\") VALUES (?, ?, ?, ?,"
                + " 'service'::\"ProductType\", 500, 0, false, 'ordered', ?, now())").params(service, demoOrg, "CR-"
                        + sfx, "Haulage " + sfx, category).update();
        String so = ok(201, "POST", "/sales/orders", Map.of("partnerId", buyer, "lines", List.of(Map.of("productId",
                service, "quantity", 4, "unitPrice", 500)))).get("id").asString();
        ok(200, "POST", "/sales/orders/" + so + "/confirm", null);
        orderInvoice = money("/sales/orders/" + so + "/invoices", Map.of("mode", "regular")).get("id").asString();
        ok(200, "POST", "/invoices/" + orderInvoice + "/post", null);
        orderCredit = posted("/credit-notes", Map.of("partnerId", buyer, "issueDate", today, "reversedDocumentId",
                orderInvoice, "lines", List.of(Map.of("productId", service, "quantity", 1, "unitPrice", 500))));

        // A manual invoice (POST /invoices) of 2 × 500 and a credit note of 1 × 500 against it.
        manualInvoice = posted("/invoices", Map.of("partnerId", buyer, "issueDate", today, "dueDate", today, "lines",
                List.of(Map.of("productId", service, "quantity", 2, "unitPrice", 500))));
        manualCredit = posted("/credit-notes", Map.of("partnerId", buyer, "issueDate", today, "reversedDocumentId",
                manualInvoice, "lines", List.of(Map.of("productId", service, "quantity", 1, "unitPrice", 500))));

        // A POS sale of 2 × 1000 and a refund of one.
        posProduct = ok(201, "POST", "/products", Map.of("code", "CR-POS-" + sfx, "name", "Snack " + sfx, "productType",
                "service", "salesPrice", 1000)).get("id").asString();
        String register = ok(201, "POST", "/cash-registers", Map.of("code", "CRREG-" + sfx, "name", "Report till " + sfx))
                .get("id").asString();
        String session = money("/cash-sessions/open", Map.of("cashRegisterId", register, "openingFloat", 0)).get("id")
                .asString();
        ok(201, "POST", "/pos/override/pin", Map.of("pin", PIN));
        posInvoice = money("/pos/checkout", Map.of("cashSessionId", session, "lines", List.of(Map.of("productId",
                posProduct, "description", "Snack", "quantity", 2, "unitPrice", 1000)), "tenders", List.of(Map.of(
                        "method", "cash", "amount", 2000)))).get("invoiceId").asString();
        String line = jdbc.sql("SELECT id FROM \"InvoiceItem\" WHERE \"invoiceId\" = ?").param(posInvoice)
                .query(String.class).single();
        money("/pos/invoices/" + posInvoice + "/refund", Map.of("reason", "Customer returned it", "overrideById",
                adminId, "overridePin", PIN, "stockDisposition", "no_return", "cashSessionId", session, "lines",
                List.of(Map.of("lineId", line, "quantity", 1))));
    }

    static JsonNode row(JsonNode rows, String field, String value) {
        for (JsonNode r : rows) {
            if (value.equals(r.path(field).asString(null))) {
                return r;
            }
        }
        return null;
    }

    static Map<String, String> byNumber(JsonNode rows, String field) {
        Map<String, String> out = new HashMap<>();
        for (JsonNode r : rows) {
            out.put(r.path("number").asString(""), r.path(field).asString(null));
        }
        return out;
    }

    @Test
    @DisplayName("channels: order, cash and manual invoices; credit notes follow their invoice; legacy aliases filter")
    void channels() {
        Map<String, String> invoices = byNumber(run("invoice-register", "partnerId=" + buyer).get("rows"), "channel");
        assertThat(invoices).containsEntry(number(cashInvoice), "cash").containsEntry(number(orderInvoice), "order")
                .containsEntry(number(manualInvoice), "manual");
        Map<String, String> credits = byNumber(run("credit-notes", "partnerId=" + buyer).get("rows"), "channel");
        assertThat(credits).containsEntry(number(orderCredit), "order").containsEntry(number(manualCredit), "manual");
        // The pre-C7 channel keys still filter (bookmarked report links).
        assertThat(byNumber(run("invoice-register", "partnerId=" + buyer + "&channel=sales_order").get("rows"), "channel"))
                .containsOnlyKeys(number(orderInvoice));
        assertThat(byNumber(run("invoice-register", "partnerId=" + buyer + "&channel=branch_cash").get("rows"), "channel"))
                .containsOnlyKeys(number(cashInvoice));

        JsonNode byChannel = run("sales-by-channel", "partnerId=" + buyer).get("rows");
        assertThat(row(byChannel, "label", "Cash sale").get("revenue").decimalValue()).isEqualByComparingTo("300");
        assertThat(row(byChannel, "label", "Sales order").get("revenue").decimalValue()).isEqualByComparingTo("1500");
        assertThat(row(byChannel, "label", "Manual invoice").get("revenue").decimalValue()).isEqualByComparingTo("500");
        assertThat(ok(200, "GET", "/sales/report-center/lookups", null).get("channels").size()).isEqualTo(12);
    }

    @Test
    @DisplayName("POS sales and refunds are in the report center's sales, as channel pos")
    void posInTheFacts() {
        JsonNode rows = run("sales-by-channel", "productId=" + posProduct).get("rows");
        assertThat(rows.size()).isEqualTo(1);
        JsonNode pos = rows.get(0);
        assertThat(pos.get("label").asString()).isEqualTo("Point of sale");
        assertThat(pos.get("revenue").decimalValue()).isEqualByComparingTo("1000");
        assertThat(pos.get("returns").decimalValue()).isEqualByComparingTo("1000");

        JsonNode tx = run("invoice-lines", "productId=" + posProduct).get("rows");
        assertThat(tx.size()).isEqualTo(2);
        for (JsonNode t : tx) {
            assertThat(t.get("_route").asString()).isEqualTo("/pos/receipts");
        }
    }

    @Test
    @DisplayName("a cash Sale is in the branch cash register and summary, opening the Sale")
    void cashSaleInBranchCashReports() {
        String number = sale(cashSale).get("number").asString();
        JsonNode reg = run("branch-cash-register", "branchId=" + branch).get("rows");
        JsonNode r = row(reg, "number", number);
        assertThat(r).as("register row of %s", number).isNotNull();
        assertThat(r.get("stage").asString()).isEqualTo("invoiced");
        assertThat(r.get("invoice").asString()).isEqualTo(number(cashInvoice));
        assertThat(r.get("total").decimalValue()).isEqualByComparingTo("354");
        assertThat(r.get("paid").decimalValue()).isEqualByComparingTo("354");
        assertThat(r.get("_route").asString()).isEqualTo("/sales/orders/" + cashSale);
        JsonNode summary = run("branch-cash-summary", "branchId=" + branch + "&partnerId=" + buyer);
        assertThat(summary.get("totals").get("total").decimalValue()).isEqualByComparingTo("354");
    }

    @Test
    @DisplayName("the sales register and sale lines views carry the report center's channel; POS rows included")
    void viewsAgree() throws Exception {
        LocalDate today = LocalDate.now();
        List<Map<String, String>> register = csv("sales-register", today.minusDays(1), today.plusDays(1));
        Map<String, String> channel = new HashMap<>();
        BigDecimal pos = BigDecimal.ZERO;
        for (Map<String, String> r : register) {
            channel.put(r.get("documentNumber") + "|" + r.get("documentType"), r.get("channel"));
            if ("pos".equals(r.get("source")) && posProduct.equals(r.get("productId"))) {
                pos = pos.add(new BigDecimal(r.get("functionalSubtotal")));
            }
        }
        assertThat(channel).containsEntry(number(cashInvoice) + "|sales_invoice", "cash")
                .containsEntry(number(orderInvoice) + "|sales_invoice", "order")
                .containsEntry(number(orderCredit) + "|credit_note", "order")
                .containsEntry(number(manualInvoice) + "|sales_invoice", "manual")
                .containsEntry(number(manualCredit) + "|credit_note", "manual");
        assertThat(pos).isEqualByComparingTo("1000");

        List<Map<String, String>> lines = csv("sales-orders", today.minusDays(1), today.plusDays(1));
        assertThat(lines.stream().filter(l -> cashSale.equals(l.get("saleId"))).map(l -> l.get("saleType")).toList())
                .containsExactly("cash");
    }

    @Test
    @DisplayName("ledger reconciliation: no difference on the application's documents; a planted one is found")
    void ledgerReconciliation() {
        JsonNode summary = run("ledger-reconciliation", "");
        List<String> checks = new ArrayList<>();
        summary.get("rows").forEach(r -> checks.add(r.get("check").asString()));
        assertThat(checks).containsExactly("R5A", "R5B", "R5C", "R5D", "R5E", "R5F", "R5G");
        List<String> mine = new ArrayList<>(List.of(number(cashInvoice), number(orderInvoice), number(orderCredit),
                number(manualInvoice), number(manualCredit), jdbc.sql("SELECT \"invoiceNumber\" FROM \"Invoice\" WHERE"
                        + " id = ?").param(posInvoice).query(String.class).single()));
        mine.addAll(jdbc.sql("SELECT p.\"paymentNumber\" FROM \"Payment\" p JOIN \"SalesOrderPayment\" t ON t.\"paymentId\""
                + " = p.id WHERE t.\"orderId\" = ?").param(cashSale).query(String.class).list());
        List<String> differing = new ArrayList<>();
        run("reconciliation-exceptions", "limit=100000").get("rows").forEach(r -> differing.add(r.path("number")
                .asString("")));
        assertThat(differing).doesNotContainAnyElementsOf(mine);
        JsonNode bridge = run("revenue-bridge", "");
        assertThat(row(bridge.get("rows"), "key", "difference").get("amount").decimalValue().abs())
                .isLessThanOrEqualTo(new BigDecimal("0.01"));

        // A posted invoice with no journal (inserted behind the application's back) is an R5a difference.
        String planted = UUID.randomUUID().toString();
        String plantedNumber = "PLANT-" + sfx;
        try {
            jdbc.sql("INSERT INTO \"Document\" (id, \"organizationId\", \"documentNumber\", \"partnerId\", \"issueDate\","
                    + " \"updatedAt\", \"documentTypeId\", \"documentType\", status, \"exchangeRate\", \"sourceKind\","
                    + " subtotal, \"taxAmount\", \"totalAmount\") SELECT ?, \"organizationId\", ?, \"partnerId\","
                    + " \"issueDate\", now(), \"documentTypeId\", \"documentType\", status, 1, 'manual', 123.45, 0, 123.45"
                    + " FROM \"Document\" WHERE id = ?").params(planted, plantedNumber, manualInvoice).update();
            jdbc.sql("INSERT INTO \"DocumentLine\" (id, \"organizationId\", \"documentId\", description, quantity,"
                    + " \"unitPrice\", subtotal, \"taxAmount\", total) VALUES (?, ?, ?, 'Planted', 1, 123.45, 123.45, 0,"
                    + " 123.45)").params(UUID.randomUUID().toString(), demoOrg, planted).update();
            JsonNode found = run("reconciliation-exceptions", "status=r5a&limit=100000").get("rows");
            JsonNode p = row(found, "number", plantedNumber);
            assertThat(p).as("planted invoice in the R5a differences").isNotNull();
            assertThat(p.get("report").decimalValue()).isEqualByComparingTo("123.45");
            assertThat(p.get("ledger").decimalValue()).isEqualByComparingTo("0");
            assertThat(p.get("_route").asString()).isEqualTo("/invoices/" + planted);
            JsonNode r5a = row(run("ledger-reconciliation", "").get("rows"), "check", "R5A");
            assertThat(r5a.get("status").asString()).isEqualTo("difference");
            assertThat(r5a.get("_drill").get("report").asString()).isEqualTo("reconciliation-exceptions");
        } finally {
            jdbc.sql("DELETE FROM \"DocumentLine\" WHERE \"documentId\" = ?").param(planted).update();
            jdbc.sql("DELETE FROM \"Document\" WHERE id = ?").param(planted).update();
        }
        assertThat(call("GET", "/sales/report-center/run/reconciliation-exceptions?status=r9", null, adminToken)
                .status()).isEqualTo(400);
    }

    @Test
    @DisplayName("a foreign invoice created without a rate is reported at the rate it posted at (carrying rate)")
    void foreignInvoiceAtItsCarryingRate() {
        String ccy = "XRC";
        String functional = jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(demoOrg)
                .query(String.class).single();
        jdbc.sql("INSERT INTO \"Currency\" (id, code, symbol, name, \"updatedAt\") VALUES (?, ?, ?, 'Report test currency',"
                + " now()) ON CONFLICT DO NOTHING").params(UUID.randomUUID().toString(), ccy, ccy).update();
        jdbc.sql("DELETE FROM \"CurrencyRate\" WHERE ? IN (\"fromCode\", \"toCode\")").param(ccy).update();
        jdbc.sql("INSERT INTO \"CurrencyRate\" (id, \"organizationId\", \"fromCode\", \"toCode\", \"asOf\", rate, source)"
                + " VALUES (?, ?, ?, ?, now() - interval '1 day', 3700, 'test')").params(UUID.randomUUID().toString(),
                        demoOrg, ccy, functional).update();
        String fxBuyer = partner("FX reporting buyer " + sfx, null);
        // No exchangeRate in the request: the document stores 1 ("not stated") and posts at the table's 3700.
        String fx = posted("/invoices", Map.of("partnerId", fxBuyer, "issueDate", LocalDate.now().toString(), "dueDate",
                LocalDate.now().toString(), "currencyId", ccy, "lines", List.of(Map.of("productId", service, "quantity",
                        2, "unitPrice", 10))));
        assertThat(jdbc.sql("SELECT \"exchangeRate\" FROM \"Document\" WHERE id = ?").param(fx).query(BigDecimal.class)
                .single()).isEqualByComparingTo("1");
        JsonNode inv = row(run("invoice-register", "partnerId=" + fxBuyer).get("rows"), "number", number(fx));
        assertThat(inv.get("subtotal").decimalValue()).isEqualByComparingTo("74000");
        assertThat(row(run("sales-by-channel", "partnerId=" + fxBuyer).get("rows"), "label", "Manual invoice")
                .get("revenue").decimalValue()).isEqualByComparingTo("74000");
        List<String> differing = new ArrayList<>();
        run("reconciliation-exceptions", "limit=100000").get("rows").forEach(r -> differing.add(r.path("number")
                .asString("")));
        assertThat(differing).doesNotContain(number(fx));
    }

    @Test
    @DisplayName("the reconciliation needs report:accounting and is hidden from other report readers")
    void accountingOnly() {
        String reader = user("rc-reader", null, Permissions.SALES__REPORT_READ);
        Res cat = call("GET", "/sales/report-center/catalog", null, reader);
        assertThat(cat.status()).isEqualTo(200);
        JsonNode recon = row(cat.body().get("categories"), "key", "reconciliation");
        assertThat(recon.get("reports").size()).isZero();
        assertThat(call("GET", "/sales/report-center/run/ledger-reconciliation?" + period(), null, reader).status())
                .isEqualTo(403);
        String accountant = user("rc-accountant", null, Permissions.SALES__REPORT_READ, Permissions.REPORT__ACCOUNTING);
        assertThat(call("GET", "/sales/report-center/run/ledger-reconciliation?" + period(), null, accountant).status())
                .isEqualTo(200);
    }

    // ------------------------------------------------------------------ BI export

    List<Map<String, String>> csv(String dataset, LocalDate from, LocalDate to) throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/api/v1/bi/datasets/" + dataset + "/export.csv?from=" + from + "&to=" + to)).header("Authorization",
                        "Bearer " + adminToken).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        List<List<String>> table = parse(res.body());
        List<String> head = table.getFirst();
        List<Map<String, String>> out = new ArrayList<>();
        for (List<String> r : table.subList(1, table.size())) {
            Map<String, String> m = new LinkedHashMap<>();
            for (int i = 0; i < head.size() && i < r.size(); i++) {
                m.put(head.get(i), r.get(i));
            }
            out.add(m);
        }
        return out;
    }

    /** RFC 4180 rows (quoted fields, doubled quotes, line breaks inside quotes); a leading BOM is skipped. */
    static List<List<String>> parse(String body) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        String s = body.startsWith("﻿") ? body.substring(1) : body;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < s.length() && s.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (!cell.isEmpty() || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }
}
