package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.domain.AccountCategory;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import jakarta.persistence.EntityManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only access to the global, system-managed account-category catalog (port of
 * {@code account-category.service.ts}); usage counts are tenant-scoped.
 */
@Service
public class AccountCategoryService {

    private final EntityManager em;

    public AccountCategoryService(EntityManager em) {
        this.em = em;
    }

    private List<AccountCategory> rows() {
        return em.createQuery("select c from AccountCategory c where c.deletedAt is null order by c.sortOrder asc, c.key asc",
                AccountCategory.class).getResultList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return EntityJson.list(rows());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        AccountCategory row = em.createQuery("select c from AccountCategory c where c.id = :id and c.deletedAt is null",
                AccountCategory.class).setParameter("id", id).getResultList().stream().findFirst().orElse(null);
        if (row == null) {
            throw ApiException.notFound("Account category " + id + " not found");
        }
        return EntityJson.of(row);
    }

    /** Categories plus how many of this organization's live accounts use each. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listWithUsage() {
        Map<String, Long> usage = new HashMap<>();
        em.createQuery("select a.categoryId, count(a) from Account a where a.deletedAt is null group by a.categoryId",
                Object[].class).getResultList().forEach(r -> usage.put((String) r[0], (Long) r[1]));
        return rows().stream().map(c -> {
            Map<String, Object> m = EntityJson.of(c);
            m.put("accountCount", usage.getOrDefault(c.getId(), 0L));
            return m;
        }).toList();
    }
}
