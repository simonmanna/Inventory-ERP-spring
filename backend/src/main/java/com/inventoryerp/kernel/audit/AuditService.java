package com.inventoryerp.kernel.audit;

import com.inventoryerp.kernel.common.DetachedWrites;
import com.inventoryerp.kernel.domain.AuditAction;
import com.inventoryerp.kernel.domain.AuditLog;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.persistence.DbEnum;
import com.inventoryerp.shared.web.EntityJson;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Audit trail (port of {@code audit.service.ts}, ADR-006).
 *
 * <ul>
 *   <li>{@link #recordInTx} writes inside the caller's business transaction; a failure rolls it back.
 *       Use it from every mutation path that touches money.
 *   <li>{@link #record} is fire-and-forget in its own transaction; only for non-financial events (login).
 * </ul>
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /** One audit row. {@code action} is an {@code AuditAction} value ({@code create}, {@code update}, ...). */
    public record AuditInput(String entity, String entityId, String action, Object oldValues, Object newValues,
            String ipAddress, String userAgent) {

        public static AuditInput of(String entity, String entityId, String action) {
            return new AuditInput(entity, entityId, action, null, null, null, null);
        }

        public static AuditInput of(String entity, String entityId, String action, Object oldValues, Object newValues) {
            return new AuditInput(entity, entityId, action, oldValues, newValues, null, null);
        }
    }

    private final EntityManager em;
    private final DetachedWrites detached;
    private final TransactionTemplate newTx;

    public AuditService(EntityManager em, DetachedWrites detached, PlatformTransactionManager txm) {
        this.em = em;
        this.detached = detached;
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Write the audit row inside the current business transaction. Throws on failure. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordInTx(AuditInput input) {
        String organizationId = TenantContext.optionalOrganizationId();
        if (organizationId == null) {
            throw new IllegalStateException("recordInTx called without a tenant context");
        }
        em.persist(row(organizationId, input));
    }

    /**
     * Fire-and-forget audit write; silently skipped without a tenant, failures only logged. Inside a transaction
     * the write runs after it completes ({@link DetachedWrites}), never holding two pooled connections.
     */
    public void record(AuditInput input) {
        String organizationId = TenantContext.optionalOrganizationId();
        if (organizationId == null) {
            return;
        }
        AuditLog row = row(organizationId, input);
        detached.submit(() -> {
            try {
                newTx.executeWithoutResult(s -> em.persist(row));
            } catch (RuntimeException err) {
                log.warn("Fire-and-forget audit failed (non-critical): {}", err.toString());
            }
        });
    }

    private static AuditLog row(String organizationId, AuditInput input) {
        AuditLog row = new AuditLog();
        row.setOrganizationId(organizationId);
        row.setEntity(input.entity());
        row.setEntityId(input.entityId());
        row.setAction(DbEnum.fromValue(AuditAction.class, input.action()));
        row.setOldValues(EntityJson.toJson(input.oldValues()));
        row.setNewValues(EntityJson.toJson(input.newValues()));
        row.setActorId(TenantContext.userId());
        row.setIpAddress(input.ipAddress());
        row.setUserAgent(input.userAgent());
        return row;
    }
}
