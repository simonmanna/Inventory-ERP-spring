package com.inventoryerp.sync.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * ADR-S083 S9 metrics, server-wide (open dead letters are {@code erp.sync.dead.letters.open} in BacklogMetrics):
 * <ul>
 *   <li>{@code erp.sync.push.ops{status}}: pushed ops by result ({@code applied}, {@code replayed}, {@code failed}, …);</li>
 *   <li>{@code erp.sync.lag.seconds.max}: the longest time since a device synced, over devices active in the last 7
 *       days (an abandoned device does not hold the alert up);</li>
 *   <li>{@code erp.sync.devices.stale}: devices not revoked and not heard from for 24 hours.</li>
 * </ul>
 * Lag is one server-wide gauge rather than one series per device ({@code sync_lag_seconds{device}} in the ADR), to
 * keep Prometheus cardinality bounded; per-device lag is on the dashboard.
 */
@Component
public class SyncMetrics implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(SyncMetrics.class);

    private final JdbcClient jdbc;
    private final Map<String, Counter> pushed = new ConcurrentHashMap<>();
    private volatile MeterRegistry registry;

    public SyncMetrics(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        this.registry = registry;
        gauge(registry, "erp.sync.lag.seconds.max", "Longest time since a device active in the last 7 days synced",
                () -> value("SELECT coalesce(max(extract(epoch FROM now() - greatest(\"lastSeenAt\", \"lastPullAt\"))), 0)"
                        + " FROM \"PosDevice\" WHERE \"revokedAt\" IS NULL"
                        + " AND greatest(\"lastSeenAt\", \"lastPullAt\") > now() - interval '7 days'"));
        gauge(registry, "erp.sync.devices.stale", "Sync devices not revoked and not heard from for 24 hours",
                () -> value("SELECT count(*) FROM \"PosDevice\" WHERE \"revokedAt\" IS NULL"
                        + " AND coalesce(greatest(\"lastSeenAt\", \"lastPullAt\"), \"createdAt\") < now() - interval '24 hours'"));
    }

    /** One pushed op's result. */
    public void pushed(Object status) {
        MeterRegistry r = registry;
        if (r == null) {
            return;
        }
        String s = status == null ? "unknown" : status.toString();
        pushed.computeIfAbsent(s, k -> Counter.builder("erp.sync.push.ops").description("Pushed sync ops by result")
                .tag("status", k).register(r)).increment();
    }

    private static void gauge(MeterRegistry registry, String name, String help, Supplier<Double> value) {
        Gauge.builder(name, value).description(help).strongReference(true).register(registry);
    }

    private double value(String sql) {
        try {
            Number n = jdbc.sql(sql).query(Number.class).single();
            return n == null ? 0 : n.doubleValue();
        } catch (RuntimeException e) {
            log.debug("sync gauge query failed: {}", e.toString());
            return -1;
        }
    }
}
