-- V561 (was V521): loss prevention, work packages 2–5 (ADR-S077 §4–§5): projection cursors, rules and their history, settings,
-- reconciliation windows, runs and findings. A run is never overwritten: a newer run supersedes it and both are kept.

CREATE FUNCTION lp_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% rows are append-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

-- Where the read-only sweep of each source table stopped.
CREATE TABLE "LpProjectionCursor" (
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    source           TEXT         NOT NULL,
    "cursorAt"       TIMESTAMP(3) NOT NULL,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY ("organizationId", source)
);

-- One organization's settings for one rule. With no row the catalog defaults apply (version 0).
CREATE TABLE "LpRule" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code             TEXT         NOT NULL,
    enabled          BOOLEAN      NOT NULL,
    severity         TEXT         NOT NULL CHECK (severity IN ('info', 'warning', 'critical')),
    params           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    version          INTEGER      NOT NULL CHECK (version >= 1),
    "updatedBy"      TEXT,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "LpRule_org_code_key" ON "LpRule" ("organizationId", code);

-- Every state a rule has had, so a run can name the exact version it used.
CREATE TABLE "LpRuleVersion" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code             TEXT         NOT NULL,
    version          INTEGER      NOT NULL,
    enabled          BOOLEAN      NOT NULL,
    severity         TEXT         NOT NULL,
    params           JSONB        NOT NULL,
    "changedBy"      TEXT,
    "changedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "LpRuleVersion_org_code_version_key" ON "LpRuleVersion" ("organizationId", code, version);
CREATE TRIGGER "LpRuleVersion_append_only" BEFORE UPDATE ON "LpRuleVersion"
    FOR EACH ROW EXECUTE FUNCTION lp_append_only();

-- Settling times and retention. branchId '' is the organization's default; a branch row overrides it.
CREATE TABLE "LpSetting" (
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "branchId"       TEXT         NOT NULL DEFAULT '',
    "values"         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    "updatedBy"      TEXT,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY ("organizationId", "branchId")
);

-- A unit to reconcile: one engine, one branch ('' = no branch), one business day.
CREATE TABLE "LpWindow" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    engine           TEXT         NOT NULL CHECK (engine IN ('cashier', 'bar')),
    "branchId"       TEXT         NOT NULL DEFAULT '',
    "businessDate"   DATE         NOT NULL,
    state            TEXT         NOT NULL DEFAULT 'dirty' CHECK (state IN ('dirty', 'reconciled')),
    -- When it first became dirty since its last run: the start of the settling time.
    "dirtyAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Why the next run happens: first run, a late event, or a replay somebody asked for.
    "pendingTrigger" TEXT         NOT NULL DEFAULT 'scheduled'
        CHECK ("pendingTrigger" IN ('scheduled', 'late_event', 'replay')),
    -- True once a run has covered the whole business day (the rules that compare a day run only then).
    "finalDone"      BOOLEAN      NOT NULL DEFAULT false,
    "latestRunId"    TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "LpWindow_key" ON "LpWindow" ("organizationId", engine, "branchId", "businessDate");
CREATE INDEX "LpWindow_due_idx" ON "LpWindow" ("organizationId", state, "dirtyAt");

-- One reconciliation of one window, with everything needed to repeat it.
CREATE TABLE "LpReconRun" (
    id                TEXT         NOT NULL PRIMARY KEY,
    "organizationId"  TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "windowId"        TEXT         NOT NULL REFERENCES "LpWindow" (id) ON DELETE CASCADE,
    engine            TEXT         NOT NULL,
    "engineVersion"   TEXT         NOT NULL,
    "branchId"        TEXT         NOT NULL DEFAULT '',
    "businessDate"    DATE         NOT NULL,
    trigger           TEXT         NOT NULL CHECK (trigger IN ('scheduled', 'late_event', 'final', 'replay')),
    "isFinal"         BOOLEAN      NOT NULL,
    -- {"C01": 0, "C08": 3}: the version of every rule the run used.
    "ruleVersions"    JSONB        NOT NULL,
    -- The rule settings and settling times as they were.
    config            JSONB        NOT NULL,
    -- The input: events of the window (and the look-back the engine asks for) up to this ledger position.
    "inputMaxSeq"     BIGINT       NOT NULL,
    "inputCount"      INTEGER      NOT NULL,
    "inputHash"       TEXT         NOT NULL,
    "findingCount"    INTEGER      NOT NULL,
    "supersedesRunId" TEXT,
    "createdBy"       TEXT,
    "createdAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "LpReconRun_window_idx" ON "LpReconRun" ("windowId", "createdAt" DESC);
CREATE INDEX "LpReconRun_org_created_idx" ON "LpReconRun" ("organizationId", "createdAt" DESC);
CREATE TRIGGER "LpReconRun_append_only" BEFORE UPDATE ON "LpReconRun"
    FOR EACH ROW EXECUTE FUNCTION lp_append_only();

-- One finding of one run. A newer run marks it superseded; nothing else about it changes.
CREATE TABLE "LpDiscrepancy" (
    id                 TEXT           NOT NULL PRIMARY KEY,
    "organizationId"   TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "runId"            TEXT           NOT NULL REFERENCES "LpReconRun" (id) ON DELETE CASCADE,
    "windowId"         TEXT           NOT NULL,
    engine             TEXT           NOT NULL,
    "ruleCode"         TEXT           NOT NULL,
    "ruleVersion"      INTEGER        NOT NULL,
    "branchId"         TEXT           NOT NULL DEFAULT '',
    "businessDate"     DATE           NOT NULL,
    -- Stable across runs: the same finding keeps the same fingerprint, and so its case.
    fingerprint        TEXT           NOT NULL,
    severity           TEXT           NOT NULL,
    title              TEXT           NOT NULL,
    "subjectType"      TEXT,
    "subjectId"        TEXT,
    expected           NUMERIC(20, 6),
    observed           NUMERIC(20, 6),
    delta              NUMERIC(20, 6),
    unit               TEXT,
    "exposureAmount"   NUMERIC(20, 6),
    "exposureMethod"   TEXT,
    detail             JSONB          NOT NULL DEFAULT '{}'::jsonb,
    "evidenceEventIds" TEXT[]         NOT NULL DEFAULT ARRAY[]::TEXT[],
    status             TEXT           NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'superseded')),
    "createdAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "LpDiscrepancy_run_idx" ON "LpDiscrepancy" ("runId");
CREATE INDEX "LpDiscrepancy_window_status_idx" ON "LpDiscrepancy" ("windowId", status);
CREATE INDEX "LpDiscrepancy_org_fingerprint_idx" ON "LpDiscrepancy" ("organizationId", fingerprint);

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['LpProjectionCursor', 'LpRule', 'LpRuleVersion', 'LpSetting', 'LpWindow', 'LpReconRun',
        'LpDiscrepancy']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I'
            || ' USING (coalesce(current_setting(''app.org_id'', true), '''') = '''''
            || ' OR "organizationId" = current_setting(''app.org_id'', true))'
            || ' WITH CHECK (coalesce(current_setting(''app.org_id'', true), '''') = '''''
            || ' OR "organizationId" = current_setting(''app.org_id'', true))', t);
    END LOOP;
END $$;

-- A run reads one branch's events of one business day (and a look-back by type).
CREATE INDEX "LpEvent_org_bdate_type_idx" ON "LpEvent" ("organizationId", "businessDate", type);
