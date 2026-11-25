-- Finance readiness audit B4: database backstops.
--
-- 1. Every trigger function in "public" (the journal, payment, cash and stock guards among them) gets a pinned
--    search_path with pg_temp last. They name tables unqualified, so a session could otherwise shadow "JournalEntry"
--    with a temporary table and blind a guard. (SECURITY DEFINER was not added: the guards must keep running as the
--    caller, under its RLS policies.)
DO $$
DECLARE f record;
BEGIN
  FOR f IN SELECT p.oid::regprocedure AS sig FROM pg_proc p
            WHERE p.pronamespace = 'public'::regnamespace
              AND (p.prorettype = 'trigger'::regtype
                   OR p.proname IN ('evidence_purge_enabled', 'evidence_row_changed'))
  LOOP
    EXECUTE format('ALTER FUNCTION %s SET search_path = public, pg_temp', f.sig);
  END LOOP;
END $$;

-- 2. Finance CHECK constraints added NOT VALID (V499, V320) are validated when every existing row passes; otherwise
--    they stay NOT VALID (still enforced on new rows) and a WARNING names them, so an old database cannot block startup.
DO $$
DECLARE c record; bad boolean;
BEGIN
  FOR c IN SELECT conrelid::regclass AS tbl, conname, pg_get_constraintdef(oid) AS def FROM pg_constraint
            WHERE NOT convalidated AND contype = 'c'
              AND conname IN ('JournalLine_amounts_non_negative', 'JournalLine_one_side', 'CurrencyRate_rate_positive')
  LOOP
    EXECUTE format('SELECT EXISTS (SELECT 1 FROM %s WHERE NOT (%s))', c.tbl,
                   regexp_replace(regexp_replace(c.def, '^CHECK ', ''), ' NOT VALID$', '')) INTO bad;
    IF bad THEN
      RAISE WARNING 'B4: % on % has violating rows; left NOT VALID', c.conname, c.tbl;
    ELSE
      EXECUTE format('ALTER TABLE %s VALIDATE CONSTRAINT %I', c.tbl, c.conname);
    END IF;
  END LOOP;
END $$;

-- 3. A journal line's exchange rate is positive.
ALTER TABLE "JournalLine" ADD CONSTRAINT "JournalLine_exchangeRate_positive"
  CHECK ("exchangeRate" IS NULL OR "exchangeRate" > 0) NOT VALID;
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM "JournalLine" WHERE "exchangeRate" <= 0) THEN
    RAISE WARNING 'B4: JournalLine_exchangeRate_positive has violating rows; left NOT VALID';
  ELSE
    ALTER TABLE "JournalLine" VALIDATE CONSTRAINT "JournalLine_exchangeRate_positive";
  END IF;
END $$;

-- 4. Reversal links: an entry is reversed at most once, and both links point at a real entry.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM "JournalEntry" WHERE "reversalOfId" IS NOT NULL GROUP BY "reversalOfId" HAVING count(*) > 1)
  THEN
    RAISE WARNING 'B4: some journal entries are reversed more than once; JournalEntry_reversalOfId_key not created';
  ELSE
    CREATE UNIQUE INDEX "JournalEntry_reversalOfId_key" ON "JournalEntry" ("reversalOfId")
      WHERE "reversalOfId" IS NOT NULL;
  END IF;
END $$;
ALTER TABLE "JournalEntry" ADD CONSTRAINT "JournalEntry_reversalOfId_fkey"
  FOREIGN KEY ("reversalOfId") REFERENCES "JournalEntry" (id) NOT VALID;
ALTER TABLE "JournalEntry" ADD CONSTRAINT "JournalEntry_reversedEntryId_fkey"
  FOREIGN KEY ("reversedEntryId") REFERENCES "JournalEntry" (id) NOT VALID;
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM "JournalEntry" e WHERE "reversalOfId" IS NOT NULL
               AND NOT EXISTS (SELECT 1 FROM "JournalEntry" o WHERE o.id = e."reversalOfId"))
     OR EXISTS (SELECT 1 FROM "JournalEntry" e WHERE "reversedEntryId" IS NOT NULL
               AND NOT EXISTS (SELECT 1 FROM "JournalEntry" o WHERE o.id = e."reversedEntryId")) THEN
    RAISE WARNING 'B4: dangling reversal links; the reversal foreign keys stay NOT VALID';
  ELSE
    ALTER TABLE "JournalEntry" VALIDATE CONSTRAINT "JournalEntry_reversalOfId_fkey";
    ALTER TABLE "JournalEntry" VALIDATE CONSTRAINT "JournalEntry_reversedEntryId_fkey";
  END IF;
END $$;
