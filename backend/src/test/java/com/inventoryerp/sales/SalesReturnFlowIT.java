package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.tenancy.TenantContext;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** SG-7: dispositions, per-line reject, issue-cost reversal, return without credit, credit without return, write-off. */
class SalesReturnFlowIT extends ApiTestSupport {

    @Autowired
    StockService stock;

    @Autowired
    PlatformTransactionManager txm;

    final String sfx = UUID.randomUUID().toString().substring(0, 8);
    String item;
    String yard;
    String quarantine;
    String customer;
    String adminId;

    String location(String code) {
        return ok(201, "POST", "/inventory/locations", Map.of("code", code + sfx, "name", "SG7 " + code, "type", "warehouse")).get("id")
                .asString();
    }

    void receive(BigDecimal qty, BigDecimal cost) {
        TenantContext.run(TenantContext.TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm).executeWithoutResult(
                s -> stock.receive(StockMove.of(item, yard, qty).unitCost(cost))));
    }

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'").param(demoOrg)
                .query(String.class).single();
        item = ok(201, "POST", "/products", Map.of("code", "G7P-" + sfx, "name", "SG7 item " + sfx, "productType", "stockable",
                "trackInventory", true, "salesPrice", 100, "costPrice", 30)).get("id").asString();
        yard = location("G7Y");
        quarantine = location("G7Q");
        customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(customer, demoOrg, "G7C-" + sfx, "SG7 cust " + sfx).update();
        receive(new BigDecimal("1000"), new BigDecimal("30"));
    }

    JsonNode money(String path, Object body) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        Res r = call("POST", path, body, h);
        assertThat(r.status()).as(path + " → " + r.raw().body()).isLessThan(300);
        return r.body();
    }

    /** A delivered and invoiced order of {@code qty}. */
    String[] soldAndInvoiced(int qty) {
        String so = ok(201, "POST", "/sales/orders", Map.of("partnerId", customer, "locationId", yard, "lines", List.of(Map.of(
                "productId", item, "quantity", qty, "unitPrice", 100)))).get("id").asString();
        ok(200, "POST", "/sales/orders/" + so + "/confirm", null);
        JsonNode d = ok(201, "POST", "/sales/orders/" + so + "/deliveries", Map.of("vehiclePlate", "UAX 7"));
        String id = d.get("id").asString();
        money("/sales/deliveries/" + id + "/issue", null);
        money("/sales/deliveries/" + id + "/confirm", Map.of("receiverName", "Agent", "lines", List.of(Map.of("loadLineId", d.get("lines")
                .get(0).get("id").asString(), "deliveredQty", qty))));
        String inv = money("/sales/orders/" + so + "/invoices", Map.of()).get("id").asString();
        ok(200, "POST", "/invoices/" + inv + "/post", null);
        drainOutboxFully();
        return new String[] {so, inv};
    }

    String returnOf(String so, int qty) {
        JsonNode o = ok(200, "GET", "/sales/orders/" + so, null);
        String id = ok(201, "POST", "/sales/returns", Map.of("orderId", so, "reason", "damaged", "lines", List.of(Map.of("orderLineId", o.get("lines")
                .get(0).get("id").asString(), "quantity", qty)))).get("id").asString();
        ok(200, "POST", "/sales/returns/" + id + "/approve", null);
        return id;
    }

    BigDecimal onHand(String location) {
        return jdbc.sql("SELECT COALESCE(sum(quantity), 0) FROM \"StockItem\" WHERE \"productId\" = ? AND \"locationId\" = ?")
                .params(item, location).query(BigDecimal.class).single();
    }

    @Test
    @DisplayName("restock and damaged dispositions leave stock where they should; damaged needs a location")
    void dispositions() {
        String so = soldAndInvoiced(10)[0];
        String r = returnOf(so, 10);
        BigDecimal yardBefore = onHand(yard);
        BigDecimal qBefore = onHand(quarantine);
        money("/sales/returns/" + r + "/receive", Map.of());
        assertThat(onHand(yard)).isEqualByComparingTo(yardBefore.add(BigDecimal.TEN));
        String line = ok(200, "GET", "/sales/returns/" + r, null).get("lines").get(0).get("id").asString();
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        assertThat(call("POST", "/sales/returns/" + r + "/inspect", Map.of("lines", List.of(Map.of("lineId", line, "disposition", "damaged"))), h)
                .status()).isEqualTo(400);
        money("/sales/returns/" + r + "/inspect", Map.of("lines", List.of(Map.of("lineId", line, "disposition", "damaged", "locationId", quarantine))));
        assertThat(onHand(yard)).isEqualByComparingTo(yardBefore);
        assertThat(onHand(quarantine)).isEqualByComparingTo(qBefore.add(BigDecimal.TEN));
    }

    @Test
    @DisplayName("a rejected quantity leaves stock and earns no credit; the rest is credited")
    void perLineReject() {
        String[] s = soldAndInvoiced(10);
        String r = returnOf(s[0], 10);
        BigDecimal before = onHand(yard);
        money("/sales/returns/" + r + "/receive", Map.of());
        String line = ok(200, "GET", "/sales/returns/" + r, null).get("lines").get(0).get("id").asString();
        money("/sales/returns/" + r + "/inspect", Map.of("lines", List.of(Map.of("lineId", line, "disposition", "restock", "rejectedQty", 4,
                "rejectReason", "Not our goods"))));
        assertThat(onHand(yard)).as("6 stay, 4 go back").isEqualByComparingTo(before.add(new BigDecimal("6")));
        JsonNode closed = money("/sales/returns/" + r + "/close", Map.of("outcome", "credit_note"));
        assertThat(closed.get("creditAmount").asDouble()).isEqualTo(600);
    }

    @Test
    @DisplayName("a return goes back in at what the goods cost when issued, not today's average")
    void issueCostReversal() {
        String so = soldAndInvoiced(5)[0];
        receive(new BigDecimal("1000"), new BigDecimal("90"));
        String r = returnOf(so, 5);
        money("/sales/returns/" + r + "/receive", Map.of());
        JsonNode line = ok(200, "GET", "/sales/returns/" + r, null).get("lines").get(0);
        assertThat(line.get("issueUnitCost").asDouble()).isEqualTo(30);
    }

    @Test
    @DisplayName("return without a credit note, and a credit note without a return, both work; credit allocates and refunds")
    void separateFlows() {
        String[] s = soldAndInvoiced(2);
        String r = returnOf(s[0], 1);
        money("/sales/returns/" + r + "/receive", Map.of());
        money("/sales/returns/" + r + "/inspect", Map.of());
        JsonNode closed = money("/sales/returns/" + r + "/close", Map.of("outcome", "none"));
        assertThat(closed.get("creditNoteId").isNull() || !closed.has("creditNoteId")).isTrue();

        JsonNode cn = money("/sales/credit-notes", Map.of("partnerId", customer, "type", "goodwill", "reason", "Goodwill", "lines", List.of(Map.of(
                "description", "Goodwill", "quantity", 1, "unitPrice", 40))));
        String cnId = cn.get("id").asString();
        assertThat(cn.get("amountResidual").asDouble()).isEqualTo(40);
        money("/sales/credit-notes/" + cnId + "/allocate", Map.of("documentId", s[1], "amount", 15));
        assertThat(ok(200, "GET", "/sales/credit-notes?open=true&partnerId=" + customer, null).toString()).contains(cnId);
        JsonNode refunded = money("/sales/credit-notes/" + cnId + "/refund", Map.of("paymentMethod", "bank"));
        assertThat(refunded.get("refunded").asDouble()).as("only what is left").isEqualTo(25);
    }

    @Test
    @DisplayName("a write-off lowers the receivable and debits the bad debt account, once, up to the open balance")
    void writeOff() {
        String inv = soldAndInvoiced(3)[1];
        String badDebt = jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code LIKE '6%' AND \"isPostable\" AND \"deletedAt\" IS NULL ORDER BY code LIMIT 1")
                .param(demoOrg).query(String.class).single();
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        assertThat(call("POST", "/sales/invoices/" + inv + "/write-off", Map.of("reason", "Gone", "amount", 5000, "accountId", badDebt), h).status())
                .isEqualTo(400);
        JsonNode cn = money("/sales/invoices/" + inv + "/write-off", Map.of("reason", "Customer closed", "amount", 100, "accountId", badDebt));
        assertThat(cn.get("totalAmount").asDouble()).isEqualTo(100);
        assertThat(ok(200, "GET", "/invoices/" + inv, null).get("amountResidual").asDouble()).isEqualTo(200);
        BigDecimal debit = TenantContext.run(TenantContext.TenantStore.organization(demoOrg), () -> new TransactionTemplate(txm).execute(t -> jdbc.sql(
                "SELECT COALESCE(sum(l.debit), 0) FROM \"JournalLine\" l JOIN \"Document\" d ON d.\"journalEntryId\" = l.\"journalEntryId\" WHERE d.id = ? AND l.\"accountId\" = ?")
                .params(cn.get("id").asString(), badDebt).query(BigDecimal.class).single()));
        assertThat(debit).isEqualByComparingTo("100");
        assertThat(LocalDate.now()).isNotNull();
    }

    @Test
    @DisplayName("the credit routes need their permission")
    void authorization() {
        assertThat(assertAuthorizationMatrix(com.inventoryerp.sales.web.SalesCreditFlowController.class)).isGreaterThan(4);
    }
}
