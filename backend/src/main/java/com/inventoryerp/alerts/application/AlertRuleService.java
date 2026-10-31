package com.inventoryerp.alerts.application;

import com.inventoryerp.alerts.application.AlertCommands.RuleInput;
import com.inventoryerp.alerts.spi.AlertTypes;
import com.inventoryerp.alerts.spi.AlertTypes.Definition;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-organization alert settings. The catalog ({@link AlertTypes}) gives every type its defaults; a row exists only
 * once someone changes a type, so a new type reaches every organization without a data migration.
 */
@Service
public class AlertRuleService {

    private static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";

    /** The settings a scan runs with. {@code userIds} are the named recipients. */
    public record Rule(boolean enabled, BigDecimal threshold, String repeatEvery, List<String> channels,
            String recipientPermission, List<String> userIds) {}

    private final JdbcClient jdbc;
    private final AuditService audit;

    public AlertRuleService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private Map<String, Map<String, Object>> rows() {
        Map<String, Map<String, Object>> out = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT * FROM \"AlertRule\" WHERE \"organizationId\" = ?")
                .param(org()))) {
            out.put((String) r.get("alertType"), r);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> texts(Object value) {
        return value == null ? List.of() : List.copyOf((List<String>) value);
    }

    private static Rule effective(Definition def, Map<String, Object> row) {
        if (row == null) {
            return new Rule(true, def.defaultThreshold(), def.defaultRepeat(), def.defaultChannels(),
                    AlertTypes.DEFAULT_RECIPIENT_PERMISSION, List.of());
        }
        BigDecimal threshold = row.get("threshold") == null ? def.defaultThreshold() : SqlRows.dec(row, "threshold");
        String permission = row.get("recipientPermission") == null ? AlertTypes.DEFAULT_RECIPIENT_PERMISSION
                : (String) row.get("recipientPermission");
        return new Rule(Boolean.TRUE.equals(row.get("enabled")), threshold, (String) row.get("repeatEvery"),
                texts(row.get("channels")), permission, texts(row.get("recipientUserIds")));
    }

    /** The effective settings of every type in the caller's organization. */
    @Transactional(readOnly = true)
    public Map<String, Rule> effectiveRules() {
        Map<String, Map<String, Object>> rows = rows();
        Map<String, Rule> out = new LinkedHashMap<>();
        for (Definition def : AlertTypes.ALL) {
            out.put(def.type(), effective(def, rows.get(def.type())));
        }
        return out;
    }

    /** Every type in catalog order: its definition, what is in force and whether this organization changed it. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        Map<String, Map<String, Object>> rows = rows();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Definition def : AlertTypes.ALL) {
            out.add(json(def, rows.get(def.type())));
        }
        return out;
    }

    private static Map<String, Object> json(Definition def, Map<String, Object> row) {
        Rule rule = effective(def, row);
        return Obj.of("alertType", def.type(), "label", def.label(), "cadence", def.cadence(), "thresholdUnit",
                def.thresholdUnit(), "thresholdHelp", def.thresholdHelp(), "defaultThreshold", def.defaultThreshold(),
                "enabled", rule.enabled(), "threshold", rule.threshold(), "repeatEvery", rule.repeatEvery(),
                "channels", rule.channels(), "recipientPermission", rule.recipientPermission(), "recipientUserIds",
                rule.userIds(), "customized", row != null);
    }

    /** Replace the settings of {@code type}. Writes the row on first change; audited. */
    @Transactional
    public Map<String, Object> save(String type, RuleInput in) {
        Definition def = AlertTypes.definition(type);
        if (def == null) {
            throw ApiException.notFound("Alert type not found");
        }
        for (String channel : in.channels()) {
            if (!AlertTypes.CHANNELS.contains(channel)) {
                throw ApiException.badRequest("Unknown channel " + channel);
            }
        }
        String permission = in.recipientPermission() == null || in.recipientPermission().isBlank() ? null
                : in.recipientPermission().trim();
        if (permission != null && !Permissions.ALL.contains(permission)) {
            throw ApiException.badRequest("Unknown permission " + permission);
        }
        List<String> users = in.recipientUserIds() == null ? List.of() : in.recipientUserIds().stream().distinct()
                .toList();
        for (String user : users) {
            long found = jdbc.sql("SELECT count(*) FROM \"User\" WHERE id = ? AND \"organizationId\" = ? AND"
                    + " \"deletedAt\" IS NULL").params(user, org()).query(Long.class).single();
            if (found == 0) {
                throw ApiException.badRequest("User not found");
            }
        }
        Map<String, Object> before = rows().get(type);
        String channels = String.join(",", in.channels().stream().distinct().toList());
        // Comma-joined arrays: channels come from the catalog and user ids were just checked, neither holds a comma.
        jdbc.sql("INSERT INTO \"AlertRule\" (id, \"organizationId\", \"alertType\", enabled, threshold,"
                + " \"repeatEvery\", channels, \"recipientPermission\", \"recipientUserIds\", \"createdBy\","
                + " \"updatedBy\") VALUES"
                + " (?, ?, ?, ?, ?, ?, string_to_array(?, ','), ?, string_to_array(?, ','), ?, ?) ON CONFLICT"
                + " (\"organizationId\", \"alertType\") DO UPDATE SET enabled = EXCLUDED.enabled, threshold ="
                + " EXCLUDED.threshold, \"repeatEvery\" = EXCLUDED.\"repeatEvery\", channels = EXCLUDED.channels,"
                + " \"recipientPermission\" = EXCLUDED.\"recipientPermission\", \"recipientUserIds\" ="
                + " EXCLUDED.\"recipientUserIds\", \"updatedBy\" = EXCLUDED.\"updatedBy\", \"updatedAt\" = " + NOW)
                .params(UUID.randomUUID().toString(), org(), type, in.enabled(), in.threshold(), in.repeatEvery(),
                        channels, permission, String.join(",", users), TenantContext.userId(), TenantContext.userId())
                .update();
        Map<String, Object> after = rows().get(type);
        audit.recordInTx(AuditInput.of("AlertRule", (String) after.get("id"), before == null ? "create" : "update",
                before, after));
        return json(def, after);
    }
}
