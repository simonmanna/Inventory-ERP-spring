package com.inventoryerp.costing.application;

import com.inventoryerp.accounting.application.CostingDimensionGuard;
import com.inventoryerp.accounting.application.InventoryValuationService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cost close cockpit (Cost Management CM-10, ADR-S041). Nine ordered gates are evaluated for a period; each is
 * {@code pass}, {@code fail} or {@code n/a} (nothing of that kind exists for the organisation). The costing period can
 * be locked only when no gate fails; the lock then refuses costing postings dated inside it
 * ({@link CostingDimensionGuard#assertCostingPeriodOpen}) until it is reopened with {@code costing_close:reopen}
 * (audited, with a reason).
 *
 * <ol>
 *   <li>Landed cost: no draft landed-cost document dated in or before the period.</li>
 *   <li>Absorption: settled (a posted absorption settlement covers the period) where work-centre capacities exist.</li>
 *   <li>Allocation: every active allocation cycle has a posted run reaching the period end.</li>
 *   <li>NRV: a posted NRV review dated in the period, where stock is on hand.</li>
 *   <li>Quarry: rehabilitation unwinding and stripping amortisation posted for the period, where obligations /
 *       approved stripping assets exist.</li>
 *   <li>Projects: every active or completed contract with cost has a revenue recognition run up to the period end.</li>
 *   <li>Cost adjustment: no late cost waiting in WIP of a completed order (the cost adjustment run is done).</li>
 *   <li>Profitability: a current build of exactly this period that reconciles to 0.</li>
 *   <li>Reconciliations: inventory sub-ledger vs GL within tolerance, inter-branch clearing 0, every project's WIP
 *       tie-out 0, contract sub-ledgers vs GL 0.</li>
 * </ol>
 */
@Service
public class CostCloseService {

    private final JdbcClient jdbc;
    private final CostAdjustmentService adjustments;
    private final InventoryValuationService valuation;
    private final AuditService audit;
    private final JsonMapper json;

    public CostCloseService(JdbcClient jdbc, CostAdjustmentService adjustments, InventoryValuationService valuation,
            AuditService audit, JsonMapper json) {
        this.jdbc = jdbc;
        this.adjustments = adjustments;
        this.valuation = valuation;
        this.audit = audit;
        this.json = json;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    private static Map<String, Object> gate(int seq, String key, String label, String status, String detail) {
        return Obj.of("seq", seq, "key", key, "label", label, "status", status, "detail", detail);
    }

    private static String status(boolean applicable, boolean ok) {
        return !applicable ? "n/a" : ok ? "pass" : "fail";
    }

    /** The nine gates, in order, with the first failing one. */
    @Transactional
    public Map<String, Object> gates(String fromText, String toText) {
        LocalDate from = CostAdjustmentService.day(fromText, "from");
        LocalDate to = CostAdjustmentService.day(toText, "to");
        if (to.isBefore(from)) {
            throw ApiException.badRequest("to must not be before from");
        }
        String o = org();
        Date f = Date.valueOf(from);
        Date t = Date.valueOf(to);
        Timestamp end = Timestamp.from(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        List<Map<String, Object>> gates = new ArrayList<>();

        long drafts = count("SELECT count(*) FROM \"LandedCost\" WHERE \"organizationId\" = ? AND status = 'draft' AND"
                + " date < ?", o, end);
        gates.add(gate(1, "landed_cost", "Landed costs complete", status(true, drafts == 0), drafts
                + " draft landed-cost document(s)"));

        boolean capacities = count("SELECT count(*) FROM \"WorkCenterCapacity\" WHERE \"organizationId\" = ?", o) > 0;
        long settled = count("SELECT count(*) FROM \"AbsorptionSettlement\" WHERE \"organizationId\" = ? AND status ="
                + " 'posted' AND \"periodFrom\" <= ? AND \"periodTo\" >= ?", o, t, f);
        gates.add(gate(2, "absorption", "Absorption settled", status(capacities, settled > 0), settled
                + " posted settlement(s) in the period"));

        long cycles = count("SELECT count(*) FROM \"AllocationCycle\" WHERE \"organizationId\" = ? AND \"isActive\"", o);
        long unrun = count("SELECT count(*) FROM \"AllocationCycle\" c WHERE c.\"organizationId\" = ? AND c.\"isActive\""
                + " AND NOT EXISTS (SELECT 1 FROM \"AllocationRun\" r WHERE r.\"cycleId\" = c.id AND r.status = 'posted'"
                + " AND r.\"periodFrom\" <= ? AND r.\"periodTo\" >= ?)", o, t, t);
        gates.add(gate(3, "allocation", "Allocation cycles posted", status(cycles > 0, unrun == 0), unrun
                + " active cycle(s) without a posted run for the period"));

        boolean stock = count("SELECT count(*) FROM \"StockItem\" WHERE \"organizationId\" = ? AND quantity > 0", o) > 0;
        long nrv = count("SELECT count(*) FROM \"InventoryRevaluation\" WHERE \"organizationId\" = ? AND kind = 'nrv' AND"
                + " status = 'posted' AND \"revaluationDate\" BETWEEN ? AND ?", o, f, t);
        gates.add(gate(4, "nrv", "NRV review done", status(stock, nrv > 0), nrv + " posted NRV review(s) in the"
                + " period"));

        long obligations = count("SELECT count(*) FROM \"QuarryRehabObligation\" WHERE \"organizationId\" = ? AND status"
                + " = 'open'", o);
        long unwound = count("SELECT count(*) FROM \"QuarryRehabMovement\" WHERE \"organizationId\" = ? AND type ="
                + " 'unwind' AND status = 'posted' AND \"movementDate\" BETWEEN ? AND ?", o, f, t);
        long assets = count("SELECT count(*) FROM \"QuarryStrippingAsset\" WHERE \"organizationId\" = ? AND status ="
                + " 'approved'", o);
        long amortised = count("SELECT count(*) FROM \"QuarryStrippingMovement\" WHERE \"organizationId\" = ? AND type ="
                + " 'amortise' AND status = 'posted' AND \"periodFrom\" <= ? AND \"periodTo\" >= ?", o, t, f);
        boolean quarry = obligations > 0 || assets > 0;
        gates.add(gate(5, "quarry", "Stripping amortisation and provision unwinding posted", status(quarry,
                (obligations == 0 || unwound > 0) && (assets == 0 || amortised > 0)), "unwinding " + unwound
                + ", amortisation " + amortised));

        long contracts = count("SELECT count(*) FROM \"Project\" p WHERE p.\"organizationId\" = ? AND p.status IN"
                + " ('active', 'completed') AND p.\"customerId\" IS NOT NULL AND EXISTS (SELECT 1 FROM \"ProjectCostLine\""
                + " c WHERE c.\"projectId\" = p.id AND c.status = 'posted' AND c.date <= ?)", o, t);
        long unrecognised = count("SELECT count(*) FROM \"Project\" p WHERE p.\"organizationId\" = ? AND p.status IN"
                + " ('active', 'completed') AND p.\"customerId\" IS NOT NULL AND EXISTS (SELECT 1 FROM \"ProjectCostLine\""
                + " c WHERE c.\"projectId\" = p.id AND c.status = 'posted' AND c.date <= ?) AND NOT EXISTS (SELECT 1 FROM"
                + " \"RevenueRecognitionRun\" r WHERE r.\"projectId\" = p.id AND r.status = 'posted' AND r.\"periodEnd\" >="
                + " ?)", o, t, t);
        gates.add(gate(6, "revenue", "Project revenue recognition run", status(contracts > 0, unrecognised == 0),
                unrecognised + " contract(s) without a revenue run to the period end"));

        int pending = adjustments.pending().size();
        gates.add(gate(7, "cost_adjustment", "Cost adjustment run done", status(true, pending == 0), pending
                + " late WIP share(s) waiting"));

        Map<String, Object> pf = SqlRows.one(jdbc.sql("SELECT difference FROM \"ProfitabilityRun\" WHERE"
                + " \"organizationId\" = ? AND status = 'current' AND \"periodFrom\" = ? AND \"periodTo\" = ?").params(o, f,
                t));
        gates.add(gate(8, "profitability", "Profitability rebuilt and reconciled", status(true, pf != null
                && SqlRows.dec(pf, "difference").signum() == 0), pf == null ? "no build for the period"
                : "difference " + JsNumbers.dec(SqlRows.dec(pf, "difference"))));

        List<Map<String, Object>> recon = reconciliations(to);
        long off = recon.stream().filter(r -> !Boolean.TRUE.equals(r.get("ok"))).count();
        gates.add(gate(9, "reconciliations", "Reconciliations at zero", status(true, off == 0), off
                + " reconciliation(s) off"));

        Map<String, Object> blocking = gates.stream().filter(g -> "fail".equals(g.get("status"))).findFirst()
                .orElse(null);
        return Obj.of("from", from.toString(), "to", to.toString(), "gates", gates, "reconciliations", recon,
                "blocking", blocking, "ready", blocking == null);
    }

    /** Sub-ledger vs GL rows (as of the period end where the sub-ledger supports it). */
    List<Map<String, Object>> reconciliations(LocalDate asOf) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> inv = valuation.glTieOut(asOf.toString(), 0.01);
        rows.add(Obj.of("key", "inventory", "label", "Inventory sub-ledger vs GL", "difference", inv.get("variance"),
                "ok", Boolean.TRUE.equals(inv.get("withinTolerance"))));
        BigDecimal clearing = jdbc.sql("SELECT coalesce(sum(jl.\"baseDebit\" - jl.\"baseCredit\"), 0) FROM"
                + " \"JournalLine\" jl JOIN \"JournalEntry\" e ON e.id = jl.\"journalEntryId\" JOIN \"Account\" a ON a.id ="
                + " jl.\"accountId\" WHERE jl.\"organizationId\" = ? AND a.code = '1299' AND e.status <> 'draft'")
                .param(org()).query(BigDecimal.class).single();
        rows.add(Obj.of("key", "inter_branch_clearing", "label", "Inter-branch clearing nets to zero", "difference",
                JsNumbers.dec(clearing), "ok", clearing.signum() == 0));
        BigDecimal wipOff = BigDecimal.ZERO;
        for (Map<String, Object> p : SqlRows.list(jdbc.sql("SELECT p.id, coalesce((SELECT sum(c.amount) FROM"
                + " \"ProjectCostLine\" c WHERE c.\"projectId\" = p.id AND c.status = 'posted'), 0) - coalesce((SELECT"
                + " sum(r.\"costThisPeriod\") FROM \"RevenueRecognitionRun\" r WHERE r.\"projectId\" = p.id AND r.status ="
                + " 'posted'), 0) AS sub, coalesce((SELECT sum(jl.\"baseDebit\" - jl.\"baseCredit\") FROM \"JournalLine\""
                + " jl JOIN \"JournalEntry\" e ON e.id = jl.\"journalEntryId\" JOIN \"Account\" a ON a.id ="
                + " jl.\"accountId\" WHERE jl.\"organizationId\" = p.\"organizationId\" AND a.code = '1460' AND"
                + " e.status <> 'draft' AND jl.dimensions ->> 'projectId' = p.id), 0) AS gl FROM \"Project\" p WHERE"
                + " p.\"organizationId\" = ?").param(org()))) {
            wipOff = wipOff.add(SqlRows.dec(p, "gl").subtract(SqlRows.dec(p, "sub")).abs());
        }
        rows.add(Obj.of("key", "project_wip", "label", "Project WIP vs project cost sub-ledger", "difference",
                JsNumbers.dec(wipOff), "ok", wipOff.signum() == 0));
        BigDecimal contractOff = contractDifference();
        rows.add(Obj.of("key", "contracts", "label", "Retentions, advances and onerous provisions vs GL",
                "difference", JsNumbers.dec(contractOff), "ok", contractOff.signum() == 0));
        return rows;
    }

    private BigDecimal contractDifference() {
        String o = org();
        BigDecimal off = BigDecimal.ZERO;
        off = off.add(diff("1475", jdbc.sql("SELECT coalesce(sum(amount), 0) FROM \"RetentionLedger\" WHERE"
                + " \"organizationId\" = ? AND side = 'customer' AND status = 'held' AND classification ="
                + " 'contract_asset'").param(o).query(BigDecimal.class).single(), false));
        off = off.add(diff("2485", jdbc.sql("SELECT coalesce(sum(amount - \"backCharged\"), 0) FROM \"RetentionLedger\""
                + " WHERE \"organizationId\" = ? AND side = 'subcontractor' AND status = 'held'").param(o)
                .query(BigDecimal.class).single(), true));
        off = off.add(diff("2480", jdbc.sql("SELECT coalesce(sum(amount - recovered), 0) FROM \"CustomerAdvance\" WHERE"
                + " \"organizationId\" = ?").param(o).query(BigDecimal.class).single(), true));
        off = off.add(diff("2490", jdbc.sql("SELECT coalesce(sum(movement), 0) FROM \"OnerousContractAssessment\" WHERE"
                + " \"organizationId\" = ? AND status = 'posted'").param(o).query(BigDecimal.class).single(), true));
        return off;
    }

    private BigDecimal diff(String code, BigDecimal sub, boolean creditNormal) {
        BigDecimal gl = jdbc.sql("SELECT coalesce(sum(jl.\"baseDebit\" - jl.\"baseCredit\"), 0) FROM \"JournalLine\" jl"
                + " JOIN \"JournalEntry\" e ON e.id = jl.\"journalEntryId\" JOIN \"Account\" a ON a.id = jl.\"accountId\""
                + " WHERE jl.\"organizationId\" = ? AND a.code = ? AND e.status <> 'draft'").params(org(), code)
                .query(BigDecimal.class).single();
        return (creditNormal ? gl.negate() : gl).subtract(sub).abs();
    }

    /** Reconciliation dashboard as of a date. */
    @Transactional
    public Map<String, Object> dashboard(String asOfText) {
        LocalDate asOf = asOfText == null || asOfText.isBlank() ? LocalDate.now(ZoneOffset.UTC)
                : CostAdjustmentService.day(asOfText, "asOf");
        return Obj.of("asOf", asOf.toString(), "rows", reconciliations(asOf));
    }

    /** Lock the costing period; refused (409) while any gate fails or another lock overlaps. */
    @Transactional
    public Map<String, Object> lock(String fromText, String toText) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)").param("costing-close:" + org()).query()
                .listOfRows();
        Map<String, Object> g = gates(fromText, toText);
        if (g.get("blocking") instanceof Map<?, ?> b) {
            throw ApiException.conflict("Gate " + b.get("seq") + " (" + b.get("label") + ") blocks the costing close: "
                    + b.get("detail"));
        }
        Date f = Date.valueOf((String) g.get("from"));
        Date t = Date.valueOf((String) g.get("to"));
        if (count("SELECT count(*) FROM \"CostingPeriodLock\" WHERE \"organizationId\" = ? AND status = 'locked' AND"
                + " \"periodFrom\" <= ? AND \"periodTo\" >= ?", org(), t, f) > 0) {
            throw ApiException.conflict("A locked costing period already overlaps " + g.get("from") + " – "
                    + g.get("to"));
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"CostingPeriodLock\" (id, \"organizationId\", \"periodFrom\", \"periodTo\", gates,"
                + " \"lockedBy\") VALUES (?, ?, ?, ?, ?::jsonb, ?)").params(id, org(), f, t, json.writeValueAsString(
                g.get("gates")), TenantContext.userId()).update();
        audit.recordInTx(AuditInput.of("CostingPeriodLock", id, "create", null, Obj.of("from", g.get("from"), "to",
                g.get("to"))));
        return lockRow(id);
    }

    /** Reopen a locked costing period (permission {@code costing_close:reopen}); the reason is audited. */
    @Transactional
    public Map<String, Object> reopen(String id, String reason) {
        Map<String, Object> l = SqlRows.one(jdbc.sql("SELECT * FROM \"CostingPeriodLock\" WHERE id = ? AND"
                + " \"organizationId\" = ? FOR UPDATE").params(id, org()));
        if (l == null) {
            throw ApiException.notFound("Costing period lock not found");
        }
        if (!"locked".equals(l.get("status"))) {
            throw ApiException.badRequest("This costing period is not locked");
        }
        if (reason == null || reason.trim().length() < 10) {
            throw ApiException.badRequest("Give the reason for reopening (at least 10 characters)");
        }
        jdbc.sql("UPDATE \"CostingPeriodLock\" SET status = 'reopened', \"reopenedBy\" = ?, \"reopenedAt\" = now(),"
                + " \"reopenReason\" = ? WHERE id = ?").params(TenantContext.userId(), reason.trim(), id).update();
        audit.recordInTx(AuditInput.of("CostingPeriodLock", id, "update", Obj.of("status", "locked"), Obj.of("status",
                "reopened", "reason", reason.trim())));
        return lockRow(id);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> locks() {
        return SqlRows.list(jdbc.sql("SELECT * FROM \"CostingPeriodLock\" WHERE \"organizationId\" = ? ORDER BY"
                + " \"periodFrom\" DESC, \"lockedAt\" DESC").param(org()));
    }

    private Map<String, Object> lockRow(String id) {
        return SqlRows.one(jdbc.sql("SELECT * FROM \"CostingPeriodLock\" WHERE id = ?").param(id));
    }
}
