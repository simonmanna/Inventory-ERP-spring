package com.inventoryerp.guide;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** {@code /guide/progress} (ADR-S079): a user's own learning progress through the in-app guided tours. */
class GuideProgressIT extends ApiTestSupport {

    private static String key() {
        return "test.tour-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static JsonNode find(JsonNode list, String tourKey) {
        for (JsonNode n : list) {
            if (tourKey.equals(n.get("tourKey").asString())) {
                return n;
            }
        }
        return null;
    }

    @Test
    @DisplayName("save, list, complete and reset a tour")
    void saveListCompleteReset() {
        String key = key();
        JsonNode started = ok(200, "PUT", "/guide/progress/" + key,
                Map.of("tourVersion", 1, "status", "in_progress", "currentStep", 2));
        assertThat(started.get("currentStep").asInt()).isEqualTo(2);
        assertThat(started.get("completedAt").isNull()).isTrue();
        assertThat(started.get("startedAt").isNull()).isFalse();

        JsonNode done = ok(200, "PUT", "/guide/progress/" + key,
                Map.of("tourVersion", 1, "status", "completed", "currentStep", 5));
        assertThat(done.get("status").asString()).isEqualTo("completed");
        assertThat(done.get("completedAt").isNull()).isFalse();
        assertThat(done.get("startedAt").asString()).isEqualTo(started.get("startedAt").asString());

        // A replay keeps the completion date.
        JsonNode replay = ok(200, "PUT", "/guide/progress/" + key,
                Map.of("tourVersion", 1, "status", "in_progress", "currentStep", 0));
        assertThat(replay.get("completedAt").asString()).isEqualTo(done.get("completedAt").asString());

        assertThat(find(ok(200, "GET", "/guide/progress", null), key)).isNotNull();
        assertThat(as("DELETE", "/guide/progress/" + key, null).status()).isEqualTo(204);
        assertThat(find(ok(200, "GET", "/guide/progress", null), key)).isNull();
    }

    @Test
    @DisplayName("a user sees only their own progress; another organization sees none of it")
    void ownRowsOnly() {
        String key = key();
        ok(200, "PUT", "/guide/progress/" + key, Map.of("tourVersion", 1, "status", "in_progress", "currentStep", 1));

        Res colleague = call("GET", "/guide/progress", null, weakToken());
        assertThat(colleague.status()).isEqualTo(200);
        assertThat(find(colleague.body(), key)).isNull();

        Res other = call("GET", "/guide/progress", null, otherToken);
        assertThat(other.status()).isEqualTo(200);
        assertThat(find(other.body(), key)).isNull();

        // The other organization's reset of the same key leaves this user's row alone.
        assertThat(call("DELETE", "/guide/progress/" + key, null, otherToken).status()).isEqualTo(204);
        assertThat(find(ok(200, "GET", "/guide/progress", null), key)).isNotNull();
    }

    @Test
    @DisplayName("any signed-in user may record progress; anonymous calls are refused")
    void access() {
        Res weak = call("PUT", "/guide/progress/" + key(),
                Map.of("tourVersion", 1, "status", "dismissed", "currentStep", 0), weakToken());
        assertThat(weak.status()).isEqualTo(200);
        assertThat(call("GET", "/guide/progress", null, (String) null).status()).isEqualTo(401);
        assertThat(call("PUT", "/guide/progress/x", Map.of(), (String) null).status()).isEqualTo(401);
        assertThat(call("DELETE", "/guide/progress/x", null, (String) null).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("bad keys and bodies are rejected")
    void validation() {
        Res badKey = as("PUT", "/guide/progress/Bad_Key", Map.of("tourVersion", 1, "status", "completed",
                "currentStep", 0));
        assertThat(badKey.status()).isEqualTo(400);
        assertThat(badKey.body().get("message").asString())
                .isEqualTo("tourKey must be 1-100 lowercase letters, digits, dots or dashes");

        Res badBody = as("PUT", "/guide/progress/" + key(), Map.of("tourVersion", 0, "status", "done",
                "currentStep", -1));
        assertThat(badBody.status()).isEqualTo(400);
        assertThat(validationMessages(badBody)).contains("tourVersion must not be less than 1",
                "status must be one of the following values: in_progress, completed, dismissed",
                "currentStep must not be less than 0");
    }
}
