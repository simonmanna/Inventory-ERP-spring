package com.inventoryerp.intercompany.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.auth.AuthService;
import com.inventoryerp.kernel.auth.UserAccounts;
import com.inventoryerp.kernel.auth.UserAccounts.UserWithRoles;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.InstallWide;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Company groups (ADR-S028 §1) and company switching (§2). A group is install-wide: the parent organization and its
 * subsidiaries. Only the parent manages it, and a company joins only when the caller administers it too (an active
 * user with the same e-mail holding {@code intercompany:manage} there).
 */
@Service
public class CompanyGroupService {

    /** A member company of a group. */
    public record Member(String organizationId, String code, String name, String currencyCode,
            BigDecimal ownershipPercent, boolean parent) {}

    /** The caller's group: id, name, parent, currency and members (parent first). */
    public record Group(String id, String name, String parentOrganizationId, String currencyCode, List<Member> members) {

        public Optional<Member> member(String organizationId) {
            return members.stream().filter(m -> m.organizationId().equals(organizationId)).findFirst();
        }

        public boolean contains(String organizationId) {
            return member(organizationId).isPresent();
        }
    }

    private final JdbcClient jdbc;
    private final UserAccounts users;
    private final AuthService auth;
    private final AuditService audit;
    private final InstallWide installWide;

    public CompanyGroupService(JdbcClient jdbc, UserAccounts users, AuthService auth, AuditService audit,
            InstallWide installWide) {
        this.jdbc = jdbc;
        this.users = users;
        this.auth = auth;
        this.audit = audit;
        this.installWide = installWide;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /**
     * The group of {@code organizationId}, if it belongs to one. The group tables have no tenant and are read in the
     * caller's transaction (so a change made in it is seen); the members' organization rows are other tenants' and are
     * read without a tenant.
     */
    public Optional<Group> groupOf(String organizationId) {
        Map<String, Object> g = SqlRows.one(jdbc.sql("SELECT g.id, g.name, g.\"parentOrganizationId\","
                + " g.\"currencyCode\" FROM \"CompanyGroup\" g JOIN \"CompanyGroupMember\" m ON m.\"groupId\" = g.id"
                + " WHERE m.\"memberOrganizationId\" = ?").param(organizationId));
        if (g == null) {
            return Optional.empty();
        }
        String parent = (String) g.get("parentOrganizationId");
        List<Map<String, Object>> rows = new ArrayList<>(SqlRows.list(jdbc.sql("SELECT \"memberOrganizationId\", \"ownershipPercent\""
                + " FROM \"CompanyGroupMember\" WHERE \"groupId\" = ?").param(g.get("id"))));
        String[] ids = rows.stream().map(r -> (String) r.get("memberOrganizationId")).toArray(String[]::new);
        Map<String, Map<String, Object>> orgs = new java.util.HashMap<>();
        installWide.run(() -> SqlRows.list(jdbc.sql("SELECT id, code, name, \"currencyCode\" FROM \"Organization\""
                + " WHERE id = ANY (?)").param(ids))).forEach(o -> orgs.put((String) o.get("id"), o));
        rows.sort(java.util.Comparator.comparing(r -> String.valueOf(orgs.getOrDefault(
                (String) r.get("memberOrganizationId"), Map.of()).get("code"))));
        List<Member> members = new ArrayList<>();
        for (Map<String, Object> mr : rows) {
            String id = (String) mr.get("memberOrganizationId");
            Map<String, Object> r = orgs.getOrDefault(id, Map.of());
            Member m = new Member(id, (String) r.get("code"), (String) r.get("name"),
                    (String) r.get("currencyCode"), SqlRows.dec(mr, "ownershipPercent"), id.equals(parent));
            if (m.parent()) {
                members.addFirst(m);
            } else {
                members.add(m);
            }
        }
        return Optional.of(new Group((String) g.get("id"), (String) g.get("name"), parent,
                (String) g.get("currencyCode"), members));
    }

    /** The caller's group or 404. */
    public Group requireGroup() {
        return groupOf(org()).orElseThrow(() -> ApiException.notFound("This company is not in a company group"));
    }

    /** The caller's group, which the caller must be the parent of. */
    public Group requireParent() {
        Group g = requireGroup();
        if (!g.parentOrganizationId().equals(org())) {
            throw ApiException.forbidden("Only the parent company manages the company group");
        }
        return g;
    }

    /** The counterpart must be another member of the caller's group. */
    public Group requireSameGroup(String counterpartOrganizationId) {
        Group g = requireGroup();
        if (counterpartOrganizationId == null || counterpartOrganizationId.equals(org())
                || !g.contains(counterpartOrganizationId)) {
            throw ApiException.badRequest("The company is not another member of your company group");
        }
        return g;
    }

    /** {@code GET /intercompany/group}: the group, or {@code null} when the company is not in one. */
    public Map<String, Object> current() {
        return groupOf(org()).map(CompanyGroupService::render).orElse(null);
    }

    @Transactional
    public Map<String, Object> create(String name) {
        if (groupOf(org()).isPresent()) {
            throw ApiException.conflict("This company already belongs to a company group");
        }
        String currency = jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(org())
                .query(String.class).single();
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"CompanyGroup\" (id, name, \"parentOrganizationId\", \"currencyCode\", \"createdBy\")"
                + " VALUES (?, ?, ?, ?, ?)").params(id, name.trim(), org(), currency, TenantContext.userId()).update();
        jdbc.sql("INSERT INTO \"CompanyGroupMember\" (id, \"groupId\", \"memberOrganizationId\", \"ownershipPercent\","
                + " \"addedBy\") VALUES (?, ?, ?, 100, ?)")
                .params(UUID.randomUUID().toString(), id, org(), TenantContext.userId()).update();
        audit.recordInTx(AuditInput.of("CompanyGroup", id, "create", null, Obj.of("name", name)));
        return current();
    }

    @Transactional
    public Map<String, Object> rename(String name) {
        Group g = requireParent();
        jdbc.sql("UPDATE \"CompanyGroup\" SET name = ?, \"updatedAt\" = now() WHERE id = ?").params(name.trim(), g.id())
                .update();
        audit.recordInTx(AuditInput.of("CompanyGroup", g.id(), "update", Obj.of("name", g.name()), Obj.of("name", name)));
        return current();
    }

    @Transactional
    public Map<String, Object> delete() {
        Group g = requireParent();
        if (g.members().size() > 1) {
            throw ApiException.badRequest("Remove every subsidiary before deleting the company group");
        }
        jdbc.sql("DELETE FROM \"CompanyGroup\" WHERE id = ?").param(g.id()).update();
        audit.recordInTx(AuditInput.of("CompanyGroup", g.id(), "delete"));
        return Obj.of("deleted", true);
    }

    /** Add a company by its code; the caller must administer it (§1 consent rule). */
    @Transactional
    public Map<String, Object> addMember(String organizationCode, BigDecimal ownershipPercent) {
        Group g = requireParent();
        Map<String, Object> target = installWide.run(() -> SqlRows.one(jdbc.sql("SELECT id, status::text AS status"
                + " FROM \"Organization\" WHERE code = ? AND \"deletedAt\" IS NULL").param(organizationCode)));
        if (target == null || !"active".equals(target.get("status"))) {
            throw ApiException.notFound("Company not found");
        }
        String targetId = (String) target.get("id");
        if (g.contains(targetId)) {
            throw ApiException.conflict("The company is already in this group");
        }
        if (groupOf(targetId).isPresent()) {
            throw ApiException.conflict("The company already belongs to another company group");
        }
        UserWithRoles twin = twinUser(targetId).orElse(null);
        if (twin == null || !twin.permissions().contains(Permissions.INTERCOMPANY__MANAGE)) {
            throw ApiException.forbidden("You need an active account with intercompany:manage in that company to add"
                    + " it to the group");
        }
        jdbc.sql("INSERT INTO \"CompanyGroupMember\" (id, \"groupId\", \"memberOrganizationId\", \"ownershipPercent\","
                + " \"addedBy\") VALUES (?, ?, ?, ?, ?)").params(UUID.randomUUID().toString(), g.id(), targetId,
                percent(ownershipPercent), TenantContext.userId()).update();
        audit.recordInTx(AuditInput.of("CompanyGroup", g.id(), "assign", null, Obj.of("organizationId", targetId,
                "ownershipPercent", percent(ownershipPercent))));
        return current();
    }

    @Transactional
    public Map<String, Object> updateMember(String organizationId, BigDecimal ownershipPercent) {
        Group g = requireParent();
        Member m = g.member(organizationId).orElseThrow(() -> ApiException.notFound("Member company not found"));
        if (m.parent()) {
            throw ApiException.badRequest("The parent company is always owned 100%");
        }
        jdbc.sql("UPDATE \"CompanyGroupMember\" SET \"ownershipPercent\" = ? WHERE \"groupId\" = ? AND"
                + " \"memberOrganizationId\" = ?").params(percent(ownershipPercent), g.id(), organizationId).update();
        audit.recordInTx(AuditInput.of("CompanyGroup", g.id(), "update", Obj.of("ownershipPercent",
                m.ownershipPercent()), Obj.of("organizationId", organizationId, "ownershipPercent",
                percent(ownershipPercent))));
        return current();
    }

    @Transactional
    public Map<String, Object> removeMember(String organizationId) {
        Group g = requireParent();
        Member m = g.member(organizationId).orElseThrow(() -> ApiException.notFound("Member company not found"));
        if (m.parent()) {
            throw ApiException.badRequest("The parent company cannot leave its group; delete the group instead");
        }
        jdbc.sql("DELETE FROM \"CompanyGroupMember\" WHERE \"groupId\" = ? AND \"memberOrganizationId\" = ?")
                .params(g.id(), organizationId).update();
        audit.recordInTx(AuditInput.of("CompanyGroup", g.id(), "unassign", Obj.of("organizationId",
                organizationId), null));
        return current();
    }

    // ---- §2 company switching ------------------------------------------------------------------------------------

    /** The caller's active, unlocked account (same e-mail) in another company. */
    public Optional<UserWithRoles> twinUser(String organizationId) {
        String email = callerEmail();
        if (email == null) {
            return Optional.empty();
        }
        return installWide.run(() -> users.byEmail(organizationId, email, true))
                .filter(u -> u.lockedUntil() == null || !u.lockedUntil().isAfter(Instant.now()));
    }

    private String callerEmail() {
        String userId = TenantContext.userId();
        if (userId == null) {
            return null;
        }
        return jdbc.sql("SELECT email FROM \"User\" WHERE id = ? AND \"organizationId\" = ?").params(userId, org())
                .query(String.class).optional().orElse(null);
    }

    /** {@code GET /intercompany/companies}: the current company first, then the members the caller can switch to. */
    public List<Map<String, Object>> companies() {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> self = SqlRows.one(jdbc.sql("SELECT id, code, name, \"currencyCode\" FROM \"Organization\""
                + " WHERE id = ?").param(org()));
        out.add(Obj.of("organizationId", self.get("id"), "code", self.get("code"), "name", self.get("name"),
                "currencyCode", self.get("currencyCode"), "current", true));
        Optional<Group> g = groupOf(org());
        if (g.isEmpty()) {
            return out;
        }
        for (Member m : g.get().members()) {
            if (m.organizationId().equals(org()) || twinUser(m.organizationId()).isEmpty()) {
                continue;
            }
            out.add(Obj.of("organizationId", m.organizationId(), "code", m.code(), "name", m.name(), "currencyCode",
                    m.currencyCode(), "current", false));
        }
        return out;
    }

    /** {@code POST /intercompany/companies/:organizationId/switch}: tokens for the twin account (no transaction). */
    public Map<String, Object> switchTo(String organizationId, AuthService.RequestInfo req) {
        if (organizationId.equals(org())) {
            throw ApiException.badRequest("You are already signed in to this company");
        }
        Group g = groupOf(org()).orElse(null);
        if (g == null || !g.contains(organizationId)) {
            throw ApiException.forbidden("The company is not in your company group");
        }
        UserWithRoles twin = twinUser(organizationId)
                .orElseThrow(() -> ApiException.forbidden("You have no active account in this company"));
        if (hasMfa(twin) && !callerHasMfa()) {
            throw ApiException.forbidden("Sign in to this company directly: it requires MFA");
        }
        audit.record(AuditInput.of("User", TenantContext.userId(), "login", null,
                Obj.of("targetOrganizationId", organizationId)));
        return auth.issueTokensForSwitch(organizationId, twin.id(), req);
    }

    private static boolean hasMfa(UserWithRoles u) {
        return u.mfaSecret() != null && !u.mfaSecret().isEmpty();
    }

    private boolean callerHasMfa() {
        return jdbc.sql("SELECT coalesce(\"mfaSecret\", '') <> '' FROM \"User\" WHERE id = ? AND \"organizationId\" = ?")
                .params(TenantContext.userId(), org()).query(Boolean.class).optional().orElse(false);
    }

    private static BigDecimal percent(BigDecimal v) {
        return (v == null ? new BigDecimal("100") : v).setScale(4, RoundingMode.HALF_UP);
    }

    static Map<String, Object> render(Group g) {
        List<Map<String, Object>> members = g.members().stream().map(m -> Obj.of("organizationId", m.organizationId(),
                "code", m.code(), "name", m.name(), "currencyCode", m.currencyCode(), "ownershipPercent",
                m.ownershipPercent().stripTrailingZeros().toPlainString(), "isParent", m.parent())).toList();
        return Obj.of("id", g.id(), "name", g.name(), "parentOrganizationId", g.parentOrganizationId(), "currencyCode",
                g.currencyCode(), "members", members);
    }
}
