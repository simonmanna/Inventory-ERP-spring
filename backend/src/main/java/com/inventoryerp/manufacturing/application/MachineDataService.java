package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.MachineEventParser.Parsed;
import com.inventoryerp.manufacturing.application.MachineEventParser.RowError;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.PollInput;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.PushEvent;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.PushInput;
import com.inventoryerp.manufacturing.domain.ShopFloorDowntime;
import com.inventoryerp.manufacturing.domain.ShopFloorProductionReport;
import com.inventoryerp.manufacturing.domain.WorkOrder;
import com.inventoryerp.manufacturing.spi.MachineDataAdapter;
import com.inventoryerp.manufacturing.spi.MachineDataAdapter.MachineEvent;
import com.inventoryerp.manufacturing.spi.MachineDataAdapter.MachineRef;
import com.inventoryerp.manufacturing.spi.MachineDataAdapter.PollRequest;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Production data capture (ADR-S015, ADR-S011 §11): events from {@link MachineDataAdapter} beans (poll), a gateway /
 * MQTT bridge (push) or a CSV export are stored once per {@code (adapter, externalId)} and turned into drafts — a
 * count into a draft production report for the work order running at the machine's work centre, a stop into a draft
 * downtime. Nothing is posted until an operator confirms the draft on the terminal. Machines are matched by the
 * work-centre profile's machine code, else the work-centre code.
 */
@Service
public class MachineDataService {

    private final ShopFloorSupport sf;
    private final MfgDb db;
    private final List<MachineDataAdapter> adapters;

    public MachineDataService(ShopFloorSupport sf, List<MachineDataAdapter> adapters) {
        this.sf = sf;
        this.db = sf.db();
        this.adapters = adapters;
    }

    /** Pull adapters plus the push and CSV channels. */
    public List<Map<String, Object>> adapters() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MachineDataAdapter a : adapters) {
            out.add(Obj.of("code", a.code(), "configured", a.configured(), "pull", a.pull()));
        }
        out.add(Obj.of("code", "push", "configured", true, "pull", false));
        out.add(Obj.of("code", "csv", "configured", true, "pull", false));
        return out;
    }

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> events(String workCenterId, String status, String adapter, Integer page,
            Integer pageSize) {
        int p = page == null ? 1 : page;
        int size = pageSize == null ? 25 : pageSize;
        StringBuilder where = new StringBuilder(" WHERE e.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(MfgDb.org()));
        for (String[] f : new String[][] {{"workCenterId", workCenterId}, {"status", status}, {"adapter", adapter}}) {
            if (f[1] != null && !f[1].isBlank()) {
                where.append(" AND e.\"").append(f[0]).append("\" = ?");
                params.add(f[1]);
            }
        }
        long total = db.jdbc().sql("SELECT count(*) FROM \"ShopFloorMachineEvent\" e" + where).params(params)
                .query(Long.class).single();
        params.add(size);
        params.add((p - 1) * size);
        return PageResult.of(SqlRows.list(db.jdbc().sql("SELECT e.id, e.adapter, e.\"externalId\", e.\"machineCode\","
                + " e.\"workCenterId\", w.code AS \"workCenterCode\", e.\"eventType\", e.\"occurredAt\", e.\"endedAt\","
                + " e.\"goodQty\", e.\"scrapQty\", e.\"reasonCode\", e.status, e.\"draftType\", e.\"draftId\", e.error,"
                + " e.\"createdAt\" FROM \"ShopFloorMachineEvent\" e LEFT JOIN \"WorkCenter\" w ON w.id ="
                + " e.\"workCenterId\"" + where + " ORDER BY e.\"occurredAt\" DESC, e.\"createdAt\" DESC LIMIT ? OFFSET ?")
                .params(params)), p, size, total);
    }

    /** Poll a pull adapter for the given work centres (default: all active with a profile or code). */
    @Transactional
    public Map<String, Object> poll(PollInput in) {
        MachineDataAdapter adapter = adapters.stream().filter(a -> a.code().equals(in.adapter())).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Unknown machine-data adapter " + in.adapter() + "."));
        if (!adapter.pull()) {
            throw ApiException.badRequest("Adapter " + adapter.code() + " pushes its events; it cannot be polled.");
        }
        if (!adapter.configured()) {
            throw ApiException.badRequest("Adapter " + adapter.code() + " is not configured.");
        }
        Instant until = in.until() != null ? EntityPatch.toInstant(in.until()) : Instant.now();
        Instant since = in.since() != null ? EntityPatch.toInstant(in.since()) : until.minus(8, ChronoUnit.HOURS);
        if (!since.isBefore(until)) {
            throw ApiException.badRequest("since must be before until.");
        }
        List<MachineRef> machines = new ArrayList<>();
        for (Map<String, Object> m : machines(in.workCenterIds())) {
            machines.add(new MachineRef(str(m, "workCenterId"), str(m, "machineCode"),
                    m.get("idealRatePerHour") == null ? null : dec(m, "idealRatePerHour")));
        }
        if (machines.isEmpty()) {
            throw ApiException.badRequest("No work centre to poll.");
        }
        List<MachineEvent> events = adapter.poll(new PollRequest(machines, since, until));
        Map<String, Object> out = ingest(adapter.code(), events, new ArrayList<>());
        out.put("machines", machines.size());
        return out;
    }

    /** Events pushed by a gateway / MQTT bridge (adapter key {@code push} or {@code push:<source>}). */
    @Transactional
    public Map<String, Object> push(PushInput in) {
        String adapter = in.source() == null || in.source().isBlank() ? "push" : "push:" + in.source().trim();
        List<MachineEvent> events = new ArrayList<>();
        for (PushEvent e : in.events()) {
            events.add(new MachineEvent(e.externalId(), e.machineCode(), e.type(), EntityPatch.toInstant(e.occurredAt()),
                    e.endedAt() == null ? null : EntityPatch.toInstant(e.endedAt()), e.goodQty(), e.scrapQty(),
                    e.reasonCode(), e.notes()));
        }
        return ingest(adapter, events, new ArrayList<>());
    }

    /** A CSV export of a counter / PLC historian (see {@link MachineEventParser}). */
    @Transactional
    public Map<String, Object> csv(String content, String filename) {
        Parsed parsed = MachineEventParser.parseCsv(content);
        Map<String, Object> out = ingest("csv", parsed.events(), new ArrayList<>(parsed.errors()));
        out.put("filename", filename);
        return out;
    }

    /** Store each new event once and draft it; returns counts and row errors. */
    Map<String, Object> ingest(String adapter, List<MachineEvent> raw, List<RowError> errors) {
        Map<String, String> machineToWc = new HashMap<>();
        for (Map<String, Object> m : machines(null)) {
            machineToWc.put(str(m, "machineCode"), str(m, "workCenterId"));
        }
        int created = 0;
        int duplicates = 0;
        int drafted = 0;
        int ignored = 0;
        int failed = 0;
        for (int i = 0; i < raw.size(); i++) {
            MachineEvent e = MachineEventParser.normalise(raw.get(i));
            String problem = MachineEventParser.check(e);
            if (problem != null) {
                errors.add(new RowError(i + 1, problem));
                continue;
            }
            String wcId = machineToWc.get(e.machineCode());
            String eventId = UUID.randomUUID().toString();
            int inserted = db.jdbc().sql("INSERT INTO \"ShopFloorMachineEvent\" (id, \"organizationId\", adapter,"
                    + " \"externalId\", \"machineCode\", \"workCenterId\", \"eventType\", \"occurredAt\", \"endedAt\","
                    + " \"goodQty\", \"scrapQty\", \"reasonCode\", payload, status, \"createdAt\") VALUES (?, ?, ?, ?, ?,"
                    + " ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'ignored', " + MfgDb.NOW + ") ON CONFLICT (\"organizationId\","
                    + " adapter, \"externalId\") DO NOTHING").params(eventId, MfgDb.org(), adapter, e.externalId(),
                            e.machineCode(), wcId, e.type(), MfgDb.param(e.occurredAt()), MfgDb.param(e.endedAt()),
                            e.goodQty(), e.scrapQty(), e.reasonCode(), e.notes() == null ? null
                                    : com.inventoryerp.shared.web.EntityJson.toJson(Obj.of("notes", e.notes())))
                    .update();
            if (inserted == 0) {
                duplicates++;
                continue;
            }
            created++;
            String[] outcome;
            if (wcId == null) {
                outcome = new String[] {"error", null, null, "Unknown machine " + e.machineCode() + "."};
            } else {
                try {
                    outcome = draft(eventId, wcId, e);
                } catch (ApiException ex) {
                    outcome = new String[] {"error", null, null, ex.getMessage()};
                }
            }
            switch (outcome[0]) {
                case "drafted" -> drafted++;
                case "ignored" -> ignored++;
                default -> {
                    failed++;
                    errors.add(new RowError(i + 1, outcome[3]));
                }
            }
            db.jdbc().sql("UPDATE \"ShopFloorMachineEvent\" SET status = ?, \"draftType\" = ?, \"draftId\" = ?,"
                    + " error = ? WHERE id = ? AND \"organizationId\" = ?").params(outcome[0], outcome[1], outcome[2],
                            outcome[3], eventId, MfgDb.org()).update();
        }
        List<Map<String, Object>> errs = new ArrayList<>();
        for (RowError r : errors) {
            errs.add(Obj.of("row", r.row(), "message", r.message()));
        }
        return new LinkedHashMap<>(Obj.of("adapter", adapter, "received", raw.size(), "created", created,
                "duplicates", duplicates, "drafted", drafted, "ignored", ignored, "failed", failed, "errors", errs));
    }

    /** {@code {status, draftType, draftId, error}} of one stored event. */
    private String[] draft(String eventId, String wcId, MachineEvent e) {
        Instant now = Instant.now();
        switch (e.type()) {
            case "count" -> {
                BigDecimal good = e.goodQty() == null ? BigDecimal.ZERO : e.goodQty();
                BigDecimal scrap = e.scrapQty() == null ? BigDecimal.ZERO : e.scrapQty();
                if (good.signum() == 0 && scrap.signum() == 0) {
                    return new String[] {"ignored", null, null, "Zero count."};
                }
                Instant at = e.endedAt() != null ? e.endedAt() : e.occurredAt();
                Map<String, Object> report = sf.ensureReport(wcId, at);
                ShopFloorSupport.assertEditable(report);
                Map<String, Object> wo = runningWorkOrder(wcId);
                Map<String, Object> row = db.insert(ShopFloorProductionReport.class, Obj.of("workCenterId", wcId,
                        "workOrderId", wo == null ? null : wo.get("id"), "productionOrderId",
                        wo == null ? null : wo.get("productionOrderId"), "shiftReportId",
                        report == null ? null : report.get("id"), "qtyGood", good, "qtyScrap", scrap,
                        "scrapReasonCode", null, "completesStage", false, "periodStart", e.occurredAt(), "periodEnd",
                        e.endedAt(), "reportedAt", at, "source", "machine", "status", "draft", "machineEventId",
                        eventId, "notes", e.notes(), "recordedBy", TenantContext.userId()));
                return new String[] {"drafted", "production", str(row, "id"), null};
            }
            case "downtime", "downtime_start" -> {
                boolean open = "downtime_start".equals(e.type());
                Map<String, Object> report = sf.ensureReport(wcId, e.occurredAt());
                ShopFloorSupport.assertEditable(report);
                if (open) {
                    db.jdbc().sql("SELECT pg_advisory_xact_lock(hashtext(?))").param("sf-stop:" + wcId).query()
                            .listOfRows();
                    long already = db.jdbc().sql("SELECT count(*) FROM \"ShopFloorDowntime\" WHERE \"organizationId\""
                            + " = ? AND \"workCenterId\" = ? AND source = 'machine' AND \"endedAt\" IS NULL AND status"
                            + " <> 'rejected'").params(MfgDb.org(), wcId).query(Long.class).single();
                    if (already > 0) {
                        return new String[] {"ignored", null, null, "A machine stop is already open."};
                    }
                }
                Map<String, Object> reason = sf.reasonIfKnown(e.reasonCode()).orElse(null);
                Map<String, Object> wo = runningOrPausedWorkOrder(wcId);
                Map<String, Object> row = db.insert(ShopFloorDowntime.class, Obj.of("workCenterId", wcId,
                        "workOrderId", wo == null ? null : wo.get("id"), "shiftReportId",
                        report == null ? null : report.get("id"), "reasonId", reason == null ? null : reason.get("id"),
                        "reasonCode", e.reasonCode(), "isPlanned", reason != null && Boolean.TRUE.equals(
                                reason.get("isPlanned")), "startedAt", e.occurredAt(), "endedAt",
                        open ? null : e.endedAt(), "minutes", open ? null
                                : (int) ChronoUnit.MINUTES.between(e.occurredAt(), e.endedAt()),
                        "source", "machine", "status", "draft", "machineEventId", eventId, "notes", e.notes(),
                        "recordedBy", TenantContext.userId(), "createdAt", now));
                return new String[] {"drafted", "downtime", str(row, "id"), null};
            }
            default -> {
                Map<String, Object> stop = SqlRows.one(ShopFloorDowntime.class, db.jdbc().sql("SELECT * FROM"
                        + " \"ShopFloorDowntime\" WHERE \"organizationId\" = ? AND \"workCenterId\" = ? AND source ="
                        + " 'machine' AND \"endedAt\" IS NULL AND status <> 'rejected' ORDER BY \"startedAt\" DESC"
                        + " LIMIT 1 FOR UPDATE").params(MfgDb.org(), wcId));
                if (stop == null) {
                    return new String[] {"ignored", null, null, "No open machine stop to end."};
                }
                Instant start = (Instant) stop.get("startedAt");
                if (e.occurredAt().isBefore(start)) {
                    return new String[] {"error", null, null, "The stop ends before it started."};
                }
                db.update(ShopFloorDowntime.class, str(stop, "id"), Obj.of("endedAt", e.occurredAt(), "minutes",
                        (int) ChronoUnit.MINUTES.between(start, e.occurredAt())));
                return new String[] {"drafted", "downtime", str(stop, "id"), null};
            }
        }
    }

    /** The single in-progress work order of the centre (null when none or several). */
    private Map<String, Object> runningWorkOrder(String wcId) {
        List<Map<String, Object>> rows = db.list(WorkOrder.class, " AND \"workCenterId\" = ? AND status ="
                + " 'in_progress' LIMIT 2", wcId);
        return rows.size() == 1 ? rows.getFirst() : null;
    }

    private Map<String, Object> runningOrPausedWorkOrder(String wcId) {
        Map<String, Object> running = runningWorkOrder(wcId);
        if (running != null) {
            return running;
        }
        List<Map<String, Object>> rows = db.list(WorkOrder.class, " AND \"workCenterId\" = ? AND status = 'paused'"
                + " LIMIT 2", wcId);
        return rows.size() == 1 ? rows.getFirst() : null;
    }

    /** Active work centres with their machine code (profile, else the centre code) and ideal rate. */
    private List<Map<String, Object>> machines(List<String> workCenterIds) {
        List<Object> params = new ArrayList<>(List.of(MfgDb.org()));
        String only = "";
        if (workCenterIds != null && !workCenterIds.isEmpty()) {
            only = " AND w.id IN (" + String.join(", ", java.util.Collections.nCopies(workCenterIds.size(), "?")) + ")";
            params.addAll(workCenterIds);
        }
        return SqlRows.list(db.jdbc().sql("SELECT w.id AS \"workCenterId\", coalesce(p.\"machineCode\", w.code) AS"
                + " \"machineCode\", p.\"idealRatePerHour\" FROM \"WorkCenter\" w LEFT JOIN \"ShopFloorWorkCenterProfile\""
                + " p ON p.\"workCenterId\" = w.id AND p.\"organizationId\" = w.\"organizationId\" WHERE"
                + " w.\"organizationId\" = ? AND w.\"deletedAt\" IS NULL AND w.\"isActive\" = true" + only
                + " ORDER BY w.code").params(params));
    }
}
