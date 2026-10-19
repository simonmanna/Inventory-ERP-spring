package com.inventoryerp.kernel.crud;

import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import com.inventoryerp.shared.web.PageQuery;
import com.inventoryerp.shared.web.PageResult;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.Session;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generic CRUD over a tenant-aware entity (port of {@code BaseCrudService}). The Hibernate tenant and
 * soft-delete filters (enabled per transaction) scope every query like the Prisma tenancy extension;
 * {@code organizationId} is assigned on persist. Subclasses declare the entity, search fields, default
 * order and (optionally) the Prisma {@code include} via {@link #render(List)}.
 */
public abstract class BaseCrudService<T> {

    /** One {@code orderBy} key. */
    public record Sort(String field, boolean asc) {
        public static Sort asc(String field) {
            return new Sort(field, true);
        }

        public static Sort desc(String field) {
            return new Sort(field, false);
        }
    }

    /** Extra list predicates (subclass filters). */
    @FunctionalInterface
    public interface Where<T> {
        void add(CriteriaBuilder cb, Root<T> root, List<Predicate> predicates);
    }

    protected final EntityManager em;
    protected final Class<T> type;

    protected BaseCrudService(EntityManager em, Class<T> type) {
        this.em = em;
        this.type = type;
    }

    /** Prisma model name used in "X id not found". */
    protected abstract String entityName();

    protected List<String> searchFields() {
        return List.of();
    }

    protected List<Sort> defaultOrderBy() {
        return List.of(Sort.desc("createdAt"));
    }

    /** Rows → wire maps with the Prisma {@code include}; default = scalar columns only. */
    protected List<Map<String, Object>> render(List<T> rows) {
        return EntityJson.list(rows);
    }

    protected Map<String, Object> render(T row) {
        return render(List.of(row)).getFirst();
    }

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(PageQuery query) {
        return list(query, (cb, root, p) -> {});
    }

    protected PageResult<Map<String, Object>> list(PageQuery query, Where<T> extra) {
        int page = Math.max(1, query.page() == null ? 1 : query.page());
        int pageSize = Math.min(1000, Math.max(1, query.pageSize() == null ? 25 : query.pageSize()));
        CriteriaBuilder cb = em.getCriteriaBuilder();

        CriteriaQuery<T> cq = cb.createQuery(type);
        Root<T> root = cq.from(type);
        cq.where(where(cb, root, query.search(), extra));
        List<Order> orders = new ArrayList<>();
        if (query.sortBy() != null && !query.sortBy().isEmpty()) {
            boolean asc = !"desc".equals(query.sortOrder());
            orders.add(asc ? cb.asc(root.get(query.sortBy())) : cb.desc(root.get(query.sortBy())));
        } else {
            for (Sort s : defaultOrderBy()) {
                orders.add(s.asc() ? cb.asc(root.get(s.field())) : cb.desc(root.get(s.field())));
            }
        }
        cq.orderBy(orders);
        List<T> rows = em.createQuery(cq).setFirstResult((page - 1) * pageSize).setMaxResults(pageSize).getResultList();

        CriteriaQuery<Long> count = cb.createQuery(Long.class);
        Root<T> countRoot = count.from(type);
        count.select(cb.count(countRoot)).where(where(cb, countRoot, query.search(), extra));
        long total = em.createQuery(count).getSingleResult();
        return PageResult.of(render(rows), page, pageSize, total);
    }

    private Predicate[] where(CriteriaBuilder cb, Root<T> root, String search, Where<T> extra) {
        List<Predicate> and = new ArrayList<>();
        if (search != null && !search.isEmpty() && !searchFields().isEmpty()) {
            String pattern = "%" + escapeLike(search.toLowerCase()) + "%";
            List<Predicate> or = new ArrayList<>();
            for (String f : searchFields()) {
                or.add(cb.like(cb.lower(root.get(f)), pattern, '\\'));
            }
            and.add(cb.or(or.toArray(Predicate[]::new)));
        }
        extra.add(cb, root, and);
        return and.toArray(Predicate[]::new);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        return render(require(id));
    }

    @Transactional
    public Map<String, Object> create(Record dto) {
        T row = newInstance();
        EntityPatch.apply(row, dto);
        return render(persist(row));
    }

    @Transactional
    public Map<String, Object> update(String id, Record dto) {
        T row = require(id);
        EntityPatch.apply(row, dto);
        em.flush();
        return render(row);
    }

    @Transactional
    public void remove(String id) {
        T row = require(id);
        EntityPatch.set(row, "deletedAt", Instant.now());
        em.flush();
    }

    // ------------------------------------------------------------------ helpers

    /** Insert, flush and reload so DB defaults (Prisma {@code @default}) appear in the response. */
    protected T persist(T row) {
        em.persist(row);
        em.flush();
        em.refresh(row);
        return row;
    }

    /** {@code findFirst({where: {id}})} under the tenant + soft-delete filters, or 404. */
    protected T require(String id) {
        T row = find(id);
        if (row == null) {
            throw ApiException.notFound(entityName() + " " + id + " not found");
        }
        return row;
    }

    /** {@code findFirst({where: {id}})} under the tenant + soft-delete filters. */
    protected T find(String id) {
        List<T> rows = em.createQuery("select e from " + type.getSimpleName() + " e where e.id = :id", type)
                .setParameter("id", id)
                .getResultList();
        return rows.isEmpty() ? null : rows.getFirst();
    }

    protected T newInstance() {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException
                 | NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Run {@code body} with the soft-delete filter off (Prisma {@code where: {deletedAt: {not: null}}}). */
    public static <R> R withDeleted(EntityManager em, java.util.function.Supplier<R> body) {
        Session session = em.unwrap(Session.class);
        boolean enabled = session.getEnabledFilter(com.inventoryerp.shared.persistence.TenancyFilters.SOFT_DELETE) != null;
        if (enabled) {
            session.disableFilter(com.inventoryerp.shared.persistence.TenancyFilters.SOFT_DELETE);
        }
        try {
            return body.get();
        } finally {
            if (enabled) {
                session.enableFilter(com.inventoryerp.shared.persistence.TenancyFilters.SOFT_DELETE);
            }
        }
    }

    // ------------------------------------------------------------------ include helpers

    /** Nested to-one include ({@code include: {rel: true}}): unfiltered lookup by FK like Prisma. */
    public static Map<String, Object> one(EntityManager em, Class<?> type, Object id) {
        return id == null ? null : EntityJson.of(em.find(type, id));
    }

    /** Group rows by a FK field for to-many includes. */
    public static <E> Map<Object, List<Map<String, Object>>> groupBy(Collection<E> rows, String fkField) {
        Map<Object, List<Map<String, Object>>> out = new LinkedHashMap<>();
        for (E r : rows) {
            out.computeIfAbsent(EntityPatch.get(r, fkField), k -> new ArrayList<>()).add(EntityJson.of(r));
        }
        return out;
    }

    /** Ids of the given rows. */
    public static List<Object> ids(Collection<?> rows) {
        List<Object> ids = new ArrayList<>();
        for (Object r : rows) {
            ids.add(EntityPatch.get(r, "id"));
        }
        return ids;
    }

    /** {@code _count: {select: {rel: true}}}: raw count of child rows by FK (Prisma counts ignore soft delete). */
    public static Map<Object, Long> countBy(EntityManager em, String table, String fkColumn, Collection<Object> ids) {
        Map<Object, Long> out = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("SELECT \"" + fkColumn + "\", count(*) FROM \"" + table
                        + "\" WHERE \"" + fkColumn + "\" IN (:ids) GROUP BY \"" + fkColumn + "\"")
                .setParameter("ids", ids)
                .getResultList();
        for (Object[] r : rows) {
            out.put(r[0], ((Number) r[1]).longValue());
        }
        return out;
    }

    public static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
