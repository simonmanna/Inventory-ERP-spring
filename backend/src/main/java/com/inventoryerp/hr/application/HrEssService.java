package com.inventoryerp.hr.application;

import static com.inventoryerp.hr.application.HrRows.dec;
import static com.inventoryerp.hr.application.HrRows.org;
import static com.inventoryerp.hr.application.HrRows.str;
import static com.inventoryerp.hr.application.HrRows.user;

import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Employee self-service and its approvals (HP-8, ADR-S048): profile change requests (HR applies them), pay claims
 * (paid through payroll as an input), the manager's approvals inbox, and the employee's own payslip / annual earnings
 * PDFs. The signed-in user must be linked to an employee record.
 */
@Service
public class HrEssService {

    /** Fields an employee may ask to change. */
    static final List<String> PROFILE_FIELDS = List.of("phone", "email", "address", "preferredName",
            "emergencyContactName", "emergencyContactPhone", "emergencyContactRelation", "bankName", "bankAccountName",
            "bankAccountNumber", "mobileMoneyProvider", "mobileMoneyNumber");
    static final List<String> CLAIM_TYPES = List.of("TRANSPORT", "MEALS", "MEDICAL", "PHONE", "TRAINING", "TRAVEL",
            "OTHER");

    private final HrRows rows;
    private final HrDb hr;
    private final SequenceService seq;
    private final HrPayrollInputService inputs;
    private final HrPayrollOpsService ops;

    public HrEssService(HrRows rows, HrDb hr, SequenceService seq, HrPayrollInputService inputs,
            HrPayrollOpsService ops) {
        this.rows = rows;
        this.hr = hr;
        this.seq = seq;
        this.inputs = inputs;
        this.ops = ops;
    }

    Map<String, Object> self() {
        if (user() == null) {
            throw ApiException.forbidden("Authentication required");
        }
        Map<String, Object> e = rows.queryOne("SELECT * FROM \"HrEmployee\" WHERE \"organizationId\" = ? AND \"userId\" = ?"
                + " AND \"deletedAt\" IS NULL", org(), user());
        if (e == null) {
            throw ApiException.badRequest("Your login is not linked to an employee record. Ask HR to link it.");
        }
        return e;
    }

    public Object selfId() {
        return self().get("id");
    }

    /** An appraisal the signed-in employee reviews (or a SELF appraisal of theirs). */
    public Map<String, Object> ownAppraisal(HrTalentService talent, String id) {
        Map<String, Object> a = talent.getAppraisal(id);
        if (!selfId().equals(a.get("reviewerId"))) {
            throw ApiException.notFound("Appraisal not found");
        }
        return a;
    }

    // ------------------------------------------------------------------ own documents

    public HrPayrollOpsService.FileOut myPayslipPdf(String payslipId) {
        return ops.payslipPdf(payslipId, (String) self().get("id"));
    }

    public Map<String, Object> myAnnualSummary(int year) {
        return ops.annualSummary((String) self().get("id"), year);
    }

    public HrPayrollOpsService.FileOut myAnnualCertificate(int year) {
        return ops.annualCertificate((String) self().get("id"), year);
    }

    // ------------------------------------------------------------------ profile change requests

    public Map<String, Object> myProfileRequests() {
        List<Map<String, Object>> list = rows.list("HrProfileChangeRequest", "\"employeeId\" = ?", "\"createdAt\" DESC",
                self().get("id"));
        return HrRows.page(list, list.size());
    }

    public Map<String, Object> requestProfileChange(Map<String, Object> dto) {
        Map<String, Object> me = self();
        Map<String, Object> changes = new LinkedHashMap<>();
        Map<String, Object> raw = PosSql.map(dto.get("changes"));
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            if (!PROFILE_FIELDS.contains(e.getKey())) {
                throw ApiException.badRequest("Field " + e.getKey() + " cannot be changed from self-service");
            }
            changes.put(e.getKey(), str(e.getValue()));
        }
        if (changes.isEmpty()) {
            throw ApiException.badRequest("changes is required");
        }
        if (rows.count("HrProfileChangeRequest", "\"employeeId\" = ? AND status = 'PENDING'", me.get("id")) > 0) {
            throw ApiException.badRequest("You already have a pending profile change request");
        }
        Map<String, Object> previous = new LinkedHashMap<>();
        changes.keySet().forEach(k -> previous.put(k, me.get(k)));
        return hr.inTx(() -> rows.insert("HrProfileChangeRequest", Map.of("employeeId", me.get("id"), "changes", changes,
                "previous", previous, "status", "PENDING")));
    }

    public Map<String, Object> cancelProfileRequest(String id) {
        Map<String, Object> r = rows.require("HrProfileChangeRequest", id, "Request not found");
        if (!self().get("id").equals(r.get("employeeId")) || !"PENDING".equals(r.get("status"))) {
            throw ApiException.badRequest("Only your own pending request can be cancelled");
        }
        return rows.update("HrProfileChangeRequest", id, Map.of("status", "CANCELLED"));
    }

    public Map<String, Object> listProfileRequests(Map<String, String> q) {
        List<Map<String, Object>> list = rows.query("SELECT r.*, e.\"employeeCode\", e.\"firstName\", e.\"lastName\" FROM"
                + " \"HrProfileChangeRequest\" r JOIN \"HrEmployee\" e ON e.id = r.\"employeeId\" WHERE r.\"organizationId\""
                + " = ? AND r.\"deletedAt\" IS NULL AND r.status = ? ORDER BY r.\"createdAt\" DESC LIMIT 500", org(),
                q.getOrDefault("status", "PENDING"));
        return HrRows.page(list, list.size());
    }

    /** HR applies (approve) or rejects a profile change; the employee row is updated on approval. */
    public Map<String, Object> reviewProfileRequest(String id, boolean approve, Map<String, Object> dto) {
        Map<String, Object> r = rows.require("HrProfileChangeRequest", id, "Request not found");
        if (!"PENDING".equals(r.get("status"))) {
            throw ApiException.badRequest("Only a pending request can be reviewed");
        }
        if (!approve && str(dto.get("note")) == null) {
            throw ApiException.badRequest("note is required to reject");
        }
        return hr.inTx(() -> {
            if (approve) {
                Map<String, Object> changes = PosSql.map(r.get("changes"));
                StringBuilder set = new StringBuilder();
                List<Object> params = new java.util.ArrayList<>();
                for (Map.Entry<String, Object> c : changes.entrySet()) {
                    if (!PROFILE_FIELDS.contains(c.getKey())) {
                        continue;
                    }
                    set.append("\"").append(c.getKey()).append("\" = ?, ");
                    params.add(c.getValue());
                }
                params.add(user());
                params.add(r.get("employeeId"));
                params.add(org());
                rows.jdbc().sql("UPDATE \"HrEmployee\" SET " + set + "\"updatedBy\" = ?, \"updatedAt\" = CURRENT_TIMESTAMP"
                        + " WHERE id = ? AND \"organizationId\" = ?").params(params).update();
                hr.auditInTx(AuditInput.of("HrEmployee", (String) r.get("employeeId"), "update", r.get("previous"),
                        changes));
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", approve ? "APPROVED" : "REJECTED");
            data.put("reviewedBy", user());
            data.put("reviewedAt", Instant.now());
            data.put("reviewNote", str(dto.get("note")));
            return rows.update("HrProfileChangeRequest", id, data);
        });
    }

    // ------------------------------------------------------------------ claims

    public Map<String, Object> myClaims() {
        List<Map<String, Object>> list = rows.list("HrPayClaim", "\"employeeId\" = ?", "\"createdAt\" DESC",
                self().get("id"));
        return HrRows.page(list, list.size());
    }

    public Map<String, Object> submitClaim(Map<String, Object> dto) {
        Map<String, Object> me = self();
        Object type = dto.get("claimType");
        HrRows.oneOf("claimType", type, CLAIM_TYPES);
        BigDecimal amount = dec(dto.get("amount"));
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.badRequest("amount must be positive");
        }
        LocalDate date = HrRows.date(dto.get("claimDate"));
        if (date == null) {
            date = LocalDate.now(ZoneOffset.UTC);
        }
        if (date.isAfter(LocalDate.now(ZoneOffset.UTC))) {
            throw ApiException.badRequest("claimDate cannot be in the future");
        }
        LocalDate d = date;
        return hr.inTx(() -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("claimNumber", seq.next("hr_pay_claim", "CLM-", 6));
            data.put("employeeId", me.get("id"));
            data.put("claimType", type);
            data.put("amount", amount);
            data.put("claimDate", d);
            data.put("description", str(dto.get("description")));
            data.put("status", "PENDING");
            return rows.insert("HrPayClaim", data);
        });
    }

    public Map<String, Object> cancelClaim(String id) {
        Map<String, Object> c = rows.require("HrPayClaim", id, "Claim not found");
        if (!self().get("id").equals(c.get("employeeId")) || !"PENDING".equals(c.get("status"))) {
            throw ApiException.badRequest("Only your own pending claim can be cancelled");
        }
        return rows.update("HrPayClaim", id, Map.of("status", "CANCELLED"));
    }

    public Map<String, Object> listClaims(Map<String, String> q) {
        List<Map<String, Object>> list = rows.query("SELECT c.*, e.\"employeeCode\", e.\"firstName\", e.\"lastName\" FROM"
                + " \"HrPayClaim\" c JOIN \"HrEmployee\" e ON e.id = c.\"employeeId\" WHERE c.\"organizationId\" = ? AND"
                + " c.\"deletedAt\" IS NULL AND c.status = ? ORDER BY c.\"createdAt\" DESC LIMIT 500", org(),
                q.getOrDefault("status", "PENDING"));
        return HrRows.page(list, list.size());
    }

    /**
     * Approve (payroll input, non-taxable unless {@code isTaxable}) or reject a claim. {@code asManager}: only claims
     * of the signed-in employee's direct reports, and never their own.
     */
    public Map<String, Object> reviewClaim(String id, boolean approve, Map<String, Object> dto, boolean asManager) {
        Map<String, Object> c = rows.require("HrPayClaim", id, "Claim not found");
        if (!"PENDING".equals(c.get("status"))) {
            throw ApiException.badRequest("Only a pending claim can be reviewed");
        }
        if (asManager) {
            Map<String, Object> me = self();
            Map<String, Object> claimant = rows.queryOne("SELECT \"supervisorId\" FROM \"HrEmployee\" WHERE id = ?",
                    c.get("employeeId"));
            if (!me.get("id").equals(claimant.get("supervisorId"))) {
                throw ApiException.forbidden("You can only review claims of your direct reports");
            }
        }
        if (!approve && str(dto.get("note")) == null) {
            throw ApiException.badRequest("note is required to reject");
        }
        return hr.inTx(() -> {
            Map<String, Object> data = new LinkedHashMap<>();
            if (approve) {
                boolean taxable = Boolean.TRUE.equals(dto.get("isTaxable"));
                Map<String, Object> input = inputs.addEarning((String) c.get("employeeId"), "Claim " + c.get("claimNumber")
                        + " (" + String.valueOf(c.get("claimType")).toLowerCase() + ")", dec(c.get("amount")),
                        HrRows.date(c.get("claimDate")), taxable, null, "hr_claim", id);
                data.put("payrollInputId", input.get("id"));
                data.put("isTaxable", taxable);
            }
            data.put("status", approve ? "APPROVED" : "REJECTED");
            data.put("reviewedBy", user());
            data.put("reviewedAt", Instant.now());
            data.put("reviewNote", str(dto.get("note")));
            Map<String, Object> row = rows.update("HrPayClaim", id, data);
            hr.auditInTx(AuditInput.of("HrPayClaim", id, approve ? "approve" : "reject", Map.of("status", "PENDING"),
                    row));
            return row;
        });
    }

    /** Manager inbox: pending leave and claims of direct reports. */
    public Map<String, Object> approvals() {
        Map<String, Object> me = self();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("leave", rows.query("SELECT r.*, e.\"employeeCode\", e.\"firstName\", e.\"lastName\", t.name AS"
                + " \"leaveType\" FROM \"HrLeaveRequest\" r JOIN \"HrEmployee\" e ON e.id = r.\"employeeId\" JOIN"
                + " \"HrLeaveType\" t ON t.id = r.\"leaveTypeId\" WHERE r.\"organizationId\" = ? AND r.\"deletedAt\" IS NULL"
                + " AND r.status::text = 'PENDING' AND e.\"supervisorId\" = ? ORDER BY r.\"startDate\"", org(),
                me.get("id")));
        out.put("claims", rows.query("SELECT c.*, e.\"employeeCode\", e.\"firstName\", e.\"lastName\" FROM \"HrPayClaim\" c"
                + " JOIN \"HrEmployee\" e ON e.id = c.\"employeeId\" WHERE c.\"organizationId\" = ? AND c.\"deletedAt\" IS"
                + " NULL AND c.status = 'PENDING' AND e.\"supervisorId\" = ? ORDER BY c.\"createdAt\"", org(), me.get("id")));
        return out;
    }
}
