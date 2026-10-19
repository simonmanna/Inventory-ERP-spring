package com.inventoryerp.kernel.workflow;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.events.EventOutboxService;
import com.inventoryerp.kernel.security.PermissionService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.workflow.WorkflowDefinition.Context;
import com.inventoryerp.kernel.workflow.WorkflowDefinition.Transition;
import com.inventoryerp.shared.web.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generic state-machine executor (port of {@code workflow.service.ts}). A transition loads the row,
 * checks the declared transition, permission and guard, runs the side effect, updates {@code status}
 * (plus postedAt/postedBy or cancelledAt/cancelledBy when the table has them), audits, and publishes the
 * business fact on the SAME transaction (joins the caller's when one is active).
 */
@Service
public class WorkflowService {

    /** Store operations, swappable in unit tests. */
    public interface Store {
        Map<String, Object> load(String table, String id);

        void updateStatus(String table, String id, Map<String, Object> values);

        Set<String> columns(String table);
    }

    /** Transition result. */
    public record Result(String fromState, String toState, Map<String, Object> entity) {}

    private static final Map<String, String> MODEL = Map.of(
            "invoice", "Document", "credit_note", "Document", "vendor_bill", "Document", "debit_note", "Document",
            "proforma_invoice", "Document", "journal_entry", "JournalEntry");

    private final Store store;
    private final AuditService audit;
    private final EventOutboxService outbox;
    private final WorkflowRegistry registry;
    private final Supplier<List<String>> permissions;

    @org.springframework.beans.factory.annotation.Autowired
    public WorkflowService(JdbcClient jdbc, AuditService audit, EventOutboxService outbox, WorkflowRegistry registry,
            PermissionService permissionService) {
        this(new JdbcStore(jdbc), audit, outbox, registry, permissionService::current);
    }

    public WorkflowService(Store store, AuditService audit, EventOutboxService outbox, WorkflowRegistry registry,
            Supplier<List<String>> permissions) {
        this.store = store;
        this.audit = audit;
        this.outbox = outbox;
        this.registry = registry;
        this.permissions = permissions;
    }

    /** Actions currently available to the actor. */
    public List<Transition> availableActions(String entityType, String fromState, List<String> perms) {
        WorkflowDefinition def = registry.get(entityType);
        if (def == null) {
            return List.of();
        }
        return def.transitions().stream()
                .filter(t -> t.from().equals(fromState) && (t.permission() == null || perms.contains(t.permission())))
                .toList();
    }

    /** Pre-flight check without DB writes. */
    public Transition canTransition(String entityType, String fromState, String action, List<String> perms) {
        WorkflowDefinition def = registry.get(entityType);
        if (def == null) {
            return null;
        }
        return def.transitions().stream()
                .filter(t -> t.from().equals(fromState) && t.action().equals(action)
                        && (t.permission() == null || perms.contains(t.permission())))
                .findFirst()
                .orElse(null);
    }

    @Transactional
    public Result transition(String entityType, String entityId, String action, Map<String, Object> payload,
            Map<String, Object> entity) {
        WorkflowDefinition def = registry.get(entityType);
        if (def == null) {
            throw ApiException.notFound("No workflow defined for entity type '" + entityType + "'");
        }
        String table = tableName(entityType);
        Map<String, Object> row = entity != null ? entity : store.load(table, entityId);
        if (row == null) {
            throw ApiException.notFound(entityType + " " + entityId + " not found");
        }
        Object status = row.get("status");
        if (status == null) {
            throw ApiException.badRequest("Entity " + entityType + " has no 'status' field");
        }
        String fromState = String.valueOf(status);
        Transition transition = def.transitions().stream()
                .filter(t -> t.from().equals(fromState) && t.action().equals(action))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest(
                        "No '" + action + "' transition from '" + fromState + "' for " + entityType));
        List<String> perms = permissions.get();
        Context ctx = new Context(entityType, entityId, TenantContext.organizationId(), TenantContext.userId(), perms,
                action, fromState, transition.to(), payload, row);
        if (transition.permission() != null && !perms.contains(transition.permission())) {
            throw ApiException.forbidden("Missing permission '" + transition.permission() + "' for " + entityType + "."
                    + action);
        }
        if (transition.guard() != null && !transition.guard().test(ctx)) {
            throw ApiException.badRequest("Guard rejected " + entityType + "." + action);
        }
        if (transition.sideEffect() != null) {
            transition.sideEffect().run(ctx);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", transition.to());
        values.putAll(statusExtraFields(table, transition.to()));
        store.updateStatus(table, entityId, values);
        Map<String, Object> oldValues = Map.of("status", fromState);
        Map<String, Object> newValues = new LinkedHashMap<>();
        newValues.put("status", transition.to());
        newValues.put("action", action);
        audit.recordInTx(AuditService.AuditInput.of(entityType, entityId, auditActionFor(action), oldValues, newValues));
        String eventName = transition.event() != null ? transition.event() : entityType + "." + action;
        Map<String, Object> fact = new LinkedHashMap<>();
        fact.put("organizationId", ctx.organizationId());
        fact.put(entityType + "Id", entityId);
        fact.put("fromState", fromState);
        fact.put("toState", transition.to());
        fact.put("action", action);
        if (payload != null) {
            fact.putAll(payload);
        }
        outbox.publish(eventName, fact);
        Map<String, Object> after = new LinkedHashMap<>(row);
        after.put("status", transition.to());
        return new Result(fromState, transition.to(), after);
    }

    private Map<String, Object> statusExtraFields(String table, String toState) {
        Map<String, Object> extra = new LinkedHashMap<>();
        if ("posted".equals(toState)) {
            extra.put("postedAt", java.time.Instant.now());
            extra.put("postedBy", TenantContext.userId());
        } else if ("cancelled".equals(toState)) {
            extra.put("cancelledAt", java.time.Instant.now());
            extra.put("cancelledBy", TenantContext.userId());
        }
        Set<String> cols = store.columns(table);
        extra.keySet().removeIf(k -> cols != null && !cols.contains(k));
        return extra;
    }

    /** entityType → Prisma model/table (PascalCase of the snake_case type, with the document overrides). */
    static String tableName(String entityType) {
        String mapped = MODEL.get(entityType);
        if (mapped != null) {
            return mapped;
        }
        String c = camel(entityType);
        return Character.toUpperCase(c.charAt(0)) + c.substring(1);
    }

    static String camel(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char ch : snake.toCharArray()) {
            if (ch == '_') {
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(ch) : ch);
                up = false;
            }
        }
        return sb.toString();
    }

    static String auditActionFor(String action) {
        return switch (action) {
            case "post" -> "post";
            case "cancel", "void" -> "cancel";
            case "approve" -> "approve";
            case "reject" -> "reject";
            default -> "update";
        };
    }

    /** SQL store: tenant-scoped row access by table name. */
    static final class JdbcStore implements Store {
        private final JdbcClient jdbc;
        private final Map<String, Set<String>> columns = new ConcurrentHashMap<>();

        JdbcStore(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public Map<String, Object> load(String table, String id) {
            Set<String> cols = columns(table);
            String org = cols.contains("organizationId") ? " AND \"organizationId\" = :org" : "";
            String deleted = cols.contains("deletedAt") ? " AND \"deletedAt\" IS NULL" : "";
            var q = jdbc.sql("SELECT * FROM \"" + table + "\" WHERE id = :id" + org + deleted + " LIMIT 1").param("id", id);
            if (!org.isEmpty()) {
                q = q.param("org", TenantContext.organizationId());
            }
            return q.query((rs, n) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                var md = rs.getMetaData();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    row.put(md.getColumnLabel(i), rs.getObject(i));
                }
                return row;
            }).optional().orElse(null);
        }

        @Override
        public void updateStatus(String table, String id, Map<String, Object> values) {
            Set<String> cols = columns(table);
            StringBuilder set = new StringBuilder();
            var params = new LinkedHashMap<String, Object>();
            int i = 0;
            for (var e : values.entrySet()) {
                String p = "v" + i++;
                set.append(set.isEmpty() ? "" : ", ").append('"').append(e.getKey()).append("\" = :").append(p);
                if (e.getKey().equals("status")) {
                    set.append("::text::\"").append(statusType(table)).append('"');
                }
                Object v = e.getValue();
                params.put(p, v instanceof java.time.Instant inst ? java.sql.Timestamp.from(inst) : v);
            }
            if (cols.contains("updatedAt")) {
                set.append(", \"updatedAt\" = now()");
            }
            String org = cols.contains("organizationId") ? " AND \"organizationId\" = :org" : "";
            var q = jdbc.sql("UPDATE \"" + table + "\" SET " + set + " WHERE id = :id" + org).params(params).param("id", id);
            if (!org.isEmpty()) {
                q = q.param("org", TenantContext.organizationId());
            }
            q.update();
        }

        @Override
        public Set<String> columns(String table) {
            return columns.computeIfAbsent(table, t -> Set.copyOf(jdbc.sql("""
                            SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ?""")
                    .param(t).query(String.class).list()));
        }

        private String statusType(String table) {
            return jdbc.sql("""
                            SELECT udt_name FROM information_schema.columns
                            WHERE table_schema = 'public' AND table_name = ? AND column_name = 'status'""")
                    .param(table).query(String.class).optional().orElse("text");
        }
    }
}
