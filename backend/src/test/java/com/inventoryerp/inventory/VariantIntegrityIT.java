package com.inventoryerp.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Phase I2 — variant identity (owner decision D2, audit V-5…V-10). Each test failed before its fix.
 */
class VariantIntegrityIT extends ApiTestSupport {

    @Autowired
    StockService stock;

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    final String today = LocalDate.now().toString();
    String warehouse;
    String adminId;

    @BeforeAll
    void fixtures() {
        warehouse = ok(201, "POST", "/inventory/locations", Map.of("code", "VI-" + sfx, "name", "Variant integrity " + sfx,
                "type", "warehouse")).get("id").asString();
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
    }

    String product(boolean hasVariants) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"hasVariants\", \"updatedAt\") VALUES (?, ?, ?, ?,"
                + " 'stockable', true, 'AVCO', 40, 100, ?, now())").params(id, demoOrg, "VI-"
                        + UUID.randomUUID().toString().substring(0, 8), "Shirt " + sfx, hasVariants).update();
        return id;
    }

    String variant(String product, String name, boolean active) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"ProductVariant\" (id, \"organizationId\", \"productId\", name, \"isActive\","
                + " \"combinationKey\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, now())").params(id, demoOrg, product,
                        name, active, id).update();
        return id;
    }

    /** A quant without a variant, as left by stock received before the product got variants. */
    void legacyStock(String product, String qty) {
        jdbc.sql("INSERT INTO \"StockItem\" (id, \"organizationId\", \"productId\", \"locationId\", \"variantKey\","
                + " quantity, \"runningAverageCost\", \"updatedAt\") VALUES (?, ?, ?, ?, '', ?, 40, now())").params(
                        UUID.randomUUID().toString(), demoOrg, product, warehouse, new BigDecimal(qty)).update();
    }

    <T> T inTenant(java.util.function.Supplier<T> work) {
        Object[] out = new Object[1];
        TenantContext.run(TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm)
                .executeWithoutResult(s -> out[0] = work.get()));
        @SuppressWarnings("unchecked")
        T t = (T) out[0];
        return t;
    }

    StockMove counted(String product, String qty) {
        StockMove m = StockMove.of(product, warehouse, null);
        m.countedQuantity = new BigDecimal(qty);
        return m;
    }

    BigDecimal quant(String product, String variantId) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockItem\" WHERE \"productId\" = ? AND \"variantKey\" = ?")
                .params(product, variantId == null ? "" : variantId).query(BigDecimal.class).single();
    }

    String partner(boolean customer) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"isSupplier\","
                + " \"isCompany\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, true, now())").params(id, demoOrg, "VI-"
                        + UUID.randomUUID().toString().substring(0, 8), "Partner " + sfx, customer, !customer).update();
        return id;
    }

    @Test
    @DisplayName("D2: a receipt must name an active variant even while legacy no-variant stock exists; legacy stock is"
            + " still issued until it runs out; a positive count never grows the legacy quant")
    void receiptsNeedAnActiveVariant() {
        String p = product(true);
        String red = variant(p, "Red", true);
        String old = variant(p, "Old", false);
        legacyStock(p, "5");

        assertThatThrownBy(() -> inTenant(() -> stock.receive(StockMove.of(p, warehouse, new BigDecimal("3"))
                .unitCost(new BigDecimal("40")))))
                .isInstanceOf(ApiException.class).hasMessageContaining("Choose a variant for");
        assertThatThrownBy(() -> inTenant(() -> stock.receive(StockMove.of(p, warehouse, new BigDecimal("3"))
                .variant(old).unitCost(new BigDecimal("40")))))
                .isInstanceOf(ApiException.class).hasMessageContaining("Variant is inactive");
        assertThat(quant(p, null)).isEqualByComparingTo("5");

        inTenant(() -> stock.receive(StockMove.of(p, warehouse, new BigDecimal("3")).variant(red)
                .unitCost(new BigDecimal("40"))));
        assertThat(quant(p, red)).isEqualByComparingTo("3");

        // Legacy stock without a variant may still be sold (issued) until it runs out.
        inTenant(() -> stock.issue(StockMove.of(p, warehouse, new BigDecimal("2"))));
        assertThat(quant(p, null)).isEqualByComparingTo("3");

        // A count that adds to the legacy quant is a receipt in disguise: refused. Counting it down is fine.
        assertThatThrownBy(() -> inTenant(() -> stock.adjust(counted(p, "9"))))
                .isInstanceOf(ApiException.class).hasMessageContaining("Choose a variant for");
        inTenant(() -> stock.adjust(counted(p, "1")));
        assertThat(quant(p, null)).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("V-5/V-8: a vendor bill for a product with variants names one; an invoice refuses an archived variant")
    void billsAndInvoicesValidateTheVariant() {
        String p = product(true);
        String red = variant(p, "Red", true);
        String old = variant(p, "Old", false);
        Res bill = as("POST", "/vendor-bills", Map.of("partnerId", partner(false), "issueDate", today, "lines",
                List.of(Map.of("productId", p, "description", "Shirts", "quantity", 4, "unitPrice", 30))));
        assertThat(bill.status()).as(bill.raw().body()).isEqualTo(400);
        assertThat(bill.raw().body()).contains("Choose a variant for");
        ok(200, "POST", "/vendor-bills", Map.of("partnerId", partner(false), "issueDate", today, "lines",
                List.of(Map.of("productId", p, "variantId", red, "description", "Shirts", "quantity", 4,
                        "unitPrice", 30))));

        Res inv = as("POST", "/invoices", Map.of("partnerId", partner(true), "issueDate", today, "lines",
                List.of(Map.of("productId", p, "variantId", old, "quantity", 1))));
        assertThat(inv.status()).as(inv.raw().body()).isEqualTo(400);
        assertThat(inv.raw().body()).contains("Variant not found");
    }

    @Test
    @DisplayName("V-6: a credit note against an invoice with two variants of a product must say which one it restocks")
    void creditNoteNamesTheVariantWhenAmbiguous() {
        String p = product(true);
        String red = variant(p, "Red", true);
        String blue = variant(p, "Blue", true);
        String customer = partner(true);
        String invoice = ok(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today, "lines",
                List.of(Map.of("productId", p, "variantId", red, "quantity", 1, "unitPrice", 100),
                        Map.of("productId", p, "variantId", blue, "quantity", 1, "unitPrice", 100)))).get("id")
                .asString();
        // Sales readiness INV-2: only a posted invoice can be credited.
        ok(200, "POST", "/invoices/" + invoice + "/post", null);
        Res cn = as("POST", "/credit-notes", Map.of("partnerId", customer, "issueDate", today, "reversedDocumentId",
                invoice, "lines", List.of(Map.of("productId", p, "quantity", 1, "unitPrice", 100))));
        assertThat(cn.status()).as(cn.raw().body()).isEqualTo(400);
        assertThat(cn.raw().body()).contains("Choose a variant for");
        ok(200, "POST", "/credit-notes", Map.of("partnerId", customer, "issueDate", today, "reversedDocumentId",
                invoice, "lines", List.of(Map.of("productId", p, "variantId", blue, "quantity", 1, "unitPrice", 100))));
    }

    @Test
    @DisplayName("V-7/V-9: purchase requests and BOMs name an active variant for a product with variants")
    void purchaseRequestsAndBomsValidateTheVariant() {
        String p = product(true);
        String red = variant(p, "Red", true);
        Res pr = as("POST", "/procurement/purchase-requests", Map.of("description", "Need shirts", "lines",
                List.of(Map.of("productId", p, "description", "Shirts", "quantity", 5))));
        assertThat(pr.status()).as(pr.raw().body()).isEqualTo(400);
        assertThat(pr.raw().body()).contains("Choose a variant for");
        Res prOk = as("POST", "/procurement/purchase-requests", Map.of("description", "Need shirts", "lines",
                List.of(Map.of("productId", p, "variantId", red, "description", "Shirts", "quantity", 5))));
        assertThat(prOk.status()).as(prOk.raw().body()).isIn(200, 201);

        String cloth = product(false);
        Res bom = as("POST", "/manufacturing/boms", Map.of("name", "Shirt BOM " + sfx, "outputProductId", p,
                "outputQuantity", 1, "lines", List.of(Map.of("componentProductId", cloth, "quantity", 2))));
        assertThat(bom.status()).as(bom.raw().body()).isEqualTo(400);
        assertThat(bom.raw().body()).contains("Choose a variant for");
        Res bomOk = as("POST", "/manufacturing/boms", Map.of("name", "Shirt BOM " + sfx, "outputProductId", p,
                "outputVariantId", red, "outputQuantity", 1, "lines", List.of(Map.of("componentProductId", cloth,
                        "quantity", 2))));
        assertThat(bomOk.status()).as(bomOk.raw().body()).isIn(200, 201);
    }

    @Test
    @DisplayName("V-10: a replenishment rule watches one variant; another variant's surplus does not hide its shortage")
    void replenishmentPerVariant() {
        String p = product(true);
        String red = variant(p, "Red", true);
        String blue = variant(p, "Blue", true);
        inTenant(() -> stock.receive(StockMove.of(p, warehouse, new BigDecimal("50")).variant(blue)
                .unitCost(new BigDecimal("40"))));
        inTenant(() -> stock.receive(StockMove.of(p, warehouse, new BigDecimal("2")).variant(red)
                .unitCost(new BigDecimal("40"))));

        Res noVariant = as("POST", "/inventory/replenishment/rules", Map.of("productId", p, "locationId", warehouse,
                "minQty", 10));
        assertThat(noVariant.status()).as(noVariant.raw().body()).isEqualTo(400);
        assertThat(noVariant.raw().body()).contains("Choose a variant for");
        String rule = ok(201, "POST", "/inventory/replenishment/rules", Map.of("productId", p, "variantId", red,
                "locationId", warehouse, "minQty", 10)).get("id").asString();
        ok(201, "POST", "/inventory/replenishment/scan", Map.of());
        Map<String, Object> alert = jdbc.sql("SELECT \"variantId\", \"onHand\", \"suggestedQty\" FROM"
                + " \"InventoryReplenishmentAlert\" WHERE \"ruleId\" = ?").param(rule).query().singleRow();
        assertThat(alert.get("variantId")).isEqualTo(red);
        assertThat((BigDecimal) alert.get("onHand")).as("red alone, not red + blue").isEqualByComparingTo("2");
        assertThat((BigDecimal) alert.get("suggestedQty")).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("V-10/U-9: the par report lists each variant below par; an unconvertible purchase unit flags its line"
            + " instead of failing the report")
    void reorderReportPerVariant() {
        String p = product(true);
        String red = variant(p, "Red", true);
        String blue = variant(p, "Blue", true);
        jdbc.sql("UPDATE \"Product\" SET \"minQuantity\" = 10 WHERE id = ?").param(p).update();
        inTenant(() -> stock.receive(StockMove.of(p, warehouse, new BigDecimal("50")).variant(blue)
                .unitCost(new BigDecimal("40"))));
        inTenant(() -> stock.receive(StockMove.of(p, warehouse, new BigDecimal("2")).variant(red)
                .unitCost(new BigDecimal("40"))));
        // A second product whose purchase unit has no conversion to its stock unit.
        String broken = product(false);
        String kg = jdbc.sql("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND category = 'weight'"
                + " LIMIT 1").param(demoOrg).query(String.class).optional().orElse(null);
        String pcs = jdbc.sql("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND category = 'unit'"
                + " LIMIT 1").param(demoOrg).query(String.class).optional().orElse(null);
        if (kg != null && pcs != null) {
            jdbc.sql("UPDATE \"Product\" SET \"minQuantity\" = 5, \"uomId\" = ?, \"purchaseUomId\" = ? WHERE id = ?")
                    .params(pcs, kg, broken).update();
        }

        Res r = as("GET", "/inventory/reports/reorder?locationId=" + warehouse, null);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        JsonNode lines = r.body();
        JsonNode redLine = null;
        for (JsonNode l : lines) {
            if (p.equals(l.path("productId").asString()) && red.equals(l.path("variantId").asString())) {
                redLine = l;
            }
            assertThat(blue).as("blue is above par").isNotEqualTo(l.path("variantId").asString());
        }
        assertThat(redLine).as(lines.toString()).isNotNull();
        assertThat(redLine.get("onHand").asDouble()).isEqualTo(2);
        if (kg != null && pcs != null) {
            assertThat(lines.toString()).contains("unitError");
        }
    }

    @Test
    @DisplayName("C-1: a variant's first count and return restock take the variant's cost, not the product's")
    void variantCostFallbacks() {
        String p = product(true);
        String red = variant(p, "Red", true);
        jdbc.sql("UPDATE \"ProductVariant\" SET \"costPrice\" = 70 WHERE id = ?").param(red).update();
        StockMove count = counted(p, "4");
        count.variantId = red;
        inTenant(() -> stock.adjust(count));
        assertThat(jdbc.sql("SELECT \"unitCost\" FROM \"InventoryLedger\" WHERE \"productId\" = ? AND \"variantId\" = ?"
                + " AND type::text = 'adjustment_in'").params(p, red).query(BigDecimal.class).single())
                .as("variant cost 70, not product cost 40").isEqualByComparingTo("70");

        String blue = variant(p, "Blue", true);
        jdbc.sql("UPDATE \"ProductVariant\" SET \"costPrice\" = 90 WHERE id = ?").param(blue).update();
        inTenant(() -> stock.receiveReturn(StockMove.of(p, warehouse, new BigDecimal("2")).variant(blue)));
        assertThat(jdbc.sql("SELECT \"runningAverageCost\" FROM \"StockItem\" WHERE \"productId\" = ? AND"
                + " \"variantKey\" = ?").params(p, blue).query(BigDecimal.class).single()).isEqualByComparingTo("90");
    }

    @Test
    @DisplayName("C-2: the item list and item detail value FIFO stock by its lots, not qty x running average")
    void itemValueByCostingMethod() {
        String p = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"batchTracking\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?,"
                + " ?, 'stockable', true, 'FIFO', true, 40, 100, now())").params(p, demoOrg, "VF-" + sfx, "Fifo " + sfx)
                .update();
        inTenant(() -> stock.receive(StockMove.of(p, warehouse, new BigDecimal("10")).batch("A-" + sfx)
                .unitCost(new BigDecimal("50"))));
        inTenant(() -> stock.receive(StockMove.of(p, warehouse, new BigDecimal("10")).batch("B-" + sfx)
                .unitCost(new BigDecimal("70"))));
        inTenant(() -> stock.issue(StockMove.of(p, warehouse, new BigDecimal("5"))));
        // 5 left of lot A at 50 + 10 of lot B at 70.
        JsonNode detail = ok(200, "GET", "/inventory/items/" + p + "?locationId=" + warehouse, null);
        assertThat(detail.get("totalValue").asDouble()).isEqualTo(950);
        JsonNode list = ok(200, "GET", "/inventory/product-stock-levels?locationId=" + warehouse + "&search=VF-" + sfx
                + "&pageSize=200", null);
        JsonNode row = null;
        for (JsonNode r : list.get("data")) {
            if (p.equals(r.get("id").asString())) {
                row = r;
            }
        }
        assertThat(row).as(list.toString()).isNotNull();
        assertThat(row.get("totalValue").asDouble()).isEqualTo(950);
    }
}
