package com.inventoryerp.hr.application;

import static com.inventoryerp.hr.application.HrDb.nz;
import static com.inventoryerp.hr.application.HrDb.org;
import static com.inventoryerp.hr.application.HrDb.truthy;
import static com.inventoryerp.hr.application.HrDb.user;

import com.inventoryerp.hr.domain.HrHoliday;
import com.inventoryerp.hr.domain.HrOvertimeRule;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;

/** Overtime rules (HR-3): administration plus the two reads the payroll engine needs for a run. */
@Service
public class HrOvertimeService {

    private static final List<String> MULTIPLIERS = List.of("weekdayMultiplier", "weekendMultiplier",
            "holidayMultiplier");
    private static final List<String> TARGETS = List.of("departmentId", "positionId", "employmentType");

    private final HrDb hr;

    public HrOvertimeService(HrDb hr) {
        this.hr = hr;
    }

    public Map<String, Object> list(Map<String, String> query) {
        HrDb.Where w = hr.where(HrOvertimeRule.class);
        return HrDb.rows(w.list("code ASC", HrDb.take(query.get("take"), 100, 200), HrDb.skip(query.get("skip"))),
                w.count());
    }

    public Map<String, Object> create(Map<String, Object> dto) {
        if (!truthy(dto.get("code")) || !truthy(dto.get("name"))) {
            throw ApiException.badRequest("code and name are required");
        }
        String code = HrOrgService.upper(dto.get("code"));
        if (hr.where(HrOvertimeRule.class).eq("code", code).first() != null) {
            throw ApiException.badRequest("Overtime rule \"" + code + "\" already exists");
        }
        assertRule(dto);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("organizationId", org());
        data.put("code", code);
        data.put("name", dto.get("name"));
        data.put("appliesTo", blank(dto.get("appliesTo")));
        for (String m : MULTIPLIERS) {
            data.put(m, dto.get(m));
        }
        data.put("weekendDays", nz(blank(dto.get("weekendDays")), "SATURDAY,SUNDAY"));
        data.put("isActive", nz(dto.get("isActive"), true));
        data.put("createdBy", user());
        return hr.insert(HrOvertimeRule.class, data);
    }

    public Map<String, Object> update(String id, Map<String, Object> dto) {
        if (hr.byId(HrOvertimeRule.class, id) == null) {
            throw ApiException.notFound("Overtime rule not found");
        }
        assertRule(dto);
        Map<String, Object> data = new LinkedHashMap<>();
        for (String f : List.of("name", "weekdayMultiplier", "weekendMultiplier", "holidayMultiplier", "weekendDays",
                "isActive")) {
            if (dto.containsKey(f)) {
                data.put(f, dto.get(f));
            }
        }
        if (dto.containsKey("appliesTo")) {
            data.put("appliesTo", blank(dto.get("appliesTo")));
        }
        data.put("updatedBy", user());
        return hr.update(HrOvertimeRule.class, id, data);
    }

    public Map<String, Object> delete(String id) {
        if (hr.byId(HrOvertimeRule.class, id) == null) {
            throw ApiException.notFound("Overtime rule not found");
        }
        return hr.softDelete(HrOvertimeRule.class, id, Obj.of("isActive", false, "updatedBy", user()));
    }

    // ------------------------------------------------------------------ payroll engine reads (package-private)

    /** Active rules; empty → the engine keeps the Nest 1.5 multiplier. */
    List<Map<String, Object>> activeRules() {
        return hr.where(HrOvertimeRule.class).eq("isActive", true).list("code ASC");
    }

    /** Is a date a holiday? A recurring holiday repeats on its month and day every year. */
    Predicate<LocalDate> holidays() {
        Set<LocalDate> fixed = new HashSet<>();
        Set<MonthDay> recurring = new HashSet<>();
        for (Map<String, Object> h : hr.where(HrHoliday.class).list(null)) {
            LocalDate d = ((Instant) h.get("date")).atOffset(ZoneOffset.UTC).toLocalDate();
            fixed.add(d);
            if (Boolean.TRUE.equals(h.get("isRecurring"))) {
                recurring.add(MonthDay.from(d));
            }
        }
        return d -> fixed.contains(d) || recurring.contains(MonthDay.from(d));
    }

    /** Weighted overtime hours of one employee over the window the engine uses for attendance ({@code date < end}). */
    BigDecimal weightedHours(Map<String, Object> rule, Object employeeId, Instant start, Instant end,
            Predicate<LocalDate> holidays) {
        Map<LocalDate, BigDecimal> byDay = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(hr.jdbc().sql("SELECT date, sum(\"overtimeMinutes\") AS o FROM"
                + " \"HrAttendance\" WHERE \"organizationId\" = ? AND \"employeeId\" = ? AND \"deletedAt\" IS NULL AND"
                + " date >= ? AND date < ? GROUP BY date ORDER BY date")
                .params(org(), employeeId, SqlRows.ts(start), SqlRows.ts(end)))) {
            if (r.get("o") != null) {
                byDay.put(((Instant) r.get("date")).atOffset(ZoneOffset.UTC).toLocalDate(),
                        new BigDecimal(r.get("o").toString()));
            }
        }
        return HrOvertimeCalculator.weightedHours(byDay, rule, holidays);
    }

    private static void assertRule(Map<String, Object> dto) {
        for (String m : MULTIPLIERS) {
            if (dto.get(m) != null) {
                double v = HrDb.jsNumber(dto.get(m));
                if (Double.isNaN(v) || v <= 0) {
                    throw ApiException.badRequest(m + " must be a positive number");
                }
            }
        }
        Object days = blank(dto.get("weekendDays"));
        if (days != null) {
            try {
                HrOvertimeCalculator.weekendDays(days.toString());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("weekendDays must list day names, e.g. SATURDAY,SUNDAY");
            }
        }
        Object target = blank(dto.get("appliesTo"));
        if (target != null) {
            String[] parts = target.toString().split(":", 2);
            if (parts.length != 2 || parts[1].isBlank() || !TARGETS.contains(parts[0])) {
                throw ApiException.badRequest("appliesTo must be departmentId:<id>, positionId:<id> or"
                        + " employmentType:<type>");
            }
        }
    }

    private static Object blank(Object v) {
        return v instanceof String s && s.isBlank() ? null : v;
    }
}
