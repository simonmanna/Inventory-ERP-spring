package com.inventoryerp.repair.application;

import static com.inventoryerp.repair.application.RepairDb.dateOrNull;
import static com.inventoryerp.repair.application.RepairDb.nz;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.repair.domain.RepairSchedule;
import com.inventoryerp.repair.domain.RepairServiceContract;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Service contracts (SLA maintenance) and preventive maintenance schedules (port of
 * {@code repair-contract.service.ts}). A schedule drives {@link RepairCronWorker}, which generates preventive repair
 * orders.
 */
@Service
@ConditionalOnProperty(name = "app.modules.repair", havingValue = "true")
public class RepairContractService {

    private final RepairDb db;
    private final SequenceService seq;

    public RepairContractService(RepairDb db, SequenceService seq) {
        this.db = db;
        this.seq = seq;
    }

    // ─── contracts ───

    public List<Map<String, Object>> listContracts(Map<String, String> query) {
        return db.where(RepairServiceContract.class)
                .eqIf("status", query.get("status"))
                .eqIf("partnerId", query.get("partnerId"))
                .list("\"createdAt\" DESC", RepairDb.take(query.get("take"), 50, 200), RepairDb.skip(query.get("skip")));
    }

    public Map<String, Object> createContract(Map<String, Object> dtoIn) {
        Map<String, Object> dto = RepairDb.body(dtoIn);
        String contractNumber = seq.next("repair_contract", "CTR-", 6);
        return db.insert(RepairServiceContract.class, Obj.of("organizationId", RepairDb.org(),
                "contractNumber", contractNumber, "partnerId", dto.get("partnerId"), "title", dto.get("title"),
                "status", nz(dto.get("status"), "draft"), "startDate", dateOrNull(dto.get("startDate")),
                "endDate", dateOrNull(dto.get("endDate")), "frequencyDays", nz(dto.get("frequencyDays"), 30),
                "slaHours", dto.get("slaHours"), "includedLabour", nz(dto.get("includedLabour"), List.of()),
                "includedParts", nz(dto.get("includedParts"), List.of()), "notes", dto.get("notes"),
                "createdBy", RepairDb.user()));
    }

    public Map<String, Object> updateContract(String id, Map<String, Object> dto) {
        ensureExists(RepairServiceContract.class, id);
        Map<String, Object> data = new LinkedHashMap<>(RepairDb.body(dto));
        data.put("updatedBy", RepairDb.user());
        return db.update(RepairServiceContract.class, id, data);
    }

    public Map<String, Object> activateContract(String id) {
        ensureExists(RepairServiceContract.class, id);
        return db.update(RepairServiceContract.class, id, Obj.of("status", "active", "startDate", Instant.now()));
    }

    public Map<String, Object> cancelContract(String id, Map<String, Object> dtoIn) {
        Map<String, Object> dto = RepairDb.body(dtoIn);
        ensureExists(RepairServiceContract.class, id);
        Map<String, Object> data = Obj.of("status", "cancelled");
        if (dto.get("reason") != null) {
            data.put("notes", dto.get("reason"));
        }
        return db.update(RepairServiceContract.class, id, data);
    }

    public Map<String, Object> deleteContract(String id) {
        ensureExists(RepairServiceContract.class, id);
        return db.softDelete(RepairServiceContract.class, id);
    }

    // ─── preventive schedules ───

    public List<Map<String, Object>> listSchedules(Map<String, String> query) {
        return db.where(RepairSchedule.class)
                .eqIf("status", query.get("status"))
                .eqIf("contractId", query.get("contractId"))
                .list("\"nextDueAt\" ASC", RepairDb.take(query.get("take"), 100, 300), RepairDb.skip(query.get("skip")));
    }

    public Map<String, Object> createSchedule(Map<String, Object> dtoIn) {
        Map<String, Object> dto = RepairDb.body(dtoIn);
        Object intervalDays = nz(dto.get("intervalDays"), 30);
        Instant nextDueAt = com.inventoryerp.pos.application.PosSql.truthy(dto.get("nextDueAt"))
                ? RepairDb.jsDate(dto.get("nextDueAt")) : Instant.now();
        return db.insert(RepairSchedule.class, Obj.of("organizationId", RepairDb.org(),
                "contractId", dto.get("contractId"), "title", dto.get("title"), "assetRef", dto.get("assetRef"),
                "status", nz(dto.get("status"), "active"), "intervalDays", intervalDays, "nextDueAt", nextDueAt,
                "taskTemplate", dto.get("taskTemplate")));
    }

    public Map<String, Object> updateSchedule(String id, Map<String, Object> dto) {
        ensureExists(RepairSchedule.class, id);
        return db.update(RepairSchedule.class, id, RepairDb.body(dto));
    }

    public Map<String, Object> deleteSchedule(String id) {
        ensureExists(RepairSchedule.class, id);
        return db.softDelete(RepairSchedule.class, id);
    }

    /** Roll a schedule forward after a preventive run: {@code lastRunAt = now}, {@code nextDueAt = now + interval}. */
    public Map<String, Object> rollSchedule(String id) {
        Map<String, Object> schedule = db.byId(RepairSchedule.class, id);
        if (schedule == null) {
            throw ApiException.notFound("Repair schedule not found");
        }
        Instant now = Instant.now();
        return db.update(RepairSchedule.class, id, Obj.of("lastRunAt", now, "nextDueAt",
                addDays(now, ((Number) schedule.get("intervalDays")).intValue())));
    }

    /** Schedules due now (the cron worker's selection). */
    public List<Map<String, Object>> dueSchedules(Instant now) {
        return db.where(RepairSchedule.class).eq("status", "active").and("\"nextDueAt\" <= ?",
                com.inventoryerp.pos.application.PosDb.param(now)).list(null);
    }

    /** JS {@code d.setDate(d.getDate() + days)} on a UTC server clock. */
    static Instant addDays(Instant at, int days) {
        return at.atZone(ZoneOffset.UTC).plusDays(days).toInstant();
    }

    private void ensureExists(Class<?> entity, String id) {
        if (db.byId(entity, id) == null) {
            throw ApiException.notFound("Record not found");
        }
    }
}
