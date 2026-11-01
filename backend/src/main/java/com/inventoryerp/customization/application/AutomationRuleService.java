package com.inventoryerp.customization.application;

import com.inventoryerp.customization.application.AutomationCatalog.Trigger;
import com.inventoryerp.customization.application.TemplateEngine.TemplateException;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.webhooks.WebhookUrlGuard;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import com.inventoryerp.shared.web.PageQuery;
import com.inventoryerp.shared.web.PageResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Automation rules (ADR-S029 §4): definition, validation, runs log and dry-run tests. The {@link AutomationEngine} runs
 * them on events.
 */
@Service
public class AutomationRuleService {

    /** Rule input (update: nulls keep the stored value). */
    public record RuleInput(String name, String description, String trigger, List<Map<String, Object>> conditions,
            List<Map<String, Object>> actions, Boolean isActive) {}

    private final JdbcClient jdbc;
    private final AutomationEngine engine;
    private final WebhookUrlGuard urlGuard;
    private final AuditService audit;

    public AutomationRuleService(JdbcClient jdbc, AutomationEngine engine, WebhookUrlGuard urlGuard,
            AuditService audit) {
        this.jdbc = jdbc;
        this.engine = engine;
        this.urlGuard = urlGuard;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** {@code GET /automation-rules/catalog}: triggers, operators and action types. */
    public Map<String, Object> catalog() {
        List<Map<String, Object>> triggers = new ArrayList<>();
        AutomationCatalog.TRIGGERS.values().forEach(t -> triggers.add(Obj.of("event", t.event(), "label", t.label(),
                "entity", t.entity())));
        return Obj.of("triggers", triggers, "operators", List.of("eq", "ne", "gt", "gte", "lt", "lte", "contains", "in",
                "empty", "not_empty"), "actions", AutomationCatalog.ACTIONS);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return SqlRows.list(jdbc.sql("SELECT * FROM \"AutomationRule\" WHERE \"organizationId\" = ? ORDER BY name")
                .param(org()));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT * FROM \"AutomationRule\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (r == null) {
            throw ApiException.notFound("Automation rule not found");
        }
        return r;
    }

    @Transactional
    public Map<String, Object> create(RuleInput in) {
        String name = name(in.name());
        Trigger trigger = trigger(in.trigger());
        List<Map<String, Object>> conditions = conditions(in.conditions());
        List<Map<String, Object>> actions = actions(in.actions(), trigger);
        boolean active = in.isActive() == null || in.isActive();
        if (active) {
            assertActiveLimit(null);
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"AutomationRule\" (id, \"organizationId\", name, description, trigger, conditions, actions,"
                + " \"isActive\", \"createdBy\") VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)")
                .params(id, org(), name, in.description(), trigger.event(), EntityJson.toJson(conditions),
                        EntityJson.toJson(actions), active, TenantContext.userId()).update();
        audit.recordInTx(AuditInput.of("AutomationRule", id, "create", null, Obj.of("name", name, "trigger",
                trigger.event())));
        return get(id);
    }

    @Transactional
    public Map<String, Object> update(String id, RuleInput in) {
        Map<String, Object> before = get(id);
        Trigger trigger = trigger(in.trigger() == null ? (String) before.get("trigger") : in.trigger());
        List<Map<String, Object>> conditions = in.conditions() == null ? list(before.get("conditions"))
                : conditions(in.conditions());
        List<Map<String, Object>> actions = actions(in.actions() == null ? list(before.get("actions")) : in.actions(),
                trigger);
        boolean active = in.isActive() == null ? Boolean.TRUE.equals(before.get("isActive")) : in.isActive();
        if (active && !Boolean.TRUE.equals(before.get("isActive"))) {
            assertActiveLimit(id);
        }
        jdbc.sql("UPDATE \"AutomationRule\" SET name = ?, description = ?, trigger = ?, conditions = ?::jsonb, actions ="
                + " ?::jsonb, \"isActive\" = ?, \"updatedAt\" = now() WHERE id = ? AND \"organizationId\" = ?")
                .params(in.name() == null ? before.get("name") : name(in.name()),
                        in.description() == null ? before.get("description") : in.description(), trigger.event(),
                        EntityJson.toJson(conditions), EntityJson.toJson(actions), active, id, org()).update();
        Map<String, Object> after = get(id);
        audit.recordInTx(AuditInput.of("AutomationRule", id, "update", before, after));
        return after;
    }

    @Transactional
    public Map<String, Object> delete(String id) {
        get(id);
        jdbc.sql("DELETE FROM \"AutomationRule\" WHERE id = ? AND \"organizationId\" = ?").params(id, org()).update();
        audit.recordInTx(AuditInput.of("AutomationRule", id, "delete"));
        return Obj.of("id", id, "deleted", true);
    }

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> runs(String id, PageQuery q) {
        get(id);
        long total = jdbc.sql("SELECT count(*) FROM \"AutomationRun\" WHERE \"ruleId\" = ? AND \"organizationId\" = ?")
                .params(id, org()).query(Long.class).single();
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT * FROM \"AutomationRun\" WHERE \"ruleId\" = ? AND"
                + " \"organizationId\" = ? ORDER BY \"createdAt\" DESC LIMIT ? OFFSET ?").params(id, org(),
                        q.pageSizeOrDefault(), q.offset()));
        return PageResult.of(rows, q, total);
    }

    /** {@code POST /automation-rules/:id/test}: what the rule would do for {@code recordId}, without doing it. */
    @Transactional(readOnly = true)
    public Map<String, Object> test(String id, String recordId) {
        Map<String, Object> rule = get(id);
        Trigger trigger = trigger((String) rule.get("trigger"));
        if (recordId == null || recordId.isBlank()) {
            throw ApiException.badRequest("recordId should not be empty");
        }
        return engine.dryRun(org(), trigger, list(rule.get("conditions")), list(rule.get("actions")), recordId);
    }

    private void assertActiveLimit(String exceptId) {
        long n = jdbc.sql("SELECT count(*) FROM \"AutomationRule\" WHERE \"organizationId\" = ? AND \"isActive\""
                + (exceptId == null ? "" : " AND id <> ?")).params(exceptId == null ? List.of(org())
                        : List.of(org(), exceptId)).query(Long.class).single();
        if (n >= AutomationCatalog.MAX_ACTIVE_RULES) {
            throw ApiException.badRequest("An organization can have at most " + AutomationCatalog.MAX_ACTIVE_RULES
                    + " active automation rules");
        }
    }

    // ---- validation -----------------------------------------------------------------------------------------------

    private static String name(String n) {
        if (n == null || n.isBlank()) {
            throw ApiException.badRequest("name should not be empty");
        }
        return n.trim();
    }

    private static Trigger trigger(String event) {
        Trigger t = event == null ? null : AutomationCatalog.TRIGGERS.get(event);
        if (t == null) {
            throw ApiException.badRequest("trigger must be one of the following values: "
                    + String.join(", ", AutomationCatalog.TRIGGERS.keySet()));
        }
        return t;
    }

    private static List<Map<String, Object>> conditions(List<Map<String, Object>> in) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (in == null) {
            return out;
        }
        if (in.size() > AutomationCatalog.MAX_CONDITIONS) {
            throw ApiException.badRequest("A rule can have at most " + AutomationCatalog.MAX_CONDITIONS + " conditions");
        }
        for (Map<String, Object> c : in) {
            Object field = c.get("field");
            Object op = c.get("op");
            if (!(field instanceof String f) || !f.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*")) {
                throw ApiException.badRequest("Each condition needs a field such as document.totalRaw");
            }
            if (!(op instanceof String o) || !AutomationCatalog.OPERATORS.contains(o)) {
                throw ApiException.badRequest("op must be one of the following values: eq, ne, gt, gte, lt, lte, contains,"
                        + " in, empty, not_empty");
            }
            if (!"empty".equals(op) && !"not_empty".equals(op) && c.get("value") == null) {
                throw ApiException.badRequest("Condition on " + field + " needs a value");
            }
            out.add(Obj.of("field", field, "op", op, "value", c.get("value")));
        }
        return out;
    }

    private List<Map<String, Object>> actions(List<Map<String, Object>> in, Trigger trigger) {
        if (in == null || in.isEmpty()) {
            throw ApiException.badRequest("A rule needs at least one action");
        }
        if (in.size() > AutomationCatalog.MAX_ACTIONS) {
            throw ApiException.badRequest("A rule can have at most " + AutomationCatalog.MAX_ACTIONS + " actions");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> a : in) {
            Object type = a.get("type");
            if (!(type instanceof String t) || !AutomationCatalog.ACTIONS.contains(t)) {
                throw ApiException.badRequest("action type must be one of the following values: "
                        + String.join(", ", AutomationCatalog.ACTIONS));
            }
            switch (t) {
                case "notify" -> {
                    template(a, "title", true);
                    template(a, "body", false);
                    boolean role = a.get("roleId") instanceof String r && !r.isBlank();
                    boolean users = a.get("userIds") instanceof List<?> u && !u.isEmpty();
                    if (!role && !users) {
                        throw ApiException.badRequest("A notify action needs a roleId or userIds");
                    }
                    if (role && jdbc.sql("SELECT count(*) FROM \"Role\" WHERE id = ? AND \"organizationId\" = ?")
                            .params(a.get("roleId"), org()).query(Long.class).single() == 0) {
                        throw ApiException.badRequest("Role not found");
                    }
                }
                case "email" -> {
                    template(a, "to", true);
                    template(a, "subject", true);
                    template(a, "body", true);
                }
                case "webhook" -> {
                    Object url = a.get("url");
                    if (!(url instanceof String u) || !u.startsWith("https://")) {
                        throw ApiException.badRequest("A webhook action needs an https:// url");
                    }
                    urlGuard.check(u);
                }
                case "set_custom_field" -> {
                    Object key = a.get("key");
                    if (!(key instanceof String k) || jdbc.sql("SELECT count(*) FROM \"CustomFieldDefinition\" WHERE"
                            + " \"organizationId\" = ? AND entity = ? AND key = ? AND \"isActive\"").params(org(),
                                    trigger.entity(), k).query(Long.class).single() == 0) {
                        throw ApiException.badRequest("set_custom_field needs the key of an active custom field of "
                                + trigger.entity());
                    }
                    template(a, "value", false);
                }
                case "create_task" -> {
                    template(a, "title", true);
                    template(a, "description", false);
                    if (a.get("dueInDays") != null && !(a.get("dueInDays") instanceof Number)) {
                        throw ApiException.badRequest("dueInDays must be a number");
                    }
                }
                default -> throw new IllegalStateException(t);
            }
            out.add(a);
        }
        return out;
    }

    private static void template(Map<String, Object> a, String key, boolean required) {
        Object v = a.get(key);
        if (v == null || (v instanceof String s && s.isBlank())) {
            if (required) {
                throw ApiException.badRequest("A " + a.get("type") + " action needs " + key);
            }
            return;
        }
        try {
            TemplateEngine.validate(String.valueOf(v));
        } catch (TemplateException e) {
            throw ApiException.badRequest(key + ": " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Object v) {
        Object parsed = CustomFieldService.plain(v);
        return parsed instanceof List<?> l ? (List<Map<String, Object>>) l : new ArrayList<>();
    }
}
