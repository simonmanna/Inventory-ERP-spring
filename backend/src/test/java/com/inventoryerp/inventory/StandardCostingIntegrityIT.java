package com.inventoryerp.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.inventory.application.StandardCostService;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockMove.Received;
import com.inventoryerp.inventory.application.StockReversalService;
import com.inventoryerp.inventory.application.StockReversalService.IssueSource;
import com.inventoryerp.inventory.application.StockReversalService.LedgerSource;
import com.inventoryerp.inventory.application.StockReversalService.ReturnFromSource;
import com.inventoryerp.inventory.application.StockReversalService.ReturnLine;
import com.inventoryerp.inventory.application.StockService.GlReceiptContext;
import com.inventoryerp.inventory.audit.InventoryAuditSupport;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Hardening R3 (gate G2): STANDARD costing ties stock, sub-ledger and GL together — a receipt without an active
 * standard enters at the cost price with the difference to PPV; a return after a standard change enters at the new
 * standard with the difference to PPV while COGS is reversed at the original cost; serial tracking is refused for
 * STANDARD on every path; reversals and returns never use today's cost; the per-product GL check is green after a
 * mixed scenario and red when a posted entry moves stamped stock value from one product to another. Each test runs in
 * its own organisation.
 */
class StandardCostingIntegrityIT extends InventoryAuditSupport {

    @Autowired
    StockReversalService reversals;

    @Autowired
    StandardCostService standards;

    @Autowired
    PostingService posting;

    <T> T as(AuditOrg o, Supplier<T> body) {
        return TenantContext.run(TenantStore.user(o.id(), o.userId()),
                () -> new TransactionTemplate(txm).execute(st -> body.get()));
    }

    JsonNode okAs(AuditOrg o, int status, String method, String path, Object body) {
        Res r = call(method, path, body, o.token());
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(status);
        return r.body();
    }

    Received grn(AuditOrg o, String product, Object qty, Object cost) {
        return as(o, () -> stock.receiveForDocument(StockMove.of(product, o.main(), dec(qty)).unitCost(dec(cost)),
                new GlReceiptContext("audit_seed", "seed-" + UUID.randomUUID(), Instant.now(), null)));
    }

    String sale(AuditOrg o, String product, Object qty) {
        String id = "sale-" + UUID.randomUUID();
        as(o, () -> stock.issue(StockMove.of(product, o.main(), dec(qty)).source("audit_sale", id)));
        return id;
    }

    String standardProduct(AuditOrg o, String code, Object costPrice) {
        Map<String, Object> extra = new HashMap<>();
        extra.put("costingMethod", "STANDARD");
        extra.put("costPrice", costPrice);
        return product(o, code, extra);
    }

    BigDecimal balance(AuditOrg o, String accountId) {
        return jdbc.sql("SELECT COALESCE(SUM(l.debit - l.credit), 0) FROM \"JournalLine\" l JOIN \"JournalEntry\" e"
                        + " ON e.id = l.\"journalEntryId\" WHERE e.\"organizationId\" = ? AND l.\"accountId\" = ?"
                        + " AND e.status::text <> 'draft'")
                .params(o.id(), accountId).query(BigDecimal.class).single();
    }

    String mapped(AuditOrg o, String key) {
        return jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key = ?")
                .params(o.id(), key).query(String.class).single();
    }

    String ppv(AuditOrg o) {
        return mapped(o, "purchase_price_variance");
    }

    JsonNode integrity(AuditOrg o) {
        return okAs(o, 200, "GET", "/inventory/reports/integrity", null);
    }

    static List<String> failing(JsonNode report) {
        List<String> keys = new ArrayList<>();
        report.get("checks").forEach(c -> {
            if (!c.get("ok").asBoolean()) {
                keys.add(c.get("key").asString());
            }
        });
        return keys;
    }

    static JsonNode check(JsonNode report, String key) {
        for (JsonNode c : report.get("checks")) {
            if (key.equals(c.get("key").asString())) {
                return c;
            }
        }
        throw new AssertionError("no check " + key);
    }

    /** Every check green, the per-product GL check actually run, and the GL tie-out within 0.01. */
    void tiesOut(AuditOrg o) {
        JsonNode r = integrity(o);
        assertThat(failing(r)).as(r.toString()).isEmpty();
        assertThat(check(r, "valuation_vs_gl_by_product").get("skipped").asBoolean()).as(r.toString()).isFalse();
        JsonNode t = okAs(o, 200, "GET", "/reports/inventory/gl-tieout?tolerance=0.01", null);
        assertThat(t.get("withinTolerance").asBoolean()).as(t.toString()).isTrue();
    }

    BigDecimal ledgerValue(AuditOrg o, String ledgerCode, String column) {
        return jdbc.sql("SELECT \"" + column + "\" FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                + " \"ledgerCode\" = ?").params(o.id(), ledgerCode).query(BigDecimal.class).single();
    }

    @Test
    @DisplayName("P1-8: STANDARD receipt without an active standard enters at the cost price; difference to PPV")
    void receiptWithoutStandard() {
        AuditOrg o = newOrg();
        String p = standardProduct(o, "STD", 5);
        Received r = grn(o, p, 10, 7);
        assertThat(r.unitCost()).isEqualByComparingTo("5");
        assertThat(ledgerValue(o, r.ledgerCode(), "totalValue")).isEqualByComparingTo("50");
        assertThat(balance(o, mapped(o, "stock_valuation"))).isEqualByComparingTo("50");
        assertThat(balance(o, ppv(o))).isEqualByComparingTo("20");
        assertThat(balance(o, mapped(o, "grni_accrued"))).isEqualByComparingTo("-70");
        assertThat(count("SELECT count(*) FROM \"StandardCostVariance\" WHERE \"organizationId\" = ? AND"
                + " \"productId\" = ? AND kind::text = 'purchase_price' AND amount = 20", o.id(), p)).isEqualTo(1);
        tiesOut(o);

        // No cost price and no standard: a valued receipt is refused.
        Map<String, Object> extra = new HashMap<>();
        extra.put("costingMethod", "STANDARD");
        extra.put("costPrice", null);
        String bare = product(o, "BARE", extra);
        assertThatThrownBy(() -> grn(o, bare, 1, 3)).isInstanceOf(ApiException.class)
                .hasMessage("Set a cost price or an active standard cost for STANDARD product BARE before"
                        + " receiving it");
    }

    @Test
    @DisplayName("P1-9: return after a standard change: stock at the new standard, PPV the difference, COGS original")
    void returnAfterStandardChange() {
        AuditOrg o = newOrg();
        String p = standardProduct(o, "STDR", 5);
        grn(o, p, 10, 5);
        String saleId = sale(o, p, 4);
        String cogs = mapped(o, "cogs");
        assertThat(balance(o, cogs)).isEqualByComparingTo("20");
        okAs(o, 200, "PATCH", "/products/" + p, Map.of("costPrice", 6));
        assertThat(balance(o, mapped(o, "stock_valuation"))).isEqualByComparingTo("36");

        List<StockReversalService.ReturnedRow> back = as(o, () -> reversals.returnFromSource(new ReturnFromSource(
                IssueSource.of("audit_sale", saleId), List.of(ReturnLine.of(p, null, new BigDecimal("4"))),
                "audit_return", "ret-" + UUID.randomUUID(), null, null, null, null, true, null)));
        assertThat(back).hasSize(1);
        assertThat(back.getFirst().unitCost()).isEqualByComparingTo("5");
        assertThat(ledgerValue(o, back.getFirst().ledgerCode(), "unitCost")).isEqualByComparingTo("6");
        assertThat(ledgerValue(o, back.getFirst().ledgerCode(), "totalValue")).isEqualByComparingTo("24");
        assertThat(balance(o, cogs)).as("COGS reversed at the original cost").isEqualByComparingTo("0");
        assertThat(balance(o, mapped(o, "stock_valuation"))).isEqualByComparingTo("60");
        assertThat(balance(o, ppv(o))).isEqualByComparingTo("-4");
        tiesOut(o);
    }

    @Test
    @DisplayName("P1-10: serial tracking is refused for STANDARD on every path")
    void serialStandardRefused() {
        AuditOrg o = newOrg();
        String std = standardProduct(o, "STDS", 5);
        Res r = call("PATCH", "/products/" + std, Map.of("serialTracking", true), o.token());
        assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).contains("cannot become serial-tracked");

        String serial = product(o, "SERS", Map.of("serialTracking", true));
        r = call("PATCH", "/products/" + serial, Map.of("costingMethod", "STANDARD"), o.token());
        assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).contains("cannot use STANDARD costing");
        r = call("PATCH", "/products/" + std, Map.of("pickingStrategy", "SERIAL"), o.token());
        assertThat(r.status()).as(r.raw().body()).isEqualTo(400);

        r = call("POST", "/inventory/standard-costs", Map.of("productId", serial, "effectiveFrom", "2020-01-01",
                "materialCost", 4), o.token());
        assertThat(r.status()).as(r.raw().body()).isEqualTo(400);

        // A legacy STANDARD + serial product (created before R3) with stock: a cost price change is refused.
        Map<String, Object> legacy = new HashMap<>();
        legacy.put("costingMethod", "STANDARD");
        legacy.put("costPrice", 5);
        legacy.put("serialTracking", true);
        String old = product(o, "LEG", legacy);
        as(o, () -> stock.receiveForDocument(StockMove.of(old, o.main(), BigDecimal.ONE).unitCost(dec(5))
                .serials(List.of("LEG-1")), new GlReceiptContext("audit_seed", "seed-" + UUID.randomUUID(),
                        Instant.now(), null)));
        r = call("PATCH", "/products/" + old, Map.of("costPrice", 7), o.token());
        assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).contains("serial-tracked");

        // A standard approved before serial tracking was switched on is not activated.
        JsonNode created = okAs(o, 201, "POST", "/inventory/standard-costs", Map.of("productId", std,
                "effectiveFrom", "2020-01-01", "materialCost", 6));
        String sid = created.get("id").asString();
        okAs(o, 200, "POST", "/inventory/standard-costs/" + sid + "/approve", null);
        jdbc.sql("UPDATE \"Product\" SET \"serialTracking\" = true WHERE id = ?").param(std).update();
        assertThatThrownBy(() -> as(o, () -> standards.activate(sid))).isInstanceOf(ApiException.class)
                .hasMessageContaining("Serial-tracked products keep specific identification");
    }

    @Test
    @DisplayName("Reversals and returns use the original cost, never today's average")
    void reversalsAtOriginalCost() {
        AuditOrg o = newOrg();
        String p = product(o, "AVG", Map.of());
        grn(o, p, 10, 5);
        String saleA = sale(o, p, 4);
        String saleB = sale(o, p, 2);
        grn(o, p, 10, 8); // the average moves to 6.875
        List<StockReversalService.ReturnedRow> back = as(o, () -> reversals.returnFromSource(new ReturnFromSource(
                IssueSource.of("audit_sale", saleA), null, "audit_return", "ret-" + UUID.randomUUID(), null, null,
                null, null, true, null)));
        assertThat(back).hasSize(1);
        assertThat(ledgerValue(o, back.getFirst().ledgerCode(), "unitCost")).isEqualByComparingTo("5");
        assertThat(ledgerValue(o, back.getFirst().ledgerCode(), "totalValue")).isEqualByComparingTo("20");

        String reversal = "rev-" + UUID.randomUUID();
        as(o, () -> reversals.reverseLedgerSource(new LedgerSource("audit_sale", saleB, "audit_sale_reversal",
                reversal, List.of("audit_sale"), "R3 reversal", null)));
        List<BigDecimal> costs = jdbc.sql("SELECT \"unitCost\" FROM \"InventoryLedger\" WHERE \"organizationId\" = ?"
                + " AND \"referenceType\" = 'audit_sale_reversal' AND \"referenceId\" = ?").params(o.id(), reversal)
                .query(BigDecimal.class).list();
        assertThat(costs).hasSize(1);
        assertThat(costs.getFirst()).isEqualByComparingTo("5");
        tiesOut(o);
    }

    @Test
    @DisplayName("Per-product GL: green after a mixed scenario, red when stamped GL value moves to another product")
    void perProductGl() {
        AuditOrg o = newOrg();
        String avco = product(o, "MIXA", Map.of());
        String std = standardProduct(o, "MIXS", 5);
        grn(o, avco, 10, 4);
        grn(o, std, 10, 6);
        sale(o, avco, 3);
        String saleS = sale(o, std, 2);
        okAs(o, 200, "POST", "/inventory/direct-stock/in", Map.of("locationId", o.main(), "responsibleById", o.userId(),
                "approvedById", o.userId(), "items", List.of(item(avco, 5, 5))));
        okAs(o, 200, "POST", "/inventory/direct-stock/out", Map.of("locationId", o.main(), "responsibleById",
                o.userId(), "approvedById", o.userId(), "items", List.of(item(std, 1, null))));
        okAs(o, 200, "PATCH", "/products/" + std, Map.of("costPrice", 7));
        as(o, () -> reversals.returnFromSource(new ReturnFromSource(IssueSource.of("audit_sale", saleS), null,
                "audit_return", "ret-" + UUID.randomUUID(), null, null, null, null, true, null)));
        tiesOut(o);
        long stamped = count("SELECT count(*) FROM \"JournalLine\" WHERE \"organizationId\" = ? AND dimensions ->>"
                + " 'inventoryProductId' IS NOT NULL", o.id());
        assertThat(stamped).isPositive();

        // Tamper: a balanced entry on the stock account whose two lines name different products moves 3 of value from
        // the AVCO product to the STANDARD one (posted journal lines cannot be edited). The organisation-level tie-out
        // and the sub-ledger stay green; the per-product GL check finds both products.
        String sv = mapped(o, "stock_valuation");
        as(o, () -> posting.post(PostingRequest.of("GEN", Instant.now(), "R3 tamper", "r3_tamper",
                UUID.randomUUID().toString(), null, List.of(
                        new PostingRequest.Line(sv, "3", null, null, "tamper", null, null,
                                Map.of("inventoryProductId", avco)),
                        new PostingRequest.Line(sv, null, "3", null, "tamper", null, null,
                                Map.of("inventoryProductId", std))))));
        JsonNode r = integrity(o);
        assertThat(failing(r)).as(r.toString()).containsExactly("valuation_vs_gl_by_product");
        JsonNode c = check(r, "valuation_vs_gl_by_product");
        assertThat(c.get("failures").asLong()).isEqualTo(2);
        List<String> products = new ArrayList<>();
        c.get("rows").forEach(x -> products.add(x.get("productId").asString()));
        assertThat(products).containsExactlyInAnyOrder(avco, std);
    }
}
