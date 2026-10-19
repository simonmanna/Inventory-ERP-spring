package com.inventoryerp.kernel.settings;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.inventoryerp.kernel.settings.SettingRegistry.Definition;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Hierarchical setting resolver (port of {@code setting-resolver.service.ts}): product → category →
 * warehouse → organization → registry default. Values are cached 30 s per (org, key, scope ids) and
 * invalidated per organization on any write.
 */
@Service
public class SettingResolverService {

    /** Resolution context; any subset of ids may be given. */
    public record SettingContext(String productId, String categoryId, String warehouseId) {
        public static final SettingContext EMPTY = new SettingContext(null, null, null);
    }

    /** A resolved value plus where it came from ({@code default} when nothing overrides it). */
    public record Described(Object value, String source, String scopeId) {}

    private record Entry(Object value, long version) {}

    private final JdbcClient jdbc;
    private final JsonMapper mapper;
    private final Cache<String, Entry> cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(30)).build();
    private final Map<String, Long> orgVersion = new ConcurrentHashMap<>();

    public SettingResolverService(JdbcClient jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Drop cached values for an org (all orgs when null). */
    public void invalidate(String organizationId) {
        if (organizationId == null) {
            orgVersion.clear();
            cache.invalidateAll();
            return;
        }
        orgVersion.merge(organizationId, 1L, Long::sum);
    }

    public Object resolve(String key, SettingContext ctx) {
        Definition def = SettingRegistry.get(key);
        if (def == null) {
            throw ApiException.badRequest("Unknown setting '" + key + "'.");
        }
        String orgId = TenantContext.optionalOrganizationId();
        long version = orgId == null ? 0 : orgVersion.getOrDefault(orgId, 0L);
        SettingContext c = ctx == null ? SettingContext.EMPTY : ctx;
        String cacheKey = String.join("::", orgId == null ? "_sys" : orgId, key, nz(c.productId()), nz(c.categoryId()),
                nz(c.warehouseId()));
        Entry hit = cache.getIfPresent(cacheKey);
        if (hit != null && hit.version() == version) {
            return hit.value();
        }
        Object value = compute(def, orgId, c).value();
        cache.put(cacheKey, new Entry(value, version));
        return value;
    }

    public boolean resolveBool(String key, SettingContext ctx) {
        return Boolean.TRUE.equals(resolve(key, ctx));
    }

    public String resolveString(String key, SettingContext ctx) {
        Object v = resolve(key, ctx);
        return v == null ? null : String.valueOf(v);
    }

    public Number resolveNumber(String key, SettingContext ctx) {
        return (Number) resolve(key, ctx);
    }

    /** Uncached, with the source level (admin surfaces). */
    public Described describe(String key, SettingContext ctx) {
        Definition def = SettingRegistry.get(key);
        if (def == null) {
            throw ApiException.badRequest("Unknown setting '" + key + "'.");
        }
        return compute(def, TenantContext.optionalOrganizationId(), ctx == null ? SettingContext.EMPTY : ctx);
    }

    private Described compute(Definition def, String orgId, SettingContext ctx) {
        List<String[]> candidates = candidates(def, ctx);
        if (!candidates.isEmpty()) {
            StringBuilder or = new StringBuilder();
            List<Object> params = new ArrayList<>();
            params.add(def.key());
            for (String[] c : candidates) {
                or.append(or.isEmpty() ? "" : " OR ").append("(\"scopeType\" = ? AND \"scopeId\" = ?)");
                params.add(c[0]);
                params.add(c[1]);
            }
            String orgClause = orgId == null ? "\"organizationId\" IS NULL" : "\"organizationId\" = ?";
            if (orgId != null) {
                params.add(0, orgId);
            }
            Map<String, String> byScope = new HashMap<>();
            jdbc.sql("SELECT \"scopeType\", \"scopeId\", value::text AS value FROM \"Setting\" WHERE " + orgClause
                            + " AND key = ? AND (" + or + ")")
                    .params(params)
                    .query((rs, n) -> byScope.put(rs.getString("scopeType") + ":" + rs.getString("scopeId"),
                            rs.getString("value")))
                    .list();
            for (String[] c : candidates) {
                String found = byScope.get(c[0] + ":" + c[1]);
                if (found != null) {
                    Object value = mapper.readValue(found, Object.class);
                    if (value != null) {
                        return new Described(safeCoerce(def, value), c[0], c[1]);
                    }
                }
            }
        }
        return new Described(def.defaultValue(), "default", null);
    }

    private static List<String[]> candidates(Definition def, SettingContext ctx) {
        Map<String, String> ids = new LinkedHashMap<>();
        ids.put("product", ctx.productId());
        ids.put("category", ctx.categoryId());
        ids.put("warehouse", ctx.warehouseId());
        List<String[]> out = new ArrayList<>();
        for (String level : SettingRegistry.SCOPE_PRECEDENCE) {
            if (!level.equals("organization") && (!def.cascades() || !def.scopeLevels().contains(level))) {
                continue;
            }
            if (level.equals("organization")) {
                out.add(new String[] {"organization", ""});
            } else if (ids.get(level) != null && !ids.get(level).isEmpty()) {
                out.add(new String[] {level, ids.get(level)});
            }
        }
        return out;
    }

    private static Object safeCoerce(Definition def, Object raw) {
        try {
            return SettingRegistry.coerce(def, raw);
        } catch (IllegalArgumentException e) {
            return def.defaultValue();
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
