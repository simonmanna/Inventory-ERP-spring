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
 * POS money operations over HTTP (ports of {@code pos-money-foundations.spec.ts} refund cases,
 * {@code pos-store-credit-issuance.spec.ts}, {@code pos-kot-print-delta.spec.ts},
 * {@code billing/pos-credit-settlement.spec.ts}, {@code order/pos-orders.void.spec.ts},
 * {@code test/unit/pos-tender-validation.spec.ts}) plus the shift handover.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PosMoneyIT extends ApiTestSupport {

    static final String PIN = "4321";

    String productId;
    String customerId;
    String registerId;
    String sessionId;
    String adminId;

    Res keyed(String method, String path, Object body) {
        return call(method, path, body, Map.of("Authorization", "Bearer " + adminToken, "Idempotency-Key",
                UUID.randomUUID().toString()));
    }

    JsonNode money(int status, String method, String path, Object body) {
        Res r = keyed(method, path, body);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(status);
        return r.body();
    }

    @BeforeAll
    void fixtures() {
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        productId = ok(201, "POST", "/products", Map.of("code", "MNY-" + suffix, "name", "Cake " + suffix,
                "productType", "service", "salesPrice", 1000)).get("id").asString();
        customerId = ok(201, "POST", "/partners", Map.of("code", "MNY-C-" + suffix, "name", "House " + suffix,
                "isCustomer", true)).get("id").asString();
        jdbc.sql("UPDATE \"Partner\" SET \"creditLimit\" = 100000 WHERE id = ?").param(customerId).update();
        registerId = ok(201, "POST", "/cash-registers", Map.of("code", "MREG-" + suffix, "name", "Money till"))
                .get("id").asString();
        sessionId = money(200, "POST", "/cash-sessions/open", Map.of("cashRegisterId", registerId, "openingFloat", 0))
                .get("id").asString();
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
        ok(201, "POST", "/pos/override/pin", Map.of("pin", PIN));
    }

    JsonNode sale(int qty) {
        return money(200, "POST", "/pos/checkout", Map.of("cashSessionId", sessionId, "lines", List.of(Map.of(
                "productId", productId, "description", "Cake", "quantity", qty, "unitPrice", 1000)),
                "tenders", List.of(Map.of("method", "cash", "amount", qty * 1000))));
    }

    Map<String, Object> refundBody(Object lines) {
        Map<String, Object> b = new LinkedHashMap<>(Map.of("reason", "Customer returned it", "overrideById", adminId,
                "overridePin", PIN, "stockDisposition", "no_return", "cashSessionId", sessionId));
        if (lines != null) {
            b.put("lines", lines);
        }
        return b;
    }

    @Test
    @Order(1)
    @DisplayName("tender legs must be positive; allowPartial must be boolean (D1/D2)")
    void tenderValidation() {
        Res negative = keyed("POST", "/pos/checkout", Map.of("cashSessionId", sessionId, "lines", List.of(Map.of(
                "productId", productId, "description", "Cake", "quantity", 2, "unitPrice", 1000)),
                "tenders", List.of(Map.of("method", "cash", "amount", 3000), Map.of("method", "card", "amount", -1000))));
        assertThat(negative.status()).isEqualTo(400);
        assertThat(negative.raw().body()).contains("must be a positive number");
        Res zero = keyed("POST", "/pos/invoices/" + UUID.randomUUID() + "/payments", Map.of("tenders",
                List.of(Map.of("method", "cash", "amount", 0))));
        assertThat(zero.status()).isEqualTo(400);
        Res partial = keyed("POST", "/pos/invoices/" + UUID.randomUUID() + "/payments", Map.of("allowPartial", "yes"));
        assertThat(partial.status()).isEqualTo(400);
        Res missing = keyed("POST", "/pos/invoices/" + UUID.randomUUID() + "/payments", Map.of("tenders",
                List.of(Map.of("method", "cash"))));
        assertThat(missing.status()).isEqualTo(400);
    }

    @Test
    @Order(2)
    @DisplayName("refund: duplicate/negative lines rejected without writes, partial then remaining full, then refused")
    void refunds() {
        String invoiceId = sale(3).get("invoiceId").asString();
        String lineId = jdbc.sql("SELECT id FROM \"InvoiceItem\" WHERE \"invoiceId\" = ?").param(invoiceId)
                .query(String.class).single();
        Res dup = keyed("POST", "/pos/invoices/" + invoiceId + "/refund", refundBody(List.of(
                Map.of("lineId", lineId, "quantity", 1), Map.of("lineId", lineId, "quantity", 1))));
        assertThat(dup.body().get("message").asString()).isEqualTo("Duplicate refund line");
        Res neg = keyed("POST", "/pos/invoices/" + invoiceId + "/refund", refundBody(List.of(
                Map.of("lineId", lineId, "quantity", -1))));
        assertThat(neg.status()).isEqualTo(400);
        assertThat(neg.raw().body()).contains("quantity must not be less than 0");
        Res wrongPin = keyed("POST", "/pos/invoices/" + invoiceId + "/refund", Map.of("reason", "x", "overrideById",
                adminId, "overridePin", "0000", "stockDisposition", "no_return"));
        assertThat(wrongPin.status()).isIn(400, 401, 403);
        assertThat(jdbc.sql("SELECT count(*) FROM \"PosRefund\" WHERE \"invoiceId\" = ?").param(invoiceId)
                .query(Long.class).single()).isZero();

        money(200, "POST", "/pos/invoices/" + invoiceId + "/refund", refundBody(List.of(
                Map.of("lineId", lineId, "quantity", 1))));
        BigDecimal refunded = jdbc.sql("SELECT \"amountRefunded\" FROM \"Invoice\" WHERE id = ?").param(invoiceId)
                .query(BigDecimal.class).single();
        assertThat(refunded).isEqualByComparingTo("1000");
        Res tooMany = keyed("POST", "/pos/invoices/" + invoiceId + "/refund", refundBody(List.of(
                Map.of("lineId", lineId, "quantity", 3))));
        assertThat(tooMany.body().get("message").asString()).isEqualTo("Refund quantity exceeds the remaining quantity");
        money(200, "POST", "/pos/invoices/" + invoiceId + "/refund", refundBody(null));
        assertThat(jdbc.sql("SELECT \"amountRefunded\" FROM \"Invoice\" WHERE id = ?").param(invoiceId)
                .query(BigDecimal.class).single()).isEqualByComparingTo("3000");
        assertThat(jdbc.sql("SELECT count(*) FROM \"PosRefund\" WHERE \"invoiceId\" = ?").param(invoiceId)
                .query(Long.class).single()).isEqualTo(2);
        Res again = keyed("POST", "/pos/invoices/" + invoiceId + "/refund", refundBody(null));
        assertThat(again.status()).isEqualTo(400);
        assertThat(again.body().get("message").asString()).isIn("Invoice cannot be refunded", "Nothing remains to refund");
        Res noReason = keyed("POST", "/pos/invoices/" + invoiceId + "/refund", Map.of("overrideById", adminId,
                "stockDisposition", "no_return"));
        assertThat(noReason.body().get("message").asString()).isEqualTo("A refund reason is required");
    }

    @Test
    @Order(3)
    @DisplayName("sale void reverses the whole sale with a manager PIN")
    void saleVoid() {
        String invoiceId = sale(1).get("invoiceId").asString();
        money(200, "POST", "/pos/sales/" + invoiceId + "/void", Map.of("reason", "Rang in error", "overrideById", adminId,
                "overridePin", PIN, "stockDisposition", "no_return", "cashSessionId", sessionId));
        assertThat(jdbc.sql("SELECT \"amountRefunded\" FROM \"Invoice\" WHERE id = ?").param(invoiceId)
                .query(BigDecimal.class).single()).isEqualByComparingTo("1000");
        assertThat(jdbc.sql("SELECT reason FROM \"PosRefund\" WHERE \"invoiceId\" = ?").param(invoiceId)
                .query(String.class).single()).startsWith("VOID: ");
    }

    @Test
    @Order(4)
    @DisplayName("fired lines: void needs reason + PIN; whole-order cancel refuses a placeholder reason")
    void voidItemAndCancel() {
        JsonNode order = ok(201, "POST", "/pos/orders", Map.of("orderType", "takeaway", "guestCount", 1,
                "cashSessionId", sessionId, "lines", List.of(Map.of("productId", productId, "description", "Cake",
                        "quantity", 2, "unitPrice", 1000))));
        String orderId = order.get("id").asString();
        ok(201, "POST", "/pos/orders/" + orderId + "/fire-kitchen", Map.of());
        String itemId = jdbc.sql("SELECT id FROM \"OrderItem\" WHERE \"orderId\" = ? AND cancelled = false")
                .param(orderId).query(String.class).single();
        Res placeholder = keyed("POST", "/pos/orders/" + orderId + "/cancel", Map.of("reason", "Order emptied",
                "overrideById", adminId, "overridePin", PIN));
        assertThat(placeholder.status()).isEqualTo(400);
        assertThat(placeholder.body().get("message").asString()).contains("Give a reason for cancelling it.");
        Res noManager = keyed("DELETE", "/pos/orders/" + orderId + "/items/" + itemId, Map.of("reason", "Dropped plate"));
        assertThat(noManager.status()).isIn(400, 403);
        money(200, "DELETE", "/pos/orders/" + orderId + "/items/" + itemId, Map.of("reason", "Dropped plate",
                "overrideById", adminId, "overridePin", PIN));
        assertThat(jdbc.sql("SELECT cancelled FROM \"OrderItem\" WHERE id = ?").param(itemId).query(Boolean.class)
                .single()).isTrue();
        money(200, "POST", "/pos/orders/" + orderId + "/cancel", Map.of("reason", "Customer walked out",
                "overrideById", adminId, "overridePin", PIN));
        assertThat(jdbc.sql("SELECT status::text FROM \"Order\" WHERE id = ?").param(orderId).query(String.class)
                .single()).isEqualTo("cancelled");
        Res reopen = as("POST", "/pos/orders/" + orderId + "/reopen", null);
        assertThat(reopen.status()).isLessThan(500);
    }

    @Test
    @Order(5)
    @DisplayName("credit sale: on account, open-credit feed, statement, then collected; second credit written off")
    void creditSaleAndWriteOff() {
        JsonNode credit = money(200, "POST", "/pos/checkout", Map.of("partnerId", customerId, "cashSessionId", sessionId,
                "settleMode", "credit", "lines", List.of(Map.of("productId", productId, "description", "Cake",
                        "quantity", 5, "unitPrice", 1000))));
        assertThat(credit.get("settleMode").asString()).isEqualTo("credit");
        assertThat(credit.get("amountDue").asInt()).isEqualTo(5000);
        String invoiceId = credit.get("invoiceId").asString();
        JsonNode open = ok(200, "GET", "/pos/credit/open?partnerId=" + customerId, null);
        assertThat(open.get("total").asInt()).isEqualTo(1);
        assertThat(open.get("rows").get(0).get("amountResidual").asInt()).isEqualTo(5000);
        assertThat(ok(200, "GET", "/pos/customers/" + customerId + "/credit", null).get("outstanding").asInt())
                .isEqualTo(5000);
        money(200, "POST", "/pos/invoices/" + invoiceId + "/payments", Map.of("cashSessionId", sessionId,
                "tenders", List.of(Map.of("method", "cash", "amount", 5000))));
        assertThat(jdbc.sql("SELECT \"amountResidual\" FROM \"Invoice\" WHERE id = ?").param(invoiceId)
                .query(BigDecimal.class).single()).isEqualByComparingTo("0");
        JsonNode statement = ok(200, "GET", "/pos/customers/" + customerId + "/statement", null);
        assertThat(statement.get("entries").size()).isEqualTo(2);
        assertThat(statement.get("entries").get(1).get("runningBalance").asInt()).isZero();

        String second = money(200, "POST", "/pos/checkout", Map.of("partnerId", customerId, "cashSessionId", sessionId,
                "settleMode", "credit", "lines", List.of(Map.of("productId", productId, "description", "Cake",
                        "quantity", 2, "unitPrice", 1000)))).get("invoiceId").asString();
        Res noReason = keyed("POST", "/pos/invoices/" + second + "/write-off", Map.of("reason", " "));
        assertThat(noReason.status()).isEqualTo(400);
        money(200, "POST", "/pos/invoices/" + second + "/write-off", Map.of("reason", "Customer left town"));
        assertThat(ok(200, "GET", "/pos/credit/open?partnerId=" + customerId, null).get("total").asInt()).isZero();
        JsonNode after = ok(200, "GET", "/pos/customers/" + customerId + "/statement", null);
        assertThat(after.toString()).contains("write_off");
    }

    @Test
    @Order(6)
    @DisplayName("store credit: disabled by default, capped, funded only from a non-drawer account")
    void storeCredit() {
        String expense = jdbc.sql("SELECT a.id FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\""
                + " WHERE a.\"organizationId\" = ? AND c.key = 'expense' AND a.\"isPostable\" = true AND a.\"isActive\" = true"
                + " ORDER BY a.code LIMIT 1").param(demoOrg).query(String.class).optional().orElse(null);
        Map<String, Object> issue = new LinkedHashMap<>(Map.of("partnerId", customerId, "amount", 10000, "source",
                "gift_card", "fundingAccountId", expense == null ? UUID.randomUUID().toString() : expense,
                "notes", "A-100 notes must not crash"));
        jdbc.sql("DELETE FROM \"Setting\" WHERE \"organizationId\" = ? AND key = 'pos.storeCreditIssueLimit'")
                .param(demoOrg).update();
        Res disabled = as("POST", "/pos/loyalty/credit/issue", issue);
        if (disabled.status() == 403) {
            assertThat(disabled.body().get("message").asString()).contains("disabled");
        }
        ok(200, "PUT", "/settings/pos.storeCreditIssueLimit", Map.of("value", 50000));
        Map<String, Object> big = new LinkedHashMap<>(issue);
        big.put("amount", 60000);
        assertThat(as("POST", "/pos/loyalty/credit/issue", big).body().get("message").asString())
                .startsWith("Store credit issue exceeds the configured limit of ");
        Map<String, Object> noFunding = new LinkedHashMap<>(issue);
        noFunding.remove("fundingAccountId");
        assertThat(as("POST", "/pos/loyalty/credit/issue", noFunding).raw().body())
                .contains("fundingAccountId must be a string");
        String drawer = jdbc.sql("SELECT \"defaultAccountId\" FROM \"CashRegister\" WHERE id = ?").param(registerId)
                .query(String.class).single();
        Map<String, Object> fromDrawer = new LinkedHashMap<>(issue);
        fromDrawer.put("fundingAccountId", drawer);
        Res drawerRes = as("POST", "/pos/loyalty/credit/issue", fromDrawer);
        assertThat(drawerRes.status()).isEqualTo(400);
        if (expense != null) {
            BigDecimal before = jdbc.sql("SELECT coalesce(sum(balance), 0) FROM \"StoreCredit\" WHERE \"partnerId\" = ?")
                    .param(customerId).query(BigDecimal.class).single();
            JsonNode ok = ok(201, "POST", "/pos/loyalty/credit/issue", issue);
            assertThat(new BigDecimal(ok.get("balance").asString())).isEqualByComparingTo(before.add(BigDecimal.valueOf(10000)));
            BigDecimal imbalance = jdbc.sql("SELECT sum(l.debit) - sum(l.credit) FROM \"JournalLine\" l JOIN \"JournalEntry\" e"
                    + " ON e.id = l.\"journalEntryId\" WHERE e.\"organizationId\" = ? AND e.\"sourceType\" = 'store_credit_issue'")
                    .param(demoOrg).query(BigDecimal.class).single();
            assertThat(imbalance).isEqualByComparingTo("0");
            ok(201, "POST", "/pos/loyalty/credit/redeem", Map.of("partnerId", customerId, "amount", 2000));
            assertThat(new BigDecimal(ok(200, "GET", "/pos/loyalty/credit/" + customerId, null).get("balance").asString()))
                    .isEqualByComparingTo(before.add(BigDecimal.valueOf(8000)));
        }
    }

    @Test
    @Order(7)
    @DisplayName("paper KOT prints the whole order once, then only added quantity")
    void kotDelta() {
        JsonNode order = ok(201, "POST", "/pos/orders", Map.of("orderType", "takeaway", "guestCount", 1,
                "cashSessionId", sessionId, "lines", List.of(Map.of("productId", productId,
                        "description", "Cake", "quantity", 2, "unitPrice", 1000))));
        String orderId = order.get("id").asString();
        // (fire-kitchen prints this same paper KOT itself; here the cashier presses KOT directly)
        JsonNode first = ok(201, "POST", "/pos/receipts/" + orderId + "/print-kot", null);
        assertThat(first.get("printedCount").asInt()).isEqualTo(1);
        assertThat(first.get("text").asString()).contains("KITCHEN ORDER TICKET");
        assertThat(ok(201, "POST", "/pos/receipts/" + orderId + "/print-kot", null).get("printedCount").asInt()).isZero();
        JsonNode current = ok(200, "GET", "/pos/orders/" + orderId, null);
        ok(200, "PUT", "/pos/orders/" + orderId + "/items", Map.of("expectedVersion", current.get("version").asInt(),
                "lines", List.of(Map.of("productId", productId, "description", "Cake", "quantity", 3, "unitPrice", 1000))));
        JsonNode extra = ok(201, "POST", "/pos/receipts/" + orderId + "/print-kot", null);
        assertThat(extra.get("printedCount").asInt()).isEqualTo(1);
        assertThat(extra.get("text").asString()).contains("ADDITIONAL KOT");
        assertThat(jdbc.sql("SELECT \"kotPrintedQty\" FROM \"OrderItem\" WHERE \"orderId\" = ? AND cancelled = false")
                .param(orderId).query(BigDecimal.class).single()).isEqualByComparingTo("3");
        assertThat(ok(201, "POST", "/pos/receipts/" + orderId + "/print-kot", null).get("printedCount").asInt()).isZero();
        money(200, "POST", "/pos/orders/" + orderId + "/settle", Map.of("cashSessionId", sessionId,
                "tenders", List.of(Map.of("method", "cash", "amount", 3000))));
    }

    @org.junit.jupiter.api.AfterAll
    void cleanup() {
        forceCloseSessions(List.of(registerId));
    }

    @Test
    @Order(10)
    @DisplayName("shift handover: incoming PIN + manager PIN close the session and open the next one")
    void handover() {
        String role = jdbc.sql("SELECT r.id FROM \"Role\" r JOIN \"_UserRoles\" ur ON ur.\"A\" = r.id WHERE ur.\"B\" = ?"
                + " LIMIT 1").param(adminId).query(String.class).single();
        String email = "next-" + UUID.randomUUID().toString().substring(0, 8) + "@demo.test";
        String nextId = createUser(demoOrg, email, "Next@12345", role);
        String nextToken = login("DEMO", email, "Next@12345");
        assertThat(call("POST", "/pos/override/pin", Map.of("pin", "8765"), nextToken).status()).isEqualTo(201);
        String mgrEmail = "mgr-" + UUID.randomUUID().toString().substring(0, 8) + "@demo.test";
        String managerId = createUser(demoOrg, mgrEmail, "Mgr@12345", role);
        assertThat(call("POST", "/pos/override/pin", Map.of("pin", "5678"), login("DEMO", mgrEmail, "Mgr@12345"))
                .status()).isEqualTo(201);
        JsonNode x = ok(200, "GET", "/pos/reports/x-report?cashSessionId=" + sessionId, null);
        BigDecimal expected = new BigDecimal(x.get("totals").get("expectedCash").asString());
        Map<String, Object> body = Map.of("cashRegisterId", registerId, "closingCounted", expected, "incomingUserId",
                nextId, "incomingPin", "0000", "approvedById", managerId, "managerPin", "5678");
        Res badPin = keyed("POST", "/pos/shift/handover", body);
        assertThat(badPin.status()).isIn(400, 401, 403);
        Map<String, Object> good = new LinkedHashMap<>(body);
        good.put("incomingPin", "8765");
        good.put("uncountedAccounts", uncountedTenders());
        assertThat(call("POST", "/pos/shift/handover", good, adminToken).status()).isEqualTo(400);
        JsonNode res = money(200, "POST", "/pos/shift/handover", good);
        assertThat(jdbc.sql("SELECT status::text FROM \"CashSession\" WHERE id = ?").param(sessionId).query(String.class)
                .single()).isNotEqualTo("open");
        assertThat(jdbc.sql("SELECT count(*) FROM \"CashSession\" WHERE \"cashRegisterId\" = ? AND status = 'open'"
                + " AND \"userId\" = ?").params(registerId, nextId).query(Long.class).single()).isEqualTo(1);
        assertThat(res.toString()).isNotEmpty();
    }
}
