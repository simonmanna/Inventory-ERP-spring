package com.inventoryerp.kernel.health;

import com.inventoryerp.kernel.security.Public;
import com.inventoryerp.shared.web.ApiException;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Port of apps/api/src/observability/metrics.controller.ts. {@code @Public} (a scraper holds no
 * session) but gated on the shared bearer token {@code METRICS_TOKEN}; when unset it is open outside
 * the {@code prod} profile and refuses in it. The hand-written {@code erp_*} gauges come first, then
 * the Micrometer Prometheus registry (JVM, HTTP, Hikari) — the "real registry" the Nest comment asks for.
 */
@RestController
@RequestMapping("/metrics")
public class MetricsController {

    private static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private final long startTime = System.currentTimeMillis();
    private final String expectedToken;
    private final boolean production;
    private final ObjectProvider<PrometheusMeterRegistry> registry;

    public MetricsController(@Value("${METRICS_TOKEN:}") String expectedToken, Environment env,
            ObjectProvider<PrometheusMeterRegistry> registry) {
        this.expectedToken = expectedToken;
        this.production = env.acceptsProfiles(Profiles.of("prod"));
        this.registry = registry;
    }

    @Public
    @GetMapping(produces = CONTENT_TYPE)
    public String metrics(@RequestHeader(name = "authorization", required = false) String auth) {
        if (!expectedToken.isEmpty()) {
            if (!("Bearer " + expectedToken).equals(auth)) {
                throw ApiException.forbidden("Invalid metrics token");
            }
        } else if (production) {
            throw ApiException.forbidden("METRICS_TOKEN is not configured");
        }
        return render();
    }

    private String render() {
        double uptimeSec = (System.currentTimeMillis() - startTime) / 1000.0;
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        Runtime rt = Runtime.getRuntime();
        StringBuilder out = new StringBuilder();

        out.append("# HELP erp_process_uptime_seconds Process uptime in seconds\n");
        out.append("# TYPE erp_process_uptime_seconds gauge\n");
        out.append("erp_process_uptime_seconds ").append(String.format(Locale.ROOT, "%.3f", uptimeSec)).append('\n');

        out.append("# HELP erp_process_memory_bytes Process memory usage\n");
        out.append("# TYPE erp_process_memory_bytes gauge\n");
        out.append("erp_process_memory_bytes{type=\"rss\"} ")
                .append(rt.totalMemory() + mem.getNonHeapMemoryUsage().getCommitted()).append('\n');
        out.append("erp_process_memory_bytes{type=\"heap_total\"} ").append(rt.totalMemory()).append('\n');
        out.append("erp_process_memory_bytes{type=\"heap_used\"} ").append(rt.totalMemory() - rt.freeMemory())
                .append('\n');
        out.append("erp_process_memory_bytes{type=\"external\"} ")
                .append(mem.getNonHeapMemoryUsage().getUsed()).append('\n');

        out.append("# HELP erp_jvm_info Java runtime version\n");
        out.append("# TYPE erp_jvm_info gauge\n");
        out.append("erp_jvm_info{version=\"").append(Runtime.version()).append("\"} 1\n");

        PrometheusMeterRegistry prom = registry.getIfAvailable();
        if (prom != null) {
            out.append(prom.scrape());
        }
        return out.toString();
    }
}
