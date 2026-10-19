package com.inventoryerp.core.application;

import com.inventoryerp.core.domain.ProductCategory;
import com.inventoryerp.kernel.crud.BaseCrudService;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Port of {@code product-category.service.ts}: include {@code parent} + {@code _count {products, children}}. */
@Service
public class ProductCategoryService extends BaseCrudService<ProductCategory> {

    public ProductCategoryService(EntityManager em) {
        super(em, ProductCategory.class);
    }

    @Override
    protected String entityName() {
        return "ProductCategory";
    }

    @Override
    protected List<String> searchFields() {
        return List.of("name");
    }

    @Override
    protected List<Map<String, Object>> render(List<ProductCategory> rows) {
        List<Object> ids = ids(rows);
        Map<Object, Long> items = countBy(em, "Product", "categoryId", ids);
        Map<Object, Long> children = countBy(em, "ProductCategory", "parentId", ids);
        return rows.stream().map(r -> {
            Map<String, Object> out = com.inventoryerp.shared.web.EntityJson.of(r);
            out.put("parent", one(em, ProductCategory.class, r.getParentId()));
            Map<String, Object> count = new LinkedHashMap<>();
            count.put("products", items.getOrDefault(r.getId(), 0L));
            count.put("children", children.getOrDefault(r.getId(), 0L));
            out.put("_count", count);
            return out;
        }).toList();
    }
}
