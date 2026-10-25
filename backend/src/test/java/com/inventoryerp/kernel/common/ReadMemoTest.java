package com.inventoryerp.kernel.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReadMemoTest {

    @Test
    @DisplayName("outside a scope every lookup loads")
    void noScope() {
        AtomicInteger loads = new AtomicInteger();
        ReadMemo.get(loads::incrementAndGet, "k");
        ReadMemo.get(loads::incrementAndGet, "k");
        assertThat(loads.get()).isEqualTo(2);
        assertThat(ReadMemo.active()).isFalse();
    }

    @Test
    @DisplayName("inside a scope a key loads once; null results and other keys are not shared")
    void scope() {
        AtomicInteger loads = new AtomicInteger();
        ReadMemo.run(() -> {
            assertThat((Integer) ReadMemo.get(loads::incrementAndGet, "a", 1)).isEqualTo(1);
            assertThat((Integer) ReadMemo.get(loads::incrementAndGet, "a", 1)).isEqualTo(1);
            assertThat((Integer) ReadMemo.get(loads::incrementAndGet, "a", 2)).isEqualTo(2);
            assertThat((Integer) ReadMemo.get(loads::incrementAndGet, "a", null)).isEqualTo(3);
            assertThat((Integer) ReadMemo.get(loads::incrementAndGet, "a", null)).isEqualTo(3);
            AtomicInteger nulls = new AtomicInteger();
            ReadMemo.get(() -> {
                nulls.incrementAndGet();
                return null;
            }, "missing");
            ReadMemo.get(() -> {
                nulls.incrementAndGet();
                return null;
            }, "missing");
            assertThat(nulls.get()).isEqualTo(2);
            return null;
        });
        assertThat(ReadMemo.active()).isFalse();
    }

    @Test
    @DisplayName("nested runs share the outer scope; clear forgets everything")
    void nestedAndClear() {
        AtomicInteger loads = new AtomicInteger();
        ReadMemo.run(() -> {
            ReadMemo.get(loads::incrementAndGet, "k");
            ReadMemo.run(() -> ReadMemo.get(loads::incrementAndGet, "k"));
            assertThat(ReadMemo.active()).isTrue();
            assertThat(loads.get()).isEqualTo(1);
            ReadMemo.clear();
            ReadMemo.get(loads::incrementAndGet, "k");
            assertThat(loads.get()).isEqualTo(2);
            return null;
        });
    }
}
