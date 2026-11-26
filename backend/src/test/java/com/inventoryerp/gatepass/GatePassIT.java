package com.inventoryerp.gatepass;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.gatepass.application.GatePassSources;
import com.inventoryerp.gatepass.web.GatePassController;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Gate pass (ADR-S085, docs/spec/gate-pass.md): issue, caps, warnings, gate check / gate-out, void, PDF, scope. */
class GatePassIT extends ApiTestSupport {

    @Autowired
    AccountResolverService accounts;

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    String adminId;
    String branch;
    String location;
    String otherLocation;
    String customer;
    String goods;
    String vat;

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        String[] acc = new String[3];
        TenantContext.run(TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm).executeWithoutResult(s -> {
            acc[0] = accounts.ensureByCode("10" + num(), "Gate cash " + sfx, "cash", null);
            acc[1] = accounts.ensureByCode("10" + num(), "Gate MTN " + sfx, "mobile_money", null);
            acc[2] = accounts.ensureByCode("10" + num(), "Gate bank " + sfx, "bank", null);
        }));
        branch = insert("INSERT INTO \"Branch\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?, ?,"
                + " now())", "GB-" + sfx, "Gate branch " + sfx);
        location = insert("INSERT INTO \"InventoryLocation\" (id, \"organizationId\", code, name, type, \"branchId\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, 'store', ?, now())", "GL-" + sfx, "Gate store " + sfx, branch);
        otherLocation = insert("INSERT INTO \"InventoryLocation\" (id, \"organizationId\", code, name, type, \"branchId\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, 'store', ?, now())", "GL2-" + sfx, "Yard " + sfx, branch);
        customer = insert("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, phone, \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, '0700000001', true, now())", "GC-" + sfx, "Gate customer " + sfx);
        goods = insert("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                + " \"costPrice\", \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable'::\"ProductType\", 35000,"
                + " 21000, true, now())", "GP-" + sfx, "Cement " + sfx);
        jdbc.sql("INSERT INTO \"StockItem\" (id, \"organizationId\", \"productId\", \"locationId\", quantity,"
                + " \"runningAverageCost\", \"updatedAt\") VALUES (?, ?, ?, ?, 1000, 21000, now())")
                .params(UUID.randomUUID().toString(), demoOrg, goods, location).update();
        vat = insert("INSERT INTO \"Tax\" (id, \"organizationId\", name, code, rate, \"updatedAt\") VALUES (?, ?, ?, ?, 18,"
                + " now())", "VAT 18 " + sfx, "G" + sfx);
        ok(201, "POST", "/sales/branch-profiles", Map.of("branchId", branch, "locationId", location, "cashAccountId",
                acc[0], "momoMtnAccountId", acc[1], "bankClearingAccountId", acc[2], "walkInPartnerId", customer,
                "numberPrefix", "G" + sfx.substring(0, 4).replaceAll("[^A-Z0-9]", "X")));
    }

    String insert(String sql, Object... rest) {
        String id = UUID.randomUUID().toString();
        Object[] params = new Object[rest.length + 2];
        params[0] = id;
        params[1] = demoOrg;
        System.arraycopy(rest, 0, params, 2, rest.length);
        jdbc.sql(sql).params(params).update();
        return id;
    }

    String num() {
        return String.valueOf(1000 + (int) (Math.random() * 8999));
    }

    Map<String, String> key() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    /** A draft cash sale of {@code qty} × goods; paid and confirmed when {@code confirm}. */
    String cashSale(int qty, boolean confirm) {
        String id = ok(201, "POST", "/sales/cash-sales", Map.of("branchId", branch, "customerName", "ABC Ltd",
                "lines", List.of(Map.of("productId", goods, "quantity", qty, "unitPrice", 35_000, "taxId", vat))))
                .get("id").asString();
        if (confirm) {
            double total = ok(200, "GET", "/sales/cash-sales/" + id, null).get("grandTotal").asDouble();
            JsonNode s = ok(201, "POST", "/sales/cash-sales/" + id + "/payments", Map.of("method", "cash", "amount", total));
            String pay = s.get("payments").get(s.get("payments").size() - 1).get("id").asString();
            ok(200, "POST", "/sales/cash-sales/" + id + "/payments/" + pay + "/verify", Map.of("status", "verified"));
            Res r = call("POST", "/sales/cash-sales/" + id + "/confirm", null, key());
            assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        }
        return id;
    }

    Map<String, Object> issueBody(String type, String id, Object... extra) {
        Map<String, Object> b = new HashMap<>(Map.of("sourceType", type, "sourceId", id, "bearerType", "driver",
                "bearerName", "John Driver", "vehiclePlate", "uax 123b", "bearerIdNumber", "DL-1"));
        for (int i = 0; i < extra.length; i += 2) {
            b.put((String) extra[i], extra[i + 1]);
        }
        return b;
    }

    @Test
    @DisplayName("every source query runs against the schema: unknown id → 404 '<Label> not found'; bad type → 400")
    void sourcesSmoke() {
        for (String type : GatePassSources.TYPES) {
            Res r = as("GET", "/gate-passes/source/" + type + "/" + UUID.randomUUID(), null);
            assertThat(r.status()).as(type + " → " + r.raw().body()).isEqualTo(404);
            assertThat(r.body().get("message").asString()).endsWith(" not found");
        }
        assertThat(as("GET", "/gate-passes/source/boat/" + UUID.randomUUID(), null).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("cash sale: draft pass carries warnings; caps per line; void frees quantity; cancelled source refused")
    void cashSaleIssueAndCaps() {
        String draft = cashSale(10, false);
        JsonNode view = ok(200, "GET", "/gate-passes/source/cash_sale/" + draft, null);
        assertThat(view.get("warnings").valueStream().map(JsonNode::asString)).contains("goods_not_issued", "unpaid");
        String line = view.get("lines").get(0).get("sourceLineId").asString();
        assertThat(view.get("lines").get(0).get("remaining").asDouble()).isEqualTo(10);

        JsonNode p1 = ok(201, "POST", "/gate-passes", issueBody("cash_sale", draft, "lines",
                List.of(Map.of("sourceLineId", line, "quantity", 6))));
        assertThat(p1.get("number").asString()).startsWith("GP-");
        assertThat(p1.get("vehiclePlate").asString()).isEqualTo("UAX 123B");
        assertThat(p1.get("warnings").valueStream().map(JsonNode::asString)).contains("goods_not_issued", "unpaid");
        assertThat(p1.has("verifyCode")).isFalse();
        assertThat(p1.get("partnerName").asString()).isEqualTo("Gate customer " + sfx);

        Res over = as("POST", "/gate-passes", issueBody("cash_sale", draft, "lines",
                List.of(Map.of("sourceLineId", line, "quantity", 5))));
        assertThat(over.status()).isEqualTo(400);
        assertThat(over.body().get("message").asString()).isEqualTo("Quantity for Cement " + sfx
                + " exceeds what is left to pass out (4)");
        JsonNode p2 = ok(201, "POST", "/gate-passes", issueBody("cash_sale", draft));
        assertThat(p2.get("lines").get(0).get("quantity").asDouble()).isEqualTo(4);
        assertThat(as("POST", "/gate-passes", issueBody("cash_sale", draft)).body().get("message").asString())
                .isEqualTo("Every item of this cash sale is already on a gate pass");

        JsonNode voided = ok(200, "POST", "/gate-passes/" + p2.get("id").asString() + "/void", Map.of("reason", "wrong"));
        assertThat(voided.get("state").asString()).isEqualTo("void");
        assertThat(as("POST", "/gate-passes/" + p2.get("id").asString() + "/void", Map.of("reason", "x")).status())
                .isEqualTo(400);
        assertThat(ok(200, "GET", "/gate-passes/source/cash_sale/" + draft, null).get("lines").get(0).get("remaining")
                .asDouble()).isEqualTo(4);

        Map<String, Object> noPlate = issueBody("cash_sale", draft);
        noPlate.remove("vehiclePlate");
        assertThat(as("POST", "/gate-passes", noPlate).body().get("message").asString())
                .isEqualTo("vehiclePlate should not be empty for a driver");
        assertThat(as("POST", "/gate-passes", issueBody("cash_sale", draft, "bearerType", "boat")).status()).isEqualTo(400);
        assertThat(as("POST", "/gate-passes", issueBody("cash_sale", draft, "lines",
                List.of(Map.of("sourceLineId", "nope", "quantity", 1)))).status()).isEqualTo(400);

        jdbc.sql("UPDATE \"SalesSubmission\" SET \"executionStatus\" = 'cancelled' WHERE id = ?").param(draft).update();
        assertThat(as("POST", "/gate-passes", issueBody("cash_sale", draft)).body().get("message").asString())
                .isEqualTo("Cannot issue a gate pass for a cancelled cash sale");
        // The pass issued before the cancel can no longer be used at the gate.
        Res blocked = as("POST", "/gate-passes/" + p1.get("id").asString() + "/gate-out", Map.of());
        assertThat(blocked.body().get("message").asString())
                .isEqualTo("The source document was cancelled; the goods may not leave");
    }

    @Test
    @DisplayName("gate: verify by QR text, wrong code / plate refused, gate-out marks the cash sale dispatched, once only")
    void gateOutCashSale() {
        String sale = cashSale(3, true);
        JsonNode view = ok(200, "GET", "/gate-passes/source/cash_sale/" + sale, null);
        assertThat(view.get("source").get("goodsIssued").asBoolean()).isTrue();
        assertThat(view.get("source").get("paymentState").asString()).isEqualTo("paid");
        assertThat(view.get("warnings").size()).isZero();
        JsonNode p = ok(201, "POST", "/gate-passes", issueBody("cash_sale", sale, "bearerType", "person",
                "vehiclePlate", "", "bearerName", "Mary Walker"));
        String id = p.get("id").asString();
        String number = p.get("number").asString();
        String code = jdbc.sql("SELECT \"verifyCode\" FROM \"GatePass\" WHERE id = ?").param(id).query(String.class)
                .single();

        JsonNode typed = ok(200, "GET", "/gate-passes/verify?code=" + number.toLowerCase(), null);
        assertThat(typed.get("codeVerified").asBoolean()).isFalse();
        assertThat(typed.get("canLetOut").asBoolean()).isTrue();
        JsonNode scanned = ok(200, "GET", "/gate-passes/verify?code=" + number + "%7C" + code, null);
        assertThat(scanned.get("codeVerified").asBoolean()).isTrue();
        assertThat(as("GET", "/gate-passes/verify?code=" + number + "%7CZZZZZZ", null).status()).isEqualTo(400);
        assertThat(as("GET", "/gate-passes/verify?code=GP-1999-99999", null).status()).isEqualTo(404);

        ok(200, "POST", "/gate-passes/" + id + "/refuse", Map.of("reason", "Load does not match"));
        assertThat(as("POST", "/gate-passes/" + id + "/gate-out", Map.of("verifyCode", "WRONG1")).status()).isEqualTo(400);

        JsonNode out = ok(200, "POST", "/gate-passes/" + id + "/gate-out", Map.of("verifyCode", code, "note", "ok"));
        assertThat(out.get("state").asString()).isEqualTo("used");
        assertThat(out.get("sourceUpdate").asString()).isEqualTo("Cash sale delivery marked dispatched");
        assertThat(out.get("events").valueStream().map(e -> e.get("type").asString()))
                .containsExactly("issued", "refused", "gate_out");
        JsonNode sale2 = ok(200, "GET", "/sales/cash-sales/" + sale, null);
        assertThat(sale2.get("deliveryStatus").asString()).isEqualTo("dispatched");
        assertThat(sale2.get("deliveryMethod").asString()).isEqualTo("pickup");
        assertThat(sale2.get("deliveryDriver").asString()).isEqualTo("Mary Walker");

        Res again = as("POST", "/gate-passes/" + id + "/gate-out", Map.of());
        assertThat(again.status()).isEqualTo(409);
        assertThat(as("POST", "/gate-passes/" + id + "/void", Map.of("reason", "late")).body().get("message").asString())
                .isEqualTo("A gate pass that was used at the gate cannot be voided");
        assertThat(as("POST", "/gate-passes/" + id + "/refuse", Map.of("reason", "x")).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("gate: plate must match; expired pass refused")
    void plateAndExpiry() {
        String sale = cashSale(2, true);
        String id = ok(201, "POST", "/gate-passes", issueBody("cash_sale", sale, "validHours", 2)).get("id").asString();
        Res wrong = as("POST", "/gate-passes/" + id + "/gate-out", Map.of("plate", "UBB 999Z"));
        assertThat(wrong.body().get("message").asString())
                .isEqualTo("Vehicle plate UBB 999Z does not match the gate pass (UAX 123B)");
        jdbc.sql("UPDATE \"GatePass\" SET \"validUntil\" = now() - interval '1 hour' WHERE id = ?").param(id).update();
        assertThat(ok(200, "GET", "/gate-passes/" + id, null).get("state").asString()).isEqualTo("expired");
        assertThat(as("POST", "/gate-passes/" + id + "/gate-out", Map.of("plate", "UAX-123B")).body().get("message")
                .asString()).startsWith("Gate pass expired at ");
        assertThat(ok(200, "GET", "/gate-passes?status=expired&sourceId=" + sale, null).get("data").size()).isEqualTo(1);
        assertThat(as("POST", "/gate-passes", issueBody("cash_sale", sale, "validHours", 500)).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("sales order: unpaid confirmed order warns; gate-out marks the handover dispatched with driver + plate")
    void salesOrderHandover() {
        String order = ok(201, "POST", "/sales/orders", Map.of("partnerId", customer, "requestedDate", "2026-11-20",
                "deliveryMethod", "delivery", "deliveryAddress", "Plot 7, Site road", "branchId", branch,
                "lines", List.of(Map.of("productId", goods, "quantity", 4)))).get("id").asString();
        ok(200, "POST", "/sales/orders/" + order + "/confirm", null);
        JsonNode view = ok(200, "GET", "/gate-passes/source/sales_order/" + order, null);
        assertThat(view.get("warnings").valueStream().map(JsonNode::asString)).contains("goods_not_issued", "unpaid");
        assertThat(view.get("prefill").get("destination").asString()).isEqualTo("Plot 7, Site road");
        JsonNode p = ok(201, "POST", "/gate-passes", issueBody("sales_order", order));
        // Sales readiness R4d (DEL-5): stocked goods that were never issued from stock may not leave.
        Res blocked = as("POST", "/gate-passes/" + p.get("id").asString() + "/gate-out", Map.of("plate", "UAX123B"));
        assertThat(blocked.status()).as(blocked.raw().body()).isEqualTo(400);
        assertThat(blocked.raw().body()).contains("have not been issued from stock yet");
        assertThat(ok(200, "GET", "/gate-passes/" + p.get("id").asString(), null).get("problems").toString())
                .contains("goods_not_issued");

        // Goods that are not stock-tracked have nothing to issue: gate-out marks the handover dispatched.
        String loose = insert("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"salesPrice\","
                + " \"costPrice\", \"trackInventory\", \"updatedAt\") VALUES (?, ?, ?, ?, 'consumable'::\"ProductType\", 500,"
                + " 0, false, now())", "GL-" + sfx, "Sand bags " + sfx);
        order = ok(201, "POST", "/sales/orders", Map.of("partnerId", customer, "requestedDate", "2026-11-20",
                "deliveryMethod", "delivery", "deliveryAddress", "Plot 7, Site road", "branchId", branch,
                "lines", List.of(Map.of("productId", loose, "quantity", 4)))).get("id").asString();
        ok(200, "POST", "/sales/orders/" + order + "/confirm", null);
        p = ok(201, "POST", "/gate-passes", issueBody("sales_order", order));
        JsonNode out = ok(200, "POST", "/gate-passes/" + p.get("id").asString() + "/gate-out", Map.of("plate", "UAX123B"));
        assertThat(out.get("sourceUpdate").asString()).isEqualTo("Handover marked dispatched");
        JsonNode h = ok(200, "GET", "/sales/orders/" + order + "/handover", null);
        assertThat(h.get("status").asString()).isEqualTo("dispatched");
        assertThat(h.get("driver").asString()).isEqualTo("John Driver");
        assertThat(h.get("vehicle").asString()).isEqualTo("UAX 123B");
    }

    @Test
    @DisplayName("invoice of a cash sale: paid, goods issued; gate-out updates the cash sale that manages its delivery")
    void invoiceFromCashSale() {
        String sale = cashSale(5, true);
        ok(200, "POST", "/sales/cash-sales/" + sale + "/submit", null);
        Res inv = call("POST", "/sales/cash-sales/" + sale + "/approve-and-invoice", null, key());
        assertThat(inv.status()).as(inv.raw().body()).isEqualTo(200);
        String invoiceId = inv.body().get("currentInvoiceId").asString();
        // 2 of the 5 already went out on a pass of the cash sale: the invoice shares that cap (same goods).
        String saleLine = ok(200, "GET", "/gate-passes/source/cash_sale/" + sale, null).get("lines").get(0)
                .get("sourceLineId").asString();
        ok(201, "POST", "/gate-passes", issueBody("cash_sale", sale, "lines", List.of(Map.of("sourceLineId", saleLine,
                "quantity", 2))));
        JsonNode view = ok(200, "GET", "/gate-passes/source/invoice/" + invoiceId, null);
        assertThat(view.get("source").get("paymentState").asString()).isEqualTo("paid");
        assertThat(view.get("source").get("goodsIssued").asBoolean()).isTrue();
        assertThat(view.get("lines").get(0).get("sourceQuantity").asDouble()).isEqualTo(5);
        assertThat(view.get("lines").get(0).get("remaining").asDouble()).isEqualTo(3);
        String invLine = view.get("lines").get(0).get("sourceLineId").asString();
        assertThat(as("POST", "/gate-passes", issueBody("invoice", invoiceId, "lines", List.of(Map.of("sourceLineId",
                invLine, "quantity", 4)))).body().get("message").asString())
                .isEqualTo("Quantity for Cement " + sfx + " exceeds what is left to pass out (3)");
        JsonNode p = ok(201, "POST", "/gate-passes", issueBody("invoice", invoiceId, "destination", "Plot 9, Mukono"));
        assertThat(p.get("lines").get(0).get("quantity").asDouble()).isEqualTo(3);
        assertThat(p.get("warnings").size()).isZero();
        assertThat(ok(200, "GET", "/gate-passes/source/cash_sale/" + sale, null).get("lines").get(0).get("remaining")
                .asDouble()).isZero();
        JsonNode out = ok(200, "POST", "/gate-passes/" + p.get("id").asString() + "/gate-out", Map.of());
        assertThat(out.get("sourceUpdate").asString()).isEqualTo("Cash sale delivery marked dispatched");
        JsonNode s = ok(200, "GET", "/sales/cash-sales/" + sale, null);
        assertThat(s.get("deliveryStatus").asString()).isEqualTo("dispatched");
        assertThat(s.get("deliveryMethod").asString()).isEqualTo("pickup"); // the sale's own plan is kept
        assertThat(s.get("deliveryVehicle").asString()).isEqualTo("UAX 123B");
    }

    @Test
    @DisplayName("stock transfer: pass from the source branch, recorded at the gate only")
    void stockTransfer() {
        String t = ok(200, "POST", "/inventory/transfers", Map.of("fromLocationId", location, "toLocationId",
                otherLocation, "mode", "transit", "responsibleById", adminId, "approvedById", adminId, "items",
                List.of(Map.of("productId", goods, "qtyRequested", 3)))).get("id").asString();
        JsonNode view = ok(200, "GET", "/gate-passes/source/stock_transfer/" + t, null);
        assertThat(view.get("source").get("branchId").asString()).isEqualTo(branch);
        assertThat(view.get("prefill").get("destination").asString()).isEqualTo("Yard " + sfx);
        JsonNode p = ok(201, "POST", "/gate-passes", issueBody("stock_transfer", t, "bearerType", "courier",
                "vehiclePlate", null));
        assertThat(p.get("branchId").asString()).isEqualTo(branch);
        assertThat(p.get("paymentState").asString()).isEqualTo("not_applicable");
        JsonNode out = ok(200, "POST", "/gate-passes/" + p.get("id").asString() + "/gate-out", null);
        assertThat(out.get("sourceUpdate").asString()).startsWith("Recorded at the gate only");
        JsonNode list = ok(200, "GET", "/gate-passes?sourceType=stock_transfer&q=" + p.get("number").asString(), null);
        assertThat(list.get("data").get(0).get("state").asString()).isEqualTo("used");
        assertThat(list.get("meta").get("total").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("PDF prints and counts copies; other organization cannot see the pass")
    void pdfAndTenancy() {
        String sale = cashSale(1, true);
        String id = ok(201, "POST", "/gate-passes", issueBody("cash_sale", sale)).get("id").asString();
        for (int i = 0; i < 2; i++) {
            HttpResponse<byte[]> pdf = downloadBytes("/gate-passes/" + id + "/pdf");
            assertThat(pdf.statusCode()).isEqualTo(200);
            assertThat(new String(pdf.body(), 0, 5)).isEqualTo("%PDF-");
        }
        JsonNode p = ok(200, "GET", "/gate-passes/" + id, null);
        assertThat(p.get("printCount").asInt()).isEqualTo(2);
        assertThat(p.get("events").valueStream().filter(e -> "printed".equals(e.get("type").asString())).count())
                .isEqualTo(2);
        assertThat(call("GET", "/gate-passes/" + id, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/gate-passes/source/cash_sale/" + sale, null, otherToken).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("concurrency: two simultaneous passes for the same goods — exactly one is issued")
    void concurrentIssue() throws Exception {
        String sale = cashSale(7, true);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(() -> as("POST", "/gate-passes", issueBody("cash_sale", sale)).status()));
            }
            List<Integer> statuses = new java.util.ArrayList<>();
            for (var f : results) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsOnly(201, 400);
            assertThat(statuses.stream().filter(st -> st == 201).count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("SELECT coalesce(sum(l.quantity), 0) FROM \"GatePassLine\" l JOIN \"GatePass\" p ON p.id ="
                + " l.\"gatePassId\" WHERE p.\"sourceId\" = ? AND p.status <> 'void'").param(sale)
                .query(java.math.BigDecimal.class).single()).isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("authorization: every gate pass route refuses a user without the permission")
    void authorization() {
        assertThat(assertAuthorizationMatrix(GatePassController.class)).isEqualTo(9);
    }
}
