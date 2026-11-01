package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.procurement.application.PurchaseOrdersService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * ADR-PROCUREMENT-001 wave 1 — the P0 / P1 financial-integrity fixes from
 * {@code docs/audit/procurement-current-state-audit.md}. Each test reproduces the defect's scenario and asserts the
 * corrected ledger / stock / tenancy outcome (they failed before the fix).
 */
class ProcurementIntegrityIT extends ApiTestSupport {

    @Autowired
    PurchaseOrdersService orders;

    String adminId;
    String warehouse;
    String supplier;
    String today = LocalDate.now().toString();

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        warehouse = ok(201, "POST", "/inventory/locations", Map.of("code", "IW-" + suffix(), "name", "Integrity "
                + suffix(), "type", "warehouse")).get("id").asString();
        supplier = partner("Integrity Supplies");
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

    String product(String type, boolean tracked, String costing) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                        + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?,"
                        + " ?::\"ProductType\", ?, ?::\"CostingMethod\", 0, 0, now())")
                .params(id, demoOrg, "IP-" + suffix(), "Item " + suffix(), type, tracked, costing).update();
        return id;
    }

    String stockProduct() {
        return product("stockable", true, "AVCO");
    }

    String mapped(String key) {
        return jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key = ?"
                + " AND \"deletedAt\" IS NULL").params(demoOrg, key).query(String.class).single();
    }

    /** Net (debit − credit) on an account for the entries of one source document. */
    BigDecimal net(String accountId, String sourceType, String sourceId) {
        return jdbc.sql("SELECT COALESCE(SUM(l.debit - l.credit), 0) FROM \"JournalLine\" l JOIN \"JournalEntry\" e"
                + " ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = ? AND l.\"accountId\" = ?"
                + " AND e.\"sourceType\" = ? AND e.\"sourceId\" = ?").params(demoOrg, accountId, sourceType, sourceId)
                .query(BigDecimal.class).single();
    }

    BigDecimal balance(String accountId) {
        return jdbc.sql("SELECT COALESCE(SUM(debit - credit), 0) FROM \"JournalLine\" WHERE \"organizationId\" = ?"
                + " AND \"accountId\" = ?").params(demoOrg, accountId).query(BigDecimal.class).single();
    }

    BigDecimal onHand(String productId) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockItem\" WHERE \"productId\" = ?").param(productId)
                .query(BigDecimal.class).single();
    }

    Map<String, String> key() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
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

    JsonNode createPo(Map<String, Object> extra, String productId, Object qty, Object price) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("productId", productId);
        line.put("description", "Cement");
        line.put("quantity", qty);
        line.put("unitPrice", price);
        Map<String, Object> body = new LinkedHashMap<>(Map.of("partnerId", supplier, "warehouseId", warehouse,
                "paymentType", "credit", "currencyCode", "UGX", "lines", List.of(line)));
        body.putAll(extra);
        return ok(201, "POST", "/procurement/purchase-orders", body);
    }

    JsonNode receive(String poId, String productId, Object qty) {
        return money("POST", "/procurement/purchase-orders/" + poId + "/receive", Map.of("warehouseId", warehouse,
                "lines", List.of(Map.of("productId", productId, "description", "Cement", "quantity", qty))));
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

    // ------------------------------------------------------------------ P0-1

    @Test
    @DisplayName("P0-1: a STANDARD-costed receipt vouchers AP at the supplier price and leaves GRNI at zero")
    void standardCostReceiptClearsGrni() {
        String product = product("stockable", true, "STANDARD");
        jdbc.sql("INSERT INTO \"ProductStandardCost\" (id, \"organizationId\", \"productId\", status, \"effectiveFrom\","
                + " \"materialCost\", \"totalCost\") VALUES (?, ?, ?, 'active', CURRENT_DATE, 100, 100)")
                .params(UUID.randomUUID().toString(), demoOrg, product).update();
        String poId = createPo(Map.of(), product, 10, 110).get("id").asString();
        String grnId = receive(poId, product, 10).get("grn").get("id").asString();

        assertThat(net(mapped("grni_accrued"), "goods_receipt", grnId)).isEqualByComparingTo("0");
        assertThat(net(mapped("accounts_payable"), "goods_receipt", grnId)).isEqualByComparingTo("-1100");
        assertThat(net(mapped("stock_valuation"), "goods_receipt", grnId)).isEqualByComparingTo("1000");
    }

    // ------------------------------------------------------------------ P0-2

    @Test
    @DisplayName("P0-2: a purchase order with a goods receipt cannot be hard-deleted (cancel instead)")
    void receivedOrderCannotBeDeleted() {
        String product = stockProduct();
        String poId = createPo(Map.of(), product, 4, 10).get("id").asString();
        receive(poId, product, 1);
        Res del = keyed("DELETE", "/procurement/purchase-orders/" + poId, null);
        assertThat(del.status()).isEqualTo(400);
        assertThat(del.body().get("message").asString())
                .isEqualTo("Cannot delete a purchase order that has goods receipts, payments or bills — cancel it instead");
        assertThat(jdbc.sql("SELECT count(*) FROM \"PurchaseOrder\" WHERE id = ?").param(poId).query(Long.class).single())
                .isEqualTo(1);
        // A fresh order with nothing against it can still be deleted.
        String fresh = createPo(Map.of(), product, 1, 1).get("id").asString();
        assertThat(keyed("DELETE", "/procurement/purchase-orders/" + fresh, null).status()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ P0-3

    @Test
    @DisplayName("P0-3: concurrent posts of one return-to-vendor note ship the goods back exactly once")
    void debitNoteConcurrentPostIssuesStockOnce() throws Exception {
        String product = stockProduct();
        String poId = createPo(Map.of(), product, 5, 10).get("id").asString();
        receive(poId, product, 5);
        BigDecimal before = onHand(product);
        JsonNode note = ok(201, "POST", "/procurement/debit-notes", Map.of("direction", "inbound", "partnerId", supplier,
                "reason", "returned_goods", "locationId", warehouse, "lines", List.of(Map.of("productId", product,
                        "description", "Cement", "quantity", 2, "unitPrice", 10))));
        String id = note.get("id").asString();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<Res>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> keyed("PATCH", "/procurement/debit-notes/" + id + "/post", null)));
            }
            int ok = 0;
            for (Future<Res> f : futures) {
                Res r = f.get();
                if (r.status() == 200) {
                    ok++;
                } else {
                    assertThat(r.status()).isIn(400, 409);
                }
            }
            assertThat(ok).isEqualTo(1);
        } finally {
            pool.shutdown();
        }
        assertThat(before.subtract(onHand(product))).isEqualByComparingTo("2");
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"sourceType\" = 'debit_note' AND \"sourceId\" = ?")
                .param(id).query(Long.class).single()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ P0-4

    @Test
    @DisplayName("P0-4: a PO pays only what its receipts vouchered — never ahead of a receipt or before approval")
    void paymentCappedAtVoucheredAmount() {
        String product = stockProduct();
        String poId = createPo(Map.of(), product, 10, 50).get("id").asString();
        Res early = keyed("POST", "/procurement/purchase-orders/" + poId + "/pay", Map.of("amount", 100,
                "method", "bank"));
        assertThat(early.status()).isEqualTo(400);
        assertThat(early.body().get("message").asString()).startsWith("Nothing has been received on this purchase order");
        receive(poId, product, 4);
        Res over = keyed("POST", "/procurement/purchase-orders/" + poId + "/pay", Map.of("amount", 201, "method",
                "bank"));
        assertThat(over.body().get("message").asString()).isEqualTo("Payment of 201 exceeds remaining balance of 200");
        BigDecimal ap0 = balance(mapped("accounts_payable"));
        money("POST", "/procurement/purchase-orders/" + poId + "/pay", Map.of("method", "bank"));
        assertThat(balance(mapped("accounts_payable")).subtract(ap0)).isEqualByComparingTo("200");

        String wf = workflow("purchase_order");
        try {
            String draft = createPo(Map.of(), product, 1, 1).get("id").asString();
            Res unapproved = keyed("POST", "/procurement/purchase-orders/" + draft + "/pay", Map.of("method", "bank"));
            assertThat(unapproved.body().get("message").asString())
                    .isEqualTo("Cannot pay a purchase order that is not approved yet");
        } finally {
            dropWorkflow(wf);
        }
    }

    @Test
    @DisplayName("AP aging carries the payable a PO receipt vouchered, less PO payments; the matched bill adds nothing")
    void agingIncludesReceiptVouchers() {
        String own = partner("Aging Supplier " + suffix());
        String product = stockProduct();
        String poId = createPo(Map.of("partnerId", own), product, 10, 50).get("id").asString();
        receive(poId, product, 4);
        assertThat(agingTotal(own)).isEqualByComparingTo("200");
        money("POST", "/procurement/purchase-orders/" + poId + "/pay", Map.of("amount", 120, "method", "bank"));
        assertThat(agingTotal(own)).isEqualByComparingTo("80");
        jdbc.sql("DELETE FROM \"ReportApAgingSnapshot\" WHERE \"organizationId\" = ?").param(demoOrg).update();
        Res rebuilt = as("POST", "/reports/accounting/rebuild-snapshots", null);
        assertThat(rebuilt.status()).as(rebuilt.raw().body()).isEqualTo(201);
        assertThat(agingTotal(own)).isEqualByComparingTo("80");
    }

    BigDecimal agingTotal(String partnerId) {
        for (JsonNode p : ok(200, "GET", "/reports/ap/aging", null).get("partners")) {
            if (partnerId.equals(p.get("partnerId").asString())) {
                return new BigDecimal(p.get("total").asString());
            }
        }
        return BigDecimal.ZERO;
    }

    // ------------------------------------------------------------------ P0-5

    @Test
    @DisplayName("P0-5: a foreign-currency PO values stock, GRNI and AP in the functional currency")
    void foreignCurrencyReceiptConverts() {
        String functional = jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(demoOrg)
                .query(String.class).single();
        String foreign = "USD".equalsIgnoreCase(functional) ? "EUR" : "USD";
        String product = stockProduct();
        String poId = createPo(Map.of("currencyCode", foreign, "exchangeRate", 3700), product, 10, 2).get("id")
                .asString();
        String grnId = receive(poId, product, 10).get("grn").get("id").asString();
        assertThat(net(mapped("stock_valuation"), "goods_receipt", grnId)).isEqualByComparingTo("74000");
        assertThat(net(mapped("accounts_payable"), "goods_receipt", grnId)).isEqualByComparingTo("-74000");
        assertThat(net(mapped("grni_accrued"), "goods_receipt", grnId)).isEqualByComparingTo("0");
        // Paying 20 (PO currency) relieves 74,000 of functional AP.
        BigDecimal ap0 = balance(mapped("accounts_payable"));
        JsonNode pay = money("POST", "/procurement/purchase-orders/" + poId + "/pay", Map.of("method", "bank"));
        assertThat(pay.get("amount").asString()).isEqualTo("20");
        assertThat(balance(mapped("accounts_payable")).subtract(ap0)).isEqualByComparingTo("74000");
    }

    // ------------------------------------------------------------------ P0-6

    @Test
    @DisplayName("P0-6: another organization's purchase request / order ids are refused on create")
    void foreignIdsRefused() {
        String product = stockProduct();
        String foreignPr = call("POST", "/procurement/purchase-requests", Map.of("lines", List.of(Map.of("description",
                "x", "quantity", 1))), otherToken).body().get("id").asString();
        Map<String, Object> line = Map.of("productId", product, "description", "x", "quantity", 1, "unitPrice", 1);
        Res po = as("POST", "/procurement/purchase-orders", Map.of("partnerId", supplier, "warehouseId", warehouse,
                "requestId", foreignPr, "lines", List.of(line)));
        assertThat(po.status()).isEqualTo(404);
        assertThat(po.body().get("message").asString()).isEqualTo("Purchase request not found");

        String foreignPo = UUID.randomUUID().toString();
        String otherPartner = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\") VALUES"
                + " (?, ?, ?, 'Other', true, now())").params(otherPartner, otherOrg, "OP-" + suffix()).update();
        jdbc.sql("INSERT INTO \"PurchaseOrder\" (id, \"organizationId\", \"orderNumber\", \"partnerId\", status,"
                + " \"updatedAt\") VALUES (?, ?, ?, ?, 'active', now())").params(foreignPo, otherOrg, "OPO-" + suffix(),
                otherPartner).update();
        Res grn = as("POST", "/procurement/goods-receipts", Map.of("purchaseOrderId", foreignPo, "warehouseId",
                warehouse, "lines", List.of(Map.of("description", "x", "quantity", 1))));
        assertThat(grn.status()).isEqualTo(404);
        assertThat(grn.body().get("message").asString()).isEqualTo("Purchase order not found");
    }

    // ------------------------------------------------------------------ P1-2

    @Test
    @DisplayName("P1-2: a rejected PO cannot be activated; an approved one activates as the approver")
    void approvalDecisionsGovernActivation() {
        String product = stockProduct();
        String wf = workflow("purchase_order");
        try {
            String rejected = createPo(Map.of(), product, 1, 5).get("id").asString();
            jdbc.sql("UPDATE \"ApprovalRequest\" SET status = 'rejected' WHERE \"entityType\" = 'purchase_order'"
                    + " AND \"entityId\" = ?").param(rejected).update();
            Res act = as("POST", "/procurement/purchase-orders/" + rejected + "/activate", null);
            assertThat(act.status()).isEqualTo(403);
            assertThat(act.body().get("message").asString())
                    .isEqualTo("This purchase order's approval was rejected — it cannot be activated");

            String approved = createPo(Map.of(), product, 1, 5).get("id").asString();
            boolean first = TenantContext.run(TenantStore.user(demoOrg, adminId),
                    () -> orders.activateApproved(approved, adminId));
            boolean replay = TenantContext.run(TenantStore.user(demoOrg, adminId),
                    () -> orders.activateApproved(approved, adminId));
            assertThat(first).isTrue();
            assertThat(replay).isFalse();
            JsonNode po = ok(200, "GET", "/procurement/purchase-orders/" + approved, null);
            assertThat(po.get("status").asString()).isEqualTo("active");
            assertThat(po.get("approvedById").asString()).isEqualTo(adminId);
        } finally {
            dropWorkflow(wf);
        }
    }

    // ------------------------------------------------------------------ P1-3 / P1-1

    @Test
    @DisplayName("P1-3 / P1-1: reversing a receipt keeps a cancelled PO cancelled and drops out of the three-way match")
    void reversalKeepsCancelledAndThreeWayMatchCountsPostedOnly() {
        String product = stockProduct();
        JsonNode created = createPo(Map.of(), product, 2, 10);
        String poId = created.get("id").asString();
        String grnId = receive(poId, product, 1).get("grn").get("id").asString();
        JsonNode match = ok(201, "POST", "/procurement/three-way-match/recompute/" + poId, null);
        assertThat(new BigDecimal(match.get(0).get("receivedQuantity").asString())).isEqualByComparingTo("1");
        ok(201, "POST", "/procurement/purchase-orders/" + poId + "/cancel", Map.of("reason", "supplier closed"));
        money("PATCH", "/procurement/goods-receipts/" + grnId + "/reverse", Map.of("reason", "wrong delivery"));
        assertThat(ok(200, "GET", "/procurement/purchase-orders/" + poId, null).get("status").asString())
                .isEqualTo("cancelled");
        JsonNode after = ok(201, "POST", "/procurement/three-way-match/recompute/" + poId, null);
        assertThat(new BigDecimal(after.get(0).get("receivedQuantity").asString())).isEqualByComparingTo("0");
        assertThat(after.get(0).get("status").asString()).isEqualTo("pending");
        // The write routes of the match need the approve permission (they upsert rows).
        assertThat(call("POST", "/procurement/three-way-match/recompute/" + poId, null, weakToken()).status())
                .isEqualTo(403);
    }

    // ------------------------------------------------------------------ P1-12

    @Test
    @DisplayName("P1-12: a service line on a PO is never put into stock nor vouchered at receipt")
    void serviceLinesAreNotStocked() {
        String service = product("service", false, "AVCO");
        String poId = createPo(Map.of(), service, 1, 300).get("id").asString();
        String grnId = receive(poId, service, 1).get("grn").get("id").asString();
        assertThat(onHand(service)).isEqualByComparingTo("0");
        assertThat(net(mapped("accounts_payable"), "goods_receipt", grnId)).isEqualByComparingTo("0");
        assertThat(ok(200, "GET", "/procurement/purchase-orders/" + poId, null).get("status").asString())
                .isEqualTo("received");
    }

    // ------------------------------------------------------------------ P1-7

    @Test
    @DisplayName("P1-7: a bill priced above a vouchered receipt raises AP by the difference (Dr PPV / Cr AP)")
    void billAboveReceiptPriceRaisesVariance() {
        String product = stockProduct();
        String poId = createPo(Map.of(), product, 1, 100).get("id").asString();
        receive(poId, product, 1);
        BigDecimal ap0 = balance(mapped("accounts_payable"));
        JsonNode bill = ok(200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", today, "lines",
                List.of(Map.of("productId", product, "description", "Cement", "quantity", 1, "unitPrice", 120))));
        JsonNode posted = ok(200, "POST", "/vendor-bills/" + bill.get("id").asString() + "/post", Map.of("override3WM",
                true, "overrideApproval", true));
        assertThat(new BigDecimal(posted.get("amountResidual").asString())).isEqualByComparingTo("20");
        assertThat(balance(mapped("accounts_payable")).subtract(ap0)).isEqualByComparingTo("-20");
    }
}
