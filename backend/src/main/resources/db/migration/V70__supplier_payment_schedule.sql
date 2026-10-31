-- V70 (Phase 4, PROC-4): a planned payment date, amount and hold per open vendor bill.
--
-- A plan moves no money: payment still goes through /supplier-payments. The bill stays the source of truth for what
-- is owed; a row here only says when AP intends to pay it.

CREATE TABLE "SupplierPaymentSchedule" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "documentId"     TEXT           NOT NULL REFERENCES "Document" (id) ON DELETE CASCADE,
    "plannedDate"    DATE,
    "plannedAmount"  NUMERIC(20, 6) CHECK ("plannedAmount" IS NULL OR "plannedAmount" > 0),
    "onHold"         BOOLEAN        NOT NULL DEFAULT false,
    notes            TEXT,
    "createdBy"      TEXT,
    "updatedBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "SupplierPaymentSchedule_org_document_key"
    ON "SupplierPaymentSchedule" ("organizationId", "documentId");

ALTER TABLE "SupplierPaymentSchedule" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SupplierPaymentSchedule"
    USING (("organizationId" = current_setting('app.org_id', true)));
