-- V250: HP-9 — talent: competencies, review cycles, weighted goals and appraisals (self, manager, peer).

CREATE TABLE "HrCompetency" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code             TEXT         NOT NULL,
    name             TEXT         NOT NULL,
    description      TEXT,
    "isActive"       BOOLEAN      NOT NULL DEFAULT true,
    "createdBy"      TEXT,
    "updatedBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"      TIMESTAMP(3)
);
CREATE UNIQUE INDEX "HrCompetency_org_code_key" ON "HrCompetency" ("organizationId", code) WHERE "deletedAt" IS NULL;

CREATE TABLE "HrReviewCycle" (
    id               TEXT          NOT NULL PRIMARY KEY,
    "organizationId" TEXT          NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    name             TEXT          NOT NULL,
    "periodStart"    DATE          NOT NULL,
    "periodEnd"      DATE          NOT NULL,
    status           TEXT          NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'OPEN', 'CLOSED')),
    -- Share of the overall score from goals; the rest comes from competencies.
    "goalWeightPct"  NUMERIC(5, 2) NOT NULL DEFAULT 60 CHECK ("goalWeightPct" BETWEEN 0 AND 100),
    "selfReview"     BOOLEAN       NOT NULL DEFAULT true,
    "peerReview"     BOOLEAN       NOT NULL DEFAULT false,
    "competencyIds"  JSONB         NOT NULL DEFAULT '[]',
    "departmentId"   TEXT,
    "createdBy"      TEXT,
    "updatedBy"      TEXT,
    "createdAt"      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"      TIMESTAMP(3),
    CHECK ("periodEnd" >= "periodStart")
);

CREATE TABLE "HrGoal" (
    id               TEXT          NOT NULL PRIMARY KEY,
    "organizationId" TEXT          NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "cycleId"        TEXT          NOT NULL REFERENCES "HrReviewCycle" (id) ON DELETE CASCADE,
    "employeeId"     TEXT          NOT NULL REFERENCES "HrEmployee" (id) ON DELETE CASCADE,
    title            TEXT          NOT NULL,
    description      TEXT,
    weight           NUMERIC(5, 2) NOT NULL CHECK (weight > 0 AND weight <= 100),
    target           TEXT,
    actual           TEXT,
    -- 1..5, set by the manager appraisal.
    score            NUMERIC(4, 2),
    status           TEXT          NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ACHIEVED', 'MISSED',
                         'CANCELLED')),
    "createdBy"      TEXT,
    "updatedBy"      TEXT,
    "createdAt"      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"      TIMESTAMP(3)
);
CREATE INDEX "HrGoal_cycle_emp_idx" ON "HrGoal" ("cycleId", "employeeId");

CREATE TABLE "HrAppraisal" (
    id                TEXT          NOT NULL PRIMARY KEY,
    "organizationId"  TEXT          NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "cycleId"         TEXT          NOT NULL REFERENCES "HrReviewCycle" (id) ON DELETE CASCADE,
    "employeeId"      TEXT          NOT NULL REFERENCES "HrEmployee" (id) ON DELETE CASCADE,
    "reviewerId"      TEXT          REFERENCES "HrEmployee" (id),
    "reviewerType"    TEXT          NOT NULL CHECK ("reviewerType" IN ('SELF', 'MANAGER', 'PEER')),
    -- {"<competencyId>": 1..5}
    "competencyScores" JSONB        NOT NULL DEFAULT '{}',
    -- {"<goalId>": 1..5}
    "goalScores"      JSONB         NOT NULL DEFAULT '{}',
    "goalScore"       NUMERIC(4, 2),
    "competencyScore" NUMERIC(4, 2),
    "overallScore"    NUMERIC(4, 2),
    strengths         TEXT,
    improvements      TEXT,
    "trainingNeeds"   TEXT,
    comments          TEXT,
    status            TEXT          NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SUBMITTED', 'FINALIZED')),
    "submittedAt"     TIMESTAMP(3),
    "reviewId"        TEXT,
    "createdBy"       TEXT,
    "updatedBy"       TEXT,
    "createdAt"       TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"       TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"       TIMESTAMP(3)
);
CREATE INDEX "HrAppraisal_cycle_idx" ON "HrAppraisal" ("cycleId", "employeeId");
CREATE UNIQUE INDEX "HrAppraisal_one_key" ON "HrAppraisal" ("cycleId", "employeeId", "reviewerType",
    coalesce("reviewerId", '')) WHERE "deletedAt" IS NULL;

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['HrCompetency', 'HrReviewCycle', 'HrGoal', 'HrAppraisal'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['hr:talent']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['hr:talent'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
