package com.inventoryerp.inventory.application;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The location POS sells stock from (port of {@code pos-stock-location.ts}): an explicit register location, else
 * the {@code pos.stockLocationId} setting, else the single active warehouse (never a guess between several).
 */
@Component
public class PosStockLocation {

    private final JdbcClient jdbc;

    public PosStockLocation(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Location id or null. */
    public String resolve(String organizationId, String preferredLocationId) {
        String configured = jdbc.sql("SELECT value::text FROM \"Setting\" WHERE \"organizationId\" = ?"
                        + " AND key = 'pos.stockLocationId' AND \"scopeType\" = 'organization' LIMIT 1")
                .param(organizationId).query(String.class).optional()
                .map(v -> {
                    JsonNode n = com.inventoryerp.shared.web.EntityJson.tree(v);
                    return n == null || n.isNull() ? "" : n.isString() ? n.asString() : n.toString();
                }).orElse("");
        if (preferredLocationId != null) {
            return jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND id = ?"
                            + " AND \"isActive\" = true AND \"deletedAt\" IS NULL LIMIT 1")
                    .params(organizationId, preferredLocationId).query(String.class).optional().orElse(null);
        }
        if (!configured.isEmpty()) {
            String loc = jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND id = ?"
                            + " AND \"isActive\" = true AND \"deletedAt\" IS NULL LIMIT 1")
                    .params(organizationId, configured).query(String.class).optional().orElse(null);
            if (loc != null) {
                return loc;
            }
        }
        List<String> candidates = jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ?"
                        + " AND type = 'warehouse' AND \"isActive\" = true AND \"deletedAt\" IS NULL ORDER BY code ASC LIMIT 2")
                .param(organizationId).query(String.class).list();
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }
}
