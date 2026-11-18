-- Document reversals (owner decision 2026-11-17, "fix in this branch"): an entry a customer / supplier sub-ledger
-- document posted (invoice, bill, credit / debit note, payment, deposit, POS sale, ...) is no longer reversed by the
-- generic POST /journal-entries/{id}/reverse; each document has a document-level reversal workflow instead (invoice
-- cancel, bill void, credit-note and debit-note reversal, payment void, deposit-application reversal) that reverses the
-- GL, updates the sub-ledger, unwinds or refuses allocations, requires a reason, an optional approval and separation
-- of duties, runs in one transaction under row locks, and is idempotent. See docs/runbooks/acc-erp-controls-deployment.md
-- section 8.

-- 1. One row per reversed document: who, when, why, the original and reversal journal entries, and the allocations the
--    reversal released. Unique per document, so a document is never reversed twice (the workflows also check status
--    under a row lock; this is the backstop). Rows are never updated or deleted by the application.
CREATE TABLE "DocumentReversal" (
    id                 TEXT PRIMARY KEY,
    "organizationId"   TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "documentType"     TEXT         NOT NULL
        CHECK ("documentType" IN ('sales_invoice', 'vendor_bill', 'credit_note', 'debit_note', 'payment')),
    "documentId"       TEXT         NOT NULL,
    "documentNumber"   TEXT,
    "partnerId"        TEXT,
    reason             TEXT         NOT NULL CHECK (length(btrim(reason)) > 0),
    "originalEntryIds" TEXT[]       NOT NULL DEFAULT '{}',
    "reversalEntryIds" TEXT[]       NOT NULL DEFAULT '{}',
    unwound            JSONB        NOT NULL DEFAULT '[]'::jsonb,
    "reversedBy"       TEXT,
    "reversedAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "DocumentReversal_document_key" ON "DocumentReversal" ("organizationId", "documentType",
    "documentId");
CREATE INDEX "DocumentReversal_org_date_idx" ON "DocumentReversal" ("organizationId", "reversedAt");
CREATE INDEX "DocumentReversal_partner_idx" ON "DocumentReversal" ("organizationId", "partnerId");

ALTER TABLE "DocumentReversal" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "DocumentReversal"
    USING (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true))
    WITH CHECK (coalesce(current_setting('app.org_id', true), '') = ''
        OR "organizationId" = current_setting('app.org_id', true));

-- 2. What a debit note did to its referenced invoice / bill's open amount (+ raised, - lowered by a return to vendor),
--    recorded when it posts so its reversal gives back exactly that. NULL for notes posted before this migration (the
--    reversal then assumes the full raise, or min(total, payable - paid - residual) for a return to vendor).
ALTER TABLE "DebitNote" ADD COLUMN "documentAdjustment" NUMERIC(20, 6);

-- 3. Permissions for the new workflows: credit-note and debit-note reversal, and the supplier bill write-off
--    (expense:write_off; invoice cancel, bill void and payment void keep invoice:cancel,
--    expense:cancel and payment:void). Granted to Administrator, which by convention holds every permission
--    (SeedRunner and organization bootstrap give it Permissions.ALL); other roles only by an explicit role edit.
INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['credit_note:reverse', 'debit_note:reverse', 'expense:write_off']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['credit_note:reverse', 'debit_note:reverse', 'expense:write_off'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
