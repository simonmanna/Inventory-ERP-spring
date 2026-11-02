package com.inventoryerp.hr.application;

import static com.inventoryerp.hr.application.HrRows.dec;
import static com.inventoryerp.hr.application.HrRows.org;
import static com.inventoryerp.hr.application.HrRows.str;
import static com.inventoryerp.hr.application.HrRows.user;

import com.inventoryerp.hr.domain.HrPerformanceReview;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Talent (HP-9, ADR-S048): competencies, review cycles, weighted goals and appraisals (self, manager, peer). The
 * manager appraisal's overall score = goal score × goal weight + competency score × (1 − goal weight); closing a cycle
 * records each employee's result as a Nest performance review (rating = rounded overall score).
 */
@Service
public class HrTalentService {

    static final MathContext MC = MathContext.DECIMAL64;

    private final HrRows rows;
    private final HrDb hr;

    public HrTalentService(HrRows rows, HrDb hr) {
        this.rows = rows;
        this.hr = hr;
    }

    // ------------------------------------------------------------------ competencies

    public Map<String, Object> listCompetencies() {
        List<Map<String, Object>> list = rows.list("HrCompetency", null, "code");
        return HrRows.page(list, list.size());
    }

    public Map<String, Object> saveCompetency(String id, Map<String, Object> dto) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (id == null || dto.containsKey("code")) {
            String code = str(dto.get("code"));
            if (code == null) {
                throw ApiException.badRequest("code is required");
            }
            code = code.toUpperCase();
            if (rows.first("HrCompetency", "code = ? AND id <> ?", null, code, id == null ? "" : id) != null) {
                throw ApiException.badRequest("Competency \"" + code + "\" already exists");
            }
            data.put("code", code);
        }
        if (id == null || dto.containsKey("name")) {
            String name = str(dto.get("name"));
            if (name == null) {
                throw ApiException.badRequest("name is required");
            }
            data.put("name", name);
        }
        if (dto.containsKey("description")) {
            data.put("description", str(dto.get("description")));
        }
        if (dto.containsKey("isActive")) {
            data.put("isActive", Boolean.TRUE.equals(dto.get("isActive")));
        }
        return id == null ? rows.insert("HrCompetency", data) : rows.update("HrCompetency", id, data);
    }

    public Map<String, Object> deleteCompetency(String id) {
        rows.require("HrCompetency", id, "Competency not found");
        rows.softDelete("HrCompetency", id);
        return Map.of("id", id, "deleted", true);
    }

    // ------------------------------------------------------------------ cycles

    public Map<String, Object> listCycles() {
        List<Map<String, Object>> list = rows.query("SELECT c.*, (SELECT count(DISTINCT a.\"employeeId\") FROM"
                + " \"HrAppraisal\" a WHERE a.\"cycleId\" = c.id AND a.\"deletedAt\" IS NULL) AS participants, (SELECT"
                + " count(*) FROM \"HrAppraisal\" a WHERE a.\"cycleId\" = c.id AND a.status <> 'PENDING' AND"
                + " a.\"deletedAt\" IS NULL) AS submitted FROM \"HrReviewCycle\" c WHERE c.\"organizationId\" = ? AND"
                + " c.\"deletedAt\" IS NULL ORDER BY c.\"periodStart\" DESC", org());
        return HrRows.page(list, list.size());
    }

    public Map<String, Object> getCycle(String id) {
        Map<String, Object> c = rows.require("HrReviewCycle", id, "Review cycle not found");
        c.put("appraisals", rows.query("SELECT a.*, e.\"employeeCode\", e.\"firstName\", e.\"lastName\", r.\"firstName\" AS"
                + " \"reviewerFirstName\", r.\"lastName\" AS \"reviewerLastName\" FROM \"HrAppraisal\" a JOIN \"HrEmployee\" e"
                + " ON e.id = a.\"employeeId\" LEFT JOIN \"HrEmployee\" r ON r.id = a.\"reviewerId\" WHERE a.\"cycleId\" = ?"
                + " AND a.\"organizationId\" = ? AND a.\"deletedAt\" IS NULL ORDER BY e.\"employeeCode\", a.\"reviewerType\"",
                id, org()));
        return c;
    }

    public Map<String, Object> saveCycle(String id, Map<String, Object> dto) {
        Map<String, Object> before = id == null ? null : rows.require("HrReviewCycle", id, "Review cycle not found");
        if (before != null && !"DRAFT".equals(before.get("status"))) {
            throw ApiException.badRequest("Only a draft cycle can be edited");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (id == null || dto.containsKey("name")) {
            String name = str(dto.get("name"));
            if (name == null) {
                throw ApiException.badRequest("name is required");
            }
            data.put("name", name);
        }
        LocalDate start = dto.containsKey("periodStart") || id == null ? HrRows.date(dto.get("periodStart"))
                : HrRows.date(before.get("periodStart"));
        LocalDate end = dto.containsKey("periodEnd") || id == null ? HrRows.date(dto.get("periodEnd"))
                : HrRows.date(before.get("periodEnd"));
        if (start == null || end == null || end.isBefore(start)) {
            throw ApiException.badRequest("periodStart and periodEnd are required, end not before start");
        }
        data.put("periodStart", start);
        data.put("periodEnd", end);
        if (dto.containsKey("goalWeightPct")) {
            BigDecimal w = dec(dto.get("goalWeightPct"));
            if (w == null || w.signum() < 0 || w.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw ApiException.badRequest("goalWeightPct must be from 0 to 100");
            }
            data.put("goalWeightPct", w);
        }
        for (String f : List.of("selfReview", "peerReview")) {
            if (dto.containsKey(f)) {
                data.put(f, Boolean.TRUE.equals(dto.get(f)));
            }
        }
        if (dto.containsKey("competencyIds")) {
            List<String> ids = new ArrayList<>();
            for (Object o : PosSql.list(dto.get("competencyIds"))) {
                rows.require("HrCompetency", String.valueOf(o), "Competency not found");
                ids.add(String.valueOf(o));
            }
            data.put("competencyIds", ids);
        }
        if (dto.containsKey("departmentId")) {
            data.put("departmentId", str(dto.get("departmentId")));
        }
        return id == null ? rows.insert("HrReviewCycle", data) : rows.update("HrReviewCycle", id, data);
    }

    /** Open the cycle: appraisals for every active employee in scope (self if enabled, manager = supervisor). */
    public Map<String, Object> launch(String id) {
        Map<String, Object> c = rows.require("HrReviewCycle", id, "Review cycle not found");
        if (!"DRAFT".equals(c.get("status"))) {
            throw ApiException.badRequest("Only a draft cycle can be launched");
        }
        List<Map<String, Object>> emps = rows.query("SELECT id, \"supervisorId\" FROM \"HrEmployee\" WHERE"
                + " \"organizationId\" = ? AND \"isActive\" = true AND \"deletedAt\" IS NULL AND (CAST(? AS text) IS NULL OR"
                + " \"departmentId\" = ?)", org(), c.get("departmentId"), c.get("departmentId"));
        if (emps.isEmpty()) {
            throw ApiException.badRequest("No active employees in the cycle's scope");
        }
        return hr.inTx(() -> {
            int n = 0;
            for (Map<String, Object> e : emps) {
                if (Boolean.TRUE.equals(c.get("selfReview"))) {
                    rows.insert("HrAppraisal", appraisal(id, e.get("id"), e.get("id"), "SELF"));
                    n++;
                }
                rows.insert("HrAppraisal", appraisal(id, e.get("id"), e.get("supervisorId"), "MANAGER"));
                n++;
            }
            rows.update("HrReviewCycle", id, Map.of("status", "OPEN"));
            hr.auditInTx(AuditInput.of("HrReviewCycle", id, "update", Map.of("status", "DRAFT"),
                    Map.of("status", "OPEN", "appraisals", n)));
            return getCycle(id);
        });
    }

    private static Map<String, Object> appraisal(String cycleId, Object empId, Object reviewerId, String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cycleId", cycleId);
        m.put("employeeId", empId);
        m.put("reviewerId", reviewerId);
        m.put("reviewerType", type);
        m.put("status", "PENDING");
        return m;
    }

    /** Add a peer reviewer to an employee in an open cycle. */
    public Map<String, Object> addPeer(String cycleId, Map<String, Object> dto) {
        Map<String, Object> c = rows.require("HrReviewCycle", cycleId, "Review cycle not found");
        if (!"OPEN".equals(c.get("status")) || !Boolean.TRUE.equals(c.get("peerReview"))) {
            throw ApiException.badRequest("Peer reviews need an open cycle with peer review enabled");
        }
        String emp = str(dto.get("employeeId"));
        String peer = str(dto.get("reviewerId"));
        if (emp == null || peer == null || emp.equals(peer)) {
            throw ApiException.badRequest("employeeId and a different reviewerId are required");
        }
        return rows.insert("HrAppraisal", appraisal(cycleId, emp, peer, "PEER"));
    }

    /** Close: finalize manager appraisals into performance reviews; open appraisals are left unscored. */
    public Map<String, Object> close(String id) {
        Map<String, Object> c = rows.require("HrReviewCycle", id, "Review cycle not found");
        if (!"OPEN".equals(c.get("status"))) {
            throw ApiException.badRequest("Only an open cycle can be closed");
        }
        return hr.inTx(() -> {
            int finalized = 0;
            for (Map<String, Object> a : rows.list("HrAppraisal", "\"cycleId\" = ? AND \"reviewerType\" = 'MANAGER' AND"
                    + " status = 'SUBMITTED'", null, id)) {
                BigDecimal overall = dec(a.get("overallScore"));
                Map<String, Object> review = hr.insert(HrPerformanceReview.class, Map.of("organizationId", org(),
                        "employeeId", a.get("employeeId"), "reviewDate", Instant.now(),
                        "periodStart", HrPayrollEngineV2.utc(HrRows.date(c.get("periodStart"))),
                        "periodEnd", HrPayrollEngineV2.utc(HrRows.date(c.get("periodEnd"))),
                        "kpiScore", overall.multiply(BigDecimal.valueOf(20)).setScale(2, RoundingMode.HALF_UP),
                        "rating", overall.setScale(0, RoundingMode.HALF_UP).intValue(), "status", "APPROVED",
                        "notes", c.get("name") + ": " + (a.get("comments") == null ? "" : a.get("comments")),
                        "createdBy", user() == null ? "" : user()));
                rows.update("HrAppraisal", (String) a.get("id"), Map.of("status", "FINALIZED", "reviewId",
                        review.get("id")));
                finalized++;
            }
            rows.update("HrReviewCycle", id, Map.of("status", "CLOSED"));
            hr.auditInTx(AuditInput.of("HrReviewCycle", id, "update", Map.of("status", "OPEN"),
                    Map.of("status", "CLOSED", "finalized", finalized)));
            Map<String, Object> out = getCycle(id);
            out.put("finalized", finalized);
            return out;
        });
    }

    // ------------------------------------------------------------------ goals

    public Map<String, Object> listGoals(Map<String, String> q) {
        String cycle = q.get("cycleId");
        String emp = q.get("employeeId");
        if (cycle == null) {
            throw ApiException.badRequest("cycleId is required");
        }
        List<Map<String, Object>> list = emp == null ? rows.list("HrGoal", "\"cycleId\" = ?", "\"employeeId\", \"createdAt\"",
                cycle) : rows.list("HrGoal", "\"cycleId\" = ? AND \"employeeId\" = ?", "\"createdAt\"", cycle, emp);
        return HrRows.page(list, list.size());
    }

    public Map<String, Object> saveGoal(String id, Map<String, Object> dto) {
        Map<String, Object> before = id == null ? null : rows.require("HrGoal", id, "Goal not found");
        String cycleId = before != null ? (String) before.get("cycleId") : str(dto.get("cycleId"));
        String empId = before != null ? (String) before.get("employeeId") : str(dto.get("employeeId"));
        Map<String, Object> c = rows.require("HrReviewCycle", cycleId, "Review cycle not found");
        if ("CLOSED".equals(c.get("status"))) {
            throw ApiException.badRequest("The cycle is closed");
        }
        if (empId == null) {
            throw ApiException.badRequest("employeeId is required");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (before == null) {
            data.put("cycleId", cycleId);
            data.put("employeeId", empId);
        }
        if (before == null || dto.containsKey("title")) {
            String t = str(dto.get("title"));
            if (t == null) {
                throw ApiException.badRequest("title is required");
            }
            data.put("title", t);
        }
        BigDecimal weight = dto.containsKey("weight") || before == null ? dec(dto.get("weight"))
                : dec(before.get("weight"));
        if (weight == null || weight.signum() <= 0 || weight.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw ApiException.badRequest("weight must be greater than 0 and at most 100");
        }
        BigDecimal others = dec(rows.queryOne("SELECT coalesce(sum(weight), 0) AS w FROM \"HrGoal\" WHERE"
                + " \"organizationId\" = ? AND \"cycleId\" = ? AND \"employeeId\" = ? AND \"deletedAt\" IS NULL AND status"
                + " <> 'CANCELLED' AND id <> ?", org(), cycleId, empId, id == null ? "" : id).get("w"));
        if (others.add(weight).compareTo(BigDecimal.valueOf(100)) > 0) {
            throw ApiException.badRequest("Goal weights of an employee cannot exceed 100 in total");
        }
        data.put("weight", weight);
        for (String f : List.of("description", "target", "actual")) {
            if (dto.containsKey(f)) {
                data.put(f, str(dto.get(f)));
            }
        }
        if (dto.containsKey("status")) {
            HrRows.oneOf("status", dto.get("status"), List.of("ACTIVE", "ACHIEVED", "MISSED", "CANCELLED"));
            data.put("status", dto.get("status"));
        }
        return id == null ? rows.insert("HrGoal", data) : rows.update("HrGoal", id, data);
    }

    public Map<String, Object> deleteGoal(String id) {
        rows.require("HrGoal", id, "Goal not found");
        rows.softDelete("HrGoal", id);
        return Map.of("id", id, "deleted", true);
    }

    // ------------------------------------------------------------------ appraisals

    public Map<String, Object> getAppraisal(String id) {
        Map<String, Object> a = rows.require("HrAppraisal", id, "Appraisal not found");
        Map<String, Object> c = rows.require("HrReviewCycle", a.get("cycleId"), "Review cycle not found");
        a.put("cycle", c);
        a.put("goals", rows.list("HrGoal", "\"cycleId\" = ? AND \"employeeId\" = ? AND status <> 'CANCELLED'",
                "\"createdAt\"", a.get("cycleId"), a.get("employeeId")));
        List<Map<String, Object>> comps = new ArrayList<>();
        for (Object o : PosSql.list(c.get("competencyIds"))) {
            Map<String, Object> comp = rows.find("HrCompetency", String.valueOf(o));
            if (comp != null) {
                comps.add(comp);
            }
        }
        a.put("competencies", comps);
        return a;
    }

    /** My appraisals: as employee (SELF) and as reviewer (MANAGER / PEER). */
    public Map<String, Object> myAppraisals(String employeeId) {
        List<Map<String, Object>> list = rows.query("SELECT a.*, c.name AS \"cycleName\", c.status AS \"cycleStatus\","
                + " e.\"employeeCode\", e.\"firstName\", e.\"lastName\" FROM \"HrAppraisal\" a JOIN \"HrReviewCycle\" c ON"
                + " c.id = a.\"cycleId\" JOIN \"HrEmployee\" e ON e.id = a.\"employeeId\" WHERE a.\"organizationId\" = ? AND"
                + " a.\"deletedAt\" IS NULL AND a.\"reviewerId\" = ? ORDER BY c.\"periodStart\" DESC", org(), employeeId);
        return HrRows.page(list, list.size());
    }

    /** Submit scores. {@code reviewerEmployeeId} non-null (self-service) must be the appraisal's reviewer. */
    public Map<String, Object> submit(String id, Map<String, Object> dto, String reviewerEmployeeId) {
        Map<String, Object> a = getAppraisal(id);
        if (reviewerEmployeeId != null && !reviewerEmployeeId.equals(a.get("reviewerId"))) {
            throw ApiException.forbidden("This appraisal is not yours to submit");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> cycle = (Map<String, Object>) a.get("cycle");
        if (!"OPEN".equals(cycle.get("status")) || "FINALIZED".equals(a.get("status"))) {
            throw ApiException.badRequest("The appraisal is not open");
        }
        Map<String, Object> goalScores = scores(dto.get("goalScores"));
        Map<String, Object> compScores = scores(dto.get("competencyScores"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> goals = (List<Map<String, Object>>) a.get("goals");
        BigDecimal wSum = BigDecimal.ZERO;
        BigDecimal wScore = BigDecimal.ZERO;
        for (Map<String, Object> g : goals) {
            Object s = goalScores.get(g.get("id"));
            if (s != null) {
                BigDecimal w = dec(g.get("weight"));
                wSum = wSum.add(w);
                wScore = wScore.add(w.multiply(dec(s), MC));
            }
        }
        BigDecimal goalScore = wSum.signum() == 0 ? null : wScore.divide(wSum, 2, RoundingMode.HALF_UP);
        BigDecimal compScore = null;
        if (!compScores.isEmpty()) {
            BigDecimal t = BigDecimal.ZERO;
            for (Object v : compScores.values()) {
                t = t.add(dec(v));
            }
            compScore = t.divide(BigDecimal.valueOf(compScores.size()), 2, RoundingMode.HALF_UP);
        }
        BigDecimal gw = dec(cycle.get("goalWeightPct")).divide(BigDecimal.valueOf(100), MC);
        BigDecimal overall = goalScore == null ? compScore : compScore == null ? goalScore
                : goalScore.multiply(gw, MC).add(compScore.multiply(BigDecimal.ONE.subtract(gw), MC))
                        .setScale(2, RoundingMode.HALF_UP);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("goalScores", goalScores);
        data.put("competencyScores", compScores);
        data.put("goalScore", goalScore);
        data.put("competencyScore", compScore);
        data.put("overallScore", overall);
        for (String f : List.of("strengths", "improvements", "trainingNeeds", "comments")) {
            if (dto.containsKey(f)) {
                data.put(f, str(dto.get(f)));
            }
        }
        boolean submit = !Boolean.FALSE.equals(dto.get("submit"));
        if (submit) {
            if (overall == null) {
                throw ApiException.badRequest("Score at least one goal or competency before submitting");
            }
            data.put("status", "SUBMITTED");
            data.put("submittedAt", Instant.now());
        }
        return hr.inTx(() -> {
            Map<String, Object> row = rows.update("HrAppraisal", id, data);
            if (submit && "MANAGER".equals(a.get("reviewerType"))) {
                goalScores.forEach((goalId, s) -> rows.jdbc().sql("UPDATE \"HrGoal\" SET score = ? WHERE id = ? AND"
                        + " \"organizationId\" = ?").params(dec(s), goalId, org()).update());
            }
            return row;
        });
    }

    private static Map<String, Object> scores(Object raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        PosSql.map(raw).forEach((k, v) -> {
            BigDecimal s = dec(v);
            if (s == null || s.compareTo(BigDecimal.ONE) < 0 || s.compareTo(BigDecimal.valueOf(5)) > 0) {
                throw ApiException.badRequest("Scores must be from 1 to 5");
            }
            out.put(k, s);
        });
        return out;
    }
}
