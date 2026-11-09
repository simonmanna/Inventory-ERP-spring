-- Quotation form layout: section and note rows between the priced lines, a promised delivery date on the
-- quotation (passed on to the sales order as its commitment date) and a per-line delivery lead time in days.
ALTER TABLE "SalesQuotation" ADD COLUMN "commitmentDate" DATE;

ALTER TABLE "SalesQuotationLine"
    ADD COLUMN "displayType"  TEXT CHECK ("displayType" IS NULL OR "displayType" IN ('line_section', 'line_note')),
    ADD COLUMN "leadTimeDays" INTEGER CHECK ("leadTimeDays" IS NULL OR "leadTimeDays" >= 0);
