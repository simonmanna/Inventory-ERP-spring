package com.inventoryerp.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.tenancy.InstallWide;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * IPR S-6 (ADR-S093): row-level security is a backstop for every statement run for a tenant, not only for statements
 * inside a transaction. Before, {@code app.org_id} was set only by {@code TenantAwareTransactionManager.doBegin}, so the
 * many read services that are not {@code @Transactional} ran with an empty setting and the V214 policy let every row
 * through. Each connection is now bound to the caller's tenant when it is taken from the pool. Work without a tenant
 * (jobs, login, bootstrap, {@link InstallWide}) keeps the documented system mode: it spans organisations.
 */
class TenantRlsBackstopIT extends ApiTestSupport {

    @Autowired
    InstallWide installWide;

    long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    <T> T asTenant(String org, Supplier<T> body) {
        return TenantContext.run(TenantStore.user(org, null), body);
    }

    String setting() {
        return jdbc.sql("SELECT coalesce(current_setting('app.org_id', true), '')").query(String.class).single();
    }

    @Test
    @DisplayName("S-6: a tenant's statement outside any transaction sees only its own organisation's rows")
    void tenantReadOutsideTransactionIsRestricted() {
        long demoAccounts = count("SELECT count(*) FROM \"Account\" WHERE \"organizationId\" = ?", demoOrg);
        assertThat(demoAccounts).isPositive();
        // No organizationId filter in the query that matters: the policy must filter.
        assertThat(asTenant(otherOrg, () -> count("SELECT count(*) FROM \"Account\" WHERE \"organizationId\" = ?",
                demoOrg))).as("another tenant, no transaction").isZero();
        assertThat(asTenant(demoOrg, () -> count("SELECT count(*) FROM \"Account\" WHERE \"organizationId\" = ?",
                demoOrg))).as("own tenant, no transaction").isEqualTo(demoAccounts);
        assertThat(asTenant(otherOrg, () -> count("SELECT count(*) FROM \"Organization\" WHERE id = ?", demoOrg)))
                .isZero();
        assertThat(asTenant(otherOrg, this::setting)).isEqualTo(otherOrg);
    }

    @Test
    @DisplayName("S-6: the binding never leaks between pooled connections (tenant A, tenant B, no tenant, in turn)")
    void bindingNeverLeaksAcrossThePool() {
        List<String> seen = new ArrayList<>();
        // More turns than the pool has connections, so every connection is reused across contexts.
        for (int i = 0; i < 30; i++) {
            seen.add(asTenant(demoOrg, this::setting));
            seen.add(asTenant(otherOrg, this::setting));
            seen.add(setting());
            assertThat(count("SELECT count(*) FROM \"Organization\" WHERE id IN (?, ?)", demoOrg, otherOrg))
                    .as("no tenant after a tenant statement").isEqualTo(2);
        }
        for (int i = 0; i < seen.size(); i += 3) {
            assertThat(seen.subList(i, i + 3)).containsExactly(demoOrg, otherOrg, "");
        }
        // a transaction opened for a tenant keeps its own SET LOCAL as before
        String inTx = TenantContext.run(TenantStore.user(demoOrg, null),
                () -> new TransactionTemplate(txm).execute(s -> setting()));
        assertThat(inTx).isEqualTo(demoOrg);
    }

    @Test
    @DisplayName("S-6 remaining gap (ADR-S093): work without a tenant is the system mode and spans organisations;"
            + " InstallWide inside a tenant request still does")
    void noTenantIsTheSystemMode() {
        assertThat(setting()).isEmpty();
        assertThat(count("SELECT count(*) FROM \"Organization\" WHERE id IN (?, ?)", demoOrg, otherOrg)).isEqualTo(2);
        long spanned = asTenant(demoOrg, () -> installWide.run(() -> count(
                "SELECT count(*) FROM \"Organization\" WHERE id IN (?, ?)", demoOrg, otherOrg)));
        assertThat(spanned).as("install-wide work inside a tenant request").isEqualTo(2);
        long unscoped = asTenant(demoOrg, () -> TenantContext.unscoped(() -> count(
                "SELECT count(*) FROM \"Organization\" WHERE id IN (?, ?)", demoOrg, otherOrg)));
        assertThat(unscoped).as("TenantContext.unscoped outside a transaction").isEqualTo(2);
    }
}
