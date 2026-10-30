package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.ShiftClock.Stop;
import com.inventoryerp.manufacturing.application.ShiftClock.Window;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.OpenShiftReportInput;
import com.inventoryerp.manufacturing.domain.ShopFloorShiftReport;
import com.inventoryerp.manufacturing.domain.WorkCenter;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shift reports, OEE and the live shop-floor board (ADR-S015). A shift report collects a work centre's production
 * reports, downtime, clock entries and meter readings of one shift; the shift lead submits it (no machine drafts may
 * be pending), a supervisor other than the submitter approves it (locked) or rejects it (open again). OEE per shift
 * = {@link OeeCalculator} over the shift window (elapsed part while it runs): planned time = window − pro-rated
 * break − planned stops, unplanned stops = availability loss, counts from the posted production reports, ideal rate
 * from the work-centre profile. Submission and approval store an OEE snapshot.
 */
@Service
public class ShopFloorReportService {

    private final ShopFloorSupport sf;
    private final MfgDb db;
    private final AuditService audit;

    public ShopFloorReportService(ShopFloorSupport sf, AuditService audit) {
        this.sf = sf;
        this.db = sf.db();
        this.audit = audit;
    }

    private static final String SELECT = "SELECT r.*, w.code AS \"workCenterCode\", w.name AS \"workCenterName\","
            + " s.code AS \"shiftCode\", s.name AS \"shiftName\" FROM \"ShopFloorShiftReport\" r"
            + " JOIN \"WorkCenter\" w ON w.id = r.\"workCenterId\" JOIN \"ShopFloorShift\" s ON s.id = r.\"shiftId\"";

    // ================================================================== list / detail

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(String workCenterId, String status, String from, String to,
            Integer page, Integer pageSize) {
        int p = page == null ? 1 : page;
        int size = pageSize == null ? 25 : pageSize;
        StringBuilder where = new StringBuilder(" WHERE r.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(MfgDb.org()));
        if (workCenterId != null && !workCenterId.isBlank()) {
            where.append(" AND r.\"workCenterId\" = ?");
            params.add(workCenterId);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND r.status = ?");
            params.add(status);
        }
        if (from != null && !from.isBlank()) {
            where.append(" AND r.\"shiftDate\" >= ?");
            params.add(java.sql.Date.valueOf(ShopFloorSupport.date(from)));
        }
        if (to != null && !to.isBlank()) {
            where.append(" AND r.\"shiftDate\" <= ?");
            params.add(java.sql.Date.valueOf(ShopFloorSupport.date(to)));
        }
        long total = db.jdbc().sql("SELECT count(*) FROM \"ShopFloorShiftReport\" r" + where).params(params)
                .query(Long.class).single();
        params.add(size);
        params.add((p - 1) * size);
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql(SELECT + where + " ORDER BY r.\"startsAt\" DESC,"
                + " w.code LIMIT ? OFFSET ?").params(params));
        Instant now = Instant.now();
        for (Map<String, Object> r : rows) {
            r.put("oee", oeeView(r, now));
            r.put("pendingDrafts", pendingDrafts(r));
        }
        return PageResult.of(rows, p, size, total);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> r = row(id);
        Instant now = Instant.now();
        r.put("oee", oeeView(r, now));
        r.put("liveOee", toMap(oee(r, now)));
        r.put("pendingDrafts", pendingDrafts(r));
        r.put("productionReports", SqlRows.list(db.jdbc().sql(ShopFloorExecutionService.PRODUCTION_SELECT
                + " WHERE pr.\"organizationId\" = ? AND pr.\"shiftReportId\" = ? ORDER BY pr.\"reportedAt\"")
                .params(MfgDb.org(), id)));
        r.put("downtime", SqlRows.list(db.jdbc().sql(ShopFloorExecutionService.DOWNTIME_SELECT
                + " WHERE d.\"organizationId\" = ? AND d.\"workCenterId\" = ? AND d.\"startedAt\" < ? AND"
                + " (d.\"endedAt\" IS NULL OR d.\"endedAt\" > ?) ORDER BY d.\"startedAt\"").params(MfgDb.org(),
                        r.get("workCenterId"), MfgDb.param(r.get("endsAt")), MfgDb.param(r.get("startsAt")))));
        r.put("clockEntries", SqlRows.list(db.jdbc().sql(ShopFloorExecutionService.CLOCK_SELECT
                + " WHERE c.\"organizationId\" = ? AND c.\"workCenterId\" = ? AND c.\"clockOnAt\" < ? AND"
                + " (c.\"clockOffAt\" IS NULL OR c.\"clockOffAt\" > ?) ORDER BY c.\"clockOnAt\"").params(MfgDb.org(),
                        r.get("workCenterId"), MfgDb.param(r.get("endsAt")), MfgDb.param(r.get("startsAt")))));
        r.put("meterReadings", SqlRows.list(db.jdbc().sql("SELECT * FROM \"ShopFloorMeterReading\" WHERE"
                + " \"organizationId\" = ? AND \"shiftReportId\" = ? ORDER BY \"meterCode\", \"readAt\"")
                .params(MfgDb.org(), id)));
        r.put("submittedByUser", sf.user(str(r, "submittedBy")));
        r.put("approvedByUser", sf.user(str(r, "approvedBy")));
        return r;
    }

    /** Open (or return) the report of a work centre, shift and date explicitly. */
    @Transactional
    public Map<String, Object> open(OpenShiftReportInput in) {
        sf.requireWorkCenter(in.workCenterId());
        Map<String, Object> shift = sf.requireShift(in.shiftId());
        if (!Boolean.TRUE.equals(shift.get("isActive"))) {
            throw ApiException.badRequest("Shift " + shift.get("code") + " is inactive.");
        }
        LocalDate day = ShopFloorSupport.date(in.shiftDate());
        Window w = ShiftClock.window(ShopFloorSupport.def(shift), day, sf.zone());
        return get(str(sf.ensureReport(in.workCenterId(), w), "id"));
    }

    // ================================================================== workflow

    @Transactional
    public Map<String, Object> submit(String id, String notes) {
        Map<String, Object> r = sf.lock(ShopFloorShiftReport.class, id, "Shift report not found");
        if (!"open".equals(r.get("status"))) {
            throw ApiException.badRequest("Only an open shift report can be submitted (status " + r.get("status")
                    + ").");
        }
        long drafts = pendingDrafts(r);
        if (drafts > 0) {
            throw ApiException.badRequest("Confirm or reject the " + drafts + " machine draft(s) of this shift first.");
        }
        Map<String, Object> data = snapshot(r);
        data.put("status", "submitted");
        data.put("submittedAt", Instant.now());
        data.put("submittedBy", TenantContext.userId());
        if (notes != null && !notes.isBlank()) {
            data.put("notes", notes.trim());
        }
        db.update(ShopFloorShiftReport.class, id, data);
        audit.recordInTx(AuditInput.of("ShopFloorShiftReport", id, "update", null, Obj.of("code", r.get("code"),
                "status", "submitted")));
        return get(id);
    }

    @Transactional
    public Map<String, Object> approve(String id, String notes) {
        Map<String, Object> r = sf.lock(ShopFloorShiftReport.class, id, "Shift report not found");
        if (!"submitted".equals(r.get("status"))) {
            throw ApiException.badRequest("Only a submitted shift report can be approved (status " + r.get("status")
                    + ").");
        }
        if (TenantContext.userId() != null && TenantContext.userId().equals(r.get("submittedBy"))) {
            throw ApiException.badRequest("The supervisor who approves a shift report must not be the one who"
                    + " submitted it.");
        }
        Map<String, Object> data = snapshot(r);
        data.put("status", "approved");
        data.put("approvedAt", Instant.now());
        data.put("approvedBy", TenantContext.userId());
        data.put("approvalNotes", notes == null || notes.isBlank() ? null : notes.trim());
        db.update(ShopFloorShiftReport.class, id, data);
        audit.recordInTx(AuditInput.of("ShopFloorShiftReport", id, "approve", null, Obj.of("code", r.get("code"),
                "oee", data.get("oee"))));
        return get(id);
    }

    @Transactional
    public Map<String, Object> reject(String id, String reason) {
        Map<String, Object> r = sf.lock(ShopFloorShiftReport.class, id, "Shift report not found");
        if (!"submitted".equals(r.get("status"))) {
            throw ApiException.badRequest("Only a submitted shift report can be rejected (status " + r.get("status")
                    + ").");
        }
        db.update(ShopFloorShiftReport.class, id, Obj.of("status", "open", "rejectedAt", Instant.now(), "rejectedBy",
                TenantContext.userId(), "rejectionReason", reason.trim(), "submittedAt", null, "submittedBy", null));
        audit.recordInTx(AuditInput.of("ShopFloorShiftReport", id, "reject", null, Obj.of("code", r.get("code"),
                "reason", reason.trim())));
        return get(id);
    }

    // ================================================================== OEE

    /** OEE per shift report in a date range (optionally one work centre), with totals per work centre and overall. */
    @Transactional(readOnly = true)
    public Map<String, Object> oeeReport(String from, String to, String workCenterId) {
        LocalDate t = to != null && !to.isBlank() ? ShopFloorSupport.date(to) : LocalDate.now(sf.zone());
        LocalDate f = from != null && !from.isBlank() ? ShopFloorSupport.date(from) : t.minusDays(6);
        if (f.isAfter(t)) {
            throw ApiException.badRequest("from must not be after to.");
        }
        if (ChronoUnit.DAYS.between(f, t) > 92) {
            throw ApiException.badRequest("The OEE report covers at most 93 days.");
        }
        List<Object> params = new ArrayList<>(List.of(MfgDb.org(), java.sql.Date.valueOf(f), java.sql.Date.valueOf(t)));
        String wc = "";
        if (workCenterId != null && !workCenterId.isBlank()) {
            wc = " AND r.\"workCenterId\" = ?";
            params.add(workCenterId);
        }
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql(SELECT + " WHERE r.\"organizationId\" = ? AND"
                + " r.\"shiftDate\" BETWEEN ? AND ?" + wc + " ORDER BY w.code, r.\"startsAt\"").params(params));
        Instant now = Instant.now();
        Map<String, Agg> byWc = new LinkedHashMap<>();
        Agg all = new Agg();
        List<Map<String, Object>> shifts = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            OeeCalculator.Result res = effective(r, now);
            Map<String, Object> line = Obj.of("shiftReportId", r.get("id"), "code", r.get("code"), "workCenterId",
                    r.get("workCenterId"), "workCenterCode", r.get("workCenterCode"), "workCenterName",
                    r.get("workCenterName"), "shiftCode", r.get("shiftCode"), "shiftDate", r.get("shiftDate"),
                    "status", r.get("status"));
            line.putAll(toMap(res));
            shifts.add(line);
            Agg a = byWc.computeIfAbsent(str(r, "workCenterId"), k -> new Agg());
            a.code = str(r, "workCenterCode");
            a.name = str(r, "workCenterName");
            a.add(res);
            all.add(res);
        }
        List<Map<String, Object>> centres = new ArrayList<>();
        byWc.forEach((id, a) -> {
            Map<String, Object> m = Obj.of("workCenterId", id, "workCenterCode", a.code, "workCenterName", a.name,
                    "shifts", a.count);
            m.putAll(toMap(a.result()));
            centres.add(m);
        });
        Map<String, Object> total = Obj.of("shifts", all.count);
        total.putAll(toMap(all.result()));
        return Obj.of("from", f, "to", t, "workCenters", centres, "shifts", shifts, "total", total);
    }

    /** Summed minutes / counts / ideal minutes of several shifts. */
    private static final class Agg {
        String code;
        String name;
        int count;
        long planned;
        long down;
        BigDecimal good = BigDecimal.ZERO;
        BigDecimal scrap = BigDecimal.ZERO;
        BigDecimal ideal = BigDecimal.ZERO;
        boolean idealMissing;

        void add(OeeCalculator.Result r) {
            count++;
            planned += r.plannedMinutes();
            down += r.downtimeMinutes();
            good = good.add(r.goodQty());
            scrap = scrap.add(r.scrapQty());
            if (r.idealMinutes() == null) {
                idealMissing = idealMissing || r.totalQty().signum() > 0;
            } else {
                ideal = ideal.add(r.idealMinutes());
            }
        }

        OeeCalculator.Result result() {
            return OeeCalculator.compute(planned, down, idealMissing ? null : ideal, good, scrap);
        }
    }

    /** Snapshot for submitted / approved reports, live otherwise. */
    OeeCalculator.Result effective(Map<String, Object> r, Instant now) {
        if ("open".equals(r.get("status")) || r.get("plannedMinutes") == null) {
            return oee(r, now);
        }
        BigDecimal good = dec(r, "goodQty");
        BigDecimal scrap = dec(r, "scrapQty");
        Map<String, Object> profile = sf.profile(str(r, "workCenterId"));
        BigDecimal rate = profile == null || profile.get("idealRatePerHour") == null ? null
                : dec(profile, "idealRatePerHour");
        return OeeCalculator.compute(((Number) r.get("plannedMinutes")).longValue(),
                ((Number) r.get("downtimeMinutes")).longValue(), OeeCalculator.idealMinutes(good.add(scrap), rate), good,
                scrap);
    }

    private Map<String, Object> oeeView(Map<String, Object> r, Instant now) {
        return toMap(effective(r, now));
    }

    /** Live OEE of a shift report: the elapsed part of its window while it runs. */
    OeeCalculator.Result oee(Map<String, Object> r, Instant now) {
        Instant ws = (Instant) r.get("startsAt");
        Instant end = (Instant) r.get("endsAt");
        Instant we = now.isBefore(end) ? now : end;
        BigDecimal[] counts = counts(str(r, "id"));
        Map<String, Object> profile = sf.profile(str(r, "workCenterId"));
        BigDecimal rate = profile == null || profile.get("idealRatePerHour") == null ? null
                : dec(profile, "idealRatePerHour");
        BigDecimal ideal = OeeCalculator.idealMinutes(counts[0].add(counts[1]), rate);
        if (!we.isAfter(ws)) {
            return OeeCalculator.compute(0, 0, ideal, counts[0], counts[1]);
        }
        long elapsed = ChronoUnit.MINUTES.between(ws, we);
        long length = Math.max(1, ChronoUnit.MINUTES.between(ws, end));
        Map<String, Object> shift = db.byId(com.inventoryerp.manufacturing.domain.ShopFloorShift.class,
                str(r, "shiftId"));
        long breakMins = shift == null ? 0 : Math.round(((Number) shift.get("breakMinutes")).doubleValue() * elapsed
                / length);
        List<Stop> stops = new ArrayList<>();
        for (Map<String, Object> d : SqlRows.list(db.jdbc().sql("SELECT \"startedAt\", \"endedAt\", \"isPlanned\" FROM"
                + " \"ShopFloorDowntime\" WHERE \"organizationId\" = ? AND \"workCenterId\" = ? AND status = 'confirmed'"
                + " AND \"startedAt\" < ? AND (\"endedAt\" IS NULL OR \"endedAt\" > ?)").params(MfgDb.org(),
                        r.get("workCenterId"), MfgDb.param(we), MfgDb.param(ws)))) {
            Instant until = d.get("endedAt") == null ? now : (Instant) d.get("endedAt");
            stops.add(new Stop((Instant) d.get("startedAt"), until, Boolean.TRUE.equals(d.get("isPlanned"))));
        }
        long[] sm = ShiftClock.stopMinutes(stops, ws, we);
        long planned = Math.max(0, elapsed - breakMins - sm[0]);
        return OeeCalculator.compute(planned, sm[1], ideal, counts[0], counts[1]);
    }

    /** {@code {good, scrap}} of the posted production reports of a shift report. */
    private BigDecimal[] counts(String reportId) {
        Map<String, Object> c = SqlRows.one(db.jdbc().sql("SELECT coalesce(sum(\"qtyGood\"), 0) AS good,"
                + " coalesce(sum(\"qtyScrap\"), 0) AS scrap FROM \"ShopFloorProductionReport\" WHERE \"organizationId\""
                + " = ? AND \"shiftReportId\" = ? AND status = 'posted'").params(MfgDb.org(), reportId));
        return new BigDecimal[] {dec(c, "good"), dec(c, "scrap")};
    }

    private Map<String, Object> snapshot(Map<String, Object> r) {
        OeeCalculator.Result res = oee(r, Instant.now());
        Instant ws = (Instant) r.get("startsAt");
        Instant end = (Instant) r.get("endsAt");
        Instant now = Instant.now();
        long elapsed = Math.max(0, ChronoUnit.MINUTES.between(ws, now.isBefore(end) ? now : end));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("plannedMinutes", (int) res.plannedMinutes());
        data.put("plannedDowntimeMinutes", (int) Math.max(0, elapsed - res.plannedMinutes()));
        data.put("downtimeMinutes", (int) res.downtimeMinutes());
        data.put("runMinutes", (int) res.runMinutes());
        data.put("goodQty", res.goodQty());
        data.put("scrapQty", res.scrapQty());
        data.put("availability", res.availability());
        data.put("performance", res.performance());
        data.put("quality", res.quality());
        data.put("oee", res.oee());
        return data;
    }

    static Map<String, Object> toMap(OeeCalculator.Result r) {
        return Obj.of("plannedMinutes", r.plannedMinutes(), "downtimeMinutes", r.downtimeMinutes(), "runMinutes",
                r.runMinutes(), "goodQty", r.goodQty(), "scrapQty", r.scrapQty(), "totalQty", r.totalQty(),
                "idealMinutes", r.idealMinutes(), "availability", r.availability(), "performance", r.performance(),
                "quality", r.quality(), "oee", r.oee(), "performanceCapped", r.performanceCapped());
    }

    /** Machine drafts (production or downtime) of the shift still waiting for an operator. */
    long pendingDrafts(Map<String, Object> r) {
        long production = db.jdbc().sql("SELECT count(*) FROM \"ShopFloorProductionReport\" WHERE \"organizationId\" = ?"
                + " AND \"shiftReportId\" = ? AND status = 'draft'").params(MfgDb.org(), r.get("id"))
                .query(Long.class).single();
        long downtime = db.jdbc().sql("SELECT count(*) FROM \"ShopFloorDowntime\" WHERE \"organizationId\" = ? AND"
                + " \"workCenterId\" = ? AND status = 'draft' AND \"startedAt\" < ? AND \"startedAt\" >= ?")
                .params(MfgDb.org(), r.get("workCenterId"), MfgDb.param(r.get("endsAt")), MfgDb.param(r.get("startsAt")))
                .query(Long.class).single();
        return production + downtime;
    }

    private Map<String, Object> row(String id) {
        Map<String, Object> r = SqlRows.one(db.jdbc().sql(SELECT + " WHERE r.id = ? AND r.\"organizationId\" = ?")
                .params(id, MfgDb.org()));
        if (r == null) {
            throw ApiException.notFound("Shift report not found");
        }
        return r;
    }

    // ================================================================== live board

    /** Live status per active work centre: running / paused / down / idle, operators, current shift OEE, drafts. */
    @Transactional(readOnly = true)
    public Map<String, Object> dashboard() {
        Instant now = Instant.now();
        Optional<Window> window = sf.window(now);
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Integer> totals = new LinkedHashMap<>(Map.of("running", 0, "paused", 0, "down", 0, "idle", 0));
        for (Map<String, Object> wc : db.list(WorkCenter.class, " AND \"isActive\" = true ORDER BY code")) {
            String wcId = str(wc, "id");
            Map<String, Object> card = Obj.of("workCenterId", wcId, "code", wc.get("code"), "name", wc.get("name"));
            List<Map<String, Object>> active = SqlRows.list(db.jdbc().sql("SELECT w.id, w.name, w.status,"
                    + " w.\"startedAt\", w.\"pausedAt\", o.id AS \"productionOrderId\", o.\"orderCode\", p.name AS"
                    + " \"productName\" FROM \"WorkOrder\" w JOIN \"ProductionOrder\" o ON o.id = w.\"productionOrderId\""
                    + " LEFT JOIN \"Product\" p ON p.id = o.\"outputProductId\" WHERE w.\"organizationId\" = ? AND"
                    + " w.\"workCenterId\" = ? AND w.status IN ('in_progress', 'paused') ORDER BY w.status,"
                    + " w.\"startedAt\"").params(MfgDb.org(), wcId));
            Map<String, Object> stop = SqlRows.one(db.jdbc().sql(ShopFloorExecutionService.DOWNTIME_SELECT
                    + " WHERE d.\"organizationId\" = ? AND d.\"workCenterId\" = ? AND d.\"endedAt\" IS NULL AND"
                    + " d.status = 'confirmed' ORDER BY d.\"startedAt\" LIMIT 1").params(MfgDb.org(), wcId));
            boolean machineStop = db.jdbc().sql("SELECT count(*) FROM \"ShopFloorDowntime\" WHERE \"organizationId\""
                    + " = ? AND \"workCenterId\" = ? AND \"endedAt\" IS NULL AND status = 'draft'")
                    .params(MfgDb.org(), wcId).query(Long.class).single() > 0;
            String status;
            if (stop != null) {
                status = "down";
            } else if (active.stream().anyMatch(w -> "in_progress".equals(w.get("status")))) {
                status = "running";
            } else if (!active.isEmpty()) {
                status = "paused";
            } else {
                status = "idle";
            }
            totals.merge(status, 1, Integer::sum);
            card.put("status", status);
            card.put("workOrders", active);
            card.put("openDowntime", stop);
            card.put("machineStopUnconfirmed", machineStop);
            card.put("operators", SqlRows.list(db.jdbc().sql(ShopFloorExecutionService.CLOCK_SELECT
                    + " WHERE c.\"organizationId\" = ? AND c.\"workCenterId\" = ? AND c.\"clockOffAt\" IS NULL"
                    + " ORDER BY c.\"clockOnAt\"").params(MfgDb.org(), wcId)));
            card.put("pendingDrafts", db.jdbc().sql("SELECT (SELECT count(*) FROM \"ShopFloorProductionReport\""
                    + " WHERE \"organizationId\" = ? AND \"workCenterId\" = ? AND status = 'draft') + (SELECT count(*)"
                    + " FROM \"ShopFloorDowntime\" WHERE \"organizationId\" = ? AND \"workCenterId\" = ? AND status ="
                    + " 'draft')").params(MfgDb.org(), wcId, MfgDb.org(), wcId).query(Long.class).single());
            card.put("lastMachineEventAt", SqlRows.one(db.jdbc().sql("SELECT max(\"occurredAt\") AS at FROM"
                    + " \"ShopFloorMachineEvent\" WHERE \"organizationId\" = ? AND \"workCenterId\" = ?")
                    .params(MfgDb.org(), wcId)).get("at"));
            Map<String, Object> shift = null;
            if (window.isPresent()) {
                Map<String, Object> report = sf.findReport(wcId, now);
                shift = Obj.of("shiftId", window.get().shiftId(), "shiftDate", window.get().shiftDate(), "startsAt",
                        window.get().startsAt(), "endsAt", window.get().endsAt(), "shiftReportId",
                        report == null ? null : report.get("id"), "code", report == null ? null : report.get("code"),
                        "status", report == null ? null : report.get("status"), "oee",
                        report == null ? null : toMap(oee(report, now)));
            }
            card.put("shift", shift);
            out.add(card);
        }
        return Obj.of("asOf", now, "currentShift", window.map(w -> Obj.of("shiftId", w.shiftId(), "shiftDate",
                w.shiftDate(), "startsAt", w.startsAt(), "endsAt", w.endsAt())).orElse(null), "totals", totals,
                "workCenters", out);
    }
}
