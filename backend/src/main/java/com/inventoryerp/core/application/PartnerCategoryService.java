package com.inventoryerp.core.application;

import com.inventoryerp.core.domain.PartnerCategory;
import com.inventoryerp.kernel.crud.BaseCrudService;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Port of {@code partner-category.service.ts}: include {@code parent} + {@code _count {partners, children}}. */
@Service
public class PartnerCategoryService extends BaseCrudService<PartnerCategory> {

    public PartnerCategoryService(EntityManager em) {
        super(em, PartnerCategory.class);
    }

    @Override
    protected String entityName() {
        return "PartnerCategory";
    }

    @Override
    protected List<String> searchFields() {
        return List.of("name");
    }

    @Override
    protected List<Map<String, Object>> render(List<PartnerCategory> rows) {
        List<Object> ids = ids(rows);
        Map<Object, Long> items = countBy(em, "Partner", "categoryId", ids);
        Map<Object, Long> children = countBy(em, "PartnerCategory", "parentId", ids);
        return rows.stream().map(r -> {
            Map<String, Object> out = com.inventoryerp.shared.web.EntityJson.of(r);
            out.put("parent", one(em, PartnerCategory.class, r.getParentId()));
            Map<String, Object> count = new LinkedHashMap<>();
            count.put("partners", items.getOrDefault(r.getId(), 0L));
            count.put("children", children.getOrDefault(r.getId(), 0L));
            out.put("_count", count);
            return out;
        }).toList();
    }
}
