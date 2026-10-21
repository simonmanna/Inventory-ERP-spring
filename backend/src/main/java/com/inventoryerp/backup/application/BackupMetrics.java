package com.inventoryerp.backup.application;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Backup gauges for alerting: {@code erp_backup_health} (0 healthy, 1 degraded, 2 critical) and
 * {@code erp_backup_last_full_age_seconds} (−1 when no successful full backup is on record).
 */
@Component
public class BackupMetrics implements MeterBinder {

    private final BackupService backups;

    public BackupMetrics(BackupService backups) {
        this.backups = backups;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("erp.backup.health", this::health).description("Backup health: 0 healthy, 1 degraded, 2 critical")
                .strongReference(true).register(registry);
        Gauge.builder("erp.backup.last.full.age.seconds", this::lastFullAge)
                .description("Seconds since the last successful full backup (-1 = none)").strongReference(true)
                .register(registry);
    }

    private double health() {
        Object status = backups.getHealthStatus().get("status");
        return "healthy".equals(status) ? 0 : "degraded".equals(status) ? 1 : 2;
    }

    private double lastFullAge() {
        Instant at = backups.lastSuccessfulFullAt();
        return at == null ? -1 : (System.currentTimeMillis() - at.toEpochMilli()) / 1000.0;
    }
}
