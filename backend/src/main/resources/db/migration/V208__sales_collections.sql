-- V32: Collections (ADR-S011, phase S8; roadmap 1.6) — dunning levels, dunning runs and their entries, promises to
-- pay and collection settings. Receivables stay in invoicing ("Document"); dunning only reads them, sends reminders
-- (e-mail with the statement PDF, or a letter), raises a draft late-fee invoice and can put the customer on credit hold.

CREATE TABLE "DunningLevel" (
    id                TEXT           NOT NULL PRIMARY KEY,
    "organizationId"  TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    sequence          INTEGER        NOT NULL CHECK (sequence > 0),
    name              TEXT           NOT NULL,
    -- Applies when the oldest overdue invoice is at least this many days past due.
    "daysOverdue"     INTEGER        NOT NULL CHECK ("daysOverdue" >= 0),
    channel           TEXT           NOT NULL DEFAULT 'email' CHECK (channel IN ('email', 'letter', 'none')),
    subject           TEXT           NOT NULL,
    body              TEXT           NOT NULL,
    "feeAmount"       NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("feeAmount" >= 0),
    "setCreditHold"   BOOLEAN        NOT NULL DEFAULT false,
    "isActive"        BOOLEAN        NOT NULL DEFAULT true,
    "createdAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "DunningLevel_org_sequence_key" ON "DunningLevel" ("organizationId", sequence);

CREATE TABLE "DunningRun" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "runDate"        DATE         NOT NULL,
    trigger          TEXT         NOT NULL DEFAULT 'manual' CHECK (trigger IN ('manual', 'scheduled')),
    entries          INTEGER      NOT NULL DEFAULT 0,
    skipped          INTEGER      NOT NULL DEFAULT 0,
    "createdById"    TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE "DunningEntry" (
    id                 TEXT           NOT NULL PRIMARY KEY,
    "organizationId"   TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "runId"            TEXT           NOT NULL REFERENCES "DunningRun" (id) ON DELETE CASCADE,
    "partnerId"        TEXT           NOT NULL,
    "levelId"          TEXT           NOT NULL REFERENCES "DunningLevel" (id),
    "levelSequence"    INTEGER        NOT NULL,
    "overdueAmount"    NUMERIC(20, 6) NOT NULL,
    "maxDaysOverdue"   INTEGER        NOT NULL,
    invoices           JSONB          NOT NULL,
    channel            TEXT           NOT NULL,
    -- sent | logged (no SMTP) | letter (to print) | failed | none
    delivery           TEXT           NOT NULL,
    recipient          TEXT,
    "feeInvoiceId"     TEXT,
    "creditHoldSet"    BOOLEAN        NOT NULL DEFAULT false,
    error              TEXT,
    "createdAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "DunningEntry_partner_idx" ON "DunningEntry" ("organizationId", "partnerId", "createdAt" DESC);

CREATE TABLE "PromiseToPay" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "partnerId"      TEXT           NOT NULL,
    amount           NUMERIC(20, 6) NOT NULL CHECK (amount > 0),
    "promisedDate"   DATE           NOT NULL,
    status           TEXT           NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'kept', 'broken', 'cancelled')),
    -- Receipts of the customer counted against the promise from this moment.
    "paidSince"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    notes            TEXT,
    "createdById"    TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "resolvedAt"     TIMESTAMP(3)
);
CREATE INDEX "PromiseToPay_partner_idx" ON "PromiseToPay" ("organizationId", "partnerId", status);

ALTER TABLE "SalesSettings"
    ADD COLUMN "dunningAutoRun"      BOOLEAN NOT NULL DEFAULT false,
    -- Monthly statement e-mail to customers with an open balance (1st of the month).
    ADD COLUMN "monthlyStatements"   BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE "DunningLevel" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "DunningLevel" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "DunningRun" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "DunningRun" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "DunningEntry" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "DunningEntry" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "PromiseToPay" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "PromiseToPay" USING (("organizationId" = current_setting('app.org_id', true)));

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['sales:collections:read', 'sales:collections:manage']) AS k
ON CONFLICT (key) DO NOTHING;
UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['sales:collections:read',
        'sales:collections:manage'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
