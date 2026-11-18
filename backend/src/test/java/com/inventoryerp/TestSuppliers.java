package com.inventoryerp;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Test fixture: a supplier for goods receipts without a purchase order, which must name one (readiness gap fix h03,
 * residual (a)).
 */
public final class TestSuppliers {

    private TestSuppliers() {}

    /** A new supplier of {@code organizationId}; returns its id. */
    public static String supplier(JdbcClient jdbc, String organizationId) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, true, now())")
                .params(id, organizationId, "SUP-" + id.substring(0, 8), "Test supplier " + id.substring(0, 8)).update();
        return id;
    }
}
