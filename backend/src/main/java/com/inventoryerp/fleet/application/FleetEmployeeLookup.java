package com.inventoryerp.fleet.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only access to hr employees (drivers / operators) for incidents and fines: validation of an employee id,
 * display names and the picker search. Fleet never writes hr tables (ADR-S009 §2).
 */
@Component
public class FleetEmployeeLookup {

    private final FleetDb db;

    public FleetEmployeeLookup(FleetDb db) {
        this.db = db;
    }

    /** 400 with {@code message} unless the id is a live employee of the caller's organization. */
    void assertEmployee(String employeeId, String message) {
        if (employeeId != null && !db.exists("HrEmployee", employeeId, true)) {
            throw ApiException.badRequest(message);
        }
    }

    /** {@code {id, employeeCode, name}} or null. */
    Map<String, Object> ref(String employeeId) {
        if (employeeId == null) {
            return null;
        }
        Map<String, Object> e = SqlRows.one(db.jdbc().sql("SELECT id, \"employeeCode\", \"firstName\", \"lastName\""
                + " FROM \"HrEmployee\" WHERE id = ? AND \"organizationId\" = ?").params(employeeId, FleetDb.org()));
        return e == null ? null : shape(e);
    }

    /** Employees matching {@code search} (code / name), at most 20, active first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> search(String search) {
        String like = "%" + (search == null ? "" : search.trim().replace("%", "\\%").replace("_", "\\_")) + "%";
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> e : SqlRows.list(db.jdbc().sql("""
                SELECT id, "employeeCode", "firstName", "lastName", "employmentStatus"::text AS status
                FROM "HrEmployee" WHERE "organizationId" = ? AND "deletedAt" IS NULL
                  AND ("employeeCode" ILIKE ? OR "firstName" ILIKE ? OR "lastName" ILIKE ?
                       OR concat_ws(' ', "firstName", "lastName") ILIKE ?)
                ORDER BY (lower("employmentStatus"::text) = 'active') DESC, "firstName", "lastName" LIMIT 20""")
                .params(FleetDb.org(), like, like, like, like))) {
            Map<String, Object> m = shape(e);
            m.put("status", e.get("status"));
            out.add(m);
        }
        return out;
    }

    static Map<String, Object> shape(Map<String, Object> e) {
        String name = ((e.get("firstName") == null ? "" : e.get("firstName")) + " "
                + (e.get("lastName") == null ? "" : e.get("lastName"))).trim();
        return Obj.of("id", e.get("id"), "employeeCode", e.get("employeeCode"), "name", name);
    }
}
