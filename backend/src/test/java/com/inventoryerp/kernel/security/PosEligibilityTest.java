package com.inventoryerp.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Port of {@code pos-eligibility.spec.ts}. */
class PosEligibilityTest {

    @Test
    @DisplayName("keeps an unlinked login on the tills")
    void unlinked() {
        assertThat(PosEligibility.userMayOperatePos(true, null, null)).isTrue();
    }

    @ParameterizedTest(name = "allows {0} employees")
    @ValueSource(strings = {"ACTIVE", "PROBATION", "ON_LEAVE"})
    void allows(String status) {
        assertThat(PosEligibility.userMayOperatePos(true, null, new PosEligibility.Employee(status, null))).isTrue();
    }

    @ParameterizedTest(name = "takes {0} employees off the tills")
    @ValueSource(strings = {"SUSPENDED", "TERMINATED", "RESIGNED"})
    void blocks(String status) {
        assertThat(PosEligibility.userMayOperatePos(true, null, new PosEligibility.Employee(status, null))).isFalse();
    }

    @Test
    @DisplayName("ignores a soft-deleted employee record")
    void deletedEmployee() {
        assertThat(PosEligibility.employmentBlocksPos(new PosEligibility.Employee("TERMINATED", Instant.now()))).isFalse();
    }

    @Test
    @DisplayName("refuses a disabled or deleted login regardless of HR")
    void disabledLogin() {
        assertThat(PosEligibility.userMayOperatePos(false, null, null)).isFalse();
        assertThat(PosEligibility.userMayOperatePos(true, Instant.now(), null)).isFalse();
    }

    @Test
    @DisplayName("createPosSessionCheck: revokes a disabled user and caches the answer")
    void sessionCheckCaches() {
        AtomicInteger calls = new AtomicInteger();
        PosSessionCheck check = new PosSessionCheck((org, user) -> {
            calls.incrementAndGet();
            assertThat(org).isEqualTo("org-1");
            assertThat(user).isEqualTo("user-1");
            return Optional.of(new PosSessionCheck.UserRow(false, null, null));
        });
        assertThat(check.posUserStillAllowed("org-1", "user-1")).isFalse();
        assertThat(check.posUserStillAllowed("org-1", "user-1")).isFalse();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("createPosSessionCheck: treats a user missing from the org as revoked")
    void missingUser() {
        PosSessionCheck check = new PosSessionCheck((org, user) -> Optional.empty());
        assertThat(check.posUserStillAllowed("org-1", "ghost")).isFalse();
    }
}
