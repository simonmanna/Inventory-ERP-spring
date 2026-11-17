-- V522: loss prevention, work package 6 (ADR-S077 §6): cases. A case states a variance and its review; it has no
-- outcome that records misconduct. Its timeline is append-only.

CREATE TABLE "LpCase" (
    id                     TEXT           NOT NULL PRIMARY KEY,
    "organizationId"       TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "caseSeq"              INTEGER        NOT NULL,
    "caseNumber"           TEXT           NOT NULL,
    fingerprint            TEXT           NOT NULL,
    engine                 TEXT           NOT NULL,
    "ruleCode"             TEXT           NOT NULL,
    "branchId"             TEXT           NOT NULL DEFAULT '',
    "businessDate"         DATE           NOT NULL,
    title                  TEXT           NOT NULL,
    severity               TEXT           NOT NULL CHECK (severity IN ('info', 'warning', 'critical')),
    state                  TEXT           NOT NULL DEFAULT 'open'
        CHECK (state IN ('open', 'reviewing', 'escalated', 'closed')),
    outcome                TEXT
        CHECK (outcome IS NULL OR outcome IN ('explained', 'confirmed_variance', 'false_alarm', 'inconclusive')),
    "causeCode"            TEXT,
    "subjectType"          TEXT,
    "subjectId"            TEXT,
    -- The user the case is about, when the subject is one. That user never sees the case.
    "subjectUserId"        TEXT,
    "assigneeId"           TEXT,
    "currentDiscrepancyId" TEXT,
    "exposureAmount"       NUMERIC(20, 6),
    "exposureMethod"       TEXT,
    "openedAt"             TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "reviewStartedAt"      TIMESTAMP(3),
    "closedAt"             TIMESTAMP(3),
    -- Null on a closed case: the system closed it because a later run no longer found the variance.
    "closedBy"             TEXT,
    "updatedAt"            TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((state = 'closed') = (outcome IS NOT NULL))
);
CREATE UNIQUE INDEX "LpCase_org_fingerprint_key" ON "LpCase" ("organizationId", fingerprint);
CREATE UNIQUE INDEX "LpCase_org_seq_key" ON "LpCase" ("organizationId", "caseSeq");
CREATE INDEX "LpCase_org_state_idx" ON "LpCase" ("organizationId", state, "openedAt" DESC);
CREATE INDEX "LpCase_org_subject_idx" ON "LpCase" ("organizationId", "subjectId");

CREATE TABLE "LpCaseEvent" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "caseId"         TEXT         NOT NULL REFERENCES "LpCase" (id) ON DELETE CASCADE,
    kind             TEXT         NOT NULL CHECK (kind IN ('opened', 'viewed', 'assigned', 'note', 'review_started',
        'escalated', 'finding_changed', 'finding_withdrawn', 'closed', 'reopened')),
    -- Null when the system acted.
    "actorId"        TEXT,
    note             TEXT,
    data             JSONB        NOT NULL DEFAULT '{}'::jsonb,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "LpCaseEvent_case_idx" ON "LpCaseEvent" ("caseId", "createdAt");
CREATE TRIGGER "LpCaseEvent_append_only" BEFORE UPDATE ON "LpCaseEvent"
    FOR EACH ROW EXECUTE FUNCTION lp_append_only();

ALTER TABLE "LpCase" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "LpCase"
    USING (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true))
    WITH CHECK (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true));
ALTER TABLE "LpCaseEvent" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "LpCaseEvent"
    USING (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true))
    WITH CHECK (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true));
