package com.inventoryerp.core.application;

import com.inventoryerp.core.domain.UomCategory;
import com.inventoryerp.kernel.crud.BaseCrudService;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.stereotype.Service;

/** Port of {@code uom-category.service.ts} (generic CRUD). */
@Service
public class UomCategoryService extends BaseCrudService<UomCategory> {

    public UomCategoryService(EntityManager em) {
        super(em, UomCategory.class);
    }

    @Override
    protected String entityName() {
        return "UomCategory";
    }

    @Override
    protected List<String> searchFields() {
        return List.of("name");
    }
}
