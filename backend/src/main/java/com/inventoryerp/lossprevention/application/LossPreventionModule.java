package com.inventoryerp.lossprevention.application;

import com.inventoryerp.kernel.modules.ModuleRegistry;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Module manifest of {@code loss-prevention}, registered when ENABLE_LOSS_PREVENTION is on, so the Modules screen lists
 * it. Its routes are gated by {@code @RequiresModule("loss-prevention")} and the licence feature
 * {@code lossPrevention}.
 */
@Component
@ConditionalOnProperty(name = "app.modules.lossprevention", havingValue = "true")
public class LossPreventionModule {

    public static final String NAME = "loss-prevention";

    private final ModuleRegistry modules;

    public LossPreventionModule(ModuleRegistry modules) {
        this.modules = modules;
    }

    @PostConstruct
    void register() {
        modules.register(new ModuleRegistry.ErpModule(NAME, "0.1.0", List.of()));
    }
}
