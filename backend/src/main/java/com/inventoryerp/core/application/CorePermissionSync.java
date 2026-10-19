package com.inventoryerp.core.application;

import com.inventoryerp.kernel.security.Permissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Boot-time permission sync (port of {@code CoreModule.onModuleInit}): upserts every permission key into the
 * global catalog and appends missing keys to every "Administrator" role, so a newly added permission never
 * surfaces as a 403 for admins. Idempotent; failures are only logged.
 */
@Component
public class CorePermissionSync {

    private static final Logger log = LoggerFactory.getLogger(CorePermissionSync.class);

    private final JdbcClient jdbc;

    public CorePermissionSync(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        Thread.ofVirtual().name("core-permission-sync").start(() -> {
            try {
                sync();
            } catch (RuntimeException e) {
                log.warn("[CoreModule] permission sync failed: {}", e.getMessage());
            }
        });
    }

    void sync() {
        for (String key : Permissions.ALL) {
            String[] parts = key.split("[:.]");
            jdbc.sql("INSERT INTO \"Permission\" (\"id\", \"key\", \"resource\", \"action\", \"createdAt\")"
                            + " VALUES (gen_random_uuid()::text, ?, ?, ?, NOW()) ON CONFLICT (\"key\") DO NOTHING")
                    .params(key, parts[0], parts.length > 1 ? parts[1] : null)
                    .update();
        }
        record Admin(String id, String[] permissions) {}
        List<Admin> admins = jdbc.sql("SELECT id, permissions FROM \"Role\" WHERE name = 'Administrator'")
                .query((rs, n) -> new Admin(rs.getString("id"), (String[]) rs.getArray("permissions").getArray()))
                .list();
        for (Admin admin : admins) {
            Set<String> have = new LinkedHashSet<>(Arrays.asList(admin.permissions()));
            List<String> missing = new ArrayList<>();
            for (String k : Permissions.ALL) {
                if (!have.contains(k)) {
                    missing.add(k);
                }
            }
            if (missing.isEmpty()) {
                continue;
            }
            have.addAll(missing);
            jdbc.sql("UPDATE \"Role\" SET permissions = ?, \"updatedAt\" = now() WHERE id = ?")
                    .params(have.toArray(String[]::new), admin.id())
                    .update();
        }
    }
}
