package com.inventoryerp.fixedasset.application;

import com.inventoryerp.fixedasset.domain.AssetCategory;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asset categories (port of {@code asset-category.service.ts}): generic CRUD with
 * {@code include: {parent: true, _count: {select: {assets: true}}}}, the root tree and a delete guarded by live assets.
 */
@Service
public class AssetCategoryService extends BaseCrudService<AssetCategory> {

    static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";

    public AssetCategoryService(EntityManager em) {
        super(em, AssetCategory.class);
    }

    @Override
    protected String entityName() {
        return "AssetCategory";
    }

    @Override
    protected List<String> searchFields() {
        return List.of("name");
    }

    @Override
    protected List<Map<String, Object>> render(List<AssetCategory> rows) {
        Map<Object, Long> counts = countBy(em, "Asset", "categoryId", ids(rows));
        List<Map<String, Object>> out = new ArrayList<>();
        for (AssetCategory c : rows) {
            Map<String, Object> m = EntityJson.of(c);
            m.put("parent", one(em, AssetCategory.class, c.getParentId()));
            m.put("_count", count(counts, c.getId()));
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> count(Map<Object, Long> counts, String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("assets", counts.getOrDefault(id, 0L));
        return m;
    }

    /** Root categories (name asc) with their children; each carries {@code _count.assets}. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> findAll() {
        List<AssetCategory> roots = em.createQuery(
                        "select c from AssetCategory c where c.parentId is null order by c.name asc", AssetCategory.class)
                .getResultList();
        @SuppressWarnings("unchecked")
        List<AssetCategory> children = roots.isEmpty() ? List.of() : em.createNativeQuery(
                        "SELECT * FROM \"AssetCategory\" WHERE \"parentId\" IN (:ids)", AssetCategory.class)
                .setParameter("ids", ids(roots))
                .getResultList();
        List<Object> all = new ArrayList<>(ids(roots));
        all.addAll(ids(children));
        Map<Object, Long> counts = countBy(em, "Asset", "categoryId", all);
        List<Map<String, Object>> out = new ArrayList<>();
        for (AssetCategory r : roots) {
            Map<String, Object> m = EntityJson.of(r);
            List<Map<String, Object>> kids = new ArrayList<>();
            for (AssetCategory c : children) {
                if (r.getId().equals(c.getParentId())) {
                    Map<String, Object> k = EntityJson.of(c);
                    k.put("_count", count(counts, c.getId()));
                    kids.add(k);
                }
            }
            m.put("children", kids);
            m.put("_count", count(counts, r.getId()));
            out.add(m);
        }
        return out;
    }

    /** Patch (explicit nulls clear), 404 when missing. */
    @Override
    @Transactional
    public Map<String, Object> update(String id, Record dto) {
        AssetCategory row = find(id);
        if (row == null) {
            throw ApiException.notFound("AssetCategory " + id + " not found");
        }
        EntityPatch.apply(row, dto);
        em.flush();
        return render(row);
    }

    /** Soft delete; refused (404, as in Nest) while live assets reference the category. */
    @Override
    @Transactional
    public void remove(String id) {
        long count = ((Number) em.createNativeQuery("SELECT count(*) FROM \"Asset\" WHERE \"organizationId\" = ?1"
                        + " AND \"categoryId\" = ?2 AND \"deletedAt\" IS NULL")
                .setParameter(1, TenantContext.organizationId())
                .setParameter(2, id)
                .getSingleResult()).longValue();
        if (count > 0) {
            throw ApiException.notFound("Cannot delete category with " + count + " assigned assets");
        }
        int res = em.createNativeQuery("UPDATE \"AssetCategory\" SET \"deletedAt\" = " + NOW + ", \"updatedAt\" = " + NOW
                        + " WHERE id = ?1 AND \"organizationId\" = ?2 AND \"deletedAt\" IS NULL")
                .setParameter(1, id)
                .setParameter(2, TenantContext.organizationId())
                .executeUpdate();
        if (res == 0) {
            throw ApiException.notFound("AssetCategory " + id + " not found");
        }
    }
}
