package com.inventoryerp.manufacturing.spi;

import java.util.Map;

/**
 * Lifecycle gates on production (ADR-S086), implemented by the PLM module. Manufacturing asks before it creates a
 * production order or activates a BOM; an implementation throws a 400 {@code ApiException} with the reason. Without an
 * implementation (PLM not deployed) nothing is gated.
 */
public interface ProductionLifecycleGate {

    /** Before a production order is created for {@code productId}. */
    void requireProducible(String productId);

    /** Before a BOM is activated: {@code bom} carries {@code code}, {@code outputProductId} and its {@code lines}. */
    void requireActivatable(Map<String, Object> bom);
}
