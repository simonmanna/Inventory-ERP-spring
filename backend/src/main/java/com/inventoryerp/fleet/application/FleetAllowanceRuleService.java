package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.application.AllowanceCalculator.Rule;
import com.inventoryerp.fleet.application.DriverCommands.AllowanceRuleInput;
import com.inventoryerp.fleet.application.DriverCommands.AllowanceRulePatch;
import com.inventoryerp.fleet.domain.FleetAllowanceRule;
import com.inventoryerp.fleet.domain.FleetVehicleCategory;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Driver allowance rules (fleet 1.2, ADR-S009): an amount per trip, per day or per night out, in the organization's
 * currency, effective between two dates and optionally limited to one vehicle class and / or one route zone. Two
 * active rules with the same basis and scope must not overlap in time ({@link AllowanceCalculator#overlaps}); a rule
 * used by claims is deactivated rather than deleted. Pricing: {@link #price}.
 */
@Service
public class FleetAllowanceRuleService {

    static final String AUDIT_ENTITY = "FleetAllowanceRule";

    private final FleetDb db;
    private final AuditService audit;

    public FleetAllowanceRuleService(FleetDb db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    /** Rules with their class, newest first within basis ({@code includeInactive} adds switched-off rules). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(boolean includeInactive, String basis) {
        StringBuilder where = new StringBuilder(" WHERE r.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        if (!includeInactive) {
            where.append(" AND r.\"isActive\" = true");
        }
        if (FleetVehicleService.notBlank(basis)) {
            where.append(" AND r.basis = ?");
            params.add(basis);
        }
        return SqlRows.list(db.jdbc().sql("SELECT r.*, c.code AS \"categoryCode\", c.name AS \"categoryName\","
                + " (SELECT count(*) FROM \"FleetDriverClaimLine\" l WHERE l.\"ruleId\" = r.id) AS \"usage\""
                + " FROM \"FleetAllowanceRule\" r LEFT JOIN \"FleetVehicleCategory\" c ON c.id = r.\"categoryId\"" + where
                + " ORDER BY r.basis, r.\"effectiveFrom\" DESC, r.code").params(params));
    }

    /** Create a rule in the organization's currency. */
    @Transactional
    public Map<String, Object> create(AllowanceRuleInput in) {
        String code = FleetCategoryService.normalizeCode(in.code());
        if (db.jdbc().sql("SELECT count(*) FROM \"FleetAllowanceRule\" WHERE \"organizationId\" = ? AND code = ?")
                .params(FleetDb.org(), code).query(Long.class).single() > 0) {
            throw ApiException.conflict("An allowance rule with code " + code + " already exists");
        }
        assertCategory(in.categoryId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("name", in.name().trim());
        data.put("basis", in.basis());
        data.put("amount", in.amount());
        data.put("currencyCode", currency());
        data.put("effectiveFrom", in.effectiveFrom());
        data.put("effectiveTo", in.effectiveTo());
        data.put("categoryId", blank(in.categoryId()));
        data.put("routeZone", FleetVehicleService.trim(in.routeZone()));
        data.put("isActive", in.isActive() == null || in.isActive());
        data.put("notes", FleetVehicleService.trim(in.notes()));
        data.put("createdBy", TenantContext.userId());
        data.put("updatedBy", TenantContext.userId());
        assertPeriod(in.effectiveFrom(), in.effectiveTo());
        Rule candidate = rule(data, null);
        assertNoOverlap(candidate, null);
        Map<String, Object> row = db.insert(FleetAllowanceRule.class, data);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, (String) row.get("id"), "create", null, row));
        return row;
    }

    /** Edit a rule (the basis and currency are fixed; claims keep the rate they were priced with). */
    @Transactional
    public Map<String, Object> update(String id, AllowanceRulePatch in) {
        Map<String, Object> before = db.require(FleetAllowanceRule.class, id, true, "Allowance rule not found");
        List<String> clear = in.clear() == null ? List.of() : in.clear();
        for (String f : clear) {
            if (!DriverCommands.RULE_CLEARABLE.contains(f)) {
                throw ApiException.badRequest("Field " + f + " cannot be cleared");
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (in.name() != null) {
            data.put("name", in.name().trim());
        }
        putIf(data, "amount", in.amount());
        putIf(data, "effectiveFrom", in.effectiveFrom());
        putIf(data, "effectiveTo", in.effectiveTo());
        putIf(data, "categoryId", blank(in.categoryId()));
        putIf(data, "routeZone", FleetVehicleService.trim(in.routeZone()));
        putIf(data, "isActive", in.isActive());
        putIf(data, "notes", FleetVehicleService.trim(in.notes()));
        for (String f : clear) {
            data.put(f, null);
        }
        if (data.isEmpty()) {
            return before;
        }
        assertCategory((String) data.get("categoryId"));
        Map<String, Object> merged = new LinkedHashMap<>(before);
        merged.putAll(data);
        assertPeriod((LocalDate) merged.get("effectiveFrom"), (LocalDate) merged.get("effectiveTo"));
        assertNoOverlap(rule(merged, id), id);
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> after = db.update(FleetAllowanceRule.class, id, data);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", before, after));
        return after;
    }

    /** Delete an unused rule; a rule priced into claims is deactivated instead ({@code {deleted: false}}). */
    @Transactional
    public Map<String, Object> delete(String id) {
        Map<String, Object> r = db.require(FleetAllowanceRule.class, id, true, "Allowance rule not found");
        long used = db.jdbc().sql("SELECT count(*) FROM \"FleetDriverClaimLine\" WHERE \"organizationId\" = ? AND"
                + " \"ruleId\" = ?").params(FleetDb.org(), id).query(Long.class).single();
        if (used > 0) {
            Map<String, Object> after = db.update(FleetAllowanceRule.class, id, Obj.of("isActive", false,
                    "updatedBy", TenantContext.userId()));
            audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", r, after));
            return Map.of("ok", true, "deleted", false, "deactivated", true);
        }
        db.jdbc().sql("DELETE FROM \"FleetAllowanceRule\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, FleetDb.org()).update();
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "delete", r, null));
        return Map.of("ok", true, "deleted", true, "deactivated", false);
    }

    /**
     * The rule paying {@code basis} on {@code on} for a vehicle of {@code categoryId} in {@code routeZone}; 400 with
     * the reason when none applies.
     */
    public Map<String, Object> price(String basis, LocalDate on, String categoryId, String categoryName,
            String routeZone) {
        List<Map<String, Object>> rows = db.list(FleetAllowanceRule.class, " AND \"isActive\" = true AND basis = ?",
                basis);
        List<Rule> rules = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            rules.add(rule(r, (String) r.get("id")));
        }
        Rule best = AllowanceCalculator.select(rules, basis, on, categoryId, routeZone);
        if (best == null) {
            throw ApiException.badRequest("No active allowance rule pays " + basis.replace('_', ' ') + " on " + on
                    + (categoryName == null ? "" : " for vehicle class " + categoryName)
                    + (FleetVehicleService.notBlank(routeZone) ? " in zone " + routeZone.trim() : ""));
        }
        return rows.stream().filter(r -> best.id().equals(r.get("id"))).findFirst().orElseThrow();
    }

    private void assertNoOverlap(Rule candidate, String exceptId) {
        if (!candidate.active()) {
            return;
        }
        for (Map<String, Object> r : db.list(FleetAllowanceRule.class, " AND \"isActive\" = true AND basis = ?",
                candidate.basis())) {
            if (r.get("id").equals(exceptId)) {
                continue;
            }
            if (AllowanceCalculator.overlaps(candidate, rule(r, (String) r.get("id")))) {
                throw ApiException.conflict("Allowance rule " + r.get("code") + " already covers this basis, vehicle"
                        + " class and route zone in an overlapping period");
            }
        }
    }

    private void assertCategory(String categoryId) {
        if (FleetVehicleService.notBlank(categoryId) && db.byId(FleetVehicleCategory.class, categoryId, false) == null) {
            throw ApiException.badRequest("Vehicle class not found");
        }
    }

    private static void assertPeriod(LocalDate from, LocalDate to) {
        if (to != null && to.isBefore(from)) {
            throw ApiException.badRequest("The rule must end on or after its start date");
        }
    }

    static Rule rule(Map<String, Object> r, String id) {
        return new Rule(id, (String) r.get("code"), (String) r.get("basis"), SqlRows.dec(r, "amount"),
                (LocalDate) r.get("effectiveFrom"), (LocalDate) r.get("effectiveTo"), (String) r.get("categoryId"),
                (String) r.get("routeZone"), !Boolean.FALSE.equals(r.get("isActive")));
    }

    /** The organization's currency (Organization.currencyCode). */
    String currency() {
        return db.jdbc().sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(FleetDb.org())
                .query(String.class).optional().orElse("USD");
    }

    private static void putIf(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
