-- V340: Branch cash sales → head-office invoicing (ADR-S056, docs/spec/cash-sales.md).
--
-- * BranchSalesProfile: one per branch — showroom stock location, tender accounts per payment method, walk-in
--   customer, numbering prefix, segregation and fiscal trigger.
-- * SalesSubmission (+ lines, payments, invoice attempts, events): the Cash Sales Submission (CSS). Showroom confirm
--   posts stock, verified payments (customer deposits) and the revenue / VAT accrual once; head office reviews and
--   generates the invoice (a reclassification of the accrual).
-- * FiscalDocument: idempotent fiscal (EFRIS) operations per submission, processed outside the business transaction.
-- * CustomerDeposit: source 'cash_sale' linked to its submission (applicable only to that submission's invoice).

CREATE TABLE "BranchSalesProfile" (
    id                      TEXT         NOT NULL PRIMARY KEY,
    "organizationId"        TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "branchId"              TEXT         NOT NULL,
    "invoicingMode"         TEXT         NOT NULL DEFAULT 'head_office' CHECK ("invoicingMode" IN ('head_office')),
    "locationId"            TEXT         NOT NULL,
    "cashAccountId"         TEXT,
    "momoMtnAccountId"      TEXT,
    "momoAirtelAccountId"   TEXT,
    "bankClearingAccountId" TEXT,
    "walkInPartnerId"       TEXT,
    "numberPrefix"          TEXT         NOT NULL,
    "requireSegregation"    BOOLEAN      NOT NULL DEFAULT false,
    "fiscalTrigger"         TEXT CHECK ("fiscalTrigger" IN ('at_confirm', 'at_invoice')),
    "fiscalProvider"        TEXT         NOT NULL DEFAULT 'none',
    "rejectedAlertDays"     INTEGER      NOT NULL DEFAULT 7 CHECK ("rejectedAlertDays" > 0),
    "isActive"              BOOLEAN      NOT NULL DEFAULT true,
    "createdById"           TEXT,
    "createdAt"             TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"             TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "BranchSalesProfile_org_branch_key" ON "BranchSalesProfile" ("organizationId", "branchId");
CREATE UNIQUE INDEX "BranchSalesProfile_org_prefix_key" ON "BranchSalesProfile" ("organizationId", "numberPrefix");

CREATE TABLE "SalesSubmission" (
    id                     TEXT           NOT NULL PRIMARY KEY,
    "organizationId"       TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    number                 TEXT           NOT NULL,
    "branchId"             TEXT           NOT NULL,
    "profileId"            TEXT           NOT NULL REFERENCES "BranchSalesProfile" (id),
    "locationId"           TEXT           NOT NULL,
    "partnerId"            TEXT           NOT NULL,
    "customerName"         TEXT,
    "customerPhone"        TEXT,
    "customerTin"          TEXT,
    "customerAddress"      TEXT,
    "salespersonId"        TEXT,
    "saleDate"             DATE           NOT NULL,
    "currencyCode"         TEXT           NOT NULL,
    "exchangeRate"         NUMERIC(20, 10) NOT NULL DEFAULT 1 CHECK ("exchangeRate" > 0),
    subtotal               NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "discountTotal"        NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "taxTotal"             NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "grandTotal"           NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "paidTotal"            NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "executionStatus"      TEXT           NOT NULL DEFAULT 'draft'
        CHECK ("executionStatus" IN ('draft', 'confirmed', 'voided', 'cancelled')),
    "processingStatus"     TEXT           NOT NULL DEFAULT 'not_submitted'
        CHECK ("processingStatus" IN ('not_submitted', 'submitted', 'under_review', 'returned_for_correction',
                                      'approved', 'invoiced', 'rejected')),
    "fiscalStatus"         TEXT           NOT NULL DEFAULT 'none'
        CHECK ("fiscalStatus" IN ('none', 'pending', 'issued', 'failed', 'cancellation_pending', 'cancelled')),
    "confirmedAt"          TIMESTAMP(3),
    "confirmedById"        TEXT,
    "submittedAt"          TIMESTAMP(3),
    "submittedById"        TEXT,
    "reviewStartedAt"      TIMESTAMP(3),
    "reviewStartedById"    TEXT,
    "approvedAt"           TIMESTAMP(3),
    "approvedById"         TEXT,
    "invoicedAt"           TIMESTAMP(3),
    "invoicedById"         TEXT,
    "rejectedAt"           TIMESTAMP(3),
    "rejectedById"         TEXT,
    "rejectionReason"      TEXT,
    "ownerUserId"          TEXT,
    "voidedAt"             TIMESTAMP(3),
    "voidedById"           TEXT,
    "voidReason"           TEXT,
    "accrualJournalEntryId" TEXT,
    "currentInvoiceId"     TEXT,
    remarks                TEXT,
    version                INTEGER        NOT NULL DEFAULT 0,
    "createdById"          TEXT,
    "createdAt"            TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"            TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "SalesSubmission_org_number_key" ON "SalesSubmission" ("organizationId", number);
CREATE INDEX "SalesSubmission_org_branch_idx" ON "SalesSubmission" ("organizationId", "branchId", "processingStatus");
CREATE INDEX "SalesSubmission_org_processing_idx" ON "SalesSubmission" ("organizationId", "processingStatus");

CREATE TABLE "SalesSubmissionLine" (
    id                TEXT           NOT NULL PRIMARY KEY,
    "organizationId"  TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "submissionId"    TEXT           NOT NULL REFERENCES "SalesSubmission" (id) ON DELETE CASCADE,
    "sortOrder"       INTEGER        NOT NULL DEFAULT 0,
    "productId"       TEXT           NOT NULL,
    description       TEXT           NOT NULL,
    quantity          NUMERIC(20, 6) NOT NULL CHECK (quantity > 0),
    "uomId"           TEXT,
    "uomFactor"       NUMERIC(20, 10) NOT NULL DEFAULT 1,
    "listPrice"       NUMERIC(20, 6),
    "unitPrice"       NUMERIC(20, 6) NOT NULL CHECK ("unitPrice" >= 0),
    "discountPercent" NUMERIC(9, 4)  NOT NULL DEFAULT 0 CHECK ("discountPercent" BETWEEN 0 AND 100),
    "priceSource"     TEXT,
    "taxId"           TEXT,
    "taxInclusive"    BOOLEAN        NOT NULL DEFAULT false,
    "lineSubtotal"    NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "lineTax"         NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "lineTotal"       NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "costSnapshot"    NUMERIC(20, 6),
    "issueLedgerCode" TEXT,
    "createdAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "SalesSubmissionLine_submission_idx" ON "SalesSubmissionLine" ("submissionId");

CREATE TABLE "SalesSubmissionPayment" (
    id                   TEXT           NOT NULL PRIMARY KEY,
    "organizationId"     TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "submissionId"       TEXT           NOT NULL REFERENCES "SalesSubmission" (id) ON DELETE CASCADE,
    method               TEXT           NOT NULL CHECK (method IN ('cash', 'mobile_money', 'bank')),
    provider             TEXT CHECK (provider IN ('mtn', 'airtel')),
    amount               NUMERIC(20, 6) NOT NULL CHECK (amount > 0),
    reference            TEXT,
    "accountId"          TEXT           NOT NULL,
    "customerDepositId"  TEXT,
    "collectedById"      TEXT,
    "collectedAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "verificationStatus" TEXT           NOT NULL DEFAULT 'pending'
        CHECK ("verificationStatus" IN ('pending', 'verified', 'failed')),
    "verifiedById"       TEXT,
    "verifiedAt"         TIMESTAMP(3),
    "verificationSource" TEXT CHECK ("verificationSource" IN ('confirmer_cash', 'momo_gateway', 'verifier')),
    "verificationNote"   TEXT,
    "settlementStatus"   TEXT           NOT NULL DEFAULT 'unsettled'
        CHECK ("settlementStatus" IN ('unsettled', 'settled', 'remitted')),
    "createdAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (method <> 'mobile_money' OR provider IS NOT NULL)
);
CREATE INDEX "SalesSubmissionPayment_submission_idx" ON "SalesSubmissionPayment" ("submissionId");
CREATE UNIQUE INDEX "SalesSubmissionPayment_reference_key" ON "SalesSubmissionPayment"
    ("organizationId", method, COALESCE(provider, ''), reference) WHERE reference IS NOT NULL;

CREATE TABLE "SalesSubmissionInvoice" (
    id                 TEXT         NOT NULL PRIMARY KEY,
    "organizationId"   TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "submissionId"     TEXT         NOT NULL REFERENCES "SalesSubmission" (id) ON DELETE CASCADE,
    attempt            INTEGER      NOT NULL,
    "invoiceId"        TEXT         NOT NULL,
    status             TEXT         NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'cancelled')),
    "createdById"      TEXT,
    "createdAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "cancelledAt"      TIMESTAMP(3),
    "cancelledById"    TEXT,
    "cancelReason"     TEXT
);
CREATE UNIQUE INDEX "SalesSubmissionInvoice_active_key" ON "SalesSubmissionInvoice" ("submissionId")
    WHERE status = 'active';
CREATE UNIQUE INDEX "SalesSubmissionInvoice_attempt_key" ON "SalesSubmissionInvoice" ("submissionId", attempt);
CREATE UNIQUE INDEX "SalesSubmissionInvoice_invoice_key" ON "SalesSubmissionInvoice" ("invoiceId");

CREATE TABLE "SalesSubmissionEvent" (
    id                TEXT         NOT NULL PRIMARY KEY,
    "organizationId"  TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "submissionId"    TEXT         NOT NULL REFERENCES "SalesSubmission" (id) ON DELETE CASCADE,
    action            TEXT         NOT NULL,
    "fromExecution"   TEXT,
    "toExecution"     TEXT,
    "fromProcessing"  TEXT,
    "toProcessing"    TEXT,
    reason            TEXT,
    field             TEXT,
    "oldValue"        TEXT,
    "newValue"        TEXT,
    "userId"          TEXT,
    snapshot          JSONB,
    "createdAt"       TIMESTAMP(3) NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX "SalesSubmissionEvent_submission_idx" ON "SalesSubmissionEvent" ("submissionId", "createdAt");

CREATE TABLE "FiscalDocument" (
    id                TEXT         NOT NULL PRIMARY KEY,
    "organizationId"  TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "submissionId"    TEXT         NOT NULL REFERENCES "SalesSubmission" (id) ON DELETE CASCADE,
    "invoiceId"       TEXT,
    provider          TEXT         NOT NULL,
    operation         TEXT         NOT NULL CHECK (operation IN ('issue', 'cancel', 'correct')),
    "requestRef"      TEXT         NOT NULL,
    status            TEXT         NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'unknown', 'succeeded', 'failed')),
    attempts          INTEGER      NOT NULL DEFAULT 0,
    "lastError"       TEXT,
    "externalRef"     TEXT,
    "fiscalNumber"    TEXT,
    "requestPayload"  JSONB,
    "responsePayload" JSONB,
    reason            TEXT,
    "userId"          TEXT,
    "createdAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "FiscalDocument_request_ref_key" ON "FiscalDocument" ("organizationId", "requestRef");
-- One live (not failed) operation of each kind per submission at a time.
CREATE UNIQUE INDEX "FiscalDocument_live_op_key" ON "FiscalDocument" ("organizationId", "submissionId", operation)
    WHERE status IN ('pending', 'unknown');
CREATE INDEX "FiscalDocument_open_idx" ON "FiscalDocument" (status) WHERE status IN ('pending', 'unknown');

ALTER TABLE "CustomerDeposit" DROP CONSTRAINT IF EXISTS "CustomerDeposit_source_check";
ALTER TABLE "CustomerDeposit"
    ADD CONSTRAINT "CustomerDeposit_source_check" CHECK (source IN ('receipt', 'down_payment_invoice', 'cash_sale')),
    ADD COLUMN "cashSaleId" TEXT,
    ADD COLUMN "branchId"   TEXT;
CREATE INDEX "CustomerDeposit_cash_sale_idx" ON "CustomerDeposit" ("cashSaleId") WHERE "cashSaleId" IS NOT NULL;
ALTER TABLE "CustomerDeposit"
    ADD CONSTRAINT "CustomerDeposit_cash_sale_link_check" CHECK (source <> 'cash_sale' OR "cashSaleId" IS NOT NULL);

DO $$
DECLARE
    t    TEXT;
    expr TEXT := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['BranchSalesProfile', 'SalesSubmission', 'SalesSubmissionLine', 'SalesSubmissionPayment',
                             'SalesSubmissionInvoice', 'SalesSubmissionEvent', 'FiscalDocument'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['sales:cash_sale:read', 'sales:cash_sale:read_all', 'sales:cash_sale:create',
                  'sales:cash_sale:confirm', 'sales:cash_sale:submit', 'sales:cash_sale:review',
                  'sales:cash_sale:approve', 'sales:cash_sale:invoice', 'sales:cash_sale:void',
                  'sales:cash_sale:verify_payment', 'sales:branch_profile:manage']) AS k
ON CONFLICT (key) DO NOTHING;
UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['sales:cash_sale:read', 'sales:cash_sale:read_all',
        'sales:cash_sale:create', 'sales:cash_sale:confirm', 'sales:cash_sale:submit', 'sales:cash_sale:review',
        'sales:cash_sale:approve', 'sales:cash_sale:invoice', 'sales:cash_sale:void', 'sales:cash_sale:verify_payment',
        'sales:branch_profile:manage'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
