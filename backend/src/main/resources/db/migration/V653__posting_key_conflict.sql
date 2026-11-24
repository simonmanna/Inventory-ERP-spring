-- Finance readiness audit 2026-11-23, step A6 (P2-12): a posting key replayed with other lines. PostingService returns
-- the posted entry of a key it has seen; when the request's lines differ (net base amount per account and partner),
-- PostingKeyReplayGuard records one row here and the ledger integrity check (I13, posting_key_reuse) lists it until
-- someone who reviewed it sets "resolvedAt". Nothing here corrects data.
CREATE TABLE "PostingKeyConflict" (
    id                  TEXT         PRIMARY KEY,
    "organizationId"    TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "postingKey"        TEXT         NOT NULL,
    "journalEntryId"    TEXT         NOT NULL,
    "sourceType"        TEXT,
    "sourceId"          TEXT,
    "requestedCurrency" TEXT,
    "pairCount"         INTEGER      NOT NULL,
    pairs               JSONB        NOT NULL,
    "createdBy"         TEXT,
    "createdAt"         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "resolvedAt"        TIMESTAMP(3),
    "resolvedBy"        TEXT,
    resolution          TEXT
);

CREATE INDEX "PostingKeyConflict_org_open_idx" ON "PostingKeyConflict" ("organizationId", "createdAt")
    WHERE "resolvedAt" IS NULL;

-- Standard tenant policy (V214, as in V650), like every table with an organizationId column.
DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['PostingKeyConflict'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON public.%I', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
