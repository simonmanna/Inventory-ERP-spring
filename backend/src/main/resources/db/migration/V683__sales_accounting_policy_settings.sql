-- Sales readiness R6 (B2): accounting policies the accountant decides, made configurable.
-- Every default is the behaviour that shipped before this migration, so nothing changes until the accountant chooses
-- (docs/spec/sales-postings.md, "Sales Accounting Go-Live Sign-off").
ALTER TABLE "SalesSettings"
    -- Q7 showroom VAT tax point: output VAT at cash-sale confirm (shipped) or at the head-office invoice
    ADD COLUMN "showroomTaxPoint" TEXT NOT NULL DEFAULT 'at_confirm'
        CHECK ("showroomTaxPoint" IN ('at_confirm', 'at_invoice')),
    -- Q5 weighbridge excess over the ordered quantity (within tolerance): billed (shipped) or absorbed
    ADD COLUMN "overOrderExcess" TEXT NOT NULL DEFAULT 'bill'
        CHECK ("overOrderExcess" IN ('bill', 'absorb')),
    -- Q6 rebate VAT: the tax a rebate settlement credit note carries (NULL = none, shipped)
    ADD COLUMN "rebateTaxId" TEXT REFERENCES "Tax" (id);
