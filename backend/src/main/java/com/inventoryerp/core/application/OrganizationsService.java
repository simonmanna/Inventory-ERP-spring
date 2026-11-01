package com.inventoryerp.core.application;

import com.inventoryerp.kernel.auth.OneTimeTokenService;
import com.inventoryerp.kernel.auth.PasswordService;
import com.inventoryerp.kernel.common.InventoryPostingRuleDefaults;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.kernel.notifications.NotificationsService.SendInput;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tenant self-service (port of {@code organizations.service.ts}): public bootstrap of a new organization
 * with its admin user, invite acceptance, org settings and user invites. Bootstrap seeds the accounting
 * core (via {@link AccountingBootstrap}), permissions, currencies, the Administrator role, UoM, a no-tax
 * rate, the head-office branch, default inventory posting rules and the current fiscal year.
 */
@Service
public class OrganizationsService {

    private static final Pattern CODE = Pattern.compile("^[a-z0-9-]{2,32}$", Pattern.CASE_INSENSITIVE);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Bootstrap body. */
    public record BootstrapInput(String organizationCode, String organizationName, String timezone,
            String currencyCode, String adminEmail, String adminFirstName, String adminLastName, String adminPassword) {}

    private record Currency(String code, String symbol, String name, int decimalPlaces) {}

    private static final List<Currency> CURRENCIES = List.of(
            new Currency("USD", "$", "US Dollar", 2),
            new Currency("EUR", "€", "Euro", 2),
            new Currency("GBP", "£", "British Pound", 2),
            new Currency("UGX", "USh", "Ugandan Shilling", 0),
            new Currency("KES", "KSh", "Kenyan Shilling", 2),
            new Currency("INR", "₹", "Indian Rupee", 2));

    private final JdbcClient jdbc;
    private final jakarta.persistence.EntityManager em;
    private final PasswordService passwords;
    private final OneTimeTokenService tokens;
    private final NotificationsService notifications;
    private final ObjectProvider<AccountingBootstrap> accountingBootstrap;
    private final TransactionTemplate tx;

    public OrganizationsService(JdbcClient jdbc, jakarta.persistence.EntityManager em, PasswordService passwords, OneTimeTokenService tokens,
            NotificationsService notifications, ObjectProvider<AccountingBootstrap> accountingBootstrap,
            PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.em = em;
        this.passwords = passwords;
        this.tokens = tokens;
        this.notifications = notifications;
        this.accountingBootstrap = accountingBootstrap;
        this.tx = new TransactionTemplate(txm);
    }

    /** Public bootstrap: create a tenant + bootstrap admin user (invite token when no password is given). */
    public Map<String, Object> bootstrap(BootstrapInput p) {
        if (!CODE.matcher(p.organizationCode()).matches()) {
            throw ApiException.badRequest("organizationCode must be 2-32 chars, letters/digits/hyphens");
        }
        boolean exists = jdbc.sql("SELECT count(*) FROM \"Organization\" WHERE code = ?")
                .param(p.organizationCode()).query(Long.class).single() > 0;
        if (exists) {
            throw ApiException.conflict("Organization '" + p.organizationCode() + "' already exists");
        }
        String passwordHash = passwords.hash(p.adminPassword() != null && !p.adminPassword().isEmpty()
                ? p.adminPassword() : randomPassword());

        record Created(String orgId, String code, String name, String userId, String email) {}
        String currency = p.currencyCode() == null ? "USD" : p.currencyCode();
        Created created = tx.execute(s -> {
            // The Organization row references Currency: the reference currencies must exist first (empty database).
            seedCurrencies();
            boolean known = jdbc.sql("SELECT count(*) FROM \"Currency\" WHERE code = ?").param(currency)
                    .query(Long.class).single() > 0;
            if (!known) {
                throw ApiException.badRequest("Unknown currency '" + currency + "'");
            }
            String orgId = UUID.randomUUID().toString();
            jdbc.sql("INSERT INTO \"Organization\" (id, code, name, \"currencyCode\", timezone, \"updatedAt\")"
                            + " VALUES (?, ?, ?, ?, ?, now())")
                    .params(orgId, p.organizationCode(), p.organizationName(), currency,
                            p.timezone() == null ? "UTC" : p.timezone())
                    .update();
            AccountingBootstrap accounting = accountingBootstrap.getIfAvailable();
            if (accounting != null) {
                accounting.seedOrganization(orgId);
            }
            seedAdminRoleAndMappings(orgId);
            seedPostingRules(orgId);

            String userId = UUID.randomUUID().toString();
            String email = p.adminEmail().toLowerCase();
            jdbc.sql("INSERT INTO \"User\" (id, \"organizationId\", email, \"passwordHash\", \"firstName\", \"lastName\","
                            + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, now())")
                    .params(userId, orgId, email, passwordHash, p.adminFirstName(), p.adminLastName())
                    .update();
            jdbc.sql("SELECT id FROM \"Role\" WHERE \"organizationId\" = ? AND name = 'Administrator'")
                    .param(orgId).query(String.class).optional()
                    .ifPresent(roleId -> jdbc.sql("INSERT INTO \"_UserRoles\" (\"A\", \"B\") VALUES (?, ?)")
                            .params(roleId, userId).update());
            int year = LocalDate.now(ZoneOffset.UTC).getYear();
            jdbc.sql("INSERT INTO \"FiscalPeriod\" (id, \"organizationId\", name, \"startDate\", \"endDate\", status,"
                            + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, 'open', now())")
                    .params(UUID.randomUUID().toString(), orgId, "FY" + year,
                            Timestamp.valueOf(LocalDate.of(year, 1, 1).atStartOfDay()),
                            Timestamp.valueOf(LocalDate.of(year, 12, 31).atStartOfDay()))
                    .update();
            return new Created(orgId, p.organizationCode(), p.organizationName(), userId, email);
        });

        String inviteToken = null;
        if (p.adminPassword() == null || p.adminPassword().isEmpty()) {
            inviteToken = tokens.issue("invite", created.userId(), created.orgId(), null, null);
            notifications.send(new SendInput(created.orgId(), created.userId(), "email", "auth",
                    "You have been invited to " + p.organizationName(),
                    "Use the link in this email to set your password and sign in.",
                    Map.of("token", inviteToken, "kind", "invite")));
        }

        Map<String, Object> organization = new LinkedHashMap<>();
        organization.put("id", created.orgId());
        organization.put("code", created.code());
        organization.put("name", created.name());
        Map<String, Object> adminUser = new LinkedHashMap<>();
        adminUser.put("id", created.userId());
        adminUser.put("email", created.email());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("organization", organization);
        out.put("adminUser", adminUser);
        if (inviteToken != null) {
            out.put("inviteToken", inviteToken);
        }
        return out;
    }

    /** Accept an invite token and set the password. */
    @Transactional
    public Map<String, Object> acceptInvite(String token, String newPassword) {
        OneTimeTokenService.Consumed consumed = tokens.consume(token, "invite");
        if (consumed == null) {
            throw ApiException.badRequest("Invalid or expired invite token");
        }
        jdbc.sql("UPDATE \"User\" SET \"passwordHash\" = ?, \"isActive\" = true, \"failedLoginCount\" = 0,"
                        + " \"lockedUntil\" = NULL, \"updatedAt\" = now() WHERE id = ?")
                .params(passwords.hash(newPassword), consumed.userId())
                .update();
        return Map.of("ok", true);
    }

    /** {@code GET /organizations/me}: the caller's organization row. */
    @Transactional(readOnly = true)
    public Map<String, Object> me(String organizationId) {
        Map<String, Object> org = organization(organizationId);
        if (org == null) {
            throw new IllegalStateException("Organization not found");
        }
        return org;
    }

    /** Update organization-level settings (only the keys present). */
    @Transactional
    public Map<String, Object> updateSettings(String name, String timezone, String currencyCode,
            Map<String, Object> settings, boolean hasName, boolean hasTimezone, boolean hasCurrency, boolean hasSettings) {
        String orgId = TenantContext.organizationId();
        if (hasName) {
            jdbc.sql("UPDATE \"Organization\" SET name = ?, \"updatedAt\" = now() WHERE id = ?").params(name, orgId).update();
        }
        if (hasTimezone) {
            jdbc.sql("UPDATE \"Organization\" SET timezone = ?, \"updatedAt\" = now() WHERE id = ?").params(timezone, orgId).update();
        }
        if (hasCurrency) {
            jdbc.sql("UPDATE \"Organization\" SET \"currencyCode\" = ?, \"updatedAt\" = now() WHERE id = ?")
                    .params(currencyCode, orgId).update();
        }
        if (hasSettings) {
            jdbc.sql("UPDATE \"Organization\" SET settings = ?::jsonb, \"updatedAt\" = now() WHERE id = ?")
                    .params(EntityJson.toJson(settings), orgId).update();
        }
        if (!hasName && !hasTimezone && !hasCurrency && !hasSettings) {
            jdbc.sql("UPDATE \"Organization\" SET \"updatedAt\" = now() WHERE id = ?").param(orgId).update();
        }
        return organization(orgId);
    }

    /** Set the caller's active branch ({@code null} = org-wide access). */
    @Transactional
    public Map<String, Object> setMyBranch(String userId, String defaultBranchId) {
        int n = jdbc.sql("UPDATE \"User\" SET \"defaultBranchId\" = ?, \"updatedAt\" = now() WHERE id = ?"
                        + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(defaultBranchId, userId, TenantContext.organizationId())
                .update();
        if (n == 0) {
            throw ApiException.notFound("No record was found for an update.");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("defaultBranchId", defaultBranchId);
        return out;
    }

    /**
     * Deviation (security, 2026-10-31): Nest accepted any {@code roleId}. The role must belong to the organization, and
     * a caller can only hand out permissions they hold, so an invite cannot create a user stronger than the inviter.
     */
    private void assertGrantable(String orgId, String roleId) {
        List<String[]> rows = jdbc.sql("SELECT permissions FROM \"Role\" WHERE id = ? AND \"organizationId\" = ?")
                .params(roleId, orgId)
                .query((rs, n) -> (String[]) rs.getArray("permissions").getArray())
                .list();
        if (rows.isEmpty()) {
            throw ApiException.badRequest("Role not found in this organization");
        }
        java.util.Set<String> held = new java.util.HashSet<>(TenantContext.permissions());
        for (String p : rows.getFirst()) {
            if (!held.contains(p)) {
                throw ApiException.forbidden("You cannot assign a role with permissions you do not hold");
            }
        }
    }

    /** Invite a new user to the current organization (invite token by email). */
    @Transactional
    public Map<String, Object> inviteUser(String email, String firstName, String lastName, String roleId) {
        String orgId = TenantContext.organizationId();
        String lower = email.toLowerCase();
        boolean exists = jdbc.sql("SELECT count(*) FROM \"User\" WHERE \"organizationId\" = ? AND email = ?")
                .params(orgId, lower).query(Long.class).single() > 0;
        if (exists) {
            throw ApiException.conflict("A user with this email already exists in your organization");
        }
        if (roleId != null && !roleId.isEmpty()) {
            assertGrantable(orgId, roleId);
        }
        String userId = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"User\" (id, \"organizationId\", email, \"passwordHash\", \"firstName\", \"lastName\","
                        + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, now())")
                .params(userId, orgId, lower, passwords.hash(randomPassword()), firstName, lastName)
                .update();
        if (roleId != null && !roleId.isEmpty()) {
            jdbc.sql("INSERT INTO \"_UserRoles\" (\"A\", \"B\") VALUES (?, ?)").params(roleId, userId).update();
        }
        String token = tokens.issue("invite", userId, orgId, null, null);
        notifications.send(new SendInput(orgId, userId, "email", "auth", "You have been invited",
                "Click the link to set your password.", Map.of("token", token, "kind", "invite")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", userId);
        out.put("email", lower);
        out.put("inviteToken", token);
        return out;
    }

    /** Live users of the organization with their roles, newest first (credential columns withheld). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listUsers() {
        String orgId = TenantContext.organizationId();
        List<com.inventoryerp.kernel.domain.User> users = em.createQuery(
                        "select u from User u where u.organizationId = :org and u.deletedAt is null order by u.createdAt desc",
                        com.inventoryerp.kernel.domain.User.class)
                .setParameter("org", orgId).getResultList();
        Map<String, List<Map<String, Object>>> roles = new LinkedHashMap<>();
        if (!users.isEmpty()) {
            jdbc.sql("SELECT ur.\"B\" AS uid, r.id FROM \"_UserRoles\" ur JOIN \"Role\" r ON r.id = ur.\"A\""
                            + " WHERE ur.\"B\" IN (:ids) ORDER BY r.name")
                    .param("ids", users.stream().map(com.inventoryerp.kernel.domain.User::getId).toList())
                    .query((rs, n) -> new String[] {rs.getString("uid"), rs.getString("id")})
                    .list()
                    .forEach(pair -> roles.computeIfAbsent(pair[0], k -> new java.util.ArrayList<>())
                            .add(EntityJson.of(em.find(com.inventoryerp.kernel.domain.Role.class, pair[1]))));
        }
        return users.stream().map(u -> {
            Map<String, Object> m = EntityJson.of(u);
            for (String secret : List.of("passwordHash", "mfaSecret", "mfaSecretIv", "mfaSecretTag", "pinHash")) {
                m.remove(secret);
            }
            m.put("roles", roles.getOrDefault(u.getId(), List.of()));
            return m;
        }).toList();
    }

    /** Deactivate a user of the current organization. */
    @Transactional
    public Map<String, Object> deactivate(String id) {
        int n = jdbc.sql("UPDATE \"User\" SET \"isActive\" = false, \"updatedAt\" = now() WHERE id = ?"
                        + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(id, TenantContext.organizationId())
                .update();
        if (n == 0) {
            throw ApiException.notFound("No record was found for an update.");
        }
        return Map.of("ok", true);
    }

    private Map<String, Object> organization(String id) {
        return jdbc.sql("SELECT * FROM \"Organization\" WHERE id = ?").param(id)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("code", rs.getString("code"));
                    m.put("name", rs.getString("name"));
                    m.put("timezone", rs.getString("timezone"));
                    m.put("currencyCode", rs.getString("currencyCode"));
                    m.put("status", rs.getString("status"));
                    m.put("settings", EntityJson.parseJson(rs.getString("settings")));
                    m.put("booksLockDate", instant(rs.getTimestamp("booksLockDate")));
                    m.put("requireFiscalPeriod", rs.getBoolean("requireFiscalPeriod"));
                    m.put("receiptHeader", EntityJson.parseJson(rs.getString("receiptHeader")));
                    m.put("receiptFooter", EntityJson.parseJson(rs.getString("receiptFooter")));
                    m.put("createdAt", instant(rs.getTimestamp("createdAt")));
                    m.put("updatedAt", instant(rs.getTimestamp("updatedAt")));
                    m.put("deletedAt", instant(rs.getTimestamp("deletedAt")));
                    return m;
                })
                .optional().orElse(null);
    }

    private static java.time.Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }

    private void seedCurrencies() {
        for (Currency c : CURRENCIES) {
            jdbc.sql("INSERT INTO \"Currency\" (id, code, symbol, name, \"decimalPlaces\", \"updatedAt\")"
                            + " VALUES (gen_random_uuid()::text, ?, ?, ?, ?, now()) ON CONFLICT (code) DO UPDATE SET"
                            + " symbol = EXCLUDED.symbol, name = EXCLUDED.name,"
                            + " \"decimalPlaces\" = EXCLUDED.\"decimalPlaces\", \"updatedAt\" = now()")
                    .params(c.code(), c.symbol(), c.name(), c.decimalPlaces())
                    .update();
        }
    }

    private void seedAdminRoleAndMappings(String orgId) {
        upsertPermissionCatalog(jdbc);
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, description, \"isSystem\", permissions, \"updatedAt\")"
                        + " VALUES (?, ?, 'Administrator', 'Full platform access', true, ?, now())")
                .params(UUID.randomUUID().toString(), orgId, Permissions.ALL.toArray(String[]::new))
                .update();
        UomSeed.seedUomCategories(jdbc, orgId);
        jdbc.sql("INSERT INTO \"Tax\" (id, \"organizationId\", name, code, type, rate, \"updatedAt\")"
                        + " VALUES (?, ?, 'No Tax', 'NONE', 'vat', 0, now())")
                .params(UUID.randomUUID().toString(), orgId).update();
        jdbc.sql("INSERT INTO \"Branch\" (id, \"organizationId\", code, name, \"updatedAt\")"
                        + " VALUES (?, ?, 'MAIN', 'Head Office', now())")
                .params(UUID.randomUUID().toString(), orgId).update();
    }

    /** Upsert every permission key (resource/action split on the last ':' or '.'). */
    static void upsertPermissionCatalog(JdbcClient jdbc) {
        for (String k : Permissions.ALL) {
            String separator = k.contains(":") ? ":" : ".";
            int idx = k.lastIndexOf(separator);
            String resource = idx >= 0 ? k.substring(0, idx) : k;
            String action = idx >= 0 ? k.substring(idx + 1) : k;
            jdbc.sql("INSERT INTO \"Permission\" (id, key, resource, action, \"createdAt\")"
                            + " VALUES (gen_random_uuid()::text, ?, ?, ?, now())"
                            + " ON CONFLICT (key) DO UPDATE SET resource = EXCLUDED.resource, action = EXCLUDED.action")
                    .params(k, resource, action).update();
        }
    }

    private void seedPostingRules(String orgId) {
        for (InventoryPostingRuleDefaults.Rule r : InventoryPostingRuleDefaults.RULES) {
            jdbc.sql("INSERT INTO \"InventoryPostingRule\" (id, \"organizationId\", \"movementType\", \"lineIndex\","
                            + " \"debitOrCredit\", \"accountSource\", \"accountMappingKey\", \"updatedAt\")"
                            + " VALUES (?, ?, ?::\"InventoryMovementType\", ?, ?, ?, ?, now())")
                    .params(UUID.randomUUID().toString(), orgId, r.movementType(), r.lineIndex(), r.debitOrCredit(),
                            r.accountSource(), r.accountMappingKey())
                    .update();
        }
    }

    /** 24 chars base64url. */
    private static String randomPassword() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
