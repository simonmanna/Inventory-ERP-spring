package com.inventoryerp.pos;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.pos.application.PosInvoiceService;
import com.inventoryerp.pos.application.PosStockPostingService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Phase I1a (audit 2026-11-15, ADR-S072): POS stock posting keeps product-variant identity and prices variants like
 * Sales; a failing line never takes the good lines of the same job down with it (owner requirement R-B); kitchen waste
 * issues the sold variant in the sold unit and records a failure instead of swallowing it.
 */
class PosVariantIntegrityIT extends ApiTestSupport {

    static final String PIN = "4321";

    @Autowired
    StockService stock;

    @Autowired
    PosInvoiceService billing;

    @Autowired
    PosStockPostingService postings;

    String sfx;
    String adminId;
    String warehouseId;
    String registerId;
    String sessionId;
    String sizeAttr;
    String small;
    String large;

    @BeforeAll
    void fixtures() {
        sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'").query(String.class).single();
        warehouseId = pinPosWarehouse();
        ok(201, "POST", "/pos/override/pin", Map.of("pin", PIN));
        JsonNode s = ok(201, "POST", "/variant-attributes", Map.of("name", "PSize " + sfx, "values",
                List.of(Map.of("name", "Small"), Map.of("name", "Large"))));
        sizeAttr = s.get("id").asString();
        small = s.get("values").get(0).get("id").asString();
        large = s.get("values").get(1).get("id").asString();
        registerId = ok(201, "POST", "/cash-registers", Map.of("code", "PVI-" + sfx, "name", "Variant till"))
                .get("id").asString();
        sessionId = money(200, "POST", "/cash-sessions/open", Map.of("cashRegisterId", registerId, "openingFloat", 0))
                .get("id").asString();
    }

    @AfterAll
    void cleanup() {
        for (String id : jdbc.sql("SELECT id FROM \"Order\" WHERE \"cashSessionId\" = ? AND status::text NOT IN"
                + " ('closed', 'cancelled', 'completed')").param(sessionId).query(String.class).list()) {
            keyed("POST", "/pos/orders/" + id + "/cancel", Map.of("reason", "variant test cleanup", "overrideById",
                    adminId, "overridePin", PIN));
        }
        forceCloseSessions(List.of(registerId));
    }

    // ------------------------------------------------------------------ helpers

    <T> T asOrg(Supplier<T> body) {
        return TenantContext.run(TenantStore.organization(demoOrg), body);
    }

    void asOrgRun(Runnable body) {
        TenantContext.run(TenantStore.organization(demoOrg), body);
    }

    Res keyed(String method, String path, Object body) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return call(method, path, body, h);
    }

    JsonNode money(int status, String method, String path, Object body) {
        Res r = keyed(method, path, body);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(status);
        return r.body();
    }

    String plainProduct(String name, int salesPrice) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable', true,"
                + " 'AVCO', 0, ?, now())").params(id, demoOrg, "PVI-" + UUID.randomUUID().toString().substring(0, 8),
                        name + " " + sfx, salesPrice).update();
        return id;
    }

    void receive(String productId, String variantId, int qty) {
        asOrg(() -> stock.receive(StockMove.of(productId, warehouseId, BigDecimal.valueOf(qty)).variant(variantId)
                .unitCost(BigDecimal.TEN)));
    }

    /** A product with Small / Large variants (Large +25 over the product price), 20 of each in the POS warehouse. */
    Map<String, String> variantProduct(int price) {
        String p = plainProduct("Kerb", price);
        JsonNode cfg = ok(200, "PUT", "/products/" + p + "/attribute-lines", Map.of("lines", List.of(Map.of(
                "attributeId", sizeAttr, "valueIds", List.of(small, large)))));
        String lineId = cfg.get("lines").get(0).get("id").asString();
        JsonNode priced = ok(200, "PATCH", "/products/" + p + "/attribute-lines/" + lineId + "/values", Map.of(
                "values", List.of(Map.of("valueId", large, "priceExtra", 25))));
        Map<String, String> out = new LinkedHashMap<>();
        out.put("product", p);
        for (JsonNode v : priced.get("variants")) {
            out.put(v.get("name").asString(), v.get("id").asString());
        }
        receive(p, out.get("Small"), 20);
        receive(p, out.get("Large"), 20);
        return out;
    }

    BigDecimal quant(String productId, String variantId) {
        return jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND \"variantId\" IS NOT DISTINCT FROM ?").params(demoOrg, productId, variantId)
                .query(BigDecimal.class).single();
    }

    BigDecimal onHand(String productId) {
        return jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ?").params(demoOrg, productId).query(BigDecimal.class).single();
    }

    /** Checkout one line; the client's unit price is {@code amount / quantity} (the server prices from the catalog). */
    JsonNode sale(Map<String, Object> line, int amount) {
        Map<String, Object> l = new LinkedHashMap<>(line);
        l.putIfAbsent("unitPrice", amount / ((Number) line.get("quantity")).intValue());
        return money(200, "POST", "/pos/checkout", Map.of("cashSessionId", sessionId, "lines", List.of(l),
                "tenders", List.of(Map.of("method", "cash", "amount", amount))));
    }

    static Map<String, Object> line(String productId, String variantId, int qty) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", productId);
        if (variantId != null) {
            m.put("variantId", variantId);
        }
        m.put("description", "line");
        m.put("quantity", qty);
        return m;
    }

    String insert(String table, Map<String, Object> cols) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("id", id);
        all.putAll(cols);
        String names = String.join(", ", all.keySet().stream().map(k -> "\"" + k + "\"").toList());
        String marks = String.join(", ", all.keySet().stream().map(k -> "?").toList());
        jdbc.sql("INSERT INTO \"" + table + "\" (" + names + ") VALUES (" + marks + ")").params(all.values().stream()
                .toList()).update();
        return id;
    }

    // ------------------------------------------------------------------ R-B: line atomicity

    @Test
    @DisplayName("R-B: lines 1, 2, 4 post and stay posted while line 3 fails; a retry issues only line 3, once")
    void failingLineDoesNotUndoTheOthers() {
        String p1 = plainProduct("Line1", 100);
        String p2 = plainProduct("Line2", 100);
        String p3 = plainProduct("Line3", 100);
        String p4 = plainProduct("Line4", 100);
        receive(p1, null, 10);
        receive(p2, null, 10);
        receive(p4, null, 10);
        // Line 3 fails inside StockService.issue: a product with variants, sold without one, with no legacy stock.
        jdbc.sql("UPDATE \"Product\" SET \"hasVariants\" = true WHERE id = ?").param(p3).update();

        Timestamp now = Timestamp.from(Instant.now());
        String orderId = insert("Order", Map.of("organizationId", demoOrg, "orderNumber", "ORD-RB-" + UUID.randomUUID(),
                "status", "closed", "updatedAt", now));
        List<String> productIds = List.of(p1, p2, p3, p4);
        String[] itemIds = new String[4];
        for (int i = 0; i < 4; i++) {
            itemIds[i] = insert("OrderItem", Map.of("organizationId", demoOrg, "orderId", orderId, "description",
                    "line " + (i + 1), "quantity", 1, "lineNumber", i + 1, "productId", productIds.get(i),
                    "updatedAt", now));
        }
        String jobId = insert("StockPostingJob", Map.of("organizationId", demoOrg, "orderId", orderId, "invoiceId",
                orderId, "invoiceNumber", "INV-RB-" + orderId, "status", "pending", "idempotencyKey", "at_invoice:"
                        + orderId));

        asOrgRun(() -> billing.processStockPostingJob(jobId));

        assertThat(onHand(p1)).as("line 1 posted").isEqualByComparingTo("9");
        assertThat(onHand(p2)).as("line 2 posted").isEqualByComparingTo("9");
        assertThat(onHand(p4)).as("line 4 posted").isEqualByComparingTo("9");
        assertThat(onHand(p3)).as("line 3 not posted").isEqualByComparingTo("0");
        Map<String, Object> job = jdbc.sql("SELECT status, attempts, \"postedLineKeys\"::text AS keys FROM"
                + " \"StockPostingJob\" WHERE id = ?").param(jobId).query().singleRow();
        assertThat(job.get("status")).as("job stays retryable").isEqualTo("pending");
        assertThat((String) job.get("keys")).contains(itemIds[0], itemIds[1], itemIds[3]).doesNotContain(itemIds[2]);
        assertThat(jdbc.sql("SELECT count(*) FROM \"InventoryException\" WHERE \"organizationId\" = ? AND"
                + " \"invoiceId\" = ? AND status = 'open' AND \"productId\" = ?").params(demoOrg, orderId, p3)
                .query(Long.class).single()).as("line 3 recorded as a retryable exception").isEqualTo(1);

        // The cause is fixed; the retry posts line 3 only and never deducts lines 1, 2, 4 again.
        jdbc.sql("UPDATE \"Product\" SET \"hasVariants\" = false WHERE id = ?").param(p3).update();
        receive(p3, null, 10);
        asOrgRun(() -> billing.processStockPostingJob(jobId));

        assertThat(jdbc.sql("SELECT status FROM \"StockPostingJob\" WHERE id = ?").param(jobId).query(String.class)
                .single()).isEqualTo("done");
        assertThat(onHand(p1)).isEqualByComparingTo("9");
        assertThat(onHand(p2)).isEqualByComparingTo("9");
        assertThat(onHand(p4)).isEqualByComparingTo("9");
        assertThat(onHand(p3)).isEqualByComparingTo("9");
        assertThat(jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                + " \"referenceId\" = ? AND \"quantityChange\" < 0").params(demoOrg, orderId).query(Long.class).single())
                .as("exactly one issue per line").isEqualTo(4);
    }

    @Test
    @DisplayName("R4: when a retry posts the failed line, its open exception is resolved (history kept)")
    void retryResolvesTheLinesException() {
        String p = plainProduct("Retry", 100);
        jdbc.sql("UPDATE \"Product\" SET \"hasVariants\" = true WHERE id = ?").param(p).update();
        Timestamp now = Timestamp.from(Instant.now());
        String orderId = insert("Order", Map.of("organizationId", demoOrg, "orderNumber", "ORD-R4-" + UUID.randomUUID(),
                "status", "closed", "updatedAt", now));
        insert("OrderItem", Map.of("organizationId", demoOrg, "orderId", orderId, "description", "line 1", "quantity", 1,
                "lineNumber", 1, "productId", p, "updatedAt", now));
        String jobId = insert("StockPostingJob", Map.of("organizationId", demoOrg, "orderId", orderId, "invoiceId",
                orderId, "invoiceNumber", "INV-R4-" + orderId, "status", "pending", "idempotencyKey", "at_invoice:"
                        + orderId));
        asOrgRun(() -> billing.processStockPostingJob(jobId));
        String exceptionId = jdbc.sql("SELECT id FROM \"InventoryException\" WHERE \"organizationId\" = ? AND"
                + " \"invoiceId\" = ? AND status = 'open'").params(demoOrg, orderId).query(String.class).single();

        jdbc.sql("UPDATE \"Product\" SET \"hasVariants\" = false WHERE id = ?").param(p).update();
        receive(p, null, 10);
        asOrgRun(() -> billing.processStockPostingJob(jobId));

        Map<String, Object> ex = jdbc.sql("SELECT status, \"resolvedAt\", \"resolutionNote\", reason FROM"
                + " \"InventoryException\" WHERE id = ?").param(exceptionId).query().singleRow();
        assertThat(ex.get("status")).isEqualTo("resolved");
        assertThat(ex.get("resolvedAt")).isNotNull();
        assertThat((String) ex.get("resolutionNote")).contains("retried this line");
        assertThat((String) ex.get("reason")).as("the original failure is kept").isNotBlank();
        assertThat(onHand(p)).isEqualByComparingTo("9");
        assertThat(jdbc.sql("SELECT count(*) FROM \"InventoryException\" WHERE \"organizationId\" = ? AND"
                + " \"invoiceId\" = ? AND status = 'open'").params(demoOrg, orderId).query(Long.class).single()).isZero();
    }

    // ------------------------------------------------------------------ V-1: variant stock issue + refund

    @Test
    @DisplayName("V-1: a POS sale of a product variant issues from that variant; a restock refund returns it there")
    void variantSaleIssuesAndRestocksTheVariant() {
        Map<String, String> v = variantProduct(100);
        String p = v.get("product");
        String smallId = v.get("Small");
        JsonNode sold = sale(line(p, smallId, 2), 200);
        String invoiceId = sold.get("invoiceId").asString();
        asOrgRun(() -> postings.drainSession(sessionId));

        assertThat(quant(p, smallId)).as("Small issued").isEqualByComparingTo("18");
        assertThat(quant(p, v.get("Large"))).as("Large untouched").isEqualByComparingTo("20");
        assertThat(quant(p, null)).as("no parent-level quant").isEqualByComparingTo("0");
        assertThat(jdbc.sql("SELECT \"variantId\" FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                + " \"referenceType\" = 'pos_invoice' AND \"referenceId\" = ?").params(demoOrg, invoiceId)
                .query(String.class).single()).isEqualTo(smallId);

        String lineId = jdbc.sql("SELECT id FROM \"InvoiceItem\" WHERE \"invoiceId\" = ?").param(invoiceId)
                .query(String.class).single();
        money(200, "POST", "/pos/invoices/" + invoiceId + "/refund", Map.of("reason", "Wrong size", "overrideById",
                adminId, "overridePin", PIN, "stockDisposition", "restock", "cashSessionId", sessionId, "lines",
                List.of(Map.of("lineId", lineId, "quantity", 1))));
        assertThat(quant(p, smallId)).as("restocked to Small").isEqualByComparingTo("19");
        assertThat(quant(p, null)).isEqualByComparingTo("0");
    }

    /** A product that held 10 without a variant before it got Small / Large variants (20 of each). */
    Map<String, String> legacyVariantProduct() {
        String p = plainProduct("Legacy kerb", 100);
        receive(p, null, 10);
        JsonNode cfg = ok(200, "PUT", "/products/" + p + "/attribute-lines", Map.of("lines", List.of(Map.of(
                "attributeId", sizeAttr, "valueIds", List.of(small, large)))));
        Map<String, String> out = new LinkedHashMap<>();
        out.put("product", p);
        for (JsonNode v : cfg.get("variants")) {
            out.put(v.get("name").asString(), v.get("id").asString());
        }
        receive(p, out.get("Small"), 20);
        receive(p, out.get("Large"), 20);
        return out;
    }

    @Test
    @DisplayName("M-9: an online POS sale of a variant product must name the variant unless no-variant stock is left")
    void onlineSaleNeedsTheVariant() {
        Map<String, String> v = variantProduct(100);
        Res refused = keyed("POST", "/pos/checkout", Map.of("cashSessionId", sessionId, "lines", List.of(new LinkedHashMap<>(
                Map.of("productId", v.get("product"), "description", "line", "quantity", 1, "unitPrice", 100))),
                "tenders", List.of(Map.of("method", "cash", "amount", 100))));
        assertThat(refused.status()).as(refused.raw().body()).isEqualTo(400);
        assertThat(refused.body().get("message").asString()).startsWith("Choose a variant for Kerb");

        Map<String, String> legacy = legacyVariantProduct();
        sale(line(legacy.get("product"), null, 1), 100);
        asOrgRun(() -> postings.drainSession(sessionId));
        assertThat(quant(legacy.get("product"), null)).as("sold from the no-variant stock").isEqualByComparingTo("9");
    }

    @Test
    @DisplayName("M-10: refunding a pre-I1a variant sale restocks the no-variant stock it was issued from")
    void legacyVariantRefundRestocksWhereIssued() {
        Map<String, String> v = legacyVariantProduct();
        String p = v.get("product");
        String smallId = v.get("Small");
        String invoiceId = sale(line(p, smallId, 2), 200).get("invoiceId").asString();
        // Post it the way the code before ADR-S072 did: the issue drew on the no-variant stock.
        jdbc.sql("UPDATE \"StockPostingJob\" SET status = 'done' WHERE \"invoiceId\" = ?").param(invoiceId).update();
        asOrg(() -> stock.issue(StockMove.of(p, warehouseId, BigDecimal.valueOf(2)).source("pos_invoice", invoiceId)));
        String lineId = jdbc.sql("SELECT id FROM \"InvoiceItem\" WHERE \"invoiceId\" = ?").param(invoiceId)
                .query(String.class).single();
        money(200, "POST", "/pos/invoices/" + invoiceId + "/refund", Map.of("reason", "Old sale", "overrideById",
                adminId, "overridePin", PIN, "stockDisposition", "restock", "cashSessionId", sessionId, "lines",
                List.of(Map.of("lineId", lineId, "quantity", 1))));
        assertThat(quant(p, null)).as("back to the no-variant stock").isEqualByComparingTo("9");
        assertThat(quant(p, smallId)).as("Small untouched").isEqualByComparingTo("20");
    }

    // ------------------------------------------------------------------ V-2: variant price

    @Test
    @DisplayName("V-2: POS prices a variant as product price + attribute extras, unless the variant has a fixed price")
    void variantPricing() {
        Map<String, String> v = variantProduct(100);
        String p = v.get("product");
        assertThat(sale(line(p, v.get("Large"), 1), 125).get("total").asDouble()).isEqualTo(125);
        assertThat(sale(line(p, v.get("Small"), 1), 100).get("total").asDouble()).isEqualTo(100);
        ok(200, "PATCH", "/products/" + p + "/variants/" + v.get("Large"), Map.of("salesPrice", 180));
        assertThat(sale(line(p, v.get("Large"), 1), 180).get("total").asDouble()).isEqualTo(180);
    }

    // ------------------------------------------------------------------ I1c: variant barcode

    @Test
    @DisplayName("I1c: a variant's barcode resolves to its product plus the variant at its effective price")
    void variantBarcodeResolves() {
        Map<String, String> v = variantProduct(100);
        String code = "VB-" + UUID.randomUUID().toString().substring(0, 10);
        ok(200, "PATCH", "/products/" + v.get("product") + "/variants/" + v.get("Large"), Map.of("barcode", code));
        JsonNode r = ok(200, "GET", "/products/resolve-barcode?code=" + code, null);
        assertThat(r.get("productId").asString()).isEqualTo(v.get("product"));
        assertThat(r.get("variantId").asString()).isEqualTo(v.get("Large"));
        assertThat(r.get("variant").get("salesPrice").asDouble()).isEqualTo(125);
        assertThat(r.get("quantity").asInt()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ V-3: kitchen waste

    /** An open tab with one line sent to the kitchen, then voided after the kitchen started it. */
    String wasteAfterKitchen(Map<String, Object> line) {
        int n = (int) (Math.abs(UUID.randomUUID().getMostSignificantBits()) % 900_000) + 100_000;
        String tableId = ok(201, "POST", "/pos/tables", Map.of("name", "PVI " + n, "number", n)).get("id").asString();
        ok(201, "POST", "/pos/tabs/" + tableId + "/save", Map.of("cashSessionId", sessionId, "guestCount", 1,
                "newRound", true, "lines", List.of(line)));
        String orderId = ok(200, "GET", "/pos/tabs/" + tableId, null).get("id").asString();
        ok(201, "POST", "/pos/tabs/" + tableId + "/fire-kitchen", null);
        ok(201, "POST", "/pos/receipts/" + orderId + "/print-kot", null);
        String itemId = jdbc.sql("SELECT id FROM \"OrderItem\" WHERE \"orderId\" = ? AND cancelled = false")
                .param(orderId).query(String.class).single();
        Res voided = keyed("DELETE", "/pos/orders/" + orderId + "/items/" + itemId, Map.of("reason", "Dropped",
                "quantity", 1, "overrideById", adminId, "overridePin", PIN));
        assertThat(voided.status()).as(voided.raw().body()).isIn(200, 201);
        return itemId;
    }

    @Test
    @DisplayName("V-3: kitchen waste issues the sold variant")
    void kitchenWasteOfAVariant() {
        Map<String, String> v = variantProduct(100);
        String p = v.get("product");
        Map<String, Object> l = line(p, v.get("Small"), 1);
        l.put("unitPrice", 100);
        String itemId = wasteAfterKitchen(l);
        assertThat(jdbc.sql("SELECT \"variantId\" FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                + " \"referenceType\" = 'pos_kitchen_waste' AND \"referenceId\" LIKE ?").params(demoOrg, itemId + ":%")
                .query(String.class).single()).isEqualTo(v.get("Small"));
        assertThat(quant(p, v.get("Small"))).isEqualByComparingTo("19");
        assertThat(quant(p, null)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("V-3: kitchen waste of a product sold by the box issues the box in base units")
    void kitchenWasteInTheSoldUnit() {
        String pcs = uom("PCS");
        String box = uom("BOX");
        String p = plainProduct("Cups", 1200);
        jdbc.sql("UPDATE \"Product\" SET \"uomId\" = ?, \"salesUomId\" = ? WHERE id = ?").params(pcs, box, p).update();
        Map<String, Object> boxRow = new LinkedHashMap<>(Map.of("uomId", box, "factor", 12, "isPurchase", false,
                "isSales", true));
        ok(200, "PATCH", "/products/" + p, Map.of("units", List.of(boxRow)));
        receive(p, null, 120);
        Map<String, Object> l = line(p, null, 1);
        l.put("unitPrice", 1200);
        wasteAfterKitchen(l);
        assertThat(onHand(p)).as("one box = 12 pieces").isEqualByComparingTo("108");
    }

    @Test
    @DisplayName("V-3: kitchen waste that cannot be issued is recorded as an inventory exception, not swallowed")
    void kitchenWasteFailureIsRecorded() {
        String p = plainProduct("NoVariant", 100);
        jdbc.sql("UPDATE \"Product\" SET \"hasVariants\" = true WHERE id = ?").param(p).update();
        Map<String, Object> l = line(p, null, 1);
        l.put("unitPrice", 100);
        wasteAfterKitchen(l);
        assertThat(jdbc.sql("SELECT count(*) FROM \"InventoryException\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND kind = 'kitchen_waste' AND status = 'open'").params(demoOrg, p)
                .query(Long.class).single()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ review R2: ATP + reservations

    @Test
    @DisplayName("R2: strict availability reads the variant's own quant, so a variant in stock sells")
    void strictAvailabilityIsVariantAware() {
        Map<String, String> v = variantProduct(100);
        String p = v.get("product");
        ok(200, "PUT", "/settings/inventory.atpMode", Map.of("value", "strict"));
        try {
            assertThat(sale(line(p, v.get("Small"), 2), 200).get("total").asDouble()).isEqualTo(200);
            Res over = keyed("POST", "/pos/checkout", Map.of("cashSessionId", sessionId, "lines",
                    List.of(priced(line(p, v.get("Large"), 21), 125)), "tenders", List.of(Map.of("method", "cash", "amount",
                            21 * 125))));
            assertThat(over.status()).as(over.raw().body()).isEqualTo(400);
            assertThat(over.body().get("message").toString()).contains("need 21", "20 available");
        } finally {
            ok(200, "PUT", "/settings/inventory.atpMode", Map.of("value", "post_commit"));
            cancelOpenOrders();
        }
    }

    @Test
    @DisplayName("R2: availability and reservations are in base units (a box of 12 needs 12 pieces) per variant")
    void availabilityAndReservationsInBaseUnits() {
        String p = boxedProduct("Plates", 30);
        // Cancelling the refused sale's order records kitchen waste (a separate finding, N7), so sell another product.
        String p2 = boxedProduct("Bowls", 30);
        ok(200, "PUT", "/settings/inventory.atpMode", Map.of("value", "strict"));
        ok(200, "PUT", "/settings/inventory.reservationMode", Map.of("value", "invoice"));
        try {
            // 3 boxes = 36 pieces > 30 on hand: refused (the sale-unit 3 was compared with 30 before).
            Res over = keyed("POST", "/pos/checkout", Map.of("cashSessionId", sessionId, "lines",
                    List.of(priced(line(p, null, 3), 1200)), "tenders", List.of(Map.of("method", "cash", "amount", 3600))));
            assertThat(over.status()).as(over.raw().body()).isEqualTo(400);
            assertThat(over.body().get("message").toString()).contains("need 36", "30 available");
            cancelOpenOrders();
            // 2 boxes sell and reserve 24 pieces until the posting consumes them.
            String invoiceId = sale(line(p2, null, 2), 2400).get("invoiceId").asString();
            assertThat(jdbc.sql("SELECT quantity FROM \"StockReservation\" WHERE \"sourceType\" = 'invoice' AND"
                    + " \"sourceId\" = ? AND \"productId\" = ?").params(invoiceId, p2).query(BigDecimal.class).single())
                    .isEqualByComparingTo("24");

            Map<String, String> v = variantProduct(100);
            String vInvoice = sale(line(v.get("product"), v.get("Large"), 3), 375).get("invoiceId").asString();
            Map<String, Object> r = jdbc.sql("SELECT \"variantId\", quantity FROM \"StockReservation\" WHERE"
                    + " \"sourceType\" = 'invoice' AND \"sourceId\" = ?").param(vInvoice).query().singleRow();
            assertThat(r.get("variantId")).as("reserved on the variant's quant").isEqualTo(v.get("Large"));
            assertThat((BigDecimal) r.get("quantity")).isEqualByComparingTo("3");
        } finally {
            ok(200, "PUT", "/settings/inventory.atpMode", Map.of("value", "post_commit"));
            ok(200, "PUT", "/settings/inventory.reservationMode", Map.of("value", "none"));
            cancelOpenOrders();
            asOrgRun(() -> postings.drainSession(sessionId));
        }
    }

    /** A product counted in pieces and sold by the box of 12, with {@code pieces} on hand. */
    String boxedProduct(String name, int pieces) {
        String pcs = uom("PCS");
        String box = uom("BOX");
        String p = plainProduct(name, 1200);
        jdbc.sql("UPDATE \"Product\" SET \"uomId\" = ?, \"salesUomId\" = ? WHERE id = ?").params(pcs, box, p).update();
        ok(200, "PATCH", "/products/" + p, Map.of("units", List.of(new LinkedHashMap<>(Map.of("uomId", box, "factor",
                12, "isPurchase", false, "isSales", true)))));
        receive(p, null, pieces);
        return p;
    }

    static Map<String, Object> priced(Map<String, Object> line, int unitPrice) {
        line.put("unitPrice", unitPrice);
        return line;
    }

    /** A refused checkout leaves its order open (created before invoicing); it would block the shift close. */
    @Test
    @DisplayName("N7: cancelling the order of a refused checkout records no kitchen waste (the kitchen was never fired)")
    void refusedCheckoutLeavesNoKitchenWaste() {
        String p = boxedProduct("Cups", 30);
        ok(200, "PUT", "/settings/inventory.atpMode", Map.of("value", "strict"));
        try {
            Res over = keyed("POST", "/pos/checkout", Map.of("cashSessionId", sessionId, "lines",
                    List.of(priced(line(p, null, 3), 1200)), "tenders", List.of(Map.of("method", "cash", "amount", 3600))));
            assertThat(over.status()).as(over.raw().body()).isEqualTo(400);
            String orderId = jdbc.sql("SELECT o.id FROM \"Order\" o JOIN \"OrderItem\" i ON i.\"orderId\" = o.id WHERE"
                    + " i.\"productId\" = ? AND o.\"cashSessionId\" = ?").params(p, sessionId).query(String.class).single();
            assertThat(jdbc.sql("SELECT count(*) FROM \"KitchenTicket\" WHERE \"orderId\" = ?").param(orderId)
                    .query(Long.class).single()).as("nothing was sent to the kitchen").isZero();
            cancelOpenOrders();
            assertThat(jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"productId\" = ? AND \"referenceType\""
                    + " = 'pos_kitchen_waste'").param(p).query(Long.class).single()).isZero();
        } finally {
            ok(200, "PUT", "/settings/inventory.atpMode", Map.of("value", "post_commit"));
            cancelOpenOrders();
        }
    }

    void cancelOpenOrders() {
        for (String id : jdbc.sql("SELECT id FROM \"Order\" WHERE \"cashSessionId\" = ? AND status::text NOT IN"
                + " ('closed', 'cancelled', 'completed')").param(sessionId).query(String.class).list()) {
            keyed("POST", "/pos/orders/" + id + "/cancel", Map.of("reason", "refused sale", "overrideById", adminId,
                    "overridePin", PIN));
        }
    }

    String uom(String code) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"UnitOfMeasure\" (id, \"organizationId\", code, name, category, factor, ratio,"
                + " \"roundingPrecision\", \"updatedAt\") VALUES (?, ?, ?, ?, 'unit', 1, 1, 1, now())")
                .params(id, demoOrg, code + "-" + UUID.randomUUID().toString().substring(0, 8), code).update();
        return id;
    }
}
