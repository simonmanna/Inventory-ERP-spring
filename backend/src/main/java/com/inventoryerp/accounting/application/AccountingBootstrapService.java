package com.inventoryerp.accounting.application;

import com.inventoryerp.core.application.AccountingBootstrap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Accounting's implementation of the core {@link AccountingBootstrap} hook (port of
 * {@code accounting-bootstrap.service.ts}) plus the boot-time global category seed
 * ({@code AccountingModule.onModuleInit}).
 */
@Service
public class AccountingBootstrapService implements AccountingBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AccountingBootstrapService.class);

    private final JdbcClient jdbc;
    private final AccountResolverService accounts;

    public AccountingBootstrapService(JdbcClient jdbc, AccountResolverService accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    @Override
    public void seedOrganization(String organizationId) {
        CoaSeeder.seedAccountingCore(jdbc, organizationId);
    }

    /** Global account categories are seeded once at boot (version-gated, idempotent). */
    @EventListener(ApplicationReadyEvent.class)
    public void seedCategories() {
        try {
            CoaSeeder.seedAccountCategories(jdbc);
            accounts.reloadCategories();
        } catch (RuntimeException e) {
            log.warn("[AccountingModule] account category seed failed: {}", e.getMessage());
        }
    }
}
