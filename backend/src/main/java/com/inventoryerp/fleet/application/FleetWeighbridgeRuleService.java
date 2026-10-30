package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.application.WeighbridgeCommands.DispatchRequirementInput;
import com.inventoryerp.fleet.domain.FleetLoad;
import com.inventoryerp.fleet.domain.FleetWeighbridgeRequirement;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Weighbridge required for dispatch" (fleet 1.4b): the rule of the delivery order's branch, else the organization's
 * rule, else off. Modes: {@code all} products, {@code bulk} products (the line unit — or the product's base unit — is a
 * weight / volume / bulk unit, or the product has weighbridge product settings), or listed product {@code categories}
 * including their sub-categories. When a trip is covered, dispatch needs a completed weighbridge ticket that fed the
 * trip and a hand-entered loaded quantity is refused. Independently of the rule, a trip with a live ticket (open,
 * awaiting the second weighing, or out of tolerance waiting for approval) cannot be dispatched.
 *
 * <p>Depends only on {@link FleetDb} so that the dispatch and delivery-order services can use it without a cycle.
 */
@Service
public class FleetWeighbridgeRuleService {

    static final String AUDIT = "FleetWeighbridgeRequirement";
    static final String SOURCE = "weighbridge_ticket";

    /** Whether the weighbridge is required for a trip, and why (the product and the rule). */
    public record Requirement(boolean required, String reason, String ruleId, String mode, String branchId) {

        public Map<String, Object> toMap() {
            return Obj.of("required", required, "reason", reason, "ruleId", ruleId, "mode", mode, "branchId",
                    branchId);
        }
    }

    private final FleetDb db;
    private final AuditService audit;

    public FleetWeighbridgeRuleService(FleetDb db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ settings

    /** The organization rule (branch empty) first, then the branch rules, with branch and category names. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT r.*, b.code AS \"branchCode\", b.name AS"
                + " \"branchName\" FROM \"FleetWeighbridgeRequirement\" r LEFT JOIN \"Branch\" b ON b.id = r.\"branchId\""
                + " AND b.\"organizationId\" = r.\"organizationId\" WHERE r.\"organizationId\" = ? ORDER BY"
                + " r.\"branchId\" NULLS FIRST, b.name, r.id").params(FleetDb.org()))) {
            out.add(shape(r));
        }
        return out;
    }

    /** The settings screen: {@code {rules, branches, categories}}. */
    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        return Obj.of("rules", list(),
                "branches", SqlRows.list(db.jdbc().sql("SELECT id, code, name FROM \"Branch\" WHERE \"organizationId\" ="
                        + " ? AND \"deletedAt\" IS NULL ORDER BY name").params(FleetDb.org())),
                "categories", SqlRows.list(db.jdbc().sql("SELECT id, name, \"parentId\" FROM \"ProductCategory\" WHERE"
                        + " \"organizationId\" = ? AND \"deletedAt\" IS NULL ORDER BY name").params(FleetDb.org())));
    }

    private Map<String, Object> shape(Map<String, Object> r) {
        Map<String, Object> row = SqlRows.as(FleetWeighbridgeRequirement.class, r);
        row.put("branchCode", r.get("branchCode"));
        row.put("branchName", r.get("branchName"));
        @SuppressWarnings("unchecked")
        List<String> ids = row.get("categoryIds") instanceof List<?> l ? (List<String>) l : List.of();
        row.put("categories", ids.isEmpty() ? List.of() : SqlRows.list(db.jdbc().sql("SELECT id, name FROM"
                + " \"ProductCategory\" WHERE \"organizationId\" = ? AND id = ANY(?::text[]) ORDER BY name")
                .params(FleetDb.org(), ids.toArray(String[]::new))));
        return row;
    }

    /** Creates or replaces the rule of the organization ({@code branchId} empty) or of one branch. */
    @Transactional
    public Map<String, Object> put(DispatchRequirementInput in) {
        String branchId = FleetVehicleService.trim(in.branchId());
        if (branchId != null && !db.exists("Branch", branchId, true)) {
            throw ApiException.badRequest("Branch not found");
        }
        List<String> categories = new ArrayList<>();
        if ("categories".equals(in.mode())) {
            Set<String> unique = new LinkedHashSet<>(in.categoryIds() == null ? List.of() : in.categoryIds());
            if (unique.isEmpty()) {
                throw ApiException.badRequest("Choose at least one product category");
            }
            for (String c : unique) {
                if (!db.exists("ProductCategory", c, true)) {
                    throw ApiException.badRequest("Product category not found");
                }
                categories.add(c);
            }
        }
        Map<String, Object> before = SqlRows.one(FleetWeighbridgeRequirement.class, db.jdbc().sql("SELECT * FROM"
                + " \"FleetWeighbridgeRequirement\" WHERE \"organizationId\" = ? AND \"branchId\" IS NOT DISTINCT FROM ?"
                + " FOR UPDATE").params(FleetDb.org(), branchId));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", in.mode());
        data.put("categoryIds", categories.toArray(String[]::new));
        data.put("notes", FleetVehicleService.trim(in.notes()));
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> after;
        if (before == null) {
            data.put("branchId", branchId);
            data.put("createdBy", TenantContext.userId());
            after = db.insert(FleetWeighbridgeRequirement.class, data);
            audit.recordInTx(AuditInput.of(AUDIT, (String) after.get("id"), "create", null, after));
        } else {
            after = db.update(FleetWeighbridgeRequirement.class, (String) before.get("id"), data);
            audit.recordInTx(AuditInput.of(AUDIT, (String) after.get("id"), "update", before, after));
        }
        return one((String) after.get("id"));
    }

    @Transactional
    public Map<String, Object> delete(String id) {
        Map<String, Object> before = db.require(FleetWeighbridgeRequirement.class, id, true,
                "Weighbridge requirement not found");
        db.jdbc().sql("DELETE FROM \"FleetWeighbridgeRequirement\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, FleetDb.org()).update();
        audit.recordInTx(AuditInput.of(AUDIT, id, "delete", before, null));
        return Obj.of("deleted", true, "id", id);
    }

    private Map<String, Object> one(String id) {
        return shape(SqlRows.one(db.jdbc().sql("SELECT r.*, b.code AS \"branchCode\", b.name AS \"branchName\" FROM"
                + " \"FleetWeighbridgeRequirement\" r LEFT JOIN \"Branch\" b ON b.id = r.\"branchId\" WHERE r.id = ? AND"
                + " r.\"organizationId\" = ?").params(id, FleetDb.org())));
    }

    // ------------------------------------------------------------------ evaluation

    /** Is the weighbridge required for this trip, and why. */
    public Requirement forTrip(String tripId) {
        Map<String, Object> t = db.require(FleetLoad.class, tripId, false, "Trip not found");
        return forTrip(t);
    }

    Requirement forTrip(Map<String, Object> trip) {
        String branchId = db.jdbc().sql("SELECT \"branchId\" FROM \"FleetDeliveryOrder\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(trip.get("deliveryOrderId"), FleetDb.org()).query(String.class)
                .optional().orElse(null);
        Map<String, Object> rule = null;
        if (branchId != null) {
            rule = rule(branchId);
        }
        if (rule == null) {
            rule = rule(null);
        }
        if (rule == null || "off".equals(rule.get("mode"))) {
            return new Requirement(false, null, rule == null ? null : (String) rule.get("id"), "off", branchId);
        }
        String mode = (String) rule.get("mode");
        String ruleId = (String) rule.get("id");
        String scope = rule.get("branchName") == null ? "" : " at branch " + rule.get("branchName");
        List<Map<String, Object>> lines = SqlRows.list(db.jdbc().sql("SELECT l.\"productId\", p.name AS"
                + " \"productName\", p.\"categoryId\", u.category AS \"unitCategory\", uc.name AS \"uomCategory\","
                + " EXISTS (SELECT 1 FROM \"WeighbridgeProductSetting\" ws WHERE ws.\"organizationId\" ="
                + " l.\"organizationId\" AND ws.\"productId\" = l.\"productId\") AS \"weighSetting\" FROM"
                + " \"FleetLoadLine\" l JOIN \"Product\" p ON p.id = l.\"productId\" LEFT JOIN \"UnitOfMeasure\" u ON"
                + " u.id = COALESCE(l.\"uomId\", p.\"uomId\") LEFT JOIN \"UomCategory\" uc ON uc.id = u.\"categoryId\""
                + " WHERE l.\"organizationId\" = ? AND l.\"loadId\" = ? ORDER BY l.\"createdAt\", l.id")
                .params(FleetDb.org(), trip.get("id")));
        if (lines.isEmpty()) {
            return new Requirement(false, null, ruleId, mode, branchId);
        }
        switch (mode) {
            case "all" -> {
                return new Requirement(true, "the weighbridge is required for all products" + scope, ruleId, mode,
                        branchId);
            }
            case "bulk" -> {
                for (Map<String, Object> l : lines) {
                    if (isBulk(l)) {
                        return new Requirement(true, l.get("productName") + " is a bulk product and must be weighed"
                                + scope, ruleId, mode, branchId);
                    }
                }
            }
            case "categories" -> {
                @SuppressWarnings("unchecked")
                List<String> roots = rule.get("categoryIds") instanceof List<?> x ? (List<String>) x : List.of();
                Map<String, String> covered = coveredCategories(roots);
                for (Map<String, Object> l : lines) {
                    String c = (String) l.get("categoryId");
                    if (c != null && covered.containsKey(c)) {
                        return new Requirement(true, l.get("productName") + " (category " + covered.get(c)
                                + ") must be weighed" + scope, ruleId, mode, branchId);
                    }
                }
            }
            default -> { }
        }
        return new Requirement(false, null, ruleId, mode, branchId);
    }

    static boolean isBulk(Map<String, Object> line) {
        if (Boolean.TRUE.equals(line.get("weighSetting"))) {
            return true;
        }
        String unit = line.get("unitCategory") == null ? "" : line.get("unitCategory").toString().toLowerCase(Locale.ROOT);
        String cat = line.get("uomCategory") == null ? "" : line.get("uomCategory").toString().toLowerCase(Locale.ROOT);
        return Set.of("weight", "volume", "bulk").contains(unit) || Set.of("weight", "volume").contains(cat);
    }

    private Map<String, Object> rule(String branchId) {
        return SqlRows.one(db.jdbc().sql("SELECT r.*, b.name AS \"branchName\" FROM \"FleetWeighbridgeRequirement\" r"
                + " LEFT JOIN \"Branch\" b ON b.id = r.\"branchId\" WHERE r.\"organizationId\" = ? AND r.\"branchId\" IS"
                + " NOT DISTINCT FROM ?").params(FleetDb.org(), branchId));
    }

    /** The listed categories and all their sub-categories → name of the listed (root) category. */
    private Map<String, String> coveredCategories(List<String> roots) {
        Map<String, String> out = new LinkedHashMap<>();
        if (roots.isEmpty()) {
            return out;
        }
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("WITH RECURSIVE tree(id, root) AS (SELECT id, name"
                + " FROM \"ProductCategory\" WHERE \"organizationId\" = ? AND id = ANY(?::text[]) UNION SELECT c.id,"
                + " tree.root FROM \"ProductCategory\" c JOIN tree ON c.\"parentId\" = tree.id WHERE"
                + " c.\"organizationId\" = ?) SELECT id, root FROM tree").params(FleetDb.org(),
                        roots.toArray(String[]::new), FleetDb.org()))) {
            out.putIfAbsent((String) r.get("id"), (String) r.get("root"));
        }
        return out;
    }

    /**
     * Dispatch gate: no live ticket on the trip; when the weighbridge is required, the loaded quantity must come from
     * a completed ticket of this trip. 400 with the reason otherwise.
     */
    void assertDispatchable(Map<String, Object> trip) {
        String code = (String) trip.get("code");
        Map<String, Object> live = liveTicket((String) trip.get("id"));
        if (live != null) {
            throw ApiException.badRequest("Trip " + code + " is on weighbridge ticket " + live.get("ticketNumber")
                    + " (" + liveLabel((String) live.get("status")) + "): complete or cancel the ticket before"
                    + " dispatch");
        }
        Requirement r = forTrip(trip);
        if (r.required() && !fedByCompletedTicket(trip)) {
            throw ApiException.badRequest("Trip " + code + " needs a completed weighbridge ticket before dispatch ("
                    + r.reason() + "): weigh the truck on the weighbridge");
        }
    }

    /** Hand-entered loaded quantity: refused when the weighbridge is required for the trip. */
    void assertManualLoadAllowed(Map<String, Object> trip) {
        Requirement r = forTrip(trip);
        if (r.required()) {
            throw ApiException.badRequest("Trip " + trip.get("code") + " must be weighed on the weighbridge ("
                    + r.reason() + "): its loaded quantity cannot be entered by hand");
        }
    }

    boolean fedByCompletedTicket(Map<String, Object> trip) {
        if (!SOURCE.equals(trip.get("weighSourceType")) || trip.get("weighSourceId") == null) {
            return false;
        }
        return db.jdbc().sql("SELECT count(*) FROM \"WeighbridgeTicket\" WHERE id = ? AND \"organizationId\" = ? AND"
                + " \"tripId\" = ? AND status = 'completed'").params(trip.get("weighSourceId"), FleetDb.org(),
                        trip.get("id")).query(Long.class).single() > 0;
    }

    Map<String, Object> liveTicket(String tripId) {
        return SqlRows.one(db.jdbc().sql("SELECT id, \"ticketNumber\", status FROM \"WeighbridgeTicket\" WHERE"
                + " \"organizationId\" = ? AND \"tripId\" = ? AND status IN ('open', 'first_weighed', 'exception')"
                + " ORDER BY \"createdAt\" DESC LIMIT 1").params(FleetDb.org(), tripId));
    }

    static String liveLabel(String status) {
        return switch (status) {
            case "open" -> "waiting for the first weighing";
            case "first_weighed" -> "waiting for the second weighing";
            case "exception" -> "out of tolerance, waiting for approval";
            default -> status;
        };
    }

    /** Weighbridge tickets of a trip ({@code tripId}) or a delivery order ({@code deliveryOrderId}), oldest first. */
    static List<Map<String, Object>> linkedTickets(FleetDb db, String column, String id) {
        if (!Set.of("tripId", "deliveryOrderId").contains(column)) {
            throw new IllegalArgumentException(column);
        }
        return SqlRows.list(db.jdbc().sql("SELECT w.id, w.\"ticketNumber\", w.status,"
                + " w.\"tripId\", l.code AS \"tripCode\", w.\"registrationPlate\", w.\"grossKg\", w.\"tareKg\","
                + " w.\"netKg\", w.\"netQty\", u.code AS \"netUomCode\", w.\"expectedKg\", w.\"varianceKg\","
                + " w.\"variancePct\", w.\"withinTolerance\", w.\"isManual\", w.\"usedStoredTare\", w.\"completedAt\","
                + " w.\"voidedAt\", w.\"voidReason\", w.\"replacedById\", w.\"tripFeedStatus\", w.\"tripFeedMessage\","
                + " w.\"tripFedAt\", w.\"createdAt\" FROM \"WeighbridgeTicket\" w LEFT JOIN \"FleetLoad\" l ON l.id ="
                + " w.\"tripId\" LEFT JOIN \"UnitOfMeasure\" u ON u.id = w.\"netUomId\" WHERE w.\"organizationId\" = ?"
                + " AND w.\"" + column + "\" = ? ORDER BY w.\"createdAt\", w.\"ticketNumber\"")
                .params(FleetDb.org(), id));
    }
}
