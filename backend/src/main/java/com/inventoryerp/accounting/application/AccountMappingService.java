package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.domain.AccountMapping;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.EntityJson;
import jakarta.persistence.EntityManager;
import java.util.HashSet;
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

    public AccountMappingService(EntityManager em, AccountResolverService accounts) {
        this.em = em;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return EntityJson.list(em.createQuery("select m from AccountMapping m order by m.key asc", AccountMapping.class)
                .getResultList());
    }

    /** The mapping catalog (key, label, group, expected categories, required, description). */
    public JsonNode registry() {
        return AccountingCatalogs.MAPPINGS_JSON.get("registry");
    }

    /** Required keys with no mapping row (these throw at posting time). */
    @Transactional(readOnly = true)
    public List<String> missingRequired() {
        Set<String> configured = new HashSet<>(em.createQuery("select m.key from AccountMapping m", String.class)
                .getResultList());
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
        em.createNativeQuery("INSERT INTO \"AccountMapping\" (id, \"organizationId\", key, \"accountId\", \"updatedAt\")"
                        + " VALUES (gen_random_uuid()::text, ?1, ?2, ?3, now()) ON CONFLICT (\"organizationId\", key)"
                        + " DO UPDATE SET \"accountId\" = EXCLUDED.\"accountId\", \"updatedAt\" = now()")
                .setParameter(1, organizationId).setParameter(2, key).setParameter(3, accountId).executeUpdate();
        em.clear();
        AccountMapping row = em.createQuery("select m from AccountMapping m where m.organizationId = :org and m.key = :key",
                AccountMapping.class).setParameter("org", organizationId).setParameter("key", key).getSingleResult();
        return EntityJson.of(row);
    }
}
