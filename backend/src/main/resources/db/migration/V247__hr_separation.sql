-- V247: HP-6 — separation: offboarding with clearance checklist, final settlement (posted to the GL) and the monthly
-- end-of-service gratuity provision (IAS 19 defined-benefit, measured on the statutory formula).

CREATE TABLE "HrSeparation" (
    id                  TEXT           NOT NULL PRIMARY KEY,
    "organizationId"    TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "separationNumber"  TEXT           NOT NULL,
    "employeeId"        TEXT           NOT NULL REFERENCES "HrEmployee" (id),
    "separationType"    TEXT           NOT NULL CHECK ("separationType" IN ('RESIGNATION', 'TERMINATION',
                            'END_OF_CONTRACT', 'RETIREMENT', 'DEATH', 'REDUNDANCY', 'DISMISSAL')),
    "noticeDate"        DATE           NOT NULL,
    "lastWorkingDay"    DATE           NOT NULL,
    "noticeDaysRequired" INTEGER       NOT NULL DEFAULT 30,
    -- Employer pays notice in lieu (+) or recovers short notice from the employee (-).
    "noticePayDays"     NUMERIC(8, 2)  NOT NULL DEFAULT 0,
    reason              TEXT,
    status              TEXT           NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'CLEARANCE', 'CALCULATED',
                            'APPROVED', 'PAID', 'CANCELLED')),
    settlement          JSONB,
    "netAmount"         NUMERIC(20, 6),
    "journalEntryId"    TEXT,
    "paymentJournalId"  TEXT,
    "approvedBy"        TEXT,
    "approvedAt"        TIMESTAMP(3),
    "paidAt"            TIMESTAMP(3),
    "createdBy"         TEXT,
    "updatedBy"         TEXT,
    "createdAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"         TIMESTAMP(3),
    CHECK ("lastWorkingDay" >= "noticeDate" - 365)
);
CREATE UNIQUE INDEX "HrSeparation_number_key" ON "HrSeparation" ("organizationId", "separationNumber");
CREATE UNIQUE INDEX "HrSeparation_one_open_key" ON "HrSeparation" ("employeeId")
    WHERE status NOT IN ('CANCELLED', 'PAID') AND "deletedAt" IS NULL;

CREATE TABLE "HrSeparationTask" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "separationId"   TEXT         NOT NULL REFERENCES "HrSeparation" (id) ON DELETE CASCADE,
    task             TEXT         NOT NULL,
    department       TEXT,
    "isDone"         BOOLEAN      NOT NULL DEFAULT false,
    "doneBy"         TEXT,
    "doneAt"         TIMESTAMP(3),
    note             TEXT,
    "sortOrder"      INTEGER      NOT NULL DEFAULT 0,
    "createdBy"      TEXT,
    "updatedBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"      TIMESTAMP(3)
);
CREATE INDEX "HrSeparationTask_sep_idx" ON "HrSeparationTask" ("separationId");

-- One row per employee and month end: the liability and the movement posted.
CREATE TABLE "HrGratuityProvision" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "employeeId"     TEXT           NOT NULL REFERENCES "HrEmployee" (id),
    "asOf"           DATE           NOT NULL,
    liability        NUMERIC(20, 6) NOT NULL,
    movement         NUMERIC(20, 6) NOT NULL,
    "journalEntryId" TEXT,
    "createdBy"      TEXT,
    "updatedBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"      TIMESTAMP(3)
);
CREATE UNIQUE INDEX "HrGratuityProvision_key" ON "HrGratuityProvision" ("employeeId", "asOf") WHERE "deletedAt" IS NULL;

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['HrSeparation', 'HrSeparationTask', 'HrGratuityProvision'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['hr:separation']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['hr:separation'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
