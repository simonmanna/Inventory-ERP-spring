package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.NOW;
import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.shared.web.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Why quotations are lost (SG-3): a short catalog, filled with defaults the first time it is read. */
@Service
public class SalesLostReasonService {

    private static final List<String> DEFAULTS = List.of("Price too high", "Lost to competitor", "Delivery too slow",
            "No longer needed", "Credit not approved", "No response");

    private final SalesDb db;

    public SalesLostReasonService(SalesDb db) {
        this.db = db;
    }

    @Transactional
    public List<Map<String, Object>> list() {
        if (db.count("SELECT count(*) FROM \"SalesLostReason\" WHERE \"organizationId\" = ?", List.of(org())) == 0) {
            int i = 0;
            for (String name : DEFAULTS) {
                db.insert("SalesLostReason", Obj.of("name", name, "sortOrder", i++));
            }
        }
        return db.rows("SELECT id, name, \"isActive\", \"sortOrder\" FROM \"SalesLostReason\" WHERE \"organizationId\" = ?"
                + " ORDER BY \"sortOrder\", name", org());
    }

    @Transactional
    public Map<String, Object> save(String id, Map<String, Object> in) {
        String name = str(in.get("name"));
        if (blank(name)) {
            throw ApiException.badRequest("name should not be empty");
        }
        if (db.count("SELECT count(*) FROM \"SalesLostReason\" WHERE \"organizationId\" = ? AND lower(name) = lower(?) AND"
                + " id <> ?", List.of(org(), name, id == null ? "" : id)) > 0) {
            throw ApiException.conflict("This reason already exists");
        }
        boolean active = !Boolean.FALSE.equals(in.get("isActive"));
        if (id == null) {
            return db.insert("SalesLostReason", Obj.of("name", name, "isActive", active));
        }
        if (db.update("UPDATE \"SalesLostReason\" SET name = ?, \"isActive\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?"
                + " AND \"organizationId\" = ?", name, active, id, org()) == 0) {
            throw ApiException.notFound("Lost reason not found");
        }
        return db.row("SELECT id, name, \"isActive\", \"sortOrder\" FROM \"SalesLostReason\" WHERE id = ?", id);
    }

    /** 400 unless the reason exists in this organization and is active. */
    void assertActive(String id) {
        if (db.count("SELECT count(*) FROM \"SalesLostReason\" WHERE id = ? AND \"organizationId\" = ? AND \"isActive\"",
                List.of(id, org())) == 0) {
            throw ApiException.badRequest("Lost reason not found");
        }
    }
}
