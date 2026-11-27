package com.inventoryerp;

import com.inventoryerp.kernel.settings.SettingResolverService;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Test fixture: runs {@code body} with {@code procurement.billWithoutReceipt = auto_receive} for the organization
 * (purchase readiness H1, 2026-11-27: the default is {@code block}; tests of bill auto-receive opt in).
 */
public final class TestBillSettings {

    private TestBillSettings() {}

    public static void autoReceive(JdbcClient jdbc, String org, SettingResolverService resolver, Runnable body) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Setting\" (id, \"organizationId\", key, value, \"scopeType\", \"updatedAt\") VALUES"
                + " (?, ?, 'procurement.billWithoutReceipt', '\"auto_receive\"'::jsonb, 'organization', now())")
                .params(id, org).update();
        resolver.invalidate(org);
        try {
            body.run();
        } finally {
            jdbc.sql("DELETE FROM \"Setting\" WHERE id = ?").param(id).update();
            resolver.invalidate(org);
        }
    }
}
