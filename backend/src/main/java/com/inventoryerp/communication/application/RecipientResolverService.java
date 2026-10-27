package com.inventoryerp.communication.application;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Turns a rule's {@code recipientResolver} into recipients (port of {@code rules/recipient-resolver.service.ts}):
 * {@code permission:<perm>}, {@code role:<name>}, {@code user:<payloadPath>} (default {@code userId}),
 * {@code partner:<payloadPath>} / {@code partner:context} (default {@code partnerId}). Unscoped reads filtered by
 * the explicit organization.
 */
@Service
@ConditionalOnProperty(name = "app.modules.communication", havingValue = "true")
public class RecipientResolverService {

    private static final Logger log = LoggerFactory.getLogger(RecipientResolverService.class);

    /** A resolved recipient: kind user | partner | external with exactly one id set. */
    public record Recipient(String kind, String userId, String partnerId) {

        static Recipient user(String id) {
            return new Recipient("user", id, null);
        }

        static Recipient partner(String id) {
            return new Recipient("partner", null, id);
        }
    }

    private final JdbcClient jdbc;

    public RecipientResolverService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Recipient> resolve(String organizationId, String resolver, Map<String, Object> payload) {
        int colon = resolver.indexOf(':');
        String scheme = colon < 0 ? resolver : resolver.substring(0, colon);
        String arg = colon < 0 ? "" : resolver.substring(colon + 1);
        switch (scheme) {
            case "permission":
                return byPermission(organizationId, arg);
            case "role":
                return byRole(organizationId, arg);
            case "user": {
                Object id = TemplateRender.resolvePath(payload, arg.isEmpty() ? "userId" : arg);
                return id instanceof String s && !s.isEmpty() ? List.of(Recipient.user(s)) : List.of();
            }
            case "partner": {
                String path = arg.isEmpty() || "context".equals(arg) ? "partnerId" : arg;
                Object id = TemplateRender.resolvePath(payload, path);
                return id instanceof String s && !s.isEmpty() ? List.of(Recipient.partner(s)) : List.of();
            }
            default:
                log.warn("unknown recipient resolver '{}'", resolver);
                return List.of();
        }
    }

    private List<Recipient> byPermission(String organizationId, String permission) {
        if (permission.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT u.id FROM "User" u WHERE u."organizationId" = ? AND EXISTS (
                          SELECT 1 FROM "_UserRoles" ur JOIN "Role" r ON r.id = ur."A"
                          WHERE ur."B" = u.id AND ? = ANY(r.permissions))""")
                .params(organizationId, permission)
                .query(String.class).list().stream().map(Recipient::user).toList();
    }

    private List<Recipient> byRole(String organizationId, String roleName) {
        if (roleName.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT u.id FROM "User" u WHERE u."organizationId" = ? AND EXISTS (
                          SELECT 1 FROM "_UserRoles" ur JOIN "Role" r ON r.id = ur."A"
                          WHERE ur."B" = u.id AND r.name = ?)""")
                .params(organizationId, roleName)
                .query(String.class).list().stream().map(Recipient::user).toList();
    }
}
