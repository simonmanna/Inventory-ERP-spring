package com.inventoryerp.qualitycontrol;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.qualitycontrol.application.QcGate;
import com.inventoryerp.qualitycontrol.application.QcSourceDoc;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** QC2 of the quality-control module (ADR-S087 §4–§7): generation, blocking at the hook points, quarantine. */
class QualityControlFlowIT extends ApiTestSupport {

    static final String MO = "/manufacturing/production-orders/";

    @Autowired
    StockService stock;

    @Autowired
    QcGate gate;

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    int seq;
    String adminId;
    String bakery;
    String warehouse;
    String supplier;
    String customer;
    String flour;

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        ok(201, "POST", "/inventory/posting-rules/restore-defaults", null);
        pinPosWarehouse();
        bakery = location("production");
        warehouse = location("warehouse");
        supplier = partner(true);
        customer = partner(false);
        flour = product("QFL");
        inTx(() -> stock.receive(StockMove.of(flour, bakery, new BigDecimal("10000")).unitCost(BigDecimal.ONE)));
    }

    // ------------------------------------------------------------------ fixtures

    String next() {
        return sfx + "-" + (++seq);
    }

    void inTx(Runnable r) {
        TenantContext.run(TenantContext.TenantStore.user(demoOrg, adminId),
                () -> new TransactionTemplate(txm).executeWithoutResult(s -> r.run()));
    }

    String location(String type) {
        return ok(201, "POST", "/inventory/locations", Map.of("code", "QCF-" + next(), "name", "QC flow " + next(),
                "type", type)).get("id").asString();
    }

    String partner(boolean isSupplier) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"isCustomer\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, now())").params(id, demoOrg, "QCP-" + next(),
                        "QC partner " + next(), isSupplier, !isSupplier).update();
        return id;
    }

    String product(String code) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"manufacturingRole\", \"billingBasis\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable', true, 'AVCO', 1, 15, 'finished', 'accepted', now())")
                .params(id, demoOrg, code + "-" + next(), code + " " + next()).update();
        return id;
    }

    String point(String operation, String checkType, String productId, Map<String, Object> extra) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("title", operation + " " + checkType + " " + next());
        p.put("operation", operation);
        p.put("checkType", checkType);
        p.put("productIds", List.of(productId));
        p.putAll(extra);
        return ok(201, "POST", "/quality-control/control-points", p).get("id").asString();
    }

    String bom(String output) {
        JsonNode draft = ok(201, "POST", "/manufacturing/boms", Map.of("name", "QC recipe " + next(), "outputProductId",
                output, "outputQuantity", 1, "lines", List.of(Map.of("componentProductId", flour, "quantity", 1))));
        String id = draft.get("id").asString();
        ok(201, "POST", "/manufacturing/boms/" + id + "/activate", null);
        return id;
    }

    /** Create + confirm (+ start) an order of {@code runs} units. */
    String order(String bomId, int runs, boolean start) {
        String id = ok(201, "POST", "/manufacturing/production-orders", Map.of("bomId", bomId, "runs", runs,
                "locationId", bakery, "outputLocationId", warehouse)).get("id").asString();
        ok(201, "POST", MO + id + "/confirm", null);
        if (start) {
            ok(201, "POST", MO + id + "/start", Map.of());
        }
        return id;
    }

    JsonNode checks(String sourceType, String sourceId) {
        return ok(200, "GET", "/quality-control/checks?sourceType=" + sourceType + "&sourceId=" + sourceId
                + "&pageSize=100", null).get("data");
    }

    List<JsonNode> checks(String sourceType, String sourceId, String status) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode c : checks(sourceType, sourceId)) {
            if (status == null || status.equals(c.get("status").asString())) {
                out.add(c);
            }
        }
        return out;
    }

    JsonNode execute(String checkId, Map<String, Object> body) {
        return ok(200, "POST", "/quality-control/checks/" + checkId + "/execute", body);
    }

    Map<String, String> keyed() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    Res money(String method, String path, Object body) {
        return call(method, path, body, keyed());
    }

    BigDecimal onHand(String productId, String loc) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockItem\" WHERE \"productId\" = ? AND"
                + " \"locationId\" = ?").params(productId, loc).query(BigDecimal.class).single();
    }

    String quarantineLocation() {
        return jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND code = 'QC-QUARANTINE'")
                .param(demoOrg).query(String.class).single();
    }

    String message(Res r) {
        return r.body() == null || r.body().get("message") == null ? r.raw().body() : r.body().get("message").asString();
    }

    // ------------------------------------------------------------------ manufacturing

    @Test
    @DisplayName("MO: a blocking check is generated once, holds completion, and is closed with the order")
    void productionOrderBlocked() {
        String brick = product("QBR");
        point("manufacturing", "pass_fail", brick, Map.of());
        String id = order(bom(brick), 10, true);
        List<JsonNode> pending = checks("production_order", id, "pending");
        assertThat(pending).as("confirm + start sync: still one check").hasSize(1);
        JsonNode check = pending.getFirst();
        assertThat(check.get("origin").asString()).isEqualTo("generated");
        assertThat(check.get("generationKey").asString()).endsWith("#0");
        assertThat(new BigDecimal(check.get("coveredQty").asString())).isEqualByComparingTo("10");

        Res blocked = as("POST", MO + id + "/complete", Map.of("qtyProduced", 10));
        assertThat(blocked.status()).isEqualTo(400);
        assertThat(message(blocked)).isEqualTo("Quality checks are pending: " + check.get("checkNumber").asString() + ".");
        assertThat(onHand(brick, warehouse)).as("nothing posted").isEqualByComparingTo("0");

        execute(check.get("id").asString(), Map.of("result", "passed"));
        ok(201, "POST", MO + id + "/complete", Map.of("qtyProduced", 10));
        JsonNode closed = ok(200, "GET", "/quality-control/checks/" + check.get("id").asString(), null);
        assertThat(closed.get("sourceClosed").asBoolean()).isTrue();
        assertThat(as("POST", "/quality-control/checks/" + check.get("id").asString() + "/reset",
                Map.of("reason", "late")).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("MO: block-on-failure holds a failed check until a manager waives it")
    void productionOrderFailedUntilWaived() {
        String brick = product("QBF");
        point("manufacturing", "pass_fail", brick, Map.of("blockOnFailure", true));
        String id = order(bom(brick), 5, true);
        JsonNode check = checks("production_order", id, "pending").getFirst();
        String checkId = check.get("id").asString();
        execute(checkId, Map.of("result", "failed"));
        Res blocked = as("POST", MO + id + "/complete", Map.of("qtyProduced", 5));
        assertThat(message(blocked)).startsWith("Quality checks failed: " + check.get("checkNumber").asString());
        ok(200, "POST", "/quality-control/checks/" + checkId + "/waive", Map.of("reason", "re-tested by the lab",
                "disposition", "accepted_as_is"));
        ok(201, "POST", MO + id + "/complete", Map.of("qtyProduced", 5));
    }

    @Test
    @DisplayName("MO: a non-blocking failed quantity moves to quarantine at completion; a shortfall is retried")
    void productionOrderQuarantine() {
        String brick = product("QBQ");
        point("manufacturing", "pass_fail", brick, Map.of("blocking", false));
        String quarantine = quarantineLocationAfterEnsure();

        // 1) 3 of 10 failed: moved from the output location into quarantine with the completion.
        String id = order(bom(brick), 10, true);
        String checkId = checks("production_order", id, "pending").getFirst().get("id").asString();
        execute(checkId, Map.of("result", "failed", "failedQty", 3));
        ok(201, "POST", MO + id + "/complete", Map.of("qtyProduced", 10));
        JsonNode moved = ok(200, "GET", "/quality-control/checks/" + checkId, null);
        assertThat(moved.get("quarantineStatus").asString()).isEqualTo("completed");
        assertThat(new BigDecimal(moved.get("quarantinedQty").asString())).isEqualByComparingTo("3");
        assertThat(onHand(brick, warehouse)).isEqualByComparingTo("7");
        assertThat(onHand(brick, quarantine)).isEqualByComparingTo("3");
        assertThat(jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"referenceType\" = 'qc_check' AND"
                + " \"referenceId\" = ?").param(checkId + ":1:failed").query(Long.class).single()).isPositive();

        // 2) 8 failed, only 5 produced: the completion commits, the move is an exception, a retry fixes it.
        String brick2 = product("QBR2");
        point("manufacturing", "pass_fail", brick2, Map.of("blocking", false));
        String id2 = order(bom(brick2), 10, true);
        String check2 = checks("production_order", id2, "pending").getFirst().get("id").asString();
        execute(check2, Map.of("result", "failed", "failedQty", 8));
        ok(201, "POST", MO + id2 + "/complete", Map.of("qtyProduced", 5));
        JsonNode exception = ok(200, "GET", "/quality-control/checks/" + check2, null);
        assertThat(exception.get("quarantineStatus").asString()).isEqualTo("failed");
        assertThat(exception.get("quarantineError").asString()).startsWith("Only ");
        assertThat(new BigDecimal(exception.get("quarantinedQty").asString())).isEqualByComparingTo("0");
        assertThat(ok(200, "GET", "/quality-control/checks?quarantineStatus=failed&pageSize=100", null).get("data")
                .findValuesAsString("id")).contains(check2);
        String operator = tokenWith(Permissions.QUALITY_CONTROL__READ, Permissions.QUALITY_CONTROL__CHECK);
        assertThat(call("POST", "/quality-control/checks/" + check2 + "/quarantine/retry", null, operator).status())
                .isEqualTo(403);
        assertThat(as("POST", "/quality-control/checks/" + check2 + "/quarantine/retry", null).status()).isEqualTo(400);

        inTx(() -> stock.receive(StockMove.of(brick2, warehouse, new BigDecimal("50")).unitCost(BigDecimal.ONE)));
        JsonNode retried = ok(200, "POST", "/quality-control/checks/" + check2 + "/quarantine/retry", null);
        assertThat(retried.get("quarantineStatus").asString()).isEqualTo("completed");
        assertThat(onHand(brick2, warehouse)).as("5 produced + 50 received − 8 quarantined").isEqualByComparingTo("47");
        assertThat(onHand(brick2, quarantine)).isEqualByComparingTo("8");
        assertThat(as("POST", "/quality-control/checks/" + check2 + "/quarantine/retry", null).status())
                .as("nothing left to retry").isEqualTo(400);
    }

    String quarantineLocationAfterEnsure() {
        inTx(() -> { });
        return jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND code = 'QC-QUARANTINE'")
                .param(demoOrg).query(String.class).optional()
                .orElseGet(() -> {
                    // The first quarantine move creates it; create through the same service path.
                    String brick = product("QBX");
                    point("receipt", "pass_fail", brick, Map.of("controlPer", "product", "receiptMode",
                            "quarantine_on_receipt"));
                    adhocReceipt(brick, 1);
                    return quarantineLocation();
                });
    }

    @Test
    @DisplayName("work orders: a check per work order holds its completion and the order's completion")
    void workOrdersBlocked() {
        String brick = product("QWO");
        String wc = ok(201, "POST", "/manufacturing/work-centers", Map.of("code", "QWC-" + next(), "name", "Press"))
                .get("id").asString();
        String bomId = bom(brick);
        ok(201, "POST", "/manufacturing/routings", Map.of("name", "QC line " + next(), "bomId", bomId, "operations",
                List.of(Map.of("sequence", 10, "name", "Press", "workCenterId", wc, "durationMins", 30))));
        point("work_order", "instructions", brick, Map.of());
        String id = order(bomId, 2, true);
        ok(201, "POST", MO + id + "/work-orders/generate", null);
        String wo = ok(200, "GET", MO + id + "/work-orders", null).get(0).get("id").asString();
        List<JsonNode> woChecks = checks("work_order", wo, "pending");
        assertThat(woChecks).hasSize(1);
        assertThat(woChecks.getFirst().get("workOrderId").asString()).isEqualTo(wo);

        Res woBlocked = as("POST", "/manufacturing/work-orders/" + wo + "/complete", Map.of());
        assertThat(message(woBlocked)).startsWith("Quality checks are pending: ");
        Res sfBlocked = money("POST", "/manufacturing/shop-floor/work-orders/" + wo + "/complete", Map.of());
        assertThat(message(sfBlocked)).startsWith("Quality checks are pending: ");
        assertThat(message(as("POST", MO + id + "/complete", Map.of("qtyProduced", 2))))
                .as("the order waits for its work orders' checks").startsWith("Quality checks are pending: ");

        execute(woChecks.getFirst().get("id").asString(), Map.of());
        assertThat(as("POST", "/manufacturing/work-orders/" + wo + "/complete", Map.of()).status()).isLessThan(300);
        ok(201, "POST", MO + id + "/complete", Map.of("qtyProduced", 2));
    }

    @Test
    @DisplayName("cancelling an order cancels its pending checks and those of its work orders; done checks stay")
    void cancelCascade() {
        String brick = product("QCX");
        String wc = ok(201, "POST", "/manufacturing/work-centers", Map.of("code", "QWX-" + next(), "name", "Mixer"))
                .get("id").asString();
        String bomId = bom(brick);
        ok(201, "POST", "/manufacturing/routings", Map.of("name", "QC mix " + next(), "bomId", bomId, "operations",
                List.of(Map.of("sequence", 10, "name", "Mix", "workCenterId", wc, "durationMins", 30))));
        point("work_order", "pass_fail", brick, Map.of());
        point("manufacturing", "pass_fail", brick, Map.of());
        point("manufacturing", "instructions", brick, Map.of());
        String id = order(bomId, 2, false);
        ok(201, "POST", MO + id + "/work-orders/generate", null);
        String wo = ok(200, "GET", MO + id + "/work-orders", null).get(0).get("id").asString();
        List<JsonNode> moChecks = checks("production_order", id, "pending");
        assertThat(moChecks).hasSize(2);
        execute(moChecks.getFirst().get("id").asString(), Map.of("result", "passed"));

        ok(201, "POST", MO + id + "/cancel", Map.of("reason", "customer withdrew"));
        assertThat(checks("production_order", id, "pending")).isEmpty();
        assertThat(checks("production_order", id, "passed")).hasSize(1);
        JsonNode cancelled = checks("work_order", wo, "cancelled").getFirst();
        assertThat(cancelled.get("cancelReason").asString()).isEqualTo("customer withdrew");
    }

    @Test
    @DisplayName("a product without a control point completes exactly as before and gets no check")
    void noPointUnchanged() {
        String plain = product("QNP");
        String id = order(bom(plain), 4, true);
        ok(201, "POST", MO + id + "/complete", Map.of("qtyProduced", 4));
        assertThat(checks("production_order", id)).isEmpty();
        assertThat(onHand(plain, warehouse)).isEqualByComparingTo("4");
    }

    // ------------------------------------------------------------------ receipts

    JsonNode adhocReceipt(String productId, int qty) {
        Res r = call("POST", "/procurement/goods-receipts/adhoc", Map.of("partnerId", supplier, "warehouseId",
                warehouse, "lines", List.of(Map.of("productId", productId, "description", "QC goods", "quantity", qty,
                        "unitCost", 10))), keyed());
        assertThat(r.status()).as(r.raw().body()).isLessThan(300);
        return r.body();
    }

    String draftReceipt(String productId, int qty) {
        return ok(201, "POST", "/procurement/goods-receipts", Map.of("partnerId", supplier, "warehouseId", warehouse,
                "lines", List.of(Map.of("productId", productId, "description", "QC goods", "quantity", qty,
                        "unitCost", 10)))).get("id").asString();
    }

    String purchaseOrder(String productId, int qty) {
        return ok(201, "POST", "/procurement/purchase-orders", Map.of("partnerId", supplier, "warehouseId", warehouse,
                "paymentType", "credit", "currencyCode", "UGX", "lines", List.of(Map.of("productId", productId,
                        "description", "QC goods", "quantity", qty, "unitPrice", 10)))).get("id").asString();
    }

    Res receivePo(String poId, String productId, int qty) {
        String poLine = jdbc.sql("SELECT id FROM \"PurchaseOrderLine\" WHERE \"purchaseOrderId\" = ?").param(poId)
                .query(String.class).single();
        return money("POST", "/procurement/purchase-orders/" + poId + "/receive", Map.of("warehouseId", warehouse,
                "lines", List.of(Map.of("purchaseOrderLineId", poLine, "productId", productId, "description",
                        "QC goods", "quantity", qty))));
    }

    @Test
    @DisplayName("pre-receipt: a draft receipt's quantity check needs its test samples before it can post")
    void preReceiptDraft() {
        String cement = product("QCM");
        point("receipt", "pass_fail", cement, Map.of("controlPer", "quantity", "testQtyPct", 10, "supplierPartnerId",
                supplier));
        String grn = draftReceipt(cement, 20);
        JsonNode check = checks("goods_receipt", grn, "pending").getFirst();
        assertThat(new BigDecimal(check.get("testQty").asString())).isEqualByComparingTo("2");
        assertThat(check.get("supplierPartnerId").asString()).isEqualTo(supplier);

        Res blocked = money("PATCH", "/procurement/goods-receipts/" + grn + "/post", null);
        assertThat(message(blocked)).startsWith("Quality checks are pending: ");
        assertThat(onHand(cement, warehouse)).isEqualByComparingTo("0");

        String checkId = check.get("id").asString();
        Res short1 = as("POST", "/quality-control/checks/" + checkId + "/execute", Map.of("result", "passed",
                "samples", List.of(Map.of("sampleRef", "S1", "sampleQty", 1, "result", "passed"))));
        assertThat(message(short1)).startsWith("Record samples covering the test quantity (2)");
        execute(checkId, Map.of("result", "passed", "samples", List.of(Map.of("sampleRef", "S1", "sampleQty", 1,
                "result", "passed"), Map.of("sampleRef", "S2", "sampleQty", 1, "result", "passed"))));
        Res posted = money("PATCH", "/procurement/goods-receipts/" + grn + "/post", null);
        assertThat(posted.status()).as(posted.raw().body()).isEqualTo(200);
        assertThat(onHand(cement, warehouse)).isEqualByComparingTo("20");

        // An ad-hoc receipt stays a draft while its checks are pending.
        JsonNode adhoc = adhocReceipt(cement, 5);
        assertThat(adhoc.get("status").asString()).isEqualTo("draft");
        assertThat(adhoc.get("qualityChecksPending").asBoolean()).isTrue();
        // Another supplier's receipt is not covered by the point.
        String other = partner(true);
        String grn2 = ok(201, "POST", "/procurement/goods-receipts", Map.of("partnerId", other, "warehouseId",
                warehouse, "lines", List.of(Map.of("productId", cement, "description", "QC goods", "quantity", 5,
                        "unitCost", 10)))).get("id").asString();
        assertThat(checks("goods_receipt", grn2)).isEmpty();
    }

    @Test
    @DisplayName("pre-receipt: a one-shot PO receive is refused while a pre-receipt point matches")
    void oneShotReceiveRefused() {
        String gravel = product("QGV");
        point("receipt", "pass_fail", gravel, Map.of());
        String po = purchaseOrder(gravel, 10);
        Res refused = receivePo(po, gravel, 10);
        assertThat(refused.status()).isEqualTo(400);
        assertThat(message(refused)).isEqualTo("Quality checks are required before this receipt is posted. Save it as a"
                + " draft goods receipt, complete the checks, then post.");
        assertThat(onHand(gravel, warehouse)).isEqualByComparingTo("0");
        assertThat(jdbc.sql("SELECT count(*) FROM \"GoodsReceiptNote\" WHERE \"purchaseOrderId\" = ?").param(po)
                .query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("quarantine on receipt: received stock is held until the decision; pass releases, fail keeps failedQty")
    void quarantineOnReceipt() {
        String sand = product("QSD");
        point("receipt", "pass_fail", sand, Map.of("controlPer", "product", "receiptMode", "quarantine_on_receipt"));
        assertThat(as("POST", "/quality-control/control-points", Map.of("title", "x", "operation", "receipt",
                "checkType", "pass_fail", "receiptMode", "quarantine_on_receipt")).body().get("message").asString())
                .isEqualTo("Quarantine on receipt needs a check per product or per quantity.");

        String po = purchaseOrder(sand, 30);
        Res received = receivePo(po, sand, 10);
        assertThat(received.status()).as(received.raw().body()).isEqualTo(200);
        String grn = received.body().get("grn").get("id").asString();
        String quarantine = quarantineLocation();
        assertThat(onHand(sand, warehouse)).as("nothing available until the check passes").isEqualByComparingTo("0");
        assertThat(onHand(sand, quarantine)).isEqualByComparingTo("10");
        JsonNode check = checks("goods_receipt", grn, "pending").getFirst();
        assertThat(check.get("phase").asString()).isEqualTo("post_receipt");
        assertThat(check.get("quarantineStatus").asString()).isEqualTo("pending");
        assertThat(check.get("blocksSource").asBoolean()).isFalse();

        // fail 4: 6 released, 4 kept, one transaction
        JsonNode failed = execute(check.get("id").asString(), Map.of("result", "failed", "failedQty", 4));
        assertThat(failed.get("quarantineStatus").asString()).isEqualTo("completed");
        assertThat(new BigDecimal(failed.get("quarantinedQty").asString())).isEqualByComparingTo("4");
        assertThat(onHand(sand, warehouse)).isEqualByComparingTo("6");
        assertThat(onHand(sand, quarantine)).isEqualByComparingTo("4");
        assertThat(as("POST", "/quality-control/checks/" + check.get("id").asString() + "/reset", Map.of("reason",
                "x")).body().get("message").asString()).contains("has moved stock, so it cannot be reset");

        // second receipt passes: all 10 released
        String grn2 = receivePo(po, sand, 10).body().get("grn").get("id").asString();
        JsonNode check2 = checks("goods_receipt", grn2, "pending").getFirst();
        execute(check2.get("id").asString(), Map.of("result", "passed"));
        assertThat(onHand(sand, warehouse)).isEqualByComparingTo("16");
        assertThat(onHand(sand, quarantine)).isEqualByComparingTo("4");

        // third receipt: the held stock vanished from quarantine → the pass is refused and nothing changes
        String grn3 = receivePo(po, sand, 10).body().get("grn").get("id").asString();
        String check3 = checks("goods_receipt", grn3, "pending").getFirst().get("id").asString();
        String elsewhere = location("warehouse");
        inTx(() -> stock.transfer(StockMove.transfer(sand, quarantine, elsewhere, new BigDecimal("13"))));
        Res refused = as("POST", "/quality-control/checks/" + check3 + "/execute", Map.of("result", "passed"));
        assertThat(refused.status()).isEqualTo(400);
        assertThat(message(refused)).contains("The decision is not saved.");
        JsonNode still = ok(200, "GET", "/quality-control/checks/" + check3, null);
        assertThat(still.get("status").asString()).isEqualTo("pending");
        assertThat(still.get("executions")).isEmpty();
        assertThat(onHand(sand, warehouse)).isEqualByComparingTo("16");
    }

    @Test
    @DisplayName("quarantine on receipt: a receipt whose quarantine move fails rolls back entirely")
    void receiptRollsBackWithQuarantine() {
        String lime = product("QLM");
        // failure location = the receiving warehouse → the transfer is refused (same location) inside the receipt
        point("receipt", "pass_fail", lime, Map.of("controlPer", "product", "receiptMode", "quarantine_on_receipt",
                "failureLocationId", warehouse));
        String po = purchaseOrder(lime, 10);
        Res r = receivePo(po, lime, 10);
        assertThat(r.status()).isEqualTo(400);
        assertThat(onHand(lime, warehouse)).isEqualByComparingTo("0");
        assertThat(jdbc.sql("SELECT count(*) FROM \"GoodsReceiptNote\" WHERE \"purchaseOrderId\" = ?").param(po)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT \"receivedQuantity\" FROM \"PurchaseOrderLine\" WHERE \"purchaseOrderId\" = ?")
                .param(po).query(BigDecimal.class).single()).isEqualByComparingTo("0");
        assertThat(jdbc.sql("SELECT count(*) FROM \"QcCheck\" WHERE \"productId\" = ?").param(lime).query(Long.class)
                .single()).isZero();
    }

    // ------------------------------------------------------------------ deliveries

    @Test
    @DisplayName("delivery: a pending check holds the issue; a quantity_excluded waiver lets it go; cancel cascades")
    void deliveryBlocked() {
        String block = product("QDB");
        inTx(() -> stock.receive(StockMove.of(block, warehouse, new BigDecimal("100")).unitCost(BigDecimal.ONE)));
        point("delivery", "pass_fail", block, Map.of());
        String so = ok(201, "POST", "/sales/orders", Map.of("partnerId", customer, "locationId", warehouse, "lines",
                List.of(Map.of("productId", block, "quantity", 20, "unitPrice", 15)))).get("id").asString();
        ok(200, "POST", "/sales/orders/" + so + "/confirm", null);
        JsonNode d = ok(201, "POST", "/sales/orders/" + so + "/deliveries", Map.of("vehiclePlate", "UQC " + next(),
                "lines", List.of(Map.of("lineId", jdbc.sql("SELECT id FROM \"SalesOrderLine\" WHERE \"orderId\" = ?")
                        .param(so).query(String.class).single(), "quantity", 10))));
        String delivery = d.get("id").asString();
        JsonNode check = checks("sales_delivery", delivery, "pending").getFirst();
        Res blocked = money("POST", "/sales/deliveries/" + delivery + "/issue", null);
        assertThat(message(blocked)).startsWith("Quality checks are pending: ");
        assertThat(onHand(block, warehouse)).isEqualByComparingTo("100");

        String checkId = check.get("id").asString();
        execute(checkId, Map.of("result", "failed", "failedQty", 2));
        ok(200, "POST", "/quality-control/checks/" + checkId + "/waive", Map.of("reason", "two bags torn, left behind",
                "disposition", "quantity_excluded", "excludedQty", 2));
        Res issued = money("POST", "/sales/deliveries/" + delivery + "/issue", null);
        assertThat(issued.status()).as(issued.raw().body()).isLessThan(300);
        assertThat(onHand(block, warehouse)).isEqualByComparingTo("90");

        JsonNode d2 = ok(201, "POST", "/sales/orders/" + so + "/deliveries", Map.of("vehiclePlate", "UQC " + next()));
        String delivery2 = d2.get("id").asString();
        assertThat(checks("sales_delivery", delivery2, "pending")).hasSize(1);
        ok(200, "POST", "/sales/deliveries/" + delivery2 + "/cancel", Map.of("reason", "truck broke down"));
        assertThat(checks("sales_delivery", delivery2, "cancelled")).hasSize(1);
    }

    // ------------------------------------------------------------------ generation

    QcSourceDoc doc(String sourceId, String productId, String lineId, String qty) {
        return new QcSourceDoc("production_order", sourceId, "QC-" + sourceId.substring(0, 6), "manufacturing", null,
                null, null, null, null, lineId == null ? List.of()
                        : List.of(new QcSourceDoc.Line(lineId, productId, null, new BigDecimal(qty), null)));
    }

    long active(String sourceId) {
        return jdbc.sql("SELECT count(*) FROM \"QcCheck\" WHERE \"sourceId\" = ? AND status <> 'cancelled'")
                .param(sourceId).query(Long.class).single();
    }

    @Test
    @DisplayName("concurrent syncs of one document create each check once")
    void concurrentSync() throws Exception {
        String brick = product("QCS");
        point("manufacturing", "pass_fail", brick, Map.of("controlPer", "quantity", "testQtyPct", 10));
        String source = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                tasks.add(() -> {
                    inTx(() -> gate.sync(doc(source, brick, "L1", "100")));
                    return true;
                });
            }
            for (Future<Boolean> f : pool.invokeAll(tasks)) {
                assertThat(f.get()).isTrue();
            }
        } finally {
            pool.shutdown();
        }
        assertThat(active(source)).isEqualTo(1);
    }

    @Test
    @DisplayName("quantity changes: pending follows, done + increase gets a supplementary check, removed line cancels")
    void reconciliation() {
        String brick = product("QRC");
        point("manufacturing", "pass_fail", brick, Map.of("controlPer", "quantity", "testQtyPct", 10,
                "blocking", false));
        String source = UUID.randomUUID().toString();
        inTx(() -> gate.sync(doc(source, brick, "L1", "100")));
        inTx(() -> gate.sync(doc(source, brick, "L1", "150")));
        JsonNode pending = checks("production_order", source, "pending").getFirst();
        assertThat(new BigDecimal(pending.get("coveredQty").asString())).isEqualByComparingTo("150");
        assertThat(new BigDecimal(pending.get("testQty").asString())).isEqualByComparingTo("15");

        execute(pending.get("id").asString(), Map.of("result", "passed", "samples", List.of(Map.of("sampleQty", 15,
                "result", "passed"))));
        inTx(() -> gate.sync(doc(source, brick, "L1", "200")));
        List<JsonNode> supplementary = checks("production_order", source, "pending");
        assertThat(supplementary).hasSize(1);
        assertThat(supplementary.getFirst().get("generationKey").asString()).endsWith("#1");
        assertThat(new BigDecimal(supplementary.getFirst().get("coveredQty").asString())).isEqualByComparingTo("50");

        inTx(() -> gate.sync(doc(source, brick, null, "0")));
        assertThat(checks("production_order", source, "cancelled").getFirst().get("cancelReason").asString())
                .isEqualTo("source line removed");
        assertThat(checks("production_order", source, "passed")).as("a done check survives").hasSize(1);
        inTx(() -> gate.sync(doc(source, brick, "L1", "200")));
        assertThat(checks("production_order", source, "pending")).as("restored line: key freed").hasSize(1);
    }

    @Test
    @DisplayName("random frequency: the decision is made once per base key and kept")
    void randomFrequency() {
        String brick = product("QRF");
        String always = point("manufacturing", "pass_fail", brick, Map.of("frequency", "random", "randomPct", 100,
                "blocking", false));
        String never = point("manufacturing", "pass_fail", brick, Map.of("frequency", "random", "randomPct", 0.0001,
                "blocking", false));
        String source = UUID.randomUUID().toString();
        inTx(() -> gate.sync(doc(source, brick, "L1", "10")));
        assertThat(checks("production_order", source).findValuesAsString("controlPointId")).containsExactly(always);
        Map<String, Object> raise = new HashMap<>();
        raise.put("randomPct", 100);
        ok(200, "PUT", "/quality-control/control-points/" + never, merged(never, raise));
        inTx(() -> gate.sync(doc(source, brick, "L1", "10")));
        assertThat(active(source)).as("not re-rolled").isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM \"QcSamplingDecision\" WHERE \"controlPointId\" IN (?, ?)")
                .params(always, never).query(Long.class).single()).isEqualTo(2);
    }

    /** The point's editable fields with {@code changes} applied (PUT replaces the configuration). */
    Map<String, Object> merged(String pointId, Map<String, Object> changes) {
        JsonNode p = ok(200, "GET", "/quality-control/control-points/" + pointId, null);
        Map<String, Object> body = new LinkedHashMap<>();
        for (String f : List.of("title", "operation", "checkType", "frequency", "blocking")) {
            body.put(f, p.get(f).isBoolean() ? p.get(f).asBoolean() : p.get(f).asString());
        }
        body.put("productIds", List.of(p.get("productIds").get(0).asString()));
        body.put("reason", "test");
        body.putAll(changes);
        return body;
    }

    String tokenWith(String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "QcFlow-" + role, permissions).update();
        String email = "qcf-" + UUID.randomUUID() + "@demo.test";
        createUser(demoOrg, email, "Qc@12345678", role);
        return login("DEMO", email, "Qc@12345678");
    }
}
