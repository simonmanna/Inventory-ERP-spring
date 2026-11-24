package com.inventoryerp.plm.application;

import com.inventoryerp.kernel.modules.ModuleRegistry;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Module manifest of {@code plm} (ADR-S086), registered when ENABLE_PLM is on (default: ENABLE_MANUFACTURING). PLM is
 * an add-on of {@code manufacturing}: on wherever manufacturing is on unless an organization switches it off on the
 * Modules screen. Its routes are gated by {@code @RequiresModule("plm")}; the licence sells it as part of
 * {@code manufacturing}.
 */
@Component
@ConditionalOnProperty(name = "app.modules.plm", havingValue = "true")
public class PlmModule {

    public static final String NAME = "plm";

    private final ModuleRegistry modules;

    public PlmModule(ModuleRegistry modules) {
        this.modules = modules;
    }

    @PostConstruct
    void register() {
        modules.register(ModuleRegistry.ErpModule.addOn(NAME, "2.0.0", List.of("manufacturing")));
    }
}
