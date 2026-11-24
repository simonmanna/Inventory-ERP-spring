package com.inventoryerp.plm.application;

import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.inventory.application.StandardCostService;
import com.inventoryerp.kernel.approvals.SegregationOfDutiesService;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.manufacturing.application.CostingService;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PLM ↔ accounting (ADR-S086 §5). Applying a BOM ECO can carry the new version's cost into the books: {@code propose}
 * freezes the new version's roll-up as a draft product standard (approved and activated later in Standard costs);
 * {@code activate} also approves and activates it right away — the stock on hand is revalued to the new standard and
 * the difference is posted against Standard Cost Revaluation (5960), in the apply's own transaction. Activation needs
 * a standard-costed product and stays a proposal while separation of duties on inventory valuation is enforced.
 */
@Service
public class PlmCostService {

    private final PlmDb db;
    private final PlmSupport plm;
    private final CostingService costing;
    private final StandardCostService standards;
    private final SegregationOfDutiesService sod;
    private final TransactionTemplate isolated;

    public PlmCostService(PlmDb db, PlmSupport plm, CostingService costing, StandardCostService standards,
            SegregationOfDutiesService sod, PlatformTransactionManager txm) {
        this.db = db;
        this.plm = plm;
        this.costing = costing;
        this.standards = standards;
        this.sod = sod;
        this.isolated = new TransactionTemplate(txm);
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.isolated.setReadOnly(true);
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

    /**
     * The cost step of applying a BOM ECO (inside the apply transaction): the new version's roll-up becomes a draft
     * standard, approved and activated for {@code activate} when nothing stands in the way. Null for {@code none}.
     */
    Map<String, Object> apply(Map<String, Object> eco) {
        String policy = str(eco, "costPolicy");
        if (policy == null || "none".equals(policy) || !"bom".equals(eco.get("type")) || eco.get("newBomId") == null) {
            return null;
        }
        Map<String, Object> p = product(str(eco, "productId"));
        if (Boolean.TRUE.equals(p.get("serialTracking"))) {
            return Obj.of("policy", policy, "status", "skipped", "note", "Product " + p.get("code")
                    + " is serial-tracked; standard cost does not apply.");
        }
        BigDecimal before = currentUnitCost(str(p, "id"), p);
        Map<String, Object> std = costing.proposeProductStandard(str(eco, "newBomId"), plm.orgToday().toString(), false);
        String id = str(std, "id");
        Map<String, Object> result = Obj.of("policy", policy, "standardCostId", id, "version", std.get("version"),
                "unitCost", std.get("totalCost"), "previousUnitCost", before, "status", std.get("status"));
        if ("activate".equals(policy)) {
            String blocker = activationBlocker(p, std.get("version"));
            if (blocker == null) {
                standards.approve(id);
                Map<String, Object> active = standards.activate(id);
                result.put("status", active.get("status"));
                result.put("revaluationQuantity", active.get("revaluationQuantity"));
                result.put("revaluationAmount", active.get("revaluationAmount"));
                result.put("journalEntryId", active.get("revaluationJournalEntryId"));
            } else {
                result.put("note", blocker);
            }
        }
        return result;
    }

    /** One line for the ECO's activity log. */
    static String summary(Map<String, Object> r) {
        if (r == null) {
            return "";
        }
        if (r.get("standardCostId") == null) {
            return String.valueOf(r.get("note"));
        }
        String head = "Standard cost v" + r.get("version") + " ("
                + com.inventoryerp.kernel.common.JsNumbers.dec(SqlRows.dec(r, "unitCost")) + " per unit) ";
        if ("active".equals(r.get("status"))) {
            Object amount = r.get("revaluationAmount");
            return head + "activated" + (amount == null || SqlRows.dec(r, "revaluationAmount").signum() == 0
                    ? "." : "; stock revalued by " + com.inventoryerp.kernel.common.JsNumbers.dec(
                            SqlRows.dec(r, "revaluationAmount")) + ".");
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
            if (out.get("result") instanceof Map<?, ?> r) {
                out.put("result", Obj.of("policy", r.get("policy"), "status", r.get("status"), "version",
                        r.get("version"), "note", r.get("note")));
            }
        }
        return out;
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
