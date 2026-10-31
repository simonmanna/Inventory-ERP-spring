package com.inventoryerp.inventory.application;

import com.inventoryerp.core.application.BranchScopeService;
import com.inventoryerp.core.application.BranchScopeService.DataScope;
import com.inventoryerp.inventory.domain.InventoryLocation;
import com.inventoryerp.inventory.domain.LocationType;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.shared.persistence.DbEnum;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageQuery;
import com.inventoryerp.shared.web.PageResult;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Inventory locations CRUD (port of {@code location.service.ts}): searchable by code/name, ordered by name. */
@Service
public class LocationService extends BaseCrudService<InventoryLocation> {

    private final BranchScopeService branches;

    public LocationService(EntityManager em, BranchScopeService branches) {
        super(em, InventoryLocation.class);
        this.branches = branches;
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

    @Override
    @Transactional
    public Map<String, Object> update(String id, Record dto) {
        assertReachable(id);
        Map<String, Object> data = EntityPatch.data(dto);
        if (data.containsKey("branchId")) {
            assertBranch(data.get("branchId"), false);
        }
        return super.update(id, dto);
    }

    @Override
    @Transactional
    public void remove(String id) {
        assertReachable(id);
        super.remove(id);
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
