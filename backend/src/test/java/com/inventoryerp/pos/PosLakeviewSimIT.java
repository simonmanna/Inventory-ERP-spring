package com.inventoryerp.pos;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.pos.application.PosStockPostingService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Lakeview business simulation, POS core (reduced port of {@code test/business-simulation/lakeview-*.sim.spec.ts}
 * and {@code sim-kit.ts}): deterministic trading days ({@code mulberry32} + {@code planDay}) on two tills with menu
 * items (recipes) and direct items, counter and table sales, cash / mobile-money / card / mixed tenders, 10% discounts
 * and full refunds. Each day closes both shifts against an independent oracle and the books must tie out: Z-report
 * cash and sales, drawer ledgers, a balanced trial balance, on-hand stock, stock valuation and recipe COGS.
 * Days / ops per day: {@code -Dsim.days} / {@code -Dsim.ops} (defaults 3 / 24).
 */
class PosLakeviewSimIT extends ApiTestSupport {

    @Autowired
    StockService stock;

    @Autowired
    PosStockPostingService postings;

    static final BigDecimal UNIT_COST = BigDecimal.TEN;
    static final Map<String, Integer> STOCK = new LinkedHashMap<>();
    static final Map<String, Object[]> MENU = new LinkedHashMap<>();
    static final Map<String, Integer> DIRECT = new LinkedHashMap<>();

    static {
        STOCK.put("BEANS_G", 20_000);
        STOCK.put("MILK_ML", 50_000);
        STOCK.put("BUN", 2_000);
        MENU.put("ESPRESSO", new Object[] {6_000, Map.of("BEANS_G", 18)});
        MENU.put("CAPP_M", new Object[] {10_000, Map.of("BEANS_G", 18, "MILK_ML", 200)});
        MENU.put("BURGER", new Object[] {25_000, Map.of("BUN", 1)});
        DIRECT.put("WATER_500", 2_500);
        DIRECT.put("BEER", 7_000);
    }

    /** mulberry32 — same PRNG as the Nest sim kit. */
    static DoubleSupplier prng(int seed) {
        int[] a = {seed};
        return () -> {
            a[0] += 0x6d2b79f5;
            int t = a[0];
            t = (t ^ (t >>> 15)) * (t | 1);
            t ^= t + (t ^ (t >>> 7)) * (t | 61);
            return Integer.toUnsignedLong(t ^ (t >>> 14)) / 4294967296.0;
        };
    }

    record Item(String kind, String sku, int qty) {}

    record Op(int n, String kind, String till, List<Item> items, String tender, int discount) {}

    /** {@code planDay}. */
    static List<Op> planDay(int seed, int count) {
        DoubleSupplier r = prng(seed);
        List<String> menu = new ArrayList<>(MENU.keySet());
        List<String> direct = new ArrayList<>(DIRECT.keySet());
        List<Op> ops = new ArrayList<>();
        for (int n = 0; n < count; n++) {
            double roll = r.getAsDouble();
            String kind = roll < 0.7 ? "counter" : roll < 0.95 ? "table" : "refund";
            int len = 1 + (int) Math.floor(r.getAsDouble() * 3);
            List<Item> items = new ArrayList<>();
            for (int i = 0; i < len; i++) {
                if (r.getAsDouble() < 0.75) {
                    String sku = menu.get((int) Math.floor(r.getAsDouble() * menu.size()));
                    items.add(new Item("menu", sku, 1 + (int) Math.floor(r.getAsDouble() * 2)));
                } else {
                    String sku = direct.get((int) Math.floor(r.getAsDouble() * direct.size()));
                    items.add(new Item("direct", sku, 1 + (int) Math.floor(r.getAsDouble() * 2)));
                }
            }
            double t = r.getAsDouble();
            String till = r.getAsDouble() < 0.6 ? "COUNTER" : "BAR";
            String tender = t < 0.35 ? "cash" : t < 0.7 ? "mtn" : t < 0.9 ? "card" : "mixed";
            int discount = r.getAsDouble() < 0.08 ? 10 : 0;
            ops.add(new Op(n, kind, till, items, tender, discount));
        }
        return ops;
    }

    final Map<String, String> product = new LinkedHashMap<>();
    final Map<String, String> menuItem = new LinkedHashMap<>();
    final Map<String, String> register = new LinkedHashMap<>();
    final Map<String, String> drawer = new LinkedHashMap<>();
    final Map<String, Long> consumed = new LinkedHashMap<>();
    String warehouseId;
    String tag;
    String cardAccount;
    String mobileAccount;

    Res keyed(String method, String path, Object body) {
        return call(method, path, body, Map.of("Authorization", "Bearer " + adminToken, "Idempotency-Key",
                UUID.randomUUID().toString()));
    }

    JsonNode money(String method, String path, Object body) {
        Res r = keyed(method, path, body);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(200);
        return r.body();
    }

    BigDecimal ledger(String accountId) {
        return jdbc.sql("SELECT coalesce(sum(l.debit) - sum(l.credit), 0) FROM \"JournalLine\" l JOIN \"JournalEntry\" e"
                + " ON e.id = l.\"journalEntryId\" WHERE l.\"accountId\" = ? AND e.status::text IN ('posted', 'reversed')")
                .param(accountId).query(BigDecimal.class).single();
    }

    BigDecimal onHand(String sku) {
        return jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"StockItem\" WHERE \"productId\" = ?")
                .param(product.get(sku)).query(BigDecimal.class).single();
    }

    void boot() {
        tag = UUID.randomUUID().toString().substring(0, 5).toUpperCase();
        ok(201, "POST", "/pos/override/pin", Map.of("pin", "4321"));
        warehouseId = pinPosWarehouse();
        for (Map.Entry<String, Integer> s : STOCK.entrySet()) {
            String id = ok(201, "POST", "/products", Map.of("code", "LV-" + s.getKey() + "-" + tag, "name", s.getKey(),
                    "productType", "stockable", "trackInventory", true, "costingMethod", "AVCO", "costPrice", 0,
                    "salesPrice", 0)).get("id").asString();
            product.put(s.getKey(), id);
            TenantContext.run(TenantStore.organization(demoOrg), () -> stock.receive(StockMove.of(id, warehouseId,
                    BigDecimal.valueOf(s.getValue())).unitCost(UNIT_COST)));
        }
        for (Map.Entry<String, Integer> d : DIRECT.entrySet()) {
            product.put(d.getKey(), ok(201, "POST", "/products", Map.of("code", "LV-" + d.getKey() + "-" + tag,
                    "name", d.getKey(), "productType", "service", "salesPrice", d.getValue())).get("id").asString());
        }
        for (Map.Entry<String, Object[]> m : MENU.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Integer> recipe = (Map<String, Integer>) m.getValue()[1];
            List<Map<String, Object>> ingredients = new ArrayList<>();
            recipe.forEach((sku, q) -> ingredients.add(Map.of("productId", product.get(sku), "quantity", q)));
            menuItem.put(m.getKey(), ok(201, "POST", "/pos/menu/items", Map.of("name", m.getKey() + " " + tag,
                    "basePrice", m.getValue()[0], "ingredients", ingredients)).get("id").asString());
        }
        // Dedicated tender accounts: the shared DEMO bank / MOMO accounts belong to other suites' balance checks.
        cardAccount = ok(201, "POST", "/accounts", Map.of("code", "LV-CARD-" + tag, "name", "Sim card acquirer " + tag,
                "categoryId", category("bank"))).get("id").asString();
        mobileAccount = ok(201, "POST", "/accounts", Map.of("code", "LV-MOMO-" + tag, "name", "Sim MoMo " + tag,
                "categoryId", category("mobile_money"))).get("id").asString();
        for (String till : List.of("COUNTER", "BAR")) {
            JsonNode reg = ok(201, "POST", "/cash-registers", Map.of("code", "LV-" + till + "-" + tag, "name", till));
            register.put(till, reg.get("id").asString());
            drawer.put(till, reg.get("defaultAccountId").asString());
        }
    }

    String category(String key) {
        // AccountCategory is a global catalog (no organizationId column).
        return jdbc.sql("SELECT id FROM \"AccountCategory\" WHERE key = ?").param(key).query(String.class).single();
    }

    static int price(Item it) {
        return "menu".equals(it.kind()) ? (int) MENU.get(it.sku())[0] : DIRECT.get(it.sku());
    }

    List<Map<String, Object>> lines(List<Item> items, int discount) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Item it : items) {
            Map<String, Object> l = new LinkedHashMap<>();
            if ("menu".equals(it.kind())) {
                l.put("menuItemId", menuItem.get(it.sku()));
            } else {
                l.put("productId", product.get(it.sku()));
            }
            l.put("description", it.sku());
            l.put("quantity", it.qty());
            l.put("unitPrice", price(it));
            if (discount > 0) {
                l.put("discountPercent", discount);
                l.put("discountReason", "Loyal regular");
            }
            out.add(l);
        }
        return out;
    }

    static long gross(List<Item> items, int discount) {
        long g = 0;
        for (Item it : items) {
            g += (long) price(it) * it.qty();
        }
        return g - g * discount / 100;
    }

    List<Map<String, Object>> tenders(String tender, long amount) {
        return switch (tender) {
            case "cash" -> List.of(Map.of("method", "cash", "amount", amount));
            case "mtn" -> List.of(Map.of("method", "mobile_money", "amount", amount, "reference", "MTN-" + UUID.randomUUID(),
                    "accountId", mobileAccount));
            case "card" -> List.of(Map.of("method", "card", "amount", amount, "reference", "CARD-" + UUID.randomUUID(),
                    "accountId", cardAccount));
            default -> List.of(Map.of("method", "cash", "amount", amount / 2), Map.of("method", "card",
                    "amount", amount - amount / 2, "reference", "CARD-" + UUID.randomUUID(), "accountId", cardAccount));
        };
    }

    static long cashPart(String tender, long amount) {
        return "cash".equals(tender) ? amount : "mixed".equals(tender) ? amount / 2 : 0;
    }

    void consume(List<Item> items) {
        for (Item it : items) {
            if ("menu".equals(it.kind())) {
                @SuppressWarnings("unchecked")
                Map<String, Integer> recipe = (Map<String, Integer>) MENU.get(it.sku())[1];
                recipe.forEach((sku, q) -> consumed.merge(sku, (long) q * it.qty(), Long::sum));
            }
        }
    }

    record Sale(String invoiceId, String till, String tender, long gross) {}

    @Test
    void tradingDaysTieOut() {
        try {
            run();
        } finally {
            forceCloseSessions(register.values());
        }
    }

    void run() {
        int days = Integer.getInteger("sim.days", 3);
        int perDay = Integer.getInteger("sim.ops", 24);
        boot();
        long totalGross = 0;
        for (int day = 1; day <= days; day++) {
            Map<String, String> session = new LinkedHashMap<>();
            // Drawers are not banked in this port: each shift opens on the cash the last one counted.
            Map<String, Long> cash = new LinkedHashMap<>();
            for (String till : register.keySet()) {
                cash.put(till, ledger(drawer.get(till)).longValueExact());
            }
            Map<String, Long> sales = new LinkedHashMap<>(Map.of("COUNTER", 0L, "BAR", 0L));
            for (String till : register.keySet()) {
                session.put(till, money("POST", "/cash-sessions/open", Map.of("cashRegisterId", register.get(till),
                        "openingFloat", cash.get(till))).get("id").asString());
            }
            List<Sale> refundable = new ArrayList<>();
            for (Op op : planDay(1000 * day + 7, perDay)) {
                String sid = session.get(op.till());
                if ("refund".equals(op.kind())) {
                    if (refundable.isEmpty()) {
                        continue;
                    }
                    Sale s = refundable.removeFirst();
                    // A refund pays back over the original tenders, from the shift the sale was rung on.
                    money("POST", "/pos/invoices/" + s.invoiceId() + "/refund", Map.of("reason", "Wrong order",
                            "overrideById", jdbc.sql("SELECT id FROM \"User\" WHERE email = 'admin@demo.test'")
                                    .query(String.class).single(), "overridePin", "4321",
                            "stockDisposition", "no_return", "cashSessionId", session.get(s.till())));
                    cash.merge(s.till(), -cashPart(s.tender(), s.gross()), Long::sum);
                    sales.merge(s.till(), -s.gross(), Long::sum);
                    totalGross -= s.gross();
                    continue;
                }
                long g = gross(op.items(), op.discount());
                JsonNode res;
                if ("table".equals(op.kind())) {
                    String table = ok(201, "POST", "/pos/tables", Map.of("name", "LV " + tag + " " + day + "-" + op.n(),
                            "number", 700_000 + day * 1000 + op.n() + (int) (Math.random() * 100) * 10_000)).get("id")
                            .asString();
                    Map<String, Object> save = new LinkedHashMap<>(Map.of("cashSessionId", sid, "guestCount", 2,
                            "newRound", true, "lines", lines(op.items(), op.discount())));
                    if (op.discount() > 0) {
                        save.put("discountReason", "Loyal regular");
                    }
                    ok(201, "POST", "/pos/tabs/" + table + "/save", save);
                    res = money("POST", "/pos/tabs/" + table + "/settle", Map.of("cashSessionId", sid,
                            "tenders", tenders(op.tender(), g)));
                } else {
                    Map<String, Object> body = new LinkedHashMap<>(Map.of("cashSessionId", sid,
                            "lines", lines(op.items(), op.discount()), "tenders", tenders(op.tender(), g),
                            "expectedTotal", g));
                    if (op.discount() > 0) {
                        body.put("discountReason", "Loyal regular");
                    }
                    res = money("POST", "/pos/checkout", body);
                }
                assertThat(res.get("total").asLong()).as("op " + op).isEqualTo(g);
                cash.merge(op.till(), cashPart(op.tender(), g), Long::sum);
                sales.merge(op.till(), g, Long::sum);
                totalGross += g;
                consume(op.items());
                refundable.add(new Sale(res.get("invoiceId").asString(), op.till(), op.tender(), g));
            }
            for (String till : register.keySet()) {
                String sid = session.get(till);
                TenantContext.run(TenantStore.organization(demoOrg), () -> postings.drainSession(sid));
                JsonNode x = ok(200, "GET", "/pos/reports/x-report?cashSessionId=" + sid, null);
                assertThat(new BigDecimal(x.get("totals").get("expectedCash").asString()))
                        .as("D" + day + " " + till + " expected cash").isEqualByComparingTo(BigDecimal.valueOf(cash.get(till)));
                money("POST", "/cash-sessions/close", withUncountedTenders(Map.of("sessionId", sid, "closingCounted",
                        cash.get(till))));
                JsonNode z = ok(200, "GET", "/pos/reports/z-report?cashSessionId=" + sid, null);
                assertThat(new BigDecimal(z.get("totals").get("netSalesAfterRefunds").asString()))
                        .as("D" + day + " " + till + " Z net sales").isEqualByComparingTo(BigDecimal.valueOf(sales.get(till)));
                assertThat(new BigDecimal(z.get("totals").get("taxAfterRefunds").asString())).isEqualByComparingTo("0");
            }
            for (String till : register.keySet()) {
                assertThat(ledger(drawer.get(till))).as("D" + day + " " + till + " drawer ledger = counted cash")
                        .isEqualByComparingTo(BigDecimal.valueOf(cash.get(till)));
            }
        }

        // ── certification ──
        BigDecimal imbalance = jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\") - sum(l.\"baseCredit\"), 0) FROM \"JournalLine\" l"
                + " JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE e.\"organizationId\" = ?")
                .param(demoOrg).query(BigDecimal.class).single();
        assertThat(imbalance).as("trial balance").isEqualByComparingTo("0");
        assertThat(jdbc.sql("SELECT count(*) FROM \"StockPostingJob\" j JOIN \"Invoice\" i ON i.id = j.\"invoiceId\""
                + " WHERE i.\"cashSessionId\" IN (SELECT id"
                + " FROM \"CashSession\" WHERE \"cashRegisterId\" IN (?, ?)) AND j.status <> 'done'")
                .params(register.get("COUNTER"), register.get("BAR")).query(Long.class).single()).as("undrained jobs").isZero();
        for (String sku : STOCK.keySet()) {
            long want = STOCK.get(sku) - consumed.getOrDefault(sku, 0L);
            assertThat(onHand(sku)).as("on-hand " + sku).isEqualByComparingTo(BigDecimal.valueOf(want));
            BigDecimal value = jdbc.sql("SELECT coalesce(sum(quantity * \"runningAverageCost\"), 0) FROM \"StockItem\""
                    + " WHERE \"productId\" = ?").param(product.get(sku)).query(BigDecimal.class).single();
            assertThat(value).as("valuation " + sku).isEqualByComparingTo(UNIT_COST.multiply(BigDecimal.valueOf(want)));
        }
        long cogsWant = consumed.values().stream().mapToLong(Long::longValue).sum() * UNIT_COST.longValue();
        List<Object> ids = new ArrayList<>(product.values());
        BigDecimal cogs = jdbc.sql("SELECT coalesce(sum(\"totalValue\"), 0) FROM \"InvoiceItemRecipeIngredient\" WHERE"
                + " \"productId\" IN (" + String.join(",", ids.stream().map(i -> "?").toList()) + ")").params(ids)
                .query(BigDecimal.class).single();
        assertThat(cogs).as("recipe COGS snapshot").isEqualByComparingTo(BigDecimal.valueOf(cogsWant));
        String today = java.time.LocalDate.now().toString();
        JsonNode summary = ok(200, "GET", "/pos/reports/sales-summary?groupBy=day&fromDate=" + today + "&toDate=" + today,
                null);
        assertThat(new BigDecimal(summary.get("totals").get("netSales").asString()))
                .isGreaterThanOrEqualTo(BigDecimal.valueOf(totalGross));
    }
}
