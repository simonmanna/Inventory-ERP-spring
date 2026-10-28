-- V14: multi-supplier landed cost — each charge line carries its own supplier payable (ADR-S007 §2a).
-- billMode: 'generate' = posting the voucher raises a posted vendor bill per (supplier, invoice no.) that debits the
-- landed-cost clearing account, so each supplier keeps its own invoice and AP balance; 'link' = the supplier's bill was
-- entered separately and is referenced here; 'none' = the pre-V14 behaviour (clearing only, bill booked by hand).

ALTER TABLE "LandedCostCharge"
    ADD COLUMN "billMode"     TEXT NOT NULL DEFAULT 'none',
    ADD COLUMN "vendorBillId" TEXT,
    ADD COLUMN "dueDate"      DATE;

ALTER TABLE "LandedCostCharge" ADD CONSTRAINT "LandedCostCharge_billMode_chk"
    CHECK ("billMode" IN ('generate', 'link', 'none'));
ALTER TABLE "LandedCostCharge" ADD CONSTRAINT "LandedCostCharge_link_chk"
    CHECK ("billMode" <> 'link' OR "vendorBillId" IS NOT NULL);

CREATE INDEX "LandedCostCharge_vendorBill_idx" ON "LandedCostCharge" ("organizationId", "vendorBillId");
