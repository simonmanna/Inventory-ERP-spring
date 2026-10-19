package com.inventoryerp.kernel.featureflags;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.inventoryerp.kernel.domain.FeatureFlag;
import com.inventoryerp.kernel.domain.OrganizationModule;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.EntityJson;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-tenant feature flags + vertical module enablement (port of {@code feature-flags.service.ts}).
 * Absence of a {@code FeatureFlag} row means OFF. Lookups are cached for 30 seconds per org;
 * {@code ModuleEnabledGuard} consults the module cache on every gated request.
 */
@Service
public class FeatureFlagsService {

    /** Flag state as cached. */
    public record Flag(boolean enabled, Object payload) {}

    private static final Duration TTL = Duration.ofSeconds(30);

    private final EntityManager em;
    private final JdbcClient jdbc;
    private final Cache<String, Flag> cache = Caffeine.newBuilder().expireAfterWrite(TTL).build();
    private final Cache<String, Boolean> moduleCache = Caffeine.newBuilder().expireAfterWrite(TTL).build();
    private final Cache<String, Boolean> moduleConfiguredCache = Caffeine.newBuilder().expireAfterWrite(TTL).build();

    public FeatureFlagsService(EntityManager em, JdbcClient jdbc) {
        this.em = em;
        this.jdbc = jdbc;
    }

    public boolean isEnabled(String key) {
        Flag f = get(key);
        return f != null && f.enabled();
    }

    public void assertEnabled(String key) {
        if (!isEnabled(key)) {
            throw new IllegalStateException("Feature '" + key + "' is not enabled for this organization");
        }
    }

    public Flag get(String key) {
        String orgId = TenantContext.organizationId();
        Flag cached = cache.get(orgId + "::" + key, k -> jdbc.sql("""
                        SELECT enabled, payload::text AS payload FROM "FeatureFlag"
                        WHERE "organizationId" = ? AND key = ?""")
                .params(orgId, key)
                .query((rs, n) -> new Flag(rs.getBoolean("enabled"), EntityJson.parseJson(rs.getString("payload"))))
                .optional()
                .orElse(new Flag(false, null)));
        return cached.payload() == null && !cached.enabled() ? null : cached;
    }

    @Transactional
    public Map<String, Object> set(String key, boolean enabled, Map<String, Object> payload) {
        String orgId = TenantContext.organizationId();
        Map<String, Object> value = payload == null ? Map.of() : payload;
        List<FeatureFlag> rows = em.createQuery(
                        "select f from FeatureFlag f where f.organizationId = :org and f.key = :key", FeatureFlag.class)
                .setParameter("org", orgId)
                .setParameter("key", key)
                .getResultList();
        FeatureFlag row = rows.isEmpty() ? new FeatureFlag() : rows.getFirst();
        if (rows.isEmpty()) {
            row.setOrganizationId(orgId);
            row.setKey(key);
        }
        row.setEnabled(enabled);
        row.setPayload(EntityJson.toJson(value));
        if (rows.isEmpty()) {
            em.persist(row);
        }
        em.flush();
        em.refresh(row);
        return EntityJson.of(row);
    }

    @Transactional
    public void unset(String key) {
        em.createQuery("delete from FeatureFlag f where f.organizationId = :org and f.key = :key")
                .setParameter("org", TenantContext.organizationId())
                .setParameter("key", key)
                .executeUpdate();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return EntityJson.list(em.createQuery(
                        "select f from FeatureFlag f where f.organizationId = :org order by f.key asc", FeatureFlag.class)
                .setParameter("org", TenantContext.organizationId())
                .getResultList());
    }

    // ---- Vertical module enablement ----

    @Transactional
    public Map<String, Object> enableModule(String name, Map<String, Object> config) {
        String orgId = TenantContext.organizationId();
        invalidateModules(orgId);
        String json = EntityJson.toJson(config == null ? Map.of() : config);
        List<OrganizationModule> rows = em.createQuery(
                        "select m from OrganizationModule m where m.organizationId = :org and m.moduleName = :name",
                        OrganizationModule.class)
                .setParameter("org", orgId)
                .setParameter("name", name)
                .getResultList();
        OrganizationModule row;
        if (rows.isEmpty()) {
            row = new OrganizationModule();
            row.setOrganizationId(orgId);
            row.setModuleName(name);
            row.setIsActive(true);
            row.setConfig(json);
            em.persist(row);
        } else {
            row = rows.getFirst();
            row.setIsActive(true);
            row.setDisabledAt(null);
            row.setConfig(json);
        }
        em.flush();
        em.refresh(row);
        return EntityJson.of(row);
    }

    @Transactional
    public void disableModule(String name) {
        String orgId = TenantContext.organizationId();
        invalidateModules(orgId);
        em.createQuery("update OrganizationModule m set m.isActive = false, m.disabledAt = :now"
                        + " where m.organizationId = :org and m.moduleName = :name")
                .setParameter("now", Instant.now())
                .setParameter("org", orgId)
                .setParameter("name", name)
                .executeUpdate();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listModules() {
        return EntityJson.list(em.createQuery(
                        "select m from OrganizationModule m where m.organizationId = :org order by m.moduleName asc",
                        OrganizationModule.class)
                .setParameter("org", TenantContext.organizationId())
                .getResultList());
    }

    public boolean isModuleEnabled(String name) {
        String orgId = TenantContext.organizationId();
        return moduleCache.get(orgId + "::" + name, k -> jdbc.sql("""
                        SELECT "isActive" FROM "OrganizationModule" WHERE "organizationId" = ? AND "moduleName" = ?""")
                .params(orgId, name)
                .query(Boolean.class)
                .optional()
                .orElse(false));
    }

    /** Has this tenant configured module enablement at all? (distinguishes "disabled" from "never set up"). */
    public boolean hasAnyModuleRow() {
        String orgId = TenantContext.organizationId();
        return moduleConfiguredCache.get(orgId, k -> jdbc.sql(
                        "SELECT count(*) FROM \"OrganizationModule\" WHERE \"organizationId\" = ?")
                .param(orgId)
                .query(Long.class)
                .single() > 0);
    }

    private void invalidateModules(String orgId) {
        moduleConfiguredCache.invalidate(orgId);
        moduleCache.asMap().keySet().removeIf(k -> k.startsWith(orgId + "::"));
    }
}
