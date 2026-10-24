package com.inventoryerp.kernel.common;

import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The "local" time zone of the business: what Nest reads as the host's local time ({@code new Date().getHours()},
 * {@code toLocaleDateString()}, {@code setHours(0, 0, 0, 0)}) for receipts, report days and date-only filters.
 * The JVM default stays UTC because Prisma stores UTC wall time (see {@code Application#main}); this zone only
 * changes how instants are shown and how calendar days are cut. Configured with {@code APP_TIMEZONE}
 * (e.g. {@code Africa/Kampala}); the default {@code UTC} matches a Nest host without {@code TZ}.
 */
@Component
public class BusinessZone {

    private static volatile ZoneId zone = ZoneId.of("UTC");

    public BusinessZone(@Value("${app.timezone:UTC}") String timezone) {
        zone = ZoneId.of(timezone == null || timezone.isBlank() ? "UTC" : timezone.trim());
    }

    /** The configured business time zone. */
    public static ZoneId get() {
        return zone;
    }
}
