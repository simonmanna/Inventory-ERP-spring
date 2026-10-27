package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.application.AccountClassification.RollupNode;
import com.inventoryerp.accounting.domain.Account;
import com.inventoryerp.accounting.domain.AccountCategory;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.persistence.DbEnum;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chart of accounts (port of {@code account.service.ts}): category-driven well-formedness rules (postable ⇔
 * categorized, unique code, no code/kind change once posted to, group/leaf invariants, acyclic parents within
 * one classification, report-section overrides on the same statement side), the account forest with balance
 * roll-ups, drag reorder, deprecate and guarded delete.
 */
@Service
public class AccountService extends BaseCrudService<Account> {

    private static final int MAX_TREE_DEPTH = 32;

    private final JdbcClient jdbc;
    private final AccountResolverService resolver;

    public AccountService(EntityManager em, JdbcClient jdbc, AccountResolverService resolver) {
        super(em, Account.class);
        this.jdbc = jdbc;
        this.resolver = resolver;
    }

    @Override
    protected String entityName() {
        return "Account";
    }

    @Override
    protected List<String> searchFields() {
        return List.of("code", "name");
    }

    @Override
    protected List<Sort> defaultOrderBy() {
        return List.of(Sort.asc("sortOrder"), Sort.asc("code"));
    }

    @Override
    protected List<Map<String, Object>> render(List<Account> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Account a : rows) {
            Map<String, Object> m = EntityJson.of(a);
            AccountCategory c = a.getCategoryId() == null ? null : em.find(AccountCategory.class, a.getCategoryId());
            if (c == null) {
                m.put("category", null);
            } else {
                Map<String, Object> cat = new LinkedHashMap<>();
                cat.put("id", c.getId());
                cat.put("key", c.getKey());
                cat.put("name", c.getName());
                cat.put("classification", c.getClassification());
                cat.put("normalBalance", c.getNormalBalance());
                cat.put("reportSection", c.getReportSection());
                cat.put("cashFlowClass", c.getCashFlowClass());
                cat.put("isContra", c.getIsContra());
                cat.put("isCashEquivalent", c.getIsCashEquivalent());
                cat.put("allowReconciliation", c.getAllowReconciliation());
                cat.put("allowManualPosting", c.getAllowManualPosting());
                cat.put("allowBudgeting", c.getAllowBudgeting());
                m.put("category", cat);
            }
            Account p = a.getParentAccountId() == null ? null : em.find(Account.class, a.getParentAccountId());
            if (p == null) {
                m.put("parent", null);
            } else {
                Map<String, Object> parent = new LinkedHashMap<>();
                parent.put("id", p.getId());
                parent.put("code", p.getCode());
                parent.put("name", p.getName());
                m.put("parent", parent);
            }
            out.add(m);
        }
        return out;
    }


    @Override
    @Transactional
    public Map<String, Object> create(Record dto) {
        Map<String, Object> data = EntityPatch.data(dto);
        Map<String, Object> derived = validate(data, null);
        Account row = EntityPatch.apply(newInstance(), data);
        EntityPatch.apply(row, derived);
        row.setOrganizationId(TenantContext.organizationId());
        persist(row);
        resolver.invalidate(TenantContext.organizationId());
        return EntityJson.of(row);
    }

    @Override
    @Transactional
    public Map<String, Object> update(String id, Record dto) {
        return update(id, EntityPatch.data(dto));
    }

    private Map<String, Object> update(String id, Map<String, Object> data) {
        Account existing = find(id);
        if (existing == null) {
            throw ApiException.notFound("Account " + id + " not found");
        }
        Map<String, Object> derived = validate(data, existing);
        EntityPatch.apply(existing, data);
        EntityPatch.apply(existing, derived);
        em.flush();
        resolver.invalidate(TenantContext.organizationId());
        return render(existing);
    }

    private static String emptyToNull(Object v) {
        return v == null || "".equals(v) ? null : String.valueOf(v);
    }

    private static String enumValue(Object v) {
        return v == null ? null : v instanceof DbEnum d ? d.value() : String.valueOf(v);
    }

    /** Everything that must hold for an account to be well-formed; returns the service-owned fields. */
    private Map<String, Object> validate(Map<String, Object> data, Account existing) {
        String organizationId = TenantContext.organizationId();
        String id = existing == null ? null : existing.getId();

        boolean isPostable = data.get("isPostable") != null ? (Boolean) data.get("isPostable")
                : existing != null && existing.getIsPostable() != null ? existing.getIsPostable() : true;

        String categoryId = data.containsKey("categoryId") ? emptyToNull(data.get("categoryId"))
                : existing == null ? null : existing.getCategoryId();
        if (isPostable && categoryId == null) {
            throw ApiException.badRequest("A postable account requires a category. It determines how the accounting"
                    + " engine treats this account.");
        }
        if (!isPostable && categoryId != null) {
            throw ApiException.badRequest("A group account is a structural node in the reporting tree and must not have"
                    + " a category.");
        }
        AccountCategory category = categoryId == null ? null : em.createQuery(
                        "select c from AccountCategory c where c.id = :id", AccountCategory.class)
                .setParameter("id", categoryId).getResultList().stream().findFirst().orElse(null);
        if (categoryId != null && category == null) {
            throw ApiException.badRequest("Account category not found");
        }
        if (category != null && !Boolean.TRUE.equals(category.getIsActive())) {
            throw ApiException.badRequest("Account category is inactive");
        }

        String code = data.get("code") != null ? (String) data.get("code") : existing == null ? null : existing.getCode();
        if (code == null || code.isEmpty()) {
            throw ApiException.badRequest("Account code is required");
        }
        if (existing == null || !code.equals(existing.getCode())) {
            List<String> clash;
            if (id != null) {
                clash = em.createQuery("select a.name from Account a where a.code = :code and a.deletedAt is null"
                                + " and a.id <> :id", String.class)
                        .setParameter("code", code).setParameter("id", id).setMaxResults(1).getResultList();
            } else {
                clash = em.createQuery("select a.name from Account a where a.code = :code and a.deletedAt is null",
                                String.class)
                        .setParameter("code", code).setMaxResults(1).getResultList();
            }
            if (!clash.isEmpty()) {
                throw ApiException.conflict("Account code '" + code + "' is already used by '" + clash.getFirst() + "'");
            }
        }

        long postedLines = id == null ? 0 : jdbc.sql("SELECT count(*) FROM \"JournalLine\" WHERE \"accountId\" = ?"
                + " AND \"organizationId\" = ?").params(id, organizationId).query(Long.class).single();

        if (existing != null && !code.equals(existing.getCode()) && postedLines > 0) {
            throw ApiException.badRequest("Account " + existing.getCode() + " has posted journal entries; its code cannot"
                    + " be changed. Create a new account instead.");
        }

        if (existing != null && !java.util.Objects.equals(categoryId, existing.getCategoryId()) && postedLines > 0) {
            AccountCategory before = existing.getCategoryId() == null ? null
                    : em.find(AccountCategory.class, existing.getCategoryId());
            if (before == null || category == null || before.getClassification() != category.getClassification()
                    || before.getNormalBalance() != category.getNormalBalance()) {
                throw ApiException.badRequest("Account " + existing.getCode() + " has posted journal entries. Moving it from '"
                        + (before == null ? "none" : before.getKey()) + "' to '" + (category == null ? "none" : category.getKey())
                        + "' would restate reported history because it changes the classification or normal balance."
                        + " Create a new account and reclassify future postings instead.");
            }
        }

        boolean existingPostable = existing != null && (existing.getIsPostable() == null || existing.getIsPostable());
        if (existing != null && isPostable != existingPostable) {
            if (!isPostable) {
                if (postedLines > 0) {
                    throw ApiException.badRequest("This account has posted journal entries and cannot become a group"
                            + " account. Group accounts are structural nodes and cannot be posted to.");
                }
            } else {
                long childCount = em.createQuery("select count(a) from Account a where a.parentAccountId = :id"
                        + " and a.deletedAt is null", Long.class).setParameter("id", id).getSingleResult();
                if (childCount > 0) {
                    throw ApiException.badRequest("This account has child accounts, so it must remain a group account.");
                }
            }
        }

        String parentAccountId = data.containsKey("parentAccountId") ? emptyToNull(data.get("parentAccountId"))
                : existing == null ? null : existing.getParentAccountId();
        if (parentAccountId != null) {
            if (id != null && parentAccountId.equals(id)) {
                throw ApiException.badRequest("An account cannot be its own parent.");
            }
            Account parent = find(parentAccountId);
            if (parent == null || !organizationId.equals(parent.getOrganizationId())) {
                throw ApiException.badRequest("Parent account not found in this organization.");
            }
            if (parent.getIsPostable() == null || parent.getIsPostable()) {
                throw ApiException.badRequest("'" + parent.getCode() + " " + parent.getName() + "' is a postable account,"
                        + " so it cannot have children. Only group accounts can.");
            }
            AccountCategory parentCategory = parent.getCategoryId() == null ? null
                    : em.find(AccountCategory.class, parent.getCategoryId());
            if (parentCategory != null && category != null
                    && parentCategory.getClassification() != category.getClassification()) {
                throw ApiException.badRequest("Cannot file a " + enumValue(category.getClassification())
                        + " account under '" + parent.getCode() + " " + parent.getName() + "', which is "
                        + enumValue(parentCategory.getClassification())
                        + ". The hierarchy must stay within one classification.");
            }
            if (id != null) {
                assertNoCycle(id, parentAccountId);
            }
        }

        String reportSection = data.containsKey("reportSection") ? emptyToNull(enumValue(data.get("reportSection")))
                : existing == null ? null : enumValue(existing.getReportSection());
        if (reportSection != null && category != null) {
            String target = AccountClassification.balanceSheetSideOf(reportSection);
            String natural = AccountClassification.balanceSheetSideOf(enumValue(category.getReportSection()));
            if (!java.util.Objects.equals(target, natural)) {
                throw ApiException.badRequest("Report section '" + reportSection + "' is not compatible with a "
                        + category.getKey() + " account, which reports under '" + enumValue(category.getReportSection())
                        + "'. An override cannot move an account between the balance sheet and the profit & loss statement.");
            }
        }

        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("categoryId", categoryId);
        if (category != null) {
            derived.put("normalBalance", category.getNormalBalance());
        }
        Object cashFlow = data.get("cashFlowCategory") != null ? data.get("cashFlowCategory")
                : existing != null && existing.getCashFlowCategory() != null ? existing.getCashFlowCategory()
                : category != null ? enumValue(category.getCashFlowClass()) : null;
        derived.put("cashFlowCategory", cashFlow);
        return derived;
    }

    private void assertNoCycle(String id, String parentId) {
        String cursor = parentId;
        for (int depth = 0; cursor != null && depth < MAX_TREE_DEPTH; depth++) {
            if (cursor.equals(id)) {
                throw ApiException.badRequest("That parent is a descendant of this account; the change would create a cycle.");
            }
            Account row = find(cursor);
            cursor = row == null ? null : row.getParentAccountId();
        }
        if (cursor != null) {
            throw ApiException.badRequest("Account hierarchy is deeper than " + MAX_TREE_DEPTH
                    + " levels; refusing to reparent.");
        }
    }

    /** The chart of accounts as a forest with optional balance roll-ups. */
    @Transactional(readOnly = true)
    public Map<String, Object> tree(boolean includeInactive, boolean includeBalances, String asOf, String categoryKey,
            String classification) {
        StringBuilder jpql = new StringBuilder("select a from Account a left join a.category c where a.deletedAt is null");
        if (!includeInactive) {
            jpql.append(" and a.isActive = true");
        }
        if (categoryKey != null && !categoryKey.isEmpty()) {
            jpql.append(" and c.key = :categoryKey");
        }
        if (classification != null && !classification.isEmpty()) {
            jpql.append(" and c.classification = :classification");
        }
        jpql.append(" order by a.sortOrder asc, a.code asc");
        var q = em.createQuery(jpql.toString(), Account.class);
        if (categoryKey != null && !categoryKey.isEmpty()) {
            q.setParameter("categoryKey", categoryKey);
        }
        if (classification != null && !classification.isEmpty()) {
            q.setParameter("classification", DbEnum.fromValue(
                    com.inventoryerp.accounting.domain.AccountClassification.class, classification));
        }
        List<Account> accounts = q.getResultList();

        Map<String, BigDecimal[]> balances = new HashMap<>();
        if (includeBalances) {
            Instant at = asOf == null || asOf.isEmpty() ? Instant.now() : EntityPatch.toInstant(asOf);
            jdbc.sql("SELECT l.\"accountId\", sum(l.\"baseDebit\"), sum(l.\"baseCredit\") FROM \"JournalLine\" l"
                            + " JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = ?"
                            + " AND e.status::text IN ('posted', 'reversed') AND e.\"postingDate\" <= ? GROUP BY l.\"accountId\"")
                    .params(TenantContext.organizationId(), FiscalPeriodControl.ts(at))
                    .query((rs, n) -> balances.put(rs.getString(1), new BigDecimal[] {rs.getBigDecimal(2), rs.getBigDecimal(3)}))
                    .list();
        }
        List<RollupNode<Account>> roots = AccountClassification.rollupTree(accounts, Account::getId,
                Account::getParentAccountId, Account::getSortOrder, Account::getCode, a -> {
                    if (a.getIsPostable() != null && !a.getIsPostable()) {
                        return BigDecimal.ZERO;
                    }
                    BigDecimal[] sums = balances.get(a.getId());
                    if (sums == null) {
                        return BigDecimal.ZERO;
                    }
                    BigDecimal net = (sums[0] == null ? BigDecimal.ZERO : sums[0])
                            .subtract(sums[1] == null ? BigDecimal.ZERO : sums[1]);
                    return "credit".equals(enumValue(a.getNormalBalance())) ? net.negate() : net;
                });
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (RollupNode<Account> r : roots) {
            nodes.add(shape(r, includeBalances));
        }
        return Map.of("nodes", nodes);
    }

    private Map<String, Object> shape(RollupNode<Account> n, boolean includeBalances) {
        Account a = n.node;
        AccountCategory c = a.getCategoryId() == null ? null : em.find(AccountCategory.class, a.getCategoryId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("code", a.getCode());
        m.put("name", a.getName());
        m.put("isPostable", a.getIsPostable());
        m.put("isActive", a.getIsActive());
        m.put("deprecatedAt", a.getDeprecatedAt());
        m.put("sortOrder", a.getSortOrder());
        m.put("controlAccountType", a.getControlAccountType());
        m.put("categoryKey", c == null ? null : c.getKey());
        m.put("categoryName", c == null ? null : c.getName());
        m.put("classification", c == null ? null : c.getClassification());
        m.put("normalBalance", a.getNormalBalance());
        m.put("reportSection", a.getReportSection() != null ? a.getReportSection() : c == null ? null : c.getReportSection());
        m.put("level", n.level);
        if (includeBalances) {
            m.put("ownBalance", PostingService.plain(n.ownBalance));
            m.put("subtotal", PostingService.plain(n.subtotal));
        }
        List<Map<String, Object>> children = new ArrayList<>();
        for (RollupNode<Account> child : n.children) {
            children.add(shape(child, includeBalances));
        }
        m.put("children", children);
        return m;
    }

    /** Drag-reorder: bulk set sortOrder and/or parent. */
    @Transactional
    public Map<String, Object> reorder(List<Map<String, Object>> items) {
        for (Map<String, Object> item : items) {
            Map<String, Object> data = new LinkedHashMap<>();
            if (item.get("sortOrder") != null) {
                data.put("sortOrder", item.get("sortOrder"));
            }
            if (item.containsKey("parentAccountId")) {
                data.put("parentAccountId", item.get("parentAccountId"));
            }
            update((String) item.get("id"), data);
        }
        return Map.of("updated", items.size());
    }

    /** Soft-retire: blocks new postings, keeps history. */
    @Transactional
    public Map<String, Object> deprecate(String id, boolean deprecated) {
        Account acct = find(id);
        if (acct == null) {
            throw ApiException.notFound("Account " + id + " not found");
        }
        String mappingKey = mappingKeyOf(id);
        if (deprecated && mappingKey != null) {
            throw ApiException.badRequest("Account is used by account mapping '" + mappingKey
                    + "'. Reassign the mapping first.");
        }
        acct.setDeprecatedAt(deprecated ? Instant.now() : null);
        em.flush();
        resolver.invalidate(TenantContext.organizationId());
        return EntityJson.of(acct);
    }

    /** Guarded delete (system/protected, parent, posted-to and mapped accounts are refused). */
    @Override
    @Transactional
    public void remove(String id) {
        Account acct = find(id);
        if (acct == null) {
            throw ApiException.notFound("Account " + id + " not found");
        }
        if (Boolean.TRUE.equals(acct.getIsSystem()) || Boolean.TRUE.equals(acct.getIsProtected())) {
            throw ApiException.badRequest("This is a protected system account and cannot be deleted. Deactivate or"
                    + " deprecate it instead.");
        }
        long childCount = em.createQuery("select count(a) from Account a where a.parentAccountId = :id", Long.class)
                .setParameter("id", id).getSingleResult();
        if (childCount > 0) {
            throw ApiException.badRequest("Account has child accounts. Reassign or delete them first.");
        }
        long lineCount = jdbc.sql("SELECT count(*) FROM \"JournalLine\" WHERE \"accountId\" = ? AND \"organizationId\" = ?")
                .params(id, TenantContext.organizationId()).query(Long.class).single();
        if (lineCount > 0) {
            throw ApiException.badRequest("Account has journal entries and cannot be deleted. Deprecate it instead so"
                    + " history is preserved.");
        }
        String mappingKey = mappingKeyOf(id);
        if (mappingKey != null) {
            throw ApiException.badRequest("Account is used by account mapping '" + mappingKey
                    + "'. Reassign the mapping first.");
        }
        acct.setDeletedAt(Instant.now());
        em.flush();
        resolver.invalidate(TenantContext.organizationId());
    }

    private String mappingKeyOf(String accountId) {
        return em.createQuery("select m.key from AccountMapping m where m.accountId = :id", String.class)
                .setParameter("id", accountId).setMaxResults(1).getResultList().stream().findFirst().orElse(null);
    }
}
