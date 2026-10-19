package com.inventoryerp.core.application;

import com.inventoryerp.core.domain.FiscalPosition;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fiscal positions (port of {@code fiscal-position.service.ts}): org-scoped labels that adapt tax
 * treatment on sales invoices; codes unique per org; soft delete + restore.
 */
@Service
public class FiscalPositionService {

    /** Fields Prisma accepts from the (unvalidated) {@code Partial<UpsertFiscalPositionDto>} body. */
    private static final List<String> FIELDS = List.of("code", "name", "isActive", "sortOrder");

    private final EntityManager em;

    public FiscalPositionService(EntityManager em) {
        this.em = em;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return EntityJson.list(em.createQuery("select p from FiscalPosition p order by p.sortOrder asc, p.code asc",
                FiscalPosition.class).getResultList());
    }

    /** Archived (soft-deleted) positions, for the restore UI. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> deleted() {
        return BaseCrudService.withDeleted(em, () -> EntityJson.list(em.createQuery(
                "select p from FiscalPosition p where p.deletedAt is not null order by p.sortOrder asc, p.code asc",
                FiscalPosition.class).getResultList()));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        return EntityJson.of(require(id));
    }

    @Transactional
    public Map<String, Object> create(String code, String name, Boolean isActive, Integer sortOrder) {
        if (byCode(code, null) != null) {
            throw ApiException.badRequest("Fiscal position code \"" + code + "\" already exists");
        }
        FiscalPosition p = new FiscalPosition();
        p.setOrganizationId(TenantContext.organizationId());
        p.setCode(code);
        p.setName(name);
        p.setIsActive(isActive == null || isActive);
        p.setSortOrder(sortOrder == null ? 0 : sortOrder);
        em.persist(p);
        em.flush();
        em.refresh(p);
        return EntityJson.of(p);
    }

    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> body) {
        FiscalPosition p = require(id);
        Object code = body.get("code");
        if (code instanceof String c && !c.isEmpty() && byCode(c, id) != null) {
            throw ApiException.badRequest("Fiscal position code \"" + c + "\" already exists");
        }
        for (String f : FIELDS) {
            if (body.containsKey(f)) {
                EntityPatch.set(p, f, body.get(f));
            }
        }
        em.flush();
        return EntityJson.of(p);
    }

    /** Soft delete: archived for the restore UI, never hard-deleted. */
    @Transactional
    public Map<String, Object> remove(String id) {
        FiscalPosition p = require(id);
        p.setDeletedAt(Instant.now());
        em.flush();
        return EntityJson.of(p);
    }

    @Transactional
    public Map<String, Object> restore(String id) {
        int n = BaseCrudService.withDeleted(em, () -> em.createQuery("update FiscalPosition p set p.deletedAt = null, p.updatedAt = :now where p.id = :id"
                        + " and p.organizationId = :org and p.deletedAt is not null")
                .setParameter("now", Instant.now())
                .setParameter("id", id)
                .setParameter("org", TenantContext.organizationId())
                .executeUpdate());
        if (n == 0) {
            throw ApiException.notFound("Archived fiscal position not found");
        }
        em.clear();
        FiscalPosition p = find(id);
        return p == null ? null : EntityJson.of(p);
    }

    private FiscalPosition byCode(String code, String excludeId) {
        return em.createQuery("select p from FiscalPosition p where p.code = :code", FiscalPosition.class)
                .setParameter("code", code)
                .getResultList().stream()
                .filter(p -> excludeId == null || !excludeId.equals(p.getId()))
                .findFirst().orElse(null);
    }

    private FiscalPosition find(String id) {
        return em.createQuery("select p from FiscalPosition p where p.id = :id", FiscalPosition.class)
                .setParameter("id", id).getResultList().stream().findFirst().orElse(null);
    }

    private FiscalPosition require(String id) {
        FiscalPosition p = find(id);
        if (p == null) {
            throw ApiException.notFound("Fiscal position not found");
        }
        return p;
    }
}
