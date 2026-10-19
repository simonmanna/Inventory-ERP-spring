package com.inventoryerp.kernel.audit;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.domain.AuditLog;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.EntityJson;
import com.inventoryerp.shared.web.PageResult;
import jakarta.persistence.EntityManager;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Audit log listing: page (default 1), pageSize (default 50, max 200), optional entity filter. */
@Service
public class AuditLogQueries {

    private final EntityManager em;

    public AuditLogQueries(EntityManager em) {
        this.em = em;
    }

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(String page, String pageSize, String entity) {
        int p = JsNumbers.orDefault(page, 1);
        int ps = Math.min(JsNumbers.orDefault(pageSize, 50), 200);
        String orgId = TenantContext.organizationId();
        String where = " where a.organizationId = :org" + (entity != null && !entity.isEmpty() ? " and a.entity = :entity" : "");
        var q = em.createQuery("select a from AuditLog a" + where + " order by a.createdAt desc", AuditLog.class)
                .setParameter("org", orgId);
        var c = em.createQuery("select count(a) from AuditLog a" + where, Long.class).setParameter("org", orgId);
        if (entity != null && !entity.isEmpty()) {
            q.setParameter("entity", entity);
            c.setParameter("entity", entity);
        }
        var rows = q.setFirstResult(Math.max(0, (p - 1) * ps)).setMaxResults(ps).getResultList();
        return PageResult.of(EntityJson.list(rows), p, ps, c.getSingleResult());
    }
}
