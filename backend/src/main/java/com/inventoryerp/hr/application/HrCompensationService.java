package com.inventoryerp.hr.application;

import static com.inventoryerp.hr.application.HrRows.dec;
import static com.inventoryerp.hr.application.HrRows.org;
import static com.inventoryerp.hr.application.HrRows.str;
import static com.inventoryerp.hr.application.HrRows.user;

import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Compensation (HP-1, ADR-S048): salary grades, the pay element catalogue, salary structures, effective-dated employee
 * compensation with approved revisions, and per-employee pay elements. The payroll engine v2 reads
 * {@link #compensationOn} and {@link #elementsFor}.
 */
@Service
public class HrCompensationService {

    static final List<String> KINDS = List.of("EARNING", "DEDUCTION", "EMPLOYER_CONTRIBUTION", "INFO");
    static final List<String> METHODS = List.of("FIXED", "PERCENT_OF_BASIC", "PERCENT_OF_GROSS", "FORMULA");

    /** One resolved pay element for an employee in a period. */
    public record Element(String elementId, String code, String name, String kind, String calcMethod,
            BigDecimal amount, BigDecimal rate, String formula, boolean taxable, boolean pensionable, boolean prorated,
            String glAccountId, int sortOrder, String source) {}

    private final HrRows rows;
    private final HrDb hr;
    private final HrPayrollSetupService setup;

    public HrCompensationService(HrRows rows, HrDb hr, HrPayrollSetupService setup) {
        this.rows = rows;
        this.hr = hr;
        this.setup = setup;
    }

    // ------------------------------------------------------------------ grades

    public Map<String, Object> listGrades() {
        List<Map<String, Object>> list = rows.list("HrSalaryGrade", null, "code ASC");
        return HrRows.page(list, list.size());
    }

    public Map<String, Object> saveGrade(String id, Map<String, Object> dto) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (id == null || dto.containsKey("code")) {
            String code = upperCode(dto.get("code"));
            if (rows.first("HrSalaryGrade", "code = ? AND id <> ?", null, code, id == null ? "" : id) != null) {
                throw ApiException.badRequest("Salary grade \"" + code + "\" already exists");
            }
            data.put("code", code);
        }
        if (id == null || dto.containsKey("name")) {
            data.put("name", required(dto.get("name"), "name"));
        }
        for (String f : List.of("minAmount", "midAmount", "maxAmount")) {
            if (dto.containsKey(f)) {
                BigDecimal v = dec(dto.get(f));
                if (v != null && v.signum() < 0) {
                    throw ApiException.badRequest(f + " cannot be negative");
                }
                data.put(f, v);
            }
        }
        BigDecimal min = dec(dto.get("minAmount"));
        BigDecimal max = dec(dto.get("maxAmount"));
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw ApiException.badRequest("minAmount cannot exceed maxAmount");
        }
        copy(dto, data, "isActive", "description");
        return save("HrSalaryGrade", id, data);
    }

    public Map<String, Object> deleteGrade(String id) {
        rows.require("HrSalaryGrade", id, "Salary grade not found");
        if (rows.count("HrEmployeeCompensation", "\"gradeId\" = ? AND status IN ('ACTIVE', 'PENDING')", id) > 0) {
            throw ApiException.badRequest("The grade is used by employee compensation");
        }
        rows.softDelete("HrSalaryGrade", id);
        return Map.of("id", id, "deleted", true);
    }

    // ------------------------------------------------------------------ pay elements

    public Map<String, Object> listElements(Map<String, String> query) {
        String kind = query.get("kind");
        List<Map<String, Object>> list = kind == null ? rows.list("HrPayElement", null, "\"sortOrder\" ASC, code ASC")
                : rows.list("HrPayElement", "kind = ?", "\"sortOrder\" ASC, code ASC", kind);
        return HrRows.page(list, list.size());
    }

    public Map<String, Object> saveElement(String id, Map<String, Object> dto) {
        Map<String, Object> before = id == null ? null : rows.require("HrPayElement", id, "Pay element not found");
        Map<String, Object> data = new LinkedHashMap<>();
        if (id == null || dto.containsKey("code")) {
            String code = upperCode(dto.get("code"));
            if (rows.first("HrPayElement", "code = ? AND id <> ?", null, code, id == null ? "" : id) != null) {
                throw ApiException.badRequest("Pay element \"" + code + "\" already exists");
            }
            data.put("code", code);
        }
        if (id == null || dto.containsKey("name")) {
            data.put("name", required(dto.get("name"), "name"));
        }
        Object kind = dto.containsKey("kind") ? dto.get("kind") : before == null ? null : before.get("kind");
        HrRows.oneOf("kind", kind, KINDS);
        data.put("kind", kind);
        Object method = dto.containsKey("calcMethod") ? dto.get("calcMethod")
                : before == null ? "FIXED" : before.get("calcMethod");
        HrRows.oneOf("calcMethod", method, METHODS);
        data.put("calcMethod", method);
        copy(dto, data, "isTaxable", "isPensionable", "isRecurring", "isProrated", "glAccountId", "sortOrder",
                "isActive", "description");
        if (dto.containsKey("amount")) {
            data.put("amount", dec(dto.get("amount")));
        }
        if (dto.containsKey("rate")) {
            data.put("rate", dec(dto.get("rate")));
        }
        if (dto.containsKey("formula")) {
            data.put("formula", str(dto.get("formula")));
        }
        Map<String, Object> merged = new LinkedHashMap<>(before == null ? Map.of() : before);
        merged.putAll(data);
        validateValue(String.valueOf(method), merged.get("amount"), merged.get("rate"), merged.get("formula"));
        return save("HrPayElement", id, data);
    }

    public Map<String, Object> deleteElement(String id) {
        rows.require("HrPayElement", id, "Pay element not found");
        if (rows.count("HrEmployeePayElement", "\"elementId\" = ? AND (\"effectiveTo\" IS NULL OR \"effectiveTo\" >="
                + " CURRENT_DATE)", id) > 0) {
            throw ApiException.badRequest("The element is assigned to employees; end those assignments first");
        }
        rows.softDelete("HrPayElement", id);
        return Map.of("id", id, "deleted", true);
    }

    private static void validateValue(String method, Object amount, Object rate, Object formula) {
        switch (method) {
            case "FIXED" -> {
                if (dec(amount) == null) {
                    throw ApiException.badRequest("FIXED elements need an amount");
                }
            }
            case "PERCENT_OF_BASIC", "PERCENT_OF_GROSS" -> {
                if (dec(rate) == null) {
                    throw ApiException.badRequest("Percentage elements need a rate");
                }
            }
            default -> {
                String err = HrFormula.validate(str(formula));
                if (err != null) {
                    throw ApiException.badRequest("Invalid formula: " + err);
                }
            }
        }
    }

    // ------------------------------------------------------------------ structures

    public Map<String, Object> listStructures() {
        List<Map<String, Object>> list = rows.list("HrSalaryStructure", null, "code ASC");
        return HrRows.page(list, list.size());
    }

    public Map<String, Object> saveStructure(String id, Map<String, Object> dto) {
        if (id != null) {
            rows.require("HrSalaryStructure", id, "Salary structure not found");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (id == null || dto.containsKey("code")) {
            String code = upperCode(dto.get("code"));
            if (rows.first("HrSalaryStructure", "code = ? AND id <> ?", null, code, id == null ? "" : id) != null) {
                throw ApiException.badRequest("Salary structure \"" + code + "\" already exists");
            }
            data.put("code", code);
        }
        if (id == null || dto.containsKey("name")) {
            data.put("name", required(dto.get("name"), "name"));
        }
        if (dto.containsKey("gradeId")) {
            String gradeId = str(dto.get("gradeId"));
            if (gradeId != null) {
                rows.require("HrSalaryGrade", gradeId, "Salary grade not found");
            }
            data.put("gradeId", gradeId);
        }
        if (id == null || dto.containsKey("lines")) {
            List<Map<String, Object>> lines = new ArrayList<>();
            List<String> seen = new ArrayList<>();
            for (Object o : PosSql.list(dto.get("lines"))) {
                Map<String, Object> l = PosSql.map(o);
                String elementId = str(l.get("elementId"));
                Map<String, Object> el = rows.require("HrPayElement", elementId, "Pay element not found");
                if (seen.contains(elementId)) {
                    throw ApiException.badRequest("Element " + el.get("code") + " is listed twice");
                }
                seen.add(elementId);
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("elementId", elementId);
                line.put("amount", dec(l.get("amount")));
                line.put("rate", dec(l.get("rate")));
                line.put("formula", str(l.get("formula")));
                validateValue(String.valueOf(el.get("calcMethod")), nz(line.get("amount"), el.get("amount")),
                        nz(line.get("rate"), el.get("rate")), nz(line.get("formula"), el.get("formula")));
                lines.add(line);
            }
            data.put("lines", lines);
        }
        copy(dto, data, "isActive", "description");
        return save("HrSalaryStructure", id, data);
    }

    public Map<String, Object> deleteStructure(String id) {
        rows.require("HrSalaryStructure", id, "Salary structure not found");
        if (rows.count("HrEmployeeCompensation", "\"structureId\" = ? AND status IN ('ACTIVE', 'PENDING')", id) > 0) {
            throw ApiException.badRequest("The structure is used by employee compensation");
        }
        rows.softDelete("HrSalaryStructure", id);
        return Map.of("id", id, "deleted", true);
    }

    // ------------------------------------------------------------------ employee compensation

    public Map<String, Object> history(String employeeId) {
        employee(employeeId);
        List<Map<String, Object>> list = rows.list("HrEmployeeCompensation", "\"employeeId\" = ?",
                "\"effectiveFrom\" DESC, \"createdAt\" DESC", employeeId);
        Map<String, Object> out = HrRows.page(list, list.size());
        out.put("current", compensationOn(employeeId, LocalDate.now(ZoneOffset.UTC)));
        return out;
    }

    public Map<String, Object> listRevisions(Map<String, String> query) {
        String status = query.getOrDefault("status", "PENDING");
        List<Map<String, Object>> list = rows.query("SELECT c.*, e.\"employeeCode\", e.\"firstName\", e.\"lastName\""
                + " FROM \"HrEmployeeCompensation\" c JOIN \"HrEmployee\" e ON e.id = c.\"employeeId\" WHERE"
                + " c.\"organizationId\" = ? AND c.\"deletedAt\" IS NULL AND c.status = ? ORDER BY c.\"createdAt\" DESC"
                + " LIMIT 500", org(), status);
        return HrRows.page(list, list.size());
    }

    /** Propose a salary revision; it takes effect when approved. */
    public Map<String, Object> propose(String employeeId, Map<String, Object> dto) {
        Map<String, Object> emp = employee(employeeId);
        LocalDate from = HrRows.date(dto.get("effectiveFrom"));
        if (from == null) {
            throw ApiException.badRequest("effectiveFrom is required");
        }
        BigDecimal basic = dec(dto.get("basicSalary"));
        if (basic == null || basic.signum() < 0) {
            throw ApiException.badRequest("basicSalary must be zero or more");
        }
        String gradeId = str(dto.get("gradeId"));
        if (gradeId != null) {
            Map<String, Object> grade = rows.require("HrSalaryGrade", gradeId, "Salary grade not found");
            BigDecimal min = dec(grade.get("minAmount"));
            BigDecimal max = dec(grade.get("maxAmount"));
            boolean outside = (min != null && basic.compareTo(min) < 0) || (max != null && basic.compareTo(max) > 0);
            if (outside && !Boolean.TRUE.equals(dto.get("allowOutsideBand"))) {
                throw ApiException.badRequest("Basic salary is outside the band of grade " + grade.get("code"));
            }
        }
        String structureId = str(dto.get("structureId"));
        if (structureId != null) {
            rows.require("HrSalaryStructure", structureId, "Salary structure not found");
        }
        if (rows.count("HrEmployeeCompensation", "\"employeeId\" = ? AND status = 'PENDING'", employeeId) > 0) {
            throw ApiException.badRequest("This employee already has a pending salary revision");
        }
        Map<String, Object> current = compensationOn(employeeId, from);
        BigDecimal previous = current != null ? dec(current.get("basicSalary")) : dec(emp.get("baseSalary"));
        return hr.inTx(() -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("employeeId", employeeId);
            data.put("effectiveFrom", from);
            data.put("basicSalary", basic);
            data.put("gradeId", gradeId);
            data.put("structureId", structureId);
            data.put("status", "PENDING");
            data.put("reason", str(dto.get("reason")));
            data.put("previousBasic", previous);
            Map<String, Object> row = rows.insert("HrEmployeeCompensation", data);
            hr.auditInTx(AuditInput.of("HrEmployeeCompensation", (String) row.get("id"), "create", null, row));
            return row;
        });
    }

    public Map<String, Object> approve(String id) {
        Map<String, Object> rev = rows.require("HrEmployeeCompensation", id, "Salary revision not found");
        if (!"PENDING".equals(rev.get("status"))) {
            throw ApiException.badRequest("Only a pending salary revision can be approved");
        }
        Map<String, Object> s = setup.settingsRow();
        boolean separate = s == null || Boolean.TRUE.equals(s.get("requireSeparateApprover"));
        if (separate && user() != null && user().equals(rev.get("createdBy"))) {
            throw ApiException.forbidden("A salary revision must be approved by a different user than the one who"
                    + " proposed it");
        }
        LocalDate from = HrRows.date(rev.get("effectiveFrom"));
        String employeeId = (String) rev.get("employeeId");
        if (rows.count("HrEmployeeCompensation", "\"employeeId\" = ? AND status = 'ACTIVE' AND \"effectiveFrom\" >= ?",
                employeeId, from) > 0) {
            throw ApiException.badRequest("A compensation effective on or after " + from + " is already active");
        }
        return hr.inTx(() -> {
            rows.jdbc().sql("UPDATE \"HrEmployeeCompensation\" SET status = 'SUPERSEDED', \"effectiveTo\" = ?,"
                    + " \"updatedAt\" = CURRENT_TIMESTAMP, \"updatedBy\" = ? WHERE \"employeeId\" = ? AND"
                    + " \"organizationId\" = ? AND status = 'ACTIVE' AND \"deletedAt\" IS NULL")
                    .params(java.sql.Date.valueOf(from.minusDays(1)), user(), employeeId, org()).update();
            Map<String, Object> row = rows.update("HrEmployeeCompensation", id, Map.of("status", "ACTIVE",
                    "approvedBy", user(), "approvedAt", java.time.Instant.now()));
            if (!from.isAfter(LocalDate.now(ZoneOffset.UTC))) {
                syncBaseSalary(employeeId, dec(row.get("basicSalary")));
            }
            hr.auditInTx(AuditInput.of("HrEmployeeCompensation", id, "approve", Map.of("status", "PENDING"), row));
            return row;
        });
    }

    public Map<String, Object> reject(String id, Map<String, Object> dto) {
        Map<String, Object> rev = rows.require("HrEmployeeCompensation", id, "Salary revision not found");
        if (!"PENDING".equals(rev.get("status"))) {
            throw ApiException.badRequest("Only a pending salary revision can be rejected");
        }
        String reason = str(dto.get("reason"));
        if (reason == null) {
            throw ApiException.badRequest("reason is required");
        }
        return hr.inTx(() -> {
            Map<String, Object> row = rows.update("HrEmployeeCompensation", id, Map.of("status", "REJECTED",
                    "rejectedReason", reason));
            hr.auditInTx(AuditInput.of("HrEmployeeCompensation", id, "reject", Map.of("status", "PENDING"), row));
            return row;
        });
    }

    /** Daily: approved revisions that became effective today update the employee's base salary (engine v1). */
    public int syncDueBaseSalaries() {
        int n = 0;
        for (Map<String, Object> c : rows.list("HrEmployeeCompensation", "status = 'ACTIVE' AND \"effectiveFrom\" <="
                + " CURRENT_DATE AND (\"effectiveTo\" IS NULL OR \"effectiveTo\" >= CURRENT_DATE)", null)) {
            n += syncBaseSalary((String) c.get("employeeId"), dec(c.get("basicSalary")));
        }
        return n;
    }

    private int syncBaseSalary(String employeeId, BigDecimal basic) {
        return rows.jdbc().sql("UPDATE \"HrEmployee\" SET \"baseSalary\" = ?, \"updatedAt\" = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND \"organizationId\" = ? AND (\"baseSalary\" IS NULL OR \"baseSalary\" <> ?)")
                .params(basic, employeeId, org(), basic).update();
    }

    // ------------------------------------------------------------------ employee elements

    public Map<String, Object> listEmployeeElements(String employeeId) {
        employee(employeeId);
        List<Map<String, Object>> list = rows.query("SELECT a.*, p.code, p.name, p.kind, p.\"calcMethod\" FROM"
                + " \"HrEmployeePayElement\" a JOIN \"HrPayElement\" p ON p.id = a.\"elementId\" WHERE"
                + " a.\"organizationId\" = ? AND a.\"employeeId\" = ? AND a.\"deletedAt\" IS NULL ORDER BY"
                + " a.\"effectiveFrom\" DESC", org(), employeeId);
        return HrRows.page(list, list.size());
    }

    public Map<String, Object> saveEmployeeElement(String employeeId, String id, Map<String, Object> dto) {
        employee(employeeId);
        Map<String, Object> before = id == null ? null : rows.require("HrEmployeePayElement", id,
                "Employee pay element not found");
        String elementId = before != null ? (String) before.get("elementId") : str(dto.get("elementId"));
        Map<String, Object> el = rows.require("HrPayElement", elementId, "Pay element not found");
        Map<String, Object> data = new LinkedHashMap<>();
        if (before == null) {
            data.put("employeeId", employeeId);
            data.put("elementId", elementId);
        }
        if (before == null || dto.containsKey("effectiveFrom")) {
            LocalDate from = HrRows.date(dto.get("effectiveFrom"));
            if (from == null) {
                throw ApiException.badRequest("effectiveFrom is required");
            }
            data.put("effectiveFrom", from);
        }
        if (dto.containsKey("effectiveTo")) {
            data.put("effectiveTo", HrRows.date(dto.get("effectiveTo")));
        }
        LocalDate from = HrRows.date(nz(data.get("effectiveFrom"), before == null ? null : before.get("effectiveFrom")));
        LocalDate to = HrRows.date(dto.containsKey("effectiveTo") ? data.get("effectiveTo")
                : before == null ? null : before.get("effectiveTo"));
        if (to != null && to.isBefore(from)) {
            throw ApiException.badRequest("effectiveTo cannot be before effectiveFrom");
        }
        for (String f : List.of("amount", "rate")) {
            if (dto.containsKey(f)) {
                data.put(f, dec(dto.get(f)));
            }
        }
        if (dto.containsKey("formula")) {
            data.put("formula", str(dto.get("formula")));
        }
        copy(dto, data, "note");
        Map<String, Object> merged = new LinkedHashMap<>(before == null ? Map.of() : before);
        merged.putAll(data);
        validateValue(String.valueOf(el.get("calcMethod")), nz(merged.get("amount"), el.get("amount")),
                nz(merged.get("rate"), el.get("rate")), nz(merged.get("formula"), el.get("formula")));
        return save("HrEmployeePayElement", id, data);
    }

    public Map<String, Object> deleteEmployeeElement(String employeeId, String id) {
        Map<String, Object> row = rows.require("HrEmployeePayElement", id, "Employee pay element not found");
        if (!employeeId.equals(row.get("employeeId"))) {
            throw ApiException.notFound("Employee pay element not found");
        }
        rows.softDelete("HrEmployeePayElement", id);
        return Map.of("id", id, "deleted", true);
    }

    // ------------------------------------------------------------------ engine reads

    /** The ACTIVE compensation row in force on {@code on}, or {@code null}. */
    public Map<String, Object> compensationOn(String employeeId, LocalDate on) {
        return rows.first("HrEmployeeCompensation", "\"employeeId\" = ? AND status IN ('ACTIVE', 'SUPERSEDED') AND"
                + " \"effectiveFrom\" <= ? AND (\"effectiveTo\" IS NULL OR \"effectiveTo\" >= ?)",
                "\"effectiveFrom\" DESC", employeeId, on, on);
    }

    /**
     * Pay elements of an employee for a period: the structure lines of the compensation in force at the period end,
     * then employee elements overlapping the period (an employee element replaces a structure line of the same
     * element). Inactive catalogue elements are skipped.
     */
    public List<Element> elementsFor(String employeeId, LocalDate from, LocalDate to) {
        Map<String, Element> out = new LinkedHashMap<>();
        Map<String, Map<String, Object>> catalogue = new LinkedHashMap<>();
        for (Map<String, Object> e : rows.list("HrPayElement", "\"isActive\" = true", "\"sortOrder\" ASC, code ASC")) {
            catalogue.put((String) e.get("id"), e);
        }
        Map<String, Object> comp = compensationOn(employeeId, to);
        if (comp != null && comp.get("structureId") != null) {
            Map<String, Object> st = rows.find("HrSalaryStructure", comp.get("structureId"));
            if (st != null) {
                for (Object o : PosSql.list(st.get("lines"))) {
                    Map<String, Object> l = PosSql.map(o);
                    Map<String, Object> el = catalogue.get(String.valueOf(l.get("elementId")));
                    if (el != null) {
                        out.put((String) el.get("id"), element(el, l, "structure"));
                    }
                }
            }
        }
        for (Map<String, Object> a : rows.list("HrEmployeePayElement", "\"employeeId\" = ? AND \"effectiveFrom\" <= ?"
                + " AND (\"effectiveTo\" IS NULL OR \"effectiveTo\" >= ?)", "\"effectiveFrom\" ASC", employeeId, to,
                from)) {
            Map<String, Object> el = catalogue.get(String.valueOf(a.get("elementId")));
            if (el != null) {
                out.put((String) el.get("id"), element(el, a, "employee"));
            }
        }
        List<Element> list = new ArrayList<>(out.values());
        list.sort(java.util.Comparator.comparingInt(Element::sortOrder).thenComparing(Element::code));
        return list;
    }

    private static Element element(Map<String, Object> el, Map<String, Object> override, String source) {
        return new Element((String) el.get("id"), (String) el.get("code"), (String) el.get("name"),
                (String) el.get("kind"), (String) el.get("calcMethod"),
                dec(nz(override.get("amount"), el.get("amount"))), dec(nz(override.get("rate"), el.get("rate"))),
                str(nz(override.get("formula"), el.get("formula"))), Boolean.TRUE.equals(el.get("isTaxable")),
                Boolean.TRUE.equals(el.get("isPensionable")), Boolean.TRUE.equals(el.get("isProrated")),
                (String) el.get("glAccountId"), ((Number) el.get("sortOrder")).intValue(), source);
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> employee(String id) {
        Map<String, Object> e = rows.queryOne("SELECT id, \"employeeCode\", \"baseSalary\" FROM \"HrEmployee\" WHERE"
                + " id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL", id, org());
        if (e == null) {
            throw ApiException.notFound("Employee not found");
        }
        return e;
    }

    private Map<String, Object> save(String table, String id, Map<String, Object> data) {
        return hr.inTx(() -> {
            Map<String, Object> before = id == null ? null : rows.find(table, id);
            Map<String, Object> row = id == null ? rows.insert(table, data) : rows.update(table, id, data);
            hr.auditInTx(AuditInput.of(table, (String) row.get("id"), id == null ? "create" : "update", before, row));
            return row;
        });
    }

    private static void copy(Map<String, Object> dto, Map<String, Object> data, String... fields) {
        for (String f : fields) {
            if (dto.containsKey(f)) {
                data.put(f, dto.get(f) instanceof String s && s.isBlank() ? null : dto.get(f));
            }
        }
    }

    static Object nz(Object a, Object b) {
        return a != null ? a : b;
    }

    private static String required(Object v, String field) {
        String s = str(v);
        if (s == null) {
            throw ApiException.badRequest(field + " is required");
        }
        return s;
    }

    private static String upperCode(Object v) {
        return required(v, "code").toUpperCase().replaceAll("\\s+", "_");
    }
}
