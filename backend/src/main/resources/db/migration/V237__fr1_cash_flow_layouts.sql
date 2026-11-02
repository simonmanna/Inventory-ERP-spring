-- V237: Financial reporting FR-1..FR-9 (ADR-S043) — statement layouts may also describe a cash flow statement
-- (indirect method, kind CF).

ALTER TABLE "FinancialStatementLayout" DROP CONSTRAINT IF EXISTS "FinancialStatementLayout_kind_check";
ALTER TABLE "FinancialStatementLayout" ADD CONSTRAINT "FinancialStatementLayout_kind_check"
    CHECK (kind IN ('PL', 'BS', 'CF'));
