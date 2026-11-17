package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.domain.Account;
import com.inventoryerp.accounting.domain.AccountMapping;
import com.inventoryerp.accounting.domain.ControlAccountType;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.ReadMemo;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import jakarta.persistence.EntityManager;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** Org-level account determination defaults, key → account (port of {@code account-mapping.service.ts}). */
@Service
public class AccountMappingService {

    private final EntityManager em;
    private final AccountResolverService accounts;
    private final AuditService audit;

    public AccountMappingService(EntityManager em, AccountResolverService accounts, AuditService audit) {
        this.em = em;
        this.accounts = accounts;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return EntityJson.list(em.createQuery("select m from AccountMapping m where m.organizationId = :org"
                        + " order by m.key asc", AccountMapping.class)
                .setParameter("org", TenantContext.organizationId()).getResultList());
    }

    /** The mapping catalog (key, label, group, expected categories, required, description). */
    public JsonNode registry() {
        return AccountingCatalogs.MAPPINGS_JSON.get("registry");
    }

    /** Required keys with no mapping row (these throw at posting time). */
    @Transactional(readOnly = true)
    public List<String> missingRequired() {
        Set<String> configured = new HashSet<>(em.createQuery("select m.key from AccountMapping m"
                        + " where m.organizationId = :org", String.class)
                .setParameter("org", TenantContext.organizationId()).getResultList());
        return AccountingCatalogs.MAPPING_REGISTRY.stream()
                .filter(AccountingCatalogs.MappingDef::required)
                .map(AccountingCatalogs.MappingDef::key)
                .filter(k -> !configured.contains(k))
                .toList();
    }

    /** Point a mapping key at an account (validated against the registry's expected categories). */
    @Transactional
    public Map<String, Object> set(String key, String accountId, boolean force) {
        accounts.assertValidForMapping(key, accountId, force);
        String organizationId = TenantContext.organizationId();
        designateControlAccount(organizationId, key, accountId);
        em.createNativeQuery("INSERT INTO \"AccountMapping\" (id, \"organizationId\", key, \"accountId\", \"updatedAt\")"
                        + " VALUES (gen_random_uuid()::text, ?1, ?2, ?3, now()) ON CONFLICT (\"organizationId\", key)"
                        + " DO UPDATE SET \"accountId\" = EXCLUDED.\"accountId\", \"updatedAt\" = now()")
                .setParameter(1, organizationId).setParameter(2, key).setParameter(3, accountId).executeUpdate();
        ReadMemo.clear();
        em.clear();
        AccountMapping row = em.createQuery("select m from AccountMapping m where m.organizationId = :org and m.key = :key",
                AccountMapping.class).setParameter("org", organizationId).setParameter("key", key).getSingleResult();
        return EntityJson.of(row);
    }

    /**
     * Control-account policy (V518): mapping {@code accounts_receivable} / {@code accounts_payable} to an account is
     * explicit configuration of the org's AR / AP control account, so an account with no designation yet is designated
     * {@code ar} / {@code ap} (audited); an existing designation is never overwritten.
     */
    private void designateControlAccount(String organizationId, String key, String accountId) {
        ControlAccountType type = "accounts_receivable".equals(key) ? ControlAccountType.AR
                : "accounts_payable".equals(key) ? ControlAccountType.AP : null;
        if (type == null) {
            return;
        }
        Account account = em.createQuery("select a from Account a where a.id = :id and a.organizationId = :org",
                        Account.class)
                .setParameter("id", accountId).setParameter("org", organizationId)
                .getResultList().stream().findFirst().orElse(null);
        if (account == null || account.getControlAccountType() == type) {
            return;
        }
        if (account.getControlAccountType() != null) {
            throw ApiException.badRequest("Account " + account.getCode() + " is designated as a "
                    + account.getControlAccountType().value() + " control account; it cannot also be the "
                    + type.value().toUpperCase() + " control account (" + key + ")");
        }
        account.setControlAccountType(type);
        em.flush();
        accounts.invalidate(organizationId);
        Map<String, Object> oldValues = new LinkedHashMap<>();
        oldValues.put("code", account.getCode());
        oldValues.put("controlAccountType", null);
        Map<String, Object> newValues = new LinkedHashMap<>();
        newValues.put("code", account.getCode());
        newValues.put("controlAccountType", type.value());
        newValues.put("mappingKey", key);
        audit.recordInTx(AuditInput.of("Account", account.getId(), "update", oldValues, newValues));
    }
}
