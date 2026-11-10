package com.inventoryerp.sync.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A dead-lettered sync operation is stored without PINs, passwords or approval tokens (hardening 2026-11-10). */
class SyncDeadLetterRedactionTest {

    @Test
    void secretsAreRedactedAtAnyDepth() {
        Map<String, Object> payload = Map.of("cashRegisterId", "r1", "managerPin", "5555", "closing",
                Map.of("incomingPin", "1234", "amount", 100), "lines", List.of(Map.of("overridePin", "9999", "qty", 2)),
                "approvalToken", "abc", "password", "secret");
        String json = String.valueOf(SyncPushService.withoutSecrets(payload));
        assertThat(json).doesNotContain("5555", "1234", "9999", "abc", "secret").contains("r1", "100", "[redacted]");
    }

    @Test
    void absentSecretsStayAbsent() {
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) SyncPushService.withoutSecrets(Map.of("pin", "", "notes", "x"));
        assertThat(out).containsEntry("notes", "x").containsEntry("pin", "[redacted]");
    }
}
