-- Sales readiness INV-11 (owner decision 2026-11-26): sales invoices and credit notes take their legal number when
-- they are posted, from a counter row locked in the posting transaction, so a rolled-back post leaves no gap (a
-- PostgreSQL sequence is not transactional). Seeded on first use from the highest number already issued.
CREATE TABLE "GaplessCounter" (
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    key              TEXT         NOT NULL,
    last             BIGINT       NOT NULL,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY ("organizationId", key)
);

-- The standard tenant policy (V214 / V605) on the new table.
DO $$
DECLARE
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    EXECUTE 'ALTER TABLE public."GaplessCounter" ENABLE ROW LEVEL SECURITY';
    EXECUTE 'DROP POLICY IF EXISTS tenant_isolation ON public."GaplessCounter"';
    EXECUTE format('CREATE POLICY tenant_isolation ON public."GaplessCounter" USING (%s) WITH CHECK (%s)',
                   expr, expr);
END $$;
