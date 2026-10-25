package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.domain.Account;
import com.inventoryerp.accounting.domain.AccountCategory;
import com.inventoryerp.kernel.common.ReadMemo;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.persistence.DbEnum;
import com.inventoryerp.shared.web.ApiException;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place an account is looked up (port of {@code account-resolver.service.ts}): mapping keys,
 * category keys, cash equivalents, the literal-code {@link #ensureByCode} path and the postability /
 * mapping-kind gates. Per-organization metadata is cached for 30 s; the global category catalog for the
 * process lifetime.
 */
@Service
public class AccountResolverService {

    /** Account merged with its category behavior. */
    public record AccountMeta(String id, String code, String name, boolean isPostable, boolean isActive,
            Instant deprecatedAt, String parentAccountId, int sortOrder, String controlAccountType, boolean isDefault,
            String categoryId, String categoryKey, String categoryName, String classification, String normalBalance,
            String reportSection, String cashFlowClass, boolean isContra, boolean isCashEquivalent,
            boolean allowReconciliation, boolean allowManualPosting, boolean allowBudgeting) {}

    private record CacheEntry(long at, Map<String, AccountMeta> byId) {}

    private static final long CACHE_TTL_MS = 30_000;

    private final EntityManager em;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private volatile Map<String, AccountCategory> categoriesByKey;

    public AccountResolverService(EntityManager em) {
        this.em = em;
    }

    public void invalidate(String organizationId) {
        if (organizationId != null) {
            cache.remove(organizationId);
        } else {
            cache.clear();
        }
    }

    public void invalidateAll() {
        cache.clear();
    }

    /** Re-read the global category catalog (called after the boot seeder). */
    @Transactional(readOnly = true)
    public void reloadCategories() {
        Map<String, AccountCategory> byKey = new LinkedHashMap<>();
        em.createQuery("select c from AccountCategory c where c.deletedAt is null", AccountCategory.class)
                .getResultList().forEach(c -> byKey.put(c.getKey(), c));
        categoriesByKey = byKey;
        cache.clear();
    }

    @Transactional(readOnly = true)
    public Map<String, AccountCategory> categories() {
        if (categoriesByKey == null) {
            reloadCategories();
        }
        return categoriesByKey;
    }

    public AccountCategory categoryByKey(String key) {
        return categories().get(key);
    }

    // ------------------------------------------------------------------ mapping keys

    /** Resolve an org-level mapping; throws when unconfigured. */
    public String byMapping(String key) {
        String id = byMappingOptional(key);
        if (id == null) {
            throw ApiException.badRequest("Account mapping '" + key
                    + "' is not configured. Set it under Accounting > Account Mapping.");
        }
        return id;
    }

    public String byMappingOptional(String key) {
        String organizationId = TenantContext.organizationId();
        return ReadMemo.get(() -> {
            List<String> rows = em.createQuery("select m.accountId from AccountMapping m where m.key = :key"
                            + " and m.organizationId = :org", String.class)
                    .setParameter("key", key)
                    .setParameter("org", organizationId)
                    .setMaxResults(1)
                    .getResultList();
            return rows.isEmpty() ? null : rows.getFirst();
        }, "accountMapping", organizationId, key);
    }

    // ------------------------------------------------------------------ category keys

    public List<AccountMeta> byCategory(String key, boolean includeGroups) {
        return byCategories(List.of(key), includeGroups);
    }

    public List<AccountMeta> byCategories(List<String> keys, boolean includeGroups) {
        return filtered(m -> m.categoryKey() != null && keys.contains(m.categoryKey()) && m.isActive()
                && m.deprecatedAt() == null && (includeGroups || m.isPostable()));
    }

    public List<AccountMeta> byClassification(String classification, boolean includeGroups) {
        return filtered(m -> classification.equals(m.classification()) && m.isActive() && m.deprecatedAt() == null
                && (includeGroups || m.isPostable()));
    }

    /** First postable account in a category (prefers {@code isDefault}); {@code null} when none. */
    public String oneByCategory(String key) {
        List<AccountMeta> rows = byCategory(key, false);
        if (rows.isEmpty()) {
            return null;
        }
        return rows.stream().filter(AccountMeta::isDefault).findFirst().orElse(rows.getFirst()).id();
    }

    /** Cash, bank, mobile money, petty cash — whatever the categories flag as cash-equivalent. */
    public List<AccountMeta> cashEquivalentAccounts(boolean includeGroups) {
        return filtered(m -> m.isCashEquivalent() && m.isActive() && (includeGroups || m.isPostable()));
    }

    public List<String> cashEquivalentIds() {
        return cashEquivalentAccounts(false).stream().map(AccountMeta::id).toList();
    }

    // ------------------------------------------------------------------ literal codes

    /** Fetch an account by code, creating it (and its mapping) when missing. */
    @Transactional
    public String ensureByCode(String code, String name, String categoryKey, String mappingKey) {
        String organizationId = TenantContext.organizationId();
        if (mappingKey != null) {
            String mapped = byMappingOptional(mappingKey);
            if (mapped != null) {
                return mapped;
            }
        }
        Account account = em.createQuery("select a from Account a where a.code = :code and a.organizationId = :org"
                        + " and a.deletedAt is null", Account.class)
                .setParameter("code", code).setParameter("org", organizationId)
                .setMaxResults(1).getResultList().stream().findFirst().orElse(null);
        if (account == null) {
            AccountCategory category = em.createQuery("select c from AccountCategory c where c.key = :key",
                            AccountCategory.class)
                    .setParameter("key", categoryKey).setMaxResults(1).getResultList().stream().findFirst().orElse(null);
            if (category == null) {
                throw ApiException.badRequest("Account category '" + categoryKey
                        + "' is missing for this organization. The global catalog is seeded by AccountingModule at boot;"
                        + " if it is empty the API started without it. On a database upgraded from a pre-category schema,"
                        + " run deployment/2026-08-r1/phaseB-backfill.ts to map the existing chart of accounts.");
            }
            account = new Account();
            account.setOrganizationId(organizationId);
            account.setCode(code);
            account.setName(name);
            account.setCategoryId(category.getId());
            account.setNormalBalance(category.getNormalBalance());
            account.setIsSystem(true);
            account.setIsProtected(true);
            em.persist(account);
            em.flush();
            invalidate(organizationId);
        }
        if (mappingKey != null) {
            em.createNativeQuery("INSERT INTO \"AccountMapping\" (id, \"organizationId\", key, \"accountId\", \"updatedAt\")"
                            + " VALUES (gen_random_uuid()::text, ?1, ?2, ?3, now())"
                            + " ON CONFLICT (\"organizationId\", key) DO NOTHING")
                    .setParameter(1, organizationId).setParameter(2, mappingKey).setParameter(3, account.getId())
                    .executeUpdate();
            ReadMemo.clear();
        }
        return account.getId();
    }

    // ------------------------------------------------------------------ metadata

    public AccountMeta metaById(String id) {
        AccountMeta meta = allMeta().get(id);
        if (meta == null) {
            throw ApiException.notFound("Account not found");
        }
        return meta;
    }

    public Map<String, AccountMeta> meta(java.util.Collection<String> ids) {
        Map<String, AccountMeta> all = allMeta();
        Map<String, AccountMeta> out = new LinkedHashMap<>();
        for (String id : ids) {
            AccountMeta m = all.get(id);
            if (m != null) {
                out.put(id, m);
            }
        }
        return out;
    }

    /** All live accounts of the current organization with their category behavior merged in. */
    @Transactional(readOnly = true)
    public Map<String, AccountMeta> allMeta() {
        String organizationId = TenantContext.organizationId();
        CacheEntry cached = cache.get(organizationId);
        if (cached != null && System.currentTimeMillis() - cached.at() < CACHE_TTL_MS) {
            return cached.byId();
        }
        List<Account> rows = em.createQuery("select a from Account a left join fetch a.category"
                        + " where a.organizationId = :org and a.deletedAt is null", Account.class)
                .setParameter("org", organizationId).getResultList();
        Map<String, AccountMeta> byId = new LinkedHashMap<>();
        for (Account a : rows) {
            byId.put(a.getId(), toMeta(a, a.getCategory()));
        }
        cache.put(organizationId, new CacheEntry(System.currentTimeMillis(), byId));
        return byId;
    }

    // ------------------------------------------------------------------ validation

    /** Postability gate; {@code systemContext} lets engine-generated entries into control accounts. */
    public static void assertPostable(AccountMeta meta, boolean systemContext) {
        if (!meta.isPostable()) {
            throw ApiException.badRequest("Account " + meta.code() + " is a summary/group account and cannot be posted to");
        }
        if (!meta.isActive()) {
            throw ApiException.badRequest("Account " + meta.code() + " is inactive and cannot be posted to");
        }
        if (meta.deprecatedAt() != null) {
            throw ApiException.badRequest("Account " + meta.code() + " is deprecated and cannot be posted to");
        }
        if (!systemContext && !meta.allowManualPosting()) {
            throw ApiException.badRequest("Account " + meta.code() + " does not allow manual posting ("
                    + (meta.categoryName() == null ? "control account" : meta.categoryName())
                    + "). Post through the originating document instead.");
        }
    }

    /** Validate that {@code accountId} is an acceptable target for {@code mappingKey}. */
    public AccountMeta assertValidForMapping(String mappingKey, String accountId, boolean force) {
        AccountMeta meta = metaById(accountId);
        if (!meta.isPostable()) {
            throw ApiException.badRequest("Group/header accounts cannot be mapped.");
        }
        if (!meta.isActive() || meta.deprecatedAt() != null) {
            throw ApiException.badRequest("Account is inactive or deprecated.");
        }
        AccountingCatalogs.MappingDef def = AccountingCatalogs.MAPPING_BY_KEY.get(mappingKey);
        if (def != null && !def.expectedCategories().isEmpty() && meta.categoryKey() != null
                && !def.expectedCategories().contains(meta.categoryKey()) && !force) {
            throw ApiException.badRequest("Mapping '" + mappingKey + "' expects a "
                    + String.join(" or ", def.expectedCategories()) + " account; '" + meta.code() + " " + meta.name()
                    + "' is " + meta.categoryKey() + ". Pass force=true to override.");
        }
        return meta;
    }

    private List<AccountMeta> filtered(Predicate<AccountMeta> p) {
        List<AccountMeta> out = new ArrayList<>();
        for (AccountMeta m : allMeta().values()) {
            if (p.test(m)) {
                out.add(m);
            }
        }
        out.sort(Comparator.comparingInt(AccountMeta::sortOrder).thenComparing(AccountMeta::code, AccountResolverService::localeCompare));
        return out;
    }

    /** JS {@code a.localeCompare(b)} approximation (case-insensitive first, then case). */
    static int localeCompare(String a, String b) {
        int c = java.text.Collator.getInstance(java.util.Locale.ENGLISH).compare(a, b);
        return Integer.signum(c);
    }

    private static String v(Object e) {
        return e == null ? null : e instanceof DbEnum d ? d.value() : String.valueOf(e);
    }

    /** Merge an Account row and its category into {@link AccountMeta}. */
    public static AccountMeta toMeta(Account a, AccountCategory c) {
        return new AccountMeta(a.getId(), a.getCode(), a.getName(),
                a.getIsPostable() == null || a.getIsPostable(), Boolean.TRUE.equals(a.getIsActive()), a.getDeprecatedAt(),
                a.getParentAccountId(), a.getSortOrder() == null ? 0 : a.getSortOrder(), v(a.getControlAccountType()),
                Boolean.TRUE.equals(a.getIsDefault()), a.getCategoryId(), c == null ? null : c.getKey(),
                c == null ? null : c.getName(), c == null ? null : v(c.getClassification()),
                a.getNormalBalance() != null ? v(a.getNormalBalance()) : c != null ? v(c.getNormalBalance()) : "debit",
                a.getReportSection() != null ? v(a.getReportSection()) : c == null ? null : v(c.getReportSection()),
                a.getCashFlowCategory() != null ? a.getCashFlowCategory()
                        : c != null && c.getCashFlowClass() != null ? v(c.getCashFlowClass()) : "operating",
                c != null && Boolean.TRUE.equals(c.getIsContra()), c != null && Boolean.TRUE.equals(c.getIsCashEquivalent()),
                a.getAllowReconciliation() != null ? a.getAllowReconciliation()
                        : c != null && Boolean.TRUE.equals(c.getAllowReconciliation()),
                a.getAllowManualPosting() != null ? a.getAllowManualPosting()
                        : c == null || c.getAllowManualPosting() == null || c.getAllowManualPosting(),
                a.getAllowBudgeting() != null ? a.getAllowBudgeting() : c != null && Boolean.TRUE.equals(c.getAllowBudgeting()));
    }
}
