package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.application.AccountingCatalogs.CategoryDef;
import com.inventoryerp.accounting.application.AccountingCatalogs.CoaAccount;
import com.inventoryerp.accounting.application.AccountingCatalogs.CoaJournal;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Seeds the global account-category catalog and the per-organization accounting core (ports of
 * {@code account-category-seeder.ts} and {@code coa-seeder.ts}). Raw SQL: runs at boot / bootstrap
 * without a tenant context. Both are idempotent (upsert-only; categories are version-gated).
 */
public final class CoaSeeder {

    private CoaSeeder() {}

    /** Upsert every category; a stored row is rewritten only when the template's systemVersion is higher. */
    public static int seedAccountCategories(JdbcClient jdbc) {
        for (CategoryDef c : AccountingCatalogs.CATEGORIES) {
            jdbc.sql("""
                    INSERT INTO "AccountCategory" (
                      "id", "key", "name", "description",
                      "classification", "normalBalance", "reportSection", "cashFlowClass",
                      "isContra", "isCashEquivalent",
                      "allowReconciliation", "allowManualPosting", "allowBudgeting",
                      "isSystem", "isActive", "sortOrder", "systemVersion",
                      "createdAt", "updatedAt"
                    ) VALUES (
                      gen_random_uuid()::text, ?, ?, ?,
                      ?::"AccountClassification", ?::"NormalBalance", ?::"ReportSection", ?::"CashFlowClass",
                      ?, ?, ?, ?, ?, true, true, ?, ?, NOW(), NOW()
                    )
                    ON CONFLICT ("key") DO UPDATE SET
                      "name" = EXCLUDED."name",
                      "description" = EXCLUDED."description",
                      "classification" = EXCLUDED."classification",
                      "normalBalance" = EXCLUDED."normalBalance",
                      "reportSection" = EXCLUDED."reportSection",
                      "cashFlowClass" = EXCLUDED."cashFlowClass",
                      "isContra" = EXCLUDED."isContra",
                      "isCashEquivalent" = EXCLUDED."isCashEquivalent",
                      "allowReconciliation" = EXCLUDED."allowReconciliation",
                      "allowManualPosting" = EXCLUDED."allowManualPosting",
                      "allowBudgeting" = EXCLUDED."allowBudgeting",
                      "sortOrder" = EXCLUDED."sortOrder",
                      "systemVersion" = EXCLUDED."systemVersion",
                      "isSystem" = true,
                      "updatedAt" = NOW()
                    WHERE "AccountCategory"."systemVersion" < EXCLUDED."systemVersion"
                    """)
                    .params(c.key(), c.name(), c.description(), c.classification(), c.normalBalance(),
                            c.reportSection(), c.cashFlowClass(), c.isContra(), c.isCashEquivalent(),
                            c.allowReconciliation(), c.allowManualPosting(), c.allowBudgeting(), c.sortOrder(),
                            c.systemVersion())
                    .update();
        }
        return AccountingCatalogs.CATEGORIES.size();
    }

    /** Seed (or repair) the chart of accounts, hierarchy, journals and mappings for one organization. */
    public static Map<String, String> seedAccountingCore(JdbcClient jdbc, String orgId) {
        Map<String, String> categoryIds = new HashMap<>();
        jdbc.sql("SELECT id, key FROM \"AccountCategory\"").query((rs, n) -> categoryIds.put(rs.getString("key"),
                rs.getString("id"))).list();
        if (categoryIds.isEmpty()) {
            seedAccountCategories(jdbc);
            jdbc.sql("SELECT id, key FROM \"AccountCategory\"").query((rs, n) -> categoryIds.put(rs.getString("key"),
                    rs.getString("id"))).list();
        }

        Map<String, String> accountIds = new HashMap<>();
        for (CoaAccount a : AccountingCatalogs.COA_ACCOUNTS) {
            String categoryId = a.categoryKey() == null ? null : categoryIds.get(a.categoryKey());
            if (a.categoryKey() != null && categoryId == null) {
                throw new IllegalStateException("COA_TEMPLATE account '" + a.code() + "' references unknown category '"
                        + a.categoryKey() + "'");
            }
            CategoryDef category = a.categoryKey() == null ? null : AccountingCatalogs.CATEGORY_BY_KEY.get(a.categoryKey());
            String id = jdbc.sql("""
                    INSERT INTO "Account" (id, "organizationId", code, name, "categoryId", "normalBalance", "isPostable",
                      "controlAccountType", "sortOrder", "cashFlowCategory", "isDefault", "bankName", "accountNumber",
                      "updatedAt")
                    VALUES (gen_random_uuid()::text, ?, ?, ?, ?, ?::"NormalBalance", ?, ?::"ControlAccountType", ?, ?, ?,
                      ?, ?, now())
                    ON CONFLICT ("organizationId", code) DO UPDATE SET name = EXCLUDED.name,
                      "categoryId" = EXCLUDED."categoryId", "normalBalance" = EXCLUDED."normalBalance",
                      "isPostable" = EXCLUDED."isPostable", "controlAccountType" = EXCLUDED."controlAccountType",
                      "sortOrder" = EXCLUDED."sortOrder", "cashFlowCategory" = EXCLUDED."cashFlowCategory",
                      "isDefault" = EXCLUDED."isDefault", "bankName" = EXCLUDED."bankName",
                      "accountNumber" = EXCLUDED."accountNumber", "updatedAt" = now()
                    RETURNING id""")
                    .params(orgId, a.code(), a.name(), categoryId,
                            category == null ? "debit" : category.normalBalance(),
                            a.isPostable() == null || a.isPostable(), a.controlAccountType(),
                            a.sortOrder() == null ? 0 : a.sortOrder(), a.cashFlowCategory(),
                            a.isDefault() != null && a.isDefault(), a.bankName(), a.accountNumber())
                    .query(String.class).single();
            accountIds.put(a.code(), id);
        }

        for (CoaAccount a : AccountingCatalogs.COA_ACCOUNTS) {
            if (a.parentCode() == null) {
                continue;
            }
            String parentId = accountIds.get(a.parentCode());
            if (parentId == null) {
                throw new IllegalStateException("COA_TEMPLATE account '" + a.code() + "' references unknown parent '"
                        + a.parentCode() + "'");
            }
            jdbc.sql("UPDATE \"Account\" SET \"parentAccountId\" = ?, \"updatedAt\" = now() WHERE id = ?")
                    .params(parentId, accountIds.get(a.code())).update();
        }

        for (CoaJournal j : AccountingCatalogs.COA_JOURNALS) {
            String debit = j.defaultDebitCode() == null ? null : accountIds.get(j.defaultDebitCode());
            jdbc.sql("""
                    INSERT INTO "Journal" (id, "organizationId", code, name, "journalType", "defaultDebitAccountId",
                      "updatedAt")
                    VALUES (gen_random_uuid()::text, ?, ?, ?, ?::"JournalType", ?, now())
                    ON CONFLICT ("organizationId", code) DO UPDATE SET name = EXCLUDED.name,
                      "journalType" = EXCLUDED."journalType",
                      "defaultDebitAccountId" = COALESCE(EXCLUDED."defaultDebitAccountId", "Journal"."defaultDebitAccountId"),
                      "updatedAt" = now()""")
                    .params(orgId, j.code(), j.name(), j.journalType(), debit)
                    .update();
        }

        Set<String> mapped = new LinkedHashSet<>();
        for (Map.Entry<String, String> m : AccountingCatalogs.COA_MAPPINGS.entrySet()) {
            String accountId = accountIds.get(m.getValue());
            if (accountId == null) {
                throw new IllegalStateException("COA_MAPPINGS['" + m.getKey() + "'] references unknown code '"
                        + m.getValue() + "'");
            }
            jdbc.sql("""
                    INSERT INTO "AccountMapping" (id, "organizationId", key, "accountId", "updatedAt")
                    VALUES (gen_random_uuid()::text, ?, ?, ?, now())
                    ON CONFLICT ("organizationId", key) DO UPDATE SET "accountId" = EXCLUDED."accountId",
                      "updatedAt" = now()""")
                    .params(orgId, m.getKey(), accountId).update();
            mapped.add(accountId);
        }

        jdbc.sql("UPDATE \"Account\" SET \"isSystem\" = true, \"updatedAt\" = now() WHERE \"organizationId\" = ?"
                        + " AND id IN (:ids)".replace(":ids", String.join(",", mapped.stream().map(x -> "?").toList())))
                .params(concat(orgId, mapped)).update();
        jdbc.sql("UPDATE \"Account\" SET \"isSystem\" = true, \"updatedAt\" = now() WHERE \"organizationId\" = ?"
                        + " AND \"isPostable\" = false")
                .param(orgId).update();
        return accountIds;
    }

    private static Object[] concat(String first, Set<String> rest) {
        Object[] out = new Object[rest.size() + 1];
        out[0] = first;
        int i = 1;
        for (String r : rest) {
            out[i++] = r;
        }
        return out;
    }
}
