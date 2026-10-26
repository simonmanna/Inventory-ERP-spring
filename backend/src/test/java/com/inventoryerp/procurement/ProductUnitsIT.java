package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Per-product units of measure and line unit snapshots (ADR-S003, V5): pack sizes declared per product, purchase /
 * sales / return lines in any enabled unit, stock always kept in the base unit, and the conversion frozen on the
 * line so editing a product's units never changes what an existing document posts.
 */
class ProductUnitsIT extends ApiTestSupport {

    String warehouse;
    String supplier;
    String customer;
    String pcs;
    String box;
    String pack;
    String today = LocalDate.now().toString();

    @BeforeAll
    void fixtures() {
        // Sales invoices and vendor bills move stock in the organization's oldest active warehouse.
        warehouse = jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND type = 'warehouse'"
                        + " AND \"isActive\" = true AND \"deletedAt\" IS NULL ORDER BY \"createdAt\" LIMIT 1")
                .param(demoOrg).query(String.class).optional()
                .orElseGet(() -> ok(201, "POST", "/inventory/locations", Map.of("code", "UW-" + suffix(), "name",
                        "Units WH", "type", "warehouse")).get("id").asString());
        supplier = partner("Bottler", true);
        customer = partner("Shop", false);
        // Organization-wide units all at factor 1: only the per-product rows know what a Box or a Pack holds.
        pcs = unit("PCS");
        box = unit("BOX");
        pack = unit("PACK");
    }

    // ------------------------------------------------------------------ helpers

    static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String partner(String name, boolean isSupplier) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"isCustomer\","
                        + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, now())")
                .params(id, demoOrg, "UP-" + suffix(), name + " " + suffix(), isSupplier, !isSupplier).update();
        return id;
    }

    String unit(String code) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"UnitOfMeasure\" (id, \"organizationId\", code, name, category, factor, ratio,"
                        + " \"roundingPrecision\", \"updatedAt\") VALUES (?, ?, ?, ?, 'unit', 1, 1, 1, now())")
                .params(id, demoOrg, code + "-" + suffix(), code).update();
        return id;
    }

    /** Stockable AVCO product counted in pieces, sold per piece at {@code salesPrice}. */
    String product(String name, int salesPrice) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                        + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"uomId\", \"salesUomId\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, 'stockable', true, 'AVCO', 0, ?, ?, ?, now())")
                .params(id, demoOrg, "UPR-" + suffix(), name + " " + suffix(), salesPrice, pcs, pcs).update();
        return id;
    }

    static Map<String, Object> unitRow(String uomId, Object factor, boolean purchase, boolean sales, Object salePrice) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uomId", uomId);
        m.put("factor", factor);
        m.put("isPurchase", purchase);
        m.put("isSales", sales);
        if (salePrice != null) {
            m.put("salePrice", salePrice);
        }
        return m;
    }

    JsonNode setUnits(String productId, List<Map<String, Object>> units) {
        return ok(200, "PATCH", "/products/" + productId, Map.of("units", units));
    }

    Map<String, String> key() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    JsonNode money(String method, String path, Object body) {
        Res r = call(method, path, body, key());
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(200);
        return r.body();
    }

    String createPo(String productId, String uomId, Object qty, Object price) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("productId", productId);
        line.put("description", "Stock");
        line.put("quantity", qty);
        line.put("unitPrice", price);
        if (uomId != null) {
            line.put("unitOfMeasureId", uomId);
        }
        return ok(201, "POST", "/procurement/purchase-orders", Map.of("partnerId", supplier, "warehouseId", warehouse,
                "paymentType", "credit", "currencyCode", "UGX", "lines", List.of(line))).get("id").asString();
    }

    JsonNode receiveAll(String poId, String productId, Object qty) {
        String poLine = jdbc.sql("SELECT id FROM \"PurchaseOrderLine\" WHERE \"purchaseOrderId\" = ?").param(poId)
                .query(String.class).single();
        return money("POST", "/procurement/purchase-orders/" + poId + "/receive", Map.of("warehouseId", warehouse,
                "lines", List.of(Map.of("purchaseOrderLineId", poLine, "productId", productId, "description",
                        "Stock", "quantity", qty))));
    }

    BigDecimal onHand(String productId) {
        return jdbc.sql("SELECT COALESCE(SUM(\"quantityChange\"), 0) FROM \"InventoryLedger\" WHERE \"productId\" = ?")
                .param(productId).query(BigDecimal.class).single();
    }

    BigDecimal avgCost(String productId) {
        return jdbc.sql("SELECT \"runningAverageCost\" FROM \"StockItem\" WHERE \"productId\" = ? AND \"locationId\" = ?")
                .params(productId, warehouse).query(BigDecimal.class).single();
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("pack sizes are per product: Box = 24 drinks and Box = 12 soap share one Box unit")
    void perProductPackSizes() {
        String drink = product("Soda", 1500);
        String soap = product("Soap", 3000);
        JsonNode updated = setUnits(drink, List.of(unitRow(box, 24, true, true, null)));
        assertThat(updated.get("units").get(0).get("factor").asString()).isEqualTo("24");
        assertThat(updated.get("units").get(0).get("uom").get("id").asString()).isEqualTo(box);
        setUnits(soap, List.of(unitRow(box, 12, true, true, null)));

        receiveAll(createPo(drink, box, 10, 24000), drink, 10);
        receiveAll(createPo(soap, box, 2, 36000), soap, 2);

        assertThat(onHand(drink)).isEqualByComparingTo("240");
        assertThat(avgCost(drink)).isEqualByComparingTo("1000");
        assertThat(onHand(soap)).isEqualByComparingTo("24");
        assertThat(avgCost(soap)).isEqualByComparingTo("3000");
        // The PO line remembers the unit it was ordered in and the conversion in force.
        Map<String, Object> poLine = jdbc.sql("SELECT \"unitOfMeasureId\", \"uomFactor\" FROM \"PurchaseOrderLine\" l"
                + " WHERE \"productId\" = ?").param(drink).query().singleRow();
        assertThat(poLine.get("unitOfMeasureId")).isEqualTo(box);
        assertThat((BigDecimal) poLine.get("uomFactor")).isEqualByComparingTo("24");
    }

    @Test
    @DisplayName("snapshot: changing Box from 24 to 20 after ordering does not change the receipt of that order")
    void snapshotSurvivesUnitEdits() {
        String drink = product("Juice", 2000);
        setUnits(drink, List.of(unitRow(box, 24, true, true, null)));
        String po = createPo(drink, box, 5, 24000);
        setUnits(drink, List.of(unitRow(box, 20, true, true, null)));
        receiveAll(po, drink, 5);
        assertThat(onHand(drink)).isEqualByComparingTo("120");
        // A new order uses the new size.
        receiveAll(createPo(drink, box, 1, 20000), drink, 1);
        assertThat(onHand(drink)).isEqualByComparingTo("140");
    }

    @Test
    @DisplayName("sales invoice: sell a Pack (6) at its own price or single pieces; stock leaves in pieces")
    void salesInMultipleUnits() {
        String drink = product("Cola", 1500);
        setUnits(drink, List.of(unitRow(box, 24, true, false, null), unitRow(pack, 6, false, true, 8500)));
        receiveAll(createPo(drink, box, 1, 24000), drink, 1);

        JsonNode invoice = ok(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today, "lines",
                List.of(Map.of("productId", drink, "quantity", 2, "uomId", pack),
                        Map.of("productId", drink, "quantity", 3))));
        JsonNode lines = invoice.get("lines");
        assertThat(lines.get(0).get("unitPrice").asString()).isEqualTo("8500");
        assertThat(lines.get(0).get("uomId").asString()).isEqualTo(pack);
        assertThat(lines.get(1).get("unitPrice").asString()).isEqualTo("1500");
        assertThat(lines.get(1).get("uomId").asString()).isEqualTo(pcs);
        ok(200, "POST", "/invoices/" + invoice.get("id").asString() + "/post", null);
        // 2 packs x 6 + 3 pieces = 15 pieces out of 24.
        assertThat(onHand(drink)).isEqualByComparingTo("9");

        // Box is purchase-only for this product.
        Res boxSale = as("POST", "/invoices", Map.of("partnerId", customer, "issueDate", today, "lines",
                List.of(Map.of("productId", drink, "quantity", 1, "uomId", box))));
        assertThat(boxSale.status()).isEqualTo(400);
        assertThat(boxSale.body().get("message").asString()).startsWith("Unit BOX-")
                .contains("is not enabled for sales on product Cola");
    }

    @Test
    @DisplayName("vendor bill in boxes consumes a receipt counted in pieces (matched in base units)")
    void billMatchesAcrossUnits() {
        String drink = product("Water", 800);
        setUnits(drink, List.of(unitRow(box, 24, true, true, null)));
        JsonNode grn = ok(200, "POST", "/procurement/goods-receipts/adhoc", Map.of("partnerId", supplier,
                "warehouseId", warehouse, "receivedAt", java.time.Instant.now().toString(), "lines",
                List.of(Map.of("productId", drink, "description", "Water", "quantity", 48, "unitCost", 500,
                        "uomId", pcs))));
        assertThat(grn.get("status").asString()).isEqualTo("posted");
        assertThat(onHand(drink)).isEqualByComparingTo("48");

        JsonNode bill = ok(200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", today, "lines",
                List.of(Map.of("productId", drink, "quantity", 2, "unitPrice", 12000, "uomId", box))));
        ok(200, "POST", "/vendor-bills/" + bill.get("id").asString() + "/post", Map.of("override3WM", true,
                "overrideApproval", true));
        // The 2 boxes were already received as 48 pieces: nothing is received twice.
        assertThat(onHand(drink)).isEqualByComparingTo("48");
        BigDecimal billed = jdbc.sql("SELECT \"billedQuantity\" FROM \"GoodsReceiptLine\" WHERE \"goodsReceiptId\" = ?")
                .param(grn.get("id").asString()).query(BigDecimal.class).single();
        assertThat(billed).isEqualByComparingTo("48");
    }

    @Test
    @DisplayName("return to supplier in boxes ships the base quantity the box represents")
    void supplierReturnInBoxes() {
        String drink = product("Tonic", 1200);
        setUnits(drink, List.of(unitRow(box, 24, true, true, null)));
        receiveAll(createPo(drink, box, 3, 24000), drink, 3);
        JsonNode note = ok(201, "POST", "/procurement/debit-notes", Map.of("direction", "inbound", "partnerId",
                supplier, "reason", "returned_goods", "locationId", warehouse, "lines", List.of(Map.of("productId",
                        drink, "description", "Damaged", "quantity", 1, "unitPrice", 24000, "uomId", box))));
        assertThat(note.get("lines").get(0).get("uomFactor").asString()).isEqualTo("24");
        ok(200, "PATCH", "/procurement/debit-notes/" + note.get("id").asString() + "/post", null);
        assertThat(onHand(drink)).isEqualByComparingTo("48");
    }

    @Test
    @DisplayName("the base unit is frozen once stock has moved; extra units are validated")
    void baseUnitGuardAndValidation() {
        String drink = product("Lemonade", 1000);
        Res sameAsBase = as("PATCH", "/products/" + drink, Map.of("units", List.of(unitRow(pcs, 1, true, true, null))));
        assertThat(sameAsBase.body().get("message").asString()).endsWith("extra units must differ from the base unit");
        Res duplicate = as("PATCH", "/products/" + drink, Map.of("units",
                List.of(unitRow(box, 24, true, true, null), unitRow(box, 12, true, true, null))));
        assertThat(duplicate.body().get("message").asString()).endsWith("is listed more than once");
        Res zero = as("PATCH", "/products/" + drink, Map.of("units", List.of(unitRow(box, 0, true, true, null))));
        assertThat(zero.status()).isEqualTo(400);

        // No stock yet: the base unit may still change.
        ok(200, "PATCH", "/products/" + drink, Map.of("uomId", box));
        ok(200, "PATCH", "/products/" + drink, Map.of("uomId", pcs));
        receiveAll(createPo(drink, null, 5, 1000), drink, 5);
        Res change = as("PATCH", "/products/" + drink, Map.of("uomId", box));
        assertThat(change.status()).isEqualTo(400);
        assertThat(change.body().get("message").asString()).contains("cannot be changed after stock has moved");
    }

    @Test
    @DisplayName("legacy uomConversion keeps working: purchase unit Box + uomConversion 24 converts receipts")
    void legacyConversionField() {
        String drink = product("Malt", 1800);
        ok(200, "PATCH", "/products/" + drink, Map.of("purchaseUomId", box, "uomConversion", 24));
        JsonNode p = ok(200, "GET", "/products/" + drink, null);
        assertThat(p.get("units").get(0).get("uomId").asString()).isEqualTo(box);
        assertThat(p.get("units").get(0).get("isSales").asBoolean()).isFalse();
        // No unit on the PO line: the product's purchase unit (Box) is used and frozen.
        receiveAll(createPo(drink, null, 2, 24000), drink, 2);
        assertThat(onHand(drink)).isEqualByComparingTo("48");
    }
}
