-- V518: AR / AP control-account policy (owner decision 2026-11-17, accounting controls review).
--
-- Manual and recurring journal entries (and deferral schedules) may no longer post to a designated accounts
-- receivable / payable control account: only the sub-ledger services (invoices, bills, payments, credit / debit notes,
-- POS, payroll, ...) post there, in system context, so the general ledger stays equal to the customer / supplier
-- sub-ledgers. The exception is a control-account adjustment: a manual entry flagged with a mandatory reason, a partner
-- on every control-account line, staged by a holder of journal_entry:control_adjust and posted by a different user
-- holding journal_entry:post and journal_entry:control_adjust, audited on stage and post (PostingService).
--
-- 1. Designation. An account is an AR / AP control account only when its "controlAccountType" says so ('ar' / 'ap';
--    the existing column, already exposed by the account API and seeded on 1300 / 2100 for new organizations). It is
--    never inferred from the account name or category. Backfill from explicit configuration: the account each
--    organization maps to accounts_receivable / accounts_payable (the defaults AccountDeterminationService uses) and
--    every partner-level receivable / payable override ("Partner"."receivableAccountId" / "payableAccountId").
--    Only accounts with no designation yet are changed: an account already designated as another control type keeps
--    it (reported below), and AR wins when one account is configured as both (also reported).
UPDATE "Account" a
SET "controlAccountType" = 'ar',
    "updatedAt" = CURRENT_TIMESTAMP
WHERE a."controlAccountType" IS NULL
  AND a."deletedAt" IS NULL
  AND (EXISTS (SELECT 1 FROM "AccountMapping" m
               WHERE m."organizationId" = a."organizationId" AND m."accountId" = a.id
                 AND m.key = 'accounts_receivable' AND m."deletedAt" IS NULL)
       OR EXISTS (SELECT 1 FROM "Partner" p
                  WHERE p."organizationId" = a."organizationId" AND p."receivableAccountId" = a.id
                    AND p."deletedAt" IS NULL));

UPDATE "Account" a
SET "controlAccountType" = 'ap',
    "updatedAt" = CURRENT_TIMESTAMP
WHERE a."controlAccountType" IS NULL
  AND a."deletedAt" IS NULL
  AND (EXISTS (SELECT 1 FROM "AccountMapping" m
               WHERE m."organizationId" = a."organizationId" AND m."accountId" = a.id
                 AND m.key = 'accounts_payable' AND m."deletedAt" IS NULL)
       OR EXISTS (SELECT 1 FROM "Partner" p
                  WHERE p."organizationId" = a."organizationId" AND p."payableAccountId" = a.id
                    AND p."deletedAt" IS NULL));

DO $$
DECLARE r record;
BEGIN
  FOR r IN
    SELECT a."organizationId", a.code, a."controlAccountType"::text AS designated
      FROM "Account" a
     WHERE a."deletedAt" IS NULL
       AND (a."controlAccountType" IS NULL OR a."controlAccountType"::text NOT IN ('ar', 'ap'))
       AND (EXISTS (SELECT 1 FROM "AccountMapping" m WHERE m."organizationId" = a."organizationId"
                      AND m."accountId" = a.id AND m.key IN ('accounts_receivable', 'accounts_payable')
                      AND m."deletedAt" IS NULL)
            OR EXISTS (SELECT 1 FROM "Partner" p WHERE p."organizationId" = a."organizationId"
                         AND (p."receivableAccountId" = a.id OR p."payableAccountId" = a.id) AND p."deletedAt" IS NULL))
  LOOP
    RAISE NOTICE 'V518: account % of organization % is configured as AR / AP but designated %; left unchanged',
        r.code, r."organizationId", r.designated;
  END LOOP;
  FOR r IN
    SELECT a."organizationId", a.code
      FROM "Account" a
     WHERE a."deletedAt" IS NULL AND a."controlAccountType"::text = 'ar'
       AND (EXISTS (SELECT 1 FROM "AccountMapping" m WHERE m."organizationId" = a."organizationId"
                      AND m."accountId" = a.id AND m.key = 'accounts_payable' AND m."deletedAt" IS NULL)
            OR EXISTS (SELECT 1 FROM "Partner" p WHERE p."organizationId" = a."organizationId"
                         AND p."payableAccountId" = a.id AND p."deletedAt" IS NULL))
  LOOP
    RAISE NOTICE 'V518: account % of organization % is configured as both AR and AP; designated AR', r.code,
        r."organizationId";
  END LOOP;
END $$;

-- 2. The adjustment flag and its reason on the entry. Set when the draft is staged, immutable once posted
--    (guard_journal_entry refuses any change to a posted entry outside status / reversedEntryId / postingKey).
ALTER TABLE "JournalEntry"
    ADD COLUMN "controlAdjustment"       BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN "controlAdjustmentReason" TEXT;
ALTER TABLE "JournalEntry"
    ADD CONSTRAINT "JournalEntry_control_adjustment_reason"
    CHECK (NOT "controlAdjustment" OR length(btrim(coalesce("controlAdjustmentReason", ''))) > 0);
CREATE INDEX "JournalEntry_control_adjustment_idx" ON "JournalEntry" ("organizationId", "postingDate")
    WHERE "controlAdjustment";

-- 3. The permission. Granted to no role but Administrator, which by convention holds every permission (SeedRunner and
--    organization bootstrap give it Permissions.ALL); any other role gets it only by an explicit role edit.
INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['journal_entry:control_adjust']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['journal_entry:control_adjust'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
