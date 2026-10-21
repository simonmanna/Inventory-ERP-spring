package com.inventoryerp.pos.application;

import static com.inventoryerp.pos.application.PosSql.NOW;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Item-derived table status and the floor-close lock (port of {@code table-status.util.ts}). */
public final class TableStatus {

    /** Order statuses that hold a dine-in table (legacy tail kept for the Android compat window). */
    public static final List<String> HELD = List.of("draft", "confirmed", "in_progress", "completed", "open", "preparing",
            "ready", "served");

    /** SQL list literal of {@link #HELD} for {@code status::text IN (...)}. */
    public static final String HELD_SQL = "('draft', 'confirmed', 'in_progress', 'completed', 'open', 'preparing',"
            + " 'ready', 'served')";

    /**
     * {@code heldOrderWhere}: alias {@code o} = "Order" — held status, not billed, with at least one live item.
     */
    public static final String HELD_ORDER_WHERE = "o.status::text IN " + HELD_SQL + " AND o.\"invoiceId\" IS NULL"
            + " AND EXISTS (SELECT 1 FROM \"OrderItem\" oi WHERE oi.\"orderId\" = o.id AND oi.cancelled = false)";

    private TableStatus() {}

    public static boolean isHeld(Object status) {
        return status != null && HELD.contains(status.toString());
    }

    public static void lockFloorShared(JdbcClient jdbc, String organizationId) {
        jdbc.sql("SELECT pg_advisory_xact_lock_shared(hashtext(?))::text").param("pos-floor-close:" + organizationId)
                .query().listOfRows();
    }

    public static void lockFloorExclusive(JdbcClient jdbc, String organizationId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?))::text").param("pos-floor-close:" + organizationId)
                .query().listOfRows();
    }

    /**
     * OCCUPIED iff ≥1 active item on a held order of the table, else AVAILABLE; {@code reserved} / {@code out_of_service}
     * are overrides. Returns the resulting status or {@code null} without a table.
     */
    public static String recompute(JdbcClient jdbc, String organizationId, String tableId) {
        if (tableId == null || tableId.isEmpty()) {
            return null;
        }
        String status = jdbc.sql("SELECT status::text FROM \"PosTable\" WHERE id = ? AND \"organizationId\" = ?")
                .params(tableId, organizationId).query(String.class).optional().orElse(null);
        if (status == null) {
            return null;
        }
        if ("out_of_service".equals(status) || "reserved".equals(status)) {
            return status;
        }
        long active = jdbc.sql("SELECT count(*) FROM \"OrderItem\" oi JOIN \"Order\" o ON o.id = oi.\"orderId\""
                        + " WHERE oi.\"organizationId\" = ? AND oi.cancelled = false AND o.\"tableId\" = ?"
                        + " AND o.status::text IN " + HELD_SQL)
                .params(organizationId, tableId).query(Long.class).single();
        String next = active > 0 ? "occupied" : "available";
        if (!next.equals(status)) {
            jdbc.sql("UPDATE \"PosTable\" SET status = ?::\"PosTableStatus\", \"updatedAt\" = " + NOW
                    + " WHERE id = ? AND \"organizationId\" = ?").params(next, tableId, organizationId).update();
        }
        return next;
    }
}
