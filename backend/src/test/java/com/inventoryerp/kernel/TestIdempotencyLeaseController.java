package com.inventoryerp.kernel;

import com.inventoryerp.inventory.application.VirtualLocationService;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only money route for the ADR-S094 C1a lease tests ({@code .../payments} matches OUTCOME_ROUTES). Each call
 * writes a {@code CostCenter} marker coded {@code <tag>-<run>} in one transaction. A mode other than {@code ok} makes the
 * first run of a tag fail inside that transaction (500), after:
 * <ul>
 *   <li>{@code quarantine}: creating a location through {@link VirtualLocationService#ensure} (its retry-safe nested
 *       transaction commits);
 *   <li>{@code nested}: committing a marker {@code <tag>-inner} in a plain nested REQUIRES_NEW transaction;
 *   <li>{@code rollback}: nothing else.
 * </ul>
 */
@RestController
public class TestIdempotencyLeaseController {

    public static final Map<String, AtomicInteger> RUNS = new ConcurrentHashMap<>();

    private final JdbcClient jdbc;
    private final PlatformTransactionManager txm;
    private final VirtualLocationService locations;

    public TestIdempotencyLeaseController(JdbcClient jdbc, PlatformTransactionManager txm,
            VirtualLocationService locations) {
        this.jdbc = jdbc;
        this.txm = txm;
        this.locations = locations;
    }

    @Idempotent(required = true)
    @PostMapping("/test-kernel/lease/payments")
    public Map<String, Object> pay(@RequestBody Map<String, Object> body) {
        String tag = (String) body.get("tag");
        String mode = (String) body.getOrDefault("mode", "ok");
        int run = RUNS.computeIfAbsent(tag, k -> new AtomicInteger()).incrementAndGet();
        new TransactionTemplate(txm).executeWithoutResult(s -> {
            switch (mode) {
                case "quarantine" -> locations.ensure("ILQ-" + tag, "Lease test quarantine");
                case "nested" -> {
                    TransactionTemplate inner = new TransactionTemplate(txm);
                    inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    inner.executeWithoutResult(t -> marker(jdbc, tag + "-inner"));
                }
                default -> { }
            }
            marker(jdbc, tag + "-" + run);
            if (run == 1 && !"ok".equals(mode)) {
                throw new IllegalStateException("lease test failure");
            }
        });
        return Map.of("tag", tag, "run", run);
    }

    /** Insert the marker {@code code} for the current organization. */
    static void marker(JdbcClient jdbc, String code) {
        jdbc.sql("INSERT INTO \"CostCenter\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?, ?, now())")
                .params(UUID.randomUUID().toString(), TenantContext.organizationId(), code, code)
                .update();
    }
}
