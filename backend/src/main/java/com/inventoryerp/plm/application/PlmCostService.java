package com.inventoryerp.plm.application;

import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.inventory.application.StandardCostService;
import com.inventoryerp.kernel.approvals.SegregationOfDutiesService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.manufacturing.application.CostingService;
import com.inventoryerp.manufacturing.domain.EngineeringChange;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PLM ↔ accounting (ADR-S086 §5). Applying a BOM ECO can carry the new version's cost into the books: {@code propose}
 * freezes the new version's roll-up as a draft product standard (approved and activated later in Standard costs);
 * {@code activate} also approves and activates it right away — the stock on hand is revalued to the new standard and
 * the difference is posted against Standard Cost Revaluation (5960). Activation needs a standard-costed product and
 * stays a proposal while separation of duties on inventory valuation is enforced.
 *
 * <p><b>Cost fallback (ADR-S086 §5, owner decision 2026-11-25).</b> Engineering is never blocked by an accounting
 * control, and an accounting control is never bypassed. The apply transaction only records the cost step as
 * {@code pending}; the step runs once the apply has committed, in transactions of its own, so a costing failure can
 * neither undo the applied change nor leave half a revaluation behind (a JDBC savepoint cannot give this: a failing
 * {@code @Transactional} service marks the whole apply transaction rollback-only). Stage 1 validates the new
 * version's roll-up and proposes the draft standard; stage 2 approves and activates it. A failure rolls back its own
 * stage only and is recorded on the ECO ({@code costResult.status} {@code failed} / {@code incomplete}, the reason in
 * {@code note}, an activity entry and an audit row). A roll-up missing a component cost is never proposed, and zero is
 * never substituted for a missing cost, so a revaluation only ever posts a validated standard. A step left
 * {@code pending} (the process stopped between the commits) is visible on the ECO and run again by
 * {@link PlmEffectivityJob}.
 */
@Service
public class PlmCostService {

    private static final Logger log = LoggerFactory.getLogger(PlmCostService.class);

    /** The status a cost step has while it waits for the apply transaction to commit. */
    static final String PENDING = "pending";

    private final PlmDb db;
    private final PlmSupport plm;
    private final CostingService costing;
    private final StandardCostService standards;
    private final SegregationOfDutiesService sod;
    private final AuditService audit;
    private final TransactionTemplate isolated;
    private final TransactionTemplate own;

    public PlmCostService(PlmDb db, PlmSupport plm, CostingService costing, StandardCostService standards,
            SegregationOfDutiesService sod, AuditService audit, PlatformTransactionManager txm) {
        this.db = db;
        this.plm = plm;
        this.costing = costing;
        this.standards = standards;
        this.sod = sod;
        this.audit = audit;
        this.isolated = new TransactionTemplate(txm);
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.isolated.setReadOnly(true);
        this.own = new TransactionTemplate(txm);
        this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Refuse a cost policy that cannot work: BOM changes only; activate needs a standard-costed product. */
    void validate(String policy, String type, String productId) {
        if (policy == null || "none".equals(policy)) {
            return;
        }
        if (!"bom".equals(type)) {
            throw ApiException.badRequest("costPolicy only applies to a BOM change.");
        }
        Map<String, Object> p = product(productId);
        if (Boolean.TRUE.equals(p.get("serialTracking"))) {
            throw ApiException.badRequest("Product " + p.get("code") + " is serial-tracked; standard cost does not"
                    + " apply.");
        }
        if ("activate".equals(policy) && !"STANDARD".equals(p.get("method"))) {
            throw ApiException.badRequest("Product " + p.get("code") + " is costed at " + p.get("method") + "; only a"
                    + " product on standard cost can have its standard activated by a change (use propose).");
        }
    }

    /** A default cost policy as far as the product allows it: activate → propose off standard cost; none if serial. */
    String fit(String policy, String productId) {
        if (policy == null || "none".equals(policy) || productId == null) {
            return "none";
        }
        Map<String, Object> p = product(productId);
        if (Boolean.TRUE.equals(p.get("serialTracking"))) {
            return "none";
        }
        return "activate".equals(policy) && !"STANDARD".equals(p.get("method")) ? "propose" : policy;
    }

    /** Whether applying {@code eco} has a cost step: a BOM change with a cost policy other than {@code none}. */
    static boolean applies(Map<String, Object> eco) {
        String policy = str(eco, "costPolicy");
        return policy != null && !"none".equals(policy) && "bom".equals(eco.get("type")) && eco.get("newBomId") != null;
    }

    /**
     * What the apply transaction records for the cost step: {@code pending} until {@link #afterApply} has run it.
     * Null when the change has no cost step.
     */
    Map<String, Object> pending(Map<String, Object> eco) {
        if (!applies(eco)) {
            return null;
        }
        return Obj.of("policy", str(eco, "costPolicy"), "status", PENDING, "note",
                "The cost step runs once the change is applied.");
    }

    /**
     * Run the cost step of {@code changeId} once the current (apply) transaction has committed — at once when there is
     * none. {@code response}, the row the apply returns, receives the outcome (masked like every ECO read), so the
     * caller sees the final cost result: the step runs inside the transactional proxy's commit, before it returns.
     * Never throws: a failure is recorded on the ECO.
     */
    void afterApply(String changeId, Map<String, Object> response) {
        Runnable step = () -> {
            Map<String, Object> outcome = complete(changeId);
            if (response != null && outcome != null) {
                response.put("costResult", visibleResult(outcome));
                response.put("standardCostId", outcome.get("standardCostId"));
                response.put("revaluationJournalId", outcome.get("journalEntryId"));
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    step.run();
                }
            });
        } else {
            step.run();
        }
    }

    /**
     * The cost step of an applied BOM ECO whose cost result is still {@code pending}: stage 1 (validated roll-up →
     * draft standard) and, for {@code activate} with nothing in the way, stage 2 (approve + activate, revaluing the
     * stock on hand), each in its own transaction. Returns the recorded outcome (null when nothing was pending).
     */
    Map<String, Object> complete(String changeId) {
        Map<String, Object> staged;
        try {
            staged = own.execute(st -> {
                Map<String, Object> eco = plm.lockChange(changeId);
                if (eco == null || !PENDING.equals(PlmSupport.object(eco.get("costResult")).get("status"))) {
                    return null;
                }
                Map<String, Object> r = propose(eco);
                record(changeId, r);
                plm.activity(changeId, "cost", summary(r));
                if ("activate".equals(r.get("policy")) && "draft".equals(r.get("status")) && r.get("note") == null) {
                    r.put("activate", true); // stage 2 below, outside this transaction
                }
                return r;
            });
        } catch (RuntimeException e) {
            return fail(changeId, null, e);
        }
        if (staged == null || !Boolean.TRUE.equals(staged.remove("activate"))) {
            return staged;
        }
        try {
            return own.execute(st -> {
                plm.lockChange(changeId);
                String id = str(staged, "standardCostId");
                standards.approve(id);
                Map<String, Object> active = standards.activate(id);
                Map<String, Object> r = new LinkedHashMap<>(staged);
                r.put("status", active.get("status"));
                r.put("revaluationQuantity", active.get("revaluationQuantity"));
                r.put("revaluationAmount", active.get("revaluationAmount"));
                r.put("journalEntryId", active.get("revaluationJournalEntryId"));
                record(changeId, r);
                plm.activity(changeId, "cost", summary(r));
                return r;
            });
        } catch (RuntimeException e) {
            return fail(changeId, staged, e);
        }
    }

    /**
     * Stage 1: the new version's roll-up, validated, becomes a draft standard. A serial-tracked product is skipped; a
     * roll-up missing a component cost (or totalling nothing) proposes nothing ({@code incomplete}): a missing cost is
     * never read as zero.
     */
    private Map<String, Object> propose(Map<String, Object> eco) {
        String policy = str(eco, "costPolicy");
        Map<String, Object> p = product(str(eco, "productId"));
        if (Boolean.TRUE.equals(p.get("serialTracking"))) {
            return Obj.of("policy", policy, "status", "skipped", "note", "Product " + p.get("code")
                    + " is serial-tracked; standard cost does not apply.");
        }
        String gap = rollupGap(str(eco, "newBomId"));
        if (gap != null) {
            return Obj.of("policy", policy, "status", "incomplete", "note", gap + " No standard was proposed; the"
                    + " product keeps its current cost until the roll-up is complete.", "checkedAt", Instant.now());
        }
        BigDecimal before = currentUnitCost(str(p, "id"), p);
        Map<String, Object> std = costing.proposeProductStandard(str(eco, "newBomId"), plm.orgToday().toString(), false);
        if (SqlRows.dec(std, "totalCost").signum() <= 0) {
            // Belt and braces: the roll-up was complete, yet the derived unit standard is not positive.
            throw ApiException.badRequest("The roll-up of the new version gives a unit standard of "
                    + std.get("totalCost") + "; a standard must be positive.");
        }
        Map<String, Object> result = Obj.of("policy", policy, "standardCostId", str(std, "id"), "version",
                std.get("version"), "unitCost", std.get("totalCost"), "previousUnitCost", before, "status",
                std.get("status"));
        if ("activate".equals(policy)) {
            String blocker = activationBlocker(p, std.get("version"));
            if (blocker != null) {
                result.put("note", blocker);
            }
        }
        return result;
    }

    /**
     * Why the roll-up of {@code bomId} cannot be trusted as a standard, or null: a material line without a positive
     * unit cost (no stock average, no cost price), or a total that is not positive.
     */
    String rollupGap(String bomId) {
        Map<String, Object> rollup = isolated.execute(s -> costing.rollup(bomId));
        List<String> missing = new ArrayList<>();
        Object lines = rollup == null ? null : rollup.get("breakdown");
        if (lines instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m && "material".equals(m.get("kind"))) {
                    Object unit = m.get("unitCost");
                    if (unit == null || new BigDecimal(unit.toString()).signum() <= 0) {
                        missing.add(String.valueOf(m.get("name") != null ? m.get("name") : m.get("productId")));
                    }
                }
            }
        }
        String label = "The roll-up of " + (rollup == null ? "the new version" : rollup.get("bomCode") + " v"
                + rollup.get("version"));
        if (!missing.isEmpty()) {
            return label + " is incomplete: no cost for " + String.join(", ", missing) + ".";
        }
        Object total = rollup == null ? null : rollup.get("totalCost");
        if (total == null || new BigDecimal(total.toString()).signum() <= 0) {
            return label + " totals nothing.";
        }
        return null;
    }

    /**
     * Record a failed stage in a transaction of its own (the failed stage rolled back). Stage 1 ({@code staged} null):
     * no standard exists — {@code failed}. Stage 2: the proposal from stage 1 stays as it is, with why it was not
     * activated. The reason is the business message, or a generic one for an unexpected error (logged in full).
     */
    private Map<String, Object> fail(String changeId, Map<String, Object> staged, RuntimeException e) {
        String reason = e instanceof ApiException && e.getMessage() != null ? e.getMessage()
                : "unexpected error (see the server log).";
        log.warn("PLM cost step of change {} failed ({}): {}", changeId, staged == null ? "proposal" : "activation",
                e.toString(), e);
        Map<String, Object> r;
        if (staged == null) {
            r = new LinkedHashMap<>();
            r.put("status", "failed");
            r.put("note", "Cost step failed: " + reason + " No standard was proposed; the product keeps its current"
                    + " cost.");
        } else {
            r = new LinkedHashMap<>(staged);
            r.put("note", "Activation refused: " + reason + " Standard cost v" + staged.get("version") + " stays "
                    + staged.get("status") + " for Standard costs.");
        }
        r.put("failedAt", Instant.now());
        try {
            return own.execute(st -> {
                Map<String, Object> eco = plm.lockChange(changeId);
                if (eco == null) {
                    return null;
                }
                r.putIfAbsent("policy", eco.get("costPolicy"));
                record(changeId, r);
                plm.activity(changeId, "cost", summary(r));
                audit.recordInTx(AuditInput.of("EngineeringChange", changeId, "update", null, Obj.of("kind",
                        "plm_cost_step_failed", "stage", staged == null ? "proposal" : "activation", "reason", reason)));
                return r;
            });
        } catch (RuntimeException again) {
            // The ECO keeps "pending", which the effectivity job runs again: the failure is never silently lost.
            log.error("Could not record the failed PLM cost step of change {}: {}", changeId, again.toString());
            return null;
        }
    }

    /** Write a cost outcome onto the ECO (status, reason and figures; the figures are masked on read). */
    private void record(String changeId, Map<String, Object> r) {
        db.update(EngineeringChange.class, changeId, Obj.of("costResult", new PlmDb.Json(r), "standardCostId",
                r.get("standardCostId"), "revaluationJournalId", r.get("journalEntryId")));
    }

    /** Applied changes whose cost step is still pending (the process stopped after the apply committed). */
    List<String> stalePending() {
        return db.jdbc().sql("SELECT id FROM \"EngineeringChange\" WHERE \"organizationId\" = ? AND status = 'applied'"
                + " AND \"costResult\" ->> 'status' = ? AND \"appliedAt\" < now() - interval '10 minutes'"
                + " ORDER BY \"appliedAt\"").params(PlmDb.org(), PENDING).query(String.class).list();
    }

    /**
     * One line for the ECO's activity log. No figures: every {@code plm:read} holder reads the log, and costs are for
     * users who may read inventory valuation (ADR-S086 §5) — they find the figures in the change's cost result.
     */
    static String summary(Map<String, Object> r) {
        if (r == null) {
            return "";
        }
        if (r.get("standardCostId") == null || "failed".equals(r.get("status"))) {
            return String.valueOf(r.get("note"));
        }
        String head = "Standard cost v" + r.get("version") + " ";
        if ("active".equals(r.get("status"))) {
            Object amount = r.get("revaluationAmount");
            return head + "activated" + (amount == null || SqlRows.dec(r, "revaluationAmount").signum() == 0
                    ? "." : "; the stock on hand was revalued.");
        }
        return head + "proposed" + (r.get("note") == null ? "." : ": " + r.get("note"));
    }

    /**
     * Financial impact of a BOM ECO: the product's unit cost now and the new version's roll-up, the stock on hand it
     * applies to, the revaluation activating it would post, and what each cost policy does; after apply, its outcome.
     */
    Map<String, Object> financial(Map<String, Object> eco) {
        String type = str(eco, "type");
        String productId = str(eco, "productId");
        Map<String, Object> out = Obj.of("changeId", eco.get("id"), "number", eco.get("number"), "type", type,
                "costPolicy", eco.get("costPolicy"), "status", eco.get("status"));
        if (!"bom".equals(type) || productId == null || eco.get("newBomId") == null) {
            out.put("applicable", false);
            return out;
        }
        out.put("applicable", true);
        Map<String, Object> p = product(productId);
        Optional<BigDecimal> standard = standards.activeStandard(productId);
        BigDecimal current = currentUnitCost(productId, p);
        out.put("product", Obj.of("id", productId, "code", p.get("code"), "name", p.get("name"), "costingMethod",
                p.get("method")));
        out.put("currentUnitCost", current);
        out.put("currentSource", standard.isPresent() ? "standard" : "cost_price");

        BigDecimal rolledOld = null;
        BigDecimal rolledNew = null;
        String error = null;
        try {
            if (eco.get("bomId") != null) {
                rolledOld = SqlRows.dec(isolated.execute(s -> costing.rollup(str(eco, "bomId"))), "unitCost");
            }
            rolledNew = SqlRows.dec(isolated.execute(s -> costing.rollup(str(eco, "newBomId"))), "unitCost");
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        BigDecimal delta = rolledNew == null || current == null ? null : rolledNew.subtract(current);
        out.put("rollup", Obj.of("currentVersion", rolledOld, "newVersion", rolledNew, "deltaToCurrentCost", delta,
                "deltaPct", delta == null || current == null || current.signum() == 0 ? null
                        : delta.multiply(BigDecimal.valueOf(100)).divide(current, 2, RoundingMode.HALF_UP),
                "error", error));

        BigDecimal qty = db.jdbc().sql("SELECT coalesce(sum(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ?"
                + " AND \"productId\" = ?").params(PlmDb.org(), productId).query(BigDecimal.class).single();
        out.put("onHand", Obj.of("quantity", qty, "value", current == null ? null
                : current.multiply(qty).setScale(2, RoundingMode.HALF_UP)));
        BigDecimal reval = delta == null ? null : delta.multiply(qty).setScale(2, RoundingMode.HALF_UP);
        Map<String, Object> account = SqlRows.one(db.jdbc().sql("SELECT id, code, name FROM \"Account\" WHERE"
                + " \"organizationId\" = ? AND code = '5960' LIMIT 1").param(PlmDb.org()));
        out.put("revaluation", Obj.of("amount", reval, "direction", reval == null || reval.signum() == 0 ? null
                : reval.signum() > 0 ? "increase" : "decrease", "account", account != null ? account
                        : Obj.of("code", "5960", "name", "Standard Cost Revaluation")));
        String blocker = Boolean.TRUE.equals(p.get("serialTracking"))
                ? "Product " + p.get("code") + " is serial-tracked; standard cost does not apply."
                : !"STANDARD".equals(p.get("method"))
                        ? "Product " + p.get("code") + " is costed at " + p.get("method") + "; only a product on"
                                + " standard cost can have its standard activated by a change."
                        : activationBlocker(p, null);
        out.put("canActivate", blocker == null);
        out.put("activateBlocker", blocker);
        out.put("result", PlmSupport.plain(eco.get("costResult")));
        out.put("standardCostId", eco.get("standardCostId"));
        out.put("revaluationJournalId", eco.get("revaluationJournalId"));
        out.put("costVisible", PlmReportService.seesCost());
        if (!PlmReportService.seesCost()) {
            // The inventory rule: costs are for users who may read inventory valuation.
            out.put("currentUnitCost", null);
            out.put("rollup", Obj.of("currentVersion", null, "newVersion", null, "deltaToCurrentCost", null,
                    "deltaPct", null, "error", error));
            out.put("onHand", Obj.of("quantity", qty, "value", null));
            @SuppressWarnings("unchecked")
            Map<String, Object> rev = (Map<String, Object>) out.get("revaluation");
            rev.put("amount", null);
            out.put("result", visibleResult(out.get("result")));
        }
        return out;
    }

    /**
     * A change's cost outcome as the caller may see it: everything with {@code inventory_valuation:read}, else only
     * the policy, status, standard version and note (ADR-S086 §5).
     */
    static Object visibleResult(Object result) {
        Object r = PlmSupport.plain(result);
        if (PlmReportService.seesCost() || !(r instanceof Map<?, ?> m)) {
            return r;
        }
        return Obj.of("policy", m.get("policy"), "status", m.get("status"), "version", m.get("version"), "note",
                m.get("note"));
    }

    private String activationBlocker(Map<String, Object> p, Object version) {
        if (!"STANDARD".equals(p.get("method"))) {
            return "Product " + p.get("code") + " is costed at " + p.get("method") + "; the standard waits for"
                    + " activation in Standard costs.";
        }
        if (sod.enforced(SegregationOfDutiesService.INVENTORY_VALUATION)) {
            return "Separation of duties on inventory valuation: another user approves and activates standard cost"
                    + (version == null ? "" : " v" + version) + ".";
        }
        return null;
    }

    private BigDecimal currentUnitCost(String productId, Map<String, Object> p) {
        return standards.activeStandard(productId).orElse((BigDecimal) p.get("costPrice"));
    }

    private Map<String, Object> product(String productId) {
        Map<String, Object> p = SqlRows.one(db.jdbc().sql("SELECT id, code, name, \"costingMethod\"::text AS method,"
                + " \"costPrice\", \"serialTracking\" FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?")
                .params(productId, PlmDb.org()));
        if (p == null) {
            throw ApiException.notFound("Product not found");
        }
        return p;
    }
}
