-- Landed cost LC-1 (ADR-S053): valuation correctness.
--
-- 1. InventoryTransferLineage: which transfer_in row received the units of which transfer_out row, and how many.
--    Written by the transfer posting; backfilled exactly from the shared ledgerCode of the historic out/in pairs
--    (owner decision D1). A pair that is not exactly one out row and one in row of the same product and variant is
--    not guessed: it is listed as a LineageException.
-- 2. LineageException: structural lineage defects found while following a landed share (unpaired transfer, cycle,
--    depth). The share is parked in the 1396 adjunct until an authorised user resolves it.
-- 3. CostValueAdjustment: a landed share followed through a transfer is its own adjustment on the destination pool,
--    linked to its parent and root. New line targets: return (returned to the supplier) and in_transit (parked).
-- 4. Credit vouchers: LandedCost.voucherType 'credit' with originalVoucherId; charge amounts may be negative there.

CREATE TABLE "InventoryTransferLineage" (
    id                    TEXT PRIMARY KEY,
    "organizationId"      TEXT NOT NULL REFERENCES "Organization" (id),
    "transferId"          TEXT,
    "transferLineId"      TEXT,
    "sourceLedgerId"      TEXT NOT NULL REFERENCES "InventoryLedger" (id),
    "destinationLedgerId" TEXT NOT NULL REFERENCES "InventoryLedger" (id),
    quantity              NUMERIC(20, 6) NOT NULL CHECK (quantity > 0),
    "createdAt"           TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "InventoryTransferLineage_pair_uq"
    ON "InventoryTransferLineage" ("sourceLedgerId", "destinationLedgerId");
CREATE INDEX "InventoryTransferLineage_source_idx" ON "InventoryTransferLineage" ("organizationId", "sourceLedgerId");
CREATE INDEX "InventoryTransferLineage_dest_idx" ON "InventoryTransferLineage" ("organizationId", "destinationLedgerId");

CREATE TABLE "LineageException" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    kind             TEXT NOT NULL CHECK (kind IN ('unpaired_transfer', 'ambiguous_pair', 'cycle', 'depth', 'in_transit')),
    "ledgerId"       TEXT,
    "productId"      TEXT,
    "locationId"     TEXT,
    "adjustmentId"   TEXT,
    "sourceType"     TEXT,
    "sourceId"       TEXT,
    amount           NUMERIC(20, 6) NOT NULL DEFAULT 0,
    status           TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'resolved', 'written_off')),
    details          JSONB,
    resolution       TEXT,
    "resolvedBy"     TEXT,
    "resolvedAt"     TIMESTAMP(3),
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "LineageException_open_idx" ON "LineageException" ("organizationId", status);

-- Exact backfill: one transfer_out and one transfer_in row per ledgerCode, same product and variant.
WITH pairs AS (
    SELECT l."organizationId", l."ledgerCode",
           count(*) FILTER (WHERE l.type = 'transfer_out') AS outs,
           count(*) FILTER (WHERE l.type = 'transfer_in')  AS ins
    FROM "InventoryLedger" l
    WHERE l.type IN ('transfer_out', 'transfer_in')
    GROUP BY 1, 2
)
INSERT INTO "InventoryTransferLineage" (id, "organizationId", "sourceLedgerId", "destinationLedgerId", quantity,
                                        "createdAt")
SELECT gen_random_uuid()::text, o."organizationId", o.id, i.id, -o."quantityChange", o."createdAt"
FROM pairs p
JOIN "InventoryLedger" o ON o."organizationId" = p."organizationId" AND o."ledgerCode" = p."ledgerCode"
     AND o.type = 'transfer_out'
JOIN "InventoryLedger" i ON i."organizationId" = p."organizationId" AND i."ledgerCode" = p."ledgerCode"
     AND i.type = 'transfer_in'
WHERE p.outs = 1 AND p.ins = 1 AND o."productId" = i."productId"
  AND coalesce(o."variantId", '') = coalesce(i."variantId", '')
  AND o."quantityChange" = -i."quantityChange" AND o."quantityChange" < 0;

INSERT INTO "LineageException" (id, "organizationId", kind, "ledgerId", "productId", "locationId", details)
SELECT gen_random_uuid()::text, o."organizationId", 'ambiguous_pair', o.id, o."productId", o."locationId",
       jsonb_build_object('ledgerCode', o."ledgerCode", 'reason', 'backfill: no exact transfer_in pair')
FROM "InventoryLedger" o
WHERE o.type = 'transfer_out' AND o."quantityChange" < 0
  AND NOT EXISTS (SELECT 1 FROM "InventoryTransferLineage" t WHERE t."sourceLedgerId" = o.id);

ALTER TABLE "CostValueAdjustment"
    ADD COLUMN "parentAdjustmentId" TEXT REFERENCES "CostValueAdjustment" (id),
    ADD COLUMN "rootAdjustmentId"   TEXT,
    ADD COLUMN "sourceReceiptLedgerId" TEXT;
CREATE INDEX "CostValueAdjustment_root_idx" ON "CostValueAdjustment" ("rootAdjustmentId")
    WHERE "rootAdjustmentId" IS NOT NULL;

ALTER TABLE "CostValueAdjustmentLine" DROP CONSTRAINT "CostValueAdjustmentLine_target";
ALTER TABLE "CostValueAdjustmentLine" ADD CONSTRAINT "CostValueAdjustmentLine_target" CHECK (target IN ('on_hand',
    'cogs', 'wip', 'scrap', 'transfer', 'negative_stock', 'return', 'in_transit'));

-- Credit vouchers.
DO $$
DECLARE c text;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint WHERE conrelid = '"LandedCost"'::regclass AND contype = 'c'
             AND pg_get_constraintdef(oid) LIKE '%voucherType%' AND pg_get_constraintdef(oid) LIKE '%receipt%'
             AND pg_get_constraintdef(oid) NOT LIKE '%goodsReceiptId%' LOOP
        EXECUTE format('ALTER TABLE "LandedCost" DROP CONSTRAINT %I', c);
    END LOOP;
END $$;
ALTER TABLE "LandedCost" ADD CONSTRAINT "LandedCost_voucherType_chk"
    CHECK ("voucherType" IN ('receipt', 'voucher', 'credit'));
ALTER TABLE "LandedCost" DROP CONSTRAINT "LandedCost_receipt_chk";
ALTER TABLE "LandedCost" ADD CONSTRAINT "LandedCost_receipt_chk"
    CHECK ("voucherType" IN ('voucher', 'credit') OR ("goodsReceiptId" IS NOT NULL AND "creditAccountId" IS NOT NULL));
ALTER TABLE "LandedCost" ADD COLUMN "originalVoucherId" TEXT REFERENCES "LandedCost" (id);
ALTER TABLE "LandedCost" ADD CONSTRAINT "LandedCost_credit_original_chk"
    CHECK ("voucherType" <> 'credit' OR "originalVoucherId" IS NOT NULL);

ALTER TABLE "LandedCostCharge" DROP CONSTRAINT "LandedCostCharge_amount_chk";
ALTER TABLE "LandedCostCharge" ADD CONSTRAINT "LandedCostCharge_amount_chk" CHECK (amount <> 0);
-- A credit charge points at the charge it credits (Σ credits ≤ the original amount, checked by the service).
ALTER TABLE "LandedCostCharge" ADD COLUMN "creditOfChargeId" TEXT REFERENCES "LandedCostCharge" (id);
ALTER TABLE "LandedCostCharge" ADD CONSTRAINT "LandedCostCharge_sign_chk" CHECK (amount > 0 OR "creditOfChargeId" IS NOT NULL);

-- Per-target disposition of each allocation (stock / wip / cogs / scrap / return / adjunct), for reversal and audit.
ALTER TABLE "LandedCostAllocation" ADD COLUMN disposition JSONB;

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['landed_cost:resolve_lineage']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['landed_cost:resolve_lineage'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['InventoryTransferLineage', 'LineageException'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
