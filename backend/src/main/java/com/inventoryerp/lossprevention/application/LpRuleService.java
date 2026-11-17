package com.inventoryerp.lossprevention.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.lossprevention.application.LpCommands.RuleInput;
import com.inventoryerp.lossprevention.application.LpEngine.RuleConfig;
import com.inventoryerp.lossprevention.application.LpRuleCatalog.Definition;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Rules per organization: the catalog defaults until changed, and every later state kept as a version. */
@Service
public class LpRuleService {

    static final List<String> SEVERITIES = List.of("info", "warning", "critical");

    private final JdbcClient jdbc;
    private final LpScope scope;

    public LpRuleService(JdbcClient jdbc, LpScope scope) {
        this.jdbc = jdbc;
        this.scope = scope;
    }

    private Map<String, Map<String, Object>> rows() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT * FROM \"LpRule\" WHERE \"organizationId\" = ?")
                .param(LpScope.org()))) {
            out.put((String) r.get("code"), r);
        }
        return out;
    }

    private static RuleConfig config(Definition def, Map<String, Object> row) {
        if (row == null) {
            return new RuleConfig(def.code(), 0, def.enabled(), def.severity(), def.params());
        }
        Map<String, Object> params = new LinkedHashMap<>(def.params());
        params.putAll(LpJson.map(row.get("params")));
        return new RuleConfig(def.code(), ((Number) row.get("version")).intValue(), (Boolean) row.get("enabled"),
                (String) row.get("severity"), params);
    }

    /** Every rule of the catalog as it applies to the caller's organization now. */
    @Transactional(readOnly = true)
    public Map<String, RuleConfig> effective() {
        Map<String, Map<String, Object>> rows = rows();
        Map<String, RuleConfig> out = new LinkedHashMap<>();
        for (Definition def : LpRuleCatalog.definitions()) {
            out.put(def.code(), config(def, rows.get(def.code())));
        }
        return out;
    }

    private static Map<String, Object> view(Definition def, Map<String, Object> row) {
        RuleConfig c = config(def, row);
        // A rule with an unset threshold does not run: the screen says so instead of showing it as active.
        boolean configured = c.params().values().stream().noneMatch(v -> v == null);
        return Obj.of("code", def.code(), "engine", def.engine(), "title", def.title(), "enabled", c.enabled(),
                "severity", c.severity(), "params", c.params(), "defaults", Obj.of("enabled", def.enabled(),
                        "severity", def.severity(), "params", def.params()),
                "finalOnly", def.finalOnly(), "version", c.version(), "customized", row != null, "configured",
                configured, "updatedAt", row == null ? null : row.get("updatedAt"), "updatedBy",
                row == null ? null : row.get("updatedBy"));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        Map<String, Map<String, Object>> rows = rows();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Definition def : LpRuleCatalog.definitions()) {
            out.add(view(def, rows.get(def.code())));
        }
        return out;
    }

    private static Definition definition(String code) {
        Definition def = LpRuleCatalog.definition(code);
        if (def == null) {
            throw ApiException.notFound("Rule not found");
        }
        return def;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String code) {
        return view(definition(code), rows().get(code));
    }

    /** Replaces the rule's settings and records them as the next version. Past runs keep the version they used. */
    @Transactional
    public Map<String, Object> save(String code, RuleInput dto) {
        Definition def = definition(code);
        if (!SEVERITIES.contains(dto.severity())) {
            throw ApiException.badRequest("severity must be one of: " + String.join(", ", SEVERITIES));
        }
        Map<String, Object> params = new LinkedHashMap<>(def.params());
        if (dto.params() != null) {
            for (Map.Entry<String, Object> p : dto.params().entrySet()) {
                if (!def.params().containsKey(p.getKey())) {
                    throw ApiException.badRequest("Unknown parameter '" + p.getKey() + "' for rule " + code);
                }
                BigDecimal n = LpNumbers.decimal(p.getValue());
                if (p.getValue() != null && (n == null || n.signum() < 0)) {
                    throw ApiException.badRequest("Parameter '" + p.getKey() + "' must be a number of 0 or more");
                }
                params.put(p.getKey(), n);
            }
        }
        String org = LpScope.org();
        String user = TenantContext.userId();
        String json = scope.json(params);
        int version = jdbc.sql("INSERT INTO \"LpRule\" (id, \"organizationId\", code, enabled, severity, params,"
                + " version, \"updatedBy\") VALUES (?, ?, ?, ?, ?, ?::jsonb, 1, ?) ON CONFLICT (\"organizationId\","
                + " code) DO UPDATE SET enabled = EXCLUDED.enabled, severity = EXCLUDED.severity, params ="
                + " EXCLUDED.params, version = \"LpRule\".version + 1, \"updatedBy\" = EXCLUDED.\"updatedBy\","
                + " \"updatedAt\" = CURRENT_TIMESTAMP RETURNING version")
                .params(UUID.randomUUID().toString(), org, code, dto.enabled(), dto.severity(), json, user)
                .query(Integer.class).single();
        jdbc.sql("INSERT INTO \"LpRuleVersion\" (id, \"organizationId\", code, version, enabled, severity, params,"
                + " \"changedBy\") VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)")
                .params(UUID.randomUUID().toString(), org, code, version, dto.enabled(), dto.severity(), json, user)
                .update();
        return get(code);
    }

    /** Newest first. Version 0, the catalog default, has no row. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> versions(String code) {
        definition(code);
        return SqlRows.list(jdbc.sql("SELECT * FROM \"LpRuleVersion\" WHERE \"organizationId\" = ? AND code = ?"
                + " ORDER BY version DESC").params(LpScope.org(), code));
    }
}
