package com.inventoryerp.kernel.settings;

import com.inventoryerp.kernel.common.OccurredAt;
import com.inventoryerp.kernel.settings.SettingResolverService.SettingContext;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * IPR SW-7 / ADR-S088: feeds the organisation's time limits to {@link OccurredAt} — {@code
 * inventory.maxFutureEffectiveMinutes} (future skew) and {@code inventory.maxBackdateDays} (the age after which an
 * offline op is flagged late).
 */
@Component
public class OccurredAtSettings {

    public OccurredAtSettings(SettingResolverService settings) {
        OccurredAt.limits(() -> Duration.ofMinutes(number(settings, "inventory.maxFutureEffectiveMinutes", 5)),
                () -> Duration.ofDays(number(settings, "inventory.maxBackdateDays", 7)));
    }

    static long number(SettingResolverService settings, String key, long dflt) {
        try {
            Number n = settings.resolveNumber(key, SettingContext.EMPTY);
            return n == null || n.longValue() < 0 ? dflt : n.longValue();
        } catch (RuntimeException e) {
            return dflt;
        }
    }
}
