package com.inventoryerp.inventory.application;

import com.inventoryerp.accounting.application.InventoryValueChecks;
import com.inventoryerp.inventory.application.InventoryIntegrityService.IntegrityCheck;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.kernel.settings.SettingResolverService.SettingContext;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** IPR GL-10: {@link InventoryValueChecks} over {@link InventoryIntegrityService#valueChecks()}. */
@Component
class InventoryValueCheckGate implements InventoryValueChecks {

    /** Owner sign-off switch (default on). */
    static final String LIVE_SETTING = "inventory.closeGate.liveValueChecks";

    private final InventoryIntegrityService integrity;
    private final SettingResolverService settings;

    InventoryValueCheckGate(InventoryIntegrityService integrity, SettingResolverService settings) {
        this.integrity = integrity;
        this.settings = settings;
    }

    @Override
    public List<Failure> failing() {
        if (!settings.resolveBool(LIVE_SETTING, SettingContext.EMPTY)) {
            return List.of();
        }
        boolean glChecks = settings.resolveBool("inventory.glTieOut.enabled", SettingContext.EMPTY);
        List<Failure> out = new ArrayList<>();
        for (IntegrityCheck c : integrity.valueChecks()) {
            boolean onGl = InventoryIntegrityService.VALUATION_VS_GL.equals(c.key())
                    || InventoryIntegrityService.VALUATION_VS_GL_BY_PRODUCT.equals(c.key());
            if (!c.ok() && (glChecks || !onGl)) {
                out.add(new Failure(c.key(), c.failures(), c.note()));
            }
        }
        return out;
    }
}
