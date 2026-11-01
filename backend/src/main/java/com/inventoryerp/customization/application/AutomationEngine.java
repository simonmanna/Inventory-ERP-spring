package com.inventoryerp.customization.application;

import com.inventoryerp.customization.application.AutomationCatalog.Trigger;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.kernel.webhooks.WebhookUrlGuard;
import com.inventoryerp.shared.web.EntityJson;
import com.inventoryerp.task.application.TaskService;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs automation rules (ADR-S029 §4). One {@code AutomationRun} per (rule, event key) — a redelivered event never runs
 * a rule twice. Each action's outcome is recorded; a failing action does not stop the next one. Rules run as the
 * organization, with no user. No action publishes a catalogued event, so a rule cannot trigger itself.
 */
@Service
public class AutomationEngine {

    private static final Logger log = LoggerFactory.getLogger(AutomationEngine.class);

    private final JdbcClient jdbc;
    private final PrintContextProviders contexts;
    private final CustomFieldService customFields;
    private final NotificationsService notifications;
    private final TaskService tasks;
    private final WebhookUrlGuard urlGuard;
    private final TransactionTemplate tx;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public AutomationEngine(JdbcClient jdbc, PrintContextProviders contexts, CustomFieldService customFields,
            NotificationsService notifications, TaskService tasks, WebhookUrlGuard urlGuard,
            PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.contexts = contexts;
        this.customFields = customFields;
        this.notifications = notifications;
        this.tasks = tasks;
        this.urlGuard = urlGuard;
        this.tx = new TransactionTemplate(txm);
    }

    /** Event entry point (outbox subscriber). */
    public void onEvent(Trigger trigger, String org, String recordId) {
        if (org == null || org.isEmpty() || recordId == null || recordId.isEmpty()) {
            return;
        }
        TenantContext.run(TenantStore.organization(org), () -> {
            List<Map<String, Object>> rules = SqlRows.list(jdbc.sql("SELECT id, name, conditions, actions FROM"
                    + " \"AutomationRule\" WHERE \"organizationId\" = ? AND trigger = ? AND \"isActive\" ORDER BY"
                    + " \"createdAt\"").params(org, trigger.event()));
            if (rules.isEmpty()) {
                return;
            }
            Map<String, Object> ctx;
            try {
                ctx = tx.execute(s -> context(org, trigger, recordId));
            } catch (RuntimeException e) {
                log.warn("automation: no context for {} {}: {}", trigger.entity(), recordId, e.getMessage());
                return;
            }
            String eventKey = trigger.event() + ":" + recordId;
            for (Map<String, Object> rule : rules) {
                runRule(org, trigger, recordId, eventKey, rule, ctx);
            }
        });
    }

    private void runRule(String org, Trigger trigger, String recordId, String eventKey, Map<String, Object> rule,
            Map<String, Object> ctx) {
        String runId = UUID.randomUUID().toString();
        int claimed = jdbc.sql("INSERT INTO \"AutomationRun\" (id, \"organizationId\", \"ruleId\", \"eventKey\", entity,"
                + " \"recordId\", status) VALUES (?, ?, ?, ?, ?, ?, 'skipped') ON CONFLICT (\"ruleId\", \"eventKey\") DO"
                + " NOTHING").params(runId, org, rule.get("id"), eventKey, trigger.entity(), recordId).update();
        if (claimed == 0) {
            return;
        }
        boolean matched = matches(AutomationRuleService.list(rule.get("conditions")), ctx);
        List<Map<String, Object>> results = new ArrayList<>();
        String status = "skipped";
        if (matched) {
            int ok = 0;
            List<Map<String, Object>> actions = AutomationRuleService.list(rule.get("actions"));
            for (Map<String, Object> action : actions) {
                try {
                    String message = execute(org, trigger, recordId, action, ctx, String.valueOf(rule.get("name")));
                    results.add(Obj.of("type", action.get("type"), "ok", true, "message", message));
                    ok++;
                } catch (RuntimeException e) {
                    results.add(Obj.of("type", action.get("type"), "ok", false, "message", e.getMessage()));
                }
            }
            status = ok == actions.size() ? "succeeded" : ok == 0 ? "failed" : "partial";
        }
        jdbc.sql("UPDATE \"AutomationRun\" SET status = ?, matched = ?, results = ?::jsonb WHERE id = ?")
                .params(status, matched, EntityJson.toJson(results), runId).update();
        if (matched) {
            jdbc.sql("UPDATE \"AutomationRule\" SET \"runCount\" = \"runCount\" + 1, \"lastRunAt\" = now() WHERE id = ?")
                    .param(rule.get("id")).update();
        }
    }

    /** {@code POST /automation-rules/:id/test} body (no side effects). */
    public Map<String, Object> dryRun(String org, Trigger trigger, List<Map<String, Object>> conditions,
            List<Map<String, Object>> actions, String recordId) {
        Map<String, Object> ctx = context(org, trigger, recordId);
        List<Map<String, Object>> checks = new ArrayList<>();
        boolean all = true;
        for (Map<String, Object> c : conditions) {
            boolean ok = holds(c, ctx);
            all &= ok;
            checks.add(Obj.of("field", c.get("field"), "op", c.get("op"), "value", c.get("value"), "actual",
                    resolve(ctx, String.valueOf(c.get("field"))), "holds", ok));
        }
        List<Map<String, Object>> previews = new ArrayList<>();
        for (Map<String, Object> a : actions) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("type", a.get("type"));
            for (String k : List.of("to", "subject", "body", "title", "description", "value", "url")) {
                if (a.get(k) != null) {
                    p.put(k, render(String.valueOf(a.get(k)), ctx));
                }
            }
            previews.add(p);
        }
        return Obj.of("matched", all, "conditions", checks, "actions", previews, "context", ctx);
    }

    /** The data conditions and templates see: the print context for documents, the partner otherwise. */
    Map<String, Object> context(String org, Trigger trigger, String recordId) {
        Map<String, Object> ctx;
        if (PrintContextProviders.KINDS.contains(trigger.entity())) {
            ctx = contexts.context(org, trigger.entity(), recordId);
        } else {
            Map<String, Object> p = SqlRows.one(jdbc.sql("SELECT id, name, code, email, phone, \"isCustomer\","
                    + " \"isSupplier\", \"taxNumber\" FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(recordId, org));
            if (p == null) {
                throw new IllegalArgumentException("Record not found");
            }
            ctx = new LinkedHashMap<>();
            ctx.put("partner", p);
            ctx.put("custom", customFields.visibleValues(org, trigger.entity(), recordId));
        }
        ctx.put("event", Obj.of("name", trigger.event(), "label", trigger.label(), "recordId", recordId));
        return ctx;
    }

    // ---- conditions -----------------------------------------------------------------------------------------------

    static boolean matches(List<Map<String, Object>> conditions, Map<String, Object> ctx) {
        for (Map<String, Object> c : conditions) {
            if (!holds(c, ctx)) {
                return false;
            }
        }
        return true;
    }

    static boolean holds(Map<String, Object> c, Map<String, Object> ctx) {
        Object actual = resolve(ctx, String.valueOf(c.get("field")));
        Object expected = c.get("value");
        String op = String.valueOf(c.get("op"));
        switch (op) {
            case "empty" -> {
                return !TemplateEngine.truthy(actual);
            }
            case "not_empty" -> {
                return TemplateEngine.truthy(actual);
            }
            case "contains" -> {
                if (actual instanceof Collection<?> items) {
                    return items.stream().anyMatch(i -> String.valueOf(i).equals(String.valueOf(expected)));
                }
                return actual != null && String.valueOf(actual).toLowerCase().contains(String.valueOf(expected)
                        .toLowerCase());
            }
            case "in" -> {
                if (expected instanceof Collection<?> options) {
                    return actual != null && options.stream().anyMatch(o -> same(actual, o));
                }
                return false;
            }
            case "eq" -> {
                return same(actual, expected);
            }
            case "ne" -> {
                return !same(actual, expected);
            }
            default -> {
                BigDecimal a = number(actual);
                BigDecimal b = number(expected);
                if (a == null || b == null) {
                    return false;
                }
                int cmp = a.compareTo(b);
                return switch (op) {
                    case "gt" -> cmp > 0;
                    case "gte" -> cmp >= 0;
                    case "lt" -> cmp < 0;
                    case "lte" -> cmp <= 0;
                    default -> false;
                };
            }
        }
    }

    private static boolean same(Object a, Object b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        BigDecimal x = number(a);
        BigDecimal y = number(b);
        if (x != null && y != null) {
            return x.compareTo(y) == 0;
        }
        return String.valueOf(a).equalsIgnoreCase(String.valueOf(b));
    }

    private static BigDecimal number(Object v) {
        if (v == null || v instanceof Boolean) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(v).replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Object resolve(Map<String, Object> ctx, String path) {
        Object v = ctx;
        for (String part : path.split("\\.")) {
            if (!(v instanceof Map<?, ?> m)) {
                return null;
            }
            v = m.get(part);
        }
        return v;
    }

    // ---- actions --------------------------------------------------------------------------------------------------

    private String execute(String org, Trigger trigger, String recordId, Map<String, Object> a, Map<String, Object> ctx,
            String ruleName) {
        String type = String.valueOf(a.get("type"));
        switch (type) {
            case "notify" -> {
                List<String> users = new ArrayList<>();
                if (a.get("userIds") instanceof List<?> ids) {
                    ids.forEach(i -> users.add(String.valueOf(i)));
                }
                if (a.get("roleId") instanceof String role && !role.isBlank()) {
                    users.addAll(jdbc.sql("SELECT u.id FROM \"User\" u JOIN \"_UserRoles\" ur ON ur.\"B\" = u.id WHERE"
                            + " ur.\"A\" = ? AND u.\"organizationId\" = ? AND u.\"isActive\" AND u.\"deletedAt\" IS NULL")
                            .params(role, org).query(String.class).list());
                }
                List<String> valid = users.stream().distinct().filter(u -> jdbc.sql("SELECT count(*) FROM \"User\" WHERE"
                        + " id = ? AND \"organizationId\" = ?").params(u, org).query(Long.class).single() > 0).toList();
                String title = render(String.valueOf(a.get("title")), ctx);
                String body = a.get("body") == null ? "" : render(String.valueOf(a.get("body")), ctx);
                for (String u : valid) {
                    notifications.send(new NotificationsService.SendInput(org, u, "in_app", "automation", title, body,
                            Obj.of("rule", ruleName, "entity", trigger.entity(), "recordId", recordId)));
                }
                return "Notified " + valid.size() + " user(s)";
            }
            case "email" -> {
                String to = render(String.valueOf(a.get("to")), ctx).trim();
                if (!to.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
                    throw new IllegalArgumentException("No valid e-mail address to send to");
                }
                boolean sent = notifications.sendExternalEmail(to, render(String.valueOf(a.get("subject")), ctx),
                        render(String.valueOf(a.get("body")), ctx), null);
                return sent ? "E-mail sent" : "E-mail logged (SMTP is not configured)";
            }
            case "webhook" -> {
                String url = String.valueOf(a.get("url"));
                urlGuard.check(url);
                String json = EntityJson.toJson(Obj.of("rule", ruleName, "event", trigger.event(), "entity",
                        trigger.entity(), "recordId", recordId, "organizationId", org, "data", ctx));
                try {
                    HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                            .header("User-Agent", "Inventory-ERP-Automation/1").POST(HttpRequest.BodyPublishers
                                    .ofString(json)).build(), HttpResponse.BodyHandlers.discarding());
                    if (r.statusCode() >= 300) {
                        throw new IllegalStateException("Webhook answered " + r.statusCode());
                    }
                    return "Webhook answered " + r.statusCode();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Webhook interrupted");
                } catch (java.io.IOException e) {
                    throw new IllegalStateException("Webhook failed: " + e.getMessage());
                }
            }
            case "set_custom_field" -> {
                String key = String.valueOf(a.get("key"));
                String value = a.get("value") == null ? null : render(String.valueOf(a.get("value")), ctx);
                customFields.setValue(org, trigger.entity(), recordId, key, value);
                return "Set " + key;
            }
            case "create_task" -> {
                Map<String, Object> dto = new LinkedHashMap<>();
                dto.put("title", truncate(render(String.valueOf(a.get("title")), ctx), 200));
                if (a.get("description") != null) {
                    dto.put("description", render(String.valueOf(a.get("description")), ctx));
                }
                if (a.get("assigneeId") instanceof String assignee && !assignee.isBlank()) {
                    dto.put("assigneeId", assignee);
                }
                if (a.get("dueInDays") instanceof Number days) {
                    dto.put("dueDate", LocalDate.now(ZoneOffset.UTC).plusDays(days.longValue()).toString());
                }
                Map<String, Object> task = tasks.create(dto);
                return "Task " + task.get("id");
            }
            default -> throw new IllegalArgumentException("Unknown action " + type);
        }
    }

    static String render(String template, Map<String, Object> ctx) {
        return TemplateEngine.renderText(template, ctx);
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
