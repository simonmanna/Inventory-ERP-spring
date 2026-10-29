package com.inventoryerp.fleet.application;

import static com.inventoryerp.fleet.application.MaintainableUnits.jsonb;
import static com.inventoryerp.fleet.application.MaintainableUnits.notBlank;
import static com.inventoryerp.fleet.application.MaintainableUnits.strings;
import static com.inventoryerp.fleet.application.MaintainableUnits.today;
import static com.inventoryerp.fleet.application.MaintainableUnits.trim;

import com.inventoryerp.fleet.application.MaintenanceCommands.ApplyTemplateInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.DueQuery;
import com.inventoryerp.fleet.application.MaintenanceCommands.ScheduleInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.SchedulePatch;
import com.inventoryerp.fleet.application.MaintenanceCommands.ServiceTemplateInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.ServiceTemplatePatch;
import com.inventoryerp.fleet.application.ServiceDuePolicy.DueStatus;
import com.inventoryerp.fleet.domain.FleetServiceSchedule;
import com.inventoryerp.fleet.domain.FleetServiceTemplate;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Preventive maintenance planning (fleet 1.6): service templates per vehicle class, schedules per unit (from a
 * template or custom), next-due computation ({@link ServiceDuePolicy}: km / engine hours / calendar, whichever first),
 * the due / overdue query ({@link #due}, {@link #dueForOrganization} for the 1.9 alert job) and work-order generation
 * for due schedules (manual, or the {@code fleet-maintenance-due} ShedLock job).
 */
@Service
public class FleetServiceScheduleService {

    private final FleetDb db;
    private final MaintainableUnits units;
    private final FleetCategoryService categories;
    private final FleetWorkOrderService workOrders;
    private final AuditService audit;

    public FleetServiceScheduleService(FleetDb db, MaintainableUnits units, FleetCategoryService categories,
            FleetWorkOrderService workOrders, AuditService audit) {
        this.db = db;
        this.units = units;
        this.categories = categories;
        this.workOrders = workOrders;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ templates

    /** Templates (active first), each with its class and the number of schedules using it. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> templates(String categoryId, boolean includeInactive) {
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        StringBuilder where = new StringBuilder(" WHERE t.\"organizationId\" = ?");
        if (!includeInactive) {
            where.append(" AND t.\"isActive\" = true");
        }
        if (notBlank(categoryId)) {
            where.append(" AND (t.\"categoryId\" = ? OR t.\"categoryId\" IS NULL)");
            params.add(categoryId);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("""
                SELECT t.*, c.code AS "c_code", c.name AS "c_name",
                       (SELECT count(*) FROM "FleetServiceSchedule" s WHERE s."templateId" = t.id) AS "scheduleCount"
                FROM "FleetServiceTemplate" t LEFT JOIN "FleetVehicleCategory" c ON c.id = t."categoryId"
                """ + where + " ORDER BY t.\"isActive\" DESC, t.code").params(params))) {
            Map<String, Object> t = SqlRows.as(FleetServiceTemplate.class, r);
            t.put("category", r.get("categoryId") == null ? null
                    : Obj.of("id", r.get("categoryId"), "code", r.get("c_code"), "name", r.get("c_name")));
            t.put("scheduleCount", r.get("scheduleCount"));
            out.add(t);
        }
        return out;
    }

    @Transactional
    public Map<String, Object> createTemplate(ServiceTemplateInput in) {
        String code = FleetCategoryService.normalizeCode(in.code());
        assertTemplateCode(code, null);
        if (notBlank(in.categoryId())) {
            categories.requireActive(in.categoryId());
        }
        assertInterval(in.intervalKm(), in.intervalHours(), in.intervalDays());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("name", in.name().trim());
        data.put("description", trim(in.description()));
        data.put("categoryId", trim(in.categoryId()));
        data.put("intervalKm", in.intervalKm());
        data.put("intervalHours", in.intervalHours());
        data.put("intervalDays", in.intervalDays());
        data.put("leadKm", in.leadKm());
        data.put("leadHours", in.leadHours());
        data.put("leadDays", in.leadDays());
        data.put("tasks", jsonb(tasks(in.tasks())));
        data.put("isActive", in.isActive() == null ? Boolean.TRUE : in.isActive());
        data.put("createdBy", TenantContext.userId());
        Map<String, Object> row = db.insert(FleetServiceTemplate.class, data);
        audit.recordInTx(AuditInput.of("FleetServiceTemplate", (String) row.get("id"), "create", null, row));
        return row;
    }

    @Transactional
    public Map<String, Object> updateTemplate(String id, ServiceTemplatePatch in) {
        Map<String, Object> before = db.require(FleetServiceTemplate.class, id, true, "Service template not found");
        List<String> clear = clear(in.clear(), MaintenanceCommands.TEMPLATE_CLEARABLE);
        Map<String, Object> data = new LinkedHashMap<>();
        if (in.code() != null) {
            String code = FleetCategoryService.normalizeCode(in.code());
            assertTemplateCode(code, id);
            data.put("code", code);
        }
        put(data, "name", trim(in.name()));
        put(data, "description", trim(in.description()));
        if (notBlank(in.categoryId())) {
            categories.requireActive(in.categoryId());
            data.put("categoryId", in.categoryId());
        }
        put(data, "intervalKm", in.intervalKm());
        put(data, "intervalHours", in.intervalHours());
        put(data, "intervalDays", in.intervalDays());
        put(data, "leadKm", in.leadKm());
        put(data, "leadHours", in.leadHours());
        put(data, "leadDays", in.leadDays());
        if (in.tasks() != null) {
            data.put("tasks", jsonb(tasks(in.tasks())));
        }
        put(data, "isActive", in.isActive());
        clear.forEach(f -> data.put(f, null));
        if (data.isEmpty()) {
            return before;
        }
        Map<String, Object> merged = new LinkedHashMap<>(before);
        merged.putAll(data);
        assertInterval((BigDecimal) merged.get("intervalKm"), (BigDecimal) merged.get("intervalHours"),
                (Integer) merged.get("intervalDays"));
        Map<String, Object> after = db.update(FleetServiceTemplate.class, id, data);
        audit.recordInTx(AuditInput.of("FleetServiceTemplate", id, "update", before, after));
        return after;
    }

    /** Delete an unused template (one used by schedules is deactivated instead). */
    @Transactional
    public void deleteTemplate(String id) {
        Map<String, Object> t = db.require(FleetServiceTemplate.class, id, true, "Service template not found");
        long used = db.jdbc().sql("SELECT count(*) FROM \"FleetServiceSchedule\" WHERE \"organizationId\" = ? AND"
                + " \"templateId\" = ?").params(FleetDb.org(), id).query(Long.class).single();
        if (used > 0) {
            throw ApiException.badRequest("Service template " + t.get("code") + " is used by " + used
                    + " schedule(s); deactivate it instead");
        }
        db.jdbc().sql("DELETE FROM \"FleetServiceTemplate\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, FleetDb.org()).update();
        audit.recordInTx(AuditInput.of("FleetServiceTemplate", id, "delete", t, null));
    }

    /**
     * Apply a template to units: the listed ones, else every live unit of the given / the template's class. Units
     * that already have a schedule from this template are skipped. Baseline = today + current lifetime meters.
     */
    @Transactional
    public Map<String, Object> applyTemplate(String id, ApplyTemplateInput in) {
        Map<String, Object> t = db.require(FleetServiceTemplate.class, id, false, "Service template not found");
        if (!Boolean.TRUE.equals(t.get("isActive"))) {
            throw ApiException.badRequest("Service template " + t.get("code") + " is inactive");
        }
        List<String> ids = in == null || in.vehicleIds() == null ? List.of() : in.vehicleIds();
        if (ids.isEmpty()) {
            String category = in != null && notBlank(in.categoryId()) ? in.categoryId() : (String) t.get("categoryId");
            if (category == null) {
                throw ApiException.badRequest("Choose the vehicles or a vehicle class to apply the template to");
            }
            ids = db.jdbc().sql("SELECT id FROM \"FleetVehicle\" WHERE \"organizationId\" = ? AND \"categoryId\" = ?"
                    + " AND \"deletedAt\" IS NULL AND status <> 'disposed' ORDER BY code").params(FleetDb.org(),
                    category).query(String.class).list();
        }
        List<Map<String, Object>> created = new ArrayList<>();
        int skipped = 0;
        for (String vehicleId : ids) {
            Map<String, Object> v = units.require(vehicleId, false, true);
            boolean exists = db.jdbc().sql("SELECT count(*) FROM \"FleetServiceSchedule\" WHERE \"organizationId\" = ?"
                    + " AND \"vehicleId\" = ? AND \"templateId\" = ?").params(FleetDb.org(), vehicleId, id)
                    .query(Long.class).single() > 0;
            if (exists) {
                skipped++;
                continue;
            }
            created.add(insertSchedule(v, t, new ScheduleInput(id, null, null, null, null, null, null, null, null,
                    null, null, null, null)));
        }
        return Obj.of("created", created.size(), "skipped", skipped, "schedules", created);
    }

    // ------------------------------------------------------------------ schedules

    /** Schedules of a unit with their due status. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> forVehicle(String vehicleId) {
        Map<String, Object> v = units.require(vehicleId, false, false);
        LocalDate today = today();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> s : db.list(FleetServiceSchedule.class, " AND \"vehicleId\" = ? ORDER BY name",
                vehicleId)) {
            out.add(decorate(s, v, today));
        }
        return out;
    }

    @Transactional
    public Map<String, Object> createSchedule(String vehicleId, ScheduleInput in) {
        Map<String, Object> v = units.require(vehicleId, true, true);
        Map<String, Object> t = null;
        if (notBlank(in.templateId())) {
            t = db.require(FleetServiceTemplate.class, in.templateId(), false, "Service template not found");
            boolean exists = db.jdbc().sql("SELECT count(*) FROM \"FleetServiceSchedule\" WHERE \"organizationId\" = ?"
                    + " AND \"vehicleId\" = ? AND \"templateId\" = ?").params(FleetDb.org(), vehicleId,
                    in.templateId()).query(Long.class).single() > 0;
            if (exists) {
                throw ApiException.conflict("Vehicle " + v.get("code") + " already has a schedule from template "
                        + t.get("code"));
            }
        } else if (!notBlank(in.name())) {
            throw ApiException.badRequest("A name is required for a schedule without a template");
        }
        return decorate(insertSchedule(v, t, in), v, today());
    }

    private Map<String, Object> insertSchedule(Map<String, Object> v, Map<String, Object> t, ScheduleInput in) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("vehicleId", v.get("id"));
        data.put("templateId", t == null ? null : t.get("id"));
        data.put("name", notBlank(in.name()) ? in.name().trim() : (String) t.get("name"));
        data.put("intervalKm", pick(in.intervalKm(), t, "intervalKm"));
        data.put("intervalHours", pick(in.intervalHours(), t, "intervalHours"));
        data.put("intervalDays", pick(in.intervalDays(), t, "intervalDays"));
        data.put("leadKm", pick(in.leadKm(), t, "leadKm"));
        data.put("leadHours", pick(in.leadHours(), t, "leadHours"));
        data.put("leadDays", pick(in.leadDays(), t, "leadDays"));
        data.put("tasks", jsonb(in.tasks() != null ? tasks(in.tasks()) : t == null ? List.of() : strings(t.get("tasks"))));
        assertInterval((BigDecimal) data.get("intervalKm"), (BigDecimal) data.get("intervalHours"),
                (Integer) data.get("intervalDays"));
        data.put("lastDoneOn", in.lastDoneOn() == null ? today() : in.lastDoneOn());
        data.put("lastDoneKm", in.lastDoneKm() != null ? in.lastDoneKm() : MaintainableUnits.lifetimeKm(v));
        data.put("lastDoneHours", in.lastDoneHours() != null ? in.lastDoneHours() : MaintainableUnits.lifetimeHours(v));
        data.put("notes", trim(in.notes()));
        data.put("createdBy", TenantContext.userId());
        Map<String, Object> row = db.insert(FleetServiceSchedule.class, data);
        audit.recordInTx(AuditInput.of("FleetServiceSchedule", (String) row.get("id"), "create", null, row));
        return row;
    }

    @Transactional
    public Map<String, Object> updateSchedule(String id, SchedulePatch in) {
        Map<String, Object> before = db.require(FleetServiceSchedule.class, id, true, "Service schedule not found");
        List<String> clear = clear(in.clear(), MaintenanceCommands.SCHEDULE_CLEARABLE);
        Map<String, Object> data = new LinkedHashMap<>();
        put(data, "name", trim(in.name()));
        put(data, "intervalKm", in.intervalKm());
        put(data, "intervalHours", in.intervalHours());
        put(data, "intervalDays", in.intervalDays());
        put(data, "leadKm", in.leadKm());
        put(data, "leadHours", in.leadHours());
        put(data, "leadDays", in.leadDays());
        if (in.tasks() != null) {
            data.put("tasks", jsonb(tasks(in.tasks())));
        }
        put(data, "lastDoneOn", in.lastDoneOn());
        put(data, "lastDoneKm", in.lastDoneKm());
        put(data, "lastDoneHours", in.lastDoneHours());
        put(data, "status", in.status());
        put(data, "notes", trim(in.notes()));
        clear.forEach(f -> data.put(f, null));
        Map<String, Object> v = units.require((String) before.get("vehicleId"), false, false);
        if (data.isEmpty()) {
            return decorate(before, v, today());
        }
        Map<String, Object> merged = new LinkedHashMap<>(before);
        merged.putAll(data);
        assertInterval((BigDecimal) merged.get("intervalKm"), (BigDecimal) merged.get("intervalHours"),
                (Integer) merged.get("intervalDays"));
        Map<String, Object> after = db.update(FleetServiceSchedule.class, id, data);
        audit.recordInTx(AuditInput.of("FleetServiceSchedule", id, "update", before, after));
        return decorate(after, v, today());
    }

    @Transactional
    public void deleteSchedule(String id) {
        Map<String, Object> s = db.require(FleetServiceSchedule.class, id, true, "Service schedule not found");
        String live = liveWorkOrder(id);
        if (live != null) {
            throw ApiException.badRequest("Schedule " + s.get("name") + " has live work order " + live
                    + "; complete or cancel it first");
        }
        db.jdbc().sql("DELETE FROM \"FleetServiceSchedule\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, FleetDb.org()).update();
        audit.recordInTx(AuditInput.of("FleetServiceSchedule", id, "delete", s, null));
    }

    // ------------------------------------------------------------------ due query

    /**
     * Due list over the active schedules of live units, most urgent first (overdue, then due, by how close each is to
     * its due point). The due state depends on today's meters, so it is computed per row and paged in memory.
     */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> due(DueQuery q) {
        int page = q.page() == null || q.page() < 1 ? 1 : q.page();
        int size = q.pageSize() == null ? 50 : Math.max(1, Math.min(200, q.pageSize()));
        String state = q.state() == null ? "attention" : q.state();
        List<Map<String, Object>> rows = evaluateAll(FleetDb.org(), q.asOf() == null ? today() : q.asOf(),
                q.vehicleId(), q.categoryId(), q.branchId(), state);
        int fromIdx = Math.min(rows.size(), (page - 1) * size);
        return PageResult.of(rows.subList(fromIdx, Math.min(rows.size(), fromIdx + size)), page, size, rows.size());
    }

    /** Due and overdue schedules of {@code organizationId} on {@code asOf} — for the 1.9 alert job (no request). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> dueForOrganization(String organizationId, LocalDate asOf) {
        return evaluateAll(organizationId, asOf, null, null, null, "attention");
    }

    /** Worst due state of a unit's active schedules (dispatch warnings). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> attentionForVehicle(String vehicleId) {
        return evaluateAll(FleetDb.org(), today(), vehicleId, null, null, "attention");
    }

    private List<Map<String, Object>> evaluateAll(String org, LocalDate asOf, String vehicleId, String categoryId,
            String branchId, String state) {
        StringBuilder where = new StringBuilder(" WHERE s.\"organizationId\" = ? AND s.status = 'active'"
                + " AND v.\"deletedAt\" IS NULL AND v.status <> 'disposed'");
        List<Object> params = new ArrayList<>(List.of(org));
        filter(where, params, "s.\"vehicleId\"", vehicleId);
        filter(where, params, "v.\"categoryId\"", categoryId);
        filter(where, params, "v.\"branchId\"", branchId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("""
                SELECT s.*, v.code AS "v_code", v.name AS "v_name", v."registrationPlate" AS "v_plate",
                       v.status AS "v_status", v."currentOdometer" AS "v_odo", v."odometerOffset" AS "v_odoOff",
                       v."currentHours" AS "v_hours", v."hoursOffset" AS "v_hoursOff", c.name AS "v_category",
                       w.code AS "wo_code", w.status AS "wo_status"
                FROM "FleetServiceSchedule" s JOIN "FleetVehicle" v ON v.id = s."vehicleId"
                JOIN "FleetVehicleCategory" c ON c.id = v."categoryId"
                LEFT JOIN "FleetWorkOrder" w ON w."scheduleId" = s.id AND w.status IN ('draft', 'open', 'in_progress')
                """ + where).params(params))) {
            Map<String, Object> vehicle = Obj.of("id", r.get("vehicleId"), "code", r.get("v_code"), "name",
                    r.get("v_name"), "registrationPlate", r.get("v_plate"), "status", r.get("v_status"),
                    "categoryName", r.get("v_category"));
            Map<String, Object> meters = Obj.of("currentOdometer", r.get("v_odo"), "odometerOffset", r.get("v_odoOff"),
                    "currentHours", r.get("v_hours"), "hoursOffset", r.get("v_hoursOff"));
            Map<String, Object> s = decorate(SqlRows.as(FleetServiceSchedule.class, r), meters, asOf);
            String st = (String) s.get("dueState");
            boolean keep = switch (state) {
                case "all" -> true;
                case "attention" -> !ServiceDuePolicy.OK.equals(st);
                default -> state.equals(st);
            };
            if (!keep) {
                continue;
            }
            s.put("vehicle", vehicle);
            s.put("liveWorkOrder", r.get("wo_code") == null ? null : Obj.of("code", r.get("wo_code"), "status",
                    r.get("wo_status")));
            out.add(s);
        }
        out.sort(Comparator.comparingInt((Map<String, Object> m) -> -ServiceDuePolicy.rank((String) m.get("dueState")))
                .thenComparing(m -> (BigDecimal) m.get("urgency")));
        return out;
    }

    /** Adds {@code due} (dimensions), {@code dueState}, {@code dueTrigger}, {@code urgency} (smaller = sooner). */
    Map<String, Object> decorate(Map<String, Object> s, Map<String, Object> v, LocalDate asOf) {
        DueStatus st = evaluate(s, v, asOf);
        Map<String, Object> out = new LinkedHashMap<>(s);
        List<Map<String, Object>> dims = new ArrayList<>();
        BigDecimal urgency = BigDecimal.valueOf(Long.MAX_VALUE);
        for (ServiceDuePolicy.Dimension d : st.dimensions()) {
            dims.add(Obj.of("dimension", d.dimension(), "interval", d.interval(), "nextDue", d.nextDue(),
                    "nextDueOn", d.nextDueOn(), "remaining", d.remaining(), "state", d.state()));
            if (d.remaining() != null) {
                BigDecimal ratio = d.remaining().divide(d.interval(), 6, java.math.RoundingMode.HALF_UP);
                urgency = urgency.min(ratio);
            }
        }
        out.put("due", dims);
        out.put("dueState", st.state());
        out.put("dueTrigger", st.trigger());
        out.put("urgency", urgency);
        return out;
    }

    static DueStatus evaluate(Map<String, Object> s, Map<String, Object> v, LocalDate asOf) {
        return ServiceDuePolicy.evaluate(
                new ServiceDuePolicy.Interval((BigDecimal) s.get("intervalKm"), (BigDecimal) s.get("intervalHours"),
                        (Integer) s.get("intervalDays"), (BigDecimal) s.get("leadKm"),
                        (BigDecimal) s.get("leadHours"), (Integer) s.get("leadDays")),
                new ServiceDuePolicy.Baseline((LocalDate) s.get("lastDoneOn"), (BigDecimal) s.get("lastDoneKm"),
                        (BigDecimal) s.get("lastDoneHours")),
                new ServiceDuePolicy.Current(asOf, MaintainableUnits.lifetimeKm(v), MaintainableUnits.lifetimeHours(v)));
    }

    // ------------------------------------------------------------------ work-order generation

    /** Raise the preventive work order of one schedule now (409 when it already has a live one). */
    @Transactional
    public Map<String, Object> generateWorkOrder(String scheduleId) {
        Map<String, Object> s = db.require(FleetServiceSchedule.class, scheduleId, true, "Service schedule not found");
        String live = liveWorkOrder(scheduleId);
        if (live != null) {
            throw ApiException.conflict("Schedule " + s.get("name") + " already has live work order " + live);
        }
        Map<String, Object> v = units.require((String) s.get("vehicleId"), false, true);
        return workOrders.createFromSchedule(s, v, evaluate(s, v, today()), "schedule");
    }

    /**
     * Raise work orders for every due / overdue active schedule of the caller's organization without a live work
     * order ({@code source} = schedule for the screen action, schedule_job for the ShedLock job).
     */
    @Transactional
    public Map<String, Object> generateDue(String source) {
        LocalDate asOf = today();
        List<String> created = new ArrayList<>();
        int skipped = 0;
        for (Map<String, Object> row : evaluateAll(FleetDb.org(), asOf, null, null, null, "attention")) {
            if (row.get("liveWorkOrder") != null) {
                skipped++;
                continue;
            }
            Map<String, Object> s = db.require(FleetServiceSchedule.class, (String) row.get("id"), true,
                    "Service schedule not found");
            if (liveWorkOrder((String) s.get("id")) != null) {
                skipped++;
                continue;
            }
            Map<String, Object> v = units.require((String) s.get("vehicleId"), false, true);
            Map<String, Object> wo = workOrders.createFromSchedule(s, v, evaluate(s, v, asOf), source);
            created.add((String) wo.get("code"));
        }
        return Obj.of("created", created.size(), "workOrders", created, "skipped", skipped);
    }

    private String liveWorkOrder(String scheduleId) {
        return db.jdbc().sql("SELECT code FROM \"FleetWorkOrder\" WHERE \"organizationId\" = ? AND \"scheduleId\" = ?"
                        + " AND status IN ('draft', 'open', 'in_progress') LIMIT 1").params(FleetDb.org(), scheduleId)
                .query(String.class).optional().orElse(null);
    }

    // ------------------------------------------------------------------ helpers

    private void assertTemplateCode(String code, String exceptId) {
        List<Object> params = new ArrayList<>(List.of(FleetDb.org(), code));
        if (exceptId != null) {
            params.add(exceptId);
        }
        if (db.jdbc().sql("SELECT count(*) FROM \"FleetServiceTemplate\" WHERE \"organizationId\" = ? AND code = ?"
                + (exceptId == null ? "" : " AND id <> ?")).params(params).query(Long.class).single() > 0) {
            throw ApiException.conflict("A service template with code " + code + " already exists");
        }
    }

    static void assertInterval(BigDecimal km, BigDecimal hours, Integer days) {
        if (km == null && hours == null && days == null) {
            throw ApiException.badRequest("Set at least one interval: kilometres, engine hours or days");
        }
    }

    static List<String> tasks(List<String> tasks) {
        List<String> out = new ArrayList<>();
        if (tasks != null) {
            for (String t : tasks) {
                if (notBlank(t)) {
                    out.add(t.trim());
                }
            }
        }
        return out;
    }

    private static Object pick(Object value, Map<String, Object> template, String key) {
        return value != null ? value : template == null ? null : template.get(key);
    }

    static List<String> clear(List<String> clear, List<String> allowed) {
        List<String> out = clear == null ? List.of() : clear;
        for (String f : out) {
            if (!allowed.contains(f)) {
                throw ApiException.badRequest("Field " + f + " cannot be cleared");
            }
        }
        return out;
    }

    static void put(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }

    static void filter(StringBuilder where, List<Object> params, String column, String value) {
        if (notBlank(value)) {
            where.append(" AND ").append(column).append(" = ?");
            params.add(value);
        }
    }
}
