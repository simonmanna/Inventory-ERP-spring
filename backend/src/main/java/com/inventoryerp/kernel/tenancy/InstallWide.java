package com.inventoryerp.kernel.tenancy;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs work that spans every organization of the installation (licence caps, licence module reconciliation) in its own
 * transaction with no tenant. Inside a request the tenant's row-level security policy would otherwise limit the work
 * to the caller's organization. The transaction commits on its own, before the caller's.
 */
@Component
public class InstallWide {

    private final TransactionTemplate tx;

    public InstallWide(PlatformTransactionManager txm) {
        this.tx = new TransactionTemplate(txm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public <T> T run(Supplier<T> body) {
        return TenantContext.unscoped(() -> tx.execute(s -> body.get()));
    }
}
