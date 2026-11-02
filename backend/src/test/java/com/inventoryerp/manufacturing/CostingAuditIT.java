package com.inventoryerp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.extension.ExtendWith;
import com.inventoryerp.BooksGuard;

/**
 * PC-0 cost-flow audit ({@code docs/audits/costing-gl-trace.md}): each costing posting path, checked after every step
 * the same way as {@code LandedCostAuditIT} — new entries balance, the step leaves the inventory / GL tie-out
 * difference unchanged, the 1396 adjunct and 1397 NRV allowance move with this class's open adjustments, and only the
 * expected accounts moved, by the expected amounts (classification). Fixtures are this class's own products and
 * locations; assertions are deltas.
 */
@ExtendWith(BooksGuard.class)
class CostingAuditIT extends MfgTestSupport {

    @Autowired
    StockService stock;

    final List<String> mine = new ArrayList<>();

    String item(String costing) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                        + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?,"
                        + " 'stockable', true, ?::\"CostingMethod\", 0, 0, now())")
                .params(id, demoOrg, "CA-" + suffix(), "Audit " + suffix(), costing).update();
        mine.add(id);
        return id;
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

    Map<String, String> key() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    JsonNode keyed(String path) {
        Res r = call("POST", path, null, key());
        assertThat(r.status()).as(path + " → " + r.raw().body()).isIn(200, 201);
        return r.body();
    }

    BigDecimal avg(String productId, String loc) {
        return jdbc.sql("SELECT \"runningAverageCost\" FROM \"StockItem\" WHERE \"productId\" = ? AND \"locationId\" = ?")
                .params(productId, loc).query(BigDecimal.class).single();
    }

    // ------------------------------------------------------------------ books

    /** Watched roles: mapping key → account (null while the mapping is absent). */
    static final List<String> ROLES = List.of("stock_valuation", "cogs", "stock_adjustment_expense",
            "stock_adjustment_income", "wip", "overhead_absorbed", "grni_accrued", "inventory_revaluation",
            "inventory_nrv_expense", "inventory_nrv_allowance", "fx_gain", "fx_loss");

    String optionalMapping(String key) {
        return jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key = ?"
                + " AND \"deletedAt\" IS NULL").params(demoOrg, key).query(String.class).optional().orElse(null);
    }

    Map<String, String> accounts() {
        Map<String, String> a = new LinkedHashMap<>();
        ROLES.forEach(r -> a.put(r, optionalMapping(r)));
        a.put("adjunct", jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '1396' AND"
                + " \"deletedAt\" IS NULL").param(demoOrg).query(String.class).optional().orElse(null));
        return a;
    }

    BigDecimal base(String accountId) {
        if (accountId == null) {
            return BigDecimal.ZERO;
        }
        return jdbc.sql("SELECT coalesce(sum(\"baseDebit\" - \"baseCredit\"), 0) FROM \"JournalLine\" WHERE"
                + " \"organizationId\" = ? AND \"accountId\" = ?").params(demoOrg, accountId).query(BigDecimal.class)
                .single();
    }

    /** Open adjustments of this class's products in one valuation component. */
    BigDecimal open(String component) {
        if (mine.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return jdbc.sql("SELECT coalesce(sum(\"openAmount\"), 0) FROM \"CostValueAdjustment\" WHERE"
                        + " \"organizationId\" = ? AND \"reversedAt\" IS NULL AND \"valuationComponent\" = ? AND"
                        + " \"productId\" = ANY(?::text[])")
                .params(demoOrg, component, mine.toArray(String[]::new)).query(BigDecimal.class).single();
    }

    BigDecimal tieOut() {
        return new BigDecimal(ok(200, "GET", "/reports/inventory/gl-tieout?tolerance=0.05", null)
                .get("valuationVsLedger").asString());
    }

    /** Balances before a step and the journal entries that already existed (new entries are the step's). */
    record Snap(Map<String, BigDecimal> balances, BigDecimal adjunct, BigDecimal tie, String[] entries) {}

    Snap snap() {
        Map<String, BigDecimal> b = new LinkedHashMap<>();
        accounts().forEach((role, id) -> b.put(role, base(id)));
        String[] entries = jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"organizationId\" = ?").param(demoOrg)
                .query(String.class).list().toArray(String[]::new);
        return new Snap(b, open("price_difference"), tieOut(), entries);
    }

    static Map<String, String> moves(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    void assertBooks(Snap s, Map<String, String> expected) {
        assertThat(jdbc.sql("SELECT e.id FROM \"JournalEntry\" e JOIN \"JournalLine\" l ON l.\"journalEntryId\" = e.id"
                        + " WHERE e.\"organizationId\" = ? AND NOT (e.id = ANY(?::text[])) GROUP BY e.id HAVING"
                        + " sum(l.\"baseDebit\") <> sum(l.\"baseCredit\")").params(demoOrg, s.entries()).query(String.class)
                .list()).as("unbalanced journal entries").isEmpty();
        assertThat(tieOut().subtract(s.tie())).as("the step keeps inventory tied out to the GL")
                .isBetween(new BigDecimal("-0.05"), new BigDecimal("0.05"));
        Map<String, String> accounts = accounts();
        assertThat(base(accounts.get("adjunct")).subtract(s.balances().get("adjunct")))
                .as("1396 GL movement = open price-difference adjunct movement")
                .isEqualByComparingTo(open("price_difference").subtract(s.adjunct()));
        // Exact classification: every account moved by the step's entries is expected. Roles name mapped accounts;
        // "other" is the summed movement on accounts no role names (e.g. a variance or absorbed-cost account).
        Map<String, BigDecimal> actual = new LinkedHashMap<>();
        jdbc.sql("SELECT l.\"accountId\", sum(l.\"baseDebit\" - l.\"baseCredit\") AS v FROM \"JournalLine\" l JOIN"
                        + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE e.\"organizationId\" = ? AND"
                        + " NOT (e.id = ANY(?::text[])) GROUP BY 1").params(demoOrg, s.entries()).query().listOfRows()
                .forEach(r -> actual.put((String) r.get("accountId"), (BigDecimal) r.get("v")));
        Map<String, BigDecimal> expectedById = new LinkedHashMap<>();
        Map<String, String> roleOf = new LinkedHashMap<>();
        accounts.forEach((role, id) -> {
            if (id != null) {
                expectedById.merge(id, new BigDecimal(expected.getOrDefault(role, "0")), BigDecimal::add);
                roleOf.merge(id, role, (a, b) -> a + "/" + b);
            }
        });
        BigDecimal other = BigDecimal.ZERO;
        List<String> unexpected = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : actual.entrySet()) {
            if (!expectedById.containsKey(e.getKey())) {
                if (e.getValue().signum() != 0) {
                    other = other.add(e.getValue());
                    unexpected.add(e.getKey() + "=" + e.getValue().stripTrailingZeros().toPlainString());
                }
            }
        }
        expectedById.forEach((id, v) -> assertThat(actual.getOrDefault(id, BigDecimal.ZERO))
                .as("classification: " + roleOf.get(id) + "; all movements " + actual).isEqualByComparingTo(v));
        assertThat(other).as("movement on accounts no role names: " + unexpected)
                .isEqualByComparingTo(expected.getOrDefault("other", "0"));
    }

    // ------------------------------------------------------------------ 1 stock movements

    @Test
    @DisplayName("1 AVCO sale of 30 @ 10: Dr COGS 300 / Cr stock 300")
    void sale() {
        String p = item("AVCO");
        stockIn(warehouse, p, 100, 10);
        Snap s = snap();
        asAdmin(() -> stock.issue(StockMove.of(p, warehouse, new BigDecimal(30)).source("audit_sale", suffix())));
        assertBooks(s, moves("cogs", "300", "stock_valuation", "-300"));
    }

    @Test
    @DisplayName("1 waste of 5 @ 10: Dr stock adjustment expense 50 / Cr stock 50 (never COGS)")
    void waste() {
        String p = item("AVCO");
        stockIn(warehouse, p, 100, 10);
        Snap s = snap();
        asAdmin(() -> stock.issue(StockMove.of(p, warehouse, new BigDecimal(5)).moveType("waste")
                .source("audit_waste", suffix())));
        assertBooks(s, moves("stock_adjustment_expense", "50", "stock_valuation", "-50"));
    }

    @Test
    @DisplayName("1 customer return of 10 @ 10: Dr stock 100 / Cr COGS 100")
    void customerReturn() {
        String p = item("AVCO");
        stockIn(warehouse, p, 100, 10);
        asAdmin(() -> stock.issue(StockMove.of(p, warehouse, new BigDecimal(20)).source("audit_sale", suffix())));
        Snap s = snap();
        asAdmin(() -> stock.receiveReturn(StockMove.of(p, warehouse, new BigDecimal(10)).unitCost(BigDecimal.TEN)
                .source("audit_return", suffix())));
        assertBooks(s, moves("stock_valuation", "100", "cogs", "-100"));
    }

    @Test
    @DisplayName("1 transfer of 30 between warehouses: value conserved, no P&L, destination at the carried cost")
    void transfer() {
        String p = item("AVCO");
        stockIn(warehouse, p, 100, 10);
        String w2 = location("warehouse");
        Snap s = snap();
        asAdmin(() -> stock.transfer(StockMove.transfer(p, warehouse, w2, new BigDecimal(30))
                .source("audit_transfer", suffix())));
        assertBooks(s, moves());
        assertThat(avg(p, w2)).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("1 count gain +5 and loss −3 @ 10: Dr stock / Cr adjustment income 50, then Dr adjustment expense 30")
    void countAdjustments() {
        String p = item("AVCO");
        stockIn(warehouse, p, 100, 10);
        Snap s = snap();
        StockMove gain = StockMove.of(p, warehouse, BigDecimal.ZERO).source("audit_count", suffix());
        gain.countedQuantity = new BigDecimal(105);
        asAdmin(() -> stock.adjust(gain));
        assertBooks(s, moves("stock_valuation", "50", "stock_adjustment_income", "-50"));
        Snap s2 = snap();
        StockMove loss = StockMove.of(p, warehouse, BigDecimal.ZERO).source("audit_count", suffix());
        loss.countedQuantity = new BigDecimal(102);
        asAdmin(() -> stock.adjust(loss));
        assertBooks(s2, moves("stock_adjustment_expense", "30", "stock_valuation", "-30"));
    }

    // ------------------------------------------------------------------ 2 manufacturing

    @Test
    @DisplayName("2 absorption 70 (2 h × 10 / 20 / 5): memo on the order; at completion the output carries input + 70")
    void absorption() {
        String wc = ok(201, "POST", "/manufacturing/work-centers", Map.of("code", "CA-" + suffix(), "name", "Audit"))
                .get("id").asString();
        String input = item("AVCO");
        stockIn(bakery, input, 10, 10);
        String output = product("CAABS");
        mine.add(output);
        String bomId = bom(output, input, 1, 1, Map.of());
        ok(201, "POST", "/manufacturing/routings", Map.of("name", "Audit line " + suffix(), "bomId", bomId,
                "operations", List.of(Map.of("sequence", 1, "name", "Run", "workCenterId", wc, "durationMins", 60))));
        ok(200, "PUT", "/manufacturing/costing/rates/" + wc, Map.of("labourRatePerHour", 10, "machineRatePerHour", 20,
                "overheadRatePerHour", 5));
        Snap s = snap();
        String orderId = started(bomId).get("id").asString();
        String wo = ok(201, "POST", "/manufacturing/production-orders/" + orderId + "/work-orders/generate", null)
                .get(0).get("id").asString();
        ok(201, "POST", "/manufacturing/work-orders/" + wo + "/start", null);
        ok(201, "POST", "/manufacturing/work-orders/" + wo + "/complete", Map.of("labourMins", 120));
        Snap memo = snap();
        JsonNode absorbed = keyed("/manufacturing/costing/orders/" + orderId + "/absorb");
        assertThat(new BigDecimal(absorbed.get("total").asString())).as(absorbed.toString())
                .isEqualByComparingTo("70");
        assertBooks(memo, moves());
        ok(201, "POST", "/manufacturing/production-orders/" + orderId + "/complete", Map.of("qtyProduced", 1));
        // Input 10 out of stock into WIP, output 80 (10 + 70 absorbed) into stock; WIP clears; Cr absorbed 70.
        assertBooks(s, moves("stock_valuation", "70", "overhead_absorbed", "-70"));
    }

    @Test
    @DisplayName("2 production: 2 × input @ 10 consumed into WIP and out as finished goods — WIP clears, value kept")
    void production() {
        String input = item("AVCO");
        stockIn(bakery, input, 10, 10);
        String output = product("CAOUT");
        mine.add(output);
        String bomId = bom(output, input, 1, 2, Map.of());
        Snap s = snap();
        String orderId = started(bomId).get("id").asString();
        ok(201, "POST", "/manufacturing/production-orders/" + orderId + "/complete", Map.of("qtyProduced", 1));
        // 2 × 10 consumed into WIP and received as finished goods: stock and WIP net to zero, nothing to P&L.
        assertBooks(s, moves());
        assertThat(onHand(output, warehouse)).isEqualByComparingTo("1");
    }

    // ------------------------------------------------------------------ 3 valuation

    @Test
    @DisplayName("3 new standard 12 on 40 units carried at 10: Dr stock 80 / Cr standard cost revaluation 80")
    void standardActivation() {
        String p = item("AVCO");
        stockIn(warehouse, p, 40, 10);
        Snap s = snap();
        String std = ok(201, "POST", "/inventory/standard-costs", Map.of("productId", p, "effectiveFrom",
                LocalDate.now().toString(), "materialCost", 12, "convertMethod", true)).get("id").asString();
        ok(200, "POST", "/inventory/standard-costs/" + std + "/approve", null);
        keyed("/inventory/standard-costs/" + std + "/activate");
        assertBooks(s, moves("stock_valuation", "80", "other", "-80"));
    }

    @Test
    @DisplayName("3 revaluation 10 → 12 on 50 units: Dr stock 100 / Cr revaluation 100; reversal returns to zero")
    void revaluation() {
        String p = item("AVCO");
        stockIn(warehouse, p, 50, 10);
        Snap s = snap();
        String id = ok(201, "POST", "/inventory/revaluations", Map.of("kind", "revaluation", "date",
                LocalDate.now().toString(), "reason", "Audit", "lines", List.of(Map.of("productId", p, "locationId",
                        warehouse, "unitCostAfter", 12)))).get("id").asString();
        keyed("/inventory/revaluations/" + id + "/post");
        assertBooks(s, moves("stock_valuation", "100", "inventory_revaluation", "-100"));
        keyed("/inventory/revaluations/" + id + "/reverse");
        assertBooks(s, moves());
    }

    String nrvWrittenDown(String p) {
        stockIn(warehouse, p, 60, 10);
        Snap s = snap();
        String id = ok(201, "POST", "/inventory/revaluations", Map.of("kind", "nrv", "lines", List.of(Map.of(
                "productId", p, "locationId", warehouse, "netRealisableValue", 8)))).get("id").asString();
        keyed("/inventory/revaluations/" + id + "/post");
        assertBooks(s, moves("inventory_nrv_expense", "120", "inventory_nrv_allowance", "-120"));
        return id;
    }

    @Test
    @DisplayName("3 NRV 10 → 8 on 60 units: Dr NRV expense 120 / Cr allowance 120; a sale moves full cost to COGS")
    void nrv() {
        String p = item("AVCO");
        nrvWrittenDown(p);
        Snap sold = snap();
        asAdmin(() -> stock.issue(StockMove.of(p, warehouse, new BigDecimal(30)).source("audit_sale", suffix())));
        assertBooks(sold, moves("cogs", "300", "stock_valuation", "-300"));
    }

    @Test
    @DisplayName("3 NRV recovers above cost: the 120 allowance is reversed to zero, never beyond (IAS 2.33)")
    void nrvRecovery() {
        String p = item("AVCO");
        nrvWrittenDown(p);
        Snap s = snap();
        String id = ok(201, "POST", "/inventory/revaluations", Map.of("kind", "nrv", "lines", List.of(Map.of(
                "productId", p, "locationId", warehouse, "netRealisableValue", 25)))).get("id").asString();
        keyed("/inventory/revaluations/" + id + "/post");
        assertBooks(s, moves("inventory_nrv_allowance", "120", "inventory_nrv_expense", "-120"));
    }

    @Test
    @DisplayName("3 revaluation 10 → 8 on 50 units: Dr revaluation 100 / Cr stock 100")
    void revaluationDown() {
        String p = item("AVCO");
        stockIn(warehouse, p, 50, 10);
        Snap s = snap();
        String id = ok(201, "POST", "/inventory/revaluations", Map.of("kind", "revaluation", "lines", List.of(Map.of(
                "productId", p, "locationId", warehouse, "unitCostAfter", 8)))).get("id").asString();
        keyed("/inventory/revaluations/" + id + "/post");
        assertBooks(s, moves("inventory_revaluation", "100", "stock_valuation", "-100"));
    }

    @Test
    @org.junit.jupiter.api.Disabled("PC-0 finding V1 (owner decision): the release posts the consumed allowance to NRV"
            + " expense, not to cost of sales as ADR-S031 states; P&L total unchanged, COGS overstated")
    @DisplayName("3 release after selling half: the consumed allowance (60) leaves 1397 for cost of sales")
    void nrvRelease() {
        String p = item("AVCO");
        nrvWrittenDown(p);
        asAdmin(() -> stock.issue(StockMove.of(p, warehouse, new BigDecimal(30)).source("audit_sale", suffix())));
        Snap rel = snap();
        keyed("/inventory/price-differences/release");
        assertBooks(rel, moves("inventory_nrv_allowance", "60", "cogs", "-60"));
    }
}
