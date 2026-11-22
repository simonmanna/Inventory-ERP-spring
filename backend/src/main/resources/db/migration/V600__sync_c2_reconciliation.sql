-- ADR-S083 C2: post-restore reconciliation runs (at start-up, nightly, or on demand). Each run keeps its report.
CREATE TABLE "SyncReconciliationRun" (
    id text NOT NULL,
    "organizationId" text NOT NULL,
    trigger text NOT NULL,
    status text NOT NULL,
    "issueCount" integer DEFAULT 0 NOT NULL,
    signature text,
    report jsonb DEFAULT '{}'::jsonb NOT NULL,
    "createdById" text,
    "startedAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "finishedAt" timestamp(3) without time zone,
    CONSTRAINT "SyncReconciliationRun_pkey" PRIMARY KEY (id),
    CONSTRAINT "SyncReconciliationRun_trigger_check" CHECK (trigger IN ('startup', 'scheduled', 'manual')),
    CONSTRAINT "SyncReconciliationRun_status_check" CHECK (status IN ('clean', 'issues', 'failed'))
);
CREATE INDEX "SyncReconciliationRun_org_started_idx" ON "SyncReconciliationRun" ("organizationId", "startedAt" DESC);
ALTER TABLE "SyncReconciliationRun" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SyncReconciliationRun" USING (coalesce(current_setting('app.org_id', true), '') = ''
    OR "organizationId" = current_setting('app.org_id', true));
