package com.inventoryerp.inventory.application;

import com.inventoryerp.accounting.application.InventoryValueChecks;
import com.inventoryerp.inventory.application.InventoryIntegrityService.IntegrityCheck;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.kernel.settings.SettingResolverService.SettingContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * IPR GL-10: {@link InventoryValueChecks} over {@link InventoryIntegrityService#valueChecks()} (now) and, since IPR
 * review 2 (R2-1), {@link InventoryIntegrityService#valueChecks(Instant)} (as of a period end).
 */
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
        return failing(null);
    }

    @Override
    public List<Failure> failing(Instant asOf) {
        if (!settings.resolveBool(LIVE_SETTING, SettingContext.EMPTY)) {
            return List.of();
        }
        boolean glChecks = settings.resolveBool("inventory.glTieOut.enabled", SettingContext.EMPTY);
        List<Failure> out = new ArrayList<>();
        for (IntegrityCheck c : integrity.valueChecks(asOf)) {
            boolean onGl = InventoryIntegrityService.VALUATION_VS_GL.equals(c.key())
                    || InventoryIntegrityService.VALUATION_VS_GL_BY_PRODUCT.equals(c.key());
            if (!c.ok() && (glChecks || !onGl)) {
                out.add(new Failure(c.key(), c.failures(), c.note()));
            }
        }
        return out;
    }
}
