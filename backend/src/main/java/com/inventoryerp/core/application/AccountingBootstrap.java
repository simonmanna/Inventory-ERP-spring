package com.inventoryerp.core.application;

/**
 * Seeds the accounting core (categories, chart of accounts, journals, account mappings) for a new
 * organization. Implemented by the accounting module (Nest {@code ACCOUNTING_BOOTSTRAP} token: core may
 * not import accounting).
 */
public interface AccountingBootstrap {

    void seedOrganization(String organizationId);
}
