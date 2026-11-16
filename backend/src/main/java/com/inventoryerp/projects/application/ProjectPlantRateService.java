package com.inventoryerp.projects.application;

import static com.inventoryerp.projects.application.ProjectService.blank;
import static com.inventoryerp.projects.application.ProjectService.day;
import static com.inventoryerp.projects.application.ProjectService.org;

import com.inventoryerp.accounting.application.AccountingDay;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plant / equipment rate table (ADR-S075 §3). A rate prices a fleet unit ({@code vehicle}: truck or fixed plant), a
 * fixed asset ({@code asset}) or a free plant code ({@code code}) per {@code hour}, {@code day} or {@code km} over an
 * effective window. Job-costing equipment charges and fleet trips charged to a project take the rate in force on the
 * charge date; the credit goes to plant recovery on the plant's cost centre (the rate's, else the unit's / asset's).
 */
@Service
public class ProjectPlantRateService {

    static final Set<String> TYPES = Set.of("vehicle", "asset", "code");
    static final Set<String> UNITS = Set.of("hour", "day", "km");

    public record RateInput(String equipmentType, String equipmentId, String name, String unit, BigDecimal rate,
            String validFrom, String validTo, String costCenterId, Boolean isActive, String notes) {}

    private final JdbcClient jdbc;
    private final AuditService audit;

    public ProjectPlantRateService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Every rate of the organisation (optionally of one plant), newest window first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String equipmentId, Boolean activeOnly) {
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder sql = new StringBuilder("SELECT * FROM \"ProjectPlantRate\" WHERE \"organizationId\" = ?");
        if (blank(equipmentId) != null) {
            sql.append(" AND \"equipmentId\" = ?");
            params.add(equipmentId.trim());
        }
        if (Boolean.TRUE.equals(activeOnly)) {
            sql.append(" AND \"isActive\"");
        }
        return SqlRows.list(jdbc.sql(sql + " ORDER BY name, unit, \"validFrom\" DESC").params(params));
    }

    @Transactional
    public Map<String, Object> create(RateInput in) {
        String type = in.equipmentType() == null ? null : in.equipmentType().trim();
        if (type == null || !TYPES.contains(type)) {
            throw ApiException.badRequest("equipmentType must be one of the following values: vehicle, asset, code");
        }
        String equipment = blank(in.equipmentId()) == null ? null : in.equipmentId().trim();
        if (equipment == null) {
            throw ApiException.badRequest("equipmentId should not be empty");
        }
        String fallback = defaultName(type, equipment);
        String name = blank(in.name()) == null ? fallback : in.name().trim();
        String unit = unit(in.unit());
        BigDecimal rate = rate(in.rate());
        String from = day(in.validFrom()) == null ? LocalDate.now(AccountingDay.zone(jdbc)).toString() : day(in.validFrom());
        String to = dates(from, day(in.validTo()));
        if (jdbc.sql("SELECT count(*) FROM \"ProjectPlantRate\" WHERE \"organizationId\" = ? AND \"equipmentType\" = ?"
                + " AND \"equipmentId\" = ? AND unit = ? AND \"validFrom\" = ?::date").params(org(), type, equipment, unit,
                from).query(Long.class).single() > 0) {
            throw ApiException.conflict("A " + unit + " rate for " + name + " already starts on " + from);
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"ProjectPlantRate\" (id, \"organizationId\", \"equipmentType\", \"equipmentId\", name,"
                        + " unit, rate, \"validFrom\", \"validTo\", \"costCenterId\", \"isActive\", notes, \"createdBy\")"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?::date, ?::date, ?, ?, ?, ?)")
                .params(id, org(), type, equipment, name, unit, rate, from, to, blank(in.costCenterId()),
                        in.isActive() == null || in.isActive(), blank(in.notes()), TenantContext.userId())
                .update();
        audit.recordInTx(AuditInput.of("ProjectPlantRate", id, "create", null, Obj.of("equipmentId", equipment,
                "unit", unit, "rate", rate)));
        return get(id);
    }

    /** Change name, rate, window, cost centre, active flag or notes (null keeps the value). */
    @Transactional
    public Map<String, Object> update(String id, RateInput in) {
        Map<String, Object> before = get(id);
        String name = blank(in.name()) == null ? (String) before.get("name") : in.name().trim();
        BigDecimal rate = in.rate() == null ? SqlRows.dec(before, "rate") : rate(in.rate());
        String from = day(in.validFrom()) == null ? String.valueOf(before.get("validFrom")) : day(in.validFrom());
        String to = in.validTo() == null ? (before.get("validTo") == null ? null : String.valueOf(before.get("validTo")))
                : dates(from, day(in.validTo()));
        dates(from, to);
        String costCenter = in.costCenterId() == null ? (String) before.get("costCenterId") : blank(in.costCenterId());
        boolean active = in.isActive() == null ? Boolean.TRUE.equals(before.get("isActive")) : in.isActive();
        String notes = in.notes() == null ? (String) before.get("notes") : blank(in.notes());
        jdbc.sql("UPDATE \"ProjectPlantRate\" SET name = ?, rate = ?, \"validFrom\" = ?::date, \"validTo\" = ?::date,"
                + " \"costCenterId\" = ?, \"isActive\" = ?, notes = ?, \"updatedAt\" = now() WHERE id = ? AND"
                + " \"organizationId\" = ?").params(name, rate, from, to, costCenter, active, notes, id, org()).update();
        audit.recordInTx(AuditInput.of("ProjectPlantRate", id, "update", Obj.of("rate", before.get("rate")),
                Obj.of("rate", rate)));
        return get(id);
    }

    /** Deactivate (rates stay for the charges that used them). */
    @Transactional
    public Map<String, Object> deactivate(String id) {
        get(id);
        jdbc.sql("UPDATE \"ProjectPlantRate\" SET \"isActive\" = false, \"updatedAt\" = now() WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()).update();
        audit.recordInTx(AuditInput.of("ProjectPlantRate", id, "cancel", null, null));
        return get(id);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT * FROM \"ProjectPlantRate\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (r == null) {
            throw ApiException.notFound("Plant rate not found");
        }
        return r;
    }

    /**
     * The active rate of a plant ({@code equipmentId}) in force on {@code on}, of {@code unit} when given; null when
     * none. A plant priced in several units needs the unit.
     */
    public Map<String, Object> resolve(String equipmentId, String unit, LocalDate on) {
        if (unit != null && !UNITS.contains(unit)) {
            throw ApiException.badRequest("unit must be one of the following values: hour, day, km");
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT DISTINCT ON (unit) * FROM \"ProjectPlantRate\""
                + " WHERE \"organizationId\" = ? AND \"equipmentId\" = ? AND \"isActive\" AND \"validFrom\" <= ?::date AND"
                + " (\"validTo\" IS NULL OR \"validTo\" >= ?::date) AND (CAST(? AS text) IS NULL OR unit = ?)"
                + " ORDER BY unit, \"validFrom\" DESC").params(org(), equipmentId, on.toString(), on.toString(), unit,
                unit));
        if (rows.isEmpty()) {
            return null;
        }
        if (rows.size() > 1) {
            throw ApiException.badRequest("Plant " + equipmentId + " has rates per " + String.join(", ", rows.stream()
                    .map(r -> (String) r.get("unit")).toList()) + "; give the unit");
        }
        return rows.getFirst();
    }

    /** Cost centre relieved by a plant charge: the rate's, else the fleet unit's (for an asset: its linked unit's). */
    public String costCenter(Map<String, Object> rate, String equipmentId) {
        if (rate != null && rate.get("costCenterId") != null) {
            return (String) rate.get("costCenterId");
        }
        String type = rate == null ? null : (String) rate.get("equipmentType");
        if (type == null || "vehicle".equals(type)) {
            return jdbc.sql("SELECT \"costCenterId\" FROM \"FleetVehicle\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(equipmentId, org()).query(String.class).optional().orElse(null);
        }
        if ("asset".equals(type)) {
            // A fixed asset carries no cost centre of its own; the fleet unit linked to it does.
            return jdbc.sql("SELECT \"costCenterId\" FROM \"FleetVehicle\" WHERE \"assetId\" = ? AND"
                    + " \"organizationId\" = ? AND \"deletedAt\" IS NULL LIMIT 1").params(equipmentId, org())
                    .query(String.class).optional().orElse(null);
        }
        return null;
    }

    private String defaultName(String type, String equipment) {
        if ("vehicle".equals(type)) {
            Map<String, Object> v = SqlRows.one(jdbc.sql("SELECT code, name FROM \"FleetVehicle\" WHERE id = ? AND"
                    + " \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(equipment, org()));
            if (v == null) {
                throw ApiException.notFound("Vehicle not found");
            }
            return v.get("code") + " · " + v.get("name");
        }
        if ("asset".equals(type)) {
            Map<String, Object> a = SqlRows.one(jdbc.sql("SELECT name FROM \"Asset\" WHERE id = ? AND"
                    + " \"organizationId\" = ?").params(equipment, org()));
            if (a == null) {
                throw ApiException.notFound("Asset not found");
            }
            return (String) a.get("name");
        }
        return equipment;
    }

    private static String unit(String unit) {
        if (unit == null || !UNITS.contains(unit)) {
            throw ApiException.badRequest("unit must be one of the following values: hour, day, km");
        }
        return unit;
    }

    private static BigDecimal rate(BigDecimal rate) {
        if (rate == null || rate.signum() <= 0) {
            throw ApiException.badRequest("rate must be greater than 0");
        }
        return rate;
    }

    private static String dates(String from, String to) {
        if (to != null && LocalDate.parse(to).isBefore(LocalDate.parse(from))) {
            throw ApiException.badRequest("validTo must not be before validFrom");
        }
        return to;
    }
}
