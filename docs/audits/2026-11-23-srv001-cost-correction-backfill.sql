-- One-off, owner-approved data repair (2026-11-23). NOT a Flyway migration; never run at startup.
--
-- Organisation DEMO, product SRV-001 "Consulting Hour". On 2026-11-20 the vendor-bill receipt STK/000052 (1 unit at
-- 2,960,000) landed in a quant of -3 units issued at cost 0. The engine posted the negative-stock cost correction
-- journal INV/2026/00027 (Dr COGS / Cr Stock 8,880,000, posting key inventory:cost_correction:…:STK/000052), but
-- before fix/inventory-cost-correction-ledger it wrote no value-only ledger row for it. So Σ InventoryLedger value
-- (+2,960,000) disagreed with the valuation and the GL (both -5,920,000); the integrity check valuation_vs_subledger
-- flagged it. This script adds the missing row, exactly as the fixed engine now writes one: type cost_adjustment,
-- quantity 0, totalValue = -correction, balance before/after = the quant balance after the receipt (-2), the same
-- reference as the correction journal. It was the only cost-correction journal on live (all organisations).
--
-- Idempotent: inserts nothing if the row is already there. Verifies the result and rolls back otherwise.
-- Run as a role that bypasses RLS (postgres), after a pg_dump backup:
--   sudo -u postgres psql -d erp -v ON_ERROR_STOP=1 -f docs/audits/2026-11-23-srv001-cost-correction-backfill.sql

BEGIN;

DO $$
DECLARE
    v_org   constant text := 'c059a3a5-e76f-4966-8ad7-6e6395b03a9c';
    v_prod  constant text := 'e50add8a-eacd-4d71-a5a1-aa0f0c235dce';
    v_src   record;
    v_ledger numeric;
    v_valuation numeric;
BEGIN
    SELECT * INTO v_src FROM "InventoryLedger"
     WHERE "organizationId" = v_org AND "productId" = v_prod AND "ledgerCode" = 'STK/000052' AND type = 'receipt';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'source receipt STK/000052 not found';
    END IF;
    PERFORM 1 FROM "JournalEntry"
     WHERE "organizationId" = v_org AND status = 'posted'
       AND "postingKey" = 'inventory:cost_correction:' || v_org || ':STK/000052';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'correction journal for STK/000052 not found';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM "InventoryLedger"
                    WHERE "organizationId" = v_org AND "productId" = v_prod AND type = 'cost_adjustment'
                      AND notes LIKE 'Negative-stock cost correction (STK/000052)%') THEN
        INSERT INTO "InventoryLedger" (id, "organizationId", "ledgerCode", "productId", "variantId", "locationId",
                type, "qtyBefore", "quantityChange", "balanceAfter", "unitCost", "totalValue", "referenceType",
                "referenceId", notes, "createdAt")
        VALUES (gen_random_uuid()::text, v_org,
                'STK/' || lpad(nextval('"seq_c059a3a5_stock_move"')::text, 6, '0'), v_prod, v_src."variantId",
                v_src."locationId", 'cost_adjustment', -2, 0, -2, 0, -8880000, v_src."referenceType",
                v_src."referenceId",
                'Negative-stock cost correction (STK/000052) — backfilled 2026-11-23, owner-approved repair',
                v_src."createdAt" + interval '1 millisecond');
    END IF;

    SELECT COALESCE(SUM(CASE WHEN "quantityChange" >= 0 THEN COALESCE("totalValue", 0)
                             ELSE -COALESCE("totalValue", 0) END), 0)
      INTO v_ledger FROM "InventoryLedger" WHERE "organizationId" = v_org AND "productId" = v_prod;
    SELECT COALESCE(SUM(quantity * "runningAverageCost"), 0)
      INTO v_valuation FROM "StockItem" WHERE "organizationId" = v_org AND "productId" = v_prod;
    IF v_ledger <> v_valuation THEN
        RAISE EXCEPTION 'after backfill ledger value % <> valuation %', v_ledger, v_valuation;
    END IF;
    RAISE NOTICE 'SRV-001 ledger value % = valuation %', v_ledger, v_valuation;
END $$;

COMMIT;
