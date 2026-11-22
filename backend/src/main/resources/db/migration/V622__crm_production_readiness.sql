-- CRM production-readiness fixes (PR A, 2026-11-22; docs/spec/crm.md "Production-readiness fixes").

-- P0-4: a public quotation link is bound to the revision it was issued for, and the answer keeps an immutable copy of
-- the exact commercial terms (header + lines) the customer accepted or declined, with the client's user agent.
ALTER TABLE "SalesQuoteLink"
    ADD COLUMN "revision"         INTEGER,
    ADD COLUMN "decidedRevision"  INTEGER,
    ADD COLUMN "decisionSnapshot" JSONB,
    ADD COLUMN "signerUserAgent"  TEXT;
UPDATE "SalesQuoteLink" l SET "revision" = q."revision" FROM "SalesQuotation" q WHERE q.id = l."quotationId";
-- Every link has a revision from now on (the service always sets it).
ALTER TABLE "SalesQuoteLink" ALTER COLUMN "revision" SET NOT NULL;

-- P0-5: website lead forms get a rolling 24-hour submission cap per form (an organization-wide cap applies on top).
ALTER TABLE "CrmLeadForm"
    ADD COLUMN "dailyLimit"  INTEGER NOT NULL DEFAULT 200 CHECK ("dailyLimit" BETWEEN 1 AND 100000),
    ADD COLUMN "windowStart" TIMESTAMP(3),
    ADD COLUMN "windowCount" INTEGER NOT NULL DEFAULT 0;

-- P0-6: CRM e-mails go through a transactional outbox: the request only writes this row (with the activity it logs),
-- a worker sends after commit and records sent / failed with retries; nothing claims "sent" before the SMTP server
-- accepted the message.
CREATE TABLE "CrmEmailOutbox" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "activityId"     TEXT         REFERENCES "Activity" (id) ON DELETE SET NULL,
    "dealId"         TEXT         REFERENCES "Deal" (id) ON DELETE SET NULL,
    "toAddress"      TEXT         NOT NULL,
    subject          TEXT         NOT NULL,
    body             TEXT         NOT NULL,
    status           TEXT         NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'sent', 'failed')),
    attempts         INTEGER      NOT NULL DEFAULT 0,
    "nextAttemptAt"  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "lastError"      TEXT,
    "sentAt"         TIMESTAMP(3),
    "createdById"    TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "CrmEmailOutbox_due_idx" ON "CrmEmailOutbox" ("nextAttemptAt") WHERE status = 'pending';
CREATE INDEX "CrmEmailOutbox_org_idx" ON "CrmEmailOutbox" ("organizationId", "createdAt");
CREATE INDEX "CrmEmailOutbox_activity_idx" ON "CrmEmailOutbox" ("activityId");
CREATE INDEX "CrmEmailOutbox_deal_idx" ON "CrmEmailOutbox" ("dealId");

-- Standard tenant policy (V214 / V605 expression): the worker runs unscoped, every scoped query sees its own org.
ALTER TABLE "CrmEmailOutbox" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CrmEmailOutbox"
    USING (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true))
    WITH CHECK (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true));
