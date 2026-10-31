package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Landed cost vouchers (ADR-S007 §2–3, roadmap 1.8 acceptance tests): the UGX laptop import (customs, freight,
 * clearing, insurance → 2,380,000 per unit), the same charges after half the stock was sold (19M to stock, 19M to
 * COGS), cement / steel transport by value (1.8M / 1.2M), weight and manual bases, expensed and recoverable-tax
 * charges, duplicate supplier invoices, the approval workflow, reversal, FIFO lots, idempotent replay, GL balance and
 * tenant isolation.
 */
class LandedCostVoucherIT extends ApiTestSupport {

    @Autowired
    StockService stock;

    String adminId;
    String warehouse;
    String supplier;
    String forwarder;
    String customs;

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        warehouse = ok(201, "POST", "/inventory/locations", Map.of("code", "LV-" + suffix(), "name", "Import "
                + suffix(), "type", "warehouse")).get("id").asString();
        supplier = partner("Laptop Vendor");
        forwarder = partner("Freight Forwarder");
        customs = partner("Revenue Authority");
    }

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

    String product(String costing, boolean batch, String weight) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                        + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"batchTracking\", weight, \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, 'stockable', true, ?::\"CostingMethod\", 0, 0, ?, ?::numeric, now())")
                .params(id, demoOrg, "LV-" + suffix(), "Item " + suffix(), costing, batch, weight).update();
        return id;
    }

    String mapped(String key) {
        return jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key = ?"
                + " AND \"deletedAt\" IS NULL").params(demoOrg, key).query(String.class).single();
    }

    BigDecimal balance(String accountId) {
        return jdbc.sql("SELECT COALESCE(SUM(debit - credit), 0) FROM \"JournalLine\" WHERE \"organizationId\" = ?"
                + " AND \"accountId\" = ?").params(demoOrg, accountId).query(BigDecimal.class).single();
    }

    BigDecimal avg(String productId) {
        return jdbc.sql("SELECT \"runningAverageCost\" FROM \"StockItem\" WHERE \"productId\" = ? AND \"locationId\" = ?")
                .params(productId, warehouse).query(BigDecimal.class).single();
    }

    Map<String, String> key() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    JsonNode receive(List<Map<String, Object>> lines) {
        return ok(200, "POST", "/procurement/goods-receipts/adhoc", Map.of("partnerId", supplier, "warehouseId",
                warehouse, "receivedAt", java.time.Instant.now().toString(), "lines", lines));
    }

    static Map<String, Object> rline(String productId, Object qty, Object unitCost) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", productId);
        m.put("description", "Goods");
        m.put("quantity", qty);
        m.put("unitCost", unitCost);
        return m;
    }

    static Map<String, Object> charge(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    String component(String code) {
        ok(200, "GET", "/procurement/cost-components", null);
        return jdbc.sql("SELECT id FROM \"CostComponent\" WHERE \"organizationId\" = ? AND code = ?")
                .params(demoOrg, code).query(String.class).single();
    }

    JsonNode voucher(List<String> receipts, List<Map<String, Object>> charges) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("goodsReceiptIds", receipts);
        body.put("charges", charges);
        Res r = call("POST", "/procurement/landed-cost-vouchers", body, key());
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        return r.body();
    }

    JsonNode approveAndPost(String id) {
        ok(200, "POST", "/procurement/landed-cost-vouchers/" + id + "/approve", null);
        Res r = call("POST", "/procurement/landed-cost-vouchers/" + id + "/post", null, key());
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        return r.body();
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

    void assertBalanced(String journalEntryId) {
        Map<String, Object> t = jdbc.sql("SELECT SUM(debit) AS d, SUM(credit) AS c FROM \"JournalLine\" WHERE"
                + " \"journalEntryId\" = ?").param(journalEntryId).query().singleRow();
        assertThat((BigDecimal) t.get("d")).isEqualByComparingTo((BigDecimal) t.get("c"));
    }

    List<Map<String, Object>> laptopCharges() {
        return List.of(
                charge("costComponentId", component("DUTY"), "supplierId", customs, "supplierInvoiceRef", "C-" + suffix(),
                        "amount", 30_000_000),
                charge("costComponentId", component("FREIGHT"), "supplierId", forwarder, "supplierInvoiceRef",
                        "F-" + suffix(), "amount", 5_000_000),
                charge("costComponentId", component("CLEARING"), "supplierId", forwarder, "supplierInvoiceRef",
                        "K-" + suffix(), "amount", 2_000_000),
                charge("costComponentId", component("INSURANCE"), "description", "Marine insurance", "amount",
                        1_000_000));
    }

    @Test
    @DisplayName("laptops: 200M + 38M customs/freight/clearing/insurance → 2,380,000 per unit; GP 420,000 at 2.8M")
    void laptopImport() {
        String laptop = product("AVCO", false, "2.5");
        String grn = receive(List.of(rline(laptop, 100, 2_000_000))).get("id").asString();
        BigDecimal sv0 = balance(mapped("stock_valuation"));
        String clearing = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '2155'")
                .param(demoOrg).query(String.class).optional().orElse(null);
        BigDecimal cl0 = clearing == null ? BigDecimal.ZERO : balance(clearing);

        JsonNode draft = voucher(List.of(grn), laptopCharges());
        String id = draft.get("id").asString();
        assertThat(draft.get("status").asString()).isEqualTo("draft");
        assertThat(draft.get("voucherType").asString()).isEqualTo("voucher");
        assertThat(draft.get("totalAmount").asString()).isEqualTo("38000000");
        assertThat(draft.get("charges")).hasSize(4);

        JsonNode preview = ok(200, "GET", "/procurement/landed-cost-vouchers/" + id + "/preview", null);
        assertThat(preview.get("capitalizedAmount").asString()).isEqualTo("38000000");
        assertThat(jdbc.sql("SELECT count(*) FROM \"LandedCostAllocation\" WHERE \"landedCostId\" = ?").param(id)
                .query(Long.class).single()).isZero();

        Res early = call("POST", "/procurement/landed-cost-vouchers/" + id + "/post", null, key());
        assertThat(early.status()).isEqualTo(400);
        assertThat(early.body().get("message").asString()).startsWith("Only an approved landed cost voucher");

        JsonNode posted = approveAndPost(id);
        assertThat(posted.get("status").asString()).isEqualTo("posted");
        assertThat(posted.get("capitalizedAmount").asString()).isEqualTo("38000000");
        assertThat(posted.get("expensedAmount").asString()).isEqualTo("0");
        assertThat(posted.get("allocations")).hasSize(4);
        assertThat(avg(laptop)).isEqualByComparingTo("2380000");
        assertThat(balance(mapped("stock_valuation")).subtract(sv0)).isEqualByComparingTo("38000000");
        String clearingNow = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '2155'")
                .param(demoOrg).query(String.class).single();
        // Customs, freight and clearing raised their own supplier bills (clearing debited back); only the
        // supplier-less insurance line still waits in clearing.
        assertThat(balance(clearingNow).subtract(cl0)).isEqualByComparingTo("-1000000");
        assertBalanced(posted.get("journalEntryId").asString());
        String code = posted.get("code").asString();
        assertThat(payable(customs, code)).isEqualByComparingTo("30000000");
        assertThat(payable(forwarder, code)).isEqualByComparingTo("7000000");
        assertThat(billCount(code)).isEqualTo(3);
        assertThat(posted.get("suppliers")).hasSize(3);
        posted.get("charges").forEach(c -> {
            if (c.get("supplierId") != null && !c.get("supplierId").isNull()) {
                assertThat(c.get("billMode").asString()).isEqualTo("generate");
                assertThat(c.get("vendorBill").get("status").asString()).isEqualTo("posted");
            }
        });

        // Gross profit at 2,800,000 uses the landed unit cost.
        assertThat(new BigDecimal("2800000").subtract(avg(laptop))).isEqualByComparingTo("420000");

        Res again = call("POST", "/procurement/landed-cost-vouchers/" + id + "/post", null, key());
        assertThat(again.status()).isEqualTo(409);

        JsonNode byShipment = ok(200, "GET", "/procurement/landed-cost-reports/by-shipment?from=2000-01-01&to=2100-01-01",
                null);
        JsonNode row = null;
        for (JsonNode r : byShipment) {
            if (r.get("id").asString().equals(id)) {
                row = r;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("purchaseValue").asString()).isEqualTo("200000000");
        assertThat(row.get("landedValue").asString()).isEqualTo("238000000");
        assertThat(row.get("items").get(0).get("landedUnitCost").asString()).isEqualTo("2380000");

        // Reversal: the full capitalisation leaves stock again.
        Res rev = call("POST", "/procurement/landed-cost-vouchers/" + id + "/reverse", null, key());
        assertThat(rev.status()).as(rev.raw().body()).isEqualTo(200);
        assertThat(rev.body().get("status").asString()).isEqualTo("reversed");
        assertThat(avg(laptop)).isEqualByComparingTo("2000000");
        assertThat(balance(mapped("stock_valuation")).subtract(sv0)).isEqualByComparingTo("0");
        assertThat(payable(customs, code)).isEqualByComparingTo("0");
        assertThat(balance(clearingNow).subtract(cl0)).isEqualByComparingTo("0");
    }

    BigDecimal payable(String partnerId, String voucherCode) {
        return jdbc.sql("SELECT COALESCE(SUM(\"amountResidual\"), 0) FROM \"Document\" WHERE \"organizationId\" = ?"
                + " AND \"partnerId\" = ? AND \"documentType\" = 'vendor_bill' AND \"sourceDocument\" = ?"
                + " AND status = 'posted'").params(demoOrg, partnerId, voucherCode).query(BigDecimal.class).single();
    }

    long billCount(String voucherCode) {
        return jdbc.sql("SELECT count(*) FROM \"Document\" WHERE \"organizationId\" = ? AND \"documentType\" ="
                + " 'vendor_bill' AND \"sourceDocument\" = ? AND status = 'posted'").params(demoOrg, voucherCode)
                .query(Long.class).single();
    }

    String clearingAccountId() {
        String id = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '2155'")
                .param(demoOrg).query(String.class).optional().orElse(null);
        if (id != null) {
            return id;
        }
        // First voucher of the run creates 2155; force it through a throwaway draft.
        String item = product("AVCO", false, null);
        String grn = receive(List.of(rline(item, 1, 1))).get("id").asString();
        String draft = voucher(List.of(grn), List.of(charge("kind", "other", "amount", 1))).get("id").asString();
        approveAndPost(draft);
        return jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '2155'")
                .param(demoOrg).query(String.class).single();
    }

    JsonNode receiveFrom(String partnerId, List<Map<String, Object>> lines) {
        return ok(200, "POST", "/procurement/goods-receipts/adhoc", Map.of("partnerId", partnerId, "warehouseId",
                warehouse, "receivedAt", java.time.Instant.now().toString(), "lines", lines));
    }

    @Test
    @DisplayName("one shipment, two goods suppliers, four cost suppliers: own bills, linked bill, scoped charge, paid guard")
    void multiSupplierShipment() {
        String cementVendor = partner("Cement Vendor");
        String steelVendor = partner("Steel Vendor");
        String transport = partner("Transport Ltd");
        String clearingAgent = partner("Clearing Agent Ltd");
        String insurer = partner("Insurance Co");
        String cement = product("AVCO", false, null);
        String steel = product("AVCO", false, null);
        String g1 = receiveFrom(cementVendor, List.of(rline(cement, 100, 1_200_000))).get("id").asString();
        String g2 = receiveFrom(steelVendor, List.of(rline(steel, 100, 800_000))).get("id").asString();
        String steelLine = jdbc.sql("SELECT id FROM \"GoodsReceiptLine\" WHERE \"goodsReceiptId\" = ?").param(g2)
                .query(String.class).single();
        String clearing = clearingAccountId();
        BigDecimal cl0 = balance(clearing);

        // Customs assessment entered beforehand as its own bill against clearing: linked, not billed again.
        String customsRef = "ASM-" + suffix();
        Map<String, Object> billLine = new LinkedHashMap<>();
        billLine.put("accountId", clearing);
        billLine.put("description", "Import duty");
        billLine.put("quantity", 1);
        billLine.put("unitPrice", 30_000_000);
        Res created = call("POST", "/vendor-bills", Map.of("partnerId", customs, "issueDate",
                java.time.LocalDate.now().toString(), "reference", customsRef, "lines", List.of(billLine)), key());
        assertThat(created.status()).as(created.raw().body()).isBetween(200, 201);
        String assessmentId = created.body().get("id").asString();
        Res postedBill = call("POST", "/vendor-bills/" + assessmentId + "/post", Map.of(), key());
        assertThat(postedBill.status()).as(postedBill.raw().body()).isBetween(200, 201);
        BigDecimal cl1 = balance(clearing);
        assertThat(cl1.subtract(cl0)).isEqualByComparingTo("30000000");

        String tRef = "TR-" + suffix();
        JsonNode v = voucher(List.of(g1, g2), List.of(
                charge("costComponentId", component("FREIGHT"), "supplierId", transport, "supplierInvoiceRef", tRef,
                        "amount", 5_000_000),
                charge("costComponentId", component("DUTY"), "amount", 30_000_000, "billMode", "link",
                        "vendorBillId", assessmentId),
                charge("costComponentId", component("CLEARING"), "supplierId", clearingAgent, "supplierInvoiceRef",
                        "CA-" + suffix(), "amount", 2_000_000, "dueDate", java.time.LocalDate.now().plusDays(30)
                                .toString()),
                charge("costComponentId", component("INSURANCE"), "supplierId", insurer, "supplierInvoiceRef",
                        "INS-" + suffix(), "amount", 1_000_000, "allocationMethod", "value", "appliesToLineIds",
                        List.of(steelLine))));
        String id = v.get("id").asString();
        assertThat(v.get("charges").get(1).get("supplierId").asString()).isEqualTo(customs);
        assertThat(v.get("charges").get(1).get("supplierInvoiceRef").asString()).isEqualTo(customsRef);

        JsonNode posted = approveAndPost(id);
        String code = posted.get("code").asString();
        assertThat(posted.get("totalAmount").asString()).isEqualTo("38000000");
        assertThat(posted.get("suppliers")).hasSize(4);
        // 37M by value 60/40; insurance only on the steel -> cement 22.2M, steel 15.8M.
        Map<String, BigDecimal> byProduct = new LinkedHashMap<>();
        posted.get("allocations").forEach(a -> byProduct.merge(a.get("productId").asString(),
                new BigDecimal(a.get("allocatedAmount").asString()), BigDecimal::add));
        assertThat(byProduct.get(cement)).isEqualByComparingTo("22200000");
        assertThat(byProduct.get(steel)).isEqualByComparingTo("15800000");
        assertThat(avg(steel)).isEqualByComparingTo("958000");

        // Each cost supplier holds its own payable; the linked customs bill is untouched; clearing nets to zero.
        assertThat(payable(transport, code)).isEqualByComparingTo("5000000");
        assertThat(payable(clearingAgent, code)).isEqualByComparingTo("2000000");
        assertThat(payable(insurer, code)).isEqualByComparingTo("1000000");
        assertThat(billCount(code)).isEqualTo(3);
        assertThat(jdbc.sql("SELECT \"amountResidual\" FROM \"Document\" WHERE id = ?").param(assessmentId)
                .query(BigDecimal.class).single()).isEqualByComparingTo("30000000");
        assertThat(balance(clearing).subtract(cl0)).isEqualByComparingTo("0");
        assertBalanced(posted.get("journalEntryId").asString());

        // A paid supplier bill blocks the reversal.
        String transportBill = jdbc.sql("SELECT \"vendorBillId\" FROM \"LandedCostCharge\" WHERE \"landedCostId\" = ?"
                + " AND \"supplierId\" = ?").params(id, transport).query(String.class).single();
        jdbc.sql("UPDATE \"Document\" SET \"amountPaid\" = 100 WHERE id = ?").param(transportBill).update();
        Res blocked = call("POST", "/procurement/landed-cost-vouchers/" + id + "/reverse", null, key());
        assertThat(blocked.status()).isEqualTo(400);
        assertThat(blocked.body().get("message").asString()).contains("has payments");
        jdbc.sql("UPDATE \"Document\" SET \"amountPaid\" = 0 WHERE id = ?").param(transportBill).update();
        Res rev = call("POST", "/procurement/landed-cost-vouchers/" + id + "/reverse", null, key());
        assertThat(rev.status()).as(rev.raw().body()).isEqualTo(200);
        assertThat(billCount(code)).isZero();
        assertThat(balance(clearing).subtract(cl0)).isEqualByComparingTo("30000000");
    }

    @Test
    @DisplayName("supplier-bill validation: generate needs an invoice no.; one invoice = one currency, one bill")
    void supplierBillValidation() {
        String item = product("AVCO", false, null);
        String grn = receive(List.of(rline(item, 10, 100))).get("id").asString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("goodsReceiptIds", List.of(grn));
        body.put("charges", List.of(charge("kind", "freight", "amount", 10, "supplierId", forwarder, "billMode",
                "generate")));
        Res noRef = call("POST", "/procurement/landed-cost-vouchers", body, key());
        assertThat(noRef.status()).isEqualTo(400);
        assertThat(noRef.body().get("message").asString()).contains("needs a supplier and the supplier invoice number");

        String ref = "MIX-" + suffix();
        body.put("charges", List.of(
                charge("kind", "freight", "amount", 10, "supplierId", forwarder, "supplierInvoiceRef", ref),
                charge("kind", "handling", "amount", 10, "supplierId", forwarder, "supplierInvoiceRef", ref,
                        "currencyCode", "EUR", "exchangeRate", 4000)));
        Res mixed = call("POST", "/procurement/landed-cost-vouchers", body, key());
        assertThat(mixed.status()).isEqualTo(400);
        assertThat(mixed.body().get("message").asString()).contains("must share one currency");

        // Two charges on one supplier invoice become one bill.
        String id = voucher(List.of(grn), List.of(
                charge("kind", "freight", "amount", 10, "supplierId", forwarder, "supplierInvoiceRef", ref),
                charge("kind", "handling", "amount", 5, "supplierId", forwarder, "supplierInvoiceRef", ref)))
                .get("id").asString();
        String code = approveAndPost(id).get("code").asString();
        assertThat(billCount(code)).isEqualTo(1);
        assertThat(payable(forwarder, code)).isEqualByComparingTo("15");
    }

    @Test
    @DisplayName("50 of 100 laptops sold before the 38M arrives: 19M into stock, 19M to COGS; reversal reclasses")
    void lateChargesAfterPartialSale() {
        String laptop = product("AVCO", false, null);
        String grn = receive(List.of(rline(laptop, 100, 2_000_000))).get("id").asString();
        asAdmin(() -> stock.issue(StockMove.of(laptop, warehouse, new BigDecimal("50")).source("audit", suffix())));
        BigDecimal sv0 = balance(mapped("stock_valuation"));
        BigDecimal cogs0 = balance(mapped("cogs"));
        String id = voucher(List.of(grn), laptopCharges()).get("id").asString();
        JsonNode posted = approveAndPost(id);
        assertThat(posted.get("capitalizedAmount").asString()).isEqualTo("19000000");
        assertThat(posted.get("expensedAmount").asString()).isEqualTo("19000000");
        assertThat(avg(laptop)).isEqualByComparingTo("2380000");
        assertThat(balance(mapped("stock_valuation")).subtract(sv0)).isEqualByComparingTo("19000000");
        assertThat(balance(mapped("cogs")).subtract(cogs0)).isEqualByComparingTo("19000000");
        assertBalanced(posted.get("journalEntryId").asString());

        // Sell 25 more, then reverse: 25 on hand give back 9.5M; the other 9.5M moves stock → COGS.
        asAdmin(() -> stock.issue(StockMove.of(laptop, warehouse, new BigDecimal("25")).source("audit", suffix())));
        Res rev = call("POST", "/procurement/landed-cost-vouchers/" + id + "/reverse", null, key());
        assertThat(rev.status()).as(rev.raw().body()).isEqualTo(200);
        assertThat(avg(laptop)).isEqualByComparingTo("2000000");
        BigDecimal stockValue = jdbc.sql("SELECT quantity * \"runningAverageCost\" FROM \"StockItem\" WHERE"
                + " \"productId\" = ? AND \"locationId\" = ?").params(laptop, warehouse).query(BigDecimal.class).single();
        assertThat(stockValue).isEqualByComparingTo("50000000");
    }

    @Test
    @DisplayName("cement 60% / steel 40% by value: transport 3,000,000 → 1,800,000 / 1,200,000; weight basis too")
    void mixedShipmentByValueAndWeight() {
        String cement = product("AVCO", false, "50");
        String steel = product("AVCO", false, "25");
        JsonNode grn = receive(List.of(rline(cement, 100, 600), rline(steel, 100, 400)));
        String grnId = grn.get("id").asString();
        String id = voucher(List.of(grnId), List.of(charge("costComponentId", component("FREIGHT"), "supplierId",
                forwarder, "supplierInvoiceRef", "T-" + suffix(), "amount", 3_000_000))).get("id").asString();
        JsonNode posted = approveAndPost(id);
        Map<String, String> byProduct = new LinkedHashMap<>();
        posted.get("allocations").forEach(a -> byProduct.put(a.get("productId").asString(),
                a.get("allocatedAmount").asString()));
        assertThat(byProduct.get(cement)).isEqualTo("1800000");
        assertThat(byProduct.get(steel)).isEqualTo("1200000");
        assertThat(posted.get("charges").get(0).get("amount").asString()).isEqualTo("3000000");

        // Weight: cement 100 × 50 kg vs steel 100 × 25 kg → 2/3 vs 1/3 of 900.
        JsonNode grn2 = receive(List.of(rline(cement, 100, 600), rline(steel, 100, 400)));
        String id2 = voucher(List.of(grn2.get("id").asString()), List.of(charge("kind", "handling", "amount", 900,
                "allocationMethod", "weight", "creditAccountId", mapped("grni_accrued")))).get("id").asString();
        Map<String, String> byWeight = new LinkedHashMap<>();
        approveAndPost(id2).get("allocations").forEach(a -> byWeight.put(a.get("productId").asString(),
                a.get("allocatedAmount").asString()));
        assertThat(byWeight.get(cement)).isEqualTo("600");
        assertThat(byWeight.get(steel)).isEqualTo("300");
    }

    @Test
    @DisplayName("HANDLING (loading and unloading): capitalized, spread by quantity by default, balanced journal")
    void handlingByQuantity() {
        String heavy = product("AVCO", false, null);
        String light = product("AVCO", false, null);
        // Equal value, unequal quantity: by value each line would take 200; by quantity they take 100 and 300.
        String grn = receive(List.of(rline(heavy, 10, 300), rline(light, 30, 100))).get("id").asString();
        BigDecimal stock0 = balance(mapped("stock_valuation"));

        String id = voucher(List.of(grn), List.of(charge("costComponentId", component("HANDLING"), "amount", 400)))
                .get("id").asString();
        JsonNode posted = approveAndPost(id);

        assertThat(posted.get("capitalizedAmount").asString()).isEqualTo("400");
        assertThat(posted.get("expensedAmount").asString()).isEqualTo("0");
        assertThat(avg(heavy)).isEqualByComparingTo("310");
        assertThat(avg(light)).isEqualByComparingTo("110");
        assertThat(balance(mapped("stock_valuation")).subtract(stock0)).isEqualByComparingTo("400");
        assertBalanced(posted.get("journalEntryId").asString());
    }

    @Test
    @DisplayName("manual, selected lines, expense with cost centre, recoverable tax, duplicate supplier invoice")
    void chargeTreatments() {
        String a = product("AVCO", false, null);
        String b = product("AVCO", false, null);
        JsonNode grn = receive(List.of(rline(a, 10, 100), rline(b, 10, 100)));
        String grnId = grn.get("id").asString();
        List<String> lineIds = jdbc.sql("SELECT id FROM \"GoodsReceiptLine\" WHERE \"goodsReceiptId\" = ? ORDER BY"
                + " \"lineNumber\", id").param(grnId).query(String.class).list();
        String lineA = jdbc.sql("SELECT id FROM \"GoodsReceiptLine\" WHERE \"goodsReceiptId\" = ? AND \"productId\" = ?")
                .params(grnId, a).query(String.class).single();
        String lineB = lineIds.stream().filter(l -> !l.equals(lineA)).findFirst().orElseThrow();

        // Manual must sum to the charge.
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("goodsReceiptIds", List.of(grnId));
        bad.put("charges", List.of(charge("kind", "freight", "amount", 100, "allocationMethod", "manual",
                "manualAllocations", Map.of(lineA, 30, lineB, 60))));
        Res badManual = call("POST", "/procurement/landed-cost-vouchers", bad, key());
        assertThat(badManual.status()).isEqualTo(400);
        assertThat(badManual.body().get("message").asString()).contains("totals 90, not the charge amount 100");

        String cc = ok(201, "POST", "/cost-centers", Map.of("code", "IMP-" + suffix(), "name", "Import Dept " + suffix()))
                .get("id").asString();
        String ref = "INV-" + suffix();
        JsonNode v = voucher(List.of(grnId), List.of(
                charge("kind", "freight", "amount", 100, "allocationMethod", "manual", "manualAllocations",
                        Map.of(lineA, 70, lineB, 30), "supplierId", forwarder, "supplierInvoiceRef", ref, "taxAmount", 18),
                charge("kind", "duty", "amount", 50, "appliesToLineIds", List.of(lineB)),
                charge("costComponentId", component("DEMURRAGE"), "amount", 40, "costCenterId", cc),
                charge("costComponentId", component("IMPORT_VAT"), "amount", 25)));
        String id = v.get("id").asString();

        Map<String, Object> dup = new LinkedHashMap<>();
        dup.put("goodsReceiptIds", List.of(grnId));
        dup.put("charges", List.of(charge("kind", "freight", "amount", 5, "supplierId", forwarder,
                "supplierInvoiceRef", ref)));
        Res dupRes = call("POST", "/procurement/landed-cost-vouchers", dup, key());
        assertThat(dupRes.status()).isEqualTo(409);
        assertThat(dupRes.body().get("message").asString()).startsWith("Supplier invoice " + ref + " is already on");

        BigDecimal tax0 = balance(mapped("tax_receivable"));
        JsonNode posted = approveAndPost(id);
        assertThat(posted.get("totalAmount").asString()).isEqualTo("215");
        assertThat(posted.get("capitalizedAmount").asString()).isEqualTo("150");
        assertThat(posted.get("expensedAmount").asString()).isEqualTo("40");
        assertThat(posted.get("taxAmount").asString()).isEqualTo("43");
        assertThat(avg(a)).isEqualByComparingTo("107");
        assertThat(avg(b)).isEqualByComparingTo("108");
        assertThat(balance(mapped("tax_receivable")).subtract(tax0)).isEqualByComparingTo("43");
        String je = posted.get("journalEntryId").asString();
        assertBalanced(je);
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND \"costCenterId\" = ?"
                + " AND debit = 40").params(je, cc).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("FIFO lot and two receipts of one shipment; idempotent replay; GRN reversal blocked; tenants isolated")
    void shipmentFifoAndIsolation() {
        String lot = product("FIFO", true, null);
        String n1 = "L1-" + suffix();
        String n2 = "L2-" + suffix();
        Map<String, Object> l1 = rline(lot, 10, 50);
        l1.put("batchNumber", n1);
        Map<String, Object> l2 = rline(lot, 10, 50);
        l2.put("batchNumber", n2);
        String g1 = receive(List.of(l1)).get("id").asString();
        String g2 = receive(List.of(l2)).get("id").asString();
        JsonNode ship = ok(201, "POST", "/procurement/shipments", Map.of("reference", "BL-" + suffix(), "supplierId",
                supplier, "transportMode", "sea"));
        String shipId = ship.get("id").asString();
        ok(200, "POST", "/procurement/shipments/" + shipId + "/receipts", Map.of("goodsReceiptId", g1));
        ok(200, "POST", "/procurement/shipments/" + shipId + "/receipts", Map.of("goodsReceiptId", g2));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("shipmentId", shipId);
        body.put("charges", List.of(charge("kind", "freight", "amount", 200, "allocationMethod", "quantity")));
        Map<String, String> headers = key();
        Res first = call("POST", "/procurement/landed-cost-vouchers", body, headers);
        Res replay = call("POST", "/procurement/landed-cost-vouchers", body, headers);
        assertThat(first.status()).isEqualTo(200);
        assertThat(replay.body().get("id").asString()).isEqualTo(first.body().get("id").asString());
        String id = first.body().get("id").asString();
        assertThat(first.body().get("receipts")).hasSize(2);
        approveAndPost(id);
        List<BigDecimal> costs = jdbc.sql("SELECT \"unitCost\" FROM \"InventoryBatch\" WHERE \"productId\" = ?")
                .param(lot).query(BigDecimal.class).list();
        assertThat(costs).hasSize(2).allSatisfy(c -> assertThat(c).isEqualByComparingTo("60"));

        Res reverseGrn = call("PATCH", "/procurement/goods-receipts/" + g2 + "/reverse", Map.of("reason", "wrong"), key());
        assertThat(reverseGrn.status()).isEqualTo(400);
        assertThat(reverseGrn.body().get("message").asString()).contains("has posted landed cost");

        assertThat(ok(200, "GET", "/procurement/shipments/" + shipId, null).get("vouchers")).hasSize(1);
        assertThat(call("GET", "/procurement/landed-cost-vouchers/" + id, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/procurement/landed-cost-vouchers", null, otherToken).body()).isEmpty();
        assertThat(call("GET", "/procurement/shipments/" + shipId, null, otherToken).status()).isEqualTo(404);
        assertThat(call("POST", "/procurement/landed-cost-vouchers/" + id + "/reverse", null, otherToken).status())
                .isEqualTo(404);
    }
}
