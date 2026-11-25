package com.inventoryerp.plm.application;

import com.inventoryerp.kernel.modules.ModuleAccess;
import com.inventoryerp.kernel.modules.ModuleRegistry;
import org.springframework.stereotype.Component;

/**
 * Is the PLM application on (ADR-S086)? Deployed ({@code app.modules.plm}, which registers its manifest) and on for
 * the organization (an add-on of manufacturing that the organization has not switched off). PLM's behaviour outside
 * its own routes — gates on production, BOM activation, purchasing, sales and replenishment, the effectivity job and
 * the review of ADR-S006 change orders — applies only while this says yes.
 */
@Component
public class PlmApp {

    private final ModuleRegistry registry;
    private final ModuleAccess access;

    public PlmApp(ModuleRegistry registry, ModuleAccess access) {
        this.registry = registry;
        this.access = access;
    }

    /** For the organization of the current tenant context. */
    public boolean on() {
        return registry.has(PlmModule.NAME) && access.allowed(PlmModule.NAME);
    }

    /** For {@code orgId} (call inside that organization's tenant context). */
    public boolean on(String orgId) {
        return registry.has(PlmModule.NAME) && access.allowed(orgId, PlmModule.NAME);
    }
}
