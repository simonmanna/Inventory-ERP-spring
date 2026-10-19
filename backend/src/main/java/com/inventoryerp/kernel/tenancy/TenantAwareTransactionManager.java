package com.inventoryerp.kernel.tenancy;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.EntityManagerFactory;
import java.sql.PreparedStatement;
import org.hibernate.Session;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Opens every transaction in the current tenant (port of the Nest Prisma tenancy extension and the
 * {@code PrismaService.$transaction} wrapper): enables the Hibernate {@code tenant} and
 * {@code softDelete} filters and runs {@code SET LOCAL app.org_id} so the V1 RLS policies see the
 * calling tenant. Without a tenant context (public routes, jobs, Nest {@code prisma.raw}) the
 * transaction is unscoped.
 */
public class TenantAwareTransactionManager extends JpaTransactionManager {

    public TenantAwareTransactionManager(EntityManagerFactory emf) {
        super(emf);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        String orgId = TenantContext.optionalOrganizationId();
        if (orgId == null || orgId.isEmpty()) {
            return;
        }
        var holder = (EntityManagerHolder) TransactionSynchronizationManager.getResource(obtainEntityManagerFactory());
        if (holder == null) {
            return;
        }
        Session session = holder.getEntityManager().unwrap(Session.class);
        session.enableFilter(TenancyFilters.TENANT).setParameter(TenancyFilters.TENANT_PARAM, orgId);
        session.enableFilter(TenancyFilters.SOFT_DELETE);
        session.doWork(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT set_config('app.org_id', ?, true)")) {
                ps.setString(1, orgId);
                ps.execute();
            }
        });
    }
}
