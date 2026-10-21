package com.inventoryerp.kernel.health;

/**
 * An advisory readiness check contributed by a module (Nest {@code HealthController.collect}: backup health). Failing
 * advisory checks turn {@code /health/ready} {@code degraded}, never 503.
 */
public interface AdvisoryHealthCheck {

    /** Key under {@code advisory} / {@code checks}. */
    String name();

    /** {@code null} when healthy; otherwise the issues, joined. Throwing counts as unhealthy. */
    String problems();
}
