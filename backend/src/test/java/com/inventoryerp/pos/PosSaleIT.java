package com.inventoryerp.pos;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;

/**
 * POS sell loop over HTTP (port of {@code test/integration/pos-sale-pipeline.spec.ts} plus the money-route,
 * order-lifecycle, split, KDS, posting-monitor and report surfaces): counter checkout → Order → Invoice →
 * Receipt with balanced GL, idempotent replay, tableless orders, tabs, splits, reports and the Z close.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PosSaleIT extends ApiTestSupport {

    String productId;
    String customerId;
    String registerId;
    String sessionId;

    Res keyed(String method, String path, Object body, String key) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        if (key != null) {
            h.put("Idempotency-Key", key);
        }
        return call(method, path, body, h);
    }

    java.net.http.HttpResponse<String> raw(String path) {
        try {
            return http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/api/v1"
                    + path)).header("Authorization", "Bearer " + adminToken).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.ISO_8859_1));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    JsonNode money(int status, String method, String path, Object body) {
        Res r = keyed(method, path, body, UUID.randomUUID().toString());
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(status);
        return r.body();
    }

    @BeforeAll
    void fixtures() {
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        productId = ok(201, "POST", "/products", Map.of("code", "POS-SVC-" + suffix, "name", "Coffee " + suffix,
                "productType", "service", "salesPrice", 100, "costPrice", 0)).get("id").asString();
        customerId = ok(201, "POST", "/partners", Map.of("code", "POS-C-" + suffix, "name", "Pipeline Cust " + suffix,
                "isCustomer", true)).get("id").asString();
        registerId = ok(201, "POST", "/cash-registers", Map.of("code", "PREG-" + suffix, "name", "POS till " + suffix))
                .get("id").asString();
        JsonNode opened = money(200, "POST", "/cash-sessions/open", Map.of("cashRegisterId", registerId,
                "openingFloat", 0));
        sessionId = opened.get("id").asString();
    }

    Map<String, Object> line(int qty) {
        return Map.of("productId", productId, "description", "Coffee", "quantity", qty, "unitPrice", 100);
    }

    BigDecimal glImbalance(String sourceId) {
        return jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\") - sum(l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE e.\"sourceType\" = 'pos_invoice' AND e.\"sourceId\" = ?")
                .param(sourceId).query(BigDecimal.class).single();
    }

    @Test
    @Order(1)
    @DisplayName("checkout writes Order + Invoice + Receipt and posts balanced GL")
    void checkoutPipeline() {
        JsonNode result = money(200, "POST", "/pos/checkout", Map.of("partnerId", customerId, "cashSessionId", sessionId,
                "lines", List.of(line(2)), "tenders", List.of(Map.of("method", "cash", "amount", 200))));
        String invoiceId = result.get("invoiceId").asString();
        assertThat(result.get("invoiceNumber").asString()).startsWith("INV-");
        assertThat(result.get("orderNumber").asString()).startsWith("ORD-");
        assertThat(result.get("total").asInt()).isEqualTo(200);
        assertThat(result.get("settleMode").asString()).isEqualTo("tender");
        Map<String, Object> inv = jdbc.sql("SELECT \"totalAmount\", \"amountResidual\", status::text AS status,"
                + " \"settlementStatus\"::text AS ss FROM \"Invoice\" WHERE id = ?").param(invoiceId).query().singleRow();
        assertThat((BigDecimal) inv.get("totalAmount")).isEqualByComparingTo("200");
        assertThat((BigDecimal) inv.get("amountResidual")).isEqualByComparingTo("0");
        assertThat(inv.get("status")).isEqualTo("paid");
        assertThat(inv.get("ss")).isEqualTo("settled");
        assertThat(jdbc.sql("SELECT count(*) FROM \"Receipt\" WHERE \"invoiceId\" = ? AND type::text = 'payment_receipt'")
                .param(invoiceId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status::text FROM \"Order\" WHERE \"invoiceId\" = ?").param(invoiceId)
                .query(String.class).single()).isEqualTo("closed");
        assertThat(glImbalance(invoiceId)).isEqualByComparingTo("0");
        assertThat(jdbc.sql("SELECT count(*) FROM \"PaymentAllocation\" WHERE \"invoiceId\" = ?").param(invoiceId)
                .query(Long.class).single()).isPositive();

        // Receipt surfaces.
        var text = raw("/pos/receipts/" + invoiceId + "/text");
        assertThat(text.statusCode()).isEqualTo(200);
        assertThat(text.headers().firstValue("content-type").orElse("")).startsWith("text/plain");
        assertThat(text.body()).contains("TOTAL");
        var pdf = raw("/pos/receipts/" + invoiceId + "/pdf");
        assertThat(pdf.statusCode()).isEqualTo(200);
        assertThat(pdf.headers().firstValue("content-type").orElse("")).isEqualTo("application/pdf");
        assertThat(pdf.body()).startsWith("%PDF");
        JsonNode detail = ok(200, "GET", "/pos/receipts/" + invoiceId, null);
        assertThat(detail.toString()).contains(result.get("invoiceNumber").asString());
        JsonNode list = ok(200, "GET", "/pos/receipts?pageSize=500&search=" + result.get("invoiceNumber").asString(), null);
        assertThat(list.toString()).contains(invoiceId);
        // Checkout already printed the receipt (counter + RECEIPT print log); another plain print is refused.
        assertThat(jdbc.sql("SELECT \"receiptPrintCount\" FROM \"Invoice\" WHERE id = ?").param(invoiceId)
                .query(Integer.class).single()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM \"DocumentPrintLog\" WHERE \"invoiceId\" = ? AND type::text = 'RECEIPT'"
                + " AND \"documentId\" IS NULL").param(invoiceId).query(Long.class).single()).isPositive();
        Res again = as("POST", "/pos/receipts/" + invoiceId + "/print", null);
        assertThat(again.status()).isEqualTo(403);
        assertThat(again.body().get("message").asString())
                .isEqualTo("Receipt already printed. Use the reprint endpoint (Admin/Manager only, with a reason).");
        ok(201, "POST", "/pos/receipts/" + invoiceId + "/reprint", Map.of("reason", "Customer lost it"));
        assertThat(jdbc.sql("SELECT count(*) FROM \"DocumentPrintLog\" WHERE \"invoiceId\" = ? AND action::text = 'REPRINT'")
                .param(invoiceId).query(Long.class).single()).isEqualTo(1);
        Res email = as("POST", "/pos/receipts/" + invoiceId + "/email", null);
        assertThat(email.status()).isEqualTo(400);
        assertThat(email.body().get("message").asString()).isEqualTo("Customer has no email on file. Add one in Partners.");
    }

    @Test
    @Order(2)
    @DisplayName("cash over-tender returns change and persists amountTendered")
    void overTender() {
        JsonNode result = money(200, "POST", "/pos/checkout", Map.of("partnerId", customerId, "cashSessionId", sessionId,
                "lines", List.of(line(2)), "tenders", List.of(Map.of("method", "cash", "amount", 200)),
                "amountTendered", 500));
        assertThat(result.get("change").asInt()).isEqualTo(300);
        BigDecimal tendered = jdbc.sql("SELECT \"amountTendered\" FROM \"Invoice\" WHERE id = ?")
                .param(result.get("invoiceId").asString()).query(BigDecimal.class).single();
        assertThat(tendered).isEqualByComparingTo("500");
        assertThat(result.get("receiptText").asString()).contains("Cash tendered:").contains("Change:");
    }

    @Test
    @Order(3)
    @DisplayName("checkout is a money route: key required, replay returns the same sale")
    void idempotentCheckout() {
        Map<String, Object> body = Map.of("cashSessionId", sessionId, "lines", List.of(line(1)),
                "tenders", List.of(Map.of("method", "cash", "amount", 100)));
        Res noKey = keyed("POST", "/pos/checkout", body, null);
        assertThat(noKey.status()).isEqualTo(400);
        String key = UUID.randomUUID().toString();
        long before = jdbc.sql("SELECT count(*) FROM \"Invoice\" WHERE \"organizationId\" = ?").param(demoOrg)
                .query(Long.class).single();
        Res first = keyed("POST", "/pos/checkout", body, key);
        Res second = keyed("POST", "/pos/checkout", body, key);
        assertThat(first.status()).as(first.raw().body()).isEqualTo(200);
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.body().get("invoiceId").asString()).isEqualTo(first.body().get("invoiceId").asString());
        assertThat(jdbc.sql("SELECT count(*) FROM \"Invoice\" WHERE \"organizationId\" = ?").param(demoOrg)
                .query(Long.class).single()).isEqualTo(before + 1);
    }

    @Test
    @Order(4)
    @DisplayName("tableless order lists open, resumes, settles with GL parity, then leaves the feed")
    void multiOrder() {
        JsonNode created = ok(201, "POST", "/pos/orders", Map.of("orderType", "takeaway", "guestCount", 1,
                "cashSessionId", sessionId, "lines", List.of(line(2))));
        String id = created.get("id").asString();
        assertThat(created.get("tableId").isNull()).isTrue();
        JsonNode open = ok(200, "GET", "/pos/orders/open", null);
        JsonNode row = null;
        for (JsonNode r : open.get("rows")) {
            if (r.get("id").asString().equals(id)) {
                row = r;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("orderType").asString()).isEqualTo("takeaway");
        assertThat(row.get("tableName").isNull()).isTrue();
        assertThat(open.get("count").asInt()).isEqualTo(open.get("rows").size());
        JsonNode resumed = ok(200, "GET", "/pos/orders/" + id + "/resume", null);
        assertThat(resumed.get("lines").size()).isEqualTo(1);
        assertThat(resumed.get("orderType").asString()).isEqualTo("takeaway");
        ok(200, "GET", "/pos/orders/" + id, null);
        ok(200, "GET", "/pos/orders/" + id + "/milestones", null);
        JsonNode settled = money(200, "POST", "/pos/orders/" + id + "/settle", Map.of("cashSessionId", sessionId,
                "tenders", List.of(Map.of("method", "cash", "amount", 200))));
        String invoiceId = settled.get("invoiceId").asString();
        assertThat(glImbalance(invoiceId)).isEqualByComparingTo("0");
        assertThat(jdbc.sql("SELECT status::text FROM \"Order\" WHERE id = ?").param(id).query(String.class).single())
                .isEqualTo("closed");
        assertThat(ok(200, "GET", "/pos/orders/open", null).toString()).doesNotContain(id);
    }

    @Test
    @Order(5)
    @DisplayName("draft line and order discounts survive save and resume; version guards the cart")
    void discountsThroughSave() {
        Map<String, Object> l = new LinkedHashMap<>(line(2));
        l.put("discountType", "fixed_amount");
        l.put("discountAmount", 10);
        l.put("discountReason", "Line promotion");
        l.put("course", 2);
        JsonNode created = ok(201, "POST", "/pos/orders", Map.of("orderType", "takeaway", "cashSessionId", sessionId,
                "guestCount", 1, "lines", List.of(l), "transactionDiscountType", "fixed_amount", "transactionDiscountAmount", 5,
                "discountReason", "Order promotion"));
        String id = created.get("id").asString();
        assertThat(new BigDecimal(created.get("totalAmount").asString())).isEqualByComparingTo("185");
        JsonNode resumed = ok(200, "GET", "/pos/orders/" + id + "/resume", null);
        assertThat(resumed.get("transactionDiscountType").asString()).isEqualTo("fixed_amount");
        assertThat(resumed.get("transactionDiscountAmount").asInt()).isEqualTo(5);
        assertThat(resumed.get("lines").get(0).get("discountAmount").asString()).isEqualTo("10");
        JsonNode saved = ok(200, "PUT", "/pos/orders/" + id + "/items", Map.of("expectedVersion",
                resumed.get("version").asInt(), "lines", List.of(l), "transactionDiscountType", "percentage",
                "transactionDiscountPercent", 5, "transactionDiscountAmount", 0, "discountReason", "Revised promotion"));
        assertThat(new BigDecimal(saved.get("totalAmount").asString())).isEqualByComparingTo("180.5");
        Res stale = as("PUT", "/pos/orders/" + id + "/items", Map.of("expectedVersion", resumed.get("version").asInt(),
                "lines", List.of(l)));
        assertThat(stale.status()).isEqualTo(409);
        JsonNode empty = ok(200, "PUT", "/pos/orders/" + id + "/items", Map.of("expectedVersion",
                saved.get("version").asInt(), "lines", List.of()));
        assertThat(new BigDecimal(empty.get("totalAmount").asString())).isEqualByComparingTo("0");
        money(200, "POST", "/pos/orders/" + id + "/cancel", Map.of("reason", "test cleanup"));
    }

    @Test
    @Order(6)
    @DisplayName("tab on a table: save, stale save rejected, fire kitchen, settle; split bill settles atomically")
    void tabsAndSplit() {
        JsonNode table = ok(201, "POST", "/pos/tables", Map.of("name", "Tab test " + UUID.randomUUID(), "number",
                (int) (Math.random() * 100000) + 1000));
        String tableId = table.get("id").asString();
        Map<String, Object> input = new LinkedHashMap<>(Map.of("cashSessionId", sessionId, "partnerId", customerId,
                "guestCount", 1, "lines", List.of(line(1))));
        input.put("newRound", true);
        JsonNode saved = ok(201, "POST", "/pos/tabs/" + tableId + "/save", input);
        assertThat(saved.get("cashSessionId").asString()).isEqualTo(sessionId);
        assertThat(saved.get("customer").get("id").asString()).isEqualTo(customerId);
        Map<String, Object> next = new LinkedHashMap<>(input);
        next.remove("newRound");
        next.put("expectedVersion", saved.get("version").asInt());
        next.put("lines", List.of(line(2)));
        ok(201, "POST", "/pos/tabs/" + tableId + "/save", next);
        Map<String, Object> staleEmpty = new LinkedHashMap<>(input);
        staleEmpty.remove("newRound");
        staleEmpty.put("expectedVersion", saved.get("version").asInt());
        staleEmpty.put("lines", List.of());
        Res stale = as("POST", "/pos/tabs/" + tableId + "/save", staleEmpty);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.raw().body()).contains("modified by someone else");
        JsonNode tab = ok(200, "GET", "/pos/tabs/" + tableId, null);
        assertThat(tab.toString()).contains(saved.get("id").asString());
        assertThat(ok(200, "GET", "/pos/tables/" + tableId, null).get("status").asString()).isEqualTo("occupied");
        ok(201, "POST", "/pos/tabs/" + tableId + "/fire-kitchen", null);
        JsonNode tickets = ok(200, "GET", "/pos/kds/tickets", null);
        assertThat(tickets.isArray()).isTrue();

        JsonNode rest = money(200, "POST", "/pos/tabs/" + tableId + "/settle", Map.of("cashSessionId", sessionId,
                "tenders", List.of(Map.of("method", "cash", "amount", 200))));
        assertThat(rest.get("invoiceId").asString()).isNotBlank();

        // Split on a second table: one bill takes the whole line; a short tender writes no invoice.
        JsonNode t2 = ok(201, "POST", "/pos/tables", Map.of("name", "Split test " + UUID.randomUUID(), "number",
                (int) (Math.random() * 100000) + 400000));
        String table2 = t2.get("id").asString();
        Map<String, Object> first = new LinkedHashMap<>(input);
        first.put("lines", List.of(line(2)));
        String orderId = ok(201, "POST", "/pos/tabs/" + table2 + "/save", first).get("id").asString();
        String itemId = jdbc.sql("SELECT id FROM \"OrderItem\" WHERE \"orderId\" = ? AND cancelled = false LIMIT 1")
                .param(orderId).query(String.class).single();
        JsonNode state = ok(201, "POST", "/pos/tabs/" + table2 + "/split/bills", Map.of("count", 1));
        String billId = state.get("bills").get(0).get("id").asString();
        state = ok(201, "POST", "/pos/split-bills/" + billId + "/assign", Map.of("items",
                List.of(Map.of("sourceItemId", itemId, "quantity", 2))));
        assertThat(state.get("bills").get(0).get("totalAmount").asInt()).isEqualTo(200);
        long before = jdbc.sql("SELECT count(*) FROM \"Invoice\" WHERE \"organizationId\" = ?").param(demoOrg)
                .query(Long.class).single();
        Res shortTender = keyed("POST", "/pos/split-bills/" + billId + "/settle", Map.of("cashSessionId", sessionId,
                "expectedTotal", 200, "tenders", List.of(Map.of("method", "cash", "amount", 190))),
                UUID.randomUUID().toString());
        assertThat(shortTender.status()).isEqualTo(400);
        assertThat(shortTender.raw().body()).contains("does not match");
        assertThat(jdbc.sql("SELECT count(*) FROM \"Invoice\" WHERE \"organizationId\" = ?").param(demoOrg)
                .query(Long.class).single()).isEqualTo(before);
        String key = UUID.randomUUID().toString();
        Map<String, Object> pay = Map.of("cashSessionId", sessionId, "expectedTotal", 200, "tenders",
                List.of(Map.of("method", "cash", "amount", 200)));
        Res paid = keyed("POST", "/pos/split-bills/" + billId + "/settle", pay, key);
        assertThat(paid.status()).as(paid.raw().body()).isEqualTo(200);
        Res again = keyed("POST", "/pos/split-bills/" + billId + "/settle", pay, UUID.randomUUID().toString());
        assertThat(again.body().get("invoiceId").asString()).isEqualTo(paid.body().get("invoiceId").asString());
        assertThat(jdbc.sql("SELECT count(*) FROM \"Invoice\" WHERE \"organizationId\" = ?").param(demoOrg)
                .query(Long.class).single()).isEqualTo(before + 1);
        ok(200, "GET", "/pos/tabs/" + table2 + "/split", null);
    }

    @Test
    @Order(7)
    @DisplayName("KDS ticket lifecycle via send-to-kitchen and transitions")
    void kds() {
        JsonNode ids = ok(201, "POST", "/pos/kds/send-to-kitchen", Map.of("label", "Counter",
                "items", List.of(Map.of("productId", productId, "productName", "Coffee", "quantity", 1))));
        assertThat(ids.isArray()).isTrue();
        if (ids.isEmpty()) {
            return;
        }
        String id = ids.get(0).asString();
        assertThat(ok(200, "GET", "/pos/kds/tickets/" + id, null).get("status").asString()).isEqualTo("new");
        ok(201, "POST", "/pos/kds/tickets/" + id + "/priority", Map.of("priority", "rush"));
        assertThat(ok(201, "POST", "/pos/kds/tickets/" + id + "/transition", Map.of("action", "start"))
                .get("status").asString()).isEqualTo("preparing");
        Res bad = as("POST", "/pos/kds/tickets/" + id + "/transition", Map.of("action", "fly"));
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.raw().body()).contains("action must be one of the following values: start, ready, serve, cancel, recall");
        ok(201, "POST", "/pos/kds/tickets/bulk-transition", Map.of("ids", List.of(id), "action", "ready"));
        ok(200, "GET", "/pos/kds/reports/live", null);
        ok(200, "GET", "/pos/kds/reports/summary", null);
    }

    @Test
    @Order(8)
    @DisplayName("posting monitor, holds, loyalty, statements")
    void backOffice() {
        JsonNode counts = ok(200, "GET", "/pos/posting-monitor/counts", null);
        assertThat(counts.get("health").asString()).isIn("ok", "attention", "degraded");
        ok(200, "GET", "/pos/posting-monitor/jobs", null);
        ok(200, "GET", "/pos/posting-monitor/exceptions?status=all", null);
        Res retry = as("POST", "/pos/posting-monitor/jobs/" + UUID.randomUUID() + "/retry", null);
        assertThat(retry.status()).isEqualTo(404);
        assertThat(retry.body().get("message").asString()).isEqualTo("Stock posting job not found");
        Res resolve = as("POST", "/pos/posting-monitor/exceptions/" + UUID.randomUUID() + "/resolve",
                Map.of("status", "resolved", "note", " "));
        assertThat(resolve.body().get("message").asString())
                .isEqualTo("A note is required — record how the drift was corrected");

        JsonNode hold = ok(201, "POST", "/pos/holds", Map.of("name", "Table 4 hold", "lines",
                List.of(Map.of("productId", productId, "description", "Coffee", "quantity", 1, "unitPrice", 100))));
        String holdId = hold.get("id").asString();
        ok(200, "PATCH", "/pos/holds/" + holdId + "/notes", Map.of("notes", "VIP"));
        assertThat(ok(200, "GET", "/pos/holds/" + holdId, null).get("notes").asString()).isEqualTo("VIP");
        ok(200, "GET", "/pos/holds?status=open", null);
        ok(201, "POST", "/pos/holds/" + holdId + "/recall", null);

        ok(201, "POST", "/pos/loyalty/program/ensure", null);
        ok(200, "GET", "/pos/loyalty/program", null);
        ok(200, "GET", "/pos/loyalty/balance/" + customerId, null);
        ok(200, "GET", "/pos/loyalty/credit/" + customerId, null);
        ok(200, "GET", "/pos/loyalty/tab/" + customerId, null);
        ok(200, "GET", "/pos/loyalty/earned/" + customerId, null);

        JsonNode statement = ok(200, "GET", "/pos/customers/" + customerId + "/statement", null);
        assertThat(statement.get("partner").get("id").asString()).isEqualTo(customerId);
        assertThat(statement.has("creditLimit")).isTrue();
        ok(200, "GET", "/pos/customers/" + customerId + "/credit", null);
        JsonNode open = ok(200, "GET", "/pos/credit/open?pageSize=5", null);
        assertThat(open.get("pageSize").asInt()).isEqualTo(5);
        Res missing = as("GET", "/pos/customers/" + UUID.randomUUID() + "/statement", null);
        assertThat(missing.body().get("message").asString()).isEqualTo("Customer not found");
    }

    @Test
    @Order(9)
    @DisplayName("reservations: create, overlap conflict, seat, complete; cancel and no-show")
    void reservations() {
        JsonNode table = ok(201, "POST", "/pos/tables", Map.of("name", "Res " + UUID.randomUUID(), "number",
                (int) (Math.random() * 100000) + 200000));
        String tableId = table.get("id").asString();
        String start = java.time.Instant.now().plusSeconds(3 * 3600).toString();
        String end = java.time.Instant.now().plusSeconds(5 * 3600).toString();
        JsonNode r = ok(201, "POST", "/pos/reservations", Map.of("tableId", tableId, "customerName", " Ann ",
                "startAt", start, "endAt", end, "partySize", 4));
        assertThat(r.get("customerName").asString()).isEqualTo("Ann");
        assertThat(r.get("status").asString()).isEqualTo("pending");
        Res overlap = as("POST", "/pos/reservations", Map.of("tableId", tableId, "customerName", "Bob",
                "startAt", start, "endAt", end));
        assertThat(overlap.status()).isEqualTo(409);
        assertThat(overlap.body().get("message").asString()).startsWith("Table T" + table.get("number").asInt()
                + " is already reserved from ");
        Res badRange = as("POST", "/pos/reservations", Map.of("tableId", tableId, "customerName", "Bob",
                "startAt", end, "endAt", start));
        assertThat(badRange.body().get("message").asString()).isEqualTo("endAt must be after startAt");
        String id = r.get("id").asString();
        assertThat(ok(200, "GET", "/pos/reservations/" + id, null).get("table").get("id").asString()).isEqualTo(tableId);
        assertThat(ok(200, "GET", "/pos/reservations?tableId=" + tableId, null).get(0).get("table").has("status")).isTrue();
        ok(200, "PATCH", "/pos/reservations/" + id, Map.of("partySize", 5));
        JsonNode seated = ok(201, "POST", "/pos/reservations/" + id + "/seat", Map.of());
        assertThat(seated.get("status").asString()).isEqualTo("seated");
        assertThat(ok(200, "GET", "/pos/tables/" + tableId, null).get("status").asString()).isEqualTo("occupied");
        Res cancelSeated = as("POST", "/pos/reservations/" + id + "/cancel", null);
        assertThat(cancelSeated.body().get("message").asString()).isEqualTo("Reservation is already seated");
        assertThat(ok(201, "POST", "/pos/reservations/" + id + "/complete", null).get("status").asString())
                .isEqualTo("completed");

        JsonNode r2 = ok(201, "POST", "/pos/reservations", Map.of("tableId", tableId, "customerName", "Cy",
                "startAt", java.time.Instant.now().plusSeconds(24 * 3600).toString(),
                "endAt", java.time.Instant.now().plusSeconds(26 * 3600).toString()));
        assertThat(ok(201, "POST", "/pos/reservations/" + r2.get("id").asString() + "/no-show", null).get("status")
                .asString()).isEqualTo("no_show");
        JsonNode report = ok(200, "GET", "/pos/reports/reservations?fromDate=2020-01-01&toDate=2100-01-01", null);
        assertThat(report.get("totals").get("total").asInt()).isGreaterThanOrEqualTo(2);
        ok(200, "GET", "/pos/reports/tables/utilization?date=" + java.time.LocalDate.now(), null);
        ok(200, "GET", "/pos/reports/tables/revenue?fromDate=2020-01-01&toDate=2100-01-01", null);
        assertThat(as("GET", "/pos/reports/tables/utilization", null).body().get("message").asString())
                .isEqualTo("Invalid date");
    }

    @Test
    @Order(10)
    @DisplayName("sales reports read the POS invoices of the range")
    void reports() {
        String today = java.time.LocalDate.now().toString();
        String range = "?fromDate=" + today + "&toDate=" + today;
        JsonNode summary = ok(200, "GET", "/pos/reports/sales-summary" + range + "&groupBy=day", null);
        assertThat(summary.get("totals").get("orders").asInt()).isGreaterThanOrEqualTo(4);
        assertThat(summary.get("periods").get(0).get("periodKey").asString()).isEqualTo(today);
        Res badGroup = as("GET", "/pos/reports/sales-summary" + range + "&groupBy=year", null);
        assertThat(badGroup.body().get("message").asString()).isEqualTo("Unknown groupBy \"year\"");
        Res badType = as("GET", "/pos/reports/sales-report" + range + "&orderType=boat", null);
        assertThat(badType.body().get("message").asString()).isEqualTo("Unknown orderType \"boat\"");
        Res badRange = as("GET", "/pos/reports/sales-report?fromDate=nope&toDate=" + today, null);
        assertThat(badRange.body().get("message").asString()).isEqualTo("Invalid fromDate/toDate");
        JsonNode top = ok(200, "GET", "/pos/reports/top-items" + range, null);
        assertThat(top.toString()).contains(productId);
        JsonNode sold = ok(200, "GET", "/pos/reports/sold-items" + range + "&itemSearch=coffee", null);
        assertThat(sold.size()).isGreaterThanOrEqualTo(4);
        ok(200, "GET", "/pos/reports/sales-report" + range, null);
        ok(200, "GET", "/pos/reports/cashier-report" + range + "&paymentMethod=cash", null);
        ok(200, "GET", "/pos/reports/waiter-report" + range, null);
        ok(200, "GET", "/pos/reports/order-report" + range + "&includeCancelled=true", null);
        assertThat(as("GET", "/pos/reports/order-report" + range + "&status=bogus", null).body().get("message").asString())
                .isEqualTo("Unknown order status \"bogus\"");
        ok(200, "GET", "/pos/reports/sales-by-hour" + range + "&hours=8,9,x", null);
        JsonNode group = ok(200, "GET", "/pos/reports/items-by-group" + range, null);
        assertThat(group.isArray()).isTrue();
        JsonNode itemSales = ok(200, "GET", "/pos/reports/item-sales" + range + "&itemKey=" + productId, null);
        assertThat(itemSales.get("rows").get(0).get("itemKey").asString()).isEqualTo(productId);
        JsonNode byServer = ok(200, "GET", "/pos/reports/items-by-server" + range + "&groupBy=shift", null);
        assertThat(byServer.get("groupBy").asString()).isEqualTo("shift");
        ok(200, "GET", "/pos/reports/filter-options" + range, null);
        JsonNode shifts = ok(200, "GET", "/pos/reports/cashier-shift-summary" + range + "&registerId=" + registerId,
                null);
        assertThat(shifts.get(0).get("sessionId").asString()).isEqualTo(sessionId);
        JsonNode x = ok(200, "GET", "/pos/reports/x-report?cashSessionId=" + sessionId, null);
        assertThat(x.has("issues")).isTrue();
        Res z = as("GET", "/pos/reports/z-report?cashSessionId=" + sessionId, null);
        assertThat(z.body().get("message").asString()).isEqualTo("Close the register before requesting its Z-report");
    }

    @Test
    @Order(11)
    @DisplayName("digital menu: QR session, catalog, revoke")
    void digitalMenu() {
        String branchId = jdbc.sql("SELECT id FROM \"Branch\" WHERE \"organizationId\" = ? LIMIT 1").param(demoOrg)
                .query(String.class).optional().orElse(UUID.randomUUID().toString());
        JsonNode session = ok(201, "POST", "/pos/menu/sessions", Map.of("branchId", branchId, "tableNumber", "7"));
        String token = session.get("token").asString();
        assertThat(token).hasSize(22);
        JsonNode catalog = ok(200, "GET", "/menu/public/catalog?token=" + token, null);
        assertThat(catalog.get("session").get("tableNumber").asString()).isEqualTo("7");
        assertThat(catalog.get("combos").size()).isZero();
        assertThat(ok(200, "GET", "/pos/menu/sessions", null).toString()).contains(token);
        assertThat(ok(200, "DELETE", "/pos/menu/sessions/" + session.get("id").asString(), null).get("ok").asBoolean())
                .isTrue();
        Res expired = as("GET", "/menu/public/catalog?token=" + token, null);
        assertThat(expired.status()).isEqualTo(404);
        assertThat(expired.body().get("message").asString())
                .isEqualTo("This menu link has expired. Please ask staff for a new QR.");
        Res place = as("POST", "/menu/public/orders", Map.of("token", token, "customerName", "Q", "lines", List.of(),
                "tenders", List.of()));
        assertThat(place.body().get("message").asString()).isEqualTo("Menu link expired");
        ok(200, "GET", "/pos/menu/orders", null);
        Res status = as("PATCH", "/pos/menu/orders/" + UUID.randomUUID() + "/status", Map.of("status", "ready"));
        assertThat(status.status()).isEqualTo(400);
        assertThat(call("GET", "/menu/public/catalog?token=x", null, (String) null).status()).isEqualTo(401);
    }

    @Test
    @Order(20)
    @DisplayName("tenant isolation: another org cannot read DEMO POS records")
    void tenantIsolation() {
        String orderId = jdbc.sql("SELECT id FROM \"Order\" WHERE \"organizationId\" = ? LIMIT 1").param(demoOrg)
                .query(String.class).single();
        assertThat(call("GET", "/pos/orders/" + orderId, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/pos/orders", null, otherToken).body().toString()).doesNotContain(orderId);
        assertThat(call("GET", "/pos/customers/" + customerId + "/statement", null, otherToken).status()).isEqualTo(404);
    }

    @Test
    @Order(30)
    @DisplayName("shift close drains stock postings and freezes the Z-report")
    void closeAndZ() {
        JsonNode x = ok(200, "GET", "/pos/reports/x-report?cashSessionId=" + sessionId, null);
        Object expected = x.get("totals").get("expectedCash").asString();
        Res closed = keyed("POST", "/cash-sessions/close", withUncountedTenders(Map.of("sessionId", sessionId,
                "closingCounted", new BigDecimal(expected.toString()))), UUID.randomUUID().toString());
        assertThat(closed.status()).as(closed.raw().body()).isEqualTo(200);
        JsonNode z = ok(200, "GET", "/pos/reports/z-report?cashSessionId=" + sessionId, null);
        assertThat(z.has("totals")).isTrue();
        JsonNode snap = ok(200, "GET", "/pos/reports/z-report/" + sessionId, null);
        assertThat(snap.get("cashSessionId").asString()).isEqualTo(sessionId);
        Res stmt = as("POST", "/pos/receipts/print-closing-statement", Map.of("sessionId", sessionId, "text", "Z"));
        assertThat(stmt.status()).isEqualTo(201);
        assertThat(stmt.body().get("backend").asString()).isEqualTo("console");
    }

    @org.junit.jupiter.api.AfterAll
    void cleanup() {
        forceCloseSessions(List.of(registerId));
    }

    @Test
    @Order(40)
    @DisplayName("authorization matrix covers every POS route")
    void authorizationMatrix() {
        // 219 ported Nest routes + 5 post-parity /returns routes (docs/spec/pos.md "Deviations").
        assertThat(assertAuthorizationMatrix("com.inventoryerp.pos")).isEqualTo(224);
    }
}
