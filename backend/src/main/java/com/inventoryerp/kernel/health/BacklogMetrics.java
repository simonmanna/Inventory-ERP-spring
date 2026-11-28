package com.inventoryerp.kernel.health;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Server-wide backlog gauges for alerting (infra/monitoring/alerts.yml): POS stock-posting jobs still to run and those
 * that failed, open offline-sync dead letters, undelivered domain events in the Modulith outbox and the EventOutbox
 * backlog / dead rows. Each is one indexed count, evaluated when Prometheus scrapes; a failing query reports {@code -1}
 * instead of breaking the scrape. Open inventory integrity findings ({@code erp.inventory.integrity.open}, one series
 * per {@code integrity_*} kind, totals across organisations) are counted by {@link #refreshIntegrityFindings()} once a
 * minute instead, so a scrape never runs the grouped query; a kind seen once keeps reporting (0 when cleared).
 */
@Component
public class BacklogMetrics implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(BacklogMetrics.class);

    /** Open {@code InventoryException} findings of the integrity monitor, per kind. */
    static final String INTEGRITY_OPEN = "erp.inventory.integrity.open";

    private final JdbcClient jdbc;
    private final List<MultiGauge> integrityGauges = new CopyOnWriteArrayList<>();
    private final Map<String, Long> integrityOpen = new TreeMap<>();

    public BacklogMetrics(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        gauge(registry, "erp.stock.posting.jobs.pending", "POS stock-posting jobs still to run (pending, processing)",
                () -> count("SELECT count(*) FROM \"StockPostingJob\" WHERE status NOT IN ('done', 'failed',"
                        + " 'abandoned')"));
        gauge(registry, "erp.stock.posting.jobs.failed", "POS stock-posting jobs that failed every attempt",
                () -> count("SELECT count(*) FROM \"StockPostingJob\" WHERE status = 'failed'"));
        gauge(registry, "erp.sync.dead.letters.open", "Offline sync ops refused and awaiting a manager decision",
                () -> count("SELECT count(*) FROM \"SyncOpDeadLetter\" WHERE status = 'open'"));
        gauge(registry, "erp.event.publications.incomplete", "Domain events in the outbox not yet delivered",
                () -> count("SELECT count(*) FROM event_publication WHERE completion_date IS NULL"));
        gauge(registry, "erp.outbox.pending", "EventOutbox rows waiting to be shipped (incl. retries)",
                () -> count("SELECT count(*) FROM \"EventOutbox\" WHERE status IN ('pending', 'claimed')"));
        gauge(registry, "erp.outbox.dead", "EventOutbox rows that failed every attempt and need an operator",
                () -> count("SELECT count(*) FROM \"EventOutbox\" WHERE status = 'dead'"));
        MultiGauge integrity = MultiGauge.builder(INTEGRITY_OPEN)
                .description("Open inventory integrity findings (integrity_* InventoryException rows)")
                .register(registry);
        integrityGauges.add(integrity);
        publishIntegrity(integrity);
    }

    /** Recount the open integrity findings per kind (one grouped query on the open-status rows) and publish them. */
    @Scheduled(initialDelayString = "${erp.metrics.integrity-refresh-ms:60000}",
            fixedDelayString = "${erp.metrics.integrity-refresh-ms:60000}")
    public void refreshIntegrityFindings() {
        Map<String, Long> fresh;
        try {
            fresh = new TreeMap<>();
            jdbc.sql("SELECT kind, count(*) AS n FROM \"InventoryException\" WHERE status = 'open' AND kind LIKE"
                            + " 'integrity\\_%' GROUP BY kind")
                    .query((rs, i) -> Map.entry(rs.getString("kind"), rs.getLong("n")))
                    .list()
                    .forEach(e -> fresh.put(e.getKey(), e.getValue()));
        } catch (RuntimeException e) {
            log.debug("integrity gauge query failed: {}", e.toString());
            return;
        }
        synchronized (integrityOpen) {
            integrityOpen.replaceAll((kind, n) -> 0L);
            integrityOpen.putAll(fresh);
        }
        integrityGauges.forEach(this::publishIntegrity);
    }

    /** Open findings of {@code kind} as last counted. */
    double integrityOpen(String kind) {
        synchronized (integrityOpen) {
            return integrityOpen.getOrDefault(kind, 0L);
        }
    }

    private void publishIntegrity(MultiGauge gauge) {
        List<MultiGauge.Row<?>> rows = new ArrayList<>();
        synchronized (integrityOpen) {
            // The row reads this (singleton) bean, not a boxed count: a gauge holds its object weakly.
            integrityOpen.keySet().forEach(kind -> rows.add(MultiGauge.Row.of(Tags.of("kind", kind), this,
                    m -> m.integrityOpen(kind))));
        }
        gauge.register(rows, true);
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
