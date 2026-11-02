package com.inventoryerp.hr.application;

import static com.inventoryerp.hr.application.HrDb.nz;
import static com.inventoryerp.hr.application.HrDb.org;
import static com.inventoryerp.hr.application.HrDb.truthy;
import static com.inventoryerp.hr.application.HrDb.user;

import com.inventoryerp.hr.domain.HrEmployee;
import com.inventoryerp.hr.domain.HrPayGroup;
import com.inventoryerp.hr.domain.HrPayrollPeriod;
import com.inventoryerp.hr.domain.HrPayrollRun;
import com.inventoryerp.hr.domain.HrPayrollSettings;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Payroll foundations (HP-0, ADR-S047): organisation payroll settings, pay groups with their calendars, and period
 * locking. The payroll engine calls the package-private hooks; an organisation without a settings row and without
 * pay groups keeps the Nest behaviour.
 */
@Service
public class HrPayrollSetupService {

    static final List<String> ENGINES = List.of("v1", "v2");
    static final List<String> PACKS = List.of("NONE", "UG", "AE");
    static final List<String> PRORATION = List.of("CALENDAR_DAYS", "WORKING_DAYS", "FIXED_30");
    static final List<String> FREQUENCIES = List.of("MONTHLY", "BIWEEKLY", "WEEKLY");
    static final String LOCKED = "LOCKED";

    private static final List<Map<String, Object>> STATUTORY_PACKS = List.of(
            Obj.of("code", "NONE", "name", "No statutory pack", "countryCode", null, "currencyCode", null,
                    "description", "Tax tables and deduction components as configured", "available", true),
            Obj.of("code", "UG", "name", "Uganda", "countryCode", "UG", "currencyCode", "UGX",
                    "description", "PAYE, NSSF (5 % employee, 10 % employer), Local Service Tax", "available", false),
            Obj.of("code", "AE", "name", "United Arab Emirates", "countryCode", "AE", "currencyCode", "AED",
                    "description", "No income tax, GPSSA pension for UAE nationals, end-of-service gratuity, WPS",
                    "available", false));

    private final HrDb hr;

    public HrPayrollSetupService(HrDb hr) {
        this.hr = hr;
    }

    // ------------------------------------------------------------------ settings

    /** The organisation's settings, or the defaults with {@code configured: false} when none were saved. */
    public Map<String, Object> getSettings() {
        Map<String, Object> row = settingsRow();
        Map<String, Object> out = new LinkedHashMap<>(row == null ? defaults() : row);
        out.put("configured", row != null);
        return out;
    }

    /** Create or update the settings row. Engine v2 is refused until it exists (HP-2). */
    public Map<String, Object> updateSettings(Map<String, Object> dto) {
        Map<String, Object> before = settingsRow();
        Map<String, Object> data = new LinkedHashMap<>();
        if (dto.containsKey("engine")) {
            oneOf("engine", dto.get("engine"), ENGINES);
            if ("v2".equals(dto.get("engine"))) {
                throw ApiException.badRequest("Payroll engine v2 is not available yet");
            }
            data.put("engine", dto.get("engine"));
        }
        if (dto.containsKey("statutoryPack")) {
            oneOf("statutoryPack", dto.get("statutoryPack"), PACKS);
            data.put("statutoryPack", dto.get("statutoryPack"));
        }
        if (dto.containsKey("prorationBasis")) {
            oneOf("prorationBasis", dto.get("prorationBasis"), PRORATION);
            data.put("prorationBasis", dto.get("prorationBasis"));
        }
        if (dto.containsKey("weekendDays")) {
            data.put("weekendDays", weekendDays(dto.get("weekendDays")));
        }
        if (dto.containsKey("standardHoursPerDay")) {
            BigDecimal hours = decimal("standardHoursPerDay", dto.get("standardHoursPerDay"));
            if (hours.signum() <= 0 || hours.compareTo(BigDecimal.valueOf(24)) > 0) {
                throw ApiException.badRequest("standardHoursPerDay must be greater than 0 and at most 24");
            }
            data.put("standardHoursPerDay", hours);
        }
        if (dto.containsKey("roundingScale")) {
            Object raw = dto.get("roundingScale");
            if (!(raw instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || n.intValue() < 0
                    || n.intValue() > 4) {
                throw ApiException.badRequest("roundingScale must be a whole number from 0 to 4");
            }
            data.put("roundingScale", n.intValue());
        }
        for (String f : List.of("requireSeparateApprover", "lockPeriodOnApproval")) {
            if (dto.containsKey(f)) {
                if (!(dto.get(f) instanceof Boolean)) {
                    throw ApiException.badRequest(f + " must be a boolean");
                }
                data.put(f, dto.get(f));
            }
        }
        if (dto.containsKey("payslipNote")) {
            data.put("payslipNote", blank(dto.get("payslipNote")));
        }
        return hr.inTx(() -> {
            Map<String, Object> saved;
            if (before == null) {
                Map<String, Object> insert = new LinkedHashMap<>(defaults());
                insert.remove("id");
                insert.putAll(data);
                insert.put("organizationId", org());
                insert.put("createdBy", user());
                saved = hr.insert(HrPayrollSettings.class, insert);
            } else {
                data.put("updatedBy", user());
                saved = hr.update(HrPayrollSettings.class, (String) before.get("id"), data);
            }
            hr.auditInTx(AuditInput.of("HrPayrollSettings", (String) saved.get("id"), before == null ? "create"
                    : "update", before, saved));
            Map<String, Object> out = new LinkedHashMap<>(saved);
            out.put("configured", true);
            return out;
        });
    }

    /** The statutory packs an organisation can choose; {@code available: false} until HP-3 ships the pack. */
    public List<Map<String, Object>> statutoryPacks() {
        return STATUTORY_PACKS;
    }

    private Map<String, Object> defaults() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", null);
        d.put("organizationId", org());
        d.put("engine", "v1");
        d.put("statutoryPack", "NONE");
        d.put("prorationBasis", "CALENDAR_DAYS");
        d.put("weekendDays", "SATURDAY,SUNDAY");
        d.put("standardHoursPerDay", new BigDecimal("8"));
        d.put("roundingScale", currencyScale());
        d.put("requireSeparateApprover", true);
        d.put("lockPeriodOnApproval", true);
        d.put("payslipNote", null);
        return d;
    }

    private int currencyScale() {
        String code = hr.jdbc().sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(org())
                .query(String.class).optional().orElse(null);
        try {
            int digits = code == null ? 2 : Currency.getInstance(code).getDefaultFractionDigits();
            return digits < 0 ? 2 : Math.min(digits, 4);
        } catch (IllegalArgumentException e) {
            return 2;
        }
    }

    static String weekendDays(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return "";
        }
        Set<String> days = new LinkedHashSet<>();
        for (String part : String.valueOf(raw).split(",")) {
            String day = part.trim().toUpperCase();
            try {
                days.add(DayOfWeek.valueOf(day).name());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("Invalid weekend day: " + part.trim());
            }
        }
        if (days.size() > 6) {
            throw ApiException.badRequest("At least one working day is required");
        }
        return String.join(",", days);
    }

    // ------------------------------------------------------------------ pay groups

    public Map<String, Object> listPayGroups(Map<String, String> query) {
        HrDb.Where w = hr.where(HrPayGroup.class);
        if ("true".equals(query.get("active"))) {
            w.eq("isActive", true);
        }
        List<Map<String, Object>> rows = w.list("\"isDefault\" DESC, code ASC", HrDb.take(query.get("take"), 100, 200),
                HrDb.skip(query.get("skip")));
        rows.forEach(this::withMemberCount);
        return HrDb.rows(rows, w.count());
    }

    public Map<String, Object> getPayGroup(String id) {
        return withMemberCount(payGroup(id));
    }

    public Map<String, Object> createPayGroup(Map<String, Object> dto) {
        if (!truthy(dto.get("code")) || !truthy(dto.get("name"))) {
            throw ApiException.badRequest("code and name are required");
        }
        String code = HrOrgService.upper(dto.get("code"));
        if (hr.where(HrPayGroup.class).eq("code", code).first() != null) {
            throw ApiException.badRequest("Pay group \"" + code + "\" already exists");
        }
        Object frequency = nz(dto.get("payFrequency"), "MONTHLY");
        oneOf("payFrequency", frequency, FREQUENCIES);
        LocalDate anchor = anchorDate(frequency, dto.get("anchorDate"));
        int paymentDay = paymentDay(dto.get("paymentDay"));
        boolean isDefault = Boolean.TRUE.equals(dto.get("isDefault"))
                || hr.where(HrPayGroup.class).count() == 0;
        return hr.inTx(() -> {
            if (isDefault) {
                clearDefault();
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("organizationId", org());
            data.put("code", code);
            data.put("name", dto.get("name"));
            data.put("payFrequency", frequency);
            data.put("anchorDate", anchor);
            data.put("paymentDay", paymentDay);
            data.put("isDefault", isDefault);
            data.put("isActive", nz(dto.get("isActive"), true));
            data.put("description", blank(dto.get("description")));
            data.put("createdBy", user());
            Map<String, Object> row = hr.insert(HrPayGroup.class, data);
            hr.auditInTx(AuditInput.of("HrPayGroup", (String) row.get("id"), "create", null, row));
            return withMemberCount(row);
        });
    }

    public Map<String, Object> updatePayGroup(String id, Map<String, Object> dto) {
        Map<String, Object> before = payGroup(id);
        Object frequency = nz(dto.get("payFrequency"), before.get("payFrequency"));
        oneOf("payFrequency", frequency, FREQUENCIES);
        if (dto.containsKey("payFrequency") && !frequency.equals(before.get("payFrequency")) && hasPeriods(id)) {
            throw ApiException.badRequest("The pay frequency cannot change once periods exist for the group");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (dto.containsKey("name")) {
            if (!truthy(dto.get("name"))) {
                throw ApiException.badRequest("name is required");
            }
            data.put("name", dto.get("name"));
        }
        data.put("payFrequency", frequency);
        data.put("anchorDate", anchorDate(frequency, dto.containsKey("anchorDate") ? dto.get("anchorDate")
                : before.get("anchorDate")));
        if (dto.containsKey("paymentDay")) {
            data.put("paymentDay", paymentDay(dto.get("paymentDay")));
        }
        if (dto.containsKey("description")) {
            data.put("description", blank(dto.get("description")));
        }
        boolean makeDefault = Boolean.TRUE.equals(dto.get("isDefault"));
        if (Boolean.FALSE.equals(dto.get("isDefault")) && Boolean.TRUE.equals(before.get("isDefault"))) {
            throw ApiException.badRequest("Make another pay group the default instead");
        }
        if (dto.containsKey("isActive")) {
            if (Boolean.FALSE.equals(dto.get("isActive")) && (Boolean.TRUE.equals(before.get("isDefault"))
                    || makeDefault)) {
                throw ApiException.badRequest("The default pay group cannot be deactivated");
            }
            data.put("isActive", dto.get("isActive"));
        }
        data.put("updatedBy", user());
        return hr.inTx(() -> {
            if (makeDefault) {
                clearDefault();
                data.put("isDefault", true);
            }
            Map<String, Object> row = hr.update(HrPayGroup.class, id, data);
            hr.auditInTx(AuditInput.of("HrPayGroup", id, "update", before, row));
            return withMemberCount(row);
        });
    }

    public Map<String, Object> deletePayGroup(String id) {
        Map<String, Object> group = payGroup(id);
        if (Boolean.TRUE.equals(group.get("isDefault"))) {
            throw ApiException.badRequest("The default pay group cannot be deleted");
        }
        if (directMembers(id) > 0) {
            throw ApiException.badRequest("Move the employees of this pay group to another group first");
        }
        return hr.inTx(() -> {
            Map<String, Object> row = hr.softDelete(HrPayGroup.class, id, Obj.of("isActive", false,
                    "updatedBy", user()));
            hr.auditInTx(AuditInput.of("HrPayGroup", id, "delete", group, null));
            return row;
        });
    }

    /** Employees paid in the group (for the default group: also every employee without a group). */
    public Map<String, Object> listMembers(String id, Map<String, String> query) {
        Map<String, Object> group = payGroup(id);
        HrDb.Where w = hr.where(HrEmployee.class).search(query.get("search"), "firstName", "lastName", "employeeCode");
        memberClause(w, group);
        List<Map<String, Object>> rows = w.list("\"employeeCode\" ASC", HrDb.take(query.get("take"), 100, 500),
                HrDb.skip(query.get("skip")));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> e : rows) {
            Map<String, Object> m = HrDb.pick(e, "id", "employeeCode", "firstName", "lastName", "departmentId",
                    "positionId", "payFrequency", "isActive", "employmentStatus");
            m.put("payGroupId", rawPayGroup(e.get("id")));
            out.add(m);
        }
        return HrDb.rows(out, w.count());
    }

    /** Move employees into the group; {@code null} → back to the default group. */
    public Map<String, Object> assignMembers(String id, Map<String, Object> dto) {
        Map<String, Object> group = payGroup(id);
        if (!Boolean.TRUE.equals(group.get("isActive"))) {
            throw ApiException.badRequest("Pay group is inactive");
        }
        List<Object> ids = PosSql.list(dto.get("employeeIds"));
        if (ids.isEmpty()) {
            throw ApiException.badRequest("employeeIds is required");
        }
        Set<String> wanted = new LinkedHashSet<>();
        ids.forEach(o -> wanted.add(String.valueOf(o)));
        List<Map<String, Object>> found = hr.where(HrEmployee.class).in("id", wanted).list(null);
        if (found.size() != wanted.size()) {
            throw ApiException.badRequest("Some employees were not found");
        }
        return hr.inTx(() -> {
            int moved = hr.jdbc().sql("UPDATE \"HrEmployee\" SET \"payGroupId\" = ?, \"updatedAt\" = CURRENT_TIMESTAMP,"
                    + " \"updatedBy\" = ? WHERE \"organizationId\" = ? AND id IN (" + PosSql.marks(wanted.size())
                    + ")").params(concat(List.of(id, user(), org()), wanted)).update();
            hr.auditInTx(AuditInput.of("HrPayGroup", id, "update", null, Obj.of("assignedEmployeeIds",
                    new ArrayList<>(wanted))));
            return Obj.of("payGroupId", id, "assigned", moved);
        });
    }

    // ------------------------------------------------------------------ calendar

    /** Create the group's periods for a year; existing period codes are skipped. */
    public Map<String, Object> generatePeriods(String id, Map<String, Object> dto) {
        Map<String, Object> group = payGroup(id);
        if (!Boolean.TRUE.equals(group.get("isActive"))) {
            throw ApiException.badRequest("Pay group is inactive");
        }
        Object rawYear = dto.get("year");
        if (!(rawYear instanceof Number n) || n.intValue() < 2000 || n.intValue() > 2100
                || n.doubleValue() != Math.rint(n.doubleValue())) {
            throw ApiException.badRequest("year must be a whole number from 2000 to 2100");
        }
        List<HrPayCalendar.Slot> slots = HrPayCalendar.periods(String.valueOf(group.get("code")),
                String.valueOf(group.get("payFrequency")), (LocalDate) group.get("anchorDate"),
                PosSql.intOf(group.get("paymentDay")), n.intValue());
        return hr.inTx(() -> {
            List<Map<String, Object>> created = new ArrayList<>();
            int skipped = 0;
            for (HrPayCalendar.Slot s : slots) {
                if (hr.where(HrPayrollPeriod.class).eq("periodCode", s.code()).first() != null) {
                    skipped++;
                    continue;
                }
                Map<String, Object> row = hr.insert(HrPayrollPeriod.class, Obj.of("organizationId", org(),
                        "periodCode", s.code(), "periodType", group.get("payFrequency"), "startDate", utc(s.start()),
                        "endDate", utc(s.end()), "status", "OPEN", "payGroupId", id, "paymentDate", s.paymentDate(),
                        "createdBy", user()));
                created.add(periodView(row));
            }
            return Obj.of("created", created, "skipped", skipped);
        });
    }

    /** Periods with the HP-0 columns and their latest run; filter by {@code payGroupId} and {@code year}. */
    public Map<String, Object> calendar(Map<String, String> query) {
        HrDb.Where w = hr.where(HrPayrollPeriod.class);
        if (truthy(query.get("payGroupId"))) {
            w.and("\"payGroupId\" = ?", query.get("payGroupId"));
        }
        if (truthy(query.get("year"))) {
            int year;
            try {
                year = Integer.parseInt(query.get("year"));
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("year must be a number");
            }
            w.and("\"startDate\" >= ? AND \"startDate\" < ?", SqlRows.ts(utc(LocalDate.of(year, 1, 1))),
                    SqlRows.ts(utc(LocalDate.of(year + 1, 1, 1))));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : w.list("\"startDate\" ASC", HrDb.take(query.get("take"), 60, 400),
                HrDb.skip(query.get("skip")))) {
            Map<String, Object> v = periodView(p);
            Map<String, Object> run = hr.where(HrPayrollRun.class).eq("periodId", p.get("id"))
                    .first("\"createdAt\" DESC");
            v.put("run", run == null ? null : HrDb.pick(run, "id", "runNumber", "status", "totalNet"));
            out.add(v);
        }
        return HrDb.rows(out, w.count());
    }

    // ------------------------------------------------------------------ locking

    public Map<String, Object> lockPeriod(String id) {
        Map<String, Object> period = period(id);
        if (LOCKED.equals(period.get("status"))) {
            throw ApiException.badRequest("Payroll period is already locked");
        }
        return hr.inTx(() -> {
            lock(id, user());
            hr.auditInTx(AuditInput.of("HrPayrollPeriod", id, "update", Obj.of("status", period.get("status")),
                    Obj.of("status", LOCKED)));
            return periodView(hr.byId(HrPayrollPeriod.class, id));
        });
    }

    public Map<String, Object> unlockPeriod(String id, Map<String, Object> dto) {
        Map<String, Object> period = period(id);
        if (!LOCKED.equals(period.get("status"))) {
            throw ApiException.badRequest("Payroll period is not locked");
        }
        if (!truthy(dto.get("reason"))) {
            throw ApiException.badRequest("reason is required");
        }
        if (hr.where(HrPayrollRun.class).eq("periodId", id).and("status::text IN ('APPROVED', 'PAID')")
                .first() != null) {
            throw ApiException.badRequest("Reverse the approved payroll run before unlocking its period");
        }
        return hr.inTx(() -> {
            unlock(id);
            hr.auditInTx(AuditInput.of("HrPayrollPeriod", id, "update", Obj.of("status", LOCKED),
                    Obj.of("status", "OPEN", "reason", dto.get("reason"))));
            return periodView(hr.byId(HrPayrollPeriod.class, id));
        });
    }

    // ------------------------------------------------------------------ payroll engine hooks (package-private)

    /** The saved settings row, or {@code null}: no row means the Nest behaviour. */
    Map<String, Object> settingsRow() {
        return hr.where(HrPayrollSettings.class).first();
    }

    void assertPeriodOpen(Map<String, Object> period) {
        if (LOCKED.equals(period.get("status"))) {
            throw ApiException.badRequest("Payroll period is locked");
        }
    }

    /** The payment date of a generated period, or {@code null}. */
    Instant periodPaymentDate(Object periodId) {
        LocalDate d = hr.jdbc().sql("SELECT \"paymentDate\" FROM \"HrPayrollPeriod\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(periodId, org()).query(LocalDate.class).optional().orElse(null);
        return d == null ? null : utc(d);
    }

    /** Employee ids paid in the period's pay group, or {@code null} when the period has no group (everyone). */
    Set<String> periodEmployees(Object periodId) {
        String groupId = hr.jdbc().sql("SELECT \"payGroupId\" FROM \"HrPayrollPeriod\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(periodId, org()).query(String.class).optional().orElse(null);
        if (groupId == null) {
            return null;
        }
        Map<String, Object> group = hr.db().first(HrPayGroup.class, "id = ? AND \"organizationId\" = ?", groupId,
                org());
        HrDb.Where w = hr.where(HrEmployee.class);
        memberClause(w, group == null ? Obj.of("id", groupId, "isDefault", false) : group);
        Set<String> ids = new LinkedHashSet<>();
        w.list(null).forEach(e -> ids.add((String) e.get("id")));
        return ids;
    }

    /** Maker-checker: with the setting on, the user who calculated the run cannot approve it. */
    void assertCanApprove(Map<String, Object> run, String userId) {
        Map<String, Object> s = settingsRow();
        if (s != null && Boolean.TRUE.equals(s.get("requireSeparateApprover")) && userId != null
                && userId.equals(run.get("processedById"))) {
            throw ApiException.forbidden("A payroll run must be approved by a different user than the one who"
                    + " calculated it");
        }
    }

    /** After approval (inside its transaction): lock the period when the setting asks for it. */
    void onApproved(Object periodId, String userId) {
        Map<String, Object> s = settingsRow();
        if (s != null && Boolean.TRUE.equals(s.get("lockPeriodOnApproval"))) {
            lock((String) periodId, userId);
        }
    }

    /** After an approved run is reversed (inside its transaction): reopen a locked period. */
    void onReversed(Object periodId) {
        Map<String, Object> p = hr.byId(HrPayrollPeriod.class, periodId);
        if (p != null && LOCKED.equals(p.get("status"))) {
            unlock((String) periodId);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void lock(String periodId, String userId) {
        hr.jdbc().sql("UPDATE \"HrPayrollPeriod\" SET status = 'LOCKED', \"lockedAt\" = CURRENT_TIMESTAMP,"
                + " \"lockedBy\" = ?, \"updatedAt\" = CURRENT_TIMESTAMP WHERE id = ? AND \"organizationId\" = ?")
                .params(userId, periodId, org()).update();
    }

    private void unlock(String periodId) {
        hr.jdbc().sql("UPDATE \"HrPayrollPeriod\" SET status = 'OPEN', \"lockedAt\" = NULL, \"lockedBy\" = NULL,"
                + " \"updatedAt\" = CURRENT_TIMESTAMP, \"updatedBy\" = ? WHERE id = ? AND \"organizationId\" = ?")
                .params(user(), periodId, org()).update();
    }

    private Map<String, Object> periodView(Map<String, Object> period) {
        Map<String, Object> v = new LinkedHashMap<>(period);
        Map<String, Object> extra = SqlRows.one(hr.jdbc().sql("SELECT \"payGroupId\", \"paymentDate\", \"lockedAt\","
                + " \"lockedBy\" FROM \"HrPayrollPeriod\" WHERE id = ? AND \"organizationId\" = ?")
                .params(period.get("id"), org()));
        if (extra != null) {
            v.putAll(extra);
        }
        return v;
    }

    private Map<String, Object> period(String id) {
        Map<String, Object> p = hr.byId(HrPayrollPeriod.class, id);
        if (p == null) {
            throw ApiException.notFound("Payroll period not found");
        }
        return p;
    }

    private Map<String, Object> payGroup(String id) {
        Map<String, Object> g = hr.byId(HrPayGroup.class, id);
        if (g == null) {
            throw ApiException.notFound("Pay group not found");
        }
        return g;
    }

    private Map<String, Object> withMemberCount(Map<String, Object> group) {
        HrDb.Where w = hr.where(HrEmployee.class).eq("isActive", true);
        memberClause(w, group);
        group.put("employeeCount", w.count());
        return group;
    }

    private void memberClause(HrDb.Where w, Map<String, Object> group) {
        if (Boolean.TRUE.equals(group.get("isDefault"))) {
            w.and("\"payGroupId\" = ? OR \"payGroupId\" IS NULL", group.get("id"));
        } else {
            w.and("\"payGroupId\" = ?", group.get("id"));
        }
    }

    private long directMembers(String groupId) {
        return hr.where(HrEmployee.class).and("\"payGroupId\" = ?", groupId).count();
    }

    private boolean hasPeriods(String groupId) {
        return hr.where(HrPayrollPeriod.class).and("\"payGroupId\" = ?", groupId).count() > 0;
    }

    private String rawPayGroup(Object employeeId) {
        return hr.jdbc().sql("SELECT \"payGroupId\" FROM \"HrEmployee\" WHERE id = ?").param(employeeId)
                .query(String.class).optional().orElse(null);
    }

    private void clearDefault() {
        hr.where(HrPayGroup.class).eq("isDefault", true).update(Obj.of("isDefault", false, "updatedBy", user()));
    }

    private static LocalDate anchorDate(Object frequency, Object raw) {
        if ("MONTHLY".equals(frequency)) {
            return null;
        }
        if (raw instanceof LocalDate d) {
            return d;
        }
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw ApiException.badRequest("anchorDate is required for weekly and bi-weekly pay groups");
        }
        try {
            return LocalDate.parse(String.valueOf(raw).substring(0, Math.min(10, String.valueOf(raw).length())));
        } catch (RuntimeException e) {
            throw ApiException.badRequest("anchorDate must be a date (YYYY-MM-DD)");
        }
    }

    private static int paymentDay(Object raw) {
        if (raw == null) {
            return 0;
        }
        if (!(raw instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || n.intValue() < 0
                || n.intValue() > 31) {
            throw ApiException.badRequest("paymentDay must be a whole number from 0 to 31");
        }
        return n.intValue();
    }

    private static BigDecimal decimal(String field, Object raw) {
        try {
            return new BigDecimal(String.valueOf(raw));
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(field + " must be a number");
        }
    }

    private static void oneOf(String field, Object value, List<String> allowed) {
        if (!(value instanceof String) || !allowed.contains(value)) {
            throw ApiException.badRequest(field + " must be one of " + String.join(", ", allowed));
        }
    }

    private static Object blank(Object v) {
        return v == null || String.valueOf(v).isBlank() ? null : v;
    }

    private static Instant utc(LocalDate d) {
        return d.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Object[] concat(List<Object> head, Set<String> tail) {
        List<Object> all = new ArrayList<>(head);
        all.addAll(tail);
        return all.toArray();
    }
}
