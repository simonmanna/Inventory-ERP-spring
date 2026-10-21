package com.inventoryerp.hr.application;

import static com.inventoryerp.hr.application.HrDb.nz;
import static com.inventoryerp.hr.application.HrDb.org;
import static com.inventoryerp.hr.application.HrDb.truthy;
import static com.inventoryerp.hr.application.HrDb.user;

import com.inventoryerp.hr.domain.HrEmployee;
import com.inventoryerp.hr.domain.HrHoliday;
import com.inventoryerp.hr.domain.HrLeaveBalance;
import com.inventoryerp.hr.domain.HrLeaveRequest;
import com.inventoryerp.hr.domain.HrLeaveType;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.pos.application.PosDb;
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
 * Leave types, annual balances, requests and public holidays (port of {@code hr-leave.service.ts}). Approving a
 * request deducts the balance and stamps every covered day ON_LEAVE in attendance. Dates are server-local (UTC) day
 * starts like the Nest {@code dayStart}.
 */
@Service
public class HrLeaveService {

    private final HrDb hr;
    private final SequenceService seq;
    private final HrAttendanceService attendance;

    public HrLeaveService(HrDb hr, SequenceService seq, HrAttendanceService attendance) {
        this.hr = hr;
        this.seq = seq;
        this.attendance = attendance;
    }

    /** JS {@code new Date(y, m, d)} of the (UTC) calendar day of {@code d}. */
    static Instant dayStart(Object d) {
        Instant dt = HrDb.jsDate(d);
        return dt.atOffset(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    static int year(Object instant) {
        return ((Instant) instant).atOffset(ZoneOffset.UTC).getYear();
    }

    /** decimal.js {@code valueOf()} of a Prisma Decimal. */
    static String decString(Object v) {
        BigDecimal d = PosSql.dec(v);
        return d.signum() == 0 ? "0" : d.stripTrailingZeros().toPlainString();
    }

    // ─── leave types ───

    public Map<String, Object> listTypes(Map<String, String> query) {
        HrDb.Where w = hr.where(HrLeaveType.class);
        return HrDb.rows(w.list("name ASC", HrDb.take(query.get("take"), 100, 200), HrDb.skip(query.get("skip"))),
                w.count());
    }

    public Map<String, Object> createType(Map<String, Object> dto) {
        if (!truthy(dto.get("code")) || !truthy(dto.get("name"))) {
            throw ApiException.badRequest("code and name are required");
        }
        if (hr.where(HrLeaveType.class).eq("code", dto.get("code")).first() != null) {
            throw ApiException.badRequest("Leave type code \"" + PosSql.jsString(dto.get("code")) + "\" already exists");
        }
        return hr.insert(HrLeaveType.class, Obj.of("organizationId", org(), "code", HrOrgService.upper(dto.get("code")),
                "name", dto.get("name"), "daysPerYear", nz(dto.get("daysPerYear"), 0),
                "isPaid", nz(dto.get("isPaid"), true), "carryForwardDays", nz(dto.get("carryForwardDays"), 0),
                "maxConsecutiveDays", dto.get("maxConsecutiveDays"), "isActive", nz(dto.get("isActive"), true),
                "createdBy", user()));
    }

    public Map<String, Object> updateType(String id, Map<String, Object> dto) {
        if (hr.byId(HrLeaveType.class, id) == null) {
            throw ApiException.notFound("Leave type not found");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        for (String f : List.of("name", "daysPerYear", "isPaid", "carryForwardDays", "maxConsecutiveDays", "isActive")) {
            if (dto.containsKey(f)) {
                data.put(f, dto.get(f));
            }
        }
        data.put("updatedBy", user());
        return hr.update(HrLeaveType.class, id, data);
    }

    public Map<String, Object> deleteType(String id) {
        if (hr.byId(HrLeaveType.class, id) == null) {
            throw ApiException.notFound("Leave type not found");
        }
        if (hr.where(HrLeaveRequest.class).eq("leaveTypeId", id).count() > 0) {
            throw ApiException.badRequest("Leave type has requests — deactivate instead of deleting");
        }
        return hr.softDelete(HrLeaveType.class, id, Obj.of("isActive", false, "updatedBy", user()));
    }

    // ─── balances ───

    /** The (employee, type, year) balance row, created at the type's yearly allowance when missing. */
    private Map<String, Object> ensureBalance(Object employeeId, Object leaveTypeId, Object year) {
        Map<String, Object> balance = hr.where(HrLeaveBalance.class).eq("employeeId", employeeId)
                .eq("leaveTypeId", leaveTypeId).eq("year", year).first();
        if (balance != null) {
            return balance;
        }
        Map<String, Object> leaveType = hr.byId(HrLeaveType.class, leaveTypeId);
        return hr.insert(HrLeaveBalance.class, Obj.of("organizationId", org(), "employeeId", employeeId,
                "leaveTypeId", leaveTypeId, "year", year,
                "accruedDays", leaveType == null ? 0 : nz(leaveType.get("daysPerYear"), 0)));
    }

    public Map<String, Object> listBalances(Map<String, String> query) {
        HrDb.Where w = hr.where(HrLeaveBalance.class).eqIf("employeeId", query.get("employeeId"));
        if (truthy(query.get("year"))) {
            w.and("year = ?", (int) HrDb.jsNumber(query.get("year")));
        }
        List<Map<String, Object>> rows = w.list("year DESC, \"createdAt\" DESC", HrDb.take(query.get("take"), 100, 200),
                HrDb.skip(query.get("skip")));
        hr.oneAll(rows, "employee", HrEmployee.class, "employeeId", HrAttendanceService.EMPLOYEE_REF);
        hr.oneAll(rows, "leaveType", HrLeaveType.class, "leaveTypeId");
        return HrDb.rows(rows, w.count());
    }

    /** Manual grant / revoke on a balance (Nest adds the delta to the Decimal by string concatenation — kept). */
    public Map<String, Object> adjustBalance(Map<String, Object> dto) {
        if (!truthy(dto.get("employeeId")) || !truthy(dto.get("leaveTypeId"))) {
            throw ApiException.badRequest("employeeId and leaveTypeId are required");
        }
        Object year = nz(dto.get("year"), LocalDate.now(ZoneOffset.UTC).getYear());
        return hr.inTx(() -> {
            Map<String, Object> balance = ensureBalance(dto.get("employeeId"), dto.get("leaveTypeId"), year);
            Object delta = nz(dto.get("adjustedDays"), 0);
            BigDecimal next;
            try {
                next = new BigDecimal(decString(balance.get("adjustedDays")) + PosSql.jsString(delta));
            } catch (NumberFormatException e) {
                throw new IllegalStateException("Invalid value for argument `adjustedDays`: invalid decimal.", e);
            }
            Map<String, Object> updated = hr.update(HrLeaveBalance.class, (String) balance.get("id"),
                    Obj.of("adjustedDays", next, "createdBy", user()));
            hr.auditInTx(AuditInput.of("HrLeaveBalance", (String) balance.get("id"), "adjust",
                    Obj.of("adjustedDays", balance.get("adjustedDays")),
                    Obj.of("adjustedDays", updated.get("adjustedDays"), "delta", delta, "employeeId", dto.get("employeeId"),
                            "leaveTypeId", dto.get("leaveTypeId"), "year", year, "reason", dto.get("reason"))));
            return updated;
        });
    }

    // ─── requests ───

    public Map<String, Object> listRequests(Map<String, String> query) {
        HrDb.Where w = hr.where(HrLeaveRequest.class).eqIf("employeeId", query.get("employeeId"))
                .eqIf("leaveTypeId", query.get("leaveTypeId")).eqIf("status", query.get("status"));
        if (truthy(query.get("from"))) {
            w.and("\"startDate\" >= ?", SqlRows.ts(dayStart(query.get("from"))));
        }
        List<Map<String, Object>> rows = w.list("\"createdAt\" DESC", HrDb.take(query.get("take"), 50, 200),
                HrDb.skip(query.get("skip")));
        hr.oneAll(rows, "employee", HrEmployee.class, "employeeId", HrAttendanceService.EMPLOYEE_REF);
        hr.oneAll(rows, "leaveType", HrLeaveType.class, "leaveTypeId");
        return HrDb.rows(rows, w.count());
    }

    private static double available(Map<String, Object> b) {
        return PosSql.num(b.get("accruedDays")) + PosSql.num(b.get("adjustedDays")) - PosSql.num(b.get("usedDays"));
    }

    public Map<String, Object> createRequest(Map<String, Object> dto) {
        String orgId = org();
        String userId = user();
        if (!truthy(dto.get("employeeId")) || !truthy(dto.get("leaveTypeId")) || !truthy(dto.get("startDate"))
                || !truthy(dto.get("endDate"))) {
            throw ApiException.badRequest("employeeId, leaveTypeId, startDate and endDate are required");
        }
        Instant startDate = dayStart(dto.get("startDate"));
        Instant endDate = dayStart(dto.get("endDate"));
        if (endDate.isBefore(startDate)) {
            throw ApiException.badRequest("endDate must be after startDate");
        }
        long days = Math.round((endDate.toEpochMilli() - startDate.toEpochMilli()) / 86400000.0) + 1;
        String requestCode = seq.next("hr_leave_request", "LV-", 6);
        return hr.inTx(() -> {
            Map<String, Object> clash = hr.where(HrLeaveRequest.class).eq("employeeId", dto.get("employeeId"))
                    .and("status::text IN ('PENDING', 'APPROVED')").and("\"startDate\" <= ?", SqlRows.ts(endDate))
                    .and("\"endDate\" >= ?", SqlRows.ts(startDate)).first();
            if (clash != null) {
                throw ApiException.badRequest("Overlaps leave request " + clash.get("requestCode") + " ("
                        + clash.get("status").toString().toLowerCase() + "), which already covers part of those dates");
            }
            Map<String, Object> balance = ensureBalance(dto.get("employeeId"), dto.get("leaveTypeId"), year(startDate));
            double available = available(balance);
            boolean override = Boolean.TRUE.equals(dto.get("overrideBalance"));
            if (override && !truthy(dto.get("reason"))) {
                throw ApiException.badRequest(
                        "overrideBalance requires a reason explaining why the balance is being exceeded");
            }
            if (!override && available < days) {
                throw ApiException.badRequest("Insufficient leave balance: " + PosSql.jsString(available)
                        + " day(s) available, " + days + " requested");
            }
            Map<String, Object> created = hr.insert(HrLeaveRequest.class, Obj.of("organizationId", orgId,
                    "requestCode", requestCode, "employeeId", dto.get("employeeId"), "leaveTypeId", dto.get("leaveTypeId"),
                    "startDate", startDate, "endDate", endDate, "days", days, "reason", dto.get("reason"),
                    "notes", dto.get("notes"), "createdBy", userId));
            Map<String, Object> nv = Obj.of("requestCode", requestCode, "employeeId", dto.get("employeeId"),
                    "leaveTypeId", dto.get("leaveTypeId"), "days", days, "balanceOverridden", override);
            if (override) {
                nv.put("overrideReason", dto.get("reason"));
            }
            hr.auditInTx(AuditInput.of("HrLeaveRequest", (String) created.get("id"), "create", null, nv));
            return created;
        });
    }

    public Map<String, Object> approveRequest(String id, Map<String, Object> dto) {
        String userId = user();
        Map<String, Object> row = hr.byId(HrLeaveRequest.class, id);
        if (row == null) {
            throw ApiException.notFound("Leave request not found");
        }
        if (!"PENDING".equals(row.get("status"))) {
            throw ApiException.badRequest("Only PENDING requests can be approved");
        }
        if (userId != null) {
            Map<String, Object> approver = hr.where(HrEmployee.class).eq("userId", userId).first();
            if (approver != null && approver.get("id").equals(row.get("employeeId"))) {
                throw ApiException.forbidden("You cannot approve your own leave request");
            }
        }
        return hr.inTx(() -> {
            Map<String, Object> balance = ensureBalance(row.get("employeeId"), row.get("leaveTypeId"),
                    year(row.get("startDate")));
            double available = available(balance);
            double days = PosSql.num(row.get("days"));
            if (available < days) {
                throw ApiException.badRequest("Insufficient balance: " + PosSql.jsString(available)
                        + " day(s) available, " + decString(row.get("days")) + " on request");
            }
            Map<String, Object> updated = hr.update(HrLeaveRequest.class, id, Obj.of("status", "APPROVED",
                    "approverId", userId, "approvedAt", PosDb.raw(PosSql.NOW),
                    "notes", nz(dto.get("notes"), row.get("notes")), "updatedBy", userId));
            double usedAfter = PosSql.num(balance.get("usedDays")) + days;
            hr.update(HrLeaveBalance.class, (String) balance.get("id"), Obj.of("usedDays", PosSql.decOf(usedAfter)));
            hr.auditInTx(AuditInput.of("HrLeaveRequest", id, "approve", Obj.of("status", row.get("status")),
                    Obj.of("status", "APPROVED", "approverId", userId, "days", row.get("days"),
                            "balanceAfter", PosSql.js(usedAfter))));
            Instant cursor = (Instant) row.get("startDate");
            Instant end = (Instant) row.get("endDate");
            while (!cursor.isAfter(end)) {
                attendance.markLeaveDay((String) row.get("employeeId"), cursor, (String) row.get("requestCode"));
                cursor = HrDb.addDays(cursor, 1);
            }
            return updated;
        });
    }

    public Map<String, Object> rejectRequest(String id, Map<String, Object> dto) {
        String userId = user();
        Map<String, Object> row = hr.byId(HrLeaveRequest.class, id);
        if (row == null) {
            throw ApiException.notFound("Leave request not found");
        }
        if (!"PENDING".equals(row.get("status"))) {
            throw ApiException.badRequest("Only PENDING requests can be rejected");
        }
        return hr.inTx(() -> {
            Object notes = truthy(dto.get("reason"))
                    ? ((row.get("notes") == null ? "" : row.get("notes")) + "\nRejected: " + PosSql.jsString(dto.get("reason")))
                            .trim()
                    : row.get("notes");
            Map<String, Object> updated = hr.update(HrLeaveRequest.class, id, Obj.of("status", "REJECTED",
                    "notes", notes, "updatedBy", userId));
            hr.auditInTx(AuditInput.of("HrLeaveRequest", id, "reject", Obj.of("status", row.get("status")),
                    Obj.of("status", "REJECTED", "rejectedBy", userId, "reason", dto.get("reason"))));
            return updated;
        });
    }

    public Map<String, Object> cancelRequest(String id) {
        String userId = user();
        Map<String, Object> row = hr.byId(HrLeaveRequest.class, id);
        if (row == null) {
            throw ApiException.notFound("Leave request not found");
        }
        Object status = row.get("status");
        if (!"PENDING".equals(status) && !"APPROVED".equals(status)) {
            throw ApiException.badRequest("Only PENDING/APPROVED requests can be cancelled");
        }
        return hr.inTx(() -> {
            Map<String, Object> updated = hr.update(HrLeaveRequest.class, id, Obj.of("status", "CANCELLED",
                    "updatedBy", userId));
            if ("APPROVED".equals(status)) {
                Map<String, Object> balance = ensureBalance(row.get("employeeId"), row.get("leaveTypeId"),
                        year(row.get("startDate")));
                double used = Math.max(0, PosSql.num(balance.get("usedDays")) - PosSql.num(row.get("days")));
                hr.update(HrLeaveBalance.class, (String) balance.get("id"), Obj.of("usedDays", PosSql.decOf(used)));
            }
            hr.auditInTx(AuditInput.of("HrLeaveRequest", id, "cancel", Obj.of("status", status),
                    Obj.of("status", "CANCELLED", "daysReturned", "APPROVED".equals(status) ? row.get("days") : 0)));
            return updated;
        });
    }

    // ─── holidays ───

    public Map<String, Object> listHolidays(Map<String, String> query) {
        HrDb.Where w = hr.where(HrHoliday.class);
        if (truthy(query.get("year"))) {
            int y = (int) HrDb.jsNumber(query.get("year"));
            w.and("date >= ? AND date < ?", SqlRows.ts(LocalDate.of(y, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant()),
                    SqlRows.ts(LocalDate.of(y + 1, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant()));
        }
        return HrDb.rows(w.list("date ASC", HrDb.take(query.get("take"), 100, 200), HrDb.skip(query.get("skip"))),
                w.count());
    }

    public Map<String, Object> createHoliday(Map<String, Object> dto) {
        if (!truthy(dto.get("name")) || !truthy(dto.get("date"))) {
            throw ApiException.badRequest("name and date are required");
        }
        return hr.insert(HrHoliday.class, Obj.of("organizationId", org(), "name", dto.get("name"),
                "date", dayStart(dto.get("date")), "isRecurring", nz(dto.get("isRecurring"), false), "createdBy", user()));
    }

    public Map<String, Object> updateHoliday(String id, Map<String, Object> dto) {
        Map<String, Object> row = hr.byId(HrHoliday.class, id);
        if (row == null) {
            throw ApiException.notFound("Holiday not found");
        }
        return hr.update(HrHoliday.class, id, Obj.of("name", nz(dto.get("name"), row.get("name")),
                "date", truthy(dto.get("date")) ? dayStart(dto.get("date")) : row.get("date"),
                "isRecurring", nz(dto.get("isRecurring"), row.get("isRecurring")), "updatedBy", user()));
    }

    public Map<String, Object> deleteHoliday(String id) {
        if (hr.byId(HrHoliday.class, id) == null) {
            throw ApiException.notFound("Holiday not found");
        }
        return hr.softDelete(HrHoliday.class, id, Obj.of("updatedBy", user()));
    }
}
