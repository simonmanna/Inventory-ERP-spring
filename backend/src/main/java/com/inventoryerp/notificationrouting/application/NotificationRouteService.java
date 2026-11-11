package com.inventoryerp.notificationrouting.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.notificationrouting.spi.Party;
import com.inventoryerp.notificationrouting.spi.RoutedEvent;
import com.inventoryerp.shared.web.ApiException;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-organization routing of each catalogued event (ADR-S065). With no {@code NotificationRoute} row the event's
 * defaults apply: on, its default parties, in-app only. Runs in the caller's tenant scope.
 */
@Service
public class NotificationRouteService {

    /** What is in force for one event. */
    public record Route(boolean enabled, List<String> parties, List<String> roleIds, List<String> userIds,
            List<String> channels, boolean customized) {}

    /** {@code PUT /notification-routing/events/{event}}: the whole route, as the form shows it. */
    public record RouteInput(
            @NotNull Boolean enabled,
            @NotNull @Size(max = 20) List<@NotNull String> parties,
            @Size(max = 50) List<@NotNull String> roleIds,
            @Size(max = 100) List<@NotNull String> userIds,
            @NotNull @Size(max = 4) List<@NotNull String> channels) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final RoutedEventCatalog catalog;

    public NotificationRouteService(JdbcClient jdbc, AuditService audit, RoutedEventCatalog catalog) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.catalog = catalog;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private Map<String, Map<String, Object>> rows() {
        Map<String, Map<String, Object>> out = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT * FROM \"NotificationRoute\" WHERE"
                + " \"organizationId\" = ?").param(org()))) {
            out.put((String) r.get("eventName"), r);
        }
        return out;
    }

    private Map<String, Object> row(String event) {
        return SqlRows.one(jdbc.sql("SELECT * FROM \"NotificationRoute\" WHERE \"organizationId\" = ? AND"
                + " \"eventName\" = ?").params(org(), event));
    }

    @SuppressWarnings("unchecked")
    private static List<String> texts(Object value) {
        return value == null ? List.of() : List.copyOf((List<String>) value);
    }

    static Route effective(RoutedEvent def, Map<String, Object> row) {
        if (row == null) {
            return new Route(true, def.defaultParties(), List.of(), List.of(), List.of("in_app"), false);
        }
        // A party the module no longer declares is dropped rather than failing the event.
        List<String> parties = texts(row.get("parties")).stream().filter(k -> def.party(k) != null).toList();
        return new Route(Boolean.TRUE.equals(row.get("enabled")), parties, texts(row.get("roleIds")),
                texts(row.get("userIds")), texts(row.get("channels")), true);
    }

    /** What is in force for {@code event} in the current organization. */
    @Transactional(readOnly = true)
    public Route route(RoutedEvent def) {
        return effective(def, row(def.event()));
    }

    /** Every event in catalog order: its definition, what is in force and whether this organization changed it. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        Map<String, Map<String, Object>> rows = rows();
        List<Map<String, Object>> out = new ArrayList<>();
        for (RoutedEvent def : catalog.all()) {
            out.add(json(def, effective(def, rows.get(def.event()))));
        }
        return out;
    }

    private static Map<String, Object> json(RoutedEvent def, Route r) {
        List<Map<String, Object>> parties = new ArrayList<>();
        for (Party p : def.parties()) {
            parties.add(Obj.of("key", p.key(), "label", p.label()));
        }
        return Obj.of("event", def.event(), "area", def.area(), "label", def.label(), "parties", parties,
                "defaultParties", def.defaultParties(), "enabled", r.enabled(), "selectedParties", r.parties(),
                "roleIds", r.roleIds(), "userIds", r.userIds(), "channels", r.channels(), "customized",
                r.customized());
    }

    private RoutedEvent definition(String event) {
        RoutedEvent def = catalog.get(event);
        if (def == null) {
            throw ApiException.notFound("Event not found");
        }
        return def;
    }

    /** Replace the route of {@code event}. Writes the row on first change; audited. */
    @Transactional
    public Map<String, Object> save(String event, RouteInput in) {
        RoutedEvent def = definition(event);
        List<String> parties = in.parties().stream().distinct().toList();
        for (String key : parties) {
            if (def.party(key) == null) {
                throw ApiException.badRequest("Unknown party " + key);
            }
        }
        List<String> channels = in.channels().stream().distinct().toList();
        for (String channel : channels) {
            if (!RoutedEventCatalog.CHANNELS.contains(channel)) {
                throw ApiException.badRequest("Unknown channel " + channel);
            }
        }
        List<String> roles = in.roleIds() == null ? List.of() : in.roleIds().stream().distinct().toList();
        for (String role : roles) {
            long found = jdbc.sql("SELECT count(*) FROM \"Role\" WHERE id = ? AND \"organizationId\" = ? AND"
                    + " \"deletedAt\" IS NULL").params(role, org()).query(Long.class).single();
            if (found == 0) {
                throw ApiException.badRequest("Role not found");
            }
        }
        List<String> users = in.userIds() == null ? List.of() : in.userIds().stream().distinct().toList();
        for (String user : users) {
            long found = jdbc.sql("SELECT count(*) FROM \"User\" WHERE id = ? AND \"organizationId\" = ? AND"
                    + " \"deletedAt\" IS NULL").params(user, org()).query(Long.class).single();
            if (found == 0) {
                throw ApiException.badRequest("User not found");
            }
        }
        Map<String, Object> before = row(event);
        // Comma-joined arrays: party keys and channels come from the catalog, ids were just checked; none holds a comma.
        jdbc.sql("INSERT INTO \"NotificationRoute\" (id, \"organizationId\", \"eventName\", enabled, parties,"
                + " \"roleIds\", \"userIds\", channels, \"createdBy\", \"updatedBy\") VALUES (?, ?, ?, ?,"
                + " string_to_array(?, ','), string_to_array(?, ','), string_to_array(?, ','), string_to_array(?, ','),"
                + " ?, ?) ON CONFLICT (\"organizationId\", \"eventName\") DO UPDATE SET enabled = EXCLUDED.enabled,"
                + " parties = EXCLUDED.parties, \"roleIds\" = EXCLUDED.\"roleIds\", \"userIds\" = EXCLUDED.\"userIds\","
                + " channels = EXCLUDED.channels, \"updatedBy\" = EXCLUDED.\"updatedBy\", \"updatedAt\" ="
                + " CURRENT_TIMESTAMP")
                .params(UUID.randomUUID().toString(), org(), event, in.enabled(), String.join(",", parties),
                        String.join(",", roles), String.join(",", users), String.join(",", channels),
                        TenantContext.userId(), TenantContext.userId())
                .update();
        Map<String, Object> after = row(event);
        audit.recordInTx(AuditInput.of("NotificationRoute", (String) after.get("id"),
                before == null ? "create" : "update", before, after));
        return json(def, effective(def, after));
    }

    /** Back to the event's defaults; audited. */
    @Transactional
    public Map<String, Object> reset(String event) {
        RoutedEvent def = definition(event);
        Map<String, Object> before = row(event);
        if (before != null) {
            jdbc.sql("DELETE FROM \"NotificationRoute\" WHERE \"organizationId\" = ? AND \"eventName\" = ?")
                    .params(org(), event).update();
            audit.recordInTx(AuditInput.of("NotificationRoute", (String) before.get("id"), "delete", before, null));
        }
        return json(def, effective(def, null));
    }

    /** The latest events the router handled in this organization, newest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> activity(Integer limit) {
        int n = limit == null ? 50 : Math.max(1, Math.min(200, limit));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT \"eventName\", \"recordId\", recipients,"
                + " \"claimedAt\" FROM \"NotificationRouteClaim\" WHERE \"organizationId\" = ? ORDER BY \"claimedAt\""
                + " DESC LIMIT ?").params(org(), n))) {
            RoutedEvent def = catalog.get((String) r.get("eventName"));
            r.put("label", def == null ? r.get("eventName") : def.label());
            r.put("area", def == null ? null : def.area());
            out.add(r);
        }
        return out;
    }
}
