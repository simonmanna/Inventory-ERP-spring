package com.inventoryerp.crm;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.crm.application.CrmCaseRules;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The case status machine and response times (CRM-1). */
class CrmCaseRulesTest {

    @Test
    @DisplayName("resolved can reopen or close; closed and cancelled are final; open cannot close directly")
    void transitions() {
        assertThat(CrmCaseRules.mayBecome("open", "resolved")).isTrue();
        assertThat(CrmCaseRules.mayBecome("resolved", "in_progress")).isTrue();
        assertThat(CrmCaseRules.mayBecome("resolved", "closed")).isTrue();
        assertThat(CrmCaseRules.mayBecome("open", "closed")).isFalse();
        assertThat(CrmCaseRules.mayBecome("closed", "open")).isFalse();
        assertThat(CrmCaseRules.mayBecome("cancelled", "open")).isFalse();
    }

    @Test
    @DisplayName("priority sets the response time, unknown falls back to normal")
    void dueTimes() {
        Instant t = Instant.parse("2026-11-02T08:00:00Z");
        assertThat(CrmCaseRules.dueAt(t, "urgent")).isEqualTo(t.plus(Duration.ofHours(4)));
        assertThat(CrmCaseRules.dueAt(t, "low")).isEqualTo(t.plus(Duration.ofDays(7)));
        assertThat(CrmCaseRules.dueAt(t, "other")).isEqualTo(t.plus(Duration.ofHours(72)));
    }
}
