package com.inventoryerp.portal.application;

import com.inventoryerp.kernel.modules.ModuleRegistry;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Module manifest of {@code portal}, registered when ENABLE_PORTAL is on so the Modules screen lists it. */
@Component
@ConditionalOnProperty(name = "app.modules.portal", havingValue = "true")
public class PortalModule {

    public static final String NAME = "portal";

    private final ModuleRegistry modules;

    public PortalModule(ModuleRegistry modules) {
        this.modules = modules;
    }

    @PostConstruct
    void register() {
        modules.register(new ModuleRegistry.ErpModule(NAME, "1.0.0", List.of()));
    }
}
