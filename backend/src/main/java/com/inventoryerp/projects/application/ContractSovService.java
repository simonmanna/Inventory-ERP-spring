package com.inventoryerp.projects.application;

import static com.inventoryerp.projects.application.ProjectService.blank;
import static com.inventoryerp.projects.application.ProjectService.money;
import static com.inventoryerp.projects.application.ProjectService.org;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.projects.application.ContractCalculator.SovLine;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contract schedule of values and line-level progress billing (ADR-S075 §1). The SOV lists the billable items of a
 * contract (code, description, optional WBS element, scheduled value, optional retention %). Approved variations change
 * a line's value or add a line. A progress claim may carry SOV lines (G703): per line, work completed this period (or
 * the cumulative % complete), materials presently stored and retention; every SOV line is carried on the claim so the
 * claim's cumulative amount is the sum of G over the whole schedule. The G702 / G703 payment application is served as
 * JSON and as a PDF.
 */
@Service
public class ContractSovService {

    static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public record SovLineInput(String code, String description, String wbsId, BigDecimal scheduledValue,
            BigDecimal retentionPct, Integer sortOrder) {}

    /** One claim line request: E or the cumulative % complete (of work, excluding stored materials), F, retention %. */
    public record ClaimLineInput(String sovLineId, BigDecimal workCompletedThisPeriod, BigDecimal percentComplete,
            BigDecimal materialsStored, BigDecimal retentionPct) {}

    /** A computed claim line. */
    public record ComputedLine(Map<String, Object> sov, SovLine line) {}

    private final JdbcClient jdbc;
    private final ProjectService projects;
    private final AuditService audit;

    public ContractSovService(JdbcClient jdbc, ProjectService projects, AuditService audit) {
        this.jdbc = jdbc;
        this.projects = projects;
        this.audit = audit;
    }

    // ---- schedule of values --------------------------------------------------------------------------------------------

    /** SOV lines with their current value, billed to date (latest certified claim) and balance. */
    @Transactional(readOnly = true)
    public Map<String, Object> schedule(String projectId) {
        projects.project(projectId, false);
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT s.*, w.code AS \"wbsCode\", s.\"scheduledValue\""
                + " + s.\"variationValue\" AS \"currentValue\", coalesce((SELECT l.\"totalCompleted\" FROM"
                + " \"ProgressClaimLine\" l JOIN \"ProgressClaim\" c ON c.id = l.\"claimId\" WHERE l.\"sovLineId\" = s.id"
                + " AND c.status = 'certified' ORDER BY c.\"cumulativeCertified\" DESC, c.number DESC LIMIT 1), 0) AS"
                + " \"billedToDate\" FROM \"ContractSovLine\" s LEFT JOIN \"ProjectWbsElement\" w ON w.id = s.\"wbsId\""
                + " WHERE s.\"projectId\" = ? AND s.\"organizationId\" = ? ORDER BY s.\"sortOrder\", s.code")
                .params(projectId, org()));
        BigDecimal scheduled = BigDecimal.ZERO;
        BigDecimal variations = BigDecimal.ZERO;
        BigDecimal billed = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            BigDecimal current = SqlRows.dec(r, "currentValue");
            BigDecimal b = SqlRows.dec(r, "billedToDate");
            r.put("balance", JsNumbers.dec(current.subtract(b)));
            r.put("percentBilled", JsNumbers.dec(current.signum() == 0 ? BigDecimal.ZERO
                    : b.multiply(HUNDRED).divide(current, 2, RoundingMode.HALF_UP)));
            scheduled = scheduled.add(SqlRows.dec(r, "scheduledValue"));
            variations = variations.add(SqlRows.dec(r, "variationValue"));
            billed = billed.add(b);
        }
        BigDecimal total = scheduled.add(variations);
        return Obj.of("projectId", projectId, "lines", rows, "totals", Obj.of("scheduledValue",
                JsNumbers.dec(scheduled), "variationValue", JsNumbers.dec(variations), "currentValue",
                JsNumbers.dec(total), "billedToDate", JsNumbers.dec(billed), "balance",
                JsNumbers.dec(total.subtract(billed))));
    }

    @Transactional
    public Map<String, Object> create(String projectId, SovLineInput in) {
        projects.project(projectId, false);
        String code = blank(in.code()) == null ? null : in.code().trim();
        if (code == null) {
            throw ApiException.badRequest("code should not be empty");
        }
        if (blank(in.description()) == null) {
            throw ApiException.badRequest("description should not be empty");
        }
        if (in.scheduledValue() == null || in.scheduledValue().signum() < 0) {
            throw ApiException.badRequest("scheduledValue must not be less than 0");
        }
        if (blank(in.wbsId()) != null) {
            projects.wbs(projectId, in.wbsId());
        }
        retention(in.retentionPct());
        codeFree(projectId, code, null);
        String id = UUID.randomUUID().toString();
        int sort = in.sortOrder() != null ? in.sortOrder() : jdbc.sql("SELECT coalesce(max(\"sortOrder\"), 0) + 10 FROM"
                + " \"ContractSovLine\" WHERE \"projectId\" = ?").param(projectId).query(Integer.class).single();
        jdbc.sql("INSERT INTO \"ContractSovLine\" (id, \"organizationId\", \"projectId\", code, description, \"wbsId\","
                        + " \"scheduledValue\", \"retentionPct\", \"sortOrder\", \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?,"
                        + " ?, ?)")
                .params(id, org(), projectId, code, in.description().trim(), blank(in.wbsId()),
                        money(in.scheduledValue()), in.retentionPct(), sort, TenantContext.userId())
                .update();
        audit.recordInTx(AuditInput.of("ContractSovLine", id, "create", null, Obj.of("code", code,
                "scheduledValue", money(in.scheduledValue()))));
        return line(id);
    }

    /**
     * Change code, description, WBS ({@code ""} clears), retention % or order; the scheduled value only while no claim
     * carries the line (afterwards a variation changes it).
     */
    @Transactional
    public Map<String, Object> update(String lineId, SovLineInput in) {
        Map<String, Object> l = locked(lineId);
        String projectId = (String) l.get("projectId");
        String code = blank(in.code()) == null ? (String) l.get("code") : in.code().trim();
        codeFree(projectId, code, lineId);
        String description = blank(in.description()) == null ? (String) l.get("description") : in.description().trim();
        String wbs = in.wbsId() == null ? (String) l.get("wbsId") : blank(in.wbsId());
        if (wbs != null) {
            projects.wbs(projectId, wbs);
        }
        BigDecimal value = SqlRows.dec(l, "scheduledValue");
        if (in.scheduledValue() != null && in.scheduledValue().compareTo(value) != 0) {
            if (in.scheduledValue().signum() < 0) {
                throw ApiException.badRequest("scheduledValue must not be less than 0");
            }
            if (claimed(lineId)) {
                throw ApiException.badRequest("Line " + l.get("code") + " is already claimed; change its value through"
                        + " an approved variation");
            }
            value = money(in.scheduledValue());
        }
        BigDecimal retention = in.retentionPct() == null ? (BigDecimal) l.get("retentionPct") : in.retentionPct();
        retention(retention);
        int sort = in.sortOrder() == null ? ((Number) l.get("sortOrder")).intValue() : in.sortOrder();
        jdbc.sql("UPDATE \"ContractSovLine\" SET code = ?, description = ?, \"wbsId\" = ?, \"scheduledValue\" = ?,"
                + " \"retentionPct\" = ?, \"sortOrder\" = ?, \"updatedAt\" = now() WHERE id = ?").params(code, description,
                wbs, value, retention, sort, lineId).update();
        audit.recordInTx(AuditInput.of("ContractSovLine", lineId, "update", Obj.of("scheduledValue",
                l.get("scheduledValue")), Obj.of("scheduledValue", value)));
        return line(lineId);
    }

    @Transactional
    public Map<String, Object> delete(String lineId) {
        Map<String, Object> l = locked(lineId);
        if (claimed(lineId)) {
            throw ApiException.badRequest("Line " + l.get("code") + " is carried by a progress claim and cannot be"
                    + " deleted");
        }
        if (jdbc.sql("SELECT count(*) FROM \"ContractVariation\" WHERE \"sovLineId\" = ?").param(lineId)
                .query(Long.class).single() > 0 || l.get("variationId") != null) {
            throw ApiException.badRequest("Line " + l.get("code") + " is linked to a variation and cannot be deleted");
        }
        jdbc.sql("DELETE FROM \"ContractSovLine\" WHERE id = ?").param(lineId).update();
        audit.recordInTx(AuditInput.of("ContractSovLine", lineId, "delete", null, null));
        return Obj.of("id", lineId, "deleted", true);
    }

    private Map<String, Object> line(String id) {
        return SqlRows.one(jdbc.sql("SELECT s.*, s.\"scheduledValue\" + s.\"variationValue\" AS \"currentValue\" FROM"
                + " \"ContractSovLine\" s WHERE s.id = ?").param(id));
    }

    private Map<String, Object> locked(String id) {
        Map<String, Object> l = SqlRows.one(jdbc.sql("SELECT * FROM \"ContractSovLine\" WHERE id = ? AND"
                + " \"organizationId\" = ? FOR UPDATE").params(id, org()));
        if (l == null) {
            throw ApiException.notFound("Schedule-of-values line not found");
        }
        return l;
    }

    private boolean claimed(String lineId) {
        return jdbc.sql("SELECT count(*) FROM \"ProgressClaimLine\" l JOIN \"ProgressClaim\" c ON c.id = l.\"claimId\""
                + " WHERE l.\"sovLineId\" = ? AND c.status <> 'cancelled'").param(lineId).query(Long.class).single() > 0;
    }

    private void codeFree(String projectId, String code, String exceptId) {
        if (jdbc.sql("SELECT count(*) FROM \"ContractSovLine\" WHERE \"projectId\" = ? AND code = ? AND id <> ?")
                .params(projectId, code, exceptId == null ? "" : exceptId).query(Long.class).single() > 0) {
            throw ApiException.conflict("Schedule-of-values line " + code + " already exists on this contract");
        }
    }

    private static void retention(BigDecimal pct) {
        if (pct != null && (pct.signum() < 0 || pct.compareTo(HUNDRED) >= 0)) {
            throw ApiException.badRequest("retentionPct must be between 0 and 100");
        }
    }

    // ---- variations --------------------------------------------------------------------------------------------------

    /** Validate a variation's SOV target: an existing line of the project, or a new code with a description. */
    public void validateVariation(String projectId, String sovLineId, String sovCode, String sovDescription) {
        if (blank(sovLineId) != null && blank(sovCode) != null) {
            throw ApiException.badRequest("Give sovLineId or sovCode, not both");
        }
        if (blank(sovLineId) != null && jdbc.sql("SELECT count(*) FROM \"ContractSovLine\" WHERE id = ? AND"
                + " \"projectId\" = ? AND \"organizationId\" = ?").params(sovLineId, projectId, org()).query(Long.class)
                .single() == 0) {
            throw ApiException.notFound("Schedule-of-values line not found on this project");
        }
        if (blank(sovCode) != null) {
            if (blank(sovDescription) == null) {
                throw ApiException.badRequest("sovDescription should not be empty");
            }
            codeFree(projectId, sovCode.trim(), null);
        }
    }

    /** An approved variation: change its SOV line's value or add its new line. */
    public void applyVariation(Map<String, Object> v) {
        BigDecimal delta = SqlRows.dec(v, "priceDelta");
        if (v.get("sovLineId") != null) {
            Map<String, Object> l = locked((String) v.get("sovLineId"));
            if (SqlRows.dec(l, "scheduledValue").add(SqlRows.dec(l, "variationValue")).add(delta).signum() < 0) {
                throw ApiException.badRequest("The variation would take line " + l.get("code") + " below 0");
            }
            jdbc.sql("UPDATE \"ContractSovLine\" SET \"variationValue\" = \"variationValue\" + ?, \"updatedAt\" = now()"
                    + " WHERE id = ?").params(delta, l.get("id")).update();
        } else if (v.get("sovCode") != null) {
            if (delta.signum() < 0) {
                throw ApiException.badRequest("A variation adding a schedule-of-values line needs a positive priceDelta");
            }
            String projectId = (String) v.get("projectId");
            codeFree(projectId, (String) v.get("sovCode"), null);
            int sort = jdbc.sql("SELECT coalesce(max(\"sortOrder\"), 0) + 10 FROM \"ContractSovLine\" WHERE"
                    + " \"projectId\" = ?").param(projectId).query(Integer.class).single();
            jdbc.sql("INSERT INTO \"ContractSovLine\" (id, \"organizationId\", \"projectId\", code, description,"
                            + " \"wbsId\", \"scheduledValue\", \"variationValue\", \"variationId\", \"sortOrder\","
                            + " \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org(), projectId, v.get("sovCode"), v.get("sovDescription"),
                            v.get("wbsId"), delta, v.get("id"), sort, TenantContext.userId())
                    .update();
        }
    }

    // ---- claim lines -------------------------------------------------------------------------------------------------

    /** True when the project has a schedule of values. */
    public boolean hasSchedule(String projectId) {
        return jdbc.sql("SELECT count(*) FROM \"ContractSovLine\" WHERE \"projectId\" = ?").param(projectId)
                .query(Long.class).single() > 0;
    }

    /**
     * Compute every SOV line of the contract for a new claim: requested lines take their inputs, the others carry
     * their previous work forward with nothing this period. D and the previous F come from the latest certified claim
     * that carried the line.
     */
    public List<ComputedLine> computeClaim(String projectId, List<ClaimLineInput> inputs, BigDecimal claimRetentionPct) {
        List<Map<String, Object>> sov = SqlRows.list(jdbc.sql("SELECT s.*, s.\"scheduledValue\" + s.\"variationValue\" AS"
                + " \"currentValue\" FROM \"ContractSovLine\" s WHERE s.\"projectId\" = ? AND s.\"organizationId\" = ?"
                + " ORDER BY s.\"sortOrder\", s.code FOR UPDATE").params(projectId, org()));
        if (sov.isEmpty()) {
            throw ApiException.badRequest("The contract has no schedule of values; add its lines before a line-level"
                    + " claim");
        }
        Map<String, ClaimLineInput> byId = new HashMap<>();
        Set<String> known = new HashSet<>();
        sov.forEach(s -> known.add((String) s.get("id")));
        for (ClaimLineInput i : inputs) {
            if (i == null || blank(i.sovLineId()) == null) {
                throw ApiException.badRequest("lines.sovLineId should not be empty");
            }
            if (!known.contains(i.sovLineId())) {
                throw ApiException.notFound("Schedule-of-values line not found on this project");
            }
            if (byId.put(i.sovLineId(), i) != null) {
                throw ApiException.badRequest("A schedule-of-values line appears twice on the claim");
            }
        }
        List<ComputedLine> out = new ArrayList<>();
        for (Map<String, Object> s : sov) {
            String id = (String) s.get("id");
            Map<String, Object> prev = SqlRows.one(jdbc.sql("SELECT l.* FROM \"ProgressClaimLine\" l JOIN"
                    + " \"ProgressClaim\" c ON c.id = l.\"claimId\" WHERE l.\"sovLineId\" = ? AND c.status = 'certified'"
                    + " ORDER BY c.\"cumulativeCertified\" DESC, c.number DESC LIMIT 1").param(id));
            BigDecimal d = prev == null ? BigDecimal.ZERO
                    : SqlRows.dec(prev, "previousCompleted").add(SqlRows.dec(prev, "thisPeriod"));
            BigDecimal prevStored = prev == null ? BigDecimal.ZERO : SqlRows.dec(prev, "materialsStored");
            BigDecimal c = SqlRows.dec(s, "currentValue");
            ClaimLineInput in = byId.get(id);
            BigDecimal e = BigDecimal.ZERO;
            BigDecimal f = prevStored;
            BigDecimal pct = s.get("retentionPct") != null ? SqlRows.dec(s, "retentionPct") : claimRetentionPct;
            if (in != null) {
                if (in.workCompletedThisPeriod() != null && in.percentComplete() != null) {
                    throw ApiException.badRequest("Line " + s.get("code") + ": give workCompletedThisPeriod or"
                            + " percentComplete, not both");
                }
                if (in.percentComplete() != null) {
                    if (in.percentComplete().signum() < 0 || in.percentComplete().compareTo(HUNDRED) > 0) {
                        throw ApiException.badRequest("Line " + s.get("code") + ": percentComplete must be between 0"
                                + " and 100");
                    }
                    e = c.multiply(in.percentComplete()).divide(HUNDRED, 2, RoundingMode.HALF_UP).subtract(d);
                } else if (in.workCompletedThisPeriod() != null) {
                    e = money(in.workCompletedThisPeriod());
                }
                if (e.signum() < 0) {
                    throw ApiException.badRequest("Line " + s.get("code") + ": work completed cannot fall below the "
                            + d.toPlainString() + " already certified");
                }
                if (in.materialsStored() != null) {
                    if (in.materialsStored().signum() < 0) {
                        throw ApiException.badRequest("Line " + s.get("code") + ": materialsStored must not be less"
                                + " than 0");
                    }
                    f = money(in.materialsStored());
                }
                if (in.retentionPct() != null) {
                    retention(in.retentionPct());
                    pct = in.retentionPct();
                }
            }
            SovLine l = ContractCalculator.sovLine(c, d, prevStored, e, f, pct);
            if (l.total().compareTo(c) > 0) {
                throw ApiException.badRequest("Line " + s.get("code") + ": work completed and stored ("
                        + l.total().toPlainString() + ") exceeds its scheduled value " + c.toPlainString());
            }
            out.add(new ComputedLine(s, l));
        }
        return out;
    }

    /** Persist the computed lines of a claim. */
    public void insertClaimLines(String claimId, List<ComputedLine> lines) {
        for (ComputedLine c : lines) {
            SovLine l = c.line();
            jdbc.sql("INSERT INTO \"ProgressClaimLine\" (id, \"organizationId\", \"claimId\", \"sovLineId\","
                            + " \"scheduledValue\", \"previousCompleted\", \"previousStored\", \"thisPeriod\","
                            + " \"materialsStored\", \"totalCompleted\", \"percentComplete\", \"balanceToFinish\","
                            + " \"retentionPct\", retention) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org(), claimId, c.sov().get("id"), l.scheduled(),
                            l.previousCompleted(), l.previousStored(), l.thisPeriod(), l.stored(), l.total(),
                            l.percent(), l.balance(), l.retentionPct(), l.retention())
                    .update();
        }
    }

    /** The G703 lines of a claim with the SOV code and description. */
    public List<Map<String, Object>> claimLines(String claimId) {
        return SqlRows.list(jdbc.sql("SELECT l.*, s.code, s.description, s.\"wbsId\" FROM \"ProgressClaimLine\" l JOIN"
                + " \"ContractSovLine\" s ON s.id = l.\"sovLineId\" WHERE l.\"claimId\" = ? ORDER BY s.\"sortOrder\","
                + " s.code").param(claimId));
    }

    // ---- payment application (G702 / G703) ---------------------------------------------------------------------------

    /**
     * G702 summary and G703 continuation sheet of a claim. A claim without SOV lines shows one line for the contract
     * works. Retainage is cumulative over the certified claims up to this one.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> paymentApplication(String claimId) {
        Map<String, Object> c = SqlRows.one(jdbc.sql("SELECT * FROM \"ProgressClaim\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(claimId, org()));
        if (c == null) {
            throw ApiException.notFound("Progress claim not found");
        }
        String projectId = (String) c.get("projectId");
        Map<String, Object> p = projects.project(projectId, false);
        String customer = p.get("customerId") == null ? null : jdbc.sql("SELECT name FROM \"Partner\" WHERE id = ?")
                .param(p.get("customerId")).query(String.class).optional().orElse(null);
        BigDecimal original = SqlRows.dec(p, "contractValue");
        BigDecimal variations = jdbc.sql("SELECT coalesce(sum(\"priceDelta\"), 0) FROM \"ContractVariation\" WHERE"
                + " \"projectId\" = ? AND status = 'approved' AND modification <> 'separate_contract'").param(projectId)
                .query(BigDecimal.class).single();
        BigDecimal sumToDate = original.add(variations);
        BigDecimal cumulative = SqlRows.dec(c, "cumulativeCertified");
        BigDecimal previous = SqlRows.dec(c, "previousCertified");
        BigDecimal retention = SqlRows.dec(c, "retention");
        BigDecimal previousRetention = jdbc.sql("SELECT coalesce(sum(retention), 0) FROM \"ProgressClaim\" WHERE"
                + " \"projectId\" = ? AND status = 'certified' AND id <> ? AND \"cumulativeCertified\" <= ?").params(
                projectId, claimId, previous).query(BigDecimal.class).single();
        BigDecimal retainage = previousRetention.add(retention);
        BigDecimal earned = cumulative.subtract(retainage);
        BigDecimal previousCertificates = previous.subtract(previousRetention);
        BigDecimal due = earned.subtract(previousCertificates);
        List<Map<String, Object>> lines = new ArrayList<>();
        List<Map<String, Object>> stored = claimLines(claimId);
        BigDecimal[] t = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO};
        if (stored.isEmpty()) {
            lines.add(g703("1", "Contract works", sumToDate, previous, cumulative.subtract(previous), BigDecimal.ZERO,
                    cumulative, retention, t));
        } else {
            for (Map<String, Object> l : stored) {
                lines.add(g703((String) l.get("code"), (String) l.get("description"), SqlRows.dec(l, "scheduledValue"),
                        SqlRows.dec(l, "previousCompleted"), SqlRows.dec(l, "thisPeriod"),
                        SqlRows.dec(l, "materialsStored"), SqlRows.dec(l, "totalCompleted"), SqlRows.dec(l, "retention"),
                        t));
            }
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("scheduledValue", JsNumbers.dec(t[0]));
        totals.put("previousCompleted", JsNumbers.dec(t[1]));
        totals.put("thisPeriod", JsNumbers.dec(t[2]));
        totals.put("materialsStored", JsNumbers.dec(t[3]));
        totals.put("totalCompleted", JsNumbers.dec(t[4]));
        totals.put("percentComplete", JsNumbers.dec(t[0].signum() == 0 ? BigDecimal.ZERO
                : t[4].multiply(HUNDRED).divide(t[0], 2, RoundingMode.HALF_UP)));
        totals.put("balanceToFinish", JsNumbers.dec(t[5]));
        totals.put("retention", JsNumbers.dec(t[6]));
        Map<String, Object> g702 = new LinkedHashMap<>();
        g702.put("originalContractSum", JsNumbers.dec(original));
        g702.put("netChangeByVariations", JsNumbers.dec(variations));
        g702.put("contractSumToDate", JsNumbers.dec(sumToDate));
        g702.put("totalCompletedAndStored", JsNumbers.dec(cumulative));
        g702.put("retainage", JsNumbers.dec(retainage));
        g702.put("totalEarnedLessRetainage", JsNumbers.dec(earned));
        g702.put("lessPreviousCertificates", JsNumbers.dec(previousCertificates));
        g702.put("currentPaymentDue", JsNumbers.dec(due));
        g702.put("advanceRecovery", JsNumbers.dec(SqlRows.dec(c, "advanceRecovery")));
        g702.put("netAmount", JsNumbers.dec(SqlRows.dec(c, "netAmount")));
        g702.put("balanceToFinishIncludingRetainage", JsNumbers.dec(sumToDate.subtract(earned)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("claimId", claimId);
        out.put("number", c.get("number"));
        out.put("claimDate", c.get("claimDate"));
        out.put("status", c.get("status"));
        out.put("projectId", projectId);
        out.put("projectCode", p.get("code"));
        out.put("projectName", p.get("name"));
        out.put("contractReference", p.get("contractRef"));
        out.put("customerName", customer);
        out.put("lineLevel", !stored.isEmpty());
        out.put("summary", g702);
        out.put("lines", lines);
        out.put("totals", totals);
        return out;
    }

    /** The payment application as a PDF (G702 summary + G703 continuation sheet). */
    @Transactional(readOnly = true)
    public byte[] paymentApplicationPdf(String claimId) {
        Map<String, Object> app = paymentApplication(claimId);
        String company = jdbc.sql("SELECT name FROM \"Organization\" WHERE id = ?").param(org()).query(String.class)
                .optional().orElse("");
        return PaymentApplicationPdf.render(app, company);
    }

    private static Map<String, Object> g703(String code, String description, BigDecimal scheduled, BigDecimal previous,
            BigDecimal thisPeriod, BigDecimal stored, BigDecimal total, BigDecimal retention, BigDecimal[] t) {
        BigDecimal balance = scheduled.subtract(total);
        t[0] = t[0].add(scheduled);
        t[1] = t[1].add(previous);
        t[2] = t[2].add(thisPeriod);
        t[3] = t[3].add(stored);
        t[4] = t[4].add(total);
        t[5] = t[5].add(balance);
        t[6] = t[6].add(retention);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("description", description);
        m.put("scheduledValue", JsNumbers.dec(scheduled));
        m.put("previousCompleted", JsNumbers.dec(previous));
        m.put("thisPeriod", JsNumbers.dec(thisPeriod));
        m.put("materialsStored", JsNumbers.dec(stored));
        m.put("totalCompleted", JsNumbers.dec(total));
        m.put("percentComplete", JsNumbers.dec(scheduled.signum() == 0 ? BigDecimal.ZERO
                : total.multiply(HUNDRED).divide(scheduled, 2, RoundingMode.HALF_UP)));
        m.put("balanceToFinish", JsNumbers.dec(balance));
        m.put("retention", JsNumbers.dec(retention));
        return m;
    }
}
