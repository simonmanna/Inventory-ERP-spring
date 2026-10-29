package com.inventoryerp.fleet.application;

import com.inventoryerp.kernel.modules.ModuleRegistry;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Module manifest of {@code fleet}, registered when ENABLE_FLEET is on, so the Modules screen
 * ({@code GET /feature-flags/modules/catalog}) lists it and organizations can switch it on. Its routes are gated by
 * {@code @RequiresModule("fleet")} and the licence feature {@code fleet}.
 */
@Component
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
public class FleetModule {

    public static final String NAME = "fleet";

    private final ModuleRegistry modules;

    public FleetModule(ModuleRegistry modules) {
        this.modules = modules;
    }

    @PostConstruct
    void register() {
        // Fleet reads core / accounting / inventory / fixedasset rows and uses kernel services; those modules
        // register no manifest in this backend, so declaring them would fail the registry's boot validation.
        modules.register(new ModuleRegistry.ErpModule(NAME, "1.1.0", List.of()));
    }
}
