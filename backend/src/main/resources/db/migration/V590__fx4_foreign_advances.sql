-- FX-4 (ADR-S082 Outstanding): an expense advance may be in a foreign currency. amount / disbursed / settled /
-- refunded are in that currency; disbursedBase is the functional value it was paid out at (its carrying value).
-- Existing advances stay functional (currencyCode NULL).
ALTER TABLE "ExpenseAdvance"
    ADD COLUMN "currencyCode"  TEXT,
    ADD COLUMN "disbursedBase" NUMERIC(20, 6);
