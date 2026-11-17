package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.settings.SettingResolverService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Review 2026-11-17 (ACR-2 / ACR-3 / ACR-4): a PO receipt raises AP (receipt voucher) and the supplier bill that takes
 * the receipt over is the open item for it. The supplier's open items always equal the AP ledger: PO payments are
 * applied to the bill, the PO open item keeps unbilled receipts only, a bill below the receipt price trues AP down, and
 * a bill can be required to have a goods receipt.
 */
class PoBillApplicationIT extends ApiTestSupport {

    @Autowired
    SettingResolverService settingResolver;

    String warehouse;
    String today = LocalDate.now().toString();

    @BeforeAll
    void fixtures() {
        warehouse = ok(201, "POST", "/inventory/locations", Map.of("code", "PB-" + suffix(), "name", "PO bill "
                + suffix(), "type", "warehouse")).get("id").asString();
    }

    static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String partner() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(id, demoOrg, "SUP-" + suffix(), "Applied " + suffix())
                .update();
        return id;
    }

    String product() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                        + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?,"
                        + " 'stockable', true, 'AVCO', 0, 0, now())")
                .params(id, demoOrg, "PB-" + suffix(), "Item " + suffix()).update();
        return id;
    }

    String ap() {
        return jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key ="
                + " 'accounts_payable' AND \"deletedAt\" IS NULL").param(demoOrg).query(String.class).single();
    }

    /** The supplier's AP in the ledger (credit positive). */
    BigDecimal ledger(String partnerId) {
        return jdbc.sql("SELECT COALESCE(SUM(\"baseCredit\" - \"baseDebit\"), 0) FROM \"JournalLine\" WHERE"
                + " \"organizationId\" = ? AND \"accountId\" = ? AND \"partnerId\" = ?").params(demoOrg, ap(), partnerId)
                .query(BigDecimal.class).single();
    }

    /** The supplier's open items in the live AP aging (bills + PO receipt vouchers). */
    BigDecimal aging(String partnerId) {
        // a snapshot under a minute old would be served instead of the live figures
        jdbc.sql("DELETE FROM \"ReportApAgingSnapshot\" WHERE \"organizationId\" = ?").param(demoOrg).update();
        return agingRow(partnerId);
    }

    BigDecimal agingRow(String partnerId) {
        for (JsonNode p : ok(200, "GET", "/reports/ap/aging", null).get("partners")) {
            if (partnerId.equals(p.get("partnerId").asString())) {
                return new BigDecimal(p.get("total").asString());
            }
        }
        return BigDecimal.ZERO;
    }

    Res keyed(String method, String path, Object body) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return call(method, path, body, h);
    }

    JsonNode money(String method, String path, Object body) {
        Res r = keyed(method, path, body);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(200);
        return r.body();
    }

    String po(String supplier, String productId, int qty, int price) {
        Map<String, Object> line = Map.of("productId", productId, "description", "Cement", "quantity", qty,
                "unitPrice", price);
        return ok(201, "POST", "/procurement/purchase-orders", Map.of("partnerId", supplier, "warehouseId", warehouse,
                "paymentType", "credit", "currencyCode", "UGX", "lines", List.of(line))).get("id").asString();
    }

    void receive(String poId, String productId, int qty) {
        money("POST", "/procurement/purchase-orders/" + poId + "/receive", Map.of("warehouseId", warehouse, "lines",
                List.of(Map.of("productId", productId, "description", "Cement", "quantity", qty))));
    }

    JsonNode bill(String supplier, String productId, int qty, int price) {
        JsonNode draft = ok(200, "POST", "/vendor-bills", Map.of("partnerId", supplier, "issueDate", today, "lines",
                List.of(Map.of("productId", productId, "description", "Cement", "quantity", qty, "unitPrice", price))));
        return draft;
    }

    JsonNode post(JsonNode bill) {
        return ok(200, "POST", "/vendor-bills/" + bill.get("id").asString() + "/post", Map.of("override3WM", true,
                "overrideApproval", true));
    }

    @Test
    @DisplayName("ACR-2: PO payments are applied to the bill that takes the receipt over; the supplier is owed once")
    void poPaymentsAppliedToTheBill() {
        String supplier = partner();
        String item = product();
        String poId = po(supplier, item, 10, 50);
        receive(poId, item, 4); // voucher: AP 200
        money("POST", "/procurement/purchase-orders/" + poId + "/pay", Map.of("amount", 120, "method", "bank"));
        assertThat(aging(supplier)).isEqualByComparingTo("80");
        assertThat(ledger(supplier)).isEqualByComparingTo("80");

        JsonNode posted = post(bill(supplier, item, 4, 50));
        // the bill is the open item for the 200; the 120 paid on the PO is applied to it
        assertThat(new BigDecimal(posted.get("amountResidual").asString())).isEqualByComparingTo("80");
        assertThat(posted.get("paymentStatus").asString()).isEqualTo("partial");
        // was 280: the bill's 200 plus the PO's 80 for the same goods
        assertThat(aging(supplier)).isEqualByComparingTo("80");
        assertThat(ledger(supplier)).isEqualByComparingTo("80");
        // the stored snapshot measures the same (ReportSnapshotService)
        Res rebuilt = call("POST", "/reports/accounting/rebuild-snapshots", null, adminToken);
        assertThat(rebuilt.status()).as(rebuilt.raw().body()).isEqualTo(201);
        assertThat(agingRow(supplier)).isEqualByComparingTo("80");
        Res payPo = keyed("POST", "/procurement/purchase-orders/" + poId + "/pay", Map.of("method", "bank"));
        assertThat(payPo.status()).isEqualTo(400);
        assertThat(payPo.body().get("message").asString()).isEqualTo("The goods received on this purchase order have"
                + " been billed — pay the supplier bill instead");

        // a later receipt is the PO's open item again, and only it can be paid on the PO
        receive(poId, item, 2); // voucher: AP 100
        assertThat(aging(supplier)).isEqualByComparingTo("180");
        assertThat(ledger(supplier)).isEqualByComparingTo("180");
        Res over = keyed("POST", "/procurement/purchase-orders/" + poId + "/pay", Map.of("amount", 101, "method",
                "bank"));
        assertThat(over.body().get("message").asString()).isEqualTo("Payment of 101 exceeds remaining balance of 100");

        // voiding the bill gives the applied payment back to the purchase order
        ok(200, "POST", "/vendor-bills/" + posted.get("id").asString() + "/cancel", null);
        assertThat(jdbc.sql("SELECT count(*) FROM \"PurchaseOrderBillApplication\" WHERE \"vendorBillId\" = ? AND"
                + " \"releasedAt\" IS NULL").param(posted.get("id").asString()).query(Long.class).single()).isZero();
        assertThat(aging(supplier)).isEqualByComparingTo("180");
        assertThat(ledger(supplier)).isEqualByComparingTo("180");
    }

    @Test
    @DisplayName("ACR-2: a PO paid in full before the bill leaves the matched bill paid")
    void fullyPaidPoBillIsPaid() {
        String supplier = partner();
        String item = product();
        String poId = po(supplier, item, 2, 75);
        receive(poId, item, 2);
        money("POST", "/procurement/purchase-orders/" + poId + "/pay", Map.of("method", "bank"));
        assertThat(ledger(supplier)).isEqualByComparingTo("0");
        JsonNode posted = post(bill(supplier, item, 2, 75));
        assertThat(new BigDecimal(posted.get("amountResidual").asString())).isEqualByComparingTo("0");
        assertThat(posted.get("paymentStatus").asString()).isEqualTo("paid");
        assertThat(aging(supplier)).isEqualByComparingTo("0");
        assertThat(ledger(supplier)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("ACR-3: a bill below the receipt price trues AP down to the bill (Dr AP / Cr PPV)")
    void billBelowReceiptPriceTruesApDown() {
        String supplier = partner();
        String item = product();
        String poId = po(supplier, item, 1, 100);
        receive(poId, item, 1);
        assertThat(ledger(supplier)).isEqualByComparingTo("100");
        JsonNode posted = post(bill(supplier, item, 1, 80));
        assertThat(new BigDecimal(posted.get("amountResidual").asString())).isEqualByComparingTo("80");
        // was 100 in the ledger against an 80 bill
        assertThat(ledger(supplier)).isEqualByComparingTo("80");
        assertThat(aging(supplier)).isEqualByComparingTo("80");
        String ppv = jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key IN"
                + " ('purchase_price_variance', 'stock_adjustment_expense') AND \"deletedAt\" IS NULL ORDER BY key ="
                + " 'purchase_price_variance' DESC LIMIT 1").param(demoOrg).query(String.class).single();
        BigDecimal varianceCredit = jdbc.sql("SELECT COALESCE(SUM(l.credit - l.debit), 0) FROM \"JournalLine\" l WHERE"
                + " l.\"journalEntryId\" = ? AND l.\"accountId\" = ?").params(posted.get("journalEntryId").asString(), ppv)
                .query(BigDecimal.class).single();
        assertThat(varianceCredit).isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("ACR-4: with bills requiring a receipt, a bill for goods not received is refused")
    void billWithoutReceiptCanBeBlocked() {
        String supplier = partner();
        String item = product();
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Setting\" (id, \"organizationId\", key, value, \"scopeType\", \"updatedAt\") VALUES"
                + " (?, ?, 'procurement.billWithoutReceipt', '\"block\"'::jsonb, 'organization', now())")
                .params(id, demoOrg).update();
        settingResolver.invalidate(demoOrg);
        try {
            JsonNode draft = bill(supplier, item, 3, 10);
            Res refused = call("POST", "/vendor-bills/" + draft.get("id").asString() + "/post", Map.of("override3WM",
                    true, "overrideApproval", true), adminToken);
            assertThat(refused.status()).isEqualTo(400);
            assertThat(refused.body().get("message").asString()).startsWith("Bill line ").contains(
                    ": 3 not received yet — post the goods receipt first");
            String poId = po(supplier, item, 3, 10);
            receive(poId, item, 3);
            assertThat(new BigDecimal(post(draft).get("amountResidual").asString())).isEqualByComparingTo("30");
        } finally {
            jdbc.sql("DELETE FROM \"Setting\" WHERE id = ?").param(id).update();
            settingResolver.invalidate(demoOrg);
        }
        // default (auto_receive): the bill receives what nobody received, as before
        String other = product();
        JsonNode auto = post(bill(supplier, other, 2, 10));
        assertThat(auto.get("status").asString()).isEqualTo("posted");
        assertThat(jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockItem\" WHERE \"productId\" = ?").param(other)
                .query(BigDecimal.class).single()).isEqualByComparingTo("2");
    }
}
