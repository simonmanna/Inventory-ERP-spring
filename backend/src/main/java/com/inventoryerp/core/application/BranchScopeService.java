package com.inventoryerp.core.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Branch scoping for transactional queries (port of {@code branch-scope.service.ts}) and the data-level user scope of
 * BR-1.
 *
 * <p>{@link #branchFilter()} keeps the Nest meaning of the active branch and limits nothing by itself. A real
 * restriction is an explicit grant in {@code UserDataScope}: no rows means the user is unrestricted and every caller
 * takes its unchanged path. {@code User.defaultBranchId} is not a restriction because users pick it themselves and HR
 * fills it in for existing staff. A restricted user sees the granted branches, their locations (through
 * {@code InventoryLocation.branchId}) and the granted locations with those locations' branches; a row without a branch
 * or location is outside the scope. POS and sync never consult this class.
 */
@Service
public class BranchScopeService {

    /** What the current user may reach; {@code restricted} false means everything. */
    public record DataScope(boolean restricted, Set<String> branchIds, Set<String> locationIds) {

        public static final DataScope OPEN = new DataScope(false, Set.of(), Set.of());

        public boolean allowsBranch(String branchId) {
            return !restricted || (branchId != null && branchIds.contains(branchId));
        }

        public boolean allowsLocation(String locationId) {
            return !restricted || (locationId != null && locationIds.contains(locationId));
        }

        /** For {@code col = ANY(?::text[])}. */
        public String[] branchArray() {
            return branchIds.toArray(String[]::new);
        }

        public String[] locationArray() {
            return locationIds.toArray(String[]::new);
        }
    }

    private final JdbcClient jdbc;
    private final AuditService audit;

    public BranchScopeService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** The branch the current user is working in, or {@code null}. Not an access limit: see {@link #scope()}. */
    public String branchFilter() {
        String userId = TenantContext.userId();
        if (userId == null) {
            return null;
        }
        return jdbc.sql("SELECT \"defaultBranchId\" FROM \"User\" WHERE id = ? AND \"organizationId\" = ?"
                        + " AND \"deletedAt\" IS NULL")
                .params(userId, TenantContext.organizationId())
                .query(String.class)
                .optional()
                .orElse(null);
    }

    /** The current user's data scope. Background work has no user and is unrestricted. */
    public DataScope scope() {
        String userId = TenantContext.userId();
        if (userId == null) {
            return DataScope.OPEN;
        }
        String org = TenantContext.organizationId();
        List<Map<String, Object>> rows = jdbc.sql("SELECT \"branchId\", \"locationId\" FROM \"UserDataScope\" WHERE"
                + " \"organizationId\" = ? AND \"userId\" = ?").params(org, userId).query().listOfRows();
        if (rows.isEmpty()) {
            return DataScope.OPEN;
        }
        LinkedHashSet<String> branches = new LinkedHashSet<>();
        LinkedHashSet<String> locations = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) {
            if (r.get("branchId") != null) {
                branches.add((String) r.get("branchId"));
            }
            if (r.get("locationId") != null) {
                locations.add((String) r.get("locationId"));
            }
        }
        if (!branches.isEmpty()) {
            locations.addAll(jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND \"branchId\""
                    + " = ANY(?::text[]) AND \"deletedAt\" IS NULL").params(org, branches.toArray(String[]::new))
                    .query(String.class).list());
        }
        if (!locations.isEmpty()) {
            branches.addAll(jdbc.sql("SELECT DISTINCT \"branchId\" FROM \"InventoryLocation\" WHERE \"organizationId\" = ?"
                    + " AND id = ANY(?::text[]) AND \"branchId\" IS NOT NULL").params(org,
                    locations.toArray(String[]::new)).query(String.class).list());
        }
        return new DataScope(true, Set.copyOf(branches), Set.copyOf(locations));
    }

    /** 403 when the current user may not reach this branch. */
    public void assertBranch(String branchId) {
        if (!scope().allowsBranch(branchId)) {
            throw ApiException.forbidden("This branch is outside your data access");
        }
    }

    /** Refuse a report that spans the whole organization for a restricted user. */
    public void assertUnrestricted() {
        if (scope().restricted()) {
            throw ApiException.forbidden("This report covers all branches and is outside your data access");
        }
    }

    /**
     * The branch to stamp on a new document. Unrestricted users keep {@code requested} as is. A restricted user's
     * document always lands in their scope, or the outcome is a 403.
     */
    public String documentBranch(String requested) {
        DataScope s = scope();
        if (!s.restricted()) {
            return requested;
        }
        if (requested != null && !requested.isEmpty()) {
            if (!s.allowsBranch(requested)) {
                throw ApiException.forbidden("This branch is outside your data access");
            }
            return requested;
        }
        String active = branchFilter();
        if (s.allowsBranch(active)) {
            return active;
        }
        return s.branchIds().stream().min(Comparator.naturalOrder())
                .orElseThrow(() -> ApiException.forbidden("Your data access has no branch"));
    }

    /** 400 unless the branch exists in this organization; null and blank pass. */
    public void assertBranchExists(String branchId) {
        if (branchId == null || branchId.isEmpty()) {
            return;
        }
        long n = jdbc.sql("SELECT count(*) FROM \"Branch\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS"
                + " NULL").params(branchId, TenantContext.organizationId()).query(Long.class).single();
        if (n == 0) {
            throw ApiException.badRequest("Branch not found");
        }
    }

    // ------------------------------------------------------------------ administration

    /** The grants of one user; both lists empty means unrestricted. */
    @Transactional(readOnly = true)
    public Map<String, Object> grants(String userId) {
        assertUser(userId);
        List<String> branches = new ArrayList<>();
        List<String> locations = new ArrayList<>();
        for (Map<String, Object> r : jdbc.sql("SELECT \"branchId\", \"locationId\" FROM \"UserDataScope\" WHERE"
                + " \"organizationId\" = ? AND \"userId\" = ?").params(TenantContext.organizationId(), userId).query()
                .listOfRows()) {
            if (r.get("branchId") != null) {
                branches.add((String) r.get("branchId"));
            }
            if (r.get("locationId") != null) {
                locations.add((String) r.get("locationId"));
            }
        }
        return Obj.of("userId", userId, "restricted", !branches.isEmpty() || !locations.isEmpty(), "branchIds", branches,
                "locationIds", locations);
    }

    /**
     * Replace a user's grants; two empty lists lift the restriction. Nobody changes their own scope, and a restricted
     * user changes nobody's: scope administration must not be a way to widen one's own access.
     */
    @Transactional
    public Map<String, Object> saveGrants(String userId, List<String> branchIds, List<String> locationIds) {
        assertUser(userId);
        if (Objects.equals(userId, TenantContext.userId()) || scope().restricted()) {
            throw ApiException.forbidden("You cannot change this data access");
        }
        String org = TenantContext.organizationId();
        Map<String, Object> before = grants(userId);
        List<String> branches = branchIds == null ? List.of() : List.copyOf(new LinkedHashSet<>(branchIds));
        List<String> locations = locationIds == null ? List.of() : List.copyOf(new LinkedHashSet<>(locationIds));
        branches.forEach(this::assertBranchExists);
        for (String l : locations) {
            long n = jdbc.sql("SELECT count(*) FROM \"InventoryLocation\" WHERE id = ? AND \"organizationId\" = ? AND"
                    + " \"deletedAt\" IS NULL").params(l, org).query(Long.class).single();
            if (n == 0) {
                throw ApiException.badRequest("Location not found");
            }
        }
        jdbc.sql("DELETE FROM \"UserDataScope\" WHERE \"organizationId\" = ? AND \"userId\" = ?").params(org, userId)
                .update();
        String by = TenantContext.userId();
        for (String b : branches) {
            jdbc.sql("INSERT INTO \"UserDataScope\" (id, \"organizationId\", \"userId\", \"branchId\", \"createdBy\")"
                    + " VALUES (?, ?, ?, ?, ?)").params(UUID.randomUUID().toString(), org, userId, b, by).update();
        }
        for (String l : locations) {
            jdbc.sql("INSERT INTO \"UserDataScope\" (id, \"organizationId\", \"userId\", \"locationId\", \"createdBy\")"
                    + " VALUES (?, ?, ?, ?, ?)").params(UUID.randomUUID().toString(), org, userId, l, by).update();
        }
        Map<String, Object> after = grants(userId);
        audit.recordInTx(AuditInput.of("UserDataScope", userId, "update", before, after));
        return after;
    }

    private void assertUser(String userId) {
        long n = jdbc.sql("SELECT count(*) FROM \"User\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(userId, TenantContext.organizationId()).query(Long.class).single();
        if (n == 0) {
            throw ApiException.notFound("User not found");
        }
    }
}
