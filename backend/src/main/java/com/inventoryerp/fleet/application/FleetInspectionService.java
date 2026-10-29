package com.inventoryerp.fleet.application;

import static com.inventoryerp.fleet.application.FleetServiceScheduleService.filter;
import static com.inventoryerp.fleet.application.MaintainableUnits.jsonb;
import static com.inventoryerp.fleet.application.MaintainableUnits.notBlank;
import static com.inventoryerp.fleet.application.MaintainableUnits.text;
import static com.inventoryerp.fleet.application.MaintainableUnits.trim;

import com.inventoryerp.fleet.application.MaintenanceCommands.ChecklistItem;
import com.inventoryerp.fleet.application.MaintenanceCommands.DefectCloseInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.DefectInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.DefectQuery;
import com.inventoryerp.fleet.application.MaintenanceCommands.DefectWorkOrderInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.InspectionInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.InspectionQuery;
import com.inventoryerp.fleet.application.MaintenanceCommands.InspectionTemplateInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.InspectionTemplatePatch;
import com.inventoryerp.fleet.application.MaintenanceCommands.ItemResult;
import com.inventoryerp.fleet.application.MaintenanceCommands.WorkOrderInput;
import com.inventoryerp.fleet.domain.FleetDefect;
import com.inventoryerp.fleet.domain.FleetInspection;
import com.inventoryerp.fleet.domain.FleetInspectionTemplate;
import com.inventoryerp.fleet.domain.FleetWorkOrder;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Inspections / pre-trip checklists (DVIR) and defects (fleet 1.6): configurable checklist templates per vehicle
 * class, inspection records (inspector employee, meters, pass / fail / n.a. per item, notes, photos), failed items
 * raise defects (minor / major / critical) that become work orders. An open critical defect blocks dispatch
 * ({@link FleetDispatchEligibilityService}).
 */
@Service
public class FleetInspectionService {

    private final FleetDb db;
    private final MaintainableUnits units;
    private final FleetCategoryService categories;
    private final FleetMeterService meters;
    private final FleetAttachmentService attachments;
    private final FleetWorkOrderService workOrders;
    private final SequenceService sequence;
    private final AuditService audit;
    private final EventBus events;

    public FleetInspectionService(FleetDb db, MaintainableUnits units, FleetCategoryService categories,
            FleetMeterService meters, FleetAttachmentService attachments, FleetWorkOrderService workOrders,
            SequenceService sequence, AuditService audit, EventBus events) {
        this.db = db;
        this.units = units;
        this.categories = categories;
        this.meters = meters;
        this.attachments = attachments;
        this.workOrders = workOrders;
        this.sequence = sequence;
        this.audit = audit;
        this.events = events;
    }

    // ------------------------------------------------------------------ checklist templates

    /** The default daily pre-trip checklist created for an organization without any. */
    static final List<Object[]> DEFAULT_PRE_TRIP = List.of(
            new Object[] {"BRAKES", "Brakes and air pressure", "Safety", "critical"},
            new Object[] {"STEERING", "Steering", "Safety", "critical"},
            new Object[] {"SEAT_BELT", "Seat belt", "Safety", "critical"},
            new Object[] {"LIGHTS", "Lights, indicators and reflectors", "Safety", "major"},
            new Object[] {"TYRES", "Tyres and wheel nuts", "Running gear", "major"},
            new Object[] {"LEAKS", "Oil, fuel, coolant or hydraulic leaks", "Engine", "major"},
            new Object[] {"FLUIDS", "Engine oil, coolant and fluid levels", "Engine", "minor"},
            new Object[] {"BODY", "Tipper body, hydraulics and load securing", "Body", "major"},
            new Object[] {"MIRRORS", "Mirrors, windscreen and wipers", "Cab", "minor"},
            new Object[] {"HORN", "Horn and reverse alarm", "Cab", "minor"},
            new Object[] {"SAFETY_KIT", "Fire extinguisher, triangles and first-aid kit", "Cab", "major"},
            new Object[] {"DOCUMENTS", "Vehicle documents on board", "Cab", "minor"});

    /** Checklists (active first). An organization without any gets the default pre-trip checklist. */
    @Transactional
    public List<Map<String, Object>> templates(String categoryId, String kind, boolean includeInactive) {
        String org = FleetDb.org();
        if (db.jdbc().sql("SELECT count(*) FROM \"FleetInspectionTemplate\" WHERE \"organizationId\" = ?").param(org)
                .query(Long.class).single() == 0) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (Object[] i : DEFAULT_PRE_TRIP) {
                items.add(Obj.of("code", i[0], "label", i[1], "section", i[2], "severity", i[3]));
            }
            // ON CONFLICT: two first visits at once must not fail on the unique code.
            db.jdbc().sql("INSERT INTO \"FleetInspectionTemplate\" (id, \"organizationId\", code, name, kind, items,"
                    + " \"updatedAt\") VALUES (gen_random_uuid()::text, ?, 'PRE_TRIP', 'Daily pre-trip check', 'pre_trip',"
                    + " ?, " + FleetDb.NOW + ") ON CONFLICT (\"organizationId\", code) DO NOTHING")
                    .params(org, jsonb(items)).update();
        }
        StringBuilder where = new StringBuilder(" WHERE t.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(org));
        if (!includeInactive) {
            where.append(" AND t.\"isActive\" = true");
        }
        if (notBlank(categoryId)) {
            where.append(" AND (t.\"categoryId\" = ? OR t.\"categoryId\" IS NULL)");
            params.add(categoryId);
        }
        filter(where, params, "t.kind", kind);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT t.*, c.name AS \"c_name\" FROM"
                + " \"FleetInspectionTemplate\" t LEFT JOIN \"FleetVehicleCategory\" c ON c.id = t.\"categoryId\""
                + where + " ORDER BY t.\"isActive\" DESC, t.\"categoryId\" NULLS LAST, t.code").params(params))) {
            Map<String, Object> t = SqlRows.as(FleetInspectionTemplate.class, r);
            t.put("category", r.get("categoryId") == null ? null : Obj.of("id", r.get("categoryId"), "name",
                    r.get("c_name")));
            out.add(t);
        }
        return out;
    }

    @Transactional
    public Map<String, Object> createTemplate(InspectionTemplateInput in) {
        String code = FleetCategoryService.normalizeCode(in.code());
        assertTemplateCode(code, null);
        if (notBlank(in.categoryId())) {
            categories.requireActive(in.categoryId());
        }
        Map<String, Object> row = db.insert(FleetInspectionTemplate.class, Obj.of(
                "code", code,
                "name", in.name().trim(),
                "kind", in.kind() == null ? "pre_trip" : in.kind(),
                "categoryId", trim(in.categoryId()),
                "items", jsonb(items(in.items())),
                "isActive", in.isActive() == null ? Boolean.TRUE : in.isActive(),
                "createdBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of("FleetInspectionTemplate", (String) row.get("id"), "create", null, row));
        return row;
    }

    @Transactional
    public Map<String, Object> updateTemplate(String id, InspectionTemplatePatch in) {
        Map<String, Object> before = db.require(FleetInspectionTemplate.class, id, true, "Checklist not found");
        List<String> clear = FleetServiceScheduleService.clear(in.clear(), List.of("categoryId"));
        Map<String, Object> data = new LinkedHashMap<>();
        if (in.code() != null) {
            String code = FleetCategoryService.normalizeCode(in.code());
            assertTemplateCode(code, id);
            data.put("code", code);
        }
        FleetServiceScheduleService.put(data, "name", trim(in.name()));
        FleetServiceScheduleService.put(data, "kind", in.kind());
        if (notBlank(in.categoryId())) {
            categories.requireActive(in.categoryId());
            data.put("categoryId", in.categoryId());
        }
        if (in.items() != null) {
            data.put("items", jsonb(items(in.items())));
        }
        FleetServiceScheduleService.put(data, "isActive", in.isActive());
        clear.forEach(f -> data.put(f, null));
        if (data.isEmpty()) {
            return before;
        }
        Map<String, Object> after = db.update(FleetInspectionTemplate.class, id, data);
        audit.recordInTx(AuditInput.of("FleetInspectionTemplate", id, "update", before, after));
        return after;
    }

    @Transactional
    public void deleteTemplate(String id) {
        Map<String, Object> t = db.require(FleetInspectionTemplate.class, id, true, "Checklist not found");
        long used = db.jdbc().sql("SELECT count(*) FROM \"FleetInspection\" WHERE \"organizationId\" = ? AND"
                + " \"templateId\" = ?").params(FleetDb.org(), id).query(Long.class).single();
        if (used > 0) {
            throw ApiException.badRequest("Checklist " + t.get("code") + " has " + used
                    + " recorded inspection(s); deactivate it instead");
        }
        db.jdbc().sql("DELETE FROM \"FleetInspectionTemplate\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, FleetDb.org()).update();
        audit.recordInTx(AuditInput.of("FleetInspectionTemplate", id, "delete", t, null));
    }

    private static List<Map<String, Object>> items(List<ChecklistItem> items) {
        Set<String> codes = new LinkedHashSet<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChecklistItem i : items) {
            String code = FleetCategoryService.normalizeCode(i.code());
            if (!codes.add(code)) {
                throw ApiException.badRequest("Checklist item code " + code + " is used twice");
            }
            out.add(Obj.of("code", code, "label", i.label().trim(), "section", trim(i.section()), "severity",
                    i.severity() == null ? "minor" : i.severity()));
        }
        return out;
    }

    private void assertTemplateCode(String code, String exceptId) {
        List<Object> params = new ArrayList<>(List.of(FleetDb.org(), code));
        if (exceptId != null) {
            params.add(exceptId);
        }
        if (db.jdbc().sql("SELECT count(*) FROM \"FleetInspectionTemplate\" WHERE \"organizationId\" = ? AND code = ?"
                + (exceptId == null ? "" : " AND id <> ?")).params(params).query(Long.class).single() > 0) {
            throw ApiException.conflict("A checklist with code " + code + " already exists");
        }
    }

    // ------------------------------------------------------------------ inspections

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> inspections(InspectionQuery q) {
        int page = q.page() == null || q.page() < 1 ? 1 : q.page();
        int size = q.pageSize() == null ? 20 : Math.max(1, Math.min(200, q.pageSize()));
        StringBuilder where = new StringBuilder(" WHERE i.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        filter(where, params, "i.\"vehicleId\"", q.vehicleId());
        filter(where, params, "i.result", q.result());
        filter(where, params, "i.kind", q.kind());
        if (q.from() != null) {
            where.append(" AND i.\"inspectedAt\" >= ?");
            params.add(SqlRows.ts(q.from().atStartOfDay(BusinessZone.get()).toInstant()));
        }
        if (q.to() != null) {
            where.append(" AND i.\"inspectedAt\" < ?");
            params.add(SqlRows.ts(q.to().plusDays(1).atStartOfDay(BusinessZone.get()).toInstant()));
        }
        String from = " FROM \"FleetInspection\" i JOIN \"FleetVehicle\" v ON v.id = i.\"vehicleId\""
                + " LEFT JOIN \"FleetInspectionTemplate\" t ON t.id = i.\"templateId\""
                + " LEFT JOIN \"HrEmployee\" e ON e.id = i.\"inspectorEmployeeId\" AND e.\"organizationId\" ="
                + " i.\"organizationId\"";
        long total = db.jdbc().sql("SELECT count(*)" + from + where).params(params).query(Long.class).single();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((page - 1) * size);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT i.*, v.code AS \"v_code\","
                + " v.\"registrationPlate\" AS \"v_plate\", t.name AS \"t_name\", e.\"firstName\" AS \"e_first\","
                + " e.\"lastName\" AS \"e_last\"" + from + where
                + " ORDER BY i.\"inspectedAt\" DESC, i.code DESC LIMIT ? OFFSET ?").params(pageParams))) {
            Map<String, Object> i = SqlRows.as(FleetInspection.class, r);
            i.remove("items");
            i.put("vehicle", Obj.of("id", r.get("vehicleId"), "code", r.get("v_code"), "registrationPlate",
                    r.get("v_plate")));
            i.put("templateName", r.get("t_name"));
            i.put("inspectorName", name(r.get("e_first"), r.get("e_last")));
            rows.add(i);
        }
        return PageResult.of(rows, page, size, total);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> inspection(String id) {
        Map<String, Object> i = db.require(FleetInspection.class, id, false, "Inspection not found");
        Map<String, Object> out = new LinkedHashMap<>(i);
        out.put("vehicle", SqlRows.one(db.jdbc().sql("SELECT id, code, name, \"registrationPlate\", status FROM"
                + " \"FleetVehicle\" WHERE id = ? AND \"organizationId\" = ?").params(i.get("vehicleId"),
                FleetDb.org())));
        out.put("inspector", workOrders.employee((String) i.get("inspectorEmployeeId")));
        out.put("defects", db.list(FleetDefect.class, " AND \"inspectionId\" = ? ORDER BY code", id));
        Map<String, Map<String, Object>> files = new LinkedHashMap<>();
        for (Map<String, Object> a : attachments.list((String) i.get("vehicleId"))) {
            files.put((String) a.get("id"), a);
        }
        List<Map<String, Object>> photos = new ArrayList<>();
        if (i.get("photos") instanceof JsonNode arr) {
            for (JsonNode p : arr) {
                Map<String, Object> a = files.get(text(p, "attachmentId"));
                if (a != null) {
                    photos.add(Obj.of("attachmentId", a.get("id"), "itemCode", text(p, "itemCode"), "url",
                            a.get("url"), "filename", a.get("filename")));
                }
            }
        }
        out.put("photos", photos);
        return out;
    }

    /**
     * Record an inspection against its checklist: every item answered (pass / fail / na). Failed items raise defects
     * (severity from the answer, else the checklist item) unless the same item already has an open defect on the
     * unit. Meters given are recorded as readings (source inspection).
     */
    @Transactional
    public Map<String, Object> record(InspectionInput in) {
        Map<String, Object> v = units.require(in.vehicleId(), true, true);
        Map<String, Object> t = db.require(FleetInspectionTemplate.class, in.templateId(), false,
                "Checklist not found");
        if (!Boolean.TRUE.equals(t.get("isActive"))) {
            throw ApiException.badRequest("Checklist " + t.get("code") + " is inactive");
        }
        if (t.get("categoryId") != null && !t.get("categoryId").equals(v.get("categoryId"))) {
            throw ApiException.badRequest("Checklist " + t.get("code") + " is for another vehicle class");
        }
        workOrders.assertEmployee(in.inspectorEmployeeId());
        Instant now = Instant.now();
        Instant at = in.inspectedAt() == null ? now : in.inspectedAt();
        if (at.isAfter(now.plus(Duration.ofMinutes(5)))) {
            throw ApiException.badRequest("The inspection time cannot be in the future");
        }
        Map<String, ItemResult> answers = new LinkedHashMap<>();
        for (ItemResult r : in.items()) {
            String code = FleetCategoryService.normalizeCode(r.code());
            if (answers.put(code, r) != null) {
                throw ApiException.badRequest("Checklist item " + code + " is answered twice");
            }
        }
        List<Map<String, Object>> results = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        Set<String> known = new LinkedHashSet<>();
        if (t.get("items") instanceof JsonNode arr) {
            for (JsonNode item : arr) {
                String code = text(item, "code");
                known.add(code);
                ItemResult a = answers.get(code);
                if (a == null) {
                    missing.add(code);
                    continue;
                }
                String severity = a.severity() != null ? a.severity()
                        : text(item, "severity") == null ? "minor" : text(item, "severity");
                results.add(Obj.of("code", code, "label", text(item, "label"), "section", text(item, "section"),
                        "result", a.result(), "severity", "fail".equals(a.result()) ? severity : null, "notes",
                        trim(a.notes())));
            }
        }
        for (String code : answers.keySet()) {
            if (!known.contains(code)) {
                throw ApiException.badRequest("Checklist item " + code + " is not on checklist " + t.get("code"));
            }
        }
        if (!missing.isEmpty()) {
            throw ApiException.badRequest("Answer every checklist item; missing: " + String.join(", ", missing));
        }
        String result = MaintenancePolicies.inspectionResult(results.stream().map(r -> (String) r.get("result"))
                .toList());
        String id = UUID.randomUUID().toString();
        String code = nextCode("fleet_inspection", "INS-", "FleetInspection");
        String vehicleId = in.vehicleId();
        if (in.odometer() != null) {
            meters.recordAutomated(vehicleId, "odometer", in.odometer(), at, "inspection", id, "Inspection " + code);
        }
        if (in.hours() != null) {
            meters.recordAutomated(vehicleId, "hours", in.hours(), at, "inspection", id, "Inspection " + code);
        }
        db.insert(FleetInspection.class, Obj.of(
                "id", id,
                "code", code,
                "vehicleId", vehicleId,
                "templateId", t.get("id"),
                "kind", t.get("kind"),
                "inspectorEmployeeId", trim(in.inspectorEmployeeId()),
                "recordedBy", TenantContext.userId(),
                "inspectedAt", at,
                "odometer", in.odometer(),
                "hours", in.hours(),
                "result", result,
                "items", jsonb(results),
                "notes", trim(in.notes())));
        int defects = 0;
        for (Map<String, Object> r : results) {
            if (!"fail".equals(r.get("result"))) {
                continue;
            }
            boolean repeat = db.jdbc().sql("SELECT count(*) FROM \"FleetDefect\" WHERE \"organizationId\" = ? AND"
                    + " \"vehicleId\" = ? AND \"itemCode\" = ? AND status IN ('open', 'in_repair')")
                    .params(FleetDb.org(), vehicleId, r.get("code")).query(Long.class).single() > 0;
            if (repeat) {
                continue;
            }
            insertDefect(vehicleId, id, (String) r.get("code"), (String) r.get("label"), (String) r.get("notes"),
                    (String) r.get("severity"), trim(in.inspectorEmployeeId()), at);
            defects++;
        }
        db.update(FleetInspection.class, id, Obj.of("defectCount", defects));
        audit.recordInTx(AuditInput.of("FleetInspection", id, "create", null, Obj.of("code", code, "vehicleId",
                vehicleId, "result", result, "defects", defects)));
        return inspection(id);
    }

    /** Attach a photo (camera capture) to an inspection, optionally for one checklist item. */
    @Transactional
    public Map<String, Object> addPhoto(String inspectionId, String itemCode, String filename, String contentType,
            byte[] bytes) {
        Map<String, Object> i = db.require(FleetInspection.class, inspectionId, true, "Inspection not found");
        String item = notBlank(itemCode) ? FleetCategoryService.normalizeCode(itemCode) : null;
        Map<String, Object> a = attachments.upload((String) i.get("vehicleId"), "photo", "Inspection " + i.get("code")
                + (item == null ? "" : " · " + item), null, filename, contentType, bytes);
        List<Map<String, Object>> photos = new ArrayList<>();
        if (i.get("photos") instanceof JsonNode arr) {
            for (JsonNode p : arr) {
                photos.add(Obj.of("attachmentId", text(p, "attachmentId"), "itemCode", text(p, "itemCode")));
            }
        }
        photos.add(Obj.of("attachmentId", a.get("id"), "itemCode", item));
        db.update(FleetInspection.class, inspectionId, Obj.of("photos", jsonb(photos)));
        return inspection(inspectionId);
    }

    // ------------------------------------------------------------------ defects

    /** Defects; {@code status} = open (open + in repair, default), resolved, dismissed, in_repair or all. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> defects(DefectQuery q) {
        int page = q.page() == null || q.page() < 1 ? 1 : q.page();
        int size = q.pageSize() == null ? 20 : Math.max(1, Math.min(200, q.pageSize()));
        StringBuilder where = new StringBuilder(" WHERE d.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        filter(where, params, "d.\"vehicleId\"", q.vehicleId());
        filter(where, params, "d.severity", q.severity());
        String status = q.status() == null ? "open" : q.status();
        if ("open".equals(status)) {
            where.append(" AND d.status IN ('open', 'in_repair')");
        } else if (!"all".equals(status)) {
            filter(where, params, "d.status", status);
        }
        String from = " FROM \"FleetDefect\" d JOIN \"FleetVehicle\" v ON v.id = d.\"vehicleId\""
                + " LEFT JOIN \"FleetWorkOrder\" w ON w.id = d.\"workOrderId\""
                + " LEFT JOIN \"FleetInspection\" i ON i.id = d.\"inspectionId\"";
        long total = db.jdbc().sql("SELECT count(*)" + from + where).params(params).query(Long.class).single();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((page - 1) * size);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT d.*, v.code AS \"v_code\","
                + " v.\"registrationPlate\" AS \"v_plate\", v.status AS \"v_status\", w.code AS \"w_code\","
                + " w.status AS \"w_status\", i.code AS \"i_code\"" + from + where
                + " ORDER BY CASE d.severity WHEN 'critical' THEN 0 WHEN 'major' THEN 1 ELSE 2 END,"
                + " d.\"reportedAt\" DESC LIMIT ? OFFSET ?").params(pageParams))) {
            Map<String, Object> d = SqlRows.as(FleetDefect.class, r);
            d.put("vehicle", Obj.of("id", r.get("vehicleId"), "code", r.get("v_code"), "registrationPlate",
                    r.get("v_plate"), "status", r.get("v_status")));
            d.put("workOrder", r.get("w_code") == null ? null : Obj.of("id", r.get("workOrderId"), "code",
                    r.get("w_code"), "status", r.get("w_status")));
            d.put("inspectionCode", r.get("i_code"));
            d.put("blocksDispatch", MaintenancePolicies.blocksDispatch((String) d.get("severity"),
                    (String) d.get("status")));
            rows.add(d);
        }
        return PageResult.of(rows, page, size, total);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> defect(String id) {
        Map<String, Object> d = db.require(FleetDefect.class, id, false, "Defect not found");
        Map<String, Object> out = new LinkedHashMap<>(d);
        out.put("workOrder", d.get("workOrderId") == null ? null : SqlRows.one(db.jdbc().sql("SELECT id, code, status"
                + " FROM \"FleetWorkOrder\" WHERE id = ? AND \"organizationId\" = ?").params(d.get("workOrderId"),
                FleetDb.org())));
        out.put("blocksDispatch", MaintenancePolicies.blocksDispatch((String) d.get("severity"),
                (String) d.get("status")));
        return out;
    }

    /** Report a defect directly (driver / mechanic). */
    @Transactional
    public Map<String, Object> report(DefectInput in) {
        units.require(in.vehicleId(), true, true);
        workOrders.assertEmployee(in.reportedByEmployeeId());
        String id = insertDefect(in.vehicleId(), null, null, in.title().trim(), trim(in.description()),
                in.severity(), trim(in.reportedByEmployeeId()), Instant.now());
        return defect(id);
    }

    /** Raise a corrective (inspection-defect) work order for an open defect, or attach it to a live one. */
    @Transactional
    public Map<String, Object> toWorkOrder(String id, DefectWorkOrderInput in) {
        Map<String, Object> d = db.require(FleetDefect.class, id, true, "Defect not found");
        MaintenancePolicies.assertDefectTransition((String) d.get("code"), (String) d.get("status"), "in_repair");
        if (in != null && notBlank(in.workOrderId())) {
            Map<String, Object> w = db.require(FleetWorkOrder.class, in.workOrderId(), true, "Work order not found");
            if (!MaintenancePolicies.LIVE.contains(w.get("status"))) {
                throw ApiException.badRequest("Work order " + w.get("code") + " is "
                        + MaintenancePolicies.label((String) w.get("status")) + "; choose a live work order");
            }
            workOrders.attachDefects(w, List.of(id));
        } else {
            String title = in != null && notBlank(in.title()) ? in.title().trim()
                    : "Defect " + d.get("code") + ": " + d.get("title");
            workOrders.createFor(new WorkOrderInput((String) d.get("vehicleId"), "inspection_defect",
                    MaintenancePolicies.priorityFor((String) d.get("severity")),
                    title.length() > 200 ? title.substring(0, 200) : title, (String) d.get("description"), null,
                    null, null, List.of(id), null, false), "defect");
        }
        return defect(id);
    }

    /** Close a defect without a work order: resolved on the spot, or dismissed (not a defect). */
    @Transactional
    public Map<String, Object> close(String id, DefectCloseInput in) {
        Map<String, Object> d = db.require(FleetDefect.class, id, true, "Defect not found");
        MaintenancePolicies.assertDefectTransition((String) d.get("code"), (String) d.get("status"), in.outcome());
        db.update(FleetDefect.class, id, Obj.of("status", in.outcome(), "resolvedAt", Instant.now(), "resolvedBy",
                TenantContext.userId(), "resolution", in.resolution().trim()));
        audit.recordInTx(AuditInput.of("FleetDefect", id, "update", Obj.of("status", d.get("status")),
                Obj.of("status", in.outcome(), "resolution", in.resolution().trim())));
        return defect(id);
    }

    private String insertDefect(String vehicleId, String inspectionId, String itemCode, String title,
            String description, String severity, String employeeId, Instant at) {
        String code = nextCode("fleet_defect", "DEF-", "FleetDefect");
        Map<String, Object> row = db.insert(FleetDefect.class, Obj.of(
                "code", code,
                "vehicleId", vehicleId,
                "inspectionId", inspectionId,
                "itemCode", itemCode,
                "title", title,
                "description", description,
                "severity", severity,
                "reportedByEmployeeId", employeeId,
                "reportedBy", TenantContext.userId(),
                "reportedAt", at));
        String id = (String) row.get("id");
        audit.recordInTx(AuditInput.of("FleetDefect", id, "create", null, row));
        events.publish(DomainEvents.FLEET_DEFECT_REPORTED, Obj.of("organizationId", FleetDb.org(), "defectId", id,
                "code", code, "vehicleId", vehicleId, "severity", severity, "blocksDispatch",
                MaintenancePolicies.blocksDispatch(severity, "open")));
        return id;
    }

    private String nextCode(String key, String prefix, String table) {
        for (int i = 0; i < 20; i++) {
            String code = sequence.next(key, prefix, 5);
            if (db.jdbc().sql("SELECT count(*) FROM \"" + table + "\" WHERE \"organizationId\" = ? AND code = ?")
                    .params(FleetDb.org(), code).query(Long.class).single() == 0) {
                return code;
            }
        }
        throw ApiException.conflict("Could not allocate a number");
    }

    static String name(Object first, Object last) {
        return first == null ? null : (first + (last == null ? "" : " " + last)).trim();
    }
}
