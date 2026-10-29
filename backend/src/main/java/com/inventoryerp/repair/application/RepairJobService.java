package com.inventoryerp.repair.application;

import static com.inventoryerp.repair.application.RepairDb.dateOrNull;
import static com.inventoryerp.repair.application.RepairDb.nz;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.repair.domain.RepairJob;
import com.inventoryerp.repair.domain.RepairOrder;
import com.inventoryerp.repair.domain.RepairOrderItem;
import com.inventoryerp.repair.domain.RepairPart;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Internal work orders against a repair order (port of {@code repair-job.service.ts}): assignee, deadline, hours,
 * instructions and a checklist snapshot. Completed and cancelled jobs accept no further status change.
 */
@Service
@ConditionalOnProperty(name = "app.modules.repair", havingValue = "true")
public class RepairJobService {

    private final RepairDb db;
    private final SequenceService seq;

    public RepairJobService(RepairDb db, SequenceService seq) {
        this.db = db;
        this.seq = seq;
    }

    /** Jobs newest first, each with {@code repairOrder: {repairNumber, itemType, brand, model}}. */
    public List<Map<String, Object>> listJobs(Map<String, String> query) {
        List<Map<String, Object>> rows = db.where(RepairJob.class)
                .eqIf("status", query.get("status"))
                .eqIf("repairOrderId", query.get("repairOrderId"))
                .eqIf("technicianId", query.get("technicianId"))
                .list("\"createdAt\" DESC", RepairDb.take(query.get("take"), 50, 200),
                        RepairDb.skip(query.get("skip")));
        for (Map<String, Object> r : rows) {
            Map<String, Object> o = db.db().first(RepairOrder.class, "id = ?", r.get("repairOrderId"));
            Map<String, Object> brief = null;
            if (o != null) {
                brief = new LinkedHashMap<>();
                for (String k : List.of("repairNumber", "itemType", "brand", "model")) {
                    brief.put(k, o.get(k));
                }
            }
            r.put("repairOrder", brief);
        }
        return rows;
    }

    /** The job with its order, that order's parts for this job and its items. */
    public Map<String, Object> getJob(String id) {
        Map<String, Object> job = db.byId(RepairJob.class, id);
        if (job == null) {
            throw ApiException.notFound("Repair job not found");
        }
        Map<String, Object> order = db.db().first(RepairOrder.class, "id = ?", job.get("repairOrderId"));
        if (order != null) {
            order.put("parts", db.db().list(RepairPart.class, "\"repairOrderId\" = ? AND \"jobId\" = ?"
                    + " ORDER BY \"createdAt\" DESC", order.get("id"), id));
            order.put("items", db.many(RepairOrderItem.class, "repairOrderId", order.get("id"), "\"lineNumber\" ASC"));
        }
        job.put("repairOrder", order);
        return job;
    }

    public Map<String, Object> createJob(String repairOrderId, Map<String, Object> dtoIn) {
        Map<String, Object> dto = RepairDb.body(dtoIn);
        String orgId = RepairDb.org();
        Map<String, Object> order = db.byId(RepairOrder.class, repairOrderId);
        if (order == null) {
            throw ApiException.notFound("Repair order not found");
        }
        String jobNumber = seq.next("repair_job", "JOB-", 6);
        Map<String, Object> job = db.insert(RepairJob.class, Obj.of("organizationId", orgId,
                "repairOrderId", repairOrderId, "jobNumber", jobNumber, "technicianId", dto.get("technicianId"),
                "priority", nz(dto.get("priority"), "medium"), "deadline", dateOrNull(dto.get("deadline")),
                "estimatedHours", dto.get("estimatedHours"), "instructions", dto.get("instructions"),
                "checklist", nz(dto.get("checklist"), List.of()), "notes", dto.get("notes"),
                "createdBy", RepairDb.user()));
        db.notify(orgId, "Job " + jobNumber + " created",
                RepairDb.s(nz(order.get("itemType"), "Item")) + " — "
                        + RepairDb.s(nz(dto.get("instructions"), "no instructions")),
                Obj.of("repairOrderId", repairOrderId, "jobId", job.get("id"), "jobNumber", jobNumber));
        return getJob((String) job.get("id"));
    }

    public Map<String, Object> startJob(String id) {
        return setStatus(id, "in_progress", Obj.of("startedAt", Instant.now()));
    }

    public Map<String, Object> completeJob(String id, Map<String, Object> dtoIn) {
        Map<String, Object> dto = RepairDb.body(dtoIn);
        Map<String, Object> extra = Obj.of("completedAt", Instant.now());
        if (dto.get("actualHours") != null) {
            extra.put("actualHours", dto.get("actualHours"));
        }
        if (dto.get("checklist") != null) {
            extra.put("checklist", dto.get("checklist"));
        }
        return setStatus(id, "completed", extra);
    }

    public Map<String, Object> testJob(String id) {
        return setStatus(id, "testing", Map.of());
    }

    public Map<String, Object> cancelJob(String id, Map<String, Object> dtoIn) {
        Map<String, Object> dto = RepairDb.body(dtoIn);
        Map<String, Object> extra = Obj.of("cancelledAt", Instant.now());
        if (dto.get("reason") != null) {
            extra.put("notes", dto.get("reason"));
        }
        return setStatus(id, "cancelled", extra);
    }

    private Map<String, Object> setStatus(String id, String status, Map<String, Object> extra) {
        Map<String, Object> job = db.byId(RepairJob.class, id);
        if (job == null) {
            throw ApiException.notFound("Repair job not found");
        }
        Object current = job.get("status");
        if ("completed".equals(current) || "cancelled".equals(current)) {
            throw ApiException.badRequest("Job is " + current + " — no further transitions");
        }
        Map<String, Object> data = Obj.of("status", status, "updatedBy", RepairDb.user());
        data.putAll(extra);
        return db.update(RepairJob.class, id, data);
    }

    public Map<String, Object> updateJob(String id, Map<String, Object> dto) {
        if (db.byId(RepairJob.class, id) == null) {
            throw ApiException.notFound("Repair job not found");
        }
        return db.update(RepairJob.class, id, db.spread(RepairJob.class, dto));
    }

    /** Toggle one checklist item ({@code {label, completed}}) inside the job's checklist JSON. */
    public Map<String, Object> setChecklistItem(String id, Map<String, Object> dtoIn) {
        Map<String, Object> dto = RepairDb.body(dtoIn);
        Map<String, Object> job = db.byId(RepairJob.class, id);
        if (job == null) {
            throw ApiException.notFound("Repair job not found");
        }
        Object stored = PosSql.java(job.get("checklist"));
        List<Object> checklist = stored instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>();
        Object label = dto.get("label");
        Object completed = dto.get("completed");
        Object completedAt = PosSql.truthy(completed) ? RepairDb.isoNow() : null;
        Map<String, Object> existing = null;
        for (Object c : checklist) {
            if (c instanceof Map<?, ?> m && java.util.Objects.equals(m.get("label"), label)) {
                existing = PosSql.map(c);
                break;
            }
        }
        if (existing != null) {
            put(existing, dto, "completed");
            existing.put("completedAt", completedAt);
        } else {
            Map<String, Object> item = new LinkedHashMap<>();
            put(item, dto, "label");
            put(item, dto, "completed");
            item.put("completedAt", completedAt);
            checklist.add(item);
        }
        return db.update(RepairJob.class, id, Obj.of("checklist", checklist, "updatedBy", RepairDb.user()));
    }

    /** {@code m[key] = dto[key]}: an absent ({@code undefined}) value is dropped by JSON, an explicit null kept. */
    private static void put(Map<String, Object> m, Map<String, Object> dto, String key) {
        if (dto.containsKey(key)) {
            m.put(key, dto.get(key));
        } else {
            m.remove(key);
        }
    }
}
