package com.inventoryerp.accounting.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Money activity taxonomy (ports of {@code money-activity.taxonomy.ts} and {@code money-activity.sql.ts}): the
 * single source of truth for "what kind of money movement is this journal entry?", shared by the activity feed,
 * the overview, settlements and the cash-flow movement report.
 */
public final class MoneyActivityTaxonomy {

    private MoneyActivityTaxonomy() {}

    /** One movement category. */
    public record Category(String key, String label, List<String> sourceTypes) {}

    public static final List<Category> CASH_MOVEMENT_CATEGORIES = List.of(
            new Category("pos_sales", "POS Sales", List.of("pos", "pos_invoice", "pos_invoice_extra", "pos_payment")),
            new Category("customer_receipts", "Customer Receipts",
                    List.of("payment", "invoice", "sales_invoice", "document", "reservation", "agreement")),
            new Category("refunds", "Refunds & Credits", List.of("pos_refund", "pos_payment_refund", "credit_note",
                    "pos_invoice_writeoff", "store_credit_issue")),
            new Category("supplier_payments", "Supplier Payments", List.of("purchase_payment", "payment_outbound",
                    "vendor_bill", "debit_note", "purchase_order", "goods_receipt")),
            new Category("expenses", "Expenses", List.of("expense_payment")),
            new Category("payroll", "Payroll", List.of("payroll_run")),
            new Category("transfers", "Internal Transfers", List.of("treasury_transfer")),
            new Category("deposits", "Other Money In", List.of("cash_flow_deposit")),
            new Category("withdrawals", "Other Money Out", List.of("cash_flow_withdrawal")),
            new Category("cash_drawer", "Cash Drawer", List.of("cash_movement", "cash_session_opening",
                    "cash_session_banking", "cash_session_variance", "drawer_account_split")),
            new Category("tender_settlement", "Provider Settlement", List.of("tender_settlement")),
            new Category("rental", "Rental", List.of("rental_checkout", "rental_inspect", "rental_return")),
            new Category("adjustments", "Manual & Adjustments", List.of("manual", "reversal", "fx_revaluation",
                    "period_close", "recurring", "a009_legacy_adjustment")));

    public static final Category OTHER_CATEGORY = new Category("other", "Other", List.of());

    public static final Map<String, String> CATEGORY_OF_SOURCE = categoryOfSource();

    public static final List<String> KNOWN_SOURCE_TYPES = List.copyOf(CATEGORY_OF_SOURCE.keySet());

    public static final Map<String, String> CATEGORY_LABEL = categoryLabels();

    /** {@code [{key, label}]} for pickers. */
    public static final List<Map<String, Object>> CATEGORY_OPTIONS = categoryOptions();

    public static final Set<String> INTERNAL_SOURCE_TYPES = Set.of("treasury_transfer", "tender_settlement",
            "cash_session_banking", "drawer_account_split");

    public static final Set<String> ADJUSTMENT_SOURCE_TYPES = adjustmentSources();

    /** Effective source types that are POS takings (sales, not refunds). */
    public static final List<String> POS_SALE_SOURCE_TYPES = CASH_MOVEMENT_CATEGORIES.getFirst().sourceTypes();

    private static Map<String, String> categoryOfSource() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Category c : CASH_MOVEMENT_CATEGORIES) {
            for (String st : c.sourceTypes()) {
                out.put(st, c.key());
            }
        }
        return out;
    }

    private static Map<String, String> categoryLabels() {
        Map<String, String> out = new LinkedHashMap<>();
        CASH_MOVEMENT_CATEGORIES.forEach(c -> out.put(c.key(), c.label()));
        out.put(OTHER_CATEGORY.key(), OTHER_CATEGORY.label());
        return out;
    }

    private static List<Map<String, Object>> categoryOptions() {
        List<Map<String, Object>> out = new ArrayList<>();
        CATEGORY_LABEL.forEach((k, v) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", k);
            m.put("label", v);
            out.add(m);
        });
        return List.copyOf(out);
    }

    private static Set<String> adjustmentSources() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        out.add("cash_session_variance");
        CASH_MOVEMENT_CATEGORIES.stream().filter(c -> c.key().equals("adjustments")).findFirst()
                .ifPresent(c -> out.addAll(c.sourceTypes()));
        return Set.copyOf(out);
    }

    // ------------------------------------------------------------------ SQL fragments (je = JournalEntry alias)

    /** {@code sourceType} refined by the Payment behind a generic {@code payment} entry. */
    public static final String EFFECTIVE_SOURCE_TYPE = "(CASE WHEN je.\"sourceType\" = 'payment' THEN COALESCE(("
            + " SELECT CASE"
            + " WHEN p.\"cashSessionId\" IS NOT NULL AND p.direction::text = 'inbound' THEN 'pos_payment'"
            + " WHEN p.\"cashSessionId\" IS NOT NULL THEN 'pos_payment_refund'"
            + " WHEN p.direction::text = 'outbound' THEN 'payment_outbound'"
            + " ELSE 'payment' END"
            + " FROM \"Payment\" p WHERE p.id = je.\"sourceId\""
            + " ), 'payment') ELSE je.\"sourceType\" END)";

    /** SQL literal list {@code 'a', 'b'} of constant strings. */
    public static String sqlList(java.util.Collection<String> values) {
        return values.stream().map(v -> "'" + v.replace("'", "''") + "'").collect(Collectors.joining(", "));
    }

    /** {@code (<effective source> IN categories)}; {@code other} matches NULL and anything unmapped. */
    public static String categorySql(List<String> categories) {
        Set<String> keys = Set.copyOf(categories);
        List<String> sourceTypes = KNOWN_SOURCE_TYPES.stream().filter(st -> keys.contains(CATEGORY_OF_SOURCE.get(st))).toList();
        List<String> clauses = new ArrayList<>();
        if (!sourceTypes.isEmpty()) {
            clauses.add(EFFECTIVE_SOURCE_TYPE + " IN (" + sqlList(sourceTypes) + ")");
        }
        if (keys.contains("other")) {
            clauses.add("(je.\"sourceType\" IS NULL OR " + EFFECTIVE_SOURCE_TYPE + " NOT IN (" + sqlList(KNOWN_SOURCE_TYPES) + "))");
        }
        return clauses.isEmpty() ? "FALSE" : "(" + String.join(" OR ", clauses) + ")";
    }

    /** Direction for a per-entry aggregate {@code x(eff, d, c)}, mirroring {@link #classify}. */
    public static final String DIRECTION_SQL = "(CASE"
            + " WHEN x.eff IN (" + sqlList(ADJUSTMENT_SOURCE_TYPES.stream().sorted().toList()) + ") THEN 'adjustment'"
            + " WHEN x.eff IN (" + sqlList(INTERNAL_SOURCE_TYPES.stream().sorted().toList()) + ") AND LEAST(x.d, x.c) > 0 THEN 'internal'"
            + " WHEN x.d > x.c THEN 'in'"
            + " WHEN x.c > x.d THEN 'out'"
            + " ELSE 'internal' END)";

    // ------------------------------------------------------------------ classification

    /** Refine a generic {@code payment} entry by its Payment. */
    public static String effectiveSourceType(String sourceType, String paymentCashSessionId, String paymentDirection,
            boolean hasPayment) {
        if (!"payment".equals(sourceType) || !hasPayment) {
            return sourceType;
        }
        if (paymentCashSessionId != null) {
            return "inbound".equals(paymentDirection) ? "pos_payment" : "pos_payment_refund";
        }
        return "outbound".equals(paymentDirection) ? "payment_outbound" : "payment";
    }

    public static String categoryOf(String sourceType) {
        if (sourceType == null || sourceType.isEmpty()) {
            return "other";
        }
        return CATEGORY_OF_SOURCE.getOrDefault(sourceType, "other");
    }

    /** One money-account line of an entry. */
    public record MoneyLine(String accountId, String accountName, String accountType, Object baseDebit, Object baseCredit) {}

    /** JS {@code Math.round(Number(v || 0) * 10000)}. */
    static long toUnits(Object v) {
        double d = v == null ? 0 : v instanceof Number n ? n.doubleValue() : Double.parseDouble(v.toString());
        return Math.round(d * 10000);
    }

    /** JS {@code (u / 10000).toFixed(2)}. */
    static String fromUnits(long u) {
        return toFixed2(u / 10000.0);
    }

    /** JS {@code Number.prototype.toFixed(2)} on a double. */
    public static String toFixed2(double v) {
        return new BigDecimal(v).setScale(2, RoundingMode.HALF_UP).toPlainString().replace("-0.00", "0.00");
    }

    /** Classify one journal entry from its lines on money accounts. */
    public static Map<String, Object> classify(String sourceType, List<MoneyLine> moneyLines) {
        String category = categoryOf(sourceType);
        Map<String, long[]> net = new LinkedHashMap<>();
        Map<String, MoneyLine> lineOf = new LinkedHashMap<>();
        long debit = 0;
        long credit = 0;
        for (MoneyLine l : moneyLines) {
            long d = toUnits(l.baseDebit());
            long c = toUnits(l.baseCredit());
            debit += d;
            credit += c;
            lineOf.putIfAbsent(l.accountId(), l);
            net.computeIfAbsent(l.accountId(), k -> new long[1])[0] += d - c;
        }
        long externalIn = Math.max(0, debit - credit);
        long externalOut = Math.max(0, credit - debit);
        long internalMoved = Math.min(debit, credit);
        List<Map.Entry<String, long[]>> entries = new ArrayList<>(net.entrySet());
        entries.removeIf(e -> e.getValue()[0] == 0);
        entries.sort(Comparator.comparingLong((Map.Entry<String, long[]> e) -> -Math.abs(e.getValue()[0])));
        List<Map<String, Object>> legs = new ArrayList<>();
        for (Map.Entry<String, long[]> e : entries) {
            MoneyLine line = lineOf.get(e.getKey());
            Map<String, Object> leg = new LinkedHashMap<>();
            leg.put("accountId", line.accountId());
            leg.put("accountName", line.accountName());
            leg.put("accountType", line.accountType());
            leg.put("side", e.getValue()[0] > 0 ? "in" : "out");
            leg.put("amount", fromUnits(Math.abs(e.getValue()[0])));
            legs.add(leg);
        }
        String direction;
        if (sourceType != null && ADJUSTMENT_SOURCE_TYPES.contains(sourceType)) {
            direction = "adjustment";
        } else if (sourceType != null && INTERNAL_SOURCE_TYPES.contains(sourceType) && internalMoved > 0) {
            direction = "internal";
        } else if (externalIn > 0) {
            direction = "in";
        } else if (externalOut > 0) {
            direction = "out";
        } else {
            direction = "internal";
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("category", category);
        out.put("categoryLabel", CATEGORY_LABEL.getOrDefault(category, "Other"));
        out.put("direction", direction);
        out.put("grossAmount", fromUnits(Math.max(debit, credit)));
        out.put("externalIn", fromUnits(externalIn));
        out.put("externalOut", fromUnits(externalOut));
        out.put("internalMoved", fromUnits(internalMoved));
        out.put("legs", legs);
        return out;
    }
}
