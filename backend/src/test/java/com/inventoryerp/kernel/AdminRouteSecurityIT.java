package com.inventoryerp.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.web.ExportController;
import com.inventoryerp.core.web.OrganizationsController;
import com.inventoryerp.kernel.featureflags.FeatureFlagsController;
import com.inventoryerp.kernel.security.Permissions;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PR-0 security fix: the organization, feature-flag and export routes that Nest left open to any signed-in user now
 * declare permissions, and an invite cannot hand out a foreign role or a role stronger than the inviter.
 */
class AdminRouteSecurityIT extends ApiTestSupport {

    String role(String org, String... permissions) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(id, org, "R-" + id, permissions).update();
        return id;
    }

    String tokenWith(String... permissions) {
        String email = "sec-" + UUID.randomUUID().toString().substring(0, 8) + "@demo.test";
        createUser(demoOrg, email, "Sec@123456", role(demoOrg, permissions));
        return login("DEMO", email, "Sec@123456");
    }

    static Map<String, Object> invite(String roleId) {
        return Map.of("email", "inv-" + UUID.randomUUID().toString().substring(0, 8) + "@demo.test", "firstName", "In",
                "roleId", roleId);
    }

    @Test
    @DisplayName("admin routes: 401 without a session, 403 without their permission")
    void authorizationMatrix() {
        assertThat(assertAuthorizationMatrix(OrganizationsController.class, FeatureFlagsController.class,
                ExportController.class)).isGreaterThanOrEqualTo(10);
    }

    @Test
    @DisplayName("a signed-in user without user:create can no longer invite an Administrator")
    void cashierCannotInvite() {
        String cashier = tokenWith(Permissions.POS__CHECKOUT);
        String admin = jdbc.sql("SELECT r.id FROM \"Role\" r WHERE r.\"organizationId\" = ? AND r.name = 'Administrator'"
                + " LIMIT 1").param(demoOrg).query(String.class).single();
        Res r = call("POST", "/organizations/users/invite", invite(admin), cashier);
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.raw().body()).doesNotContain("inviteToken");
    }

    @Test
    @DisplayName("invite: the role must belong to the organization and grant nothing the inviter lacks")
    void inviteRoleChecks() {
        String hr = tokenWith(Permissions.USER__CREATE, Permissions.USER__READ);
        Res foreign = call("POST", "/organizations/users/invite", invite(role(otherOrg, Permissions.USER__READ)), hr);
        assertThat(foreign.status()).isEqualTo(400);
        assertThat(foreign.body().get("message").asString()).isEqualTo("Role not found in this organization");

        Res stronger = call("POST", "/organizations/users/invite",
                invite(role(demoOrg, Permissions.USER__READ, Permissions.ROLE__UPDATE)), hr);
        assertThat(stronger.status()).isEqualTo(403);
        assertThat(stronger.body().get("message").asString())
                .isEqualTo("You cannot assign a role with permissions you do not hold");

        Res same = call("POST", "/organizations/users/invite", invite(role(demoOrg, Permissions.USER__READ)), hr);
        assertThat(same.status()).as(same.raw().body()).isEqualTo(201);
        assertThat(same.body().get("inviteToken").asString()).isNotBlank();
    }

    @Test
    @DisplayName("read routes stay open: profile, user list, flag list")
    void readRoutesStayOpen() {
        String weak = weakToken();
        assertThat(call("GET", "/organizations/me", null, weak).status()).isEqualTo(200);
        assertThat(call("GET", "/feature-flags", null, weak).status()).isEqualTo(200);
    }
}
