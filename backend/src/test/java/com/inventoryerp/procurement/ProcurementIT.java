package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Procurement on Testcontainers Postgres: purchase requests, purchase orders (create / receive / pay / update /
 * cancel / delete / activate), goods receipts (draft / post / ad-hoc / reverse), landed cost (AVCO + FIFO lot),
 * debit notes (incl. return-to-vendor), GRNI report and three-way match. Ports {@code procurement-flow.spec.ts},
 * {@code purchase-bill-matching.spec.ts}, {@code supplier-payment-withholding.spec.ts} and the purchasing cases of
 * the {@code inv-*} audit suites; plus idempotency, authorization matrix and tenant isolation.
 */
class ProcurementIT extends ApiTestSupport {

    @Autowired
    StockService stock;

    String adminId;
    String warehouse;
    String supplier;
    String today = LocalDate.now().toString();

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        warehouse = ok(201, "POST", "/inventory/locations", Map.of("code", "PW-" + suffix(), "name", "Purchasing "
                + suffix(), "type", "warehouse")).get("id").asString();
        supplier = partner("Acme Supplies");
        // A cash purchase pays out of the buyer's open till (findOpen() without a register = the caller's session).
        long open = jdbc.sql("SELECT count(*) FROM \"CashSession\" WHERE \"organizationId\" = ? AND \"userId\" = ?"
                + " AND status = 'open'").params(demoOrg, adminId).query(Long.class).single();
        if (open == 0) {
            String register = UUID.randomUUID().toString();
            jdbc.sql("INSERT INTO \"CashRegister\" (id, \"organizationId\", code, name, \"defaultAccountId\", \"updatedAt\")"
                    + " VALUES (?, ?, ?, 'Procurement Till', ?, now())").params(register, demoOrg, "PT-" + suffix(),
                    mapped("default_cash")).update();
            jdbc.sql("INSERT INTO \"CashSession\" (id, \"organizationId\", \"cashRegisterId\", \"userId\", status,"
                    + " \"openingFloat\", \"updatedAt\") VALUES (?, ?, ?, ?, 'open', 1000000, now())")
                    .params(UUID.randomUUID().toString(), demoOrg, register, adminId).update();
        }
    }

    // ------------------------------------------------------------------ helpers

    static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String partner(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(id, demoOrg, "SUP-" + suffix(), name + " " + suffix())
                .update();
        return id;
    }

    String product(String costing, boolean batch) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                        + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"batchTracking\", \"updatedAt\") VALUES (?, ?, ?,"
                        + " ?, 'stockable', true, ?::\"CostingMethod\", 0, 0, ?, now())")
                .params(id, demoOrg, "PR-" + suffix(), "Item " + suffix(), costing, batch).update();
        return id;
    }

    String product() {
        return product("AVCO", false);
    }

    String mapped(String key) {
        return jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key = ?"
                + " AND \"deletedAt\" IS NULL").params(demoOrg, key).query(String.class).single();
    }

    /** Net movement (debits − credits) on one account. */
    BigDecimal balance(String accountId) {
        return jdbc.sql("SELECT COALESCE(SUM(debit - credit), 0) FROM \"JournalLine\" WHERE \"organizationId\" = ?"
                + " AND \"accountId\" = ?").params(demoOrg, accountId).query(BigDecimal.class).single();
    }

    BigDecimal onHand(String productId, String loc) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockItem\" WHERE \"productId\" = ?"
                + (loc == null ? " AND ?::text IS NULL" : " AND \"locationId\" = ?")).params(productId, loc)
                .query(BigDecimal.class).single();
    }

    Map<String, String> key() {
        return key(adminToken);
    }

    Map<String, String> key(String token) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    Res keyed(String method, String path, Object body) {
        return call(method, path, body, key());
    }

    JsonNode money(String method, String path, Object body) {
        Res r = keyed(method, path, body);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(200);
        return r.body();
    }

    static Map<String, Object> line(String productId, Object qty, Object price, Object taxRate) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", productId);
        m.put("description", "Coffee Beans");
        m.put("quantity", qty);
        m.put("unitPrice", price);
        if (taxRate != null) {
            m.put("taxRate", taxRate);
        }
        return m;
    }

    static Map<String, Object> rline(String productId, Object qty, Object unitCost) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", productId);
        m.put("description", "Coffee Beans");
        m.put("quantity", qty);
        if (unitCost != null) {
            m.put("unitCost", unitCost);
        }
        return m;
    }

    JsonNode createPo(String paymentType, String productId, Object qty, Object price, Object taxRate) {
        return ok(201, "POST", "/procurement/purchase-orders", Map.of("partnerId", supplier, "warehouseId", warehouse,
                "paymentType", paymentType, "currencyCode", "UGX", "lines",
                List.of(line(productId, qty, price, taxRate))));
    }

    JsonNode receive(String poId, List<Map<String, Object>> lines) {
        return money("POST", "/procurement/purchase-orders/" + poId + "/receive", Map.of("warehouseId", warehouse,
                "lines", lines));
    }

    JsonNode adhoc(String partnerId, String productId, Object qty, Object unitCost) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (partnerId != null) {
            body.put("partnerId", partnerId);
        }
        body.put("warehouseId", warehouse);
        body.put("receivedAt", java.time.Instant.now().toString());
        body.put("lines", List.of(rline(productId, qty, unitCost)));
        return ok(200, "POST", "/procurement/goods-receipts/adhoc", body);
    }

    JsonNode postBill(String partnerId, Map<String, Object> billLine) {
        JsonNode bill = ok(200, "POST", "/vendor-bills", Map.of("partnerId", partnerId, "issueDate", today, "lines",
                List.of(billLine)));
        ok(200, "POST", "/vendor-bills/" + bill.get("id").asString() + "/post", Map.of("override3WM", true,
                "overrideApproval", true));
        return bill;
    }

    String workflow(String entityType) {
        String wf = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"ApprovalWorkflow\" (id, \"organizationId\", name, \"entityType\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, now())").params(wf, demoOrg, "Gate " + entityType, entityType).update();
        jdbc.sql("INSERT INTO \"ApprovalStep\" (id, \"organizationId\", \"workflowId\", \"stepOrder\", name,"
                        + " \"approverPermissions\", \"updatedAt\") VALUES (?, ?, ?, 1, 'Manager', ?, now())")
                .params(UUID.randomUUID().toString(), demoOrg, wf, new String[] {entityType + ":approve"}).update();
        return wf;
    }

    void dropWorkflow(String wf) {
        jdbc.sql("UPDATE \"ApprovalWorkflow\" SET \"isActive\" = false WHERE id = ?").param(wf).update();
        jdbc.sql("UPDATE \"ApprovalRequest\" SET status = 'cancelled' WHERE \"workflowId\" = ? AND status = 'pending'")
                .param(wf).update();
    }

    <T> T asAdmin(Callable<T> body) {
        return TenantContext.run(TenantStore.user(demoOrg, adminId), () -> {
            try {
                return body.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    // ------------------------------------------------------------------ purchase requests

    @Test
    @DisplayName("purchase requests: create, list with order count, submit → approve, reject, delete drafts only")
    void purchaseRequests() {
        Res empty = as("POST", "/procurement/purchase-requests", Map.of("lines", List.of()));
        assertThat(empty.status()).isEqualTo(400);
        assertThat(empty.body().get("message").get(0).asString()).isEqualTo("lines must contain at least 1 elements");
        JsonNode pr = ok(201, "POST", "/procurement/purchase-requests", Map.of("description", "Beans for Q4",
                "neededBy", today, "lines", List.of(Map.of("description", "Beans", "quantity", 20,
                        "estimatedUnitPrice", 12.5))));
        String id = pr.get("id").asString();
        assertThat(pr.get("requestNumber").asString()).startsWith("PR-" + LocalDate.now().getYear() + "-");
        assertThat(pr.get("status").asString()).isEqualTo("draft");
        assertThat(pr.get("requestedById").asString()).isEqualTo(adminId);
        assertThat(pr.get("lines").get(0).get("estimatedUnitPrice").asString()).isEqualTo("12.5");
        assertThat(pr.get("lines").get(0).get("lineNumber").asInt()).isEqualTo(1);

        JsonNode list = ok(200, "GET", "/procurement/purchase-requests?status=draft", null);
        JsonNode mine = null;
        for (JsonNode r : list) {
            if (r.get("id").asString().equals(id)) {
                mine = r;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.get("_count").get("orders").asInt()).isZero();
        assertThat(ok(200, "GET", "/procurement/purchase-requests/" + id, null).get("orders")).isEmpty();

        Res approveDraft = as("PATCH", "/procurement/purchase-requests/" + id + "/approve", null);
        assertThat(approveDraft.body().get("message").asString()).isEqualTo("Cannot approve PR in status draft");
        assertThat(ok(200, "PATCH", "/procurement/purchase-requests/" + id + "/submit", null).get("status").asString())
                .isEqualTo("submitted");
        Res resubmit = as("PATCH", "/procurement/purchase-requests/" + id + "/submit", null);
        assertThat(resubmit.body().get("message").asString()).isEqualTo("Cannot submit PR in status submitted");
        JsonNode approved = ok(200, "PATCH", "/procurement/purchase-requests/" + id + "/approve", null);
        assertThat(approved.get("status").asString()).isEqualTo("approved");
        assertThat(approved.get("approvedById").asString()).isEqualTo(adminId);
        Res rejectApproved = as("PATCH", "/procurement/purchase-requests/" + id + "/reject", Map.of("reason", "x"));
        assertThat(rejectApproved.body().get("message").asString()).isEqualTo("Cannot reject PR in status approved");
        Res deleteApproved = as("DELETE", "/procurement/purchase-requests/" + id, null);
        assertThat(deleteApproved.body().get("message").asString()).isEqualTo("Only drafts can be deleted");

        // A PO raised from the request is counted on it.
        String product = product();
        ok(201, "POST", "/procurement/purchase-orders", Map.of("partnerId", supplier, "warehouseId", warehouse,
                "requestId", id, "lines", List.of(line(product, 1, 1, null))));
        assertThat(ok(200, "GET", "/procurement/purchase-requests/" + id, null).get("orders")).hasSize(1);

        String other = ok(201, "POST", "/procurement/purchase-requests", Map.of("lines", List.of(Map.of("description",
                "Cups", "quantity", 5)))).get("id").asString();
        JsonNode rejected = ok(200, "PATCH", "/procurement/purchase-requests/" + other + "/reject", null);
        assertThat(rejected.get("status").asString()).isEqualTo("rejected");
        assertThat(rejected.get("rejectedReason").asString()).isEmpty();
        String third = ok(201, "POST", "/procurement/purchase-requests", Map.of("lines", List.of(Map.of("description",
                "Lids", "quantity", 5)))).get("id").asString();
        assertThat(ok(200, "DELETE", "/procurement/purchase-requests/" + third, null).get("ok").asBoolean()).isTrue();
        Res gone = as("PATCH", "/procurement/purchase-requests/" + third + "/submit", null);
        assertThat(gone.status()).isEqualTo(404);
        assertThat(gone.body().get("message").asString()).isEqualTo("Purchase request not found");
        Res missing = as("GET", "/procurement/purchase-requests/" + UUID.randomUUID(), null);
        assertThat(missing.status()).isEqualTo(200);
        assertThat(missing.raw().body()).isEmpty();
    }

    // ------------------------------------------------------------------ procurement-flow.spec.ts

    @Test
    @DisplayName("credit PO receive: Dr Inventory + Dr Input Tax / Cr AP, GRNI nets to zero (procurement-flow)")
    void creditPurchase() {
        String product = product();
        BigDecimal inv0 = balance(mapped("stock_valuation"));
        BigDecimal grni0 = balance(mapped("grni_accrued"));
        BigDecimal ap0 = balance(mapped("accounts_payable"));
        BigDecimal tax0 = balance(mapped("tax_receivable"));

        JsonNode po = createPo("credit", product, 10, 100, 18);
        assertThat(po.get("status").asString()).isEqualTo("active");
        assertThat(po.get("orderNumber").asString()).startsWith("PO-" + LocalDate.now().getYear() + "-");
        assertThat(po.get("subtotal").asString()).isEqualTo("1000");
        assertThat(po.get("taxAmount").asString()).isEqualTo("180");
        assertThat(po.get("totalAmount").asString()).isEqualTo("1180");
        assertThat(po.get("paymentStatus").asString()).isEqualTo("not_paid");
        assertThat(po.get("lines").get(0).get("subtotal").asString()).isEqualTo("1000");

        JsonNode res = receive(po.get("id").asString(), List.of(rline(product, 10, 100)));
        assertThat(res.get("status").asString()).isEqualTo("received");
        assertThat(res.get("grn").get("receiptNumber").asString()).startsWith("GRN-");
        assertThat(onHand(product, warehouse)).isEqualByComparingTo("10");
        assertThat(balance(mapped("stock_valuation")).subtract(inv0)).isEqualByComparingTo("1000");
        assertThat(balance(mapped("grni_accrued")).subtract(grni0)).isEqualByComparingTo("0");
        assertThat(balance(mapped("tax_receivable")).subtract(tax0)).isEqualByComparingTo("180");
        assertThat(balance(mapped("accounts_payable")).subtract(ap0)).isEqualByComparingTo("-1180");

        JsonNode detail = ok(200, "GET", "/procurement/purchase-orders/" + po.get("id").asString(), null);
        assertThat(detail.get("receipts")).hasSize(1);
        assertThat(detail.get("receipts").get(0).get("status").asString()).isEqualTo("posted");
        assertThat(detail.get("lines").get(0).get("receivedQuantity").asString()).isEqualTo("10");
        assertThat(detail.get("version").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("cash PO receive also relieves AP against the till: AP flat, cash −200, paid (procurement-flow)")
    void cashPurchase() {
        String product = product();
        BigDecimal ap0 = balance(mapped("accounts_payable"));
        String cashAccount = jdbc.sql("SELECT r.\"defaultAccountId\" FROM \"CashSession\" s JOIN \"CashRegister\" r"
                + " ON r.id = s.\"cashRegisterId\" WHERE s.\"organizationId\" = ? AND s.\"userId\" = ? AND s.status = 'open'"
                + " LIMIT 1").params(demoOrg, adminId).query(String.class).single();
        BigDecimal cash0 = balance(cashAccount);
        JsonNode po = createPo("cash", product, 5, 40, null);
        assertThat(po.get("paymentStatus").isNull()).isTrue();
        receive(po.get("id").asString(), List.of(rline(product, 5, 40)));
        assertThat(balance(mapped("accounts_payable")).subtract(ap0)).isEqualByComparingTo("0");
        assertThat(balance(cashAccount).subtract(cash0)).isEqualByComparingTo("-200");
        JsonNode settled = ok(200, "GET", "/procurement/purchase-orders/" + po.get("id").asString(), null);
        assertThat(settled.get("paymentStatus").asString()).isEqualTo("paid");
        assertThat(settled.get("totalPaid").asString()).isEqualTo("200");
        assertThat(settled.get("payments").get(0).get("reference").asString()).startsWith("auto:GRN GRN-");
        long payOuts = jdbc.sql("SELECT count(*) FROM \"CashMovement\" WHERE \"organizationId\" = ? AND reason = ?")
                .params(demoOrg, "Cash purchase PO " + po.get("orderNumber").asString()).query(Long.class).single();
        assertThat(payOuts).isEqualTo(1);
    }

    @Test
    @DisplayName("receive advances partially_received → received; over-receipt and fully received are refused")
    void receiptStatusAndGuards() {
        String product = product();
        String poId = createPo("credit", product, 10, 10, null).get("id").asString();
        assertThat(receive(poId, List.of(rline(product, 4, 10))).get("status").asString()).isEqualTo("partially_received");
        assertThat(receive(poId, List.of(rline(product, 6, 10))).get("status").asString()).isEqualTo("received");
        Res again = keyed("POST", "/procurement/purchase-orders/" + poId + "/receive", Map.of("warehouseId", warehouse,
                "lines", List.of(rline(product, 1, 10))));
        assertThat(again.status()).isEqualTo(400);
        assertThat(again.body().get("message").asString()).isEqualTo("PO is fully received");

        String over = createPo("credit", product, 3, 25, null).get("id").asString();
        BigDecimal qty0 = onHand(product, warehouse);
        BigDecimal inv0 = balance(mapped("stock_valuation"));
        Res r = keyed("POST", "/procurement/purchase-orders/" + over + "/receive", Map.of("warehouseId", warehouse,
                "lines", List.of(rline(product, 5, 25))));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).isEqualTo(
                "Over-receiving PO line \"Coffee Beans\": ordered 3, already received 0, trying to receive 5");
        // One transaction: no partial stock, no dangling journal, PO untouched.
        assertThat(onHand(product, warehouse)).isEqualByComparingTo(qty0);
        assertThat(balance(mapped("stock_valuation"))).isEqualByComparingTo(inv0);
        assertThat(ok(200, "GET", "/procurement/purchase-orders/" + over, null).get("status").asString())
                .isEqualTo("active");
        long grns = jdbc.sql("SELECT count(*) FROM \"GoodsReceiptNote\" WHERE \"purchaseOrderId\" = ?").param(over)
                .query(Long.class).single();
        assertThat(grns).isZero();

        // INV-012: a receipt line for a product not on the PO is refused.
        String offPo = product();
        Res off = keyed("POST", "/procurement/purchase-orders/" + over + "/receive", Map.of("warehouseId", warehouse,
                "lines", List.of(rline(product, 1, 25), Map.of("productId", offPo, "description", "Off PO",
                        "quantity", 1, "unitCost", 10))));
        assertThat(off.body().get("message").asString()).isEqualTo("Receipt line \"Off PO\" is not on PO "
                + ok(200, "GET", "/procurement/purchase-orders/" + over, null).get("orderNumber").asString());
        assertThat(onHand(offPo, warehouse)).isEqualByComparingTo("0");
        Res badLine = keyed("POST", "/procurement/purchase-orders/" + over + "/receive", Map.of("warehouseId",
                warehouse, "lines", List.of(Map.of("purchaseOrderLineId", "nope", "description", "x", "quantity", 1))));
        assertThat(badLine.body().get("message").asString()).isEqualTo("PO line nope not found");
        Res badWh = keyed("POST", "/procurement/purchase-orders/" + over + "/receive", Map.of("warehouseId",
                UUID.randomUUID().toString(), "lines", List.of(rline(product, 1, 25))));
        assertThat(badWh.status()).isEqualTo(404);
        assertThat(badWh.body().get("message").asString()).isEqualTo("Warehouse not found");
    }

    @Test
    @DisplayName("receive is idempotent: key required, a replay returns the same GRN and moves stock once")
    void receiveIdempotency() {
        String product = product();
        String poId = createPo("credit", product, 10, 10, null).get("id").asString();
        Res noKey = as("POST", "/procurement/purchase-orders/" + poId + "/receive", Map.of("warehouseId", warehouse,
                "lines", List.of(rline(product, 2, 10))));
        assertThat(noKey.status()).isEqualTo(400);
        assertThat(noKey.body().get("message").asString()).isEqualTo(
                "An Idempotency-Key header is required for this operation so a retry can never charge twice.");
        Map<String, String> h = key();
        Map<String, Object> body = Map.of("warehouseId", warehouse, "lines", List.of(rline(product, 2, 10)));
        Res first = call("POST", "/procurement/purchase-orders/" + poId + "/receive", body, h);
        Res replay = call("POST", "/procurement/purchase-orders/" + poId + "/receive", body, h);
        assertThat(first.status()).as(first.raw().body()).isEqualTo(200);
        assertThat(replay.body().get("grn").get("id").asString()).isEqualTo(first.body().get("grn").get("id").asString());
        assertThat(onHand(product, warehouse)).isEqualByComparingTo("2");
        // Unit cost defaults to the ordered price when omitted.
        JsonNode noCost = receive(poId, List.of(rline(product, 1, null)));
        assertThat(noCost.get("grn").get("lines").get(0).get("unitCost").asString()).isEqualTo("10");
    }

    @Test
    @DisplayName("credit PO pay: Dr AP / Cr Bank, overpay refused, cash PO refused, concurrent pays settle once")
    void payments() {
        String product = product();
        String poId = createPo("credit", product, 1, 500, null).get("id").asString();
        receive(poId, List.of(rline(product, 1, 500)));
        BigDecimal ap0 = balance(mapped("accounts_payable"));
        BigDecimal bank0 = balance(mapped("default_bank"));
        Res noKey = as("POST", "/procurement/purchase-orders/" + poId + "/pay", Map.of("amount", 500));
        assertThat(noKey.status()).isEqualTo(400);
        Res over = keyed("POST", "/procurement/purchase-orders/" + poId + "/pay", Map.of("amount", 999, "method", "bank"));
        assertThat(over.body().get("message").asString()).isEqualTo("Payment of 999 exceeds remaining balance of 500");
        JsonNode pay = money("POST", "/procurement/purchase-orders/" + poId + "/pay", Map.of("amount", 200,
                "method", "bank", "reference", "CHQ-1"));
        assertThat(pay.get("amount").asString()).isEqualTo("200");
        assertThat(pay.get("reference").asString()).isEqualTo("CHQ-1");
        assertThat(ok(200, "GET", "/procurement/purchase-orders/" + poId, null).get("paymentStatus").asString())
                .isEqualTo("partial");
        money("POST", "/procurement/purchase-orders/" + poId + "/pay", Map.of());
        assertThat(balance(mapped("accounts_payable")).subtract(ap0)).isEqualByComparingTo("500");
        assertThat(balance(mapped("default_bank")).subtract(bank0)).isEqualByComparingTo("-500");
        JsonNode paid = ok(200, "GET", "/procurement/purchase-orders/" + poId, null);
        assertThat(paid.get("paymentStatus").asString()).isEqualTo("paid");
        assertThat(paid.get("totalPaid").asString()).isEqualTo("500");
        Res done = keyed("POST", "/procurement/purchase-orders/" + poId + "/pay", Map.of());
        assertThat(done.body().get("message").asString()).isEqualTo("This purchase order is already fully paid");
        Res tooSmall = keyed("POST", "/procurement/purchase-orders/" + poId + "/pay", Map.of("amount", 0));
        assertThat(tooSmall.body().get("message").get(0).asString()).isEqualTo("amount must not be less than 0.01");

        String cashPo = createPo("cash", product, 1, 1, null).get("id").asString();
        Res cash = keyed("POST", "/procurement/purchase-orders/" + cashPo + "/pay", Map.of("amount", 1));
        assertThat(cash.body().get("message").asString()).isEqualTo("Only credit purchases can accept manual payments");

        // inv-readiness P0-2: concurrent full-balance payments on one credit PO — exactly one succeeds.
        String race = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"PurchaseOrder\" (id, \"organizationId\", \"orderNumber\", \"partnerId\", \"paymentType\","
                + " status, \"totalAmount\", \"totalPaid\", \"updatedAt\") VALUES (?, ?, ?, ?, 'credit', 'received', 100, 0,"
                + " now())").params(race, demoOrg, "RDY-PO-" + suffix(), supplier).update();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Res>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> keyed("POST", "/procurement/purchase-orders/" + race + "/pay",
                        Map.of("method", "bank"))));
            }
            int okCount = 0;
            for (Future<Res> f : futures) {
                if (f.get().status() == 200) {
                    okCount++;
                }
            }
            assertThat(okCount).isEqualTo(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            pool.shutdown();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM \"PurchasePayment\" WHERE \"purchaseOrderId\" = ?").param(race)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"sourceType\" ="
                + " 'purchase_order' AND \"sourceId\" = ?").params(demoOrg, race).query(Long.class).single()).isEqualTo(1);
        // P0-2: the database refuses a payment row that overpays a credit PO.
        jdbc.sql("INSERT INTO \"PurchasePayment\" (id, \"organizationId\", \"purchaseOrderId\", amount, \"updatedAt\")"
                + " VALUES (?, ?, ?, 0, now())").params(UUID.randomUUID().toString(), demoOrg, race).update();
        try {
            jdbc.sql("INSERT INTO \"PurchasePayment\" (id, \"organizationId\", \"purchaseOrderId\", amount, \"updatedAt\")"
                    + " VALUES (?, ?, ?, 60, now())").params(UUID.randomUUID().toString(), demoOrg, race).update();
            throw new AssertionError("overpaying payment row was accepted");
        } catch (org.springframework.dao.DataAccessException e) {
            assertThat(e.getMostSpecificCause().getMessage()).contains("exceeds purchase order total");
        }
    }

    // ------------------------------------------------------------------ PO admin

    @Test
    @DisplayName("PO list / filters / search 500 parity, update (active only), cancel, delete, activate gate")
    void purchaseOrderAdmin() {
        String product = product();
        JsonNode po = createPo("credit", product, 2, 3, null);
        String id = po.get("id").asString();
        JsonNode page = ok(200, "GET", "/procurement/purchase-orders?partnerId=" + supplier + "&status=active"
                + "&paymentType=credit&pageSize=2", null);
        assertThat(page.get("meta").get("pageSize").asInt()).isEqualTo(2);
        assertThat(page.get("data").get(0).get("partner").get("id").asString()).isEqualTo(supplier);
        assertThat(page.get("data").get(0).get("partner").size()).isEqualTo(2);
        // DefaultValuePipe treats Number("x") = NaN as missing (verified on Nest).
        assertThat(ok(200, "GET", "/procurement/purchase-orders?page=x", null).get("meta").get("page").asInt())
                .isEqualTo(1);
        // Nest builds `contains` on enum columns → Prisma validation error → 500.
        assertThat(as("GET", "/procurement/purchase-orders?search=PO", null).status()).isEqualTo(500);
        JsonNode dated = ok(200, "GET", "/procurement/purchase-orders?partnerId=" + supplier + "&dateFrom=" + today
                + "&dateTo=" + today, null);
        assertThat(dated.get("meta").get("total").asInt()).isPositive();

        JsonNode updated = ok(200, "PATCH", "/procurement/purchase-orders/" + id, Map.of("notes", "Call first"));
        assertThat(updated.get("notes").asString()).isEqualTo("Call first");
        assertThat(updated.get("version").asInt()).isEqualTo(1);
        assertThat(updated.get("expectedDeliveryDate").isNull()).isTrue();
        JsonNode cancelled = ok(201, "POST", "/procurement/purchase-orders/" + id + "/cancel", Map.of("reason",
                "Supplier out of stock"));
        assertThat(cancelled.get("status").asString()).isEqualTo("cancelled");
        assertThat(cancelled.get("notes").asString()).isEqualTo("Supplier out of stock");
        assertThat(as("POST", "/procurement/purchase-orders/" + id + "/cancel", null).body().get("message").asString())
                .isEqualTo("Cannot cancel PO in status cancelled");
        assertThat(as("PATCH", "/procurement/purchase-orders/" + id, Map.of("notes", "x")).body().get("message")
                .asString()).isEqualTo("Only active POs can be updated");
        Res recvCancelled = keyed("POST", "/procurement/purchase-orders/" + id + "/receive", Map.of("warehouseId",
                warehouse, "lines", List.of(rline(product, 1, 3))));
        assertThat(recvCancelled.body().get("message").asString()).isEqualTo("Cannot receive against a cancelled PO");
        assertThat(as("DELETE", "/procurement/purchase-orders/" + id, null).body().get("message").asString())
                .isEqualTo("Cannot delete a received or cancelled PO");
        assertThat(as("POST", "/procurement/purchase-orders/" + id + "/activate", null).body().get("message").asString())
                .isEqualTo("Only draft POs can be activated, current status: cancelled");

        String toDelete = createPo("cash", product, 1, 1, null).get("id").asString();
        assertThat(ok(200, "DELETE", "/procurement/purchase-orders/" + toDelete, null).get("ok").asBoolean()).isTrue();
        assertThat(as("GET", "/procurement/purchase-orders/" + toDelete, null).raw().body()).isEmpty();
        Res badSupplier = as("POST", "/procurement/purchase-orders", Map.of("partnerId", UUID.randomUUID().toString(),
                "warehouseId", warehouse, "lines", List.of(line(product, 1, 1, null))));
        assertThat(badSupplier.status()).isEqualTo(404);
        assertThat(badSupplier.body().get("message").asString()).isEqualTo("Supplier not found");
        Res noWarehouse = as("POST", "/procurement/purchase-orders", Map.of("partnerId", supplier, "lines",
                List.of(line(product, 1, 1, null))));
        assertThat(noWarehouse.body().get("message").asString()).isEqualTo("Warehouse is required");
        Res badQty = as("POST", "/procurement/purchase-orders", Map.of("partnerId", supplier, "warehouseId", warehouse,
                "lines", List.of(line(product, 0, 1, null))));
        assertThat(badQty.body().get("message").get(0).asString()).isEqualTo("lines.0.quantity must not be less than 0.001");

        // Approval gate: a purchase_order workflow keeps the PO in draft; activate is refused while pending.
        String wf = workflow("purchase_order");
        try {
            JsonNode draft = createPo("credit", product, 1, 10, null);
            assertThat(draft.get("status").asString()).isEqualTo("draft");
            String draftId = draft.get("id").asString();
            Res blocked = as("POST", "/procurement/purchase-orders/" + draftId + "/activate", null);
            assertThat(blocked.status()).isEqualTo(403);
            assertThat(blocked.body().get("message").asString()).isEqualTo(
                    "This purchase order is awaiting approval. Approve or reject the request first.");
            Res recvDraft = keyed("POST", "/procurement/purchase-orders/" + draftId + "/receive", Map.of("warehouseId",
                    warehouse, "lines", List.of(rline(product, 1, 10))));
            assertThat(recvDraft.body().get("message").asString()).isEqualTo("PO is in status \"draft\" — cannot receive");
            dropWorkflow(wf);
            JsonNode active = ok(201, "POST", "/procurement/purchase-orders/" + draftId + "/activate", null);
            assertThat(active.get("status").asString()).isEqualTo("active");
            assertThat(active.get("approvedById").asString()).isEqualTo(adminId);
        } finally {
            dropWorkflow(wf);
        }
    }

    // ------------------------------------------------------------------ goods receipts

    @Test
    @DisplayName("GRN: draft is inert, post stocks + accrues GRNI, list/detail, ad-hoc reversal returns stock")
    void goodsReceipts() {
        String product = product();
        BigDecimal grni0 = balance(mapped("grni_accrued"));
        JsonNode draft = ok(201, "POST", "/procurement/goods-receipts", Map.of("partnerId", supplier, "warehouseId",
                warehouse, "notes", "Delivery note 42", "lines", List.of(rline(product, 12, 10))));
        String id = draft.get("id").asString();
        assertThat(draft.get("status").asString()).isEqualTo("draft");
        assertThat(draft.get("lines").get(0).get("unitCost").asString()).isEqualTo("10");
        assertThat(onHand(product, warehouse)).isEqualByComparingTo("0");
        Res noKey = as("PATCH", "/procurement/goods-receipts/" + id + "/post", null);
        assertThat(noKey.status()).isEqualTo(400);

        // inv-p0 INV-003: concurrent posts of one draft GRN receive once.
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Res>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> keyed("PATCH", "/procurement/goods-receipts/" + id + "/post", null)));
            }
            for (Future<Res> f : futures) {
                statuses.add(f.get().status());
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            pool.shutdown();
        }
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"referenceType\" = 'goods_receipt'"
                + " AND \"referenceId\" = ?").param(id).query(Long.class).single()).isEqualTo(1);
        assertThat(onHand(product, warehouse)).isEqualByComparingTo("12");
        // Ad-hoc: GRNI stays open (credit) for the vendor bill.
        assertThat(balance(mapped("grni_accrued")).subtract(grni0)).isEqualByComparingTo("-120");
        Res repost = keyed("PATCH", "/procurement/goods-receipts/" + id + "/post", null);
        assertThat(repost.body().get("message").asString()).isEqualTo("Only draft receipts can be posted");

        JsonNode detail = ok(200, "GET", "/procurement/goods-receipts/" + id, null);
        assertThat(detail.get("status").asString()).isEqualTo("posted");
        assertThat(detail.get("warehouse").get("id").asString()).isEqualTo(warehouse);
        assertThat(detail.get("lines").get(0).get("product").get("id").asString()).isEqualTo(product);
        assertThat(detail.get("partner").get("id").asString()).isEqualTo(supplier);
        assertThat(detail.get("order").isNull()).isTrue();
        JsonNode list = ok(200, "GET", "/procurement/goods-receipts?search=" + draft.get("receiptNumber").asString()
                + "&status=posted", null);
        assertThat(list.get("meta").get("total").asInt()).isEqualTo(1);
        assertThat(list.get("data").get(0).get("lines")).hasSize(1);

        Res noReason = keyed("PATCH", "/procurement/goods-receipts/" + id + "/reverse", Map.of("reason", " "));
        assertThat(noReason.body().get("message").asString()).isEqualTo("A reason is required to reverse a goods receipt");
        JsonNode reversed = money("PATCH", "/procurement/goods-receipts/" + id + "/reverse", Map.of("reason",
                "wrong site"));
        assertThat(reversed.get("status").asString()).isEqualTo("reversed");
        assertThat(reversed.get("reversalReason").asString()).isEqualTo("wrong site");
        assertThat(onHand(product, warehouse)).isEqualByComparingTo("0");
        assertThat(balance(mapped("grni_accrued")).subtract(grni0)).isEqualByComparingTo("0");
        Res twice = keyed("PATCH", "/procurement/goods-receipts/" + id + "/reverse", Map.of("reason", "again"));
        assertThat(twice.status()).isEqualTo(409);
        assertThat(twice.body().get("message").asString()).isEqualTo("Goods receipt " + draft.get("receiptNumber")
                .asString() + " is already reversed");

        String fresh = ok(201, "POST", "/procurement/goods-receipts", Map.of("warehouseId", warehouse, "lines",
                List.of(rline(product, 1, 1)))).get("id").asString();
        Res draftReverse = keyed("PATCH", "/procurement/goods-receipts/" + fresh + "/reverse", Map.of("reason", "x"));
        assertThat(draftReverse.body().get("message").asString()).isEqualTo("Only posted goods receipts can be reversed");
        Res noWh = as("POST", "/procurement/goods-receipts", Map.of("lines", List.of(rline(product, 1, 1))));
        assertThat(noWh.body().get("message").asString()).isEqualTo("Warehouse required");
        Res noLines = as("POST", "/procurement/goods-receipts", Map.of("warehouseId", warehouse));
        assertThat(noLines.body().get("message").asString()).isEqualTo("At least one line required");
        String ghost = UUID.randomUUID().toString();
        Res missingProduct = keyed("PATCH", "/procurement/goods-receipts/" + ok(201, "POST",
                "/procurement/goods-receipts", Map.of("warehouseId", warehouse, "lines", List.of(rline(ghost, 1, 1))))
                .get("id").asString() + "/post", null);
        assertThat(missingProduct.status()).isEqualTo(404);
        assertThat(missingProduct.body().get("message").asString()).isEqualTo("Product(s) not found: " + ghost);
    }

    @Test
    @DisplayName("GRN on a PO: post advances the PO and vouchers AP; reversal rolls the PO back, nets GL + AP (INV-010)")
    void poLinkedReceiptReversal() {
        String product = product();
        BigDecimal sv0 = balance(mapped("stock_valuation"));
        JsonNode po = createPo("credit", product, 10, 12, null);
        String poId = po.get("id").asString();
        String draft = ok(201, "POST", "/procurement/goods-receipts", Map.of("purchaseOrderId", poId, "partnerId",
                supplier, "warehouseId", warehouse, "lines", List.of(Map.of("purchaseOrderLineId",
                        po.get("lines").get(0).get("id").asString(), "productId", product, "description", "P2",
                        "quantity", 6, "unitCost", 12)))).get("id").asString();
        money("PATCH", "/procurement/goods-receipts/" + draft + "/post", null);
        assertThat(onHand(product, warehouse)).isEqualByComparingTo("6");
        JsonNode mid = ok(200, "GET", "/procurement/purchase-orders/" + poId, null);
        assertThat(mid.get("status").asString()).isEqualTo("partially_received");

        JsonNode reversed = money("PATCH", "/procurement/goods-receipts/" + draft + "/reverse", Map.of("reason",
                "supplier delivered to wrong site"));
        assertThat(reversed.get("status").asString()).isEqualTo("reversed");
        assertThat(onHand(product, warehouse)).isEqualByComparingTo("0");
        assertThat(balance(mapped("stock_valuation"))).isEqualByComparingTo(sv0);
        JsonNode after = ok(200, "GET", "/procurement/purchase-orders/" + poId, null);
        assertThat(after.get("lines").get(0).get("receivedQuantity").asString()).isEqualTo("0");
        assertThat(after.get("status").asString()).isEqualTo("active");
        BigDecimal apNet = jdbc.sql("SELECT COALESCE(SUM(l.debit - l.credit), 0) FROM \"JournalLine\" l JOIN"
                + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"accountId\" = ? AND e.\"organizationId\" = ?"
                + " AND e.\"sourceId\" = ?").params(mapped("accounts_payable"), demoOrg, draft).query(BigDecimal.class)
                .single();
        assertThat(apNet).isEqualByComparingTo("0");

        // A paid PO's receipt cannot be reversed.
        String paidPo = createPo("cash", product, 1, 5, null).get("id").asString();
        String grn = receive(paidPo, List.of(rline(product, 1, 5))).get("grn").get("id").asString();
        Res refused = keyed("PATCH", "/procurement/goods-receipts/" + grn + "/reverse", Map.of("reason", "x"));
        assertThat(refused.body().get("message").asString()).endsWith(
                "already has payments — return the goods with a debit note instead");
    }

    @Test
    @DisplayName("GRN approval gate: post refused under a policy, ad-hoc lands as draft, PO receive only drafts")
    void receiptApprovalGate() {
        String product = product();
        String wf = workflow("goods_receipt");
        try {
            String draft = ok(201, "POST", "/procurement/goods-receipts", Map.of("warehouseId", warehouse, "lines",
                    List.of(rline(product, 3, 10)))).get("id").asString();
            Res post = keyed("PATCH", "/procurement/goods-receipts/" + draft + "/post", null);
            assertThat(post.status()).isEqualTo(400);
            assertThat(post.body().get("message").asString()).startsWith("Goods receipt requires approval before posting");
            Res pending = keyed("PATCH", "/procurement/goods-receipts/" + draft + "/post", null);
            assertThat(pending.body().get("message").asString()).startsWith("Goods receipt is pending approval (request ");
            assertThat(onHand(product, warehouse)).isEqualByComparingTo("0");
            JsonNode adhoc = adhoc(null, product, 2, 10);
            assertThat(adhoc.get("status").asString()).isEqualTo("draft");

            // inv-readiness P1-3: PO receive under the policy only drafts the GRN.
            JsonNode po = createPo("credit", product, 5, 10, null);
            JsonNode res = receive(po.get("id").asString(), List.of(Map.of("purchaseOrderLineId",
                    po.get("lines").get(0).get("id").asString(), "productId", product, "description", "x",
                    "quantity", 5)));
            assertThat(res.get("approvalRequired").asBoolean()).isTrue();
            assertThat(res.get("grn").get("status").asString()).isEqualTo("draft");
            assertThat(res.get("status").asString()).isEqualTo("active");
            assertThat(onHand(product, warehouse)).isEqualByComparingTo("0");
            JsonNode after = ok(200, "GET", "/procurement/purchase-orders/" + po.get("id").asString(), null);
            assertThat(after.get("lines").get(0).get("receivedQuantity").asString()).isEqualTo("0");
        } finally {
            dropWorkflow(wf);
        }

        // inv-p1 N3: a clerk without goods_receipt:post only creates a draft.
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                        + " VALUES (?, ?, ?, false, ?, now())")
                .params(role, demoOrg, "Clerk-" + role, new String[] {"goods_receipt:create"}).update();
        String email = "clerk-" + suffix() + "@demo.test";
        createUser(demoOrg, email, "Clerk@12345", role);
        String clerk = login("DEMO", email, "Clerk@12345");
        Res clerkRes = call("POST", "/procurement/goods-receipts/adhoc", Map.of("warehouseId", warehouse, "lines",
                List.of(rline(product, 2, 10))), clerk);
        assertThat(clerkRes.status()).as(clerkRes.raw().body()).isEqualTo(200);
        assertThat(clerkRes.body().get("status").asString()).isEqualTo("draft");
        assertThat(onHand(product, warehouse)).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------ landed cost (inv-audit3 P2-02)

    @Test
    @DisplayName("landed cost AVCO: 6 of 10 on hand → 60% into the average, 40% to COGS; repost refused")
    void landedCostAvco() {
        String product = product();
        String grn = adhoc(supplier, product, 10, 100).get("id").asString();
        asAdmin(() -> stock.issue(StockMove.of(product, warehouse, new BigDecimal("4")).source("audit", suffix())));
        BigDecimal sv0 = balance(mapped("stock_valuation"));
        BigDecimal cogs0 = balance(mapped("cogs"));
        BigDecimal ap0 = balance(mapped("accounts_payable"));

        Res draftGrn = as("POST", "/procurement/landed-costs", Map.of("goodsReceiptId", ok(201, "POST",
                "/procurement/goods-receipts", Map.of("warehouseId", warehouse, "lines", List.of(rline(product, 1, 1))))
                .get("id").asString(), "creditAccountId", mapped("accounts_payable"), "charges",
                List.of(Map.of("kind", "freight", "amount", 1))));
        assertThat(draftGrn.body().get("message").asString()).matches(
                "Landed cost can only be added to a posted goods receipt \\(GRN-.* is draft\\)");
        Res badAccount = as("POST", "/procurement/landed-costs", Map.of("goodsReceiptId", grn, "creditAccountId",
                UUID.randomUUID().toString(), "charges", List.of(Map.of("kind", "freight", "amount", 1))));
        assertThat(badAccount.body().get("message").asString()).isEqualTo("Credit account not found");
        Res badKind = as("POST", "/procurement/landed-costs", Map.of("goodsReceiptId", grn, "creditAccountId",
                mapped("accounts_payable"), "charges", List.of(Map.of("kind", "tip", "amount", 1))));
        assertThat(badKind.body().get("message").get(0).asString()).isEqualTo(
                "charges.0.kind must be one of the following values: freight, duty, insurance, handling, other");

        JsonNode draft = ok(200, "POST", "/procurement/landed-costs", Map.of("goodsReceiptId", grn, "creditAccountId",
                mapped("accounts_payable"), "charges", List.of(Map.of("kind", "freight", "amount", 150),
                        Map.of("kind", "duty", "amount", 50))));
        assertThat(draft.get("code").asString()).startsWith("LC-");
        assertThat(draft.get("status").asString()).isEqualTo("draft");
        assertThat(draft.get("allocationMethod").asString()).isEqualTo("value");
        assertThat(draft.get("totalAmount").asString()).isEqualTo("200");
        assertThat(draft.get("charges")).hasSize(2);
        String id = draft.get("id").asString();
        JsonNode posted = ok(200, "POST", "/procurement/landed-costs/" + id + "/post", null);
        assertThat(posted.get("status").asString()).isEqualTo("posted");
        assertThat(posted.get("capitalizedAmount").asString()).isEqualTo("120");
        assertThat(posted.get("expensedAmount").asString()).isEqualTo("80");
        assertThat(posted.get("journalEntryId").isNull()).isFalse();
        JsonNode alloc = posted.get("allocations").get(0);
        assertThat(alloc.get("onHandQty").asString()).isEqualTo("6");
        assertThat(alloc.get("unitCostBefore").asString()).isEqualTo("100");
        assertThat(alloc.get("unitCostAfter").asString()).isEqualTo("120");
        BigDecimal avg = jdbc.sql("SELECT \"runningAverageCost\" FROM \"StockItem\" WHERE \"productId\" = ? AND"
                + " \"locationId\" = ?").params(product, warehouse).query(BigDecimal.class).single();
        assertThat(avg).isEqualByComparingTo("120");
        assertThat(balance(mapped("stock_valuation")).subtract(sv0)).isEqualByComparingTo("120");
        assertThat(balance(mapped("cogs")).subtract(cogs0)).isEqualByComparingTo("80");
        assertThat(balance(mapped("accounts_payable")).subtract(ap0)).isEqualByComparingTo("-200");
        Res again = as("POST", "/procurement/landed-costs/" + id + "/post", null);
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.body().get("message").asString()).isEqualTo(
                "Landed cost is not a draft (already posted or cancelled)");
        Res cancelPosted = as("POST", "/procurement/landed-costs/" + id + "/cancel", null);
        assertThat(cancelPosted.body().get("message").asString()).isEqualTo("Only a draft landed cost can be cancelled");
        assertThat(ok(200, "GET", "/procurement/landed-costs?goodsReceiptId=" + grn, null)).hasSize(1);
        assertThat(ok(200, "GET", "/procurement/landed-costs/" + id, null).get("allocations")).hasSize(1);
        Res missing = as("GET", "/procurement/landed-costs/" + UUID.randomUUID(), null);
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.body().get("message").asString()).isEqualTo("Landed cost not found");

        String other = ok(200, "POST", "/procurement/landed-costs", Map.of("goodsReceiptId", grn, "creditAccountId",
                mapped("accounts_payable"), "charges", List.of(Map.of("kind", "other", "amount", 5)))).get("id").asString();
        assertThat(ok(200, "POST", "/procurement/landed-costs/" + other + "/cancel", null).get("status").asString())
                .isEqualTo("cancelled");
    }

    @Test
    @DisplayName("landed cost FIFO lot: only the receipt lot is re-costed; a posted landed cost blocks GRN reversal")
    void landedCostFifoLot() {
        String product = product("FIFO", true);
        String grn = ok(200, "POST", "/procurement/goods-receipts/adhoc", Map.of("warehouseId", warehouse, "lines",
                List.of(Map.of("productId", product, "description", "goods", "quantity", 10, "unitCost", 50,
                        "batchNumber", "LC-" + suffix())))).get("id").asString();
        asAdmin(() -> stock.issue(StockMove.of(product, warehouse, new BigDecimal("5")).source("audit", suffix())));
        String draft = ok(200, "POST", "/procurement/landed-costs", Map.of("goodsReceiptId", grn, "creditAccountId",
                mapped("grni_accrued"), "allocationMethod", "quantity", "charges", List.of(Map.of("kind", "freight",
                        "amount", 100)))).get("id").asString();
        JsonNode posted = ok(200, "POST", "/procurement/landed-costs/" + draft + "/post", null);
        assertThat(posted.get("capitalizedAmount").asString()).isEqualTo("50");
        BigDecimal lot = jdbc.sql("SELECT \"unitCost\" FROM \"InventoryBatch\" WHERE \"productId\" = ? AND"
                + " \"locationId\" = ?").params(product, warehouse).query(BigDecimal.class).single();
        assertThat(lot).isEqualByComparingTo("60");
        StockMove.Issued issued = asAdmin(() -> stock.issue(StockMove.of(product, warehouse, BigDecimal.ONE)
                .source("audit", suffix())));
        assertThat(issued.totalValue()).isEqualByComparingTo("60");
        Res reverse = keyed("PATCH", "/procurement/goods-receipts/" + grn + "/reverse", Map.of("reason",
                "wrong supplier"));
        assertThat(reverse.body().get("message").asString()).endsWith(
                "has posted landed cost — the capitalised freight/duty would be stranded; return the goods with a debit"
                        + " note instead");
    }

    // ------------------------------------------------------------------ debit notes

    @Test
    @DisplayName("debit notes: outbound Dr AR / Cr revenue; inbound plain note is unbalanced (Nest parity); cancel")
    void debitNotes() {
        String customer = partner("Customer");
        JsonNode note = ok(201, "POST", "/procurement/debit-notes", Map.of("direction", "outbound", "partnerId", customer,
                "reason", "price_adjustment", "lines", List.of(Map.of("description", "Under-billed", "quantity", 2,
                        "unitPrice", 25))));
        String id = note.get("id").asString();
        assertThat(note.get("noteNumber").asString()).startsWith("DBN-OUT-" + LocalDate.now().getYear() + "-");
        assertThat(note.get("totalAmount").asString()).isEqualTo("50");
        assertThat(note.get("customFields").size()).isZero();
        BigDecimal ar0 = balance(mapped("accounts_receivable"));
        JsonNode posted = ok(200, "PATCH", "/procurement/debit-notes/" + id + "/post", null);
        assertThat(posted.get("status").asString()).isEqualTo("posted");
        assertThat(balance(mapped("accounts_receivable")).subtract(ar0)).isEqualByComparingTo("50");
        assertThat(as("PATCH", "/procurement/debit-notes/" + id + "/post", null).body().get("message").asString())
                .isEqualTo("Cannot post debit note in status posted");
        assertThat(as("PATCH", "/procurement/debit-notes/" + id + "/cancel", null).body().get("message").asString())
                .isEqualTo("Cannot cancel a posted debit note; reverse with a credit note");
        JsonNode list = ok(200, "GET", "/procurement/debit-notes?direction=outbound&status=posted", null);
        assertThat(list.get(0).get("partner").get("name").asString()).startsWith("Customer");
        assertThat(ok(200, "GET", "/procurement/debit-notes/" + id, null).get("partner").get("id").asString())
                .isEqualTo(customer);

        JsonNode inbound = ok(201, "POST", "/procurement/debit-notes", Map.of("direction", "inbound", "partnerId",
                supplier, "reason", "overcharge", "lines", List.of(Map.of("description", "Fee", "quantity", 1,
                        "unitPrice", 10))));
        assertThat(inbound.get("noteNumber").asString()).startsWith("DBN-IN-");
        // Nest credits AP and the expense line alike, so PostingService refuses the entry.
        Res unbalanced = as("PATCH", "/procurement/debit-notes/" + inbound.get("id").asString() + "/post", null);
        assertThat(unbalanced.status()).isEqualTo(400);
        assertThat(unbalanced.body().get("message").asString()).startsWith("Unbalanced entry");
        assertThat(ok(200, "PATCH", "/procurement/debit-notes/" + inbound.get("id").asString() + "/cancel", null)
                .get("status").asString()).isEqualTo("cancelled");
        Res badPartner = as("POST", "/procurement/debit-notes", Map.of("direction", "inbound", "partnerId",
                UUID.randomUUID().toString(), "reason", "other", "lines", List.of(Map.of("description", "x", "quantity",
                        1, "unitPrice", 1))));
        assertThat(badPartner.body().get("message").asString()).isEqualTo("Partner not found");
        Res badDirection = as("POST", "/procurement/debit-notes", Map.of("direction", "sideways", "partnerId", supplier,
                "reason", "other", "lines", List.of(Map.of("description", "x", "quantity", 1, "unitPrice", 1))));
        assertThat(badDirection.body().get("message").get(0).asString()).isEqualTo(
                "direction must be one of the following values: outbound, inbound");
        assertThat(as("GET", "/procurement/debit-notes/" + UUID.randomUUID(), null).raw().body()).isEmpty();
    }

    @Test
    @DisplayName("return to vendor: stock leaves at cost, Dr AP / Cr Stock Valuation + Cr return variance")
    void returnToVendor() {
        String product = product();
        adhoc(supplier, product, 10, 4);
        BigDecimal sv0 = balance(mapped("stock_valuation"));
        BigDecimal ap0 = balance(mapped("accounts_payable"));
        BigDecimal income0 = balance(mapped("stock_adjustment_income"));
        JsonNode note = ok(201, "POST", "/procurement/debit-notes", Map.of("direction", "inbound", "partnerId", supplier,
                "reason", "returned_goods", "locationId", warehouse, "lines", List.of(Map.of("productId", product,
                        "description", "Damaged beans", "quantity", 3, "unitPrice", 5))));
        assertThat(note.get("customFields").get("locationId").asString()).isEqualTo(warehouse);
        JsonNode posted = ok(200, "PATCH", "/procurement/debit-notes/" + note.get("id").asString() + "/post", null);
        assertThat(posted.get("status").asString()).isEqualTo("posted");
        assertThat(onHand(product, warehouse)).isEqualByComparingTo("7");
        assertThat(balance(mapped("accounts_payable")).subtract(ap0)).isEqualByComparingTo("15");
        assertThat(balance(mapped("stock_valuation")).subtract(sv0)).isEqualByComparingTo("-12");
        assertThat(balance(mapped("stock_adjustment_income")).subtract(income0)).isEqualByComparingTo("-3");
        String ledgerType = jdbc.sql("SELECT type::text FROM \"InventoryLedger\" WHERE \"productId\" = ? AND"
                + " \"quantityChange\" < 0").param(product).query(String.class).single();
        assertThat(ledgerType).isEqualTo("return_to_supplier");
    }

    // ------------------------------------------------------------------ bill ↔ receipt (purchase-bill-matching)

    @Test
    @DisplayName("bill matching: partial bill leaves the rest open; PPV on price gap; cancel releases the quantity")
    void billMatching() {
        String sup = partner("Matching Supplier");
        String grni = mapped("grni_accrued");
        String ap = mapped("accounts_payable");
        String ppv = mapped("purchase_price_variance");

        String partial = product();
        BigDecimal g0 = balance(grni);
        adhoc(sup, partial, 100, 10);
        assertThat(balance(grni).subtract(g0)).isEqualByComparingTo("-1000");
        BigDecimal ap0 = balance(ap);
        postBill(sup, Map.of("productId", partial, "description", "Partial goods", "quantity", 60, "unitPrice", 10));
        assertThat(onHand(partial, null)).isEqualByComparingTo("100");
        assertThat(balance(grni).subtract(g0)).isEqualByComparingTo("-400");
        postBill(sup, Map.of("productId", partial, "description", "Partial goods (balance)", "quantity", 40,
                "unitPrice", 10));
        assertThat(onHand(partial, null)).isEqualByComparingTo("100");
        assertThat(balance(grni).subtract(g0)).isEqualByComparingTo("0");
        assertThat(balance(ap).subtract(ap0)).isEqualByComparingTo("-1000");

        String milk = product();
        adhoc(sup, milk, 50, 4);
        BigDecimal g1 = balance(grni);
        BigDecimal ppv1 = balance(ppv);
        BigDecimal ap1 = balance(ap);
        postBill(sup, Map.of("productId", milk, "description", "Milk", "quantity", 50, "unitPrice", 4.5));
        assertThat(onHand(milk, null)).isEqualByComparingTo("50");
        assertThat(balance(grni).subtract(g1)).isEqualByComparingTo("200");
        assertThat(balance(ppv).subtract(ppv1)).isEqualByComparingTo("25");
        assertThat(balance(ap).subtract(ap1)).isEqualByComparingTo("-225");

        String sugar = product();
        adhoc(sup, sugar, 10, 5);
        BigDecimal g2 = balance(grni);
        JsonNode bill = postBill(sup, Map.of("productId", sugar, "description", "Sugar", "quantity", 10, "unitPrice", 5));
        assertThat(balance(grni).subtract(g2)).isEqualByComparingTo("50");
        ok(200, "POST", "/vendor-bills/" + bill.get("id").asString() + "/cancel", null);
        assertThat(balance(grni)).isEqualByComparingTo(g2);
        assertThat(jdbc.sql("SELECT \"billedQuantity\" FROM \"GoodsReceiptLine\" WHERE \"productId\" = ?").param(sugar)
                .query(BigDecimal.class).single()).isEqualByComparingTo("0");
        assertThat(jdbc.sql("SELECT count(*) FROM \"VendorBillReceiptMatch\" WHERE \"vendorBillId\" = ?")
                .param(bill.get("id").asString()).query(Long.class).single()).isZero();
        postBill(sup, Map.of("productId", sugar, "description", "Sugar (corrected)", "quantity", 10, "unitPrice", 5));
        assertThat(onHand(sugar, null)).isEqualByComparingTo("10");
        assertThat(balance(grni).subtract(g2)).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("audit H1: a PO receipt then its bill neither re-receives nor double-books AP; GRN → bill clears GRNI")
    void receiptThenBill() {
        String sup = partner("H1 Supplier");
        String beans = product();
        JsonNode po = ok(201, "POST", "/procurement/purchase-orders", Map.of("partnerId", sup, "warehouseId", warehouse,
                "paymentType", "credit", "lines", List.of(line(beans, 100, 10, 0))));
        receive(po.get("id").asString(), List.of(rline(beans, 100, 10)));
        assertThat(onHand(beans, null)).isEqualByComparingTo("100");
        BigDecimal sv = balance(mapped("stock_valuation"));
        BigDecimal ap = balance(mapped("accounts_payable"));
        postBill(sup, Map.of("productId", beans, "description", "Beans", "quantity", 100, "unitPrice", 10));
        assertThat(onHand(beans, null)).isEqualByComparingTo("100");
        assertThat(balance(mapped("stock_valuation"))).isEqualByComparingTo(sv);
        assertThat(balance(mapped("accounts_payable"))).isEqualByComparingTo(ap);

        String milk = product();
        BigDecimal g0 = balance(mapped("grni_accrued"));
        adhoc(sup, milk, 50, 4);
        assertThat(balance(mapped("grni_accrued")).subtract(g0)).isEqualByComparingTo("-200");
        postBill(sup, Map.of("productId", milk, "description", "Milk", "quantity", 50, "unitPrice", 4));
        assertThat(onHand(milk, null)).isEqualByComparingTo("50");
        assertThat(balance(mapped("grni_accrued")).subtract(g0)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("audit H18: a bill priced in the purchase unit lands as base units (10 cases × 24)")
    void billInPurchaseUnit() {
        String cat = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"UomCategory\" (id, \"organizationId\", name, \"updatedAt\") VALUES (?, ?, ?, now())")
                .params(cat, demoOrg, "AUD-UNITS-" + suffix()).update();
        String each = UUID.randomUUID().toString();
        String box = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"UnitOfMeasure\" (id, \"organizationId\", \"categoryId\", code, name, factor, \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, 'Each', 1, now())").params(each, demoOrg, cat, "EA-" + suffix()).update();
        jdbc.sql("INSERT INTO \"UnitOfMeasure\" (id, \"organizationId\", \"categoryId\", code, name, factor, \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, 'Case of 24', 24, now())").params(box, demoOrg, cat, "CASE-" + suffix()).update();
        jdbc.sql("UPDATE \"UomCategory\" SET \"referenceUomId\" = ? WHERE id = ?").params(each, cat).update();
        String soda = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                        + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"uomId\", \"purchaseUomId\", \"salesUomId\","
                        + " \"updatedAt\") VALUES (?, ?, ?, 'Soda', 'stockable', true, 'AVCO', 0, 0, ?, ?, ?, now())")
                .params(soda, demoOrg, "H18-" + suffix(), each, box, each).update();
        postBill(partner("H18"), Map.of("productId", soda, "description", "Soda (cases)", "quantity", 10, "unitPrice",
                240));
        assertThat(onHand(soda, null)).isEqualByComparingTo("240");
    }

    // ------------------------------------------------------------------ supplier-payment-withholding

    @Test
    @DisplayName("withholding: AP relieved in full, bank pays the net, WHT payable credited; WHT ≥ payment refused")
    void supplierPaymentWithholding() {
        String sup = partner("WHT Supplier");
        JsonNode bill = postBill(sup, Map.of("description", "Consultancy", "quantity", 1, "unitPrice", 1000));
        BigDecimal ap0 = balance(mapped("accounts_payable"));
        BigDecimal bank0 = balance(mapped("default_bank"));
        BigDecimal wht0 = balance(mapped("withholding_payable"));
        JsonNode pay = money("POST", "/supplier-payments", Map.of("partnerId", sup, "paymentDate", today, "amount", 1000,
                "paymentMethod", "bank", "withholdingAmount", 60, "allocations", List.of(Map.of("documentId",
                        bill.get("id").asString(), "amount", 1000))));
        assertThat(balance(mapped("accounts_payable")).subtract(ap0)).isEqualByComparingTo("1000");
        assertThat(balance(mapped("default_bank")).subtract(bank0)).isEqualByComparingTo("-940");
        assertThat(balance(mapped("withholding_payable")).subtract(wht0)).isEqualByComparingTo("-60");
        assertThat(pay.get("withholdingAmount").asString()).isEqualTo("60");
        assertThat(ok(200, "GET", "/vendor-bills/" + bill.get("id").asString(), null).get("amountResidual").asString())
                .isEqualTo("0");

        JsonNode small = postBill(sup, Map.of("description", "Consultancy", "quantity", 1, "unitPrice", 500));
        Res refused = keyed("POST", "/supplier-payments", Map.of("partnerId", sup, "paymentDate", today, "amount", 500,
                "paymentMethod", "bank", "withholdingAmount", 500, "allocations", List.of(Map.of("documentId",
                        small.get("id").asString(), "amount", 500))));
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body().get("message").asString()).containsIgnoringCase("less than the payment amount");
    }

    // ------------------------------------------------------------------ GRNI + three-way match

    @Test
    @DisplayName("GRNI report: un-vouchered ad-hoc receipts are open, PO receipts are vouchered; buckets")
    void grniReport() {
        String sup = partner("GRNI Supplier");
        String product = product();
        adhoc(sup, product, 4, 25);
        JsonNode po = ok(201, "POST", "/procurement/purchase-orders", Map.of("partnerId", sup, "warehouseId", warehouse,
                "paymentType", "credit", "lines", List.of(line(product, 2, 10, null))));
        receive(po.get("id").asString(), List.of(rline(product, 2, 10)));
        // asOf a minute ahead: receipt timestamps come from the database clock, which may run ahead of the JVM's.
        String asOf = java.time.Instant.now().plusSeconds(60).toString();
        JsonNode report = ok(200, "GET", "/procurement/reports/grni?partnerId=" + sup + "&asOf=" + asOf, null);
        assertThat(report.get("subledgerOpenValue").asString()).isEqualTo("100");
        assertThat(report.get("voucheredOpenValue").asString()).isEqualTo("20");
        assertThat(report.get("positions")).hasSize(2);
        JsonNode open = report.get("positions").get(0);
        assertThat(open.get("vouchered").asBoolean()).isFalse();
        assertThat(open.get("openQuantity").asString()).isEqualTo("4");
        assertThat(open.get("ageDays").asInt()).isZero();
        assertThat(open.get("partnerName").asString()).startsWith("GRNI Supplier");
        assertThat(report.get("ageBuckets").get(0).get("value").asString()).isEqualTo("100");
        assertThat(report.get("ageBuckets").get(0).get("count").asInt()).isEqualTo(1);
        assertThat(report.has("glBalance")).isTrue();
        JsonNode aged = ok(200, "GET", "/procurement/reports/grni?partnerId=" + sup + "&asOf=" + asOf
                + "&minAgeDays=5", null);
        assertThat(aged.get("positions")).isEmpty();
    }

    @Test
    @DisplayName("three-way match: recompute per PO line, list with the order, validate a linked bill")
    void threeWayMatch() {
        String product = product();
        JsonNode po = createPo("credit", product, 10, 10, null);
        String poId = po.get("id").asString();
        JsonNode pending = ok(201, "POST", "/procurement/three-way-match/recompute/" + poId, null);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).get("status").asString()).isEqualTo("pending");
        receive(poId, List.of(rline(product, 10, 10)));
        JsonNode blocked = ok(201, "POST", "/procurement/three-way-match/recompute/" + poId, null);
        assertThat(blocked.get(0).get("status").asString()).isEqualTo("blocked");
        assertThat(blocked.get(0).get("receivedQuantity").asString()).isEqualTo("10");
        JsonNode list = ok(200, "GET", "/procurement/three-way-match?purchaseOrderId=" + poId + "&status=blocked", null);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("order").get("orderNumber").asString()).isEqualTo(po.get("orderNumber").asString());
        assertThat(ok(201, "POST", "/procurement/three-way-match/recompute/" + UUID.randomUUID(), null)).isEmpty();

        JsonNode bill = ok(200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", today,
                "purchaseOrderIds", List.of(poId), "lines", List.of(Map.of("productId", product, "description", "Beans",
                        "quantity", 10, "unitPrice", 10))));
        JsonNode validation = ok(201, "POST", "/procurement/three-way-match/validate-bill/" + bill.get("id").asString(),
                null);
        assertThat(validation.has("ok")).isTrue();
        assertThat(validation.get("matches")).hasSize(1);
        JsonNode none = ok(201, "POST", "/procurement/three-way-match/validate-bill/" + UUID.randomUUID(), null);
        assertThat(none.get("ok").asBoolean()).isTrue();
        assertThat(none.get("matches")).isEmpty();
    }

    // ------------------------------------------------------------------ isolation + authz

    @Test
    @DisplayName("tenant isolation: another organization cannot read or act on this org's procurement documents")
    void tenantIsolation() {
        String product = product();
        JsonNode po = createPo("credit", product, 1, 1, null);
        String poId = po.get("id").asString();
        String grn = adhoc(supplier, product, 1, 1).get("id").asString();
        String pr = ok(201, "POST", "/procurement/purchase-requests", Map.of("lines", List.of(Map.of("description", "x",
                "quantity", 1)))).get("id").asString();
        Res poRead = call("GET", "/procurement/purchase-orders/" + poId, null, otherToken);
        assertThat(poRead.status()).isEqualTo(200);
        assertThat(poRead.raw().body()).isEmpty();
        assertThat(call("GET", "/procurement/goods-receipts/" + grn, null, otherToken).raw().body()).isEmpty();
        assertThat(call("GET", "/procurement/purchase-requests/" + pr, null, otherToken).raw().body()).isEmpty();
        assertThat(call("PATCH", "/procurement/purchase-requests/" + pr + "/submit", null, otherToken).status())
                .isEqualTo(404);
        assertThat(call("POST", "/procurement/purchase-orders/" + poId + "/cancel", null, otherToken).status())
                .isEqualTo(404);
        assertThat(call("GET", "/procurement/purchase-orders", null, otherToken).body().get("meta").get("total").asInt())
                .isZero();
        assertThat(call("GET", "/procurement/goods-receipts", null, otherToken).body().get("data")).isEmpty();
        assertThat(call("GET", "/procurement/debit-notes", null, otherToken).body()).isEmpty();
        assertThat(call("GET", "/procurement/landed-costs", null, otherToken).body()).isEmpty();
        assertThat(call("GET", "/procurement/three-way-match?purchaseOrderId=" + poId, null, otherToken).body()).isEmpty();
        assertThat(call("POST", "/procurement/three-way-match/recompute/" + poId, null, otherToken).body()).isEmpty();
        Res lc = call("POST", "/procurement/landed-costs", Map.of("goodsReceiptId", grn, "creditAccountId", "x",
                "charges", List.of(Map.of("kind", "freight", "amount", 1))), otherToken);
        assertThat(lc.status()).isEqualTo(404);
        assertThat(lc.body().get("message").asString()).isEqualTo("Goods receipt not found");
    }

    @Test
    @DisplayName("authorization: every procurement route answers 401 without a token and 403 without its permission")
    void authorization() {
        assertThat(assertAuthorizationMatrix("com.inventoryerp.procurement")).isEqualTo(70);
    }
}
