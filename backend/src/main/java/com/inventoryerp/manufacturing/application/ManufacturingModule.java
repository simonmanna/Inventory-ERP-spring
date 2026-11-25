package com.inventoryerp.manufacturing.application;

import com.inventoryerp.kernel.modules.ModuleRegistry;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Module manifest of {@code manufacturing}, registered when ENABLE_MANUFACTURING is on, so the Modules screen lists it
 * and add-ons (PLM, ADR-S086) can declare it as their dependency. Its routes are gated by
 * {@code @RequiresModule("manufacturing")} and the licence feature {@code manufacturing}.
 */
@Component
@ConditionalOnProperty(name = "app.modules.manufacturing", havingValue = "true")
public class ManufacturingModule {

    public static final String NAME = "manufacturing";

    private final ModuleRegistry modules;

    public ManufacturingModule(ModuleRegistry modules) {
        this.modules = modules;
    }

    @PostConstruct
    void register() {
        modules.register(new ModuleRegistry.ErpModule(NAME, "3.0.0", List.of()));
    }
}
