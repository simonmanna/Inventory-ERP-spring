package com.inventoryerp.kernel.security;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Refuse-to-start guard for the {@code prod} profile (finance readiness audit A4, P2-11): the API's database role
 * ({@code DB_USER}) must be subject to the tenant row-level security policies. PostgreSQL does not apply them to a
 * superuser, to a role with {@code BYPASSRLS}, or to the owner of a table, so an API that connects as the migration
 * role ({@code FLYWAY_USER}, the default when only {@code DB_USER} is set) runs without the database backstop behind
 * the {@code organizationId} filters. Runs once every singleton exists, so after the Flyway migration.
 *
 * <p>{@code APP_DB_ROLE_CHECK=warn} only logs the findings: for an emergency start, not for normal running.
 */
@Component
@Profile("prod")
public class DatabaseRoleValidator implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(DatabaseRoleValidator.class);

    /** What the database says about the role of a connection. */
    record Facts(String role, boolean superuser, boolean bypassRls, long ownedRlsTables) {}

    private final JdbcClient jdbc;
    private final String mode;

    public DatabaseRoleValidator(JdbcClient jdbc, @Value("${app.db-role-check:fail}") String mode) {
        this.jdbc = jdbc;
        this.mode = mode;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> errors = validate(inspect(jdbc));
        if (errors.isEmpty()) {
            return;
        }
        errors.forEach(log::error);
        if ("warn".equalsIgnoreCase(mode == null ? "" : mode.strip())) {
            log.error("APP_DB_ROLE_CHECK=warn: starting anyway, without tenant row-level security in the database");
            return;
        }
        throw new IllegalStateException("Refusing to start: " + String.join("; ", errors));
    }

    /**
     * The role of {@code jdbc}'s connections: whether it is a superuser, has {@code BYPASSRLS}, and how many tables
     * with row-level security it owns, directly or as a member of the owning role, without {@code FORCE ROW LEVEL
     * SECURITY} (the policies of those tables do not apply to it).
     */
    static Facts inspect(JdbcClient jdbc) {
        return jdbc.sql("SELECT current_user::text, r.rolsuper, r.rolbypassrls, (SELECT count(*) FROM pg_class c"
                        + " JOIN pg_namespace n ON n.oid = c.relnamespace WHERE c.relkind IN ('r', 'p')"
                        + " AND n.nspname NOT IN ('pg_catalog', 'information_schema') AND c.relrowsecurity"
                        + " AND NOT c.relforcerowsecurity AND pg_has_role(current_user, c.relowner, 'MEMBER'))"
                        + " FROM pg_roles r WHERE r.rolname = current_user")
                .query((rs, n) -> new Facts(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3), rs.getLong(4)))
                .single();
    }

    /** The reasons row-level security does not apply to the role; empty when it does. */
    static List<String> validate(Facts facts) {
        List<String> errors = new ArrayList<>();
        String role = "DB_USER '" + facts.role() + "'";
        if (facts.superuser()) {
            errors.add(role + " is a database superuser: tenant row-level security does not apply to it");
        }
        if (facts.bypassRls()) {
            errors.add(role + " has BYPASSRLS: tenant row-level security does not apply to it");
        }
        if (facts.ownedRlsTables() > 0) {
            errors.add(role + " owns " + facts.ownedRlsTables() + " table(s) with row-level security, or is a member"
                    + " of the role that owns them: the policies do not apply to a table's owner. Run the migrations as"
                    + " the owner (FLYWAY_USER) and the API as a separate role that owns nothing (DB_USER)");
        }
        return errors;
    }
}
