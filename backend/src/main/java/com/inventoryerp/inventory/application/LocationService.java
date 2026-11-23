package com.inventoryerp.inventory.application;

import com.inventoryerp.core.application.BranchScopeService;
import com.inventoryerp.core.application.BranchScopeService.DataScope;
import com.inventoryerp.inventory.domain.InventoryLocation;
import com.inventoryerp.inventory.domain.LocationType;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.persistence.DbEnum;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageQuery;
import com.inventoryerp.shared.web.PageResult;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inventory locations CRUD (port of {@code location.service.ts}): searchable by code/name, ordered by name.
 *
 * <p>R2 master-data guards: a location is not deleted, deactivated, retyped or moved to another branch while it still
 * holds stock or open work (live transfers, bin stock, open counts, active reservations or rules), and the system
 * transit location is never deleted, deactivated or retyped.
 */
@Service
public class LocationService extends BaseCrudService<InventoryLocation> {

    private static final String TRANSIT = "transit";

    private final BranchScopeService branches;
    private final JdbcClient jdbc;

    public LocationService(EntityManager em, BranchScopeService branches, JdbcClient jdbc) {
        super(em, InventoryLocation.class);
        this.branches = branches;
        this.jdbc = jdbc;
    }

    @Override
    protected String entityName() {
        return "InventoryLocation";
    }

    @Override
    protected List<String> searchFields() {
        return List.of("code", "name");
    }

    @Override
    protected List<Sort> defaultOrderBy() {
        return List.of(Sort.asc("name"));
    }

    /** List with the optional {@code type} filter, limited to a restricted user's locations (BR-1). */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(PageQuery query, String type) {
        DataScope scope = branches.scope();
        LocationType t = type == null ? null : DbEnum.fromValue(LocationType.class, type);
        if (!scope.restricted()) {
            return t == null ? list(query) : list(query, (cb, root, p) -> p.add(cb.equal(root.get("type"), t)));
        }
        return list(query, (cb, root, p) -> {
            if (t != null) {
                p.add(cb.equal(root.get("type"), t));
            }
            p.add(scope.locationIds().isEmpty() ? cb.disjunction() : root.get("id").in(scope.locationIds()));
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        assertReachable(id);
        return super.findOne(id);
    }

    @Override
    @Transactional
    public Map<String, Object> create(Record dto) {
        assertBranch(EntityPatch.data(dto).get("branchId"), true);
        return super.create(dto);
    }

    /**
     * Update a location. Deactivating it, changing its type or moving it to another branch is refused while it holds
     * stock or open work; the transit location cannot be deactivated or retyped.
     */
    @Override
    @Transactional
    public Map<String, Object> update(String id, Record dto) {
        assertReachable(id);
        Map<String, Object> data = EntityPatch.data(dto);
        if (data.containsKey("branchId")) {
            assertBranch(data.get("branchId"), false);
        }
        Map<String, Object> current = current(id);
        if (current != null) {
            String type = String.valueOf(current.get("type"));
            boolean retype = data.containsKey("type") && data.get("type") != null
                    && !Objects.equals(String.valueOf(data.get("type")), type);
            boolean deactivate = data.containsKey("isActive") && Boolean.FALSE.equals(data.get("isActive"))
                    && Boolean.TRUE.equals(current.get("isActive"));
            boolean rebranch = data.containsKey("branchId")
                    && !Objects.equals(data.get("branchId") == null ? null : data.get("branchId").toString(),
                            current.get("branchId"));
            if (TRANSIT.equals(type) && (retype || deactivate)) {
                throw ApiException.badRequest("The transit location is system-managed; it cannot be "
                        + (retype ? "retyped" : "deactivated"));
            }
            if (retype) {
                assertEmpty(id, current, "retyped");
            }
            if (deactivate) {
                assertEmpty(id, current, "deactivated");
            }
            if (rebranch) {
                assertEmpty(id, current, "moved to another branch");
            }
        }
        return super.update(id, dto);
    }

    /** Soft-delete a location; refused for the transit location and while it holds stock or open work. */
    @Override
    @Transactional
    public void remove(String id) {
        assertReachable(id);
        Map<String, Object> current = current(id);
        if (current != null) {
            if (TRANSIT.equals(String.valueOf(current.get("type")))) {
                throw ApiException.badRequest("The transit location is system-managed; it cannot be deleted");
            }
            assertEmpty(id, current, "deleted");
        }
        super.remove(id);
    }

    private Map<String, Object> current(String id) {
        return SqlRows.one(jdbc.sql("SELECT id, code, type::text AS type, \"branchId\", \"isActive\" FROM"
                + " \"InventoryLocation\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(id, TenantContext.organizationId()));
    }

    /** 400 naming what still ties the location down: stock, open documents, reservations or rules. */
    private void assertEmpty(String id, Map<String, Object> location, String action) {
        String org = TenantContext.organizationId();
        List<String> held = new ArrayList<>();
        held(held, count("SELECT count(*) FROM \"StockItem\" WHERE \"organizationId\" = ? AND \"locationId\" = ?"
                + " AND quantity <> 0", org, id), "stock on hand");
        held(held, count("SELECT count(*) FROM \"WarehouseBinStock\" WHERE \"organizationId\" = ? AND"
                + " \"locationId\" = ? AND quantity <> 0", org, id), "stock in bins");
        held(held, count("SELECT count(*) FROM \"StockTransfer\" WHERE \"organizationId\" = ? AND (\"fromLocId\" = ?"
                + " OR \"toLocId\" = ? OR \"transitLocId\" = ?) AND status IN ('draft', 'pending', 'approved',"
                + " 'in_transit', 'partially_received')", org, id, id, id), "open transfers");
        held(held, count("SELECT count(*) FROM \"InventoryCountSession\" WHERE \"organizationId\" = ? AND"
                + " \"locationId\" = ? AND status = 'draft' AND \"deletedAt\" IS NULL", org, id), "open stock counts");
        held(held, count("SELECT count(*) FROM \"StockReservation\" WHERE \"organizationId\" = ? AND"
                + " \"locationId\" = ? AND status = 'active'", org, id), "active reservations");
        held(held, count("SELECT count(*) FROM \"InventoryReplenishmentRule\" WHERE \"organizationId\" = ? AND"
                + " (\"locationId\" = ? OR \"sourceLocationId\" = ?) AND \"isActive\" AND \"deletedAt\" IS NULL", org, id,
                id), "active replenishment rules");
        held(held, count("SELECT count(*) FROM \"ReorderRule\" WHERE \"organizationId\" = ? AND \"locationId\" = ? AND"
                + " \"isActive\"", org, id), "active reorder rules");
        if (!held.isEmpty()) {
            throw ApiException.badRequest("Location " + location.get("code") + " cannot be " + action
                    + " while it still has " + String.join(", ", held) + "; move or close them first");
        }
    }

    private long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    private static void held(List<String> held, long n, String what) {
        if (n > 0) {
            held.add(what + " (" + n + ")");
        }
    }

    private void assertReachable(String id) {
        if (!branches.scope().allowsLocation(id)) {
            throw ApiException.notFound(entityName() + " " + id + " not found");
        }
    }

    /** The branch must exist; a restricted user can only use their own, and a new location needs one to stay visible. */
    private void assertBranch(Object branchId, boolean creating) {
        String id = branchId == null ? null : branchId.toString();
        branches.assertBranchExists(id);
        if (id != null || (creating && branches.scope().restricted())) {
            branches.assertBranch(id);
        }
    }
}
