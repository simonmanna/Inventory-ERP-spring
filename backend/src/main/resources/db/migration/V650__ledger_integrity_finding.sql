-- Finance readiness audit 2026-11-23, step A0: findings of the nightly ledger integrity check (LedgerIntegrityMonitor,
-- GET /reports/accounting/integrity). One open row per organisation and check; it is refreshed while the check keeps
-- failing and resolved once it passes. Nothing here corrects data.
CREATE TABLE "LedgerIntegrityFinding" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "checkKey"       TEXT         NOT NULL,
    status           TEXT         NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'resolved')),
    failures         BIGINT       NOT NULL DEFAULT 0,
    invariant        TEXT         NOT NULL,
    sample           JSONB,
    "firstSeenAt"    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "lastSeenAt"     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "resolvedAt"     TIMESTAMP(3)
);

CREATE UNIQUE INDEX "LedgerIntegrityFinding_open_key" ON "LedgerIntegrityFinding" ("organizationId", "checkKey")
    WHERE status = 'open';
CREATE INDEX "LedgerIntegrityFinding_org_seen_idx" ON "LedgerIntegrityFinding" ("organizationId", "firstSeenAt");

-- Standard tenant policy (V214, as in V621), like every table with an organizationId column.
DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['LedgerIntegrityFinding'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON public.%I', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
