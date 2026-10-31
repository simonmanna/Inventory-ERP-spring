package com.inventoryerp.quarry.application;

import com.inventoryerp.kernel.modules.ModuleRegistry;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Module manifest of {@code quarry}, registered when ENABLE_QUARRY is on, so the Modules screen
 * ({@code GET /feature-flags/modules/catalog}) lists it and organizations can switch it on. Its routes are gated by
 * {@code @RequiresModule("quarry")} and the licence feature {@code quarry}.
 */
@Component
@ConditionalOnProperty(name = "app.modules.quarry", havingValue = "true")
public class QuarryModule {

    public static final String NAME = "quarry";

    private final ModuleRegistry modules;

    public QuarryModule(ModuleRegistry modules) {
        this.modules = modules;
    }

    @PostConstruct
    void register() {
        // Quarry reads fleet / inventory / hr rows and uses kernel services; fleet's manifest only exists when
        // ENABLE_FLEET is on, so declaring it as a dependency would fail the registry's boot validation.
        modules.register(new ModuleRegistry.ErpModule(NAME, "2.2.0", List.of()));
    }
}
