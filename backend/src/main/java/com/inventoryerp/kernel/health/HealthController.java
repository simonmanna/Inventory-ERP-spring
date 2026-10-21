package com.inventoryerp.kernel.health;

import com.inventoryerp.kernel.web.JsonConfig;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Port of apps/api/src/health/health.controller.ts. {@code GET /health} is the offline probe used
 * by the web POS (no auth, no DB). Modules contribute {@link AdvisoryHealthCheck}s (backup health).
 */
@RestController
@com.inventoryerp.kernel.security.Public
@RequestMapping("/health")
public class HealthController {

    private final JdbcClient jdbc;
    private final List<AdvisoryHealthCheck> advisoryChecks;

    public HealthController(JdbcClient jdbc, List<AdvisoryHealthCheck> advisoryChecks) {
        this.jdbc = jdbc;
        this.advisoryChecks = advisoryChecks;
    }

    @GetMapping({"", "/live"})
    public Map<String, Object> liveness() {
        return Map.of("status", "ok", "timestamp", JsonConfig.toJsIso(Instant.now()));
    }

    @GetMapping({"/ready", "/startup"})
    public ResponseEntity<Map<String, Object>> readiness() {
        long started = System.currentTimeMillis();
        Map<String, Object> database = probeDb();
        boolean criticalOk = Boolean.TRUE.equals(database.get("ok"));
        Map<String, Object> critical = Map.of("database", database);
        Map<String, Object> advisory = new LinkedHashMap<>();
        for (AdvisoryHealthCheck c : advisoryChecks) {
            Map<String, Object> check = new LinkedHashMap<>();
            try {
                String problems = c.problems();
                check.put("ok", problems == null);
                check.put("latencyMs", 0);
                if (problems != null && !problems.isEmpty()) {
                    check.put("error", problems);
                }
            } catch (RuntimeException e) {
                check.put("ok", false);
                check.put("error", e.toString());
            }
            advisory.put(c.name(), check);
        }
        boolean advisoryOk = advisory.values().stream().allMatch(v -> Boolean.TRUE.equals(((Map<?, ?>) v).get("ok")));
        Map<String, Object> checks = new LinkedHashMap<>(critical);
        checks.putAll(advisory);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", criticalOk ? (advisoryOk ? "ok" : "degraded") : "unavailable");
        body.put("criticalOk", criticalOk);
        body.put("timestamp", JsonConfig.toJsIso(Instant.now()));
        body.put("durationMs", System.currentTimeMillis() - started);
        body.put("checks", checks);
        body.put("critical", critical);
        body.put("advisory", advisory);
        return ResponseEntity.status(criticalOk ? 200 : 503).body(body);
    }

    private Map<String, Object> probeDb() {
        long started = System.currentTimeMillis();
        Map<String, Object> check = new LinkedHashMap<>();
        try {
            jdbc.sql("SELECT 1").query(Integer.class).single();
            check.put("ok", true);
        } catch (RuntimeException e) {
            check.put("ok", false);
            String msg = String.valueOf(e.getMessage());
            check.put("error", msg.substring(0, Math.min(200, msg.length())));
        }
        check.put("latencyMs", System.currentTimeMillis() - started);
        return check;
    }
}
