-- FX-2 C (owner decision 6, 2026-11-20): account-level monetary configuration and partner-level AR/AP revaluation.
-- History is not restated: existing FxRevaluation rows stay; the first run after deploy recomputes per partner from
-- the original foreign lines (carrying values exclude revaluation adjustments).

-- 1. Category defaults (IAS 21.16) live in code (RevaluationService.MONETARY_CATEGORIES), because the global category
--    catalog is seeded at startup, after migrations. Monetary: cash, bank, mobile money, petty cash, receivables,
--    payables, tax and liabilities (loans, accruals). Everything else is non-monetary, including prepaid expense (FX-1),
--    inventory, fixed assets, deferred revenue, equity, income and expense.

-- 2. Per-account override: inherit the category default, or force monetary / non-monetary.
ALTER TABLE "Account" ADD COLUMN "fxRevaluation" TEXT NOT NULL DEFAULT 'inherit';
ALTER TABLE "Account" ADD CONSTRAINT "Account_fxRevaluation_check"
    CHECK ("fxRevaluation" IN ('inherit', 'monetary', 'non_monetary'));

-- The customer-deposit (advance) account (IFRIC 22) and the GRNI / landed-cost clearing accounts inherit as non-monetary
-- in code (RevaluationService.NON_MONETARY_MAPPINGS), so no row is rewritten here.

-- 3. Partner-level revaluation rows.
ALTER TABLE "FxRevaluation" ADD COLUMN "partnerId" TEXT;
DROP INDEX IF EXISTS "FxRevaluation_org_period_account_currency_key";
CREATE UNIQUE INDEX "FxRevaluation_org_period_account_currency_partner_key"
    ON "FxRevaluation" ("organizationId", "fiscalPeriodId", "accountId", "currencyCode", (coalesce("partnerId", '')));
