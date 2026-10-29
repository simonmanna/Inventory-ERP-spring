package com.inventoryerp.fleet.application;

import static com.inventoryerp.fleet.application.MaintainableUnits.like;
import static com.inventoryerp.fleet.application.MaintainableUnits.notBlank;

import com.inventoryerp.kernel.crud.SqlRows;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Pickers of the maintenance screens (1.6): spare parts with on-hand at a location, hr employees. Read-only. */
@Service
public class FleetMaintenanceLookupService {

    private final FleetDb db;

    public FleetMaintenanceLookupService(FleetDb db) {
        this.db = db;
    }

    /** Stock-tracked products matching {@code search} (code, SKU, barcode, name), with on-hand at the location. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> parts(String search, String locationId) {
        List<Object> params = new ArrayList<>();
        params.add(locationId);
        params.add(FleetDb.org());
        StringBuilder where = new StringBuilder(" WHERE p.\"organizationId\" = ? AND p.\"deletedAt\" IS NULL"
                + " AND p.\"isActive\" = true AND p.\"trackInventory\" = true");
        if (notBlank(search)) {
            where.append(" AND (p.code ILIKE ? OR p.sku ILIKE ? OR p.barcode ILIKE ? OR p.name ILIKE ?)");
            String l = like(search);
            for (int i = 0; i < 4; i++) {
                params.add(l);
            }
        }
        return SqlRows.list(db.jdbc().sql("SELECT p.id, p.code, p.sku, p.name, p.\"costPrice\","
                + " COALESCE(s.quantity, 0) AS \"onHand\", s.\"runningAverageCost\" FROM \"Product\" p"
                + " LEFT JOIN \"StockItem\" s ON s.\"productId\" = p.id AND s.\"organizationId\" = p.\"organizationId\""
                + " AND s.\"variantKey\" = '' AND s.\"locationId\" = ?" + where + " ORDER BY p.name LIMIT 25")
                .params(params));
    }

    /** Active hr employees matching {@code search} (code, name), with their hourly rate. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> employees(String search) {
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        StringBuilder where = new StringBuilder(" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                + " AND \"isActive\" = true");
        if (notBlank(search)) {
            where.append(" AND (\"employeeCode\" ILIKE ? OR \"firstName\" ILIKE ? OR \"lastName\" ILIKE ?"
                    + " OR (\"firstName\" || ' ' || COALESCE(\"lastName\", '')) ILIKE ?)");
            String l = like(search);
            for (int i = 0; i < 4; i++) {
                params.add(l);
            }
        }
        return SqlRows.list(db.jdbc().sql("SELECT id, \"employeeCode\", \"firstName\", \"lastName\", \"hourlyRate\""
                + " FROM \"HrEmployee\"" + where + " ORDER BY \"firstName\", \"lastName\" LIMIT 25").params(params));
    }
}
