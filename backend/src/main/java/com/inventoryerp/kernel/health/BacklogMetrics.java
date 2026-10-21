package com.inventoryerp.kernel.health;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Server-wide backlog gauges for alerting (infra/monitoring/alerts.yml): POS stock-posting jobs not yet done, open
 * offline-sync dead letters, and undelivered domain events in the Modulith outbox. Each is one indexed count, evaluated
 * when Prometheus scrapes; a failing query reports {@code -1} instead of breaking the scrape.
 */
@Component
public class BacklogMetrics implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(BacklogMetrics.class);

    private final JdbcClient jdbc;

    public BacklogMetrics(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        gauge(registry, "erp.stock.posting.jobs.pending", "POS stock-posting jobs not yet done",
                () -> count("SELECT count(*) FROM \"StockPostingJob\" WHERE status <> 'done'"));
        gauge(registry, "erp.sync.dead.letters.open", "Offline sync ops refused and awaiting a manager decision",
                () -> count("SELECT count(*) FROM \"SyncOpDeadLetter\" WHERE status = 'open'"));
        gauge(registry, "erp.event.publications.incomplete", "Domain events in the outbox not yet delivered",
                () -> count("SELECT count(*) FROM event_publication WHERE completion_date IS NULL"));
    }

    private static void gauge(MeterRegistry registry, String name, String help, Supplier<Double> value) {
        Gauge.builder(name, value).description(help).strongReference(true).register(registry);
    }

    private double count(String sql) {
        try {
            return jdbc.sql(sql).query(Long.class).single();
        } catch (RuntimeException e) {
            log.debug("backlog gauge query failed: {}", e.toString());
            return -1;
        }
    }
}
