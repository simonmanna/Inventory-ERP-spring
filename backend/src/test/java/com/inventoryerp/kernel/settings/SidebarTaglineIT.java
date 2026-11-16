package com.inventoryerp.kernel.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** The configurable sidebar tagline under the organization name (Developer Settings). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SidebarTaglineIT extends ApiTestSupport {

    String token;

    @BeforeAll
    void org() {
        String code = "tg-" + UUID.randomUUID().toString().substring(0, 8);
        Res boot = call("POST", "/organizations/bootstrap", Map.of("organizationCode", code, "organizationName",
                "Tagline Co", "adminEmail", "owner@" + code + ".test", "adminFirstName", "Owner", "adminPassword",
                "Owner@12345"), (String) null);
        assertThat(boot.status()).as(boot.raw().body()).isEqualTo(201);
        token = login(code, "owner@" + code + ".test", "Owner@12345");
    }

    @Override
    protected Res as(String method, String path, Object body) {
        return call(method, path, body, token);
    }

    @Test
    void taglineRoundTripsAndClears() {
        assertThat(as("GET", "/settings/features", null).body().get("sidebarTagline").isNull()).isTrue();

        Res put = as("PUT", "/settings/developer", Map.of("sidebarTagline", "  Head Office  "));
        assertThat(put.status()).as(put.raw().body()).isEqualTo(200);
        assertThat(put.body().get("sidebarTagline").asString()).isEqualTo("Head Office");
        assertThat(as("GET", "/settings/developer", null).body().get("sidebarTagline").asString())
                .isEqualTo("Head Office");
        assertThat(as("GET", "/settings/features", null).body().get("sidebarTagline").asString())
                .isEqualTo("Head Office");

        // Saving other fields leaves the tagline alone.
        assertThat(as("PUT", "/settings/developer", Map.of("features", Map.of())).body().get("sidebarTagline")
                .asString()).isEqualTo("Head Office");

        // Blank or null clears it.
        assertThat(as("PUT", "/settings/developer", Map.of("sidebarTagline", " ")).body().get("sidebarTagline")
                .isNull()).isTrue();
        Map<String, Object> nul = new HashMap<>();
        nul.put("sidebarTagline", null);
        assertThat(as("PUT", "/settings/developer", nul).body().get("sidebarTagline").isNull()).isTrue();
    }

    @Test
    void rejectsTooLongOrNonString() {
        assertThat(as("PUT", "/settings/developer", Map.of("sidebarTagline", "x".repeat(61))).status())
                .isEqualTo(400);
        assertThat(as("PUT", "/settings/developer", Map.of("sidebarTagline", 5)).status()).isEqualTo(400);
    }
}
