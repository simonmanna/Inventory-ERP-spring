package com.inventoryerp.core.application;

import com.inventoryerp.core.domain.Address;
import com.inventoryerp.core.domain.Contact;
import com.inventoryerp.core.domain.Partner;
import com.inventoryerp.core.domain.PartnerCategory;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.crud.AfterCommit;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Port of {@code partner.service.ts}: include {@code category}, live {@code contacts}/{@code addresses}
 * (primary first); create/update replace nested contacts/addresses; audit in the transaction and
 * {@code partner.*} events after commit.
 */
@Service
public class PartnerService extends BaseCrudService<Partner> {

    private final AuditService audit;
    private final EventBus events;

    public PartnerService(EntityManager em, AuditService audit, EventBus events) {
        super(em, Partner.class);
        this.audit = audit;
        this.events = events;
    }

    @Override
    protected String entityName() {
        return "Partner";
    }

    @Override
    protected List<String> searchFields() {
        return List.of("code", "name", "email", "phone", "taxNumber");
    }

    @Override
    protected List<Map<String, Object>> render(List<Partner> rows) {
        List<Object> ids = ids(rows);
        Map<Object, List<Map<String, Object>>> contacts = ids.isEmpty() ? Map.of() : groupBy(em.createQuery(
                        "select c from Contact c where c.partnerId in :ids and c.deletedAt is null", Contact.class)
                .setParameter("ids", ids).getResultList().stream()
                .sorted(Comparator.comparing(c -> !Boolean.TRUE.equals(c.getIsPrimary())))
                .toList(), "partnerId");
        Map<Object, List<Map<String, Object>>> addresses = ids.isEmpty() ? Map.of() : groupBy(em.createQuery(
                        "select a from Address a where a.partnerId in :ids and a.deletedAt is null", Address.class)
                .setParameter("ids", ids).getResultList().stream()
                .sorted(Comparator.comparing(a -> !Boolean.TRUE.equals(a.getIsPrimary())))
                .toList(), "partnerId");
        return rows.stream().map(p -> {
            Map<String, Object> out = EntityJson.of(p);
            out.put("category", one(em, PartnerCategory.class, p.getCategoryId()));
            out.put("contacts", contacts.getOrDefault(p.getId(), List.of()));
            out.put("addresses", addresses.getOrDefault(p.getId(), List.of()));
            return out;
        }).toList();
    }

    /** Create with nested contacts/addresses (one transaction). */
    @Transactional
    public Map<String, Object> create(Record dto, List<Map<String, Object>> contacts,
            List<Map<String, Object>> addresses) {
        Partner row = EntityPatch.apply(newInstance(), dto, "contacts", "addresses");
        if (row.getCode() == null || row.getCode().isEmpty()) {
            row.setCode("CUST-" + Long.toString(System.currentTimeMillis(), 36).toUpperCase());
        }
        persist(row);
        Map<String, Object> created = EntityJson.of(row);
        if (contacts != null && !contacts.isEmpty()) {
            insertContacts(row.getOrganizationId(), row.getId(), contacts);
        }
        if (addresses != null && !addresses.isEmpty()) {
            insertAddresses(row.getOrganizationId(), row.getId(), addresses);
        }
        audit.recordInTx(AuditInput.of("Partner", row.getId(), "create", null, created));
        String orgId = TenantContext.organizationId();
        String id = row.getId();
        AfterCommit.run(() -> events.publish(DomainEvents.PARTNER_CREATED, Map.of("id", id, "organizationId", orgId)));
        // Nest returns the created row without the include.
        return created;
    }

    /** Update; non-empty contacts/addresses replace the existing set. */
    @Transactional
    public Map<String, Object> update(String id, Record dto, List<Map<String, Object>> contacts,
            List<Map<String, Object>> addresses) {
        Partner row = find(id);
        if (row == null) {
            throw ApiException.notFound("Partner " + id + " not found");
        }
        Map<String, Object> before = EntityJson.of(row);
        String orgId = row.getOrganizationId();
        EntityPatch.apply(row, dto, "contacts", "addresses");
        em.flush();
        if (contacts != null && !contacts.isEmpty()) {
            em.createNativeQuery("DELETE FROM \"Contact\" WHERE \"partnerId\" = ?1 AND \"organizationId\" = ?2")
                    .setParameter(1, id).setParameter(2, orgId).executeUpdate();
            insertContacts(orgId, id, contacts);
        }
        if (addresses != null && !addresses.isEmpty()) {
            em.createNativeQuery("DELETE FROM \"Address\" WHERE \"partnerId\" = ?1 AND \"organizationId\" = ?2")
                    .setParameter(1, id).setParameter(2, orgId).executeUpdate();
            insertAddresses(orgId, id, addresses);
        }
        em.refresh(row);
        Map<String, Object> after = EntityJson.of(row);
        audit.recordInTx(AuditInput.of("Partner", id, "update", before, after));
        String tenant = TenantContext.organizationId();
        AfterCommit.run(() -> events.publish(DomainEvents.PARTNER_UPDATED, Map.of("id", id, "organizationId", tenant)));
        return after;
    }

    /** Soft delete; like Nest, a missing id still succeeds (updateMany count is not checked). */
    @Override
    @Transactional
    public void remove(String id) {
        em.createQuery("update Partner p set p.deletedAt = :now, p.updatedAt = :now where p.id = :id"
                        + " and p.organizationId = :org and p.deletedAt is null")
                .setParameter("now", Instant.now())
                .setParameter("id", id)
                .setParameter("org", TenantContext.organizationId())
                .executeUpdate();
        audit.recordInTx(AuditInput.of("Partner", id, "delete"));
        String tenant = TenantContext.organizationId();
        AfterCommit.run(() -> events.publish(DomainEvents.PARTNER_DELETED, Map.of("id", id, "organizationId", tenant)));
    }

    private void insertContacts(String orgId, String partnerId, List<Map<String, Object>> rows) {
        for (Map<String, Object> c : rows) {
            Contact x = new Contact();
            x.setOrganizationId(orgId);
            x.setPartnerId(partnerId);
            x.setFirstName(c.get("firstName") == null ? "" : String.valueOf(c.get("firstName")));
            x.setLastName(str(c.get("lastName")));
            x.setPosition(str(c.get("position")));
            x.setEmail(str(c.get("email")));
            x.setPhone(str(c.get("phone")));
            x.setIsPrimary(Boolean.TRUE.equals(c.get("isPrimary")));
            em.persist(x);
        }
        em.flush();
    }

    private void insertAddresses(String orgId, String partnerId, List<Map<String, Object>> rows) {
        for (Map<String, Object> a : rows) {
            Address x = new Address();
            x.setOrganizationId(orgId);
            x.setPartnerId(partnerId);
            EntityPatch.set(x, "type", a.get("type") == null ? "billing" : a.get("type"));
            x.setLine1(a.get("line1") == null ? "" : String.valueOf(a.get("line1")));
            x.setLine2(str(a.get("line2")));
            x.setCity(str(a.get("city")));
            x.setState(str(a.get("state")));
            x.setPostalCode(str(a.get("postalCode")));
            x.setCountry(str(a.get("country")));
            x.setIsPrimary(Boolean.TRUE.equals(a.get("isPrimary")));
            em.persist(x);
        }
        em.flush();
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /** Credit limit and hold (SALES-1); null leaves a value unchanged. Used by invoicing's credit control. */
    @Transactional
    public void updateCreditPolicy(String id, BigDecimal creditLimit, Boolean creditHold) {
        Partner p = em.createQuery("select p from Partner p where p.id = :id", Partner.class).setParameter("id", id)
                .getResultList().stream().findFirst().orElseThrow(() -> ApiException.notFound("Partner not found"));
        if (creditLimit != null) {
            p.setCreditLimit(creditLimit.setScale(2, RoundingMode.HALF_UP));
        }
        if (creditHold != null) {
            p.setCreditHold(creditHold);
        }
        em.flush();
    }
}
