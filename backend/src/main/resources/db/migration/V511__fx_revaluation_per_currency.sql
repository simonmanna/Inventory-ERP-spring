-- R-8 foreign-currency treasury, phase 5 (ADR-S076, plan §5.1 M-2): one revaluation row per (period, account,
-- currency). An account holding two foreign currencies (receivables, payables) kept only the last currency's row.
-- Existing rows already carry "currencyCode"; none is changed.

DROP INDEX IF EXISTS "FxRevaluation_organizationId_fiscalPeriodId_accountId_key";
CREATE UNIQUE INDEX "FxRevaluation_org_period_account_currency_key"
    ON "FxRevaluation" ("organizationId", "fiscalPeriodId", "accountId", "currencyCode");
