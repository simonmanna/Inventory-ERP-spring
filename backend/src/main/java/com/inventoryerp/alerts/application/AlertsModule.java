package com.inventoryerp.alerts.application;

import com.inventoryerp.kernel.modules.ModuleRegistry;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Module manifest of {@code alerts}, registered when ENABLE_ALERTS is on, so the Modules screen lists it. Its routes
 * are gated by {@code @RequiresModule("alerts")}. The licence does not price it separately.
 */
@Component
@ConditionalOnProperty(name = "app.modules.alerts", havingValue = "true")
public class AlertsModule {

    public static final String NAME = "alerts";

    private final ModuleRegistry modules;

    public AlertsModule(ModuleRegistry modules) {
        this.modules = modules;
    }

    @PostConstruct
    void register() {
        modules.register(new ModuleRegistry.ErpModule(NAME, "1.0.0", List.of()));
    }
}
