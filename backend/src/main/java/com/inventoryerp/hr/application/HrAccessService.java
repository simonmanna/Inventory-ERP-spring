package com.inventoryerp.hr.application;

import static com.inventoryerp.hr.application.HrDb.org;
import static com.inventoryerp.hr.application.HrDb.user;

import com.inventoryerp.hr.domain.HrEmployee;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.domain.User;
import com.inventoryerp.kernel.staff.UsersService;
import com.inventoryerp.kernel.staff.UsersService.CreateUser;
import com.inventoryerp.kernel.staff.UsersService.UpdateUser;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The Employee ↔ User identity spine (port of {@code hr-access.service.ts}): the only writer of
 * {@code HrEmployee.userId}. Account creation, role changes and PINs delegate to {@link UsersService} so HR has no
 * private path into authorization. Never returns credentials.
 */
@Service
public class HrAccessService {

    private static final Logger log = LoggerFactory.getLogger(HrAccessService.class);

    private final HrDb hr;
    private final UsersService users;

    public HrAccessService(HrDb hr, UsersService users) {
        this.hr = hr;
        this.users = users;
    }

    /** {@code LinkUserDto} / {@code ProvisionUserDto} / {@code UpdateAccessDto} inputs. */
    public record Provision(String email, String password, String firstName, String lastName, List<String> roleIds,
            Boolean isActive) {}

    // ─── reads ───

    /** The "System Access" panel of the Employee 360 view. */
    public Map<String, Object> getAccess(String employeeId) {
        Map<String, Object> employee = findEmployee(employeeId);
        Object userId = employee.get("userId");
        Map<String, Object> u = userId == null ? null : hr.where(User.class).eq("id", userId).first();
        if (u == null) {
            return Obj.of("employee", summary(employee), "linked", false, "user", null);
        }
        List<Map<String, Object>> roles = roles((String) u.get("id"));
        LinkedHashSet<String> permissions = new LinkedHashSet<>();
        List<Map<String, Object>> roleRefs = new ArrayList<>();
        for (Map<String, Object> r : roles) {
            for (Object p : PosSql.list(r.get("permissions"))) {
                permissions.add(String.valueOf(p));
            }
            roleRefs.add(Obj.of("id", r.get("id"), "name", r.get("name")));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", u.get("id"));
        out.put("email", u.get("email"));
        out.put("firstName", u.get("firstName"));
        out.put("lastName", u.get("lastName"));
        out.put("isActive", u.get("isActive"));
        out.put("lastLoginAt", u.get("lastLoginAt"));
        out.put("defaultBranchId", u.get("defaultBranchId"));
        out.put("lockedUntil", u.get("lockedUntil"));
        out.put("roles", roleRefs);
        out.put("posAccess", permissions.stream().anyMatch(p -> p.startsWith("pos:")));
        out.put("hasPin", PosSql.truthy(u.get("pinHash")));
        return Obj.of("employee", summary(employee), "linked", true, "user", out);
    }

    /** Roles of a user through the {@code _UserRoles} relation (unfiltered include). */
    private List<Map<String, Object>> roles(String userId) {
        return SqlRows.list(hr.jdbc().sql("SELECT r.id, r.name, r.permissions FROM \"Role\" r JOIN \"_UserRoles\" ru"
                + " ON ru.\"A\" = r.id WHERE ru.\"B\" = ? ORDER BY r.id").param(userId));
    }

    /** Active accounts not yet attached to an employee ({@code employee: null}). */
    public Map<String, Object> listLinkableUsers(Map<String, String> query) {
        HrDb.Where w = hr.where(User.class).and("NOT EXISTS (SELECT 1 FROM \"HrEmployee\" e WHERE e.\"userId\""
                + " = \"User\".id)").eq("isActive", true).search(query.get("search"), "email", "firstName", "lastName");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> u : w.list("\"firstName\" ASC", HrDb.take(query.get("take"), 50, 200), 0)) {
            Map<String, Object> r = HrDb.pick(u, "id", "email", "firstName", "lastName", "isActive", "lastLoginAt");
            List<Map<String, Object>> roleRefs = new ArrayList<>();
            roles((String) u.get("id")).forEach(x -> roleRefs.add(Obj.of("id", x.get("id"), "name", x.get("name"))));
            r.put("roles", roleRefs);
            rows.add(r);
        }
        return HrDb.rows(rows, rows.size());
    }

    // ─── writes ───

    /** Attach an existing account to an employee. Idempotent for the same pair. */
    public Map<String, Object> linkUser(String employeeId, String userId) {
        String orgId = org();
        String actor = user();
        Map<String, Object> employee = findEmployee(employeeId);
        if (userId != null && userId.equals(employee.get("userId"))) {
            return getAccess(employeeId);
        }
        if (employee.get("userId") != null) {
            throw ApiException.conflict("This employee is already linked to a user account. Unlink it first.");
        }
        Map<String, Object> u = hr.where(User.class).eq("id", userId).first();
        if (u == null) {
            throw ApiException.notFound("User account not found in this organization");
        }
        Map<String, Object> claimed = hr.where(HrEmployee.class).eq("userId", userId).first();
        if (claimed != null) {
            throw ApiException.conflict("That account is already linked to employee " + claimed.get("employeeCode") + ".");
        }
        hr.inTx(() -> {
            hr.update(HrEmployee.class, employeeId, Obj.of("userId", userId, "updatedBy", actor));
            if (u.get("defaultBranchId") == null && employee.get("branchId") != null) {
                hr.where(User.class).eq("id", userId).update(Obj.of("defaultBranchId", employee.get("branchId"),
                        "updatedBy", actor));
            }
            hr.auditInTx(AuditInput.of("HrEmployee", employeeId, "assign", Obj.of("userId", null),
                    Obj.of("userId", userId, "email", u.get("email"), "organizationId", orgId)));
            return null;
        });
        return getAccess(employeeId);
    }

    /** Detach the account; never touches the user or its history. */
    public Map<String, Object> unlinkUser(String employeeId) {
        String actor = user();
        Map<String, Object> employee = findEmployee(employeeId);
        if (employee.get("userId") == null) {
            throw ApiException.badRequest("This employee has no linked user account");
        }
        Object previous = employee.get("userId");
        hr.inTx(() -> {
            hr.update(HrEmployee.class, employeeId, Obj.of("userId", null, "updatedBy", actor));
            hr.auditInTx(AuditInput.of("HrEmployee", employeeId, "unassign", Obj.of("userId", previous),
                    Obj.of("userId", null)));
            return null;
        });
        return getAccess(employeeId);
    }

    /** Create a login through {@link UsersService} and link it; the account is removed again when linking fails. */
    public Map<String, Object> provisionUser(String employeeId, Provision dto) {
        Map<String, Object> employee = findEmployee(employeeId);
        if (employee.get("userId") != null) {
            throw ApiException.conflict(
                    "This employee already has a user account. Unlink it before provisioning another.");
        }
        Map<String, Object> created = users.create(new CreateUser(dto.email(), dto.password(), dto.firstName(),
                dto.lastName(), dto.isActive() == null ? Boolean.TRUE : dto.isActive(), dto.roleIds()));
        String createdId = (String) created.get("id");
        try {
            return linkUser(employeeId, createdId);
        } catch (RuntimeException err) {
            log.error("Provision for employee {} failed after creating user {}; rolling the account back.", employeeId,
                    createdId);
            try {
                users.remove(createdId);
            } catch (RuntimeException cleanup) {
                log.error("Rollback of user {} failed — remove it by hand: {}", createdId, cleanup.toString());
            }
            throw err;
        }
    }

    /** Change roles / enabled state on the linked account through {@link UsersService}. */
    public Map<String, Object> updateAccess(String employeeId, List<String> roleIds, Boolean isActive) {
        Map<String, Object> employee = findEmployee(employeeId);
        if (employee.get("userId") == null) {
            throw ApiException.badRequest("This employee has no linked user account. Link or provision one first.");
        }
        if (roleIds == null && isActive == null) {
            throw ApiException.badRequest("Provide roleIds and/or isActive");
        }
        users.update((String) employee.get("userId"), new UpdateUser(null, null, null, isActive, roleIds));
        return getAccess(employeeId);
    }

    public Map<String, Object> setPin(String employeeId, String pin) {
        String userId = linkedUserId(employeeId);
        users.setPin(userId, pin);
        return getAccess(employeeId);
    }

    public Map<String, Object> clearPin(String employeeId) {
        String userId = linkedUserId(employeeId);
        users.clearPin(userId);
        return getAccess(employeeId);
    }

    // ─── helpers ───

    private String linkedUserId(String employeeId) {
        Map<String, Object> employee = findEmployee(employeeId);
        if (employee.get("userId") == null) {
            throw ApiException.badRequest("This employee has no linked user account. Link or provision one first.");
        }
        return (String) employee.get("userId");
    }

    private Map<String, Object> findEmployee(String employeeId) {
        Map<String, Object> employee = hr.byId(HrEmployee.class, employeeId);
        if (employee == null) {
            throw ApiException.notFound("Employee not found");
        }
        return employee;
    }

    private static Map<String, Object> summary(Map<String, Object> e) {
        return HrDb.pick(e, "id", "employeeCode", "firstName", "lastName", "isActive");
    }
}
