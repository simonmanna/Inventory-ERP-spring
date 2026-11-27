package com.inventoryerp.costing.application;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.CostingReversals;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.costing.application.LineageCapitalisation.Part;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cost adjustment run for AVCO / FIFO late costs (Cost Management CM-10, ADR-S041, AD-2a rule 5).
 *
 * <p>When a later adjustment (landed cost, revaluation, accrual true-up, …) was distributed through lineage while the
 * consuming production order was still open, its share went to WIP ({@code CostValueAdjustmentLine.target = 'wip'}).
 * Once that order is completed the amount is stranded in WIP: the order's output was costed without it. The run moves
 * each such share onto the order's output receipt layers, through their lineage — on hand → 1396 adjunct (value-only
 * stock ledger row), sold → cost of sales, consumed further → WIP / scrap — posting Dr those targets / Cr WIP in the
 * open period. Each share cascades once (unique index); a run is reversible while nothing it capitalised was
 * released. An order with no output receipt sends its share to cost of sales.
 */
@Service
public class CostAdjustmentService {

    /** Levels of a chain followed by one run request; a longer chain finishes on the next request. */
    private static final int MAX_LEVELS = 10;

    static final String SOURCE = "cost_adjustment_run";

    private final JdbcClient jdbc;
    private final SequenceService seq;
    private final PostingService posting;
    private final CostingReversals reversals;
    private final AccountDeterminationService determination;
    private final LineageCapitalisation capitalisation;
    private final AuditService audit;

    public CostAdjustmentService(JdbcClient jdbc, SequenceService seq, PostingService posting,
            AccountDeterminationService determination, LineageCapitalisation capitalisation, AuditService audit, CostingReversals reversals) {
        this.jdbc = jdbc;
        this.seq = seq;
        this.posting = posting;
        this.reversals = reversals;
        this.determination = determination;
        this.capitalisation = capitalisation;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /**
     * WIP shares of completed orders not cascaded yet: lineage lines of late costs, and (review 2026-11-17, ACR-14)
     * adjunct released into the order's WIP. Each carries what is still in WIP — its amount less what an accrual
     * clearing already took back out ({@code clearedAmount}). IPR WIP-3 (deviation): also the shares of cancelled and
     * reversed orders (status {@code cancelled}), which no run picked up, so they stayed in WIP for ever; the run sends
     * them to the materials the cancel or reversal returned to stock.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> pending() {
        String order = " JOIN \"ProductionOrder\" o ON o.\"organizationId\" = x.\"organizationId\" AND (o.id = x.\"targetRef\""
                + " OR o.\"orderCode\" = x.\"targetRef\")";
        return SqlRows.list(jdbc.sql("SELECT * FROM (SELECT x.id, x.\"adjustmentId\", x.amount - x.\"clearedAmount\" AS"
                + " amount, o.\"orderCode\", o.status::text AS \"orderStatus\", a.\"adjustmentType\", a.\"sourceType\","
                + " a.\"sourceId\", 'line' AS kind, x.\"createdAt\" FROM \"CostValueAdjustmentLine\" x JOIN"
                + " \"CostValueAdjustment\" a ON a.id = x.\"adjustmentId\"" + order + " WHERE x.\"organizationId\" = ? AND"
                + " x.target = 'wip' AND a.\"reversedAt\" IS NULL AND o.status::text IN ('completed', 'closed',"
                + " 'cancelled') AND NOT EXISTS (SELECT 1 FROM \"CostAdjustmentRunLine\" r WHERE r.\"adjustmentLineId\" = x.id AND r.status ="
                + " 'posted') UNION ALL SELECT x.id, x.\"adjustmentId\", x.amount - x.\"clearedAmount\", o.\"orderCode\","
                + " o.status::text, a.\"adjustmentType\", 'cost_release', x.\"sourceId\", 'release', x.\"createdAt\" FROM"
                + " \"CostValueAdjustmentRelease\" x JOIN \"CostValueAdjustment\" a ON a.id = x.\"adjustmentId\"" + order
                + " WHERE x.\"organizationId\" = ? AND x.target = 'wip' AND a.\"reversedAt\" IS NULL AND o.status::text IN"
                + " ('completed', 'closed', 'cancelled') AND NOT EXISTS (SELECT 1 FROM \"CostAdjustmentRunLine\" r WHERE r.\"releaseId\" ="
                + " x.id AND r.status = 'posted')) p WHERE amount <> 0 ORDER BY \"orderCode\", \"createdAt\"")
                .params(org(), org()));
    }

    /** What a run would post (nothing is written). */
    @Transactional(readOnly = true)
    public Map<String, Object> preview() {
        List<Map<String, Object>> rows = pending();
        BigDecimal total = rows.stream().map(r -> SqlRows.dec(r, "amount")).reduce(BigDecimal.ZERO, BigDecimal::add);
        return Obj.of("lines", rows, "total", JsNumbers.dec(total.setScale(2, RoundingMode.HALF_UP)));
    }

    @Transactional
    public Map<String, Object> run(String fromText, String toText) {
        LocalDate from = day(fromText, "from");
        LocalDate to = day(toText, "to");
        if (to.isBefore(from)) {
            throw ApiException.badRequest("to must not be before from");
        }
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)").param("cost-adjustment:" + org()).query()
                .listOfRows();
        List<Map<String, Object>> rows = pending();
        if (rows.isEmpty()) {
            throw ApiException.badRequest("Nothing to adjust: no late cost is waiting in WIP of a completed order");
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant date = (to.isAfter(today) ? today : to).atStartOfDay(ZoneOffset.UTC).toInstant();
        String id = UUID.randomUUID().toString();
        String number = seq.next("cost_adjustment_run", "CAR/", 5);
        String text = "Cost adjustment run " + number;
        Map<String, BigDecimal> byOrder = new LinkedHashMap<>();
        Map<String, String> statusOf = new LinkedHashMap<>();
        rows.forEach(r -> {
            byOrder.merge((String) r.get("orderCode"), SqlRows.dec(r, "amount"), BigDecimal::add);
            statusOf.put((String) r.get("orderCode"), (String) r.get("orderStatus"));
        });
        List<Line> lines = new ArrayList<>();
        List<Part> allParts = new ArrayList<>();
        String wip = determination.mapped("wip");
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : byOrder.entrySet()) {
            BigDecimal amount = e.getValue().setScale(2, RoundingMode.HALF_UP);
            if (amount.signum() == 0) {
                continue;
            }
            // IPR WIP-3: a cancelled or reversed order has no output left; its share follows the materials its
            // cancel / reversal received back into stock (on hand → adjunct, since sold → cost of sales); with none
            // (a multi-stage reversal, legacy rows) it goes to cost of sales.
            List<LineageCapitalisation.Layer> layers;
            if ("cancelled".equals(statusOf.get(e.getKey()))) {
                layers = new ArrayList<>(capitalisation.receipts("production_order_cancel", List.of(e.getKey())));
                layers.addAll(capitalisation.receipts("production_reversal", List.of(e.getKey())));
            } else {
                layers = capitalisation.orderOutputs(e.getKey());
            }
            List<Part> parts = capitalisation.spread(amount, layers);
            allParts.addAll(parts);
            Map<String, Object> dims = Obj.of("productionOrderCode", e.getKey(), "costAdjustmentRunId", id);
            for (Map.Entry<String, BigDecimal> d : capitalisation.debits(parts).entrySet()) {
                if (d.getValue().setScale(2, RoundingMode.HALF_UP).signum() == 0) {
                    continue;
                }
                lines.add(signed(d.getKey(), d.getValue(), text + " · " + e.getKey(), dims));
            }
            lines.add(signed(wip, amount.negate(), text + " · " + e.getKey() + " WIP", dims));
            total = total.add(amount);
        }
        String je = null;
        if (!lines.isEmpty()) {
            Map<String, Object> entry = posting.post(new PostingRequest("INV", date, text, null, null, SOURCE, id, null,
                    "costing:cost_adjustment:" + org() + ":" + id, null, null, null, lines));
            je = entry == null ? null : (String) entry.get("id");
        }
        jdbc.sql("INSERT INTO \"CostAdjustmentRun\" (id, \"organizationId\", number, \"periodFrom\", \"periodTo\", total,"
                + " \"journalEntryId\", \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?)").params(id, org(), number,
                Date.valueOf(from), Date.valueOf(to), total, je, TenantContext.userId()).update();
        for (Map<String, Object> r : rows) {
            boolean release = "release".equals(r.get("kind"));
            jdbc.sql("INSERT INTO \"CostAdjustmentRunLine\" (id, \"organizationId\", \"runId\", \"adjustmentLineId\","
                    + " \"releaseId\", \"orderCode\", amount) VALUES (?, ?, ?, ?, ?, ?, ?)").params(UUID.randomUUID()
                    .toString(), org(), id, release ? null : r.get("id"), release ? r.get("id") : null,
                    r.get("orderCode"), SqlRows.dec(r, "amount")).update();
        }
        if (!allParts.isEmpty()) {
            capitalisation.record(allParts, "cost_adjustment", SOURCE, id, je, text);
        }
        audit.recordInTx(AuditInput.of("CostAdjustmentRun", id, "post", null, Obj.of("number", number, "total",
                total)));
        // A share that reached a later order's input waits in that order's WIP: follow it to the end of the chain.
        for (int depth = 0; depth < MAX_LEVELS && !pending().isEmpty(); depth++) {
            run(fromText, toText);
        }
        return get(id);
    }

    @Transactional
    public Map<String, Object> reverse(String id) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT * FROM \"CostAdjustmentRun\" WHERE id = ? AND"
                + " \"organizationId\" = ? FOR UPDATE").params(id, org()));
        if (r == null) {
            throw ApiException.notFound("Cost adjustment run not found");
        }
        if (!"posted".equals(r.get("status"))) {
            throw ApiException.badRequest("Only a posted run can be reversed");
        }
        capitalisation.assertUnreleased(SOURCE, id);
        // Review 2026-11-17 (ACR-8): an accrual clearing since took part of a carried share back out of the output;
        // putting the whole share back into WIP would leave WIP holding cost that is no longer there.
        String cleared = jdbc.sql("SELECT rl.\"orderCode\" FROM \"CostAdjustmentRunLine\" rl LEFT JOIN"
                + " \"CostValueAdjustmentLine\" l ON l.id = rl.\"adjustmentLineId\" LEFT JOIN \"CostValueAdjustmentRelease\" x"
                + " ON x.id = rl.\"releaseId\" WHERE rl.\"runId\" = ? AND rl.status = 'posted' AND rl.amount <>"
                + " coalesce(l.amount - l.\"clearedAmount\", x.amount - x.\"clearedAmount\") LIMIT 1").param(id)
                .query(String.class).optional().orElse(null);
        if (cleared != null) {
            throw ApiException.badRequest("A landed-cost accrual clearing has since taken part of the cost carried to "
                    + cleared + " back out; post a correcting document instead");
        }
        String reversal = reversals.reverseId((String) r.get("journalEntryId"), "Reversal of " + r.get("number"));
        capitalisation.reverse(SOURCE, id, (String) r.get("journalEntryId"), "Reversal of " + r.get("number"));
        jdbc.sql("UPDATE \"CostAdjustmentRunLine\" SET status = 'reversed' WHERE \"runId\" = ?").param(id).update();
        jdbc.sql("UPDATE \"CostAdjustmentRun\" SET status = 'reversed', \"reversalEntryId\" = ?, \"reversedAt\" = now()"
                + " WHERE id = ?").params(reversal, id).update();
        audit.recordInTx(AuditInput.of("CostAdjustmentRun", id, "cancel", null, Obj.of("reversalEntryId", reversal)));
        return get(id);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> runs() {
        return SqlRows.list(jdbc.sql("SELECT * FROM \"CostAdjustmentRun\" WHERE \"organizationId\" = ? ORDER BY"
                + " \"createdAt\" DESC").param(org()));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT * FROM \"CostAdjustmentRun\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (r == null) {
            throw ApiException.notFound("Cost adjustment run not found");
        }
        r.put("lines", SqlRows.list(jdbc.sql("SELECT * FROM \"CostAdjustmentRunLine\" WHERE \"runId\" = ? ORDER BY"
                + " \"orderCode\"").param(id)));
        return r;
    }

    private static Line signed(String account, BigDecimal amount, String text, Map<String, Object> dims) {
        BigDecimal a = amount.abs().setScale(2, RoundingMode.HALF_UP);
        return amount.signum() >= 0 ? new Line(account, JsNumbers.dec(a), null, null, text, null, null, dims)
                : new Line(account, null, JsNumbers.dec(a), null, text, null, null, dims);
    }

    static LocalDate day(String v, String field) {
        if (v == null || v.isBlank()) {
            throw ApiException.badRequest(field + " should not be empty");
        }
        try {
            return LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(field + " must be a valid ISO 8601 date string");
        }
    }
}
