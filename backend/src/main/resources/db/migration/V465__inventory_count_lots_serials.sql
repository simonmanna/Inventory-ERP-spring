-- Inventory hardening phase 2 (owner-approved deviation, docs/spec/inventory.md Deviations 10-16).
-- Count lines can count one lot of a batch-tracked product and carry the serial units found / missing.
ALTER TABLE "InventoryCountLine"
    ADD COLUMN "batchId" text,
    ADD COLUMN "batchNumber" text,
    ADD COLUMN "lotLine" boolean NOT NULL DEFAULT false,
    ADD COLUMN "missingSerials" text[],
    ADD COLUMN "foundSerials" text[];

