package com.inventoryerp.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PR-1: the API runs as a role subject to row-level security, and the tenant policy (V214) keeps a tenant transaction
 * to its own organization even when a query forgets its {@code organizationId} filter.
 */
class TenantRowSecurityIT extends ApiTestSupport {

    <T> T asTenant(String org, java.util.function.Supplier<T> body) {
        return TenantContext.run(TenantStore.user(org, null), () -> new TransactionTemplate(txm).execute(s -> body.get()));
    }

    long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    @Test
    @DisplayName("the application role owns nothing and cannot bypass row-level security")
    void applicationRoleIsRestricted() {
        List<Boolean> flags = jdbc.sql("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user")
                .query(Boolean.class).list();
        assertThat(flags).containsExactly(false);
        assertThat(count("SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tableowner = current_user"
                + " AND tablename NOT LIKE '%seq%'")).isZero();
    }

    @Test
    @DisplayName("every table with an organizationId column has the tenant policy")
    void everyTenantTableIsCovered() {
        List<String> uncovered = jdbc.sql("""
                        SELECT c.relname FROM information_schema.columns col
                        JOIN pg_class c ON c.relname = col.table_name AND c.relkind = 'r'
                        JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'public'
                        WHERE col.table_schema = 'public' AND col.column_name = 'organizationId'
                          AND (NOT c.relrowsecurity OR NOT EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid
                               AND p.polname = 'tenant_isolation'))""")
                .query(String.class).list();
        assertThat(uncovered).isEmpty();
    }

    @Test
    @DisplayName("a tenant transaction sees and writes only its organization; jobs without a tenant see all")
    void tenantTransactionIsIsolated() {
        long otherAccounts = count("SELECT count(*) FROM \"Account\" WHERE \"organizationId\" = ?", demoOrg);
        assertThat(otherAccounts).isPositive();

        // No organizationId filter on purpose: the policy filters.
        long seenByOther = asTenant(otherOrg, () -> count("SELECT count(*) FROM \"Account\" WHERE \"organizationId\" = ?",
                demoOrg));
        assertThat(seenByOther).isZero();
        long seenByDemo = asTenant(demoOrg, () -> count("SELECT count(*) FROM \"Account\""));
        assertThat(seenByDemo).isEqualTo(otherAccounts);

        String id = UUID.randomUUID().toString();
        assertThatThrownBy(() -> asTenant(otherOrg, () -> jdbc.sql("INSERT INTO \"CostCenter\" (id, \"organizationId\","
                + " code, name, \"updatedAt\") VALUES (?, ?, ?, 'RLS', now())").params(id, demoOrg, "RLS-" + id).update()))
                .rootCause().hasMessageContaining("row-level security");
        assertThat(asTenant(otherOrg, () -> jdbc.sql("UPDATE \"Account\" SET name = name WHERE \"organizationId\" = ?")
                .param(demoOrg).update())).isZero();

        // Background work (no tenant) spans organizations.
        assertThat(count("SELECT count(DISTINCT \"organizationId\") FROM \"Account\"")).isGreaterThanOrEqualTo(1);
        assertThat(count("SELECT count(*) FROM \"Organization\" WHERE id IN (?, ?)", demoOrg, otherOrg)).isEqualTo(2);
    }
}
