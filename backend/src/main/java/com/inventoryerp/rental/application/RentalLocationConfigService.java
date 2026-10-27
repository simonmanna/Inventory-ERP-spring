package com.inventoryerp.rental.application;

import com.inventoryerp.inventory.domain.InventoryLocation;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.rental.domain.RentalLocationConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The five rental inventory locations (port of {@code rental-location-config.service.ts}). Checkout is an internal
 * stock transfer between them (RENT-STOCK → RENT-OUT and back on return); the role → InventoryLocation mapping is
 * bootstrapped per org on first use (idempotent) with {@code virtual} locations.
 */
@Service
public class RentalLocationConfigService {

    private static final Logger log = LoggerFactory.getLogger(RentalLocationConfigService.class);

    private record Boot(String role, String code, String name) {}

    private static final List<Boot> BOOTSTRAP = List.of(
            new Boot("stock", "RENT-STOCK", "Rental Stock"),
            new Boot("out", "RENT-OUT", "Rental Out (with customer)"),
            new Boot("cleaning", "RENT-CLEAN", "Rental Cleaning"),
            new Boot("repair", "RENT-REPAIR", "Rental Repair"),
            new Boot("damaged", "RENT-DAMAGED", "Rental Damaged"));

    private final PosDb db;

    public RentalLocationConfigService(PosDb db) {
        this.db = db;
    }

    /** Ensure the five locations + config rows exist for an org. Idempotent. */
    @Transactional
    public void ensureForOrg(String orgId) {
        for (Boot row : BOOTSTRAP) {
            // findUnique on (organizationId, code) is soft-delete filtered by the tenancy extension.
            Map<String, Object> existing = db.first(InventoryLocation.class,
                    "\"organizationId\" = ? AND code = ? AND \"deletedAt\" IS NULL", orgId, row.code());
            String locationId = existing != null ? (String) existing.get("id")
                    : (String) db.insert(InventoryLocation.class, Obj.of("organizationId", orgId, "code", row.code(),
                            "name", row.name(), "type", "virtual", "isActive", true)).get("id");
            db.jdbc().sql("""
                            INSERT INTO "RentalLocationConfig" (id, "organizationId", role, "locationId", "createdAt",
                              "updatedAt")
                            VALUES (gen_random_uuid()::text, ?, ?::"RentalLocationRole", ?, now(), now())
                            ON CONFLICT ("organizationId", role)
                            DO UPDATE SET "locationId" = EXCLUDED."locationId", "updatedAt" = now()""")
                    .params(orgId, row.role(), locationId).update();
        }
        log.info("Rental locations ensured for org {}", orgId);
    }

    /** Tx-aware role → InventoryLocation id, ensuring bootstrap first. */
    @Transactional
    public String resolve(String orgId, String role) {
        ensureForOrg(orgId);
        Map<String, Object> cfg = db.first(RentalLocationConfig.class, "\"organizationId\" = ? AND role = ?::\"RentalLocationRole\"",
                orgId, role);
        if (cfg == null) {
            throw new IllegalStateException("Rental location role '" + role + "' not configured for org " + orgId);
        }
        return (String) cfg.get("locationId");
    }

    /** {@code POST /rental/locations/bootstrap}: all five role → location mappings for the current org. */
    @Transactional
    public Map<String, Object> bootstrap() {
        String orgId = RentalRows.org();
        ensureForOrg(orgId);
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map<String, Object> r : db.list(RentalLocationConfig.class, "\"organizationId\" = ?", orgId)) {
            map.put((String) r.get("role"), r.get("locationId"));
        }
        return map;
    }
}
