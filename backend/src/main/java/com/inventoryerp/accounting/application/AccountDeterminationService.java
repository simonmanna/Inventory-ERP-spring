package com.inventoryerp.accounting.application;

import org.springframework.stereotype.Service;

/**
 * Account determination (port of {@code account-determination.service.ts}): line override → partner
 * override → product category → tax → org mapping. Thin façade over {@link AccountResolverService}.
 */
@Service
public class AccountDeterminationService {

    private final AccountResolverService resolver;

    public AccountDeterminationService(AccountResolverService resolver) {
        this.resolver = resolver;
    }

    /** Resolve an org-level mapping (e.g. {@code accounts_receivable}); throws if unconfigured. */
    public String mapped(String key) {
        return resolver.byMapping(key);
    }

    public String receivableAccount(String partnerReceivableAccountId) {
        return partnerReceivableAccountId != null ? partnerReceivableAccountId : mapped("accounts_receivable");
    }

    public String payableAccount(String partnerPayableAccountId) {
        return partnerPayableAccountId != null ? partnerPayableAccountId : mapped("accounts_payable");
    }

    public String incomeAccount(String lineAccountId, String categoryIncomeAccountId) {
        return lineAccountId != null ? lineAccountId
                : categoryIncomeAccountId != null ? categoryIncomeAccountId : mapped("sales_revenue");
    }

    public String expenseAccount(String lineAccountId, String categoryExpenseAccountId) {
        return lineAccountId != null ? lineAccountId
                : categoryExpenseAccountId != null ? categoryExpenseAccountId : mapped("default_expense");
    }

    /** Output tax → the tax's own account or {@code tax_payable}; input tax → always {@code tax_receivable}. */
    public String taxAccount(String taxAccountId, String mappingKey) {
        if ("tax_receivable".equals(mappingKey)) {
            return mapped("tax_receivable");
        }
        return taxAccountId != null ? taxAccountId : mapped(mappingKey == null ? "tax_payable" : mappingKey);
    }
}
