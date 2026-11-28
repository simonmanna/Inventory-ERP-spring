-- ADR-S094 C1a (D-IDEM-1, approved deviation OD-14): lease and fencing for Idempotency-Key operations.
--
-- Before this change a money operation left pending without an outcome for 2 minutes was deleted and run again, even
-- if its first execution was still running, so a slow request could post twice. Now every execution of a key is an
-- attempt with its own owner token and lease:
--   * a retry inside the lease answers OPERATION_PENDING (as before for a running operation);
--   * a retry after the lease, when nothing committed, takes the key over (attempt + 1, new token);
--   * every write transaction of an attempt must still own the key when it commits (the fence): the outermost one
--     stamps "committedAttempt" on the record under its row lock, so a superseded attempt can never commit.
-- Write transactions opened inside another one of the same attempt (REQUIRES_NEW) cannot lock the record without
-- deadlocking against the outer transaction; they leave an IdempotencyEvidence row instead.

ALTER TABLE "IdempotencyRecord"
    ADD COLUMN attempt            INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN "ownerToken"       TEXT,
    ADD COLUMN "leaseExpiresAt"   TIMESTAMP(3),
    ADD COLUMN "committedAttempt" INTEGER;

-- Records still open when this runs keep the rule they were created under: their lease ends 2 minutes after the claim.
UPDATE "IdempotencyRecord" SET "leaseExpiresAt" = "createdAt" + INTERVAL '2 minutes'
WHERE status IN ('pending', 'indeterminate');

CREATE TABLE "IdempotencyEvidence" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL,
    key              TEXT NOT NULL,
    attempt          INTEGER NOT NULL,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- No foreign key to IdempotencyRecord on purpose: the key-share lock a foreign key takes would wait for the outer
-- transaction that holds the record, which is exactly the deadlock this table avoids.
CREATE INDEX "IdempotencyEvidence_key_idx" ON "IdempotencyEvidence" ("organizationId", key, attempt);

DO $$
DECLARE
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    ALTER TABLE public."IdempotencyEvidence" ENABLE ROW LEVEL SECURITY;
    DROP POLICY IF EXISTS tenant_isolation ON public."IdempotencyEvidence";
    EXECUTE format('CREATE POLICY tenant_isolation ON public."IdempotencyEvidence" USING (%s) WITH CHECK (%s)',
        expr, expr);
END $$;
