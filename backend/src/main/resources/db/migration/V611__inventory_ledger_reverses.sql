-- Inventory hardening R1 (2026-11-23): a return or reversal row names the original movement it brings back, so
-- "which issue was returned, at what lot, serial and cost" is answerable from the ledger and the returnable quantity
-- of an issue row is (its quantity) minus (the rows that reverse it, net of their own reversals).
ALTER TABLE "InventoryLedger" ADD COLUMN "reversesLedgerId" TEXT REFERENCES "InventoryLedger" (id);

CREATE INDEX "InventoryLedger_organizationId_reversesLedgerId_idx" ON "InventoryLedger" ("organizationId", "reversesLedgerId")
    WHERE "reversesLedgerId" IS NOT NULL;
