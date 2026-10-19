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

/** Cost centers (port of {@code cost-center.service.ts}). */
@Service
public class CostCenterService {

    private static final List<String> FIELDS = List.of("code", "name", "type", "isActive");

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
        em.persist(c);
        em.flush();
        em.refresh(c);
        return EntityJson.of(c);
    }

    /** Returns Prisma's {@code {count}}. */
    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> dto) {
        CostCenter c = require(id);
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

    private CostCenter require(String id) {
        CostCenter c = em.createQuery("select c from CostCenter c where c.id = :id", CostCenter.class)
                .setParameter("id", id).getResultList().stream().findFirst().orElse(null);
        if (c == null) {
            throw ApiException.notFound("Cost center not found");
        }
        return c;
    }
}
