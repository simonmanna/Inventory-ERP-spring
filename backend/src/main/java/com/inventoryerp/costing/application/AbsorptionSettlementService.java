package com.inventoryerp.costing.application;

import com.inventoryerp.accounting.application.AccountingDay;
import com.inventoryerp.shared.costing.AllocationCalculator;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.AccountingPolicyService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.costing.application.AbsorptionSettlementCalculator.Decomposition;
import com.inventoryerp.costing.application.AbsorptionSettlementCalculator.Inputs;
import com.inventoryerp.costing.application.AbsorptionSettlementCalculator.Plan;
import com.inventoryerp.costing.application.AbsorptionSettlementCalculator.Treatment;
import com.inventoryerp.shared.costing.AllocationCalculator.Receiver;
import com.inventoryerp.shared.costing.AllocationCalculator.Rounding;
import com.inventoryerp.inventory.application.AvcoLineageCalculator.Distribution;
import com.inventoryerp.inventory.application.AvcoLineageCalculator.Share;
import com.inventoryerp.inventory.application.CostLineageService;
import com.inventoryerp.inventory.application.CostLineageService.AdjustmentInput;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.CostAbsorptionService;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Normal capacity and absorption settlement (Cost Management CM-4, ADR-S034, IAS 2.13, AD-11).
 *
 * <ul>
 *   <li><b>Normal capacity</b> per work centre is governed: proposed with its basis, derivation and budgeted fixed
 *       overhead, approved by someone else, reviewed at least yearly. Without an approved, current capacity a work
 *       centre cannot be settled.</li>
 *   <li><b>Actual</b> is the L3 cost posted on the work centre's cost centre in the period, through the AD-10
 *       eligibility gate: only accounts classified {@code production_capitalisable} enter the pool; other classes are
 *       reported as expensed directly; an unclassified account blocks the settlement.</li>
 *   <li><b>Absorbed</b> is what the rates posted for the centre's work orders
 *       ({@code ProductionCostAbsorption}).</li>
 *   <li>Timing items block: draft journals on the cost centre, conversion cost not absorbed yet, absorbed cost of an
 *       order that has not posted its output, and orders still open.</li>
 *   <li>The difference is decomposed and treated by cause ({@link AbsorptionSettlementCalculator}). Amounts that
 *       reach inventory follow the <em>lineage of the period's output layers</em> (on hand → price-difference adjunct
 *       1396, sold → cost of sales, consumed by an open order → WIP, scrapped → stock adjustment expense); an older
 *       layer is never touched. The counter-entry is the overhead-absorbed pool account.</li>
 * </ul>
 */
@Service
public class AbsorptionSettlementService {

    static final String SOURCE = "absorption_settlement";
    private static final Set<String> BASES = Set.of("machine_hours", "labour_hours");
    private static final Set<String> RATE_SOURCES = Set.of("manual", "fleet_tco", "payroll_actual");
    private static final String EXPENSE_CATEGORIES = "('operating_expense', 'cost_of_goods_sold', 'other_expense')";
    private static final BigDecimal SIXTY = BigDecimal.valueOf(60);

    /** A proposed normal capacity. Amounts are per {@code periodDays}. */
    public record CapacityInput(String workCenterId, String costCenterId, String basis, BigDecimal normalCapacity,
            BigDecimal budgetFixedOverhead, BigDecimal fixedOverheadRate, Integer periodDays, String derivation,
            String rateSource, String effectiveFrom, String reviewDue) {}

    /** A settlement request. {@code classification} is the reviewer's call above the tolerance. */
    public record SettleInput(String workCenterId, String from, String to, String classification,
            String justification) {}

    /** One output layer of the period's production. */
    record Layer(String orderId, String ledgerId, String productId, String variantId, String locationId,
            BigDecimal value) {}

    /** One computed settlement line before posting. */
    record Computed(Treatment t, String target, String orderId, Layer layer, String accountId, BigDecimal amount,
            Distribution distribution) {}

    private final JdbcClient jdbc;
    private final SequenceService seq;
    private final PostingService posting;
    private final AccountDeterminationService determination;
    private final AccountResolverService accounts;
    private final AccountingPolicyService policies;
    private final CostLineageService lineage;
    private final CostAbsorptionService absorption;
    private final AuditService audit;

    public AbsorptionSettlementService(JdbcClient jdbc, SequenceService seq, PostingService posting,
            AccountDeterminationService determination, AccountResolverService accounts,
            AccountingPolicyService policies, CostLineageService lineage, CostAbsorptionService absorption,
            AuditService audit) {
        this.jdbc = jdbc;
        this.seq = seq;
        this.posting = posting;
        this.determination = determination;
        this.accounts = accounts;
        this.policies = policies;
        this.lineage = lineage;
        this.absorption = absorption;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    // ---- accounts ---------------------------------------------------------------------------------------------------

    /** IAS 2.13: fixed overhead not absorbed because activity was below normal capacity (5970). */
    public String unabsorbedAccount() {
        return accounts.ensureByCode("5970", "Unabsorbed Production Overhead", "cost_of_goods_sold",
                "unabsorbed_overhead");
    }

    /** IAS 2.16: abnormal production cost (breakdown, waste) — always expensed (5972). */
    public String abnormalAccount() {
        return accounts.ensureByCode("5972", "Abnormal Production Cost", "cost_of_goods_sold",
                "abnormal_production_cost");
    }

    // ---- normal capacity ----------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> capacities(String workCenterId) {
        String filter = workCenterId == null || workCenterId.isBlank() ? "" : " AND c.\"workCenterId\" = ?";
        List<Object> params = new ArrayList<>(List.of(org()));
        if (!filter.isEmpty()) {
            params.add(workCenterId);
        }
        return SqlRows.list(jdbc.sql("SELECT c.*, w.code AS \"workCenterCode\", w.name AS \"workCenterName\","
                + " cc.code AS \"costCenterCode\" FROM \"WorkCenterCapacity\" c JOIN \"WorkCenter\" w ON w.id ="
                + " c.\"workCenterId\" JOIN \"CostCenter\" cc ON cc.id = c.\"costCenterId\" WHERE c.\"organizationId\" ="
                + " ?" + filter + " ORDER BY w.code, c.\"effectiveFrom\" DESC, c.\"createdAt\" DESC").params(params));
    }

    @Transactional
    public Map<String, Object> proposeCapacity(CapacityInput in) {
        requireRow("WorkCenter", in.workCenterId(), "Work centre not found");
        requireRow("CostCenter", in.costCenterId(), "Cost centre not found");
        String basis = in.basis() == null ? "machine_hours" : in.basis();
        if (!BASES.contains(basis)) {
            throw ApiException.badRequest("basis must be one of the following values: machine_hours, labour_hours");
        }
        String source = in.rateSource() == null ? "manual" : in.rateSource();
        if (!RATE_SOURCES.contains(source)) {
            throw ApiException.badRequest("rateSource must be one of the following values: manual, fleet_tco,"
                    + " payroll_actual");
        }
        if (in.normalCapacity() == null || in.normalCapacity().signum() <= 0) {
            throw ApiException.badRequest("normalCapacity must be greater than 0");
        }
        BigDecimal budget = in.budgetFixedOverhead() == null ? BigDecimal.ZERO : in.budgetFixedOverhead();
        if (budget.signum() < 0 || (in.fixedOverheadRate() != null && in.fixedOverheadRate().signum() < 0)) {
            throw ApiException.badRequest("budgetFixedOverhead and fixedOverheadRate must not be negative");
        }
        int days = in.periodDays() == null ? 30 : in.periodDays();
        if (days < 1 || days > 366) {
            throw ApiException.badRequest("periodDays must be between 1 and 366");
        }
        if (in.derivation() == null || in.derivation().trim().length() < 10) {
            throw ApiException.badRequest("Explain how the normal capacity was derived (at least 10 characters):"
                    + " average of past periods adjusted for planned maintenance, or design capacity × expected"
                    + " efficiency");
        }
        LocalDate from = day(in.effectiveFrom(), "effectiveFrom");
        if (from.isBefore(today())) {
            throw ApiException.badRequest("A normal capacity applies prospectively; effectiveFrom cannot be in the past");
        }
        LocalDate review = in.reviewDue() == null ? from.plusYears(1) : day(in.reviewDue(), "reviewDue");
        if (!review.isAfter(from) || review.isAfter(from.plusYears(1))) {
            throw ApiException.badRequest("reviewDue must fall after effectiveFrom and within one year of it");
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"WorkCenterCapacity\" (id, \"organizationId\", \"workCenterId\", \"costCenterId\", basis,"
                        + " \"normalCapacity\", \"budgetFixedOverhead\", \"fixedOverheadRate\", \"periodDays\", derivation,"
                        + " \"rateSource\", \"effectiveFrom\", \"reviewDue\", \"proposedBy\") VALUES (?, ?, ?, ?, ?, ?, ?,"
                        + " ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), in.workCenterId(), in.costCenterId(), basis, in.normalCapacity(), budget,
                        in.fixedOverheadRate(), days, in.derivation().trim(), source, Date.valueOf(from),
                        Date.valueOf(review), TenantContext.userId())
                .update();
        audit.recordInTx(AuditInput.of("WorkCenterCapacity", id, "create", null, Obj.of("workCenterId",
                in.workCenterId(), "normalCapacity", in.normalCapacity(), "basis", basis)));
        return capacity(id);
    }

    /** Maker-checker: a capacity is approved by someone other than its proposer. */
    @Transactional
    public Map<String, Object> approveCapacity(String id) {
        Map<String, Object> c = capacity(id);
        if (!"proposed".equals(c.get("status"))) {
            throw ApiException.badRequest("Only a proposed capacity can be approved");
        }
        if (TenantContext.userId() != null && TenantContext.userId().equals(c.get("proposedBy"))) {
            throw ApiException.forbidden("A normal capacity must be approved by someone other than its proposer");
        }
        jdbc.sql("UPDATE \"WorkCenterCapacity\" SET status = 'approved', \"approvedBy\" = ?, \"approvedAt\" = now()"
                + " WHERE id = ?").params(TenantContext.userId(), id).update();
        audit.recordInTx(AuditInput.of("WorkCenterCapacity", id, "approve", null, Obj.of("workCenterId",
                c.get("workCenterId"))));
        return capacity(id);
    }

    private Map<String, Object> capacity(String id) {
        Map<String, Object> c = SqlRows.one(jdbc.sql("SELECT * FROM \"WorkCenterCapacity\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (c == null) {
            throw ApiException.notFound("Normal capacity not found");
        }
        return c;
    }

    /** The approved capacity in force at the end of the period, or null. */
    private Map<String, Object> capacityInForce(String workCenterId, LocalDate to) {
        return SqlRows.one(jdbc.sql("SELECT * FROM \"WorkCenterCapacity\" WHERE \"organizationId\" = ? AND"
                + " \"workCenterId\" = ? AND status = 'approved' AND \"effectiveFrom\" <= ? ORDER BY \"effectiveFrom\""
                + " DESC, \"approvedAt\" DESC LIMIT 1").params(org(), workCenterId, Date.valueOf(to)));
    }

    // ---- analysis -----------------------------------------------------------------------------------------------------

    /** Everything a settlement needs, plus what blocks it. */
    private Map<String, Object> analyse(SettleInput in, List<Computed> computed) {
        Map<String, Object> wc = requireRow("WorkCenter", in.workCenterId(), "Work centre not found");
        LocalDate from = day(in.from(), "from");
        LocalDate to = day(in.to(), "to");
        if (to.isBefore(from)) {
            throw ApiException.badRequest("to must not be before from");
        }
        if (to.isAfter(today())) {
            throw ApiException.badRequest("A settlement cannot end in the future");
        }
        List<String> blockers = new ArrayList<>();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workCenterId", in.workCenterId());
        out.put("workCenterCode", wc.get("code"));
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("blockers", blockers);
        Map<String, Object> cap = capacityInForce(in.workCenterId(), to);
        if (cap == null) {
            blockers.add("No approved normal capacity for work centre " + wc.get("code") + " (IAS 2.13)");
            return out;
        }
        out.put("capacityId", cap.get("id"));
        LocalDate reviewDue = LocalDate.parse(cap.get("reviewDue").toString().substring(0, 10));
        if (reviewDue.isBefore(to)) {
            blockers.add("The normal capacity of " + wc.get("code") + " was due for review on " + reviewDue
                    + "; approve a reviewed capacity first");
        }
        String costCenter = (String) cap.get("costCenterId");
        String pool = determination.mapped("overhead_absorbed");
        Timestamp start = AccountingDay.startTs(from, AccountingDay.zone(jdbc));
        Timestamp end = AccountingDay.endExclusiveTs(to, AccountingDay.zone(jdbc));

        // Timing: draft journals on the cost centre.
        long drafts = jdbc.sql("SELECT count(DISTINCT e.id) FROM \"JournalLine\" jl JOIN \"JournalEntry\" e ON e.id ="
                + " jl.\"journalEntryId\" WHERE jl.\"organizationId\" = ? AND jl.\"costCenterId\" = ? AND e.status ="
                + " 'draft' AND e.\"postingDate\" >= ? AND e.\"postingDate\" < ?").params(org(), costCenter, start, end)
                .query(Long.class).single();
        if (drafts > 0) {
            blockers.add(drafts + " draft journal entr" + (drafts == 1 ? "y" : "ies") + " on the cost centre in the"
                    + " period; post or delete them first (timing)");
        }

        // L3 actual by account, through the eligibility gate.
        BigDecimal actual = BigDecimal.ZERO;
        BigDecimal expensedDirectly = BigDecimal.ZERO;
        List<Map<String, Object>> byAccount = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT a.id, a.code, a.name, sum(jl.\"baseDebit\" -"
                        + " jl.\"baseCredit\") AS amount FROM \"JournalLine\" jl JOIN \"JournalEntry\" e ON e.id ="
                        + " jl.\"journalEntryId\" JOIN \"Account\" a ON a.id = jl.\"accountId\" JOIN \"AccountCategory\" c"
                        + " ON c.id = a.\"categoryId\" WHERE jl.\"organizationId\" = ? AND jl.\"costCenterId\" = ? AND"
                        + " e.status <> 'draft' AND e.\"postingDate\" >= ? AND e.\"postingDate\" < ? AND c.key IN "
                        + EXPENSE_CATEGORIES + " AND a.id <> ? AND coalesce(e.\"sourceType\", '') <> ? GROUP BY a.id,"
                        + " a.code, a.name HAVING sum(jl.\"baseDebit\" - jl.\"baseCredit\") <> 0 ORDER BY a.code")
                .params(org(), costCenter, start, end, pool, SOURCE))) {
            BigDecimal amount = SqlRows.dec(r, "amount");
            String cls = policies.eligibility("account", (String) r.get("id"));
            r.put("eligibility", cls);
            byAccount.add(r);
            if (cls == null) {
                blockers.add("Account " + r.get("code") + " carries cost on the cost centre but has no cost"
                        + " eligibility (IAS 2.10–2.16); classify it under Costing & Valuation");
            } else if ("production_capitalisable".equals(cls)) {
                actual = actual.add(amount);
            } else {
                expensedDirectly = expensedDirectly.add(amount);
            }
        }

        // Absorbed: what the centre's rates posted; timing items on the orders.
        BigDecimal absorbed = BigDecimal.ZERO;
        Map<String, BigDecimal> absorbedByOrder = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT a.\"orderId\", o.\"orderCode\", o.status::text AS"
                        + " status, o.\"postedAt\", bool_or(a.\"stagePostingId\" IS NULL) AS plain, sum(a.amount) AS"
                        + " amount FROM \"ProductionCostAbsorption\" a JOIN \"ProductionOrder\" o ON o.id = a.\"orderId\""
                        + " LEFT JOIN \"ProductionStagePosting\" p ON p.id = a.\"stagePostingId\" WHERE"
                        + " a.\"organizationId\" = ? AND a.\"workCenterId\" = ? AND a.\"createdAt\" >= ? AND"
                        + " a.\"createdAt\" < ? AND p.\"reversedAt\" IS NULL GROUP BY a.\"orderId\", o.\"orderCode\","
                        + " o.status, o.\"postedAt\" ORDER BY o.\"orderCode\"")
                .params(org(), in.workCenterId(), start, end))) {
            String status = (String) r.get("status");
            if (Boolean.TRUE.equals(r.get("plain")) && r.get("postedAt") == null) {
                blockers.add("Production order " + r.get("orderCode") + " has absorbed cost that is not posted yet;"
                        + " complete the order first (timing)");
            } else if (!Set.of("completed", "closed", "qc_hold").contains(status)) {
                blockers.add("Production order " + r.get("orderCode") + " is still open; settle once it has"
                        + " completed (timing)");
            }
            BigDecimal amount = SqlRows.dec(r, "amount");
            absorbedByOrder.put((String) r.get("orderId"), amount);
            absorbed = absorbed.add(amount);
        }
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT DISTINCT o.id, o.\"orderCode\" FROM \"WorkOrder\" w"
                        + " JOIN \"ProductionOrder\" o ON o.id = w.\"productionOrderId\" WHERE w.\"organizationId\" = ? AND"
                        + " w.\"workCenterId\" = ? AND w.status::text <> 'cancelled' AND o.status::text NOT IN"
                        + " ('completed', 'closed', 'cancelled', 'qc_hold') AND coalesce(w.\"completedAt\","
                        + " w.\"startedAt\") >= ? AND coalesce(w.\"completedAt\", w.\"startedAt\") < ?")
                .params(org(), in.workCenterId(), start, end))) {
            if (!absorption.preview((String) r.get("id")).isEmpty()) {
                blockers.add("Production order " + r.get("orderCode") + " has conversion cost not absorbed yet"
                        + " (timing)");
            }
        }

        // Activity and decomposition.
        BigDecimal activity = activity(in.workCenterId(), (String) cap.get("basis"), start, end);
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        BigDecimal factor = BigDecimal.valueOf(days).divide(BigDecimal.valueOf(((Number) cap.get("periodDays"))
                .longValue()), 10, RoundingMode.HALF_UP);
        BigDecimal normal = SqlRows.dec(cap, "normalCapacity").multiply(factor).setScale(4, RoundingMode.HALF_UP);
        BigDecimal budget = SqlRows.dec(cap, "budgetFixedOverhead").multiply(factor).setScale(2, RoundingMode.HALF_UP);
        Decomposition d = AbsorptionSettlementCalculator.decompose(new Inputs(actual, absorbed, budget, normal, activity,
                cap.get("fixedOverheadRate") == null ? null : SqlRows.dec(cap, "fixedOverheadRate")));
        BigDecimal tolerance = policies.currentNumber("price_difference_tolerance_pct");
        BigDecimal materiality = policies.currentNumber("materiality_threshold");
        String classification = in.classification() == null || in.classification().isBlank() ? null
                : in.classification();
        if (classification != null && !Set.of("normal", "abnormal").contains(classification)) {
            throw ApiException.badRequest("classification must be one of the following values: normal, abnormal");
        }
        Plan plan = AbsorptionSettlementCalculator.plan(d, absorbed, tolerance, materiality, classification);
        if (plan.blocker() != null) {
            blockers.add(plan.blocker());
        }
        if ("normal".equals(classification) && (in.justification() == null
                || in.justification().trim().length() < 10)) {
            blockers.add("Capitalising spending above the tolerance needs a justification of at least 10 characters");
        }

        out.put("costCenterId", costCenter);
        out.put("byAccount", byAccount);
        out.put("actual", JsNumbers.dec(actual));
        out.put("expensedDirectly", JsNumbers.dec(expensedDirectly));
        out.put("absorbed", JsNumbers.dec(absorbed));
        out.put("budgetFixed", JsNumbers.dec(budget));
        out.put("normalActivity", JsNumbers.dec(normal));
        out.put("actualActivity", JsNumbers.dec(activity));
        out.put("standardRate", JsNumbers.dec(d.standardRate()));
        out.put("rateUsed", JsNumbers.dec(d.rateUsed()));
        out.put("spending", JsNumbers.dec(d.spending()));
        out.put("volume", JsNumbers.dec(d.volume()));
        out.put("rateError", JsNumbers.dec(d.rateError()));
        out.put("total", JsNumbers.dec(d.total()));
        out.put("tolerancePct", JsNumbers.dec(tolerance));
        out.put("materiality", JsNumbers.dec(materiality));
        out.put("decomposition", d);
        if (blockers.isEmpty()) {
            computed.addAll(compute(plan, absorbedByOrder));
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Computed c : computed) {
            lines.add(Obj.of("component", c.t().component(), "cause", c.t().cause(), "treatment", c.t().treatment(),
                    "target", c.target(), "orderId", c.orderId(), "productId", c.layer() == null ? null
                            : c.layer().productId(), "accountId", c.accountId(), "amount", JsNumbers.dec(c.amount())));
        }
        out.put("lines", lines);
        return out;
    }

    /** Actual activity on the basis: run hours (machine) or clocked crew hours, else run hours (labour). */
    private BigDecimal activity(String workCenterId, String basis, Timestamp start, Timestamp end) {
        BigDecimal minutes = "labour_hours".equals(basis)
                ? jdbc.sql("SELECT coalesce(sum(CASE WHEN c.mins > 0 THEN c.mins ELSE coalesce(w.\"labourMins\", 0)"
                        + " END), 0) FROM \"WorkOrder\" w LEFT JOIN (SELECT \"workOrderId\", sum(minutes) AS mins FROM"
                        + " \"ShopFloorClockEntry\" WHERE \"organizationId\" = ? AND \"clockOffAt\" IS NOT NULL GROUP BY"
                        + " \"workOrderId\") c ON c.\"workOrderId\" = w.id WHERE w.\"organizationId\" = ? AND"
                        + " w.\"workCenterId\" = ? AND w.status::text = 'completed' AND w.\"completedAt\" >= ? AND"
                        + " w.\"completedAt\" < ?").params(org(), org(), workCenterId, start, end)
                        .query(BigDecimal.class).single()
                : jdbc.sql("SELECT coalesce(sum(\"labourMins\"), 0) FROM \"WorkOrder\" WHERE \"organizationId\" = ? AND"
                        + " \"workCenterId\" = ? AND status::text = 'completed' AND \"completedAt\" >= ? AND"
                        + " \"completedAt\" < ?").params(org(), workCenterId, start, end).query(BigDecimal.class)
                        .single();
        return minutes.divide(SIXTY, 4, RoundingMode.HALF_UP);
    }

    /** Lines of the plan; inventory treatments are spread over the period's output layers and their lineage. */
    private List<Computed> compute(Plan plan, Map<String, BigDecimal> absorbedByOrder) {
        List<Computed> out = new ArrayList<>();
        for (Treatment t : plan.lines()) {
            switch (t.treatment()) {
                case "expense" -> out.add(new Computed(t, "expense", null, null, "abnormal".equals(t.cause())
                        ? abnormalAccount() : unabsorbedAccount(), t.amount(), null));
                case "cogs" -> out.add(new Computed(t, "cogs", null, null, determination.mapped("cogs"), t.amount(),
                        null));
                default -> out.addAll(toOutput(t, absorbedByOrder));
            }
        }
        return out;
    }

    private List<Computed> toOutput(Treatment t, Map<String, BigDecimal> absorbedByOrder) {
        List<Computed> out = new ArrayList<>();
        List<Receiver> orders = new ArrayList<>();
        absorbedByOrder.forEach((id, amount) -> {
            if (amount.signum() > 0) {
                orders.add(new Receiver(id, amount));
            }
        });
        if (orders.isEmpty()) {
            out.add(new Computed(t, "cogs", null, null, determination.mapped("cogs"), t.amount(), null));
            return out;
        }
        for (AllocationCalculator.Share os : AllocationCalculator.allocate(t.amount(), orders,
                Rounding.LARGEST_REMAINDER, 2)) {
            List<Layer> layers = outputLayers(os.key());
            if (layers.isEmpty()) {
                out.add(new Computed(t, "cogs", os.key(), null, determination.mapped("cogs"), os.amount(), null));
                continue;
            }
            List<Receiver> byValue = layers.stream().map(l -> new Receiver(l.ledgerId(), l.value().max(
                    BigDecimal.ONE.movePointLeft(6)))).toList();
            Map<String, Layer> byId = new LinkedHashMap<>();
            layers.forEach(l -> byId.put(l.ledgerId(), l));
            for (AllocationCalculator.Share ls : AllocationCalculator.allocate(os.amount(), byValue,
                    Rounding.LARGEST_REMAINDER, 2)) {
                Layer layer = byId.get(ls.key());
                Distribution d = signed(lineage.distribute(layer.ledgerId(), ls.amount().abs()), ls.amount().signum());
                Map<String, BigDecimal> targets = lineage.byTarget(d);
                targets.forEach((target, amount) -> {
                    if (amount.signum() != 0) {
                        out.add(new Computed(t, target, os.key(), layer, account(target, layer.productId()), amount,
                                d));
                    }
                });
            }
        }
        return out;
    }

    private String account(String target, String productId) {
        return switch (target) {
            case "on_hand", "transfer" -> lineage.priceDifferenceAccount();
            case "wip" -> determination.mapped("wip");
            case "scrap" -> determination.mapped("stock_adjustment_expense");
            default -> determination.mapped("cogs");
        };
    }

    private static Distribution signed(Distribution d, int sign) {
        if (sign >= 0) {
            return d;
        }
        return new Distribution(d.negativeStock().negate(), d.consumed().stream().map(s -> new Share(s.ledgerId(),
                s.moveType(), s.referenceType(), s.referenceId(), s.amount().negate())).toList(), d.onHand().negate(),
                d.survival());
    }

    /** Output receipts of a production order (plain or staged), in posting order. */
    private List<Layer> outputLayers(String orderId) {
        List<Layer> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT l.id, l.\"productId\", l.\"variantId\","
                        + " l.\"locationId\", l.\"totalValue\" FROM \"InventoryLedger\" l JOIN \"ProductionOrder\" o ON"
                        + " o.id = ? WHERE l.\"organizationId\" = ? AND l.type::text = 'production_output' AND"
                        + " l.\"quantityChange\" > 0 AND ((l.\"referenceType\" = 'production_order' AND l.\"referenceId\""
                        + " IN (o.id, o.\"orderCode\")) OR (l.\"referenceType\" = 'production_stage' AND"
                        + " l.\"referenceId\" IN (SELECT id FROM \"ProductionStagePosting\" WHERE \"orderId\" = o.id)))"
                        + " ORDER BY l.\"createdAt\", l.\"ledgerCode\"").params(orderId, org()))) {
            out.add(new Layer(orderId, (String) r.get("id"), (String) r.get("productId"), (String) r.get("variantId"),
                    (String) r.get("locationId"), SqlRows.dec(r, "totalValue").abs()));
        }
        return out;
    }

    // ---- settle / reverse ---------------------------------------------------------------------------------------------

    /** Decomposition, blockers and the lines a settlement would post — nothing is posted. */
    @Transactional
    public Map<String, Object> preview(SettleInput in) {
        Map<String, Object> out = analyse(in, new ArrayList<>());
        out.remove("decomposition");
        return out;
    }

    @Transactional
    public Map<String, Object> settle(SettleInput in) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)").param("absorption:" + org() + ":"
                + in.workCenterId()).query().listOfRows();
        List<Computed> computed = new ArrayList<>();
        Map<String, Object> a = analyse(in, computed);
        @SuppressWarnings("unchecked")
        List<String> blockers = (List<String>) a.get("blockers");
        if (!blockers.isEmpty()) {
            throw ApiException.badRequest("Settlement blocked: " + String.join("; ", blockers));
        }
        LocalDate from = LocalDate.parse((String) a.get("from"));
        LocalDate to = LocalDate.parse((String) a.get("to"));
        if (jdbc.sql("SELECT count(*) FROM \"AbsorptionSettlement\" WHERE \"organizationId\" = ? AND \"workCenterId\" ="
                + " ? AND status = 'posted' AND \"periodFrom\" <= ? AND \"periodTo\" >= ?").params(org(),
                in.workCenterId(), Date.valueOf(to), Date.valueOf(from)).query(Long.class).single() > 0) {
            throw ApiException.conflict("Work centre " + a.get("workCenterCode") + " already has a posted settlement"
                    + " overlapping " + from + " – " + to + "; reverse it first");
        }
        Decomposition d = (Decomposition) a.get("decomposition");
        if (computed.isEmpty()) {
            throw ApiException.badRequest("Nothing to settle: absorbed equals actual for the period");
        }
        String id = UUID.randomUUID().toString();
        String number = seq.next("absorption_settlement", "ABS/", 5);
        String pool = determination.mapped("overhead_absorbed");
        String text = "Absorption settlement " + number + " · " + a.get("workCenterCode");
        Map<String, BigDecimal> debits = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Computed c : computed) {
            debits.merge(c.accountId(), c.amount(), BigDecimal::add);
            total = total.add(c.amount());
        }
        List<Line> lines = new ArrayList<>();
        debits.forEach((acc, amount) -> {
            if (amount.signum() != 0) {
                lines.add(signedLine(acc, amount, text));
            }
        });
        lines.add(signedLine(pool, total.negate(), text));
        Map<String, Object> je = posting.post(new PostingRequest("GEN", to.atStartOfDay(ZoneOffset.UTC).toInstant(),
                text, null, null, SOURCE, id, null, "costing:absorption:" + org() + ":" + id, null, null, null, lines));
        String entryId = je == null ? null : (String) je.get("id");
        jdbc.sql("INSERT INTO \"AbsorptionSettlement\" (id, \"organizationId\", number, \"workCenterId\", \"capacityId\","
                        + " \"periodFrom\", \"periodTo\", actual, absorbed, \"budgetFixed\", \"normalActivity\","
                        + " \"actualActivity\", \"fixedRate\", spending, volume, \"rateError\", classification,"
                        + " justification, \"journalEntryId\", \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                        + " ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), number, in.workCenterId(), a.get("capacityId"), Date.valueOf(from), Date.valueOf(to),
                        new BigDecimal((String) a.get("actual")), new BigDecimal((String) a.get("absorbed")),
                        new BigDecimal((String) a.get("budgetFixed")), new BigDecimal((String) a.get("normalActivity")),
                        new BigDecimal((String) a.get("actualActivity")), d.rateUsed(), d.spending(), d.volume(),
                        d.rateError(), blankToNull(in.classification()), blankToNull(in.justification()), entryId,
                        TenantContext.userId())
                .update();
        Map<Distribution, String> recorded = new java.util.IdentityHashMap<>();
        for (Computed c : computed) {
            String adjustmentId = c.distribution() == null ? null : recorded.get(c.distribution());
            if (c.layer() != null && c.distribution() != null && adjustmentId == null) {
                Layer l = c.layer();
                BigDecimal amount = c.distribution().total();
                BigDecimal adjunct = c.distribution().onHand().add(transferred(c.distribution()));
                lineage.recordValueOnly(l.productId(), l.variantId(), l.locationId(), null, adjunct, SOURCE, id,
                        "price_difference", "Overhead settlement " + number);
                adjustmentId = lineage.recordAdjustment(new AdjustmentInput("overhead_settlement", SOURCE, id,
                        l.productId(), l.variantId(), l.locationId(), null, l.ledgerId(), amount, "price_difference",
                        entryId, c.distribution()));
                recorded.put(c.distribution(), adjustmentId);
            }
            jdbc.sql("INSERT INTO \"AbsorptionSettlementLine\" (id, \"organizationId\", \"settlementId\", component,"
                            + " cause, treatment, target, \"orderId\", \"productId\", \"layerLedgerId\", \"adjustmentId\","
                            + " \"accountId\", amount) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org(), id, c.t().component(), c.t().cause(),
                            c.t().treatment(), c.target(), c.orderId(), c.layer() == null ? null : c.layer().productId(),
                            c.layer() == null ? null : c.layer().ledgerId(), adjustmentId, c.accountId(), c.amount())
                    .update();
        }
        audit.recordInTx(AuditInput.of("AbsorptionSettlement", id, "post", null, Obj.of("number", number,
                "workCenterId", in.workCenterId(), "total", d.total(), "journalEntryId", entryId)));
        return get(id);
    }

    /** Transferred shares stay in inventory: they ride on the adjunct of the source pool (ADR-S031). */
    private BigDecimal transferred(Distribution d) {
        return d.consumed().stream().filter(s -> "transfer_out".equals(s.moveType())).map(Share::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Reverse a posted settlement while none of its inventory share has been released. */
    @Transactional
    public Map<String, Object> reverse(String id) {
        Map<String, Object> s = SqlRows.one(jdbc.sql("SELECT * FROM \"AbsorptionSettlement\" WHERE id = ? AND"
                + " \"organizationId\" = ? FOR UPDATE").params(id, org()));
        if (s == null) {
            throw ApiException.notFound("Absorption settlement not found");
        }
        if (!"posted".equals(s.get("status"))) {
            throw ApiException.badRequest("Only a posted settlement can be reversed");
        }
        List<Map<String, Object>> adjustments = SqlRows.list(jdbc.sql("SELECT * FROM \"CostValueAdjustment\" WHERE"
                + " \"organizationId\" = ? AND \"sourceType\" = ? AND \"sourceId\" = ? AND \"reversedAt\" IS NULL")
                .params(org(), SOURCE, id));
        for (Map<String, Object> adj : adjustments) {
            if (SqlRows.dec(adj, "openAmount").compareTo(SqlRows.dec(adj, "onHandAmount")) != 0) {
                throw ApiException.badRequest("Part of this settlement was already released to cost of sales; post a"
                        + " correcting settlement instead");
            }
        }
        String reversal = null;
        if (s.get("journalEntryId") != null) {
            Map<String, Object> rev = posting.reverse((String) s.get("journalEntryId"), Instant.now(),
                    "Reversal of " + s.get("number"));
            reversal = (String) rev.get("id");
        }
        for (Map<String, Object> adj : adjustments) {
            lineage.recordValueOnly((String) adj.get("productId"), (String) adj.get("variantId"),
                    (String) adj.get("locationId"), null, SqlRows.dec(adj, "onHandAmount").negate(), "reversal",
                    (String) s.get("journalEntryId"), "price_difference", "Reversal of " + s.get("number"));
        }
        lineage.closeAdjustments(SOURCE, id);
        jdbc.sql("UPDATE \"AbsorptionSettlement\" SET status = 'reversed', \"reversalEntryId\" = ?, \"reversedAt\" ="
                + " now() WHERE id = ?").params(reversal, id).update();
        audit.recordInTx(AuditInput.of("AbsorptionSettlement", id, "cancel", null, Obj.of("reversalEntryId",
                reversal)));
        return get(id);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String workCenterId) {
        boolean all = workCenterId == null || workCenterId.isBlank();
        return SqlRows.list(jdbc.sql("SELECT s.*, w.code AS \"workCenterCode\" FROM \"AbsorptionSettlement\" s JOIN"
                + " \"WorkCenter\" w ON w.id = s.\"workCenterId\" WHERE s.\"organizationId\" = ?"
                + (all ? "" : " AND s.\"workCenterId\" = ?") + " ORDER BY s.\"createdAt\" DESC")
                .params(all ? List.of(org()) : List.of(org(), workCenterId)));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> s = SqlRows.one(jdbc.sql("SELECT s.*, w.code AS \"workCenterCode\" FROM"
                + " \"AbsorptionSettlement\" s JOIN \"WorkCenter\" w ON w.id = s.\"workCenterId\" WHERE s.id = ? AND"
                + " s.\"organizationId\" = ?").params(id, org()));
        if (s == null) {
            throw ApiException.notFound("Absorption settlement not found");
        }
        s.put("lines", SqlRows.list(jdbc.sql("SELECT l.*, a.code AS \"accountCode\" FROM \"AbsorptionSettlementLine\" l"
                + " LEFT JOIN \"Account\" a ON a.id = l.\"accountId\" WHERE l.\"settlementId\" = ? ORDER BY"
                + " l.component, l.target, a.code").param(id)));
        return s;
    }

    // ---- cost-flow bridge (production part, C0) -------------------------------------------------------------------------

    /**
     * Per work centre with a capacity: L3 actual by eligibility = capitalised (absorbed) + settled by treatment +
     * expensed directly + unallocated (what is neither absorbed nor settled yet).
     */
    @Transactional
    public Map<String, Object> bridge(String fromText, String toText) {
        LocalDate from = day(fromText, "from");
        LocalDate to = day(toText, "to");
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal unallocatedTotal = BigDecimal.ZERO;
        for (Map<String, Object> w : SqlRows.list(jdbc.sql("SELECT DISTINCT w.id, w.code FROM \"WorkCenterCapacity\" c"
                + " JOIN \"WorkCenter\" w ON w.id = c.\"workCenterId\" WHERE c.\"organizationId\" = ? AND c.status ="
                + " 'approved' ORDER BY w.code").param(org()))) {
            Map<String, Object> a = analyse(new SettleInput((String) w.get("id"), from.toString(), to.toString(),
                    "abnormal", null), new ArrayList<>());
            Map<String, BigDecimal> settled = new LinkedHashMap<>();
            for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT l.treatment, sum(l.amount) AS amount FROM"
                    + " \"AbsorptionSettlementLine\" l JOIN \"AbsorptionSettlement\" s ON s.id = l.\"settlementId\" WHERE"
                    + " s.\"organizationId\" = ? AND s.\"workCenterId\" = ? AND s.status = 'posted' AND s.\"periodFrom\""
                    + " >= ? AND s.\"periodTo\" <= ? GROUP BY l.treatment").params(org(), w.get("id"),
                    Date.valueOf(from), Date.valueOf(to)))) {
                settled.put((String) l.get("treatment"), SqlRows.dec(l, "amount"));
            }
            BigDecimal actual = a.get("actual") == null ? BigDecimal.ZERO : new BigDecimal((String) a.get("actual"));
            BigDecimal absorbed = a.get("absorbed") == null ? BigDecimal.ZERO
                    : new BigDecimal((String) a.get("absorbed"));
            BigDecimal settledTotal = settled.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal unallocated = actual.subtract(absorbed).subtract(settledTotal);
            unallocatedTotal = unallocatedTotal.add(unallocated);
            Map<String, Object> settledOut = new LinkedHashMap<>();
            settled.forEach((k, v) -> settledOut.put(k, JsNumbers.dec(v)));
            rows.add(Obj.of("workCenterId", w.get("id"), "workCenterCode", w.get("code"), "actualEligible",
                    JsNumbers.dec(actual), "expensedDirectly", a.getOrDefault("expensedDirectly", "0"),
                    "capitalisedByAbsorption", JsNumbers.dec(absorbed), "settled", settledOut, "unallocated",
                    JsNumbers.dec(unallocated)));
        }
        return Obj.of("from", from.toString(), "to", to.toString(), "rows", rows, "unallocated",
                JsNumbers.dec(unallocatedTotal));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private Map<String, Object> requireRow(String table, String id, String message) {
        Map<String, Object> r = id == null ? null : SqlRows.one(jdbc.sql("SELECT * FROM \"" + table + "\" WHERE id = ?"
                + " AND \"organizationId\" = ?").params(id, org()));
        if (r == null) {
            throw ApiException.notFound(message);
        }
        return r;
    }

    private static Line signedLine(String account, BigDecimal amount, String text) {
        String v = JsNumbers.dec(amount.abs());
        return amount.signum() >= 0 ? Line.debit(account, v, text) : Line.credit(account, v, text);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    private static LocalDate day(String v, String field) {
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
