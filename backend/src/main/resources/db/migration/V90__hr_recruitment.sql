-- V90: HR-1 — recruitment and onboarding (Track C Phase 4). Job openings, applicants moved through a stage pipeline,
-- interviews, a hire step that creates the HrEmployee, and onboarding checklists generated from templates.
--
-- Additive extension beyond the NestJS reference; no existing table is changed. Branch references are plain text ids
-- validated in the service, like the rest of the Track C schema.

CREATE TABLE "HrJobOpening" (
    id                 TEXT         NOT NULL PRIMARY KEY,
    "organizationId"   TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code               TEXT         NOT NULL,
    title              TEXT         NOT NULL,
    "departmentId"     TEXT         REFERENCES "HrDepartment" (id),
    "positionId"       TEXT         REFERENCES "HrPosition" (id),
    "branchId"         TEXT,
    "hiringManagerId"  TEXT         REFERENCES "HrEmployee" (id),
    "employmentType"   TEXT         NOT NULL DEFAULT 'FULL_TIME',
    headcount          INTEGER      NOT NULL DEFAULT 1 CHECK (headcount > 0),
    status             TEXT         NOT NULL DEFAULT 'draft'
        CHECK (status IN ('draft', 'open', 'on_hold', 'closed', 'cancelled')),
    "openedOn"         DATE,
    "closesOn"         DATE,
    description        TEXT,
    "createdBy"        TEXT,
    "updatedBy"        TEXT,
    "createdAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"        TIMESTAMP(3)
);
CREATE UNIQUE INDEX "HrJobOpening_org_code_key" ON "HrJobOpening" ("organizationId", code) WHERE "deletedAt" IS NULL;
CREATE INDEX "HrJobOpening_org_status_idx" ON "HrJobOpening" ("organizationId", status);

CREATE TABLE "HrApplicant" (
    id                 TEXT         NOT NULL PRIMARY KEY,
    "organizationId"   TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code               TEXT         NOT NULL,
    "openingId"        TEXT         NOT NULL REFERENCES "HrJobOpening" (id),
    "firstName"        TEXT         NOT NULL,
    "lastName"         TEXT,
    email              TEXT,
    phone              TEXT,
    source             TEXT,
    stage              TEXT         NOT NULL DEFAULT 'applied'
        CHECK (stage IN ('applied', 'screening', 'interview', 'offer', 'hired', 'rejected', 'withdrawn')),
    "stageReason"      TEXT,
    "appliedOn"        DATE         NOT NULL DEFAULT CURRENT_DATE,
    notes              TEXT,
    -- Set by the hire step: the employee this applicant became.
    "employeeId"       TEXT         REFERENCES "HrEmployee" (id),
    "hiredAt"          TIMESTAMP(3),
    "createdBy"        TEXT,
    "updatedBy"        TEXT,
    "createdAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"        TIMESTAMP(3)
);
CREATE UNIQUE INDEX "HrApplicant_org_code_key" ON "HrApplicant" ("organizationId", code);
CREATE INDEX "HrApplicant_org_opening_idx" ON "HrApplicant" ("organizationId", "openingId", stage);
-- The same address cannot apply twice to one opening.
CREATE UNIQUE INDEX "HrApplicant_opening_email_key" ON "HrApplicant" ("openingId", lower(email))
    WHERE email IS NOT NULL AND "deletedAt" IS NULL;

CREATE TABLE "HrInterview" (
    id                 TEXT         NOT NULL PRIMARY KEY,
    "organizationId"   TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "applicantId"      TEXT         NOT NULL REFERENCES "HrApplicant" (id) ON DELETE CASCADE,
    "scheduledAt"      TIMESTAMP(3) NOT NULL,
    interviewer        TEXT,
    location           TEXT,
    status             TEXT         NOT NULL DEFAULT 'scheduled'
        CHECK (status IN ('scheduled', 'completed', 'cancelled')),
    outcome            TEXT         CHECK (outcome IS NULL OR outcome IN ('pass', 'fail', 'hold')),
    score              INTEGER      CHECK (score IS NULL OR score BETWEEN 1 AND 5),
    notes              TEXT,
    "createdBy"        TEXT,
    "updatedBy"        TEXT,
    "createdAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "HrInterview_org_applicant_idx" ON "HrInterview" ("organizationId", "applicantId");

CREATE TABLE "HrOnboardingTemplate" (
    id                 TEXT         NOT NULL PRIMARY KEY,
    "organizationId"   TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    title              TEXT         NOT NULL,
    description        TEXT,
    sequence           INTEGER      NOT NULL DEFAULT 10,
    -- Due this many days after the hire date.
    "dueDays"          INTEGER      NOT NULL DEFAULT 0 CHECK ("dueDays" >= 0),
    "isRequired"       BOOLEAN      NOT NULL DEFAULT true,
    responsible        TEXT,
    "isActive"         BOOLEAN      NOT NULL DEFAULT true,
    "createdBy"        TEXT,
    "updatedBy"        TEXT,
    "createdAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"        TIMESTAMP(3)
);
CREATE INDEX "HrOnboardingTemplate_org_idx" ON "HrOnboardingTemplate" ("organizationId", sequence);

CREATE TABLE "HrOnboardingTask" (
    id                 TEXT         NOT NULL PRIMARY KEY,
    "organizationId"   TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "employeeId"       TEXT         NOT NULL REFERENCES "HrEmployee" (id),
    "applicantId"      TEXT         REFERENCES "HrApplicant" (id),
    "templateId"       TEXT         REFERENCES "HrOnboardingTemplate" (id),
    title              TEXT         NOT NULL,
    description        TEXT,
    sequence           INTEGER      NOT NULL DEFAULT 10,
    "dueOn"            DATE,
    "isRequired"       BOOLEAN      NOT NULL DEFAULT true,
    responsible        TEXT,
    status             TEXT         NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'done', 'skipped')),
    "completedAt"      TIMESTAMP(3),
    "completedBy"      TEXT,
    notes              TEXT,
    "createdBy"        TEXT,
    "createdAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "HrOnboardingTask_org_employee_idx" ON "HrOnboardingTask" ("organizationId", "employeeId", status);
-- Generating a checklist twice adds no duplicates.
CREATE UNIQUE INDEX "HrOnboardingTask_employee_template_key" ON "HrOnboardingTask" ("employeeId", "templateId")
    WHERE "templateId" IS NOT NULL;

-- ---------------------------------------------------------------- tenancy

ALTER TABLE "HrJobOpening" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "HrJobOpening" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "HrApplicant" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "HrApplicant" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "HrInterview" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "HrInterview" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "HrOnboardingTemplate" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "HrOnboardingTemplate" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "HrOnboardingTask" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "HrOnboardingTask" USING (("organizationId" = current_setting('app.org_id', true)));

-- ---------------------------------------------------------------- permissions

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['hr:recruitment']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['hr:recruitment'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
