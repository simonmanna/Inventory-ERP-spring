package com.inventoryerp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PR-1: the 401/403 matrix over every route of the application in one run, not module by module: without a session
 * every non-public route answers 401, and a signed-in user without the route's permission gets 403 with the Nest
 * message. Routes open to any signed-in user are listed in {@code ArchitectureTests}.
 */
class AuthorizationMatrixIT extends ApiTestSupport {

    @Test
    @DisplayName("every route: 401 without a session, 403 without its permission")
    void everyRoute() {
        int checked = assertAuthorizationMatrix("com.inventoryerp");
        assertThat(checked).isGreaterThan(2000);
    }
}
