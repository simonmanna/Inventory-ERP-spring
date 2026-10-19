package com.inventoryerp.accounting.application;

import com.inventoryerp.kernel.seed.SeedContributor;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Accounting part of the demo seed: chart of accounts, journals and mappings; links the General product
 * category and the No Tax rate to their accounts; the {@code MAIN-REG} cash register on account 1100.
 */
@Component
public class AccountingSeedContributor implements SeedContributor {

    private final JdbcClient jdbc;

    public AccountingSeedContributor(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int order() {
        return 200;
    }

    @Override
    public void seed(String organizationId) {
        CoaSeeder.seedAccountCategories(jdbc);
        Map<String, String> accountIds = CoaSeeder.seedAccountingCore(jdbc, organizationId);
        jdbc.sql("UPDATE \"ProductCategory\" SET \"incomeAccountId\" = ?, \"expenseAccountId\" = ?, \"updatedAt\" = now()"
                        + " WHERE \"organizationId\" = ? AND name = 'General'")
                .params(accountIds.get("4100"), accountIds.get("5100"), organizationId).update();
        jdbc.sql("UPDATE \"Tax\" SET \"accountId\" = ?, \"updatedAt\" = now() WHERE \"organizationId\" = ? AND code = 'NOTAX'")
                .params(accountIds.get("2200"), organizationId).update();
        jdbc.sql("INSERT INTO \"CashRegister\" (id, \"organizationId\", code, name, \"isActive\", \"defaultAccountId\","
                        + " \"updatedAt\") VALUES (gen_random_uuid()::text, ?, 'MAIN-REG', 'Main Counter Register', true, ?,"
                        + " now()) ON CONFLICT (\"organizationId\", code) DO UPDATE SET name = EXCLUDED.name,"
                        + " \"isActive\" = true, \"defaultAccountId\" = EXCLUDED.\"defaultAccountId\", \"updatedAt\" = now()")
                .params(organizationId, accountIds.get("1100")).update();
    }
}
