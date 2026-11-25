package com.inventoryerp.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.security.DatabaseRoleValidator.Facts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Finance readiness audit A4 (P2-11): the production start-up check of the API's database role. The integration
 * suites run like production: the application as a role that owns nothing, Flyway as the schema owner.
 */
class DatabaseRoleValidatorIT extends ApiTestSupport {

    @Autowired
    FlywayConnectionDetails owner;

    @Autowired
    JdbcConnectionDetails app;

    @Test
    @DisplayName("A4: the application role is under row-level security and passes")
    void theApplicationRolePasses() {
        Facts facts = DatabaseRoleValidator.inspect(jdbc);
        assertThat(facts.role()).isEqualTo(app.getUsername());
        assertThat(facts.superuser()).isFalse();
        assertThat(facts.bypassRls()).isFalse();
        assertThat(facts.ownedRlsTables()).isZero();
        assertThat(DatabaseRoleValidator.validate(facts)).isEmpty();
        assertThatCode(() -> new DatabaseRoleValidator(jdbc, "fail").afterSingletonsInstantiated())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A4: an API that connects as the migration role is refused; APP_DB_ROLE_CHECK=warn only logs")
    void theMigrationRoleIsRefused() {
        String url = owner.getJdbcUrl() != null ? owner.getJdbcUrl() : app.getJdbcUrl();
        JdbcClient asOwner = JdbcClient.create(new DriverManagerDataSource(url, owner.getUsername(), owner.getPassword()));

        Facts facts = DatabaseRoleValidator.inspect(asOwner);
        assertThat(facts.role()).isEqualTo(owner.getUsername()).isNotEqualTo(app.getUsername());
        // the owner of the schema: the policies of its own tables do not apply to it
        assertThat(facts.ownedRlsTables()).isGreaterThan(100);
        assertThat(DatabaseRoleValidator.validate(facts))
                .anySatisfy(error -> assertThat(error).startsWith("DB_USER '" + owner.getUsername() + "' owns "));

        assertThatThrownBy(() -> new DatabaseRoleValidator(asOwner, "fail").afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Refusing to start: DB_USER '" + owner.getUsername() + "'");
        // an unknown mode fails too: only `warn` starts
        assertThatThrownBy(() -> new DatabaseRoleValidator(asOwner, "off").afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new DatabaseRoleValidator(asOwner, " WARN ").afterSingletonsInstantiated())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A4: each way out of row-level security is named")
    void eachFindingIsNamed() {
        assertThat(DatabaseRoleValidator.validate(new Facts("erp_app", false, false, 0))).isEmpty();
        assertThat(DatabaseRoleValidator.validate(new Facts("postgres", true, false, 0))).containsExactly(
                "DB_USER 'postgres' is a database superuser: tenant row-level security does not apply to it");
        assertThat(DatabaseRoleValidator.validate(new Facts("etl", false, true, 0)))
                .containsExactly("DB_USER 'etl' has BYPASSRLS: tenant row-level security does not apply to it");
        assertThat(DatabaseRoleValidator.validate(new Facts("erp", false, false, 290))).containsExactly(
                "DB_USER 'erp' owns 290 table(s) with row-level security, or is a member of the role that owns them:"
                        + " the policies do not apply to a table's owner. Run the migrations as the owner (FLYWAY_USER)"
                        + " and the API as a separate role that owns nothing (DB_USER)");
        assertThat(DatabaseRoleValidator.validate(new Facts("postgres", true, true, 290))).hasSize(3);
    }
}
