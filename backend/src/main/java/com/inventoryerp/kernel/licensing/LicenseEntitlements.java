package com.inventoryerp.kernel.licensing;

import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes the licence drive the gating this install already has.
 *
 * <p>The install was gating modules long before it had a licence: {@code OrganizationModule} rows
 * behind {@link com.inventoryerp.kernel.modules.RequiresModule}, and the {@code settings.features}
 * map behind the web sidebar. Rather than add a third mechanism, the licence writes those two —
 * so every existing guard, screen and test keeps working, and there is one answer to "is CRM on?".
 *
 * <p>The rule is <b>effective = licensed ∩ enabled</b>:
 *
 * <ul>
 *   <li>A feature the licence does not grant has its module row deactivated for every organization.
 *       The guard then refuses it, whatever Developer Settings says.
 *   <li>A feature the licence grants is <em>not</em> force-enabled. The operator may still switch it
 *       off locally, and that choice is theirs to keep — reconciling only ever removes what was
 *       never paid for.
 * </ul>
 *
 * <p>Writes go through plain SQL rather than the JPA entity because this runs for every
 * organization at once and must not depend on a tenant context: it is called from a background job
 * and from activation, neither of which is inside a request.
 */
@Service
public class LicenseEntitlements {

    private static final Logger log = LoggerFactory.getLogger(LicenseEntitlements.class);

    private final JdbcClient jdbc;

    public LicenseEntitlements(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Bring module enablement into line with what the licence grants.
     *
     * @return how many module rows were switched off
     */
    @Transactional
    public int reconcile(LicenseState state) {
        // A trial grants everything, so there is nothing to take away; and while enforcement is off
        // the licence must not be quietly rewriting a developer's module setup either.
        if (state.mode() == LicenseMode.TRIAL || !state.licensed()) {
            return 0;
        }

        Set<String> licensed = state.features();
        List<String> organizations = jdbc.sql(
                        "SELECT id FROM \"Organization\" WHERE \"deletedAt\" IS NULL")
                .query(String.class)
                .list();
        if (organizations.isEmpty()) {
            return 0;
        }

        int disabled = 0;
        for (String feature : LicenseFeatures.ALL) {
            if (licensed.contains(feature)) {
                continue;
            }
            String module = LicenseFeatures.moduleName(feature);
            disabled += jdbc.sql("""
                            UPDATE "OrganizationModule"
                            SET "isActive" = false, "disabledAt" = now()
                            WHERE "moduleName" = ? AND "isActive" = true""")
                    .param(module)
                    .update();
        }
        if (disabled > 0) {
            log.info("Licence does not include {} module(s) that were switched on; "
                    + "{} organization module row(s) deactivated",
                    LicenseFeatures.ALL.size() - licensed.size(), disabled);
        }
        return disabled;
    }
}
