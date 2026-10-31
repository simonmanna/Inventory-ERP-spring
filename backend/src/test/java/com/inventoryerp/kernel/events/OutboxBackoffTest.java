package com.inventoryerp.kernel.events;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OutboxBackoffTest {

    @Test
    void doublesFromFiveSecondsAndCapsAtFifteenMinutes() {
        assertThat(OutboxWorker.backoffMs(1)).isEqualTo(5_000);
        assertThat(OutboxWorker.backoffMs(2)).isEqualTo(10_000);
        assertThat(OutboxWorker.backoffMs(4)).isEqualTo(40_000);
        assertThat(OutboxWorker.backoffMs(9)).isEqualTo(900_000);
        assertThat(OutboxWorker.backoffMs(60)).isEqualTo(900_000);
    }
}
