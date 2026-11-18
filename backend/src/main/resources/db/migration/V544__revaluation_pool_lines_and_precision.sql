-- Readiness gaps (h-02 F3, F9): inventory revaluation and NRV review documents.
--
-- F3: one line per pool. A document listing the same (product, variant, location) twice double-counted the NRV
--     allowance (every line read the same allowance before) and reversed duplicate revaluation lines in heap order.
--     The service refuses such a line on create and on post; this unique index enforces it in the database as well.
--     It is created only when no existing document lists a pool twice (such a draft can no longer be posted; a posted
--     one is left as it is and reported here for review).
-- F9: the posted amount is the quant's exact value change at the stock ledger's 6-decimal precision; the line amount,
--     the allowance before and the document total kept 2 decimals and rounded the difference away.

ALTER TABLE "InventoryRevaluationLine" ALTER COLUMN amount TYPE NUMERIC(20, 6);
ALTER TABLE "InventoryRevaluationLine" ALTER COLUMN "allowanceBefore" TYPE NUMERIC(20, 6);
ALTER TABLE "InventoryRevaluation" ALTER COLUMN "totalAmount" TYPE NUMERIC(20, 6);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM "InventoryRevaluationLine"
               GROUP BY "revaluationId", "productId", coalesce("variantId", ''), "locationId"
               HAVING count(*) > 1) THEN
        RAISE NOTICE 'InventoryRevaluationLine: a document lists a pool twice; unique index not created';
    ELSE
        CREATE UNIQUE INDEX "InventoryRevaluationLine_pool_key"
            ON "InventoryRevaluationLine" ("revaluationId", "productId", (coalesce("variantId", '')), "locationId");
    END IF;
END $$;
