-- Readiness gaps h-05 (landed cost), stream gF.
--
-- F14: a customs calculation's link to a landed-cost charge becomes a real foreign key. Editing a draft voucher
--      rewrites its charges (new ids); the application moves each link to the new charge with the same line number and
--      kind (audited), and the key clears a link whose charge is gone instead of leaving it pointing at nothing.
--      Links that already point at nothing are cleared first (the immutability trigger allows this one column).
-- F1 / F3: the checks between supplier bills and voucher charges look up live charges per (organisation, supplier,
--      normalised supplier invoice number).

UPDATE "CustomsCalculation" cc SET "landedCostChargeId" = NULL
WHERE cc."landedCostChargeId" IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM "LandedCostCharge" c WHERE c.id = cc."landedCostChargeId");

ALTER TABLE "CustomsCalculation"
    ADD CONSTRAINT "CustomsCalculation_landedCostChargeId_fkey" FOREIGN KEY ("landedCostChargeId")
        REFERENCES "LandedCostCharge" (id) ON DELETE SET NULL;

CREATE INDEX "CustomsCalculation_charge_idx" ON "CustomsCalculation" ("landedCostChargeId")
    WHERE "landedCostChargeId" IS NOT NULL;

CREATE INDEX "LandedCostCharge_invoice_live_idx"
    ON "LandedCostCharge" ("organizationId", "supplierId", "supplierInvoiceRefNorm") WHERE live;
