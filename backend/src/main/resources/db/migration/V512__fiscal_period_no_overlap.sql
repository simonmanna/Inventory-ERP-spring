-- V512: fiscal periods of one organization never overlap (accounting controls review 2026-11-17, R1; owner-approved
-- deviation, Nest has the same gap).
--
-- Only ("organizationId", name) was unique, so two periods could cover one day; period control (FiscalPeriodControl)
-- then picked one with LIMIT 1, and an open copy of a closed range let postings back into closed books. Bounds are
-- inclusive org-local wall-clock timestamps (ADR-S070): two live periods overlap when each starts on or before the
-- other ends. Soft-deleted periods are ignored (FiscalPeriodService refuses to delete a closed / locked period or one
-- with postings), and a period that ends before it starts is refused.
--
-- A trigger, not an exclusion constraint: existing databases may already hold overlaps (an EXCLUDE constraint would
-- fail this migration on them), and only new or re-dated rows are checked. Writers of one organization are serialised
-- on the same advisory lock FiscalPeriodService takes, so two concurrent inserts cannot both pass the check.
CREATE OR REPLACE FUNCTION public.guard_fiscal_period_overlap() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE clash text;
BEGIN
  IF NEW."startDate" > NEW."endDate" THEN
    RAISE EXCEPTION 'Fiscal period % ends before it starts', NEW.name USING ERRCODE = 'check_violation';
  END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended('fiscal_period:' || NEW."organizationId", 0));
  SELECT p.name INTO clash
    FROM public."FiscalPeriod" p
   WHERE p."organizationId" = NEW."organizationId"
     AND p."deletedAt" IS NULL
     AND p.id <> NEW.id
     AND p."startDate" <= NEW."endDate"
     AND p."endDate" >= NEW."startDate"
   ORDER BY p."startDate"
   LIMIT 1;
  IF clash IS NOT NULL THEN
    RAISE EXCEPTION 'Fiscal period % overlaps fiscal period %', NEW.name, clash USING ERRCODE = 'exclusion_violation';
  END IF;
  RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS fiscal_period_no_overlap ON public."FiscalPeriod";
CREATE TRIGGER fiscal_period_no_overlap
    BEFORE INSERT OR UPDATE OF "organizationId", "startDate", "endDate", "deletedAt" ON public."FiscalPeriod"
    FOR EACH ROW
    WHEN (NEW."deletedAt" IS NULL)
    EXECUTE FUNCTION public.guard_fiscal_period_overlap();
