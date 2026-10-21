package com.inventoryerp.backup.application;

import com.inventoryerp.kernel.health.AdvisoryHealthCheck;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Backup health as the {@code advisory.backup} readiness check: healthy, or the issue list joined with "; ". */
@Component
public class BackupHealthCheck implements AdvisoryHealthCheck {

    private final BackupService backups;

    public BackupHealthCheck(BackupService backups) {
        this.backups = backups;
    }

    @Override
    public String name() {
        return "backup";
    }

    @Override
    public String problems() {
        Map<String, Object> health = backups.getHealthStatus();
        if ("healthy".equals(health.get("status"))) {
            return null;
        }
        List<?> issues = health.get("issues") instanceof List<?> l ? l : List.of();
        return String.join("; ", issues.stream().map(String::valueOf).toList());
    }
}
