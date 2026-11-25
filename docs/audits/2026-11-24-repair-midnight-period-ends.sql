-- Finance readiness audit 2026-11-23, P1-1 repair (fix step A1): extend OPEN fiscal periods that end at 00:00 of their
-- last day to 23:59:59.999 of that day, the end every period writer stores since A1.
--
-- Owner decision 3 (2026-11-23): open periods by this reviewed script; closed or locked periods one at a time
-- (reopen → fix → close again), each approved separately. This script lists those and never changes them.
--
-- Run only with the owner's OK for the target database, after D1–D3 of 2026-11-23-accounting-data-checks.sql, and with a
-- fresh backup. The default is a dry run: everything is rolled back. Pass -v apply=1 to commit; -v org=<code> limits the
-- run to one organisation.
--
--   psql "$DATABASE_URL" -X -f docs/audits/2026-11-24-repair-midnight-period-ends.sql                       # dry run
--   psql "$DATABASE_URL" -X -v apply=1 -v org=DEMO -f docs/audits/2026-11-24-repair-midnight-period-ends.sql # apply
--
-- Effect: postings made after 00:00 on such a period's last day fall in that period again. Nothing moves out of a period.
-- If an extended period would overlap another one (an end meant as "exclusive"), the overlap trigger (V512) stops the
-- whole run and nothing is changed; repair that period by hand. Each change writes an AuditLog row.

\set ON_ERROR_STOP on
\pset null '∅'
\if :{?org}
\else
\set org ''
\endif

BEGIN;
SET LOCAL statement_timeout = '5min';

\echo '== Closed or locked periods ending at 00:00: NOT changed here (reopen, fix, close again; each approved)'
SELECT o.code AS org, p.id AS "periodId", p.name, p.status, p."startDate", p."endDate"
  FROM "FiscalPeriod" p
  JOIN "Organization" o ON o.id = p."organizationId"
 WHERE p."deletedAt" IS NULL AND p.status::text <> 'open' AND p."endDate"::time = time '00:00:00'
   AND (:'org' = '' OR o.code = :'org')
 ORDER BY 1, p."startDate";

CREATE TEMP TABLE repair ON COMMIT DROP AS
SELECT p.id, p."organizationId", o.code, p.name, p."endDate" AS "oldEnd",
       p."endDate" + interval '1 day' - interval '1 millisecond' AS "newEnd"
  FROM "FiscalPeriod" p
  JOIN "Organization" o ON o.id = p."organizationId"
 WHERE p."deletedAt" IS NULL AND p.status::text = 'open' AND p."endDate"::time = time '00:00:00'
   AND (:'org' = '' OR o.code = :'org');

\echo '== Open periods extended to 23:59:59.999 of their last day'
SELECT code AS org, id AS "periodId", name, "oldEnd", "newEnd" FROM repair ORDER BY 1, "oldEnd";

UPDATE "FiscalPeriod" p
   SET "endDate" = r."newEnd", "updatedAt" = now()
  FROM repair r
 WHERE p.id = r.id;

INSERT INTO "AuditLog" (id, "organizationId", entity, "entityId", action, "oldValues", "newValues", "userAgent")
SELECT gen_random_uuid()::text, r."organizationId", 'FiscalPeriod', r.id, 'update',
       jsonb_build_object('endDate', r."oldEnd"),
       jsonb_build_object('endDate', r."newEnd", 'reason',
                          'Finance readiness audit 2026-11-23 P1-1: a period ends at 23:59:59.999 of its last day'),
       'docs/audits/2026-11-24-repair-midnight-period-ends.sql'
  FROM repair r;

\echo '== Open periods still ending at 00:00 (must be 0)'
SELECT count(*) AS remaining
  FROM "FiscalPeriod" p
  JOIN "Organization" o ON o.id = p."organizationId"
 WHERE p."deletedAt" IS NULL AND p.status::text = 'open' AND p."endDate"::time = time '00:00:00'
   AND (:'org' = '' OR o.code = :'org');

\if :{?apply}
COMMIT;
\echo 'Applied.'
\else
ROLLBACK;
\echo 'Dry run: rolled back. Re-run with -v apply=1 to apply.'
\endif
