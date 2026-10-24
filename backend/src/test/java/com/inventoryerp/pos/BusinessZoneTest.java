package com.inventoryerp.pos;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.pos.application.JsFormat;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Receipt dates follow {@code APP_TIMEZONE} (Nest: the host TZ); the default stays UTC. */
class BusinessZoneTest {

    private static final Instant LATE_UTC = Instant.parse("2026-10-23T23:54:00Z");

    @AfterEach
    void reset() {
        new BusinessZone("UTC");
    }

    @Test
    void defaultsToUtc() {
        new BusinessZone("");
        assertThat(BusinessZone.get()).isEqualTo(ZoneId.of("UTC"));
        assertThat(JsFormat.gbDate(LATE_UTC)).isEqualTo("23 Oct 2026");
        assertThat(JsFormat.hhmm(LATE_UTC)).isEqualTo("23:54");
    }

    @Test
    void receiptUsesBusinessZone() {
        new BusinessZone("Africa/Kampala");
        assertThat(JsFormat.gbDate(LATE_UTC)).isEqualTo("24 Oct 2026");
        assertThat(JsFormat.hhmm(LATE_UTC)).isEqualTo("02:54");
    }
}
