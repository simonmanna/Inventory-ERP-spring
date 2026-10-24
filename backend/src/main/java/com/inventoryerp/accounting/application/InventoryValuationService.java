package com.inventoryerp.accounting.application;

import static com.inventoryerp.accounting.application.PostingService.plain;

import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.text.Collator;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accounting-side inventory valuation and the inventory-to-GL tie-out (port of {@code inventory-valuation.service.ts}).
 * Current value (asOf now) is costing-method-correct remaining value (AVCO / STANDARD / FIFO-or-batch lots / serials);
 * a past {@code asOf} is rebuilt from the append-only stock ledger. Reads inventory tables directly, as Nest does — the
 * dependency between accounting and inventory runs the other way.
 */
@Service
public class InventoryValuationService {

    /** JE sourceType used by the GL-gap catch-up script. */
    public static final String GL_GAP_BACKFILL_SOURCE = "inventory_gl_gap_backfill";

    /** Valued ledger row {@code l} with no non-draft inventory journal entry referencing it. */
    private static final String UNPOSTED_PREDICATE = "NOT EXISTS ("
            + " SELECT 1 FROM \"JournalEntry\" e JOIN \"JournalLine\" jl ON jl.\"journalEntryId\" = e.id"
            + " WHERE e.\"organizationId\" = l.\"organizationId\" AND e.status <> 'draft'"
            + " AND jl.\"accountId\" IN (:accts)"
            + " AND (e.\"sourceId\" = l.\"referenceId\" OR e.\"sourceId\" = l.\"ledgerCode\""
            + " OR (e.\"sourceType\" = '" + GL_GAP_BACKFILL_SOURCE + "'"
            + " AND e.\"sourceId\" = COALESCE(l.\"referenceType\", '(none)') AND l.\"createdAt\" <= e.\"createdAt\")))";

    /** Movement types that relocate value inside inventory and never hit the GL on their own. */
    private static final String GL_NEUTRAL = "l.\"type\"::text NOT IN ('transfer_in', 'transfer_out')";

    private static final String SIGNED_VALUE = "CASE WHEN l.\"quantityChange\" >= 0 THEN l.\"totalValue\" ELSE -l.\"totalValue\" END";

    private final JdbcClient jdbc;

    public InventoryValuationService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** Money for the API surface: rounded half-up, emitted as a JSON number. */
    private static Number money(BigDecimal v) {
        return SessionReconciliation.jsNumber(v.setScale(2, RoundingMode.HALF_UP));
    }

    private static Number jsNumber(double v) {
        return v == Math.rint(v) && !Double.isInfinite(v) ? (Number) (long) v : (Number) v;
    }

    private record AsOf(Instant date, boolean historical) {}

    private static AsOf parseAsOf(String asOf) {
        if (asOf == null || asOf.isEmpty()) {
            return new AsOf(Instant.now(), false);
        }
        Instant date;
        // A bare date means "end of that day" (server-local, like `new Date('YYYY-MM-DDT23:59:59.999')`).
        if (asOf.length() <= 10) {
            try {
                date = LocalDateTime.parse(asOf + "T23:59:59.999").atZone(BusinessZone.get()).toInstant();
            } catch (DateTimeParseException e) {
                date = null;
            }
        } else {
            date = OrgDates.parse(asOf.trim());
        }
        if (date == null) {
            throw ApiException.badRequest("Invalid asOf: " + asOf);
        }
        return new AsOf(date, date.toEpochMilli() < System.currentTimeMillis() - 60_000);
    }

    /**
     * Accounts that hold inventory value: the stock_valuation/inventory mappings, every account a STOCK_IN debit rule
     * can resolve to, and every category/product inventory-account override.
     */
    public List<String> inventoryAccountIds() {
        String org = TenantContext.organizationId();
        Set<String> ids = new LinkedHashSet<>(jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ?"
                + " AND key IN ('stock_valuation', 'inventory')").param(org).query(String.class).list());
        List<Map<String, Object>> rules = SqlRows.list(jdbc.sql("SELECT \"literalAccountId\", \"accountMappingKey\""
                + " FROM \"InventoryPostingRule\" WHERE \"organizationId\" = ? AND \"isActive\" = true"
                + " AND \"movementType\"::text = 'STOCK_IN' AND \"debitOrCredit\" = 'debit'").param(org));
        List<String> ruleKeys = rules.stream().map(r -> (String) r.get("accountMappingKey")).filter(Objects::nonNull)
                .filter(s -> !s.isEmpty()).toList();
        if (!ruleKeys.isEmpty()) {
            ids.addAll(jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = :org AND key IN (:keys)")
                    .param("org", org).param("keys", ruleKeys).query(String.class).list());
        }
        for (Map<String, Object> r : rules) {
            String literal = (String) r.get("literalAccountId");
            if (literal != null && !literal.isEmpty()) {
                ids.add(literal);
            }
        }
        ids.addAll(jdbc.sql("SELECT \"inventoryAccountId\" FROM \"ProductCategory\" WHERE \"organizationId\" = ?"
                + " AND \"inventoryAccountId\" IS NOT NULL").param(org).query(String.class).list());
        ids.addAll(jdbc.sql("SELECT \"inventoryAccountOverrideId\" FROM \"Product\" WHERE \"organizationId\" = ?"
                + " AND \"inventoryAccountOverrideId\" IS NOT NULL").param(org).query(String.class).list());
        ids.removeIf(s -> s == null || s.isEmpty());
        return new ArrayList<>(ids);
    }

    /** Current remaining value per product, by costing method — one statement so quants, lots and serials agree. */
    private List<Map<String, Object>> currentValueByProduct(String org) {
        return SqlRows.list(jdbc.sql("WITH quant AS ("
                + " SELECT s.\"productId\", s.\"variantKey\", s.\"locationId\", s.\"quantity\" AS qty,"
                + " CASE WHEN s.\"runningAverageCost\" > 0 THEN s.\"runningAverageCost\" ELSE COALESCE(p.\"costPrice\", 0) END AS avg_cost,"
                + " COALESCE(p.\"costPrice\", 0) AS std_cost, p.\"costingMethod\"::text AS method, p.\"batchTracking\" AS lots,"
                + " p.\"serialTracking\" AS serials"
                + " FROM \"StockItem\" s JOIN \"Product\" p ON p.id = s.\"productId\" WHERE s.\"organizationId\" = :org),"
                + " lot AS (SELECT b.\"productId\", COALESCE(b.\"variantId\", '') AS \"variantKey\", b.\"locationId\","
                + " SUM(b.\"quantity\") AS qty, SUM(b.\"quantity\" * COALESCE(b.\"unitCost\", 0)) AS value"
                + " FROM \"InventoryBatch\" b WHERE b.\"organizationId\" = :org AND b.\"isActive\" = true AND b.\"quantity\" > 0"
                + " GROUP BY 1, 2, 3),"
                + " ser AS (SELECT sr.\"productId\", COALESCE(sr.\"variantId\", '') AS \"variantKey\", sr.\"locationId\","
                + " COUNT(*)::numeric AS qty, SUM(COALESCE(sr.\"unitCost\", p.\"costPrice\", 0)) AS value"
                + " FROM \"InventorySerial\" sr JOIN \"Product\" p ON p.id = sr.\"productId\""
                + " WHERE sr.\"organizationId\" = :org AND sr.status = 'in_stock' GROUP BY 1, 2, 3)"
                + " SELECT q.\"productId\" AS product_id, SUM(q.qty) AS qty, SUM(CASE"
                + " WHEN q.serials THEN COALESCE(se.value, 0) + (q.qty - COALESCE(se.qty, 0)) * q.avg_cost"
                + " WHEN q.method = 'FIFO' OR q.lots THEN COALESCE(l.value, 0) + (q.qty - COALESCE(l.qty, 0)) * q.avg_cost"
                + " WHEN q.method = 'STANDARD' THEN q.qty * q.std_cost"
                + " ELSE q.qty * q.avg_cost END) AS value"
                + " FROM quant q"
                + " LEFT JOIN lot l ON l.\"productId\" = q.\"productId\" AND l.\"locationId\" = q.\"locationId\" AND l.\"variantKey\" = q.\"variantKey\""
                + " LEFT JOIN ser se ON se.\"productId\" = q.\"productId\" AND se.\"locationId\" = q.\"locationId\" AND se.\"variantKey\" = q.\"variantKey\""
                + " GROUP BY q.\"productId\"").param("org", org));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> valuation(String asOfRaw) {
        String org = TenantContext.organizationId();
        AsOf asOf = parseAsOf(asOfRaw);
        List<String> accountIds = inventoryAccountIds();
        Map<String, Object> account = accountIds.isEmpty() ? null
                : SqlRows.one(jdbc.sql("SELECT code, name FROM \"Account\" WHERE id = ? LIMIT 1").param(accountIds.get(0)));

        List<Map<String, Object>> rows = asOf.historical()
                ? SqlRows.list(jdbc.sql("SELECT l.\"productId\" AS product_id, SUM(l.\"quantityChange\") AS qty, SUM(" + SIGNED_VALUE
                        + ") AS value FROM \"InventoryLedger\" l WHERE l.\"organizationId\" = ? AND l.\"createdAt\" <= ?"
                        + " GROUP BY l.\"productId\"").params(org, FiscalPeriodControl.ts(asOf.date())))
                : currentValueByProduct(org);
        List<Map<String, Object>> nonZero = rows.stream()
                .filter(r -> SqlRows.dec(r, "qty").signum() != 0 || SqlRows.dec(r, "value").signum() != 0).toList();

        Map<String, Map<String, Object>> byId = new HashMap<>();
        if (!nonZero.isEmpty()) {
            for (Map<String, Object> p : SqlRows.list(jdbc.sql("SELECT p.id, p.name, p.sku, p.code, p.\"costingMethod\"::text AS method,"
                    + " c.name AS category_name FROM \"Product\" p LEFT JOIN \"ProductCategory\" c ON c.id = p.\"categoryId\""
                    + " WHERE p.\"organizationId\" = :org AND p.id IN (:ids)").param("org", org)
                    .param("ids", nonZero.stream().map(r -> (String) r.get("product_id")).toList()))) {
                byId.put((String) p.get("id"), p);
            }
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : nonZero) {
            String productId = (String) r.get("product_id");
            Map<String, Object> p = byId.get(productId);
            BigDecimal qty = SqlRows.dec(r, "qty");
            BigDecimal value = SqlRows.dec(r, "value");
            BigDecimal unitCost = qty.signum() == 0 ? BigDecimal.ZERO : value.divide(qty, new MathContext(20, RoundingMode.HALF_UP));
            String sku = p == null ? "" : p.get("sku") != null ? (String) p.get("sku") : p.get("code") != null ? (String) p.get("code") : "";
            Map<String, Object> item = body("productId", productId,
                    "productName", p != null && p.get("name") != null ? p.get("name") : productId,
                    "sku", sku,
                    "categoryName", p != null && p.get("category_name") != null ? p.get("category_name") : "Uncategorized",
                    "costingMethod", p != null && p.get("method") != null ? p.get("method") : "",
                    "unitCost", unitCost.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                    "onHandQty", SessionReconciliation.jsNumber(qty.setScale(6, RoundingMode.HALF_UP)),
                    "totalValue", value.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                    "exactValue", plain(value),
                    "accountCode", account != null ? account.get("code") : "",
                    "accountName", account != null ? account.get("name") : "");
            items.add(item);
        }
        Collator collator = Collator.getInstance(Locale.ENGLISH);
        items.sort(Comparator.comparing(i -> (String) i.get("productName"), collator));
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal totalQty = BigDecimal.ZERO;
        for (Map<String, Object> i : items) {
            total = total.add(new BigDecimal((String) i.get("exactValue")));
            totalQty = totalQty.add(new BigDecimal(i.get("onHandQty").toString()));
        }
        return body("asOf", asOf.date(), "basis", asOf.historical() ? "ledger" : "current_cost_by_method", "items", items,
                "summary", body("totalItems", items.size(), "totalValue", total.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                        "exactTotalValue", plain(total),
                        "totalQty", SessionReconciliation.jsNumber(totalQty.setScale(6, RoundingMode.HALF_UP))),
                "groupedBy", "product");
    }

    /**
     * Inventory sub-ledger vs the inventory control account(s), with the variance explained by source and the valued
     * ledger movements that have no journal entry at all.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, Object> glTieOut(String asOfRaw, double tolerance) {
        String org = TenantContext.organizationId();
        AsOf asOf = parseAsOf(asOfRaw);
        List<String> accountIds = inventoryAccountIds();
        Map<String, Object> val = valuation(asOfRaw);
        BigDecimal subledger = new BigDecimal((String) ((Map<String, Object>) val.get("summary")).get("exactTotalValue"));
        BigDecimal tol = new BigDecimal(Double.toString(tolerance));

        if (accountIds.isEmpty()) {
            return body("asOf", asOf.date(), "accounts", List.of(), "subledgerValue", money(subledger), "glBalance", 0,
                    "variance", money(subledger), "withinTolerance", subledger.abs().compareTo(tol) <= 0,
                    "tolerance", jsNumber(tolerance), "bySource", List.of(), "unpostedMovements", List.of(),
                    "warning", "No stock_valuation account mapping — cannot tie inventory to the GL.");
        }

        Timestamp date = FiscalPeriodControl.ts(asOf.date());
        List<Map<String, Object>> accounts = SqlRows.list(jdbc.sql("SELECT id, code, name FROM \"Account\" WHERE id IN (:ids)")
                .param("ids", accountIds));
        List<Map<String, Object>> glBySource = SqlRows.list(jdbc.sql("SELECT e.\"sourceType\" AS source,"
                        + " SUM(jl.\"debit\" - jl.\"credit\") AS amount FROM \"JournalLine\" jl"
                        + " JOIN \"JournalEntry\" e ON e.id = jl.\"journalEntryId\""
                        + " WHERE e.\"organizationId\" = :org AND e.status <> 'draft' AND e.\"postingDate\" <= :date"
                        + " AND jl.\"accountId\" IN (:accts) GROUP BY e.\"sourceType\"")
                .param("org", org).param("date", date).param("accts", accountIds));
        List<Map<String, Object>> ledgerBySource = SqlRows.list(jdbc.sql("SELECT l.\"referenceType\" AS source, SUM(" + SIGNED_VALUE
                        + ") AS amount FROM \"InventoryLedger\" l WHERE l.\"organizationId\" = ? AND l.\"createdAt\" <= ?"
                        + " GROUP BY l.\"referenceType\"").params(org, date));
        List<Map<String, Object>> unposted = SqlRows.list(jdbc.sql("SELECT l.\"referenceType\" AS source, l.\"type\"::text AS move_type,"
                        + " COUNT(*) AS rows, SUM(" + SIGNED_VALUE + ") AS value FROM \"InventoryLedger\" l"
                        + " WHERE l.\"organizationId\" = :org AND l.\"createdAt\" <= :date AND l.\"totalValue\" > 0 AND " + GL_NEUTRAL
                        + " AND " + UNPOSTED_PREDICATE + " GROUP BY l.\"referenceType\", l.\"type\"")
                .param("org", org).param("date", date).param("accts", accountIds));

        BigDecimal glBalance = glBySource.stream().map(r -> SqlRows.dec(r, "amount")).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal ledgerTotal = ledgerBySource.stream().map(r -> SqlRows.dec(r, "amount")).reduce(BigDecimal.ZERO, BigDecimal::add);
        Set<String> sources = new LinkedHashSet<>();
        glBySource.forEach(r -> sources.add(sourceOf(r)));
        ledgerBySource.forEach(r -> sources.add(sourceOf(r)));
        List<Map<String, Object>> bySource = new ArrayList<>();
        List<BigDecimal> abs = new ArrayList<>();
        for (String source : sources) {
            BigDecimal gl = amountFor(glBySource, source);
            BigDecimal ledger = amountFor(ledgerBySource, source);
            Number ledgerValue = money(ledger);
            Number glValue = money(gl);
            if (ledgerValue.doubleValue() == 0 && glValue.doubleValue() == 0) {
                continue;
            }
            bySource.add(body("source", source, "ledgerValue", ledgerValue, "glValue", glValue,
                    "difference", money(ledger.subtract(gl))));
            abs.add(ledger.subtract(gl).abs());
        }
        bySource = sortByAbsDesc(bySource, abs);

        List<Map<String, Object>> movements = new ArrayList<>();
        List<BigDecimal> movementAbs = new ArrayList<>();
        for (Map<String, Object> u : unposted) {
            BigDecimal value = SqlRows.dec(u, "value");
            movements.add(body("source", sourceOf(u), "moveType", u.get("move_type"), "rows", ((Number) u.get("rows")).longValue(),
                    "value", money(value)));
            movementAbs.add(value.abs());
        }

        BigDecimal variance = subledger.subtract(glBalance);
        return body("asOf", asOf.date(), "accounts", accounts, "subledgerValue", money(subledger),
                "subledgerBasis", val.get("basis"), "ledgerMovementValue", money(ledgerTotal),
                "valuationVsLedger", money(subledger.subtract(ledgerTotal)), "glBalance", money(glBalance),
                "variance", money(variance), "tolerance", jsNumber(tolerance),
                "withinTolerance", variance.abs().compareTo(tol) <= 0, "bySource", bySource,
                "unpostedMovements", sortByAbsDesc(movements, movementAbs));
    }

    private static String sourceOf(Map<String, Object> row) {
        return row.get("source") != null ? (String) row.get("source") : "(none)";
    }

    private static BigDecimal amountFor(List<Map<String, Object>> rows, String source) {
        return rows.stream().filter(r -> sourceOf(r).equals(source)).findFirst().map(r -> SqlRows.dec(r, "amount"))
                .orElse(BigDecimal.ZERO);
    }

    /** Stable sort by a parallel list of magnitudes, largest first. */
    private static List<Map<String, Object>> sortByAbsDesc(List<Map<String, Object>> rows, List<BigDecimal> abs) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            idx.add(i);
        }
        idx.sort((a, b) -> abs.get(b).compareTo(abs.get(a)));
        return idx.stream().map(rows::get).toList();
    }
}
