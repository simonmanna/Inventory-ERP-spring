package com.inventoryerp.kernel.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** IPR SW-7 / ADR-S088 (owner decision P15-1): device times are accepted and flagged, never refused for the clock. */
class OccurredAtFlagTest {

    @Test
    @DisplayName("SW-7: an op more than 7 days old is accepted as sent and flagged lateSync")
    void oldOpAcceptedAndFlagged() {
        Instant old = Instant.now().minus(Duration.ofDays(30)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        OccurredAt.Resolved r = OccurredAt.resolveFlagged(old.toString());
        assertThat(r.at()).isEqualTo(old);
        assertThat(r.lateSync()).isTrue();
        assertThat(r.clockSkew()).isFalse();
        assertThat(OccurredAt.resolve(old.toString())).isEqualTo(old);
    }

    @Test
    @DisplayName("SW-7: an op ahead of the server is stored at the receive time and flagged clockSkew")
    void futureOpClampedAndFlagged() {
        Instant ahead = Instant.now().plus(Duration.ofHours(2));
        Instant before = Instant.now();
        OccurredAt.Resolved r = OccurredAt.resolveFlagged(ahead.toString());
        assertThat(r.clockSkew()).isTrue();
        assertThat(r.device()).isEqualTo(ahead);
        assertThat(r.at()).isBetween(before, Instant.now());
        assertThat(OccurredAt.check(ahead)).isBefore(ahead);
    }

    @Test
    @DisplayName("SW-7: within the limits nothing is flagged; a malformed value is still refused")
    void normalAndMalformed() {
        Instant recent = Instant.now().minusSeconds(60);
        OccurredAt.Resolved r = OccurredAt.checkFlagged(recent);
        assertThat(r.flagged()).isFalse();
        assertThat(r.at()).isEqualTo(recent);
        assertThat(OccurredAt.resolve(null)).isNull();
        assertThatThrownBy(() -> OccurredAt.resolve("nope")).hasMessage("occurredAt is not a valid ISO-8601 timestamp");
    }
}
