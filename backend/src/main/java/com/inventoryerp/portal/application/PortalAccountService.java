package com.inventoryerp.portal.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.staff.RolesService;
import com.inventoryerp.kernel.staff.UsersService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Portal logins (ADR-S050): staff open an account for a customer; it is a {@code User} on the system role
 * {@value #ROLE_NAME} (permission {@code portal:customer} only) plus a {@code PortalAccount} row naming its partner.
 * {@link #current()} resolves the signed-in customer on every portal request, so disabling an account takes effect at
 * once, before the access token expires.
 */
@Service
public class PortalAccountService {

    static final String ROLE_NAME = "Customer Portal";

    /** Open a login for a customer; staff pass the first password on to the customer. */
    public record AccountInput(
            @NotBlank String partnerId,
            @NotBlank @Email @Size(max = 200) String email,
            @NotBlank @Size(min = 10, max = 128) String password,
            @NotBlank @Size(max = 64) String firstName,
            @Size(max = 64) String lastName) {}

    /** Set a new password; every session of the account ends. */
    public record PasswordInput(@NotBlank @Size(min = 10, max = 128) String password) {}

    /** The signed-in customer. */
    public record Customer(String accountId, String partnerId, String userId) {}

    private final JdbcClient jdbc;
    private final UsersService users;
    private final RolesService roles;
    private final AuditService audit;

    public PortalAccountService(JdbcClient jdbc, UsersService users, RolesService roles, AuditService audit) {
        this.jdbc = jdbc;
        this.users = users;
        this.roles = roles;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** The account of the signed-in user; 403 when it has none or it is disabled. */
    public Customer current() {
        Map<String, Object> a = SqlRows.one(jdbc.sql("SELECT id, \"partnerId\", \"userId\" FROM \"PortalAccount\" WHERE"
                + " \"organizationId\" = ? AND \"userId\" = ? AND status = 'active'").params(org(),
                TenantContext.userId()));
        if (a == null) {
            throw ApiException.forbidden("No active customer portal account");
        }
        return new Customer((String) a.get("id"), (String) a.get("partnerId"), (String) a.get("userId"));
    }

    /** Staff list: accounts with customer and login. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(String partnerId, String search, Integer page, Integer pageSize) {
        int p = page == null || page < 1 ? 1 : page;
        int size = pageSize == null ? 25 : Math.max(1, Math.min(200, pageSize));
        StringBuilder where = new StringBuilder(" WHERE a.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(org()));
        if (partnerId != null && !partnerId.isBlank()) {
            where.append(" AND a.\"partnerId\" = ?");
            params.add(partnerId);
        }
        if (search != null && !search.isBlank()) {
            String like = "%" + search.trim().replace("%", "\\%").replace("_", "\\_") + "%";
            where.append(" AND (p.name ILIKE ? OR u.email ILIKE ?)");
            params.add(like);
            params.add(like);
        }
        String from = " FROM \"PortalAccount\" a JOIN \"Partner\" p ON p.id = a.\"partnerId\""
                + " JOIN \"User\" u ON u.id = a.\"userId\"";
        long total = jdbc.sql("SELECT count(*)" + from + where).params(params).query(Long.class).single();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((p - 1) * size);
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT a.id, a.\"partnerId\", a.status, a.\"createdAt\","
                + " p.code AS \"partnerCode\", p.name AS \"partnerName\", u.id AS \"userId\", u.email, u.\"firstName\","
                + " u.\"lastName\", u.\"lastLoginAt\"" + from + where + " ORDER BY p.name, u.email LIMIT ? OFFSET ?")
                .params(pageParams));
        return PageResult.of(rows, p, size, total);
    }

    /** Customers to choose from (at most 20). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> customers(String search) {
        String like = "%" + (search == null ? "" : search.trim().replace("%", "\\%").replace("_", "\\_")) + "%";
        return SqlRows.list(jdbc.sql("SELECT id, code, name, email, phone FROM \"Partner\" WHERE \"organizationId\" = ?"
                + " AND \"isCustomer\" = true AND \"deletedAt\" IS NULL AND (name ILIKE ? OR code ILIKE ?) ORDER BY name"
                + " LIMIT 20").params(org(), like, like));
    }

    /** Opens a login for a customer; 409 when the e-mail is taken (from {@link UsersService#create}). */
    @Transactional
    public Map<String, Object> create(AccountInput in) {
        long customer = jdbc.sql("SELECT count(*) FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ? AND"
                + " \"isCustomer\" = true AND \"deletedAt\" IS NULL").params(in.partnerId(), org())
                .query(Long.class).single();
        if (customer == 0) {
            throw ApiException.badRequest("Customer not found");
        }
        Map<String, Object> user = users.create(new UsersService.CreateUser(in.email().trim(), in.password(),
                in.firstName().trim(), in.lastName(), true, List.of(portalRole())));
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"PortalAccount\" (id, \"organizationId\", \"partnerId\", \"userId\", status,"
                + " \"createdBy\", \"updatedAt\") VALUES (?, ?, ?, ?, 'active', ?, now())")
                .params(id, org(), in.partnerId(), user.get("id"), TenantContext.userId()).update();
        audit.recordInTx(AuditInput.of("PortalAccount", id, "create", null, Obj.of("partnerId", in.partnerId(),
                "userId", user.get("id"), "email", in.email())));
        return get(id);
    }

    /** Enables or disables an account and its login. */
    @Transactional
    public Map<String, Object> setActive(String id, boolean active) {
        Map<String, Object> a = get(id);
        String status = active ? "active" : "disabled";
        if (status.equals(a.get("status"))) {
            return a;
        }
        users.update((String) a.get("userId"), new UsersService.UpdateUser(null, null, null, active, null));
        jdbc.sql("UPDATE \"PortalAccount\" SET status = ?, \"updatedAt\" = now() WHERE id = ? AND \"organizationId\" = ?")
                .params(status, id, org()).update();
        audit.recordInTx(AuditInput.of("PortalAccount", id, "update", Obj.of("status", a.get("status")),
                Obj.of("status", status)));
        return get(id);
    }

    /** New password for an account; ends all its sessions. */
    @Transactional
    public void resetPassword(String id, String password) {
        users.resetPassword((String) get(id).get("userId"), password);
    }

    private Map<String, Object> get(String id) {
        Map<String, Object> a = SqlRows.one(jdbc.sql("""
                SELECT a.id, a."partnerId", a.status, a."createdAt", p.code AS "partnerCode", p.name AS "partnerName",
                       u.id AS "userId", u.email, u."firstName", u."lastName"
                FROM "PortalAccount" a JOIN "Partner" p ON p.id = a."partnerId" JOIN "User" u ON u.id = a."userId"
                WHERE a.id = ? AND a."organizationId" = ?""").params(id, org()));
        if (a == null) {
            throw ApiException.notFound("Portal account not found");
        }
        return a;
    }

    /** The organization's portal role, created on first use. */
    private String portalRole() {
        String id = jdbc.sql("SELECT id FROM \"Role\" WHERE \"organizationId\" = ? AND name = ? AND \"deletedAt\" IS NULL")
                .params(org(), ROLE_NAME).query(String.class).optional().orElse(null);
        if (id != null) {
            return id;
        }
        Map<String, Object> role = roles.create(new RolesService.CreateRole(ROLE_NAME,
                "Customers on the portal: their own orders, deliveries, invoices and statement.",
                List.of(Permissions.PORTAL__CUSTOMER), true));
        return (String) role.get("id");
    }
}
