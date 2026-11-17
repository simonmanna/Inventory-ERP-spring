-- V513: ledger integrity in the database (accounting controls review 2026-11-17, R2 and R7).
--
-- R7. The evidence-guard bypass (SET app.evidence_purge = 'on') needs an explicit privileged role. V470 let any member
-- of the schema owner switch it on, which with one shared role (the development default, or an API that connects as
-- the migration role) is every session. It now requires the session user to be a superuser (who can disable triggers
-- anyway) or to hold the privileges of erp_evidence_purger (pg_has_role USAGE: a plain GRANT erp_evidence_purger TO
-- <dba login>; the ADMIN-only grant PostgreSQL 16 gives a CREATEROLE creator does not count), a NOLOGIN role nobody is
-- granted by default: a purge is an explicit DBA action (docs/DATABASE.md section 6). The role is created when the
-- migration role may (CREATEROLE or superuser); otherwise the migration still succeeds and the bypass stays off for
-- everyone but superusers until a superuser creates the role.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'erp_evidence_purger') THEN
    CREATE ROLE erp_evidence_purger NOLOGIN;
  END IF;
EXCEPTION
  WHEN insufficient_privilege THEN
    RAISE NOTICE 'Role erp_evidence_purger not created (no CREATEROLE): the evidence purge bypass stays off';
  WHEN duplicate_object THEN
    NULL;
END $$;

-- Order matters: the role lookup runs only when the setting is on (every guarded write calls this), and a missing
-- role gives false instead of an error.
CREATE OR REPLACE FUNCTION public.evidence_purge_enabled() RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
  SELECT CASE
    WHEN coalesce(current_setting('app.evidence_purge', true), '') <> 'on' THEN false
    ELSE coalesce((SELECT r.rolsuper FROM pg_catalog.pg_roles r WHERE r.rolname = session_user), false)
      OR coalesce((SELECT pg_catalog.pg_has_role(session_user, r.oid, 'USAGE') FROM pg_catalog.pg_roles r
                    WHERE r.rolname = 'erp_evidence_purger'), false)
  END
$$;

-- R2 (b). Lines could be inserted into an entry posted by an earlier transaction: V1 guards only UPDATE / DELETE.
-- "postedTxn" stamps the transaction that posted the entry (insert as posted, or draft -> posted): its top-level
-- transaction id plus its start time, so a value restored from a dump into another cluster never matches a live
-- transaction. pg_current_xact_id() is the top-level id inside savepoints too, so every PostingService path (post /
-- reverse insert the entry as posted and then its lines; postDraft only flips the status; posting-key replays run
-- under an advisory lock and savepoints) still adds lines in the transaction that posted the entry. The stamp is
-- written only by the trigger: any value a statement supplies is replaced, and on a posted entry a changed value is
-- refused by guard_journal_entry anyway (not among the columns a posted entry may change).
ALTER TABLE public."JournalEntry" ADD COLUMN IF NOT EXISTS "postedTxn" text;
COMMENT ON COLUMN public."JournalEntry"."postedTxn" IS
    'Transaction that posted the entry (journal_current_txn()), written by trigger journal_entry_stamp_posted_txn';

CREATE OR REPLACE FUNCTION public.journal_current_txn() RETURNS text
    LANGUAGE sql VOLATILE
    AS $$
  SELECT pg_catalog.pg_current_xact_id()::text || ':' || extract(epoch FROM pg_catalog.transaction_timestamp())::text
$$;

CREATE OR REPLACE FUNCTION public.stamp_journal_entry_posted_txn() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    NEW."postedTxn" := CASE WHEN NEW.status::text <> 'draft' THEN public.journal_current_txn() END;
  ELSIF OLD.status::text = 'draft' AND NEW.status::text <> 'draft' THEN
    NEW."postedTxn" := public.journal_current_txn();
  ELSE
    NEW."postedTxn" := OLD."postedTxn";
  END IF;
  RETURN NEW;
END $$;

-- Named to fire after journal_entry_immutable_after_post (BEFORE triggers fire in name order).
DROP TRIGGER IF EXISTS journal_entry_stamp_posted_txn ON public."JournalEntry";
CREATE TRIGGER journal_entry_stamp_posted_txn
    BEFORE INSERT OR UPDATE ON public."JournalEntry"
    FOR EACH ROW EXECUTE FUNCTION public.stamp_journal_entry_posted_txn();

-- SECURITY DEFINER: the entry is read whatever the caller's tenant (row-level security would hide another
-- organization's entry and let the line through). session_user, which evidence_purge_enabled() checks, is unchanged.
CREATE OR REPLACE FUNCTION public.guard_journal_line_insert() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, public
    AS $$
DECLARE
  entry_status text;
  entry_number text;
  entry_txn text;
BEGIN
  IF public.evidence_purge_enabled() THEN RETURN NEW; END IF;
  SELECT e.status::text, e."entryNumber", e."postedTxn" INTO entry_status, entry_number, entry_txn
    FROM public."JournalEntry" e WHERE e.id = NEW."journalEntryId";
  -- No entry: the foreign key refuses the row. A draft takes lines until it is posted.
  IF entry_status IS NULL OR entry_status = 'draft' THEN RETURN NEW; END IF;
  IF entry_txn IS NOT NULL AND entry_txn = public.journal_current_txn() THEN RETURN NEW; END IF;
  RAISE EXCEPTION 'Journal entry % is already posted; lines cannot be added to it', entry_number
      USING ERRCODE = 'integrity_constraint_violation';
END $$;

DROP TRIGGER IF EXISTS journal_line_insert_into_posted ON public."JournalLine";
CREATE TRIGGER journal_line_insert_into_posted
    BEFORE INSERT OR UPDATE OF "journalEntryId" ON public."JournalLine"
    FOR EACH ROW EXECUTE FUNCTION public.guard_journal_line_insert();

-- R2 (a). Nothing in the database checked that a posted entry balances. At commit (deferred), every posted or
-- reversed entry whose lines changed, or that became posted / reversed, must have sum(baseDebit) = sum(baseCredit)
-- exactly. PostingService guarantees that: lines are normalised to 6 dp (numeric(20,6) columns), a base residual of at
-- most 0.01 is booked to the ROUNDING account as its own line, anything larger is refused; reversals mirror the
-- lines. So the tolerance is 0. Every event re-checks its entry (no per-transaction de-duplication: a marker a session
-- could set would let it skip the check), and each check is one index scan of the entry's lines.
CREATE OR REPLACE FUNCTION public.check_journal_entry_balanced() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, public
    AS $$
DECLARE
  ids text[];
  entry_id text;
  entry_status text;
  entry_number text;
  total_debit numeric;
  total_credit numeric;
BEGIN
  IF public.evidence_purge_enabled() THEN RETURN NULL; END IF;
  IF TG_TABLE_NAME = 'JournalEntry' THEN
    ids := ARRAY[NEW.id];
  ELSIF TG_OP = 'INSERT' THEN
    ids := ARRAY[NEW."journalEntryId"];
  ELSIF TG_OP = 'DELETE' THEN
    ids := ARRAY[OLD."journalEntryId"];
  ELSIF OLD."journalEntryId" IS DISTINCT FROM NEW."journalEntryId" THEN
    ids := ARRAY[OLD."journalEntryId", NEW."journalEntryId"];
  ELSE
    ids := ARRAY[NEW."journalEntryId"];
  END IF;
  FOREACH entry_id IN ARRAY ids LOOP
    SELECT e.status::text, e."entryNumber" INTO entry_status, entry_number
      FROM public."JournalEntry" e WHERE e.id = entry_id;
    -- Gone (a draft discarded, a cascade) or still a draft: nothing to check.
    CONTINUE WHEN entry_status IS NULL OR entry_status = 'draft';
    SELECT coalesce(sum(l."baseDebit"), 0), coalesce(sum(l."baseCredit"), 0) INTO total_debit, total_credit
      FROM public."JournalLine" l WHERE l."journalEntryId" = entry_id;
    IF total_debit <> total_credit THEN
      RAISE EXCEPTION 'Journal entry % is unbalanced: base debit % <> base credit %', entry_number, total_debit,
          total_credit USING ERRCODE = 'integrity_constraint_violation';
    END IF;
  END LOOP;
  RETURN NULL;
END $$;

DROP TRIGGER IF EXISTS journal_line_balanced ON public."JournalLine";
CREATE CONSTRAINT TRIGGER journal_line_balanced
    AFTER INSERT OR UPDATE OR DELETE ON public."JournalLine"
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION public.check_journal_entry_balanced();

DROP TRIGGER IF EXISTS journal_entry_balanced ON public."JournalEntry";
CREATE CONSTRAINT TRIGGER journal_entry_balanced
    AFTER INSERT OR UPDATE OF status ON public."JournalEntry"
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.status::text <> 'draft')
    EXECUTE FUNCTION public.check_journal_entry_balanced();
