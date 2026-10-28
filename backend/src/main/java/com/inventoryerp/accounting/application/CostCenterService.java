package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.domain.CostCenter;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import com.inventoryerp.shared.web.PageResult;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cost centers (port of {@code cost-center.service.ts}), extended by ADR-S007 with a parent/child hierarchy, branch,
 * category, manager and validity dates. A parent must exist in the organisation and must not be the center itself
 * or one of its descendants.
 */
@Service
public class CostCenterService {

    private static final List<String> FIELDS = List.of("code", "name", "type", "isActive", "parentId", "branchId",
            "category", "description", "managerUserId", "validFrom", "validTo");

    private final EntityManager em;

    public CostCenterService(EntityManager em) {
        this.em = em;
    }

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(double page, double pageSize, String type) {
        int p = Double.isNaN(page) || page == 0 ? 1 : (int) page;
        int ps = Math.min(Double.isNaN(pageSize) || pageSize == 0 ? 50 : (int) pageSize, 200);
        boolean byType = type != null && !type.isEmpty();
        String where = byType ? " where c.type = :type" : "";
        var q = em.createQuery("select c from CostCenter c" + where + " order by c.code asc", CostCenter.class);
        var n = em.createQuery("select count(c) from CostCenter c" + where, Long.class);
        if (byType) {
            q.setParameter("type", type);
            n.setParameter("type", type);
        }
        List<CostCenter> rows = q.setFirstResult((p - 1) * ps).setMaxResults(ps).getResultList();
        return PageResult.of(EntityJson.list(rows), p, ps, n.getSingleResult());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        return EntityJson.of(require(id));
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> dto) {
        CostCenter c = new CostCenter();
        c.setOrganizationId(TenantContext.organizationId());
        c.setCode(dto.get("code") == null ? null : String.valueOf(dto.get("code")));
        c.setName(dto.get("name") == null ? null : String.valueOf(dto.get("name")));
        c.setType(dto.get("type") == null ? "cost" : String.valueOf(dto.get("type")));
        for (String f : List.of("parentId", "branchId", "category", "description", "managerUserId", "validFrom",
                "validTo")) {
            if (dto.get(f) != null) {
                EntityPatch.set(c, f, dto.get(f));
            }
        }
        assertCategory(dto.get("category"));
        assertParent(null, c.getParentId());
        em.persist(c);
        em.flush();
        em.refresh(c);
        return EntityJson.of(c);
    }

    /** Returns Prisma's {@code {count}}. */
    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> dto) {
        CostCenter c = require(id);
        if (dto.containsKey("parentId")) {
            assertParent(id, (String) dto.get("parentId"));
        }
        assertCategory(dto.get("category"));
        for (String f : FIELDS) {
            if (dto.containsKey(f)) {
                EntityPatch.set(c, f, dto.get(f));
            }
        }
        em.flush();
        return Map.of("count", 1);
    }

    @Transactional
    public void remove(String id) {
        CostCenter c = require(id);
        c.setDeletedAt(Instant.now());
        em.flush();
    }

    /** Every live center as a tree (roots first, children nested under {@code children}), ordered by code. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> tree() {
        List<CostCenter> all = em.createQuery("select c from CostCenter c order by c.code asc", CostCenter.class)
                .getResultList();
        Map<String, Map<String, Object>> byId = new java.util.LinkedHashMap<>();
        for (CostCenter c : all) {
            Map<String, Object> node = new java.util.LinkedHashMap<>(EntityJson.of(c));
            node.put("children", new java.util.ArrayList<Map<String, Object>>());
            byId.put(c.getId(), node);
        }
        List<Map<String, Object>> roots = new java.util.ArrayList<>();
        for (CostCenter c : all) {
            Map<String, Object> parent = c.getParentId() == null ? null : byId.get(c.getParentId());
            if (parent == null) {
                roots.add(byId.get(c.getId()));
            } else {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> kids = (List<Map<String, Object>>) parent.get("children");
                kids.add(byId.get(c.getId()));
            }
        }
        return roots;
    }

    private static final List<String> CATEGORIES = List.of("department", "branch", "project", "warehouse", "shipment",
            "vehicle", "other");

    private static void assertCategory(Object category) {
        if (category != null && !CATEGORIES.contains(String.valueOf(category))) {
            throw ApiException.badRequest("category must be one of the following values: " + String.join(", ", CATEGORIES));
        }
    }

    private void assertParent(String selfId, String parentId) {
        if (parentId == null || parentId.isEmpty()) {
            return;
        }
        String cursor = parentId;
        for (int depth = 0; cursor != null && depth < 64; depth++) {
            if (cursor.equals(selfId)) {
                throw ApiException.badRequest("A cost center cannot be its own parent or descendant");
            }
            CostCenter p = em.find(CostCenter.class, cursor);
            if (p == null || !TenantContext.organizationId().equals(p.getOrganizationId()) || p.getDeletedAt() != null) {
                throw ApiException.badRequest("Parent cost center not found");
            }
            cursor = p.getParentId();
        }
    }

    private CostCenter require(String id) {
        CostCenter c = em.createQuery("select c from CostCenter c where c.id = :id", CostCenter.class)
                .setParameter("id", id).getResultList().stream().findFirst().orElse(null);
        if (c == null) {
            throw ApiException.notFound("Cost center not found");
        }
        return c;
    }
}
