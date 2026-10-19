package com.inventoryerp.kernel.settings;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.domain.Setting;
import com.inventoryerp.kernel.settings.SettingRegistry.Definition;
import com.inventoryerp.kernel.settings.SettingResolverService.SettingContext;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Settings at organization / warehouse / category / product scope (port of {@code settings.service.ts}).
 * The Setting table has a nullable organizationId and is scoped explicitly here. Registered keys are
 * validated + coerced; unregistered legacy keys are stored as-is, organization level only.
 */
@Service
public class SettingsService {

    static final Map<String, String> INVENTORY_DEFAULT_KEYS = new LinkedHashMap<>();

    static {
        INVENTORY_DEFAULT_KEYS.put("costingMethod", "inventory.defaultCostingMethod");
        INVENTORY_DEFAULT_KEYS.put("pickingStrategy", "inventory.defaultPickingStrategy");
        INVENTORY_DEFAULT_KEYS.put("batchTracking", "inventory.defaultBatchTracking");
        INVENTORY_DEFAULT_KEYS.put("expiryTracking", "inventory.defaultExpiryTracking");
        INVENTORY_DEFAULT_KEYS.put("serialTracking", "inventory.defaultSerialTracking");
    }

    private final EntityManager em;
    private final SettingResolverService resolver;
    private final AuditService audit;
    private final JsonMapper mapper;

    public SettingsService(EntityManager em, SettingResolverService resolver, AuditService audit, JsonMapper mapper) {
        this.em = em;
        this.resolver = resolver;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listForOrganization() {
        return EntityJson.list(em.createQuery("select s from Setting s where s.organizationId = :org"
                        + " and s.scopeType = 'organization' and s.scopeId = '' order by s.key asc", Setting.class)
                .setParameter("org", TenantContext.organizationId())
                .getResultList());
    }

    /** Raw org-level row (legacy accessor); callers read {@code value}. */
    @Transactional(readOnly = true)
    public Object getValue(String key) {
        String orgId = TenantContext.optionalOrganizationId();
        var rows = em.createQuery("select s from Setting s where "
                        + (orgId == null ? "s.organizationId is null" : "s.organizationId = :org")
                        + " and s.scopeType = 'organization' and s.scopeId = '' and s.key = :key", Setting.class)
                .setParameter("key", key)
                .setMaxResults(1);
        if (orgId != null) {
            rows.setParameter("org", orgId);
        }
        var list = rows.getResultList();
        return list.isEmpty() ? null : read(list.getFirst().getValue());
    }

    /** Server-wide value (organizationId NULL), falling back to the newest org-level row. */
    @Transactional(readOnly = true)
    public Object getServerWide(String key) {
        var rows = em.createQuery("select s from Setting s where s.organizationId is null and s.scopeType = 'organization'"
                        + " and s.scopeId = '' and s.key = :key", Setting.class)
                .setParameter("key", key)
                .setMaxResults(1)
                .getResultList();
        if (rows.isEmpty()) {
            rows = em.createQuery("select s from Setting s where s.scopeType = 'organization' and s.scopeId = ''"
                            + " and s.key = :key order by s.updatedAt desc", Setting.class)
                    .setParameter("key", key)
                    .setMaxResults(1)
                    .getResultList();
        }
        return rows.isEmpty() ? null : read(rows.getFirst().getValue());
    }

    @Transactional
    public Map<String, Object> setServerWide(String key, Object value) {
        var rows = em.createQuery("select s from Setting s where s.organizationId is null and s.scopeType = 'organization'"
                        + " and s.scopeId = '' and s.key = :key", Setting.class)
                .setParameter("key", key)
                .getResultList();
        Setting row = rows.isEmpty() ? newRow(null, "organization", "", key) : rows.getFirst();
        row.setValue(EntityJson.toJson(value));
        if (rows.isEmpty()) {
            em.persist(row);
        }
        em.flush();
        Map<String, Object> nv = new LinkedHashMap<>();
        nv.put("key", key);
        nv.put("scopeType", "server");
        nv.put("value", value);
        audit.record(AuditService.AuditInput.of("Setting", row.getId(), "update", null, nv));
        return EntityJson.of(row);
    }

    /** Write a setting at a scope (organization by default). */
    @Transactional
    public Map<String, Object> set(String key, Object value, String scopeTypeOpt, String scopeIdOpt) {
        String organizationId = TenantContext.organizationId();
        String scopeType = scopeTypeOpt == null ? "organization" : scopeTypeOpt;
        String scopeId = "organization".equals(scopeType) ? "" : (scopeIdOpt == null ? "" : scopeIdOpt);
        Definition def = SettingRegistry.get(key);
        Object toStore = value;
        if (def != null) {
            try {
                SettingRegistry.assertScopeAllowed(def, scopeType);
                toStore = SettingRegistry.coerce(def, value);
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest(e.getMessage());
            }
        } else if (!"organization".equals(scopeType)) {
            throw ApiException.badRequest("Unknown setting '" + key + "' cannot be scoped below organization.");
        }
        if (!"organization".equals(scopeType) && scopeId.isEmpty()) {
            throw ApiException.badRequest("A scopeId is required for " + scopeType + "-level settings.");
        }
        var rows = em.createQuery("select s from Setting s where s.organizationId = :org and s.scopeType = :st"
                        + " and s.scopeId = :sid and s.key = :key", Setting.class)
                .setParameter("org", organizationId)
                .setParameter("st", scopeType)
                .setParameter("sid", scopeId)
                .setParameter("key", key)
                .getResultList();
        Setting row = rows.isEmpty() ? newRow(organizationId, scopeType, scopeId, key) : rows.getFirst();
        row.setValue(EntityJson.toJson(toStore));
        if (rows.isEmpty()) {
            em.persist(row);
        }
        em.flush();
        resolver.invalidate(organizationId);
        Map<String, Object> nv = new LinkedHashMap<>();
        nv.put("key", key);
        nv.put("scopeType", scopeType);
        nv.put("scopeId", scopeId);
        nv.put("value", toStore);
        audit.record(AuditService.AuditInput.of("Setting", row.getId(), "update", null, nv));
        return EntityJson.of(row);
    }

    /** Remove an override so the value falls back up the cascade. */
    @Transactional
    public void unset(String key, String scopeTypeOpt, String scopeIdOpt) {
        String organizationId = TenantContext.organizationId();
        String scopeType = scopeTypeOpt == null ? "organization" : scopeTypeOpt;
        String scopeId = "organization".equals(scopeType) ? "" : (scopeIdOpt == null ? "" : scopeIdOpt);
        em.createQuery("delete from Setting s where s.organizationId = :org and s.scopeType = :st and s.scopeId = :sid"
                        + " and s.key = :key")
                .setParameter("org", organizationId)
                .setParameter("st", scopeType)
                .setParameter("sid", scopeId)
                .setParameter("key", key)
                .executeUpdate();
        resolver.invalidate(organizationId);
        Map<String, Object> nv = new LinkedHashMap<>();
        nv.put("key", key);
        nv.put("scopeType", scopeType);
        nv.put("scopeId", scopeId);
        audit.record(AuditService.AuditInput.of("Setting", scopeType + ":" + scopeId + ":" + key, "delete", null, nv));
    }

    /** Effective values of a group at a scope with their source level; deprecated keys hidden unless asked. */
    public List<Map<String, Object>> listEffective(String group, SettingContext ctx, boolean includeDeprecated) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Definition def : SettingRegistry.forGroup(group)) {
            if (def.deprecated() && !includeDeprecated) {
                continue;
            }
            var d = resolver.describe(def.key(), ctx);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", def.key());
            row.put("label", def.label());
            row.put("description", def.description());
            row.put("type", def.type());
            row.put("enumValues", def.enumValues());
            row.put("cascades", def.cascades());
            row.put("scopeLevels", def.scopeLevels());
            row.put("value", d.value());
            row.put("source", d.source());
            row.put("scopeId", d.scopeId());
            out.add(row);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getInventoryDefaults() {
        var rows = em.createQuery("select s from Setting s where s.organizationId = :org and s.scopeType = 'organization'"
                        + " and s.scopeId = '' and s.key in :keys", Setting.class)
                .setParameter("org", TenantContext.organizationId())
                .setParameter("keys", List.copyOf(INVENTORY_DEFAULT_KEYS.values()))
                .getResultList();
        Map<String, Object> byKey = new LinkedHashMap<>();
        rows.forEach(r -> byKey.put(r.getKey(), read(r.getValue())));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("costingMethod", byKey.get("inventory.defaultCostingMethod"));
        out.put("pickingStrategy", byKey.get("inventory.defaultPickingStrategy"));
        out.put("batchTracking", orFalse(byKey.get("inventory.defaultBatchTracking")));
        out.put("expiryTracking", orFalse(byKey.get("inventory.defaultExpiryTracking")));
        out.put("serialTracking", orFalse(byKey.get("inventory.defaultSerialTracking")));
        return out;
    }

    @Transactional
    public Map<String, Object> setInventoryDefaults(Map<String, Object> dto) {
        for (var e : INVENTORY_DEFAULT_KEYS.entrySet()) {
            if (dto.get(e.getKey()) != null) {
                set(e.getValue(), dto.get(e.getKey()), null, null);
            }
        }
        return getInventoryDefaults();
    }

    private static Object orFalse(Object v) {
        return v == null ? false : v;
    }

    private Object read(String json) {
        return json == null ? null : mapper.readValue(json, Object.class);
    }

    private static Setting newRow(String organizationId, String scopeType, String scopeId, String key) {
        Setting row = new Setting();
        row.setOrganizationId(organizationId);
        row.setScope(scopeType);
        row.setScopeType(scopeType);
        row.setScopeId(scopeId);
        row.setKey(key);
        return row;
    }
}
