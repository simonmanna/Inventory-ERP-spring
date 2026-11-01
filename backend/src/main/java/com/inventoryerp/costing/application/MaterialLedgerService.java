package com.inventoryerp.costing.application;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.costing.application.MaterialLedgerCalculator.Input;
import com.inventoryerp.costing.application.MaterialLedgerCalculator.Result;
import com.inventoryerp.inventory.application.CostLineageService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Material ledger — periodic actual costing of standard-costed products (ADR-S030 §1). A run computes, per product,
 * how much of the period's variance pool stays in inventory; posting moves the change in that carried amount between
 * stock valuation and 5965 Material Ledger Revaluation, so inventory shows actual cost and P&L only the consumed share.
 */
@Service
public class MaterialLedgerService {

    private static final List<String> TRANSFERS = List.of("transfer_in", "transfer_out");

    private final JdbcClient jdbc;
    private final PostingService posting;
    private final AccountDeterminationService determination;
    private final AccountResolverService accounts;
    private final SequenceService sequence;
    private final AuditService audit;
    private final CostLineageService lineage;

    public MaterialLedgerService(JdbcClient jdbc, PostingService posting, AccountDeterminationService determination,
            AccountResolverService accounts, SequenceService sequence, AuditService audit, CostLineageService lineage) {
        this.lineage = lineage;
        this.jdbc = jdbc;
        this.posting = posting;
        this.determination = determination;
        this.accounts = accounts;
        this.sequence = sequence;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return SqlRows.list(jdbc.sql("SELECT * FROM \"MaterialLedgerRun\" WHERE \"organizationId\" = ? ORDER BY"
                + " \"periodTo\" DESC, \"createdAt\" DESC").param(org()));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> run = run(id);
        run.put("lines", SqlRows.list(jdbc.sql("SELECT l.*, p.name AS \"productName\", p.code AS \"productCode\" FROM"
                + " \"MaterialLedgerLine\" l JOIN \"Product\" p ON p.id = l.\"productId\" WHERE l.\"runId\" = ? AND"
                + " l.\"organizationId\" = ? ORDER BY p.name").params(id, org())));
        return run;
    }

    private Map<String, Object> run(String id) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT * FROM \"MaterialLedgerRun\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (r == null) {
            throw ApiException.notFound("Material ledger run not found");
        }
        return r;
    }

    /** Create a draft run for the period after the last posted run (or {@code from}..{@code to} for the first). */
    @Transactional
    public Map<String, Object> create(String fromRaw, String toRaw) {
        LocalDate from = day(fromRaw, "from");
        LocalDate to = day(toRaw, "to");
        if (to.isBefore(from)) {
            throw ApiException.badRequest("to must not be before from");
        }
        if (to.isAfter(LocalDate.now(ZoneOffset.UTC))) {
            throw ApiException.badRequest("A run cannot end in the future");
        }
        LocalDate lastPosted = jdbc.sql("SELECT max(\"periodTo\") FROM \"MaterialLedgerRun\" WHERE \"organizationId\" ="
                + " ? AND status = 'posted'").param(org()).query(Date.class).optional().map(Date::toLocalDate)
                .orElse(null);
        if (lastPosted != null && !from.equals(lastPosted.plusDays(1))) {
            throw ApiException.badRequest("The next run must start on " + lastPosted.plusDays(1));
        }
        if (jdbc.sql("SELECT count(*) FROM \"MaterialLedgerRun\" WHERE \"organizationId\" = ? AND status = 'draft'")
                .param(org()).query(Long.class).single() > 0) {
            throw ApiException.conflict("Post or delete the open draft run first");
        }
        String id = UUID.randomUUID().toString();
        String number = sequence.next("material_ledger_run", "ML-", 5);
        jdbc.sql("INSERT INTO \"MaterialLedgerRun\" (id, \"organizationId\", number, \"periodFrom\", \"periodTo\","
                + " \"createdBy\") VALUES (?, ?, ?, ?, ?, ?)").params(id, org(), number, Date.valueOf(from),
                        Date.valueOf(to), TenantContext.userId()).update();
        compute(id, from, to);
        audit.recordInTx(AuditInput.of("MaterialLedgerRun", id, "create", null, Obj.of("from", from.toString(), "to",
                to.toString())));
        return get(id);
    }

    @Transactional
    public Map<String, Object> recompute(String id) {
        Map<String, Object> r = run(id);
        if (!"draft".equals(r.get("status"))) {
            throw ApiException.badRequest("Only a draft run can be recomputed");
        }
        compute(id, (LocalDate) r.get("periodFrom"), (LocalDate) r.get("periodTo"));
        return get(id);
    }

    @Transactional
    public Map<String, Object> delete(String id) {
        Map<String, Object> r = run(id);
        if (!"draft".equals(r.get("status"))) {
            throw ApiException.badRequest("Only a draft run can be deleted");
        }
        jdbc.sql("DELETE FROM \"MaterialLedgerRun\" WHERE id = ? AND \"organizationId\" = ?").params(id, org()).update();
        audit.recordInTx(AuditInput.of("MaterialLedgerRun", id, "delete"));
        return Obj.of("id", id, "deleted", true);
    }

    private void compute(String runId, LocalDate from, LocalDate to) {
        jdbc.sql("DELETE FROM \"MaterialLedgerLine\" WHERE \"runId\" = ? AND \"organizationId\" = ?").params(runId, org())
                .update();
        Instant start = from.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant end = to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Set<String> products = new LinkedHashSet<>(jdbc.sql("SELECT \"productId\" FROM \"ProductStandardCost\" WHERE"
                + " \"organizationId\" = ? AND status = 'active'").param(org()).query(String.class).list());
        products.addAll(jdbc.sql("SELECT DISTINCT \"productId\" FROM \"StandardCostVariance\" WHERE \"organizationId\" = ?"
                + " AND \"createdAt\" >= ? AND \"createdAt\" < ?").params(org(), ts(start), ts(end))
                .query(String.class).list());
        Map<String, BigDecimal> carried = carried();
        products.addAll(carried.keySet());
        BigDecimal pool = BigDecimal.ZERO;
        BigDecimal toInv = BigDecimal.ZERO;
        BigDecimal toCons = BigDecimal.ZERO;
        BigDecimal delta = BigDecimal.ZERO;
        int count = 0;
        for (String productId : products) {
            Map<String, Object> q = SqlRows.one(jdbc.sql("SELECT"
                    + " coalesce(sum(\"quantityChange\") FILTER (WHERE \"createdAt\" < ?), 0) AS opening,"
                    + " coalesce(sum(\"quantityChange\") FILTER (WHERE \"createdAt\" >= ? AND \"createdAt\" < ? AND"
                    + " \"quantityChange\" > 0 AND type::text <> ALL (?)), 0) AS receipts,"
                    + " coalesce(-sum(\"quantityChange\") FILTER (WHERE \"createdAt\" >= ? AND \"createdAt\" < ? AND"
                    + " \"quantityChange\" < 0 AND type::text <> ALL (?)), 0) AS consumed"
                    + " FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND \"productId\" = ?")
                    .params(ts(start), ts(start), ts(end), TRANSFERS.toArray(String[]::new), ts(start), ts(end),
                            TRANSFERS.toArray(String[]::new), org(), productId));
            BigDecimal variance = jdbc.sql("SELECT coalesce(sum(amount), 0) FROM \"StandardCostVariance\" WHERE"
                    + " \"organizationId\" = ? AND \"productId\" = ? AND \"createdAt\" >= ? AND \"createdAt\" < ?")
                    .params(org(), productId, ts(start), ts(end)).query(BigDecimal.class).single();
            BigDecimal standard = jdbc.sql("SELECT coalesce((SELECT \"totalCost\" FROM \"ProductStandardCost\" WHERE"
                    + " \"organizationId\" = ? AND \"productId\" = ? AND status = 'active' LIMIT 1), (SELECT"
                    + " coalesce(\"costPrice\", 0) FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?), 0)")
                    .params(org(), productId, productId, org()).query(BigDecimal.class).single();
            BigDecimal carry = carried.getOrDefault(productId, BigDecimal.ZERO);
            Input in = new Input(SqlRows.dec(q, "opening"), carry, SqlRows.dec(q, "receipts"), variance,
                    SqlRows.dec(q, "consumed"), standard);
            if (carry.signum() == 0 && variance.signum() == 0 && in.receiptQty().signum() == 0
                    && in.consumedQty().signum() == 0) {
                continue;
            }
            Result r = MaterialLedgerCalculator.compute(in);
            jdbc.sql("INSERT INTO \"MaterialLedgerLine\" (id, \"organizationId\", \"runId\", \"productId\","
                    + " \"standardCost\", \"openingQty\", \"carriedVariance\", \"receiptQty\", \"periodVariance\","
                    + " \"consumedQty\", \"endingQty\", pool, \"toInventory\", \"toConsumption\", \"periodicUnitCost\","
                    + " delta) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org(), runId, productId, standard, in.openingQty(), carry,
                            in.receiptQty(), variance, in.consumedQty(), r.endingQty(), r.pool(), r.toInventory(),
                            r.toConsumption(), r.periodicUnitCost(), r.delta()).update();
            pool = pool.add(r.pool());
            toInv = toInv.add(r.toInventory());
            toCons = toCons.add(r.toConsumption());
            delta = delta.add(r.delta());
            count++;
        }
        jdbc.sql("UPDATE \"MaterialLedgerRun\" SET \"productCount\" = ?, \"poolTotal\" = ?, \"toInventory\" = ?,"
                + " \"toConsumption\" = ?, \"deltaTotal\" = ? WHERE id = ? AND \"organizationId\" = ?")
                .params(count, pool, toInv, toCons, delta, runId, org()).update();
    }

    /** Variance carried in inventory per product by the last posted run. */
    private Map<String, BigDecimal> carried() {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        String last = jdbc.sql("SELECT id FROM \"MaterialLedgerRun\" WHERE \"organizationId\" = ? AND status = 'posted'"
                + " ORDER BY \"periodTo\" DESC LIMIT 1").param(org()).query(String.class).optional().orElse(null);
        if (last == null) {
            return out;
        }
        for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT \"productId\", \"toInventory\" FROM"
                + " \"MaterialLedgerLine\" WHERE \"runId\" = ? AND \"organizationId\" = ?").params(last, org()))) {
            BigDecimal v = SqlRows.dec(l, "toInventory");
            if (v.signum() != 0) {
                out.put((String) l.get("productId"), v);
            }
        }
        return out;
    }

    /** Post the change in carried variance: Dr / Cr stock valuation against 5965 Material Ledger Revaluation. */
    @Transactional
    public Map<String, Object> post(String id) {
        Map<String, Object> r = run(id);
        if (!"draft".equals(r.get("status"))) {
            throw ApiException.badRequest("Only a draft run can be posted");
        }
        LocalDate lastPosted = jdbc.sql("SELECT max(\"periodTo\") FROM \"MaterialLedgerRun\" WHERE \"organizationId\" ="
                + " ? AND status = 'posted'").param(org()).query(Date.class).optional().map(Date::toLocalDate)
                .orElse(null);
        LocalDate from = (LocalDate) r.get("periodFrom");
        if (lastPosted != null && !from.equals(lastPosted.plusDays(1))) {
            throw ApiException.badRequest("The run no longer follows the last posted run; recompute it");
        }
        compute(id, from, (LocalDate) r.get("periodTo"));
        String stock = determination.mapped("stock_valuation");
        String reval = accounts.ensureByCode("5965", "Material Ledger Revaluation", "cost_of_goods_sold",
                "material_ledger_revaluation");
        List<PostingRequest.Line> lines = new ArrayList<>();
        Map<String, BigDecimal> deltas = new LinkedHashMap<>();
        for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT l.delta, l.\"productId\", p.name FROM"
                + " \"MaterialLedgerLine\" l JOIN"
                + " \"Product\" p ON p.id = l.\"productId\" WHERE l.\"runId\" = ? AND l.\"organizationId\" = ? AND"
                + " l.delta <> 0 ORDER BY p.name").params(id, org()))) {
            BigDecimal d = SqlRows.dec(l, "delta");
            deltas.put((String) l.get("productId"), d);
            String text = "Actual cost revaluation · " + l.get("name");
            if (d.signum() > 0) {
                lines.add(PostingRequest.Line.debit(stock, d, text));
                lines.add(PostingRequest.Line.credit(reval, d, text));
            } else {
                lines.add(PostingRequest.Line.debit(reval, d.negate(), text));
                lines.add(PostingRequest.Line.credit(stock, d.negate(), text));
            }
        }
        String entryId = null;
        if (!lines.isEmpty()) {
            LocalDate to = (LocalDate) r.get("periodTo");
            Map<String, Object> entry = posting.post(PostingRequest.of("GEN", to.atStartOfDay().toInstant(ZoneOffset.UTC),
                    "Material ledger " + r.get("number") + " (" + from + " – " + to + ")", "material_ledger_run", id,
                    "material_ledger:" + id, lines));
            entryId = (String) entry.get("id");
            deltas.forEach((productId, d) -> valueRows(productId, d, "material_ledger_run", id));
        }
        jdbc.sql("UPDATE \"MaterialLedgerRun\" SET status = 'posted', \"journalEntryId\" = ?, \"postedAt\" = now(),"
                + " \"postedBy\" = ? WHERE id = ? AND \"organizationId\" = ?").params(entryId, TenantContext.userId(), id,
                        org()).update();
        audit.recordInTx(AuditInput.of("MaterialLedgerRun", id, "post", null, Obj.of("journalEntryId", entryId)));
        return get(id);
    }

    /** Reverse the last posted run (mirrored entry); carried amounts fall back to the run before. */
    @Transactional
    public Map<String, Object> reverse(String id) {
        Map<String, Object> r = run(id);
        if (!"posted".equals(r.get("status"))) {
            throw ApiException.badRequest("Only a posted run can be reversed");
        }
        String last = jdbc.sql("SELECT id FROM \"MaterialLedgerRun\" WHERE \"organizationId\" = ? AND status = 'posted'"
                + " ORDER BY \"periodTo\" DESC LIMIT 1").param(org()).query(String.class).single();
        if (!last.equals(id)) {
            throw ApiException.badRequest("Only the last posted run can be reversed");
        }
        String reversal = null;
        if (r.get("journalEntryId") != null) {
            Map<String, Object> rev = posting.reverse((String) r.get("journalEntryId"), Instant.now(),
                    "Reversal of material ledger " + r.get("number"));
            reversal = (String) rev.get("id");
            for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT \"productId\", delta FROM \"MaterialLedgerLine\""
                    + " WHERE \"runId\" = ? AND \"organizationId\" = ? AND delta <> 0").params(id, org()))) {
                valueRows((String) l.get("productId"), SqlRows.dec(l, "delta").negate(), "reversal",
                        (String) r.get("journalEntryId"));
            }
        }
        jdbc.sql("UPDATE \"MaterialLedgerRun\" SET status = 'reversed', \"reversalEntryId\" = ?, \"reversedAt\" = now()"
                + " WHERE id = ? AND \"organizationId\" = ?").params(reversal, id, org()).update();
        audit.recordInTx(AuditInput.of("MaterialLedgerRun", id, "reopen", null, Obj.of("reversalEntryId", reversal)));
        return get(id);
    }

    /**
     * CM-1 (ADR-S031): the stock ledger carries the material-ledger price difference as a value-only adjunct row
     * ({@code price_difference}) so valuation and GL tie out; spread over the product's quants by quantity (the
     * largest quant takes the rounding), or on its last location when nothing is on hand.
     */
    private void valueRows(String productId, BigDecimal delta, String referenceType, String referenceId) {
        if (delta.signum() == 0) {
            return;
        }
        List<Map<String, Object>> quants = SqlRows.list(jdbc.sql("SELECT \"variantKey\", \"locationId\", quantity FROM"
                + " \"StockItem\" WHERE \"organizationId\" = ? AND \"productId\" = ? AND quantity > 0 ORDER BY"
                + " quantity DESC").params(org(), productId));
        BigDecimal total = quants.stream().map(q -> SqlRows.dec(q, "quantity")).reduce(BigDecimal.ZERO,
                BigDecimal::add);
        if (total.signum() <= 0) {
            String location = jdbc.sql("SELECT \"locationId\" FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                    + " \"productId\" = ? ORDER BY \"createdAt\" DESC LIMIT 1").params(org(), productId)
                    .query(String.class).optional().orElse(null);
            if (location != null) {
                lineage.recordValueOnly(productId, null, location, null, delta, referenceType, referenceId,
                        "price_difference", "Material ledger actual-cost revaluation");
            }
            return;
        }
        BigDecimal rest = delta;
        for (int i = quants.size() - 1; i >= 0; i--) {
            Map<String, Object> q = quants.get(i);
            BigDecimal part = i == 0 ? rest : delta.multiply(SqlRows.dec(q, "quantity")).divide(total, 2,
                    java.math.RoundingMode.HALF_UP);
            rest = rest.subtract(part);
            String variant = (String) q.get("variantKey");
            lineage.recordValueOnly(productId, variant == null || variant.isEmpty() ? null : variant,
                    (String) q.get("locationId"), null, part, referenceType, referenceId, "price_difference",
                    "Material ledger actual-cost revaluation");
        }
    }

    private static java.sql.Timestamp ts(Instant i) {
        return SqlRows.ts(i);
    }

    static LocalDate day(String raw, String name) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest(name + " should not be empty");
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(name + " must be a valid ISO 8601 date string");
        }
    }
}
