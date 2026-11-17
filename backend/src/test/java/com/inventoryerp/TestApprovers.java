package com.inventoryerp;

import java.util.UUID;

/**
 * Test fixture: a second DEMO user with the administrator's roles, for maker-checker steps (readiness gap fix h03-F6:
 * whoever raised a purchase request cannot approve it, with or without an approval workflow).
 */
public final class TestApprovers {

    private TestApprovers() {}

    /** Logs in a new DEMO user holding the roles of {@code admin@demo.test}; returns the access token. */
    public static String secondAdmin(ApiTestSupport t) {
        String email = "approver-" + UUID.randomUUID().toString().substring(0, 8) + "@demo.test";
        String userId = t.createUser(t.demoOrg, email, "Approve@12345", null);
        t.jdbc.sql("INSERT INTO \"_UserRoles\" (\"A\", \"B\") SELECT ur.\"A\", ? FROM \"_UserRoles\" ur JOIN \"User\" u"
                        + " ON u.id = ur.\"B\" WHERE u.email = 'admin@demo.test' AND u.\"organizationId\" = ?")
                .params(userId, t.demoOrg).update();
        return t.login("DEMO", email, "Approve@12345");
    }
}
