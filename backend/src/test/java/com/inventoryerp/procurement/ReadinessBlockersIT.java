package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The blockers of the 2026-11-17 inventory / procurement readiness audit
 * ({@code docs/audits/2026-11-17-inventory-procurement-readiness.md}). Each test replays the blocker's scenario and
 * asserts the corrected outcome. B3 (manual pick orders) is in {@code WarehouseIT}; UB2 is web only.
 */
class ReadinessBlockersIT extends ApiTestSupport {

    /** ISO 4217 test currency: never a real rate in the seed. */
    static final String TEST_CCY = "XTS";

    String adminId;
    String warehouse;
    String functional;
    String today = LocalDate.now().toString();

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        warehouse = ok(201, "POST", "/inventory/locations", Map.of("code", "RB-" + suffix(), "name", "Readiness "
                + suffix(), "type", "warehouse")).get("id").asString();
        functional = jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(demoOrg)
                .query(String.class).single();
        jdbc.sql("INSERT INTO \"Currency\" (id, code, symbol, name, \"updatedAt\") VALUES (?, ?, ?, 'Test currency',"
                + " now()) ON CONFLICT DO NOTHING").params(UUID.randomUUID().toString(), TEST_CCY, TEST_CCY).update();
    }

    // ------------------------------------------------------------------ helpers

    static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String partner(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(id, demoOrg, "RB-" + suffix(), name + " " + suffix())
                .update();
        return id;
    }

    String product(String type, boolean tracked) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                        + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?,"
                        + " ?::\"ProductType\", ?, 'AVCO'::\"CostingMethod\", 0, 0, now())")
                .params(id, demoOrg, "RB-" + suffix(), "Item " + suffix(), type, tracked).update();
        return id;
    }

    String stockProduct() {
        return product("stockable", true);
    }

    BigDecimal onHand(String productId) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockItem\" WHERE \"productId\" = ?").param(productId)
                .query(BigDecimal.class).single();
    }

    /** What the AP ledger owes the supplier (credit − debit on the payable account, lines tagged with them). */
    BigDecimal supplierAp(String partnerId) {
        return jdbc.sql("SELECT COALESCE(SUM(l.\"baseCredit\" - l.\"baseDebit\"), 0) FROM \"JournalLine\" l JOIN"
                + " \"AccountMapping\" m ON m.\"accountId\" = l.\"accountId\" AND m.\"organizationId\" = l.\"organizationId\""
                + " AND m.key = 'accounts_payable' AND m.\"deletedAt\" IS NULL WHERE l.\"organizationId\" = ? AND"
                + " l.\"partnerId\" = ?").params(demoOrg, partnerId).query(BigDecimal.class).single();
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

    static Map<String, Object> line(String productId, Object qty, Object price) {
        return Map.of("productId", productId, "description", "Cement", "quantity", qty, "unitPrice", price);
    }

    Map<String, Object> order(String supplier, Map<String, Object> extra, List<Map<String, Object>> lines) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("partnerId", supplier, "warehouseId", warehouse,
                "paymentType", "credit", "lines", lines));
        body.putAll(extra);
        return body;
    }

    JsonNode createPo(String supplier, String productId, Object qty, Object price) {
        return ok(201, "POST", "/procurement/purchase-orders", order(supplier, Map.of(), List.of(line(productId, qty,
                price))));
    }

    JsonNode receive(String poId, String productId, Object qty) {
        return money("POST", "/procurement/purchase-orders/" + poId + "/receive", Map.of("warehouseId", warehouse,
                "lines", List.of(Map.of("productId", productId, "description", "Cement", "quantity", qty))));
    }

    String grnSupplier(String grnId) {
        return jdbc.sql("SELECT \"partnerId\" FROM \"GoodsReceiptNote\" WHERE id = ?").param(grnId).query(String.class)
                .optional().orElse(null);
    }

    String tokenWith(String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "RB " + role, permissions).update();
        String email = "rb-" + role.substring(0, 8) + "@demo.test";
        createUser(demoOrg, email, "Ready@12345", role);
        return login("DEMO", email, "Ready@12345");
    }

    /** No rate for the test currency in either direction, for the organization or shared. */
    void clearTestRates() {
        jdbc.sql("DELETE FROM \"CurrencyRate\" WHERE ? IN (\"fromCode\", \"toCode\")").param(TEST_CCY).update();
    }

    void tableRate(Object rate) {
        jdbc.sql("INSERT INTO \"CurrencyRate\" (id, \"organizationId\", \"fromCode\", \"toCode\", \"asOf\", rate, source)"
                        + " VALUES (?, ?, ?, ?, now() - interval '1 day', ?, 'test')")
                .params(UUID.randomUUID().toString(), demoOrg, TEST_CCY, functional, new BigDecimal(rate.toString()))
                .update();
    }

    // ------------------------------------------------------------------ B1

    @Test
    @DisplayName("B1: a PO receipt saved without a supplier takes the PO's; the supplier's bill then matches it")
    void receiptTakesOrderSupplier() {
        String supplier = partner("B1 Supplier");
        String product = stockProduct();
        JsonNode po = createPo(supplier, product, 10, 50);
        String poId = po.get("id").asString();
        Map<String, Object> grnLine = Map.of("purchaseOrderLineId", po.get("lines").get(0).get("id").asString(),
                "productId", product, "description", "Cement", "quantity", 10, "unitCost", 50);
        // The "New goods receipt" screen sends the order and its lines, never a supplier.
        String grn = ok(201, "POST", "/procurement/goods-receipts", Map.of("purchaseOrderId", poId, "warehouseId",
                warehouse, "lines", List.of(grnLine))).get("id").asString();
        assertThat(grnSupplier(grn)).isEqualTo(supplier);
        money("PATCH", "/procurement/goods-receipts/" + grn + "/post", null);
        assertThat(onHand(product)).isEqualByComparingTo("10");
        BigDecimal owed = supplierAp(supplier);
        assertThat(owed).isEqualByComparingTo("500");

        // The supplier's bill for the same goods matches the receipt: no second receipt, no second payable.
        JsonNode bill = ok(200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", today, "lines",
                List.of(line(product, 10, 50))));
        ok(200, "POST", "/vendor-bills/" + bill.get("id").asString() + "/post", Map.of("override3WM", true,
                "overrideApproval", true));
        assertThat(onHand(product)).isEqualByComparingTo("10");
        assertThat(supplierAp(supplier)).isEqualByComparingTo(owed);

        // Another supplier on a receipt of this order is refused.
        Res wrong = as("POST", "/procurement/goods-receipts", Map.of("purchaseOrderId", poId, "partnerId",
                partner("B1 Other"), "warehouseId", warehouse, "lines", List.of(grnLine)));
        assertThat(wrong.status()).as(wrong.raw().body()).isEqualTo(400);
        assertThat(wrong.body().get("message").asString())
                .isEqualTo("The supplier must be the purchase order's supplier");
    }

    @Test
    @DisplayName("B1: posting a PO receipt drafted without a supplier (before the fix) gives it the order's supplier")
    void legacyDraftGetsSupplierOnPost() {
        String supplier = partner("B1 Legacy");
        String product = stockProduct();
        JsonNode po = createPo(supplier, product, 2, 10);
        String grn = ok(201, "POST", "/procurement/goods-receipts", Map.of("purchaseOrderId", po.get("id").asString(),
                "warehouseId", warehouse, "lines", List.of(Map.of("purchaseOrderLineId", po.get("lines").get(0)
                        .get("id").asString(), "productId", product, "description", "Cement", "quantity", 2,
                        "unitCost", 10)))).get("id").asString();
        jdbc.sql("UPDATE \"GoodsReceiptNote\" SET \"partnerId\" = NULL WHERE id = ?").param(grn).update();
        money("PATCH", "/procurement/goods-receipts/" + grn + "/post", null);
        assertThat(grnSupplier(grn)).isEqualTo(supplier);
    }

    // ------------------------------------------------------------------ B2

    @Test
    @DisplayName("B2: the PO route pays the stocked lines a receipt vouchered, never its service lines")
    void payExcludesServiceLines() {
        String supplier = partner("B2 Services");
        String goods = stockProduct();
        String service = product("service", false);
        String poId = ok(201, "POST", "/procurement/purchase-orders", order(supplier, Map.of(), List.of(
                line(goods, 10, 50), line(service, 1, 100)))).get("id").asString();
        money("POST", "/procurement/purchase-orders/" + poId + "/receive", Map.of("warehouseId", warehouse, "lines",
                List.of(Map.of("productId", goods, "description", "Cement", "quantity", 10),
                        Map.of("productId", service, "description", "Delivery", "quantity", 1))));
        assertThat(supplierAp(supplier)).isEqualByComparingTo("500");
        Res over = keyed("POST", "/procurement/purchase-orders/" + poId + "/pay", Map.of("amount", 600, "method",
                "bank"));
        assertThat(over.body().get("message").asString()).isEqualTo("Payment of 600 exceeds remaining balance of 500");
        JsonNode pay = money("POST", "/procurement/purchase-orders/" + poId + "/pay", Map.of("method", "bank",
                "supplierBankAccountId", poSupplierBankAccount(poId)));
        assertThat(new BigDecimal(pay.get("amount").asString())).isEqualByComparingTo("500");
        assertThat(supplierAp(supplier)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("B2: after a return to the supplier the PO route pays only what the ledger still owes them")
    void payCappedByReturns() {
        String supplier = partner("B2 Returns");
        String product = stockProduct();
        String poId = createPo(supplier, product, 10, 10).get("id").asString();
        receive(poId, product, 10);
        String note = ok(201, "POST", "/procurement/debit-notes", Map.of("direction", "inbound", "partnerId", supplier,
                "reason", "returned_goods", "locationId", warehouse, "lines", List.of(Map.of("productId", product,
                        "description", "Cement", "quantity", 4, "unitPrice", 10)))).get("id").asString();
        money("PATCH", "/procurement/debit-notes/" + note + "/post", null);
        assertThat(supplierAp(supplier)).isEqualByComparingTo("60");

        Res over = keyed("POST", "/procurement/purchase-orders/" + poId + "/pay", Map.of("amount", 100, "method",
                "bank"));
        assertThat(over.status()).isEqualTo(400);
        // Gap fix h03-F4: the return now also takes the 4 off the receipt, so the order itself has 60 left.
        assertThat(over.body().get("message").asString()).isEqualTo("Payment of 100 exceeds remaining balance of 60");
        JsonNode pay = money("POST", "/procurement/purchase-orders/" + poId + "/pay", Map.of("method", "bank",
                "supplierBankAccountId", poSupplierBankAccount(poId)));
        assertThat(new BigDecimal(pay.get("amount").asString())).isEqualByComparingTo("60");
        assertThat(supplierAp(supplier)).isEqualByComparingTo("0");
        Res more = keyed("POST", "/procurement/purchase-orders/" + poId + "/pay", Map.of("method", "bank"));
        assertThat(more.status()).isEqualTo(400);
        assertThat(more.body().get("message").asString()).isEqualTo("This purchase order is already fully paid");
    }

    @Test
    @DisplayName("B2: a settled claim on the order lowers what the PO route still pays")
    void payNetOfClaims() {
        String supplier = partner("B2 Claims");
        String product = stockProduct();
        String poId = createPo(supplier, product, 10, 10).get("id").asString();
        receive(poId, product, 10);
        String claim = ok(201, "POST", "/procurement/claims", Map.of("partnerId", supplier, "type", "price", "amount",
                30, "description", "Over-priced", "purchaseOrderId", poId)).get("id").asString();
        ok(200, "POST", "/procurement/claims/" + claim + "/submit", null);
        ok(200, "POST", "/procurement/claims/" + claim + "/accept", Map.of("response", "agreed"));
        money("POST", "/procurement/claims/" + claim + "/settle", Map.of("classification", "price_variance"));
        assertThat(supplierAp(supplier)).isEqualByComparingTo("70");

        Res over = keyed("POST", "/procurement/purchase-orders/" + poId + "/pay", Map.of("amount", 71, "method",
                "bank"));
        assertThat(over.body().get("message").asString()).isEqualTo("Payment of 71 exceeds remaining balance of 70");
        JsonNode pay = money("POST", "/procurement/purchase-orders/" + poId + "/pay", Map.of("method", "bank",
                "supplierBankAccountId", poSupplierBankAccount(poId)));
        assertThat(new BigDecimal(pay.get("amount").asString())).isEqualByComparingTo("70");
        assertThat(supplierAp(supplier)).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------ B4

    @Test
    @DisplayName("B4: batch / expiry / serial tracking cannot change while the product has stock on hand")
    void trackingFlagsGuarded() {
        String supplier = partner("B4");
        String withStock = stockProduct();
        ok(200, "POST", "/procurement/goods-receipts/adhoc", Map.of("partnerId", supplier, "warehouseId", warehouse,
                "receivedAt", today, "lines", List.of(Map.of("productId", withStock, "description", "Item", "quantity",
                        5, "unitCost", 10))));
        Res batch = as("PATCH", "/products/" + withStock, Map.of("batchTracking", true));
        assertThat(batch.status()).as(batch.raw().body()).isEqualTo(400);
        assertThat(batch.body().get("message").asString()).startsWith("The batch tracking of ")
                .contains("cannot be changed while it has stock on hand");
        // Expiry implies batch: both count as changed.
        Res expiry = as("PATCH", "/products/" + withStock, Map.of("expiryTracking", true));
        assertThat(expiry.status()).isEqualTo(400);
        assertThat(expiry.body().get("message").asString()).startsWith("The batch / expiry tracking of ");
        assertThat(as("PATCH", "/products/" + withStock, Map.of("serialTracking", true)).status()).isEqualTo(400);
        // Re-sending the current values is not a change.
        ok(200, "PATCH", "/products/" + withStock, Map.of("batchTracking", false, "serialTracking", false));
        // Without stock the tracking can still be set up.
        String empty = stockProduct();
        ok(200, "PATCH", "/products/" + empty, Map.of("serialTracking", true));
        assertThat(jdbc.sql("SELECT \"serialTracking\" FROM \"Product\" WHERE id = ?").param(empty)
                .query(Boolean.class).single()).isTrue();
    }

    // ------------------------------------------------------------------ B5

    @Test
    @DisplayName("B5: an order without a currency is in the functional currency; a foreign one needs a real rate")
    void orderCurrencyAndRate() {
        String supplier = partner("B5 Orders");
        String product = stockProduct();
        JsonNode plain = createPo(supplier, product, 1, 10);
        assertThat(plain.get("currencyCode").asString()).isEqualTo(functional);
        assertThat(new BigDecimal(plain.get("exchangeRate").asString())).isEqualByComparingTo("1");

        clearTestRates();
        Res noRate = as("POST", "/procurement/purchase-orders", order(supplier, Map.of("currencyCode", TEST_CCY),
                List.of(line(product, 1, 4))));
        assertThat(noRate.status()).as(noRate.raw().body()).isEqualTo(400);
        assertThat(noRate.body().get("message").asString()).startsWith("No exchange rate for " + TEST_CCY + "→"
                + functional);
        tableRate(2500);
        JsonNode fromTable = ok(201, "POST", "/procurement/purchase-orders", order(supplier, Map.of("currencyCode",
                TEST_CCY), List.of(line(product, 1, 4))));
        assertThat(new BigDecimal(fromTable.get("exchangeRate").asString())).isEqualByComparingTo("2500");
        JsonNode stated = ok(201, "POST", "/procurement/purchase-orders", order(supplier, Map.of("currencyCode",
                TEST_CCY, "exchangeRate", 2600), List.of(line(product, 1, 4))));
        assertThat(new BigDecimal(stated.get("exchangeRate").asString())).isEqualByComparingTo("2600");
        clearTestRates();
    }

    @Test
    @DisplayName("B5: a quotation in another currency takes the table rate and is refused without one")
    void quotationRate() {
        String supplier = partner("B5 RFQ");
        String product = stockProduct();
        clearTestRates();
        JsonNode rfq = ok(201, "POST", "/procurement/rfqs", Map.of("title", "B5 " + suffix(), "lines", List.of(
                Map.of("productId", product, "description", "Cement", "quantity", 10)), "supplierIds", List.of(supplier),
                "deadline", LocalDate.now().plusDays(3).toString()));
        String rfqId = rfq.get("id").asString();
        ok(200, "POST", "/procurement/rfqs/" + rfqId + "/send", null);
        Map<String, Object> quote = Map.of("partnerId", supplier, "currencyCode", TEST_CCY, "lines", List.of(Map.of(
                "rfqLineId", rfq.get("lines").get(0).get("id").asString(), "unitPrice", 4)));
        Res refused = as("POST", "/procurement/rfqs/" + rfqId + "/quotations", quote);
        assertThat(refused.status()).as(refused.raw().body()).isEqualTo(400);
        assertThat(refused.body().get("message").asString()).startsWith("No exchange rate for " + TEST_CCY + "→"
                + functional);
        tableRate(2500);
        ok(200, "POST", "/procurement/rfqs/" + rfqId + "/quotations", quote);
        assertThat(jdbc.sql("SELECT \"exchangeRate\" FROM \"SupplierQuotation\" WHERE \"rfqId\" = ? AND status ="
                + " 'received'").param(rfqId).query(BigDecimal.class).single()).isEqualByComparingTo("2500");
        clearTestRates();
    }

    // ------------------------------------------------------------------ UB1

    @Test
    @DisplayName("UB1: a storekeeper with inventory:read lists staff for the stock forms without user:read")
    void staffOptionsLeastPrivilege() {
        String keeper = tokenWith("inventory:read");
        Res list = call("GET", "/inventory/staff-options", null, keeper);
        assertThat(list.status()).as(list.raw().body()).isEqualTo(200);
        List<String> ids = new ArrayList<>();
        for (JsonNode u : list.body()) {
            ids.add(u.get("id").asString());
            assertThat(u.has("email")).isFalse();
            assertThat(u.has("passwordHash")).isFalse();
        }
        assertThat(ids).contains(adminId);
        assertThat(call("GET", "/users", null, keeper).status()).isEqualTo(403);
        assertThat(call("GET", "/inventory/staff-options", null, weakToken()).status()).isEqualTo(403);
    }
}
