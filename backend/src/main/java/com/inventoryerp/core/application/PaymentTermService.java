package com.inventoryerp.core.application;

import com.inventoryerp.core.domain.PaymentTerm;
import com.inventoryerp.core.domain.PaymentTermMethod;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.persistence.DbEnum;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payment terms (port of {@code payment-term.service.ts}): org-scoped, soft-deleted, built-in defaults
 * lazily seeded on first list; {@link #dueDateFor} and {@link #assertCreditOk} are used by invoicing/POS.
 */
@Service
public class PaymentTermService {

    private static final Logger log = LoggerFactory.getLogger(PaymentTermService.class);

    /** Fields accepted on update (Nest picks these from the unvalidated {@code Partial<>} body). */
    private static final List<String> FIELDS = List.of("code", "name", "method", "netDays", "discountDays",
            "discountPercent", "isActive", "sortOrder");

    private record Default(String code, String name, PaymentTermMethod method, int netDays, int sortOrder) {}

    private static final List<Default> DEFAULT_TERMS = List.of(
            new Default("immediate", "Immediate Payment", PaymentTermMethod.IMMEDIATE, 0, 10),
            new Default("net-7", "7 Days", PaymentTermMethod.NET_DAYS, 7, 20),
            new Default("net-15", "15 Days", PaymentTermMethod.NET_DAYS, 15, 30),
            new Default("net-30", "30 Days", PaymentTermMethod.NET_DAYS, 30, 40),
            new Default("net-45", "45 Days", PaymentTermMethod.NET_DAYS, 45, 50),
            new Default("eom", "End of Following Month", PaymentTermMethod.END_OF_FOLLOWING_MONTH, 0, 60),
            new Default("eom-10", "10 Days after End of Next Month", PaymentTermMethod.END_OF_FOLLOWING_MONTH, 10, 70));

    /** Create body. */
    public record PaymentTermInput(String code, String name, String method, Integer netDays, Integer discountDays,
            BigDecimal discountPercent, Boolean isActive, Integer sortOrder) {}

    private final EntityManager em;

    public PaymentTermService(EntityManager em) {
        this.em = em;
    }

    /** Active terms for pickers; lazy-seeds the built-in defaults when the org has none. */
    @Transactional
    public List<Map<String, Object>> list() {
        ensureDefaults(TenantContext.organizationId());
        return EntityJson.list(em.createQuery("select t from PaymentTerm t order by t.sortOrder asc, t.code asc",
                PaymentTerm.class).getResultList());
    }

    /** Archived (soft-deleted) terms, for the restore UI. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> deleted() {
        return BaseCrudService.withDeleted(em, () -> EntityJson.list(em.createQuery(
                "select t from PaymentTerm t where t.deletedAt is not null order by t.sortOrder asc, t.code asc",
                PaymentTerm.class).getResultList()));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        return EntityJson.of(require(id));
    }

    @Transactional
    public Map<String, Object> create(PaymentTermInput dto) {
        PaymentTerm t = new PaymentTerm();
        t.setOrganizationId(TenantContext.organizationId());
        t.setCode(dto.code());
        t.setName(dto.name());
        t.setMethod(dto.method() == null ? PaymentTermMethod.NET_DAYS : DbEnum.fromValue(PaymentTermMethod.class, dto.method()));
        t.setNetDays(dto.netDays() == null ? 0 : dto.netDays());
        t.setDiscountDays(dto.discountDays());
        t.setDiscountPercent(dto.discountPercent());
        t.setIsActive(dto.isActive() == null || dto.isActive());
        t.setSortOrder(dto.sortOrder() == null ? 0 : dto.sortOrder());
        em.persist(t);
        em.flush();
        em.refresh(t);
        return EntityJson.of(t);
    }

    /** Partial update: only known keys present in the body are written. */
    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> body) {
        PaymentTerm t = require(id);
        for (String f : FIELDS) {
            if (body.containsKey(f) && body.get(f) != null) {
                EntityPatch.set(t, f, body.get(f));
            } else if (body.containsKey(f) && ("discountDays".equals(f) || "discountPercent".equals(f))) {
                EntityPatch.set(t, f, null);
            }
        }
        em.flush();
        return EntityJson.of(t);
    }

    /** Soft delete; referencing documents keep their name snapshot. */
    @Transactional
    public Map<String, Object> remove(String id) {
        PaymentTerm t = require(id);
        t.setDeletedAt(Instant.now());
        em.flush();
        return EntityJson.of(t);
    }

    @Transactional
    public Map<String, Object> restore(String id) {
        int n = BaseCrudService.withDeleted(em, () -> em.createQuery("update PaymentTerm t set t.deletedAt = null, t.updatedAt = :now where t.id = :id"
                        + " and t.organizationId = :org and t.deletedAt is not null")
                .setParameter("now", Instant.now())
                .setParameter("id", id)
                .setParameter("org", TenantContext.organizationId())
                .executeUpdate());
        if (n == 0) {
            throw ApiException.notFound("Archived payment term not found");
        }
        em.clear();
        PaymentTerm t = find(id);
        return t == null ? null : EntityJson.of(t);
    }

    /** Net days for a term id (0 when none/absent). */
    @Transactional(readOnly = true)
    public int netDaysFor(String paymentTermId) {
        if (paymentTermId == null || paymentTermId.isEmpty()) {
            return 0;
        }
        PaymentTerm t = find(paymentTermId);
        return t == null || t.getNetDays() == null ? 0 : t.getNetDays();
    }

    /** Due date for a term (immediate / net_days / end_of_following_month + netDays), in {@code zone}. */
    @Transactional(readOnly = true)
    public Instant dueDateFor(String paymentTermId, Instant base, ZoneId zone) {
        if (paymentTermId == null || paymentTermId.isEmpty()) {
            return base;
        }
        PaymentTerm t = find(paymentTermId);
        if (t == null) {
            return base;
        }
        return computeDueDate(t.getMethod(), t.getNetDays() == null ? 0 : t.getNetDays(), base, zone);
    }

    /**
     * Credit-control check for a credit sale: throws when the partner is on hold or the new balance would
     * exceed the credit limit (0 = unlimited).
     */
    public static void assertCreditOk(Boolean creditHold, BigDecimal creditLimit, double currentBalance, double addAmount) {
        if (Boolean.TRUE.equals(creditHold)) {
            throw ApiException.badRequest("Customer is on credit hold.");
        }
        double limit = creditLimit == null ? 0 : creditLimit.doubleValue();
        if (limit > 0 && currentBalance + addAmount > limit) {
            throw ApiException.badRequest("Credit limit exceeded: limit " + com.inventoryerp.kernel.common.JsNumbers.toJsString(limit)
                    + ", would become " + com.inventoryerp.kernel.common.JsNumbers.toJsString(currentBalance + addAmount) + ".");
        }
    }

    /** {@link #assertCreditOk(Boolean, BigDecimal, double, double)} in exact decimals, same messages (SALES-1). */
    public static void assertCreditOk(Boolean creditHold, BigDecimal creditLimit, BigDecimal currentBalance,
            BigDecimal addAmount) {
        if (Boolean.TRUE.equals(creditHold)) {
            throw ApiException.badRequest("Customer is on credit hold.");
        }
        BigDecimal after = currentBalance.add(addAmount);
        if (creditLimit != null && creditLimit.signum() > 0 && after.compareTo(creditLimit) > 0) {
            throw ApiException.badRequest("Credit limit exceeded: limit "
                    + com.inventoryerp.kernel.common.JsNumbers.toJsString(creditLimit.doubleValue()) + ", would become "
                    + com.inventoryerp.kernel.common.JsNumbers.toJsString(after.doubleValue()) + ".");
        }
    }

    /** Idempotent: inserts the built-in default terms when the org has zero live rows. */
    @Transactional
    public void ensureDefaults(String organizationId) {
        long count = ((Number) em.createNativeQuery(
                        "SELECT count(*) FROM \"PaymentTerm\" WHERE \"organizationId\" = ?1 AND \"deletedAt\" IS NULL")
                .setParameter(1, organizationId).getSingleResult()).longValue();
        if (count > 0) {
            return;
        }
        try {
            for (Default d : DEFAULT_TERMS) {
                em.createNativeQuery("INSERT INTO \"PaymentTerm\" (id, \"organizationId\", code, name, method, \"netDays\","
                                + " \"sortOrder\", \"isActive\", \"updatedAt\") VALUES (gen_random_uuid()::text, ?1, ?2, ?3,"
                                + " CAST(?4 AS \"PaymentTermMethod\"), ?5, ?6, true, now()) ON CONFLICT DO NOTHING")
                        .setParameter(1, organizationId).setParameter(2, d.code()).setParameter(3, d.name())
                        .setParameter(4, d.method().value()).setParameter(5, d.netDays()).setParameter(6, d.sortOrder())
                        .executeUpdate();
            }
        } catch (RuntimeException e) {
            log.warn("PaymentTerm defaults seed skipped for org {}: {}", organizationId, e.getMessage());
        }
    }

    /** Pure due-date computation (Odoo "Due" methods); local midnight in {@code zone}. */
    public static Instant computeDueDate(PaymentTermMethod method, int netDays, Instant base, ZoneId zone) {
        LocalDate day = ZonedDateTime.ofInstant(base, zone).toLocalDate();
        if (method == PaymentTermMethod.IMMEDIATE) {
            return day.atStartOfDay(zone).toInstant();
        }
        if (method == PaymentTermMethod.END_OF_FOLLOWING_MONTH) {
            LocalDate eom = day.plusMonths(1).withDayOfMonth(day.plusMonths(1).lengthOfMonth());
            return eom.plusDays(netDays).atStartOfDay(zone).toInstant();
        }
        return day.plusDays(netDays).atStartOfDay(zone).toInstant();
    }

    private PaymentTerm find(String id) {
        return em.createQuery("select t from PaymentTerm t where t.id = :id", PaymentTerm.class)
                .setParameter("id", id).getResultList().stream().findFirst().orElse(null);
    }

    private PaymentTerm require(String id) {
        PaymentTerm t = find(id);
        if (t == null) {
            throw ApiException.notFound("Payment term not found");
        }
        return t;
    }
}
