package com.inventoryerp.documents.application;

import com.inventoryerp.kernel.security.PermissionService;
import com.inventoryerp.kernel.seed.SeedContributor;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * DMS boot seeder (port of {@code dms.seeder.ts} + {@code dms.seed-registry.ts}): validates and upserts the global
 * registry — lifecycles, transitions, relation types, document types, v1 templates, permission catalog — on every
 * boot, appends the DMS keys to every system role, then refreshes the type resolver. Data:
 * {@code classpath:dms/registry.json}, exported from the Nest seed by {@code tools/dms-registry/export.mjs}.
 * Also a {@link SeedContributor} so roles created by the demo seed get the keys.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class DmsRegistrySeeder implements ApplicationRunner, SeedContributor {

    private static final Logger log = LoggerFactory.getLogger(DmsRegistrySeeder.class);
    private static final String NOW = DmsStore.NOW;

    private final JdbcClient jdbc;
    private final JsonMapper mapper;
    private final DmsTypeResolver resolver;
    private final PermissionService permissions;
    private final TransactionTemplate tx;

    public DmsRegistrySeeder(JdbcClient jdbc, JsonMapper mapper, DmsTypeResolver resolver,
            PermissionService permissions, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.resolver = resolver;
        this.permissions = permissions;
        this.tx = new TransactionTemplate(txm);
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, Object> registry = load();
        int[] counts = tx.execute(s -> upsertRegistry(registry));
        int roles = tx.execute(s -> wireRoleKeys(registry));
        permissions.evictAll();
        resolver.refresh();
        log.info("DMS registry ready: {} lifecycles, {} transitions, {} types, {} relation types, {} permission keys;"
                + " {} system role(s) provisioned", counts[0], counts[1], counts[2], counts[3], counts[4], roles);
    }

    /** Demo seed ({@code prisma/seed.ts}): registry + DMS keys on the freshly created system roles. */
    @Override
    public int order() {
        return 900;
    }

    @Override
    public void seed(String organizationId) {
        Map<String, Object> registry = load();
        upsertRegistry(registry);
        wireRoleKeys(registry);
        permissions.evictAll();
        resolver.refresh();
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> load() {
        try (InputStream in = new ClassPathResource("dms/registry.json").getInputStream()) {
            return mapper.readValue(in, Map.class);
        } catch (IOException e) {
            throw new IllegalStateException("DMS registry seed missing: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    /** Pure lifecycle validation (initial state, endpoints, duplicates, guard vocabulary); returns transitions. */
    @SuppressWarnings("unchecked")
    public static int validateLifecycleSeeds(List<Map<String, Object>> lifecycles) {
        int transitions = 0;
        for (Map<String, Object> lc : lifecycles) {
            String code = (String) lc.get("code");
            List<String> states = (List<String>) lc.get("states");
            if (!states.contains(lc.get("initialState"))) {
                throw new IllegalStateException("DMS lifecycle " + code + ": initialState '" + lc.get("initialState")
                        + "' not in states");
            }
            Set<String> seen = new HashSet<>();
            for (Map<String, Object> t : list(lc, "transitions")) {
                String from = (String) t.get("from");
                String to = (String) t.get("to");
                String action = (String) t.get("action");
                if (!seen.add(from + "." + action)) {
                    throw new IllegalStateException("DMS lifecycle " + code + ": duplicate transition " + from + "."
                            + action);
                }
                if (!states.contains(from)) {
                    throw new IllegalStateException("DMS lifecycle " + code + ": fromState '" + from + "' not in states");
                }
                if (!states.contains(to)) {
                    throw new IllegalStateException("DMS lifecycle " + code + ": toState '" + to + "' not in states");
                }
                validateGuard(t.get("guard"), code + "." + from + "." + action);
                transitions++;
            }
        }
        return transitions;
    }

    /** {@code validateGuardJson}: object of vocabulary keys; booleans except {@code permission} (string / null). */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> validateGuard(Object guard, String context) {
        if (guard == null) {
            return Map.of();
        }
        if (!(guard instanceof Map<?, ?> g)) {
            throw new IllegalStateException("DMS seed guard must be an object (" + context + ")");
        }
        List<String> unknown = new ArrayList<>();
        for (Object k : g.keySet()) {
            if (!DmsRules.GUARD_VOCABULARY.contains(k.toString())) {
                unknown.add(k.toString());
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalStateException("DMS seed guard " + context + " uses unknown keys [" + String.join(", ", unknown)
                    + "] — vocabulary is [" + String.join(", ", DmsRules.GUARD_VOCABULARY)
                    + "] (G4: declarative guards only)");
        }
        for (Map.Entry<?, ?> e : g.entrySet()) {
            String k = e.getKey().toString();
            if (!(e.getValue() instanceof Boolean) && !k.equals("permission")) {
                throw new IllegalStateException("DMS seed guard " + context + "." + k + " must be boolean");
            }
            if (k.equals("permission") && e.getValue() != null && !(e.getValue() instanceof String)) {
                throw new IllegalStateException("DMS seed guard " + context + ".permission must be a string or null");
            }
        }
        return (Map<String, Object>) g;
    }

    private String json(Object v) {
        return v == null ? null : mapper.writeValueAsString(v);
    }

    @SuppressWarnings("unchecked")
    int[] upsertRegistry(Map<String, Object> registry) {
        List<Map<String, Object>> lifecycles = list(registry, "lifecycles");
        int transitionCount = validateLifecycleSeeds(lifecycles);
        List<String> lifecycleCodes = lifecycles.stream().map(l -> (String) l.get("code")).toList();
        for (Map<String, Object> t : list(registry, "types")) {
            if (!lifecycleCodes.contains(t.get("lifecycle"))) {
                throw new IllegalStateException("DMS type " + t.get("code") + ": unknown lifecycle '" + t.get("lifecycle")
                        + "'");
            }
        }
        Map<String, String> lifecycleIds = new HashMap<>();
        for (Map<String, Object> lc : lifecycles) {
            String id = jdbc.sql("""
                            INSERT INTO "DocumentLifecycle" (id, code, name, description, states, "initialState", "isSystem",
                                "updatedAt") VALUES (?, ?, ?, ?, ?, ?, true, %s)
                            ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description,
                                states = EXCLUDED.states, "initialState" = EXCLUDED."initialState", "isSystem" = true,
                                "updatedAt" = EXCLUDED."updatedAt"
                            RETURNING id""".formatted(NOW))
                    .params(UUID.randomUUID().toString(), lc.get("code"), lc.get("name"), lc.get("description"),
                            ((List<String>) lc.get("states")).toArray(String[]::new), lc.get("initialState"))
                    .query(String.class).single();
            lifecycleIds.put((String) lc.get("code"), id);
        }
        for (Map<String, Object> lc : lifecycles) {
            String lifecycleId = lifecycleIds.get((String) lc.get("code"));
            for (Map<String, Object> t : list(lc, "transitions")) {
                Map<String, Object> guard = validateGuard(t.get("guard"), lc.get("code") + "." + t.get("from") + "."
                        + t.get("action"));
                jdbc.sql("""
                                INSERT INTO "DocumentTransition" (id, "lifecycleId", "fromState", "toState", action, "guardJson",
                                    "isSystem") VALUES (?, ?, ?, ?, ?, ?::jsonb, true)
                                ON CONFLICT ("lifecycleId", "fromState", action) DO UPDATE SET "toState" = EXCLUDED."toState",
                                    "guardJson" = EXCLUDED."guardJson\"""")
                        .params(UUID.randomUUID().toString(), lifecycleId, t.get("from"), t.get("to"), t.get("action"),
                                json(guard))
                        .update();
            }
        }
        int relationTypes = 0;
        for (Map<String, Object> r : list(registry, "relationTypes")) {
            jdbc.sql("""
                            INSERT INTO "DocumentRelationType" (id, code, name, description, direction,
                                "allowedSourceCategories", "allowedTargetCategories", cardinality, "inverseImplicit",
                                "duplicatesAllowed", "isSystem", "updatedAt") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, %s)
                            ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description,
                                direction = EXCLUDED.direction, "allowedSourceCategories" = EXCLUDED."allowedSourceCategories",
                                "allowedTargetCategories" = EXCLUDED."allowedTargetCategories",
                                cardinality = EXCLUDED.cardinality, "inverseImplicit" = EXCLUDED."inverseImplicit",
                                "duplicatesAllowed" = EXCLUDED."duplicatesAllowed", "isSystem" = true,
                                "updatedAt" = EXCLUDED."updatedAt\"""".formatted(NOW))
                    .params(UUID.randomUUID().toString(), r.get("code"), r.get("name"), r.get("description"),
                            r.getOrDefault("direction", "out"), strings(r.get("allowedSourceCategories")),
                            strings(r.get("allowedTargetCategories")), r.getOrDefault("cardinality", "many"),
                            Boolean.TRUE.equals(r.get("inverseImplicit")), Boolean.TRUE.equals(r.get("duplicatesAllowed")))
                    .update();
            relationTypes++;
        }
        int types = 0;
        Map<String, String> typeIds = new HashMap<>();
        for (Map<String, Object> t : list(registry, "types")) {
            Object padding = t.get("numberingPadding");
            String id = jdbc.sql("""
                            INSERT INTO "DocumentTypeDef" (id, code, name, description, category, "isSystem", "numberingKey",
                                "numberingPrefix", "numberingPadding", "lifecycleId", "approvalPolicy", "postingPolicy",
                                "paymentPolicy", "cancellationPolicy", "editPolicy", "snapshotPolicy", "printProfile",
                                "businessEffects", security, "isFinancial", "isInventoryRelevant", "requiresPosting",
                                "updatedAt")
                            VALUES (?, ?, ?, ?, ?, true, ?, ?, ?, ?, ?::jsonb, ?::jsonb, NULL, ?::jsonb, ?, ?::jsonb, ?::jsonb,
                                ?::jsonb, ?::jsonb, ?, ?, ?, %s)
                            ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description,
                                category = EXCLUDED.category, "isSystem" = true, "numberingKey" = EXCLUDED."numberingKey",
                                "numberingPrefix" = EXCLUDED."numberingPrefix",
                                "numberingPadding" = EXCLUDED."numberingPadding", "lifecycleId" = EXCLUDED."lifecycleId",
                                "approvalPolicy" = COALESCE(EXCLUDED."approvalPolicy", "DocumentTypeDef"."approvalPolicy"),
                                "postingPolicy" = COALESCE(EXCLUDED."postingPolicy", "DocumentTypeDef"."postingPolicy"),
                                "cancellationPolicy" = COALESCE(EXCLUDED."cancellationPolicy",
                                    "DocumentTypeDef"."cancellationPolicy"),
                                "editPolicy" = EXCLUDED."editPolicy",
                                "snapshotPolicy" = COALESCE(EXCLUDED."snapshotPolicy", "DocumentTypeDef"."snapshotPolicy"),
                                "printProfile" = COALESCE(EXCLUDED."printProfile", "DocumentTypeDef"."printProfile"),
                                "businessEffects" = COALESCE(EXCLUDED."businessEffects", "DocumentTypeDef"."businessEffects"),
                                security = COALESCE(EXCLUDED.security, "DocumentTypeDef".security),
                                "isFinancial" = EXCLUDED."isFinancial", "isInventoryRelevant" = EXCLUDED."isInventoryRelevant",
                                "requiresPosting" = EXCLUDED."requiresPosting", "updatedAt" = EXCLUDED."updatedAt"
                            RETURNING id""".formatted(NOW))
                    .params(UUID.randomUUID().toString(), t.get("code"), t.get("name"), t.get("description"),
                            t.get("category"), t.get("numberingKey"), t.get("numberingPrefix"),
                            padding == null ? 6 : ((Number) padding).intValue(), lifecycleIds.get((String) t.get("lifecycle")),
                            json(t.get("approvalPolicy")), json(t.get("postingPolicy")), json(t.get("cancellationPolicy")),
                            t.getOrDefault("editPolicy", "EDITABLE"), json(t.get("snapshotPolicy")),
                            json(t.get("printProfile")), json(t.get("businessEffects")), json(t.get("security")),
                            Boolean.TRUE.equals(t.get("isFinancial")), Boolean.TRUE.equals(t.get("isInventoryRelevant")),
                            Boolean.TRUE.equals(t.get("requiresPosting")))
                    .query(String.class).single();
            typeIds.put((String) t.get("code"), id);
            types++;
        }
        for (Map<String, Object> te : list(registry, "templates")) {
            String typeId = typeIds.get((String) te.get("typeCode"));
            if (typeId == null) {
                continue;
            }
            int n = jdbc.sql("UPDATE \"TemplateDefinition\" SET name = ?, format = ?, engine = ?, content = ?, \"isActive\" ="
                            + " true, \"updatedAt\" = " + NOW + " WHERE \"templateKey\" = ? AND version = 1")
                    .params(te.get("name"), te.get("format"), te.get("engine"), te.get("content"), te.get("templateKey"))
                    .update();
            if (n == 0) {
                jdbc.sql("INSERT INTO \"TemplateDefinition\" (id, \"templateKey\", \"documentTypeId\", format, engine, version,"
                                + " name, content, \"isActive\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, 1, ?, ?, true, " + NOW + ")")
                        .params(UUID.randomUUID().toString(), te.get("templateKey"), typeId, te.get("format"),
                                te.get("engine"), te.get("name"), te.get("content"))
                        .update();
            }
        }
        int permissions = 0;
        for (Map<String, Object> p : list(registry, "permissions")) {
            jdbc.sql("INSERT INTO \"Permission\" (id, key, resource, action, description) VALUES (?, ?, ?, ?, ?)"
                            + " ON CONFLICT (key) DO NOTHING")
                    .params(UUID.randomUUID().toString(), p.get("key"), p.get("resource"), p.get("action"),
                            p.get("description"))
                    .update();
            permissions++;
        }
        return new int[] {lifecycles.size(), transitionCount, types, relationTypes, permissions};
    }

    private static String[] strings(Object v) {
        if (v instanceof List<?> l) {
            return l.stream().map(Object::toString).toArray(String[]::new);
        }
        return new String[0];
    }

    /** Append every DMS key to every system role (idempotent). */
    int wireRoleKeys(Map<String, Object> registry) {
        List<String> keys = list(registry, "permissions").stream().map(p -> (String) p.get("key")).toList();
        record Role(String id, String[] permissions) {}
        List<Role> roles = jdbc.sql("SELECT id, permissions FROM \"Role\" WHERE \"isSystem\" = true")
                .query((rs, n) -> new Role(rs.getString("id"), rs.getArray("permissions") == null ? new String[0]
                        : (String[]) rs.getArray("permissions").getArray()))
                .list();
        int updated = 0;
        for (Role role : roles) {
            Set<String> merged = new LinkedHashSet<>(Arrays.asList(role.permissions()));
            int before = merged.size();
            merged.addAll(keys);
            if (merged.size() != before) {
                jdbc.sql("UPDATE \"Role\" SET permissions = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                        .params(merged.toArray(String[]::new), role.id()).update();
                updated++;
            }
        }
        return updated;
    }
}
