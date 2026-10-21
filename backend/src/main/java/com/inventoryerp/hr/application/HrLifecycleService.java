package com.inventoryerp.hr.application;

import static com.inventoryerp.hr.application.HrDb.org;
import static com.inventoryerp.hr.application.HrDb.user;

import com.inventoryerp.core.domain.Branch;
import com.inventoryerp.hr.domain.HrDepartment;
import com.inventoryerp.hr.domain.HrEmployee;
import com.inventoryerp.hr.domain.HrEmployeeStatusHistory;
import com.inventoryerp.hr.domain.HrEmployeeTransfer;
import com.inventoryerp.hr.domain.HrPosition;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.domain.User;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Employment lifecycle (port of {@code hr-lifecycle.service.ts}): terminate, suspend, reactivate, confirm and
 * transfer, with an immutable status / transfer ledger. History is never rewritten; employment status is not
 * authorization (disabling the login is an explicit opt-in), and {@code isActive} follows the status.
 */
@Service
public class HrLifecycleService {

    /** Statuses in which a person still counts as staff. */
    static final List<String> ACTIVE_STATUSES = List.of("ACTIVE", "PROBATION", "ON_LEAVE");

    private final HrDb hr;

    public HrLifecycleService(HrDb hr) {
        this.hr = hr;
    }

    /** Lifecycle inputs ({@code TerminateEmployeeDto} etc.). */
    public record Change(String reason, Boolean disableAccount, Boolean enableAccount, Boolean resigned,
            Boolean toProbation, String terminationDate) {}

    /** {@code TransferEmployeeDto}. */
    public record Transfer(String toBranchId, String toDepartmentId, String toPositionId, String effectiveDate,
            String reason) {}

    public Map<String, Object> terminate(String employeeId, Change dto) {
        Map<String, Object> employee = findEmployee(employeeId);
        assertNotAlreadyEnded(employee);
        String status = Boolean.TRUE.equals(dto.resigned()) ? "RESIGNED" : "TERMINATED";
        Instant effective = dto.terminationDate() != null && !dto.terminationDate().isEmpty()
                ? HrDb.jsDate(dto.terminationDate()) : Instant.now();
        return transition(employee, status, effective, dto.reason(), Boolean.TRUE.equals(dto.disableAccount()), false,
                Obj.of("terminationDate", effective, "terminationReason", dto.reason()));
    }

    public Map<String, Object> suspend(String employeeId, Change dto) {
        Map<String, Object> employee = findEmployee(employeeId);
        assertNotAlreadyEnded(employee);
        if ("SUSPENDED".equals(employee.get("employmentStatus"))) {
            throw ApiException.badRequest("This employee is already suspended");
        }
        return transition(employee, "SUSPENDED", Instant.now(), dto.reason(), Boolean.TRUE.equals(dto.disableAccount()),
                false, Obj.of("suspendedAt", PosDb.raw(PosSql.NOW), "suspensionReason", dto.reason()));
    }

    public Map<String, Object> reactivate(String employeeId, Change dto) {
        Map<String, Object> employee = findEmployee(employeeId);
        if (ACTIVE_STATUSES.contains(employee.get("employmentStatus"))) {
            throw ApiException.badRequest("This employee is already active");
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("suspendedAt", null);
        extra.put("suspensionReason", null);
        extra.put("terminationDate", null);
        extra.put("terminationReason", null);
        return transition(employee, Boolean.TRUE.equals(dto.toProbation()) ? "PROBATION" : "ACTIVE", Instant.now(),
                dto.reason(), false, Boolean.TRUE.equals(dto.enableAccount()), extra);
    }

    public Map<String, Object> confirm(String employeeId, String reason) {
        Map<String, Object> employee = findEmployee(employeeId);
        if (!"PROBATION".equals(employee.get("employmentStatus"))) {
            throw ApiException.badRequest("Only employees on PROBATION can be confirmed");
        }
        return transition(employee, "ACTIVE", Instant.now(), reason, false, false,
                Obj.of("confirmedAt", PosDb.raw(PosSql.NOW)));
    }

    public Map<String, Object> transfer(String employeeId, Transfer dto) {
        Map<String, Object> employee = findEmployee(employeeId);
        String actor = user();
        String orgId = org();
        Object toBranchId = dto.toBranchId() != null ? dto.toBranchId() : employee.get("branchId");
        Object toDepartmentId = dto.toDepartmentId() != null ? dto.toDepartmentId() : employee.get("departmentId");
        Object toPositionId = dto.toPositionId() != null ? dto.toPositionId() : employee.get("positionId");
        boolean unchanged = Objects.equals(toBranchId, employee.get("branchId"))
                && Objects.equals(toDepartmentId, employee.get("departmentId"))
                && Objects.equals(toPositionId, employee.get("positionId"));
        if (unchanged) {
            throw ApiException.badRequest("Transfer would not change branch, department or position");
        }
        if (toBranchId != null && !toBranchId.equals(employee.get("branchId"))
                && hr.byId(Branch.class, toBranchId) == null) {
            throw ApiException.notFound("Target branch not found in this organization");
        }
        if (toDepartmentId != null && !toDepartmentId.equals(employee.get("departmentId"))
                && hr.byId(HrDepartment.class, toDepartmentId) == null) {
            throw ApiException.notFound("Target department not found in this organization");
        }
        if (toPositionId != null && !toPositionId.equals(employee.get("positionId"))
                && hr.byId(HrPosition.class, toPositionId) == null) {
            throw ApiException.notFound("Target position not found in this organization");
        }
        return hr.inTx(() -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("branchId", toBranchId);
            data.put("departmentId", toDepartmentId);
            data.put("positionId", toPositionId);
            data.put("updatedBy", actor);
            Map<String, Object> updated = hr.update(HrEmployee.class, employeeId, data);
            if (employee.get("userId") != null && !Objects.equals(toBranchId, employee.get("branchId"))) {
                Map<String, Object> u = new LinkedHashMap<>();
                u.put("defaultBranchId", toBranchId);
                u.put("updatedBy", actor);
                hr.where(User.class).eq("id", employee.get("userId")).update(u);
            }
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("organizationId", orgId);
            t.put("employeeId", employeeId);
            t.put("fromBranchId", employee.get("branchId"));
            t.put("toBranchId", toBranchId);
            t.put("fromDepartmentId", employee.get("departmentId"));
            t.put("toDepartmentId", toDepartmentId);
            t.put("fromPositionId", employee.get("positionId"));
            t.put("toPositionId", toPositionId);
            t.put("effectiveDate", dto.effectiveDate() != null && !dto.effectiveDate().isEmpty()
                    ? HrDb.jsDate(dto.effectiveDate()) : PosDb.raw(PosSql.NOW));
            t.put("reason", dto.reason());
            t.put("actorUserId", actor);
            hr.insert(HrEmployeeTransfer.class, t);
            hr.auditInTx(AuditInput.of("HrEmployee", employeeId, "transfer",
                    Obj.of("branchId", employee.get("branchId"), "departmentId", employee.get("departmentId"),
                            "positionId", employee.get("positionId")),
                    Obj.of("branchId", toBranchId, "departmentId", toDepartmentId, "toPositionId", toPositionId,
                            "reason", dto.reason())));
            return updated;
        });
    }

    public Map<String, Object> statusHistory(String employeeId) {
        findEmployee(employeeId);
        List<Map<String, Object>> rows = hr.where(HrEmployeeStatusHistory.class).eq("employeeId", employeeId)
                .list("\"createdAt\" DESC");
        return HrDb.rows(rows, rows.size());
    }

    public Map<String, Object> transferHistory(String employeeId) {
        findEmployee(employeeId);
        List<Map<String, Object>> rows = hr.where(HrEmployeeTransfer.class).eq("employeeId", employeeId)
                .list("\"effectiveDate\" DESC");
        return HrDb.rows(rows, rows.size());
    }

    private Map<String, Object> transition(Map<String, Object> employee, String toStatus, Instant effectiveDate,
            String reason, boolean disableAccount, boolean enableAccount, Map<String, Object> extra) {
        String actor = user();
        String orgId = org();
        Object fromStatus = employee.get("employmentStatus");
        boolean nowActive = ACTIVE_STATUSES.contains(toStatus);
        String id = (String) employee.get("id");
        Object userId = employee.get("userId");
        return hr.inTx(() -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("employmentStatus", toStatus);
            data.put("isActive", nowActive);
            data.put("updatedBy", actor);
            data.putAll(extra);
            Map<String, Object> updated = hr.update(HrEmployee.class, id, data);
            boolean accountDisabled = false;
            if (userId != null && (disableAccount || enableAccount)) {
                boolean enable = enableAccount;
                hr.where(User.class).eq("id", userId).update(Obj.of("isActive", enable, "updatedBy", actor));
                accountDisabled = !enable;
                if (!enable) {
                    hr.jdbc().sql("UPDATE \"RefreshToken\" SET \"revokedAt\" = " + PosSql.NOW
                            + " WHERE \"userId\" = ? AND \"revokedAt\" IS NULL").param(userId).update();
                }
                hr.auditInTx(AuditInput.of("User", (String) userId, "update", Obj.of("isActive", !enable),
                        Obj.of("isActive", enable, "reason", "Employee " + toStatus.toLowerCase() + ": "
                                + (reason == null ? "no reason given" : reason))));
            } else if (userId != null) {
                hr.where(User.class).eq("id", userId).update(Obj.of("updatedBy", actor));
            }
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("organizationId", orgId);
            h.put("employeeId", id);
            h.put("fromStatus", fromStatus);
            h.put("toStatus", toStatus);
            h.put("effectiveDate", effectiveDate);
            h.put("reason", reason);
            h.put("accountDisabled", accountDisabled);
            h.put("actorUserId", actor);
            hr.insert(HrEmployeeStatusHistory.class, h);
            hr.auditInTx(AuditInput.of("HrEmployee", id, "update",
                    Obj.of("employmentStatus", fromStatus, "isActive", employee.get("isActive")),
                    Obj.of("employmentStatus", toStatus, "isActive", nowActive, "reason", reason,
                            "accountDisabled", accountDisabled)));
            return updated;
        });
    }

    private Map<String, Object> findEmployee(String employeeId) {
        Map<String, Object> employee = hr.byId(HrEmployee.class, employeeId);
        if (employee == null) {
            throw ApiException.notFound("Employee not found");
        }
        return employee;
    }

    private static void assertNotAlreadyEnded(Map<String, Object> employee) {
        Object s = employee.get("employmentStatus");
        if ("TERMINATED".equals(s) || "RESIGNED".equals(s)) {
            throw ApiException.badRequest("This employee is already " + s.toString().toLowerCase()
                    + ". Reactivate them first if they were rehired.");
        }
    }
}
