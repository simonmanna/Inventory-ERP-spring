package com.inventoryerp.inventory.application;

import com.inventoryerp.inventory.domain.InventoryLocation;
import com.inventoryerp.inventory.domain.LocationType;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.shared.persistence.DbEnum;
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

    public LocationService(EntityManager em) {
        super(em, InventoryLocation.class);
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

    /** List with the optional {@code type} filter. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(PageQuery query, String type) {
        if (type == null) {
            return list(query);
        }
        LocationType t = DbEnum.fromValue(LocationType.class, type);
        return list(query, (cb, root, p) -> p.add(cb.equal(root.get("type"), t)));
    }
}
