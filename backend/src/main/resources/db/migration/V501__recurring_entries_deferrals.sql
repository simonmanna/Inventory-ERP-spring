-- ADR-S073 (1/3): recurring journal entries and deferred revenue / prepaid expense schedules.
--
-- RecurringJournalTemplate holds the entry to repeat (lines as JSON), its calendar frequency and the next run date.
-- RecurringJournalRun is the history: one row per template and occurrence date (unique), so a job that runs twice
-- generates an occurrence once. mode 'post' posts through PostingService with postingKey recurring:<id>:<date>;
-- mode 'draft' stages a draft for a second user to approve (maker-checker).
--
-- DeferralSchedule releases a balance-sheet balance (deferred revenue / prepaid expense) to a P&L account over a
-- number of months. DeferralRelease holds the planned slices (unique per schedule and period index) and the entry
-- that released each one (postingKey deferral:<scheduleId>:<periodIndex>).
CREATE TABLE "RecurringJournalTemplate" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    name             TEXT           NOT NULL,
    "journalCode"    TEXT           NOT NULL DEFAULT 'GEN',
    description      TEXT,
    frequency        TEXT           NOT NULL,
    "intervalCount"  INTEGER        NOT NULL DEFAULT 1,
    "startDate"      DATE           NOT NULL,
    "endDate"        DATE,
    "nextRunDate"    DATE,
    mode             TEXT           NOT NULL DEFAULT 'post',
    status           TEXT           NOT NULL DEFAULT 'active',
    "currencyId"     TEXT,
    "branchId"       TEXT,
    "costCenterId"   TEXT,
    lines            JSONB          NOT NULL,
    "totalAmount"    NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "lastRunDate"    DATE,
    "runCount"       INTEGER        NOT NULL DEFAULT 0,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"      TIMESTAMP(3),
    CONSTRAINT "RecurringJournalTemplate_frequency" CHECK (frequency IN ('daily', 'weekly', 'monthly', 'quarterly',
        'yearly')),
    CONSTRAINT "RecurringJournalTemplate_mode" CHECK (mode IN ('post', 'draft')),
    CONSTRAINT "RecurringJournalTemplate_status" CHECK (status IN ('active', 'paused', 'ended')),
    CONSTRAINT "RecurringJournalTemplate_interval" CHECK ("intervalCount" BETWEEN 1 AND 365)
);
CREATE INDEX "RecurringJournalTemplate_due_idx" ON "RecurringJournalTemplate" ("organizationId", status, "nextRunDate")
    WHERE "deletedAt" IS NULL;

CREATE TABLE "RecurringJournalRun" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "templateId"     TEXT         NOT NULL REFERENCES "RecurringJournalTemplate" (id) ON DELETE CASCADE,
    "runDate"        DATE         NOT NULL,
    status           TEXT         NOT NULL,
    "journalEntryId" TEXT,
    error            TEXT,
    trigger          TEXT         NOT NULL DEFAULT 'job',
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "RecurringJournalRun_status" CHECK (status IN ('posted', 'draft', 'failed')),
    CONSTRAINT "RecurringJournalRun_trigger" CHECK (trigger IN ('job', 'manual'))
);
CREATE UNIQUE INDEX "RecurringJournalRun_template_date_key" ON "RecurringJournalRun" ("templateId", "runDate");
CREATE INDEX "RecurringJournalRun_org_idx" ON "RecurringJournalRun" ("organizationId", "templateId");

CREATE TABLE "DeferralSchedule" (
    id                TEXT PRIMARY KEY,
    "organizationId"  TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    name              TEXT           NOT NULL,
    kind              TEXT           NOT NULL,
    "sourceAccountId" TEXT           NOT NULL,
    "targetAccountId" TEXT           NOT NULL,
    "totalAmount"     NUMERIC(20, 6) NOT NULL,
    "startDate"       DATE           NOT NULL,
    months            INTEGER        NOT NULL,
    method            TEXT           NOT NULL DEFAULT 'monthly',
    status            TEXT           NOT NULL DEFAULT 'active',
    "journalCode"     TEXT           NOT NULL DEFAULT 'GEN',
    "journalLineId"   TEXT,
    "sourceEntryId"   TEXT,
    "reclassEntryId"  TEXT,
    "partnerId"       TEXT,
    "branchId"        TEXT,
    "costCenterId"    TEXT,
    "releasedAmount"  NUMERIC(20, 6) NOT NULL DEFAULT 0,
    notes             TEXT,
    "createdBy"       TEXT,
    "createdAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "cancelledAt"     TIMESTAMP(3),
    "cancelledBy"     TEXT,
    "cancelReason"    TEXT,
    CONSTRAINT "DeferralSchedule_kind" CHECK (kind IN ('revenue', 'expense')),
    CONSTRAINT "DeferralSchedule_method" CHECK (method IN ('monthly', 'daily')),
    CONSTRAINT "DeferralSchedule_status" CHECK (status IN ('active', 'completed', 'cancelled')),
    CONSTRAINT "DeferralSchedule_months" CHECK (months BETWEEN 1 AND 600),
    CONSTRAINT "DeferralSchedule_total" CHECK ("totalAmount" > 0)
);
CREATE INDEX "DeferralSchedule_org_idx" ON "DeferralSchedule" ("organizationId", status);
CREATE UNIQUE INDEX "DeferralSchedule_line_key" ON "DeferralSchedule" ("organizationId", "journalLineId")
    WHERE "journalLineId" IS NOT NULL AND status <> 'cancelled';

CREATE TABLE "DeferralRelease" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "scheduleId"     TEXT           NOT NULL REFERENCES "DeferralSchedule" (id) ON DELETE CASCADE,
    "periodIndex"    INTEGER        NOT NULL,
    "periodStart"    DATE           NOT NULL,
    "periodEnd"      DATE           NOT NULL,
    amount           NUMERIC(20, 6) NOT NULL,
    status           TEXT           NOT NULL DEFAULT 'planned',
    "journalEntryId" TEXT,
    "postedAt"       TIMESTAMP(3),
    CONSTRAINT "DeferralRelease_status" CHECK (status IN ('planned', 'posted', 'cancelled'))
);
CREATE UNIQUE INDEX "DeferralRelease_schedule_period_key" ON "DeferralRelease" ("scheduleId", "periodIndex");
CREATE INDEX "DeferralRelease_due_idx" ON "DeferralRelease" ("organizationId", status, "periodEnd");

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['RecurringJournalTemplate', 'RecurringJournalRun', 'DeferralSchedule', 'DeferralRelease']
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (coalesce(current_setting(''app.org_id'', true),'
                || ' '''') = '''' OR "organizationId" = current_setting(''app.org_id'', true)) WITH CHECK'
                || ' (coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" ='
                || ' current_setting(''app.org_id'', true))', t);
    END LOOP;
END $$;
