-- Finance & accounting readiness audit 2026-11-23: read-only data checks D0–D9.
-- Context and the meaning of each check: docs/audits/2026-11-23-finance-accounting-readiness.md ("Read-only data
-- checks").
--
-- Run on a RESTORED COPY of the production dump first; on production only with the owner's OK. Every statement only
-- reads, and the script runs in one READ ONLY transaction that is rolled back. A finding is repaired, if at all, by a
-- separate reviewed script approved per finding: never here, never at start-up, never as a Flyway migration.
--
--   psql "$DATABASE_URL" -X -v ON_ERROR_STOP=1 -f docs/audits/2026-11-23-accounting-data-checks.sql > d0-d9.txt
--
-- Time: postingDate is UTC; fiscal-period bounds and VAT settlement periods are org-local wall clock (ADR-S070). A
-- timezone that is not a Postgres zone name falls back to UTC here and is listed by D0; a fixed offset such as '+03:00'
-- must not be used with AT TIME ZONE (Postgres reads it as a POSIX zone, sign inverted). Check such organisations with
-- GET /reports/accounting/integrity, which handles offsets.
--
-- Profit-and-loss accounts are those whose report section (the account's own, else its category's) is revenue,
-- contra_revenue, cogs, operating_expense, other_income or other_expense. Account mappings are read as the engine reads
-- them (AccountResolverService.byMapping): one row per organisation and key, used even when soft-deleted.

\pset null '∅'
\pset footer on
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '20min';

\echo '== D0 organisations whose timezone is not a Postgres zone name (checked as UTC below)'
SELECT o.code AS org, o.timezone
  FROM "Organization" o
 WHERE coalesce(o.timezone, '') <> '' AND o.timezone NOT IN (SELECT name FROM pg_timezone_names)
 ORDER BY o.code;

\echo '== D1 (P1-1) periods ending at 00:00 of their last day, and the postings after 00:00 on that day'
WITH orgs AS (
  SELECT o.id, o.code,
         CASE WHEN o.timezone IN (SELECT name FROM pg_timezone_names) THEN o.timezone ELSE 'UTC' END AS tz
    FROM "Organization" o)
SELECT o.code AS org, p.id AS "periodId", p.name, p.status, p."deletedAt" IS NOT NULL AS deleted, p."startDate",
       p."endDate",
       (SELECT count(*) FROM "JournalEntry" e
         WHERE e."organizationId" = p."organizationId" AND e.status::text IN ('posted', 'reversed')
           AND e."postingDate" > p."endDate" AT TIME ZONE o.tz AT TIME ZONE 'UTC'
           AND e."postingDate" < (p."endDate" + interval '1 day') AT TIME ZONE o.tz AT TIME ZONE 'UTC')
         AS "entriesAfter0000OnLastDay"
  FROM "FiscalPeriod" p
  JOIN orgs o ON o.id = p."organizationId"
 WHERE p."endDate"::time = time '00:00:00'
 ORDER BY o.code, p."startDate";

\echo '== D2 (P1-1) posted entries in no fiscal period, by org-local day (a deleted period counts only if closed/locked)'
WITH orgs AS (
  SELECT o.id, o.code,
         CASE WHEN o.timezone IN (SELECT name FROM pg_timezone_names) THEN o.timezone ELSE 'UTC' END AS tz
    FROM "Organization" o),
uncovered AS (
  SELECT e.id, e."organizationId", e."entryNumber",
         ((e."postingDate" AT TIME ZONE 'UTC') AT TIME ZONE o.tz)::date AS "localDate"
    FROM "JournalEntry" e
    JOIN orgs o ON o.id = e."organizationId"
   WHERE e.status::text IN ('posted', 'reversed')
     AND NOT EXISTS (SELECT 1 FROM "FiscalPeriod" p
                      WHERE p."organizationId" = e."organizationId"
                        AND (p."deletedAt" IS NULL OR p.status::text <> 'open')
                        AND e."postingDate" BETWEEN (p."startDate" AT TIME ZONE o.tz AT TIME ZONE 'UTC')
                                                AND (p."endDate" AT TIME ZONE o.tz AT TIME ZONE 'UTC')))
SELECT o.code AS org, u."localDate", count(DISTINCT u.id) AS entries, sum(l."baseDebit") AS "baseDebit",
       min(u."entryNumber") AS "firstEntry", max(u."entryNumber") AS "lastEntry",
       (SELECT count(*) FROM "FiscalPeriod" p WHERE p."organizationId" = u."organizationId" AND p."deletedAt" IS NULL)
         AS "livePeriodsInOrg"
  FROM uncovered u
  JOIN orgs o ON o.id = u."organizationId"
  JOIN "JournalLine" l ON l."journalEntryId" = u.id
 GROUP BY o.code, u."organizationId", u."localDate"
 ORDER BY 1, 2;

\echo '== D3 (P1-1, P2-1) closed or locked periods whose P&L accounts do not net to 0 over their whole days (> 0.01)'
WITH orgs AS (
  SELECT o.id, o.code,
         CASE WHEN o.timezone IN (SELECT name FROM pg_timezone_names) THEN o.timezone ELSE 'UTC' END AS tz
    FROM "Organization" o),
per AS (
  SELECT p.id, p."organizationId", o.code, p.name, p.status::text AS status, p."startDate",
         date_trunc('day', p."startDate") AT TIME ZONE o.tz AT TIME ZONE 'UTC' AS su,
         (date_trunc('day', p."endDate") + interval '1 day' - interval '1 millisecond') AT TIME ZONE o.tz
             AT TIME ZONE 'UTC' AS fu
    FROM "FiscalPeriod" p
    JOIN orgs o ON o.id = p."organizationId"
   WHERE p."deletedAt" IS NULL AND p.status::text IN ('closed', 'locked'))
SELECT per.code AS org, per.name AS period, per.status, a.code AS account, a.name AS "accountName",
       sum(l."baseDebit" - l."baseCredit") AS net
  FROM per
  JOIN "JournalEntry" e ON e."organizationId" = per."organizationId" AND e.status::text IN ('posted', 'reversed')
       AND e."postingDate" BETWEEN per.su AND per.fu
  JOIN "JournalLine" l ON l."journalEntryId" = e.id
  JOIN "Account" a ON a.id = l."accountId"
  LEFT JOIN "AccountCategory" cat ON cat.id = a."categoryId"
 WHERE coalesce(a."reportSection"::text, cat."reportSection"::text)
       IN ('revenue', 'contra_revenue', 'cogs', 'operating_expense', 'other_income', 'other_expense')
 GROUP BY per.code, per.id, per.name, per.status, per."startDate", a.code, a.name
HAVING abs(sum(l."baseDebit" - l."baseCredit")) > 0.01
 ORDER BY 1, per."startDate", 4;

\echo '== D4 (P2-1) ended open periods with postings whose P&L nets to 0 on every account (close says "Nothing to close")'
WITH orgs AS (
  SELECT o.id, o.code,
         CASE WHEN o.timezone IN (SELECT name FROM pg_timezone_names) THEN o.timezone ELSE 'UTC' END AS tz
    FROM "Organization" o),
per AS (
  SELECT p.id, p."organizationId", o.code, p.name, p."startDate", p."endDate",
         p."startDate" AT TIME ZONE o.tz AT TIME ZONE 'UTC' AS su,
         p."endDate" AT TIME ZONE o.tz AT TIME ZONE 'UTC' AS fu
    FROM "FiscalPeriod" p
    JOIN orgs o ON o.id = p."organizationId"
   WHERE p."deletedAt" IS NULL AND p.status::text = 'open')
SELECT per.code AS org, per.name AS period, per."startDate", per."endDate",
       (SELECT count(*) FROM "JournalEntry" e
         WHERE e."organizationId" = per."organizationId" AND e.status::text IN ('posted', 'reversed')
           AND e."postingDate" BETWEEN per.su AND per.fu) AS entries
  FROM per
 WHERE per.fu < (now() AT TIME ZONE 'UTC')
   AND EXISTS (SELECT 1 FROM "JournalEntry" e
                WHERE e."organizationId" = per."organizationId" AND e.status::text IN ('posted', 'reversed')
                  AND e."postingDate" BETWEEN per.su AND per.fu)
   AND NOT EXISTS (SELECT 1
                     FROM "JournalEntry" e
                     JOIN "JournalLine" l ON l."journalEntryId" = e.id
                     JOIN "Account" a ON a.id = l."accountId"
                     LEFT JOIN "AccountCategory" cat ON cat.id = a."categoryId"
                    WHERE e."organizationId" = per."organizationId" AND e.status::text IN ('posted', 'reversed')
                      AND e."postingDate" BETWEEN per.su AND per.fu
                      AND coalesce(a."reportSection"::text, cat."reportSection"::text)
                          IN ('revenue', 'contra_revenue', 'cogs', 'operating_expense', 'other_income', 'other_expense')
                    GROUP BY l."accountId"
                   HAVING sum(l."baseDebit" - l."baseCredit") <> 0)
 ORDER BY 1, 3;

\echo '== D5 (P1-2) reversals of entries whose source is outside the target allowlist (manual, recurring_entry), by source'
\echo '   byDocumentReversal = made by a document reversal (V520); genericRouteKey = an idempotency key of'
\echo '   POST /journal-entries/<id>/reverse survives (keys expire, so 0 does not prove the generic route was unused)'
WITH rev AS (
  SELECT r.id, r."organizationId", r."postingDate", src.id AS "originalId", src."sourceType" AS "originalSource"
    FROM "JournalEntry" r
    JOIN "JournalEntry" src ON src.id = r."reversalOfId"
   WHERE r."reversalOfId" IS NOT NULL AND coalesce(src."sourceType", '') NOT IN ('', 'recurring_entry'))
SELECT o.code AS org, rev."originalSource", count(*) AS reversals,
       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM "DocumentReversal" d
                                       WHERE d."organizationId" = rev."organizationId"
                                         AND rev.id = ANY (d."reversalEntryIds"))) AS "byDocumentReversal",
       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM "IdempotencyRecord" i
                                       WHERE i."organizationId" = rev."organizationId" AND i.method = 'POST'
                                         AND i.path LIKE '%/journal-entries/' || rev."originalId" || '/reverse'))
         AS "genericRouteKey",
       min(rev."postingDate") AS "firstReversal", max(rev."postingDate") AS "lastReversal"
  FROM rev
  JOIN "Organization" o ON o.id = rev."organizationId"
 GROUP BY o.code, rev."originalSource"
 ORDER BY 1, 2;

\echo '== D5b (P1-2) the same reversals one by one, except those made by a document reversal (first 500)'
SELECT o.code AS org, src."sourceType" AS "originalSource", src."entryNumber" AS original, r."entryNumber" AS reversal,
       r."postingDate", r."postedBy", r.description
  FROM "JournalEntry" r
  JOIN "JournalEntry" src ON src.id = r."reversalOfId"
  JOIN "Organization" o ON o.id = r."organizationId"
 WHERE r."reversalOfId" IS NOT NULL AND coalesce(src."sourceType", '') NOT IN ('', 'recurring_entry')
   AND NOT EXISTS (SELECT 1 FROM "DocumentReversal" d
                    WHERE d."organizationId" = r."organizationId" AND r.id = ANY (d."reversalEntryIds"))
 ORDER BY o.code, src."sourceType", r."postingDate"
 LIMIT 500;

\echo '== D6a (P1-2) closed or locked periods whose closing entry is reversed while none is posted'
SELECT o.code AS org, p.name AS period, p.status, ce."entryNumber" AS "closingEntry", r."entryNumber" AS reversal,
       r."postingDate" AS "reversedOn", r."postedBy" AS "reversedBy"
  FROM "FiscalPeriod" p
  JOIN "Organization" o ON o.id = p."organizationId"
  JOIN "JournalEntry" ce ON ce."organizationId" = p."organizationId" AND ce."sourceType" = 'period_close'
       AND ce."sourceId" = p.id AND ce.status::text = 'reversed'
  LEFT JOIN "JournalEntry" r ON r.id = ce."reversedEntryId"
 WHERE p."deletedAt" IS NULL AND p.status::text IN ('closed', 'locked')
   AND NOT EXISTS (SELECT 1 FROM "JournalEntry" c
                    WHERE c."organizationId" = p."organizationId" AND c."sourceType" = 'period_close'
                      AND c."sourceId" = p.id AND c.status::text = 'posted')
 ORDER BY 1, p."startDate";

\echo '== D6b closed or locked periods with P&L postings in their stored window but no posted closing entry'
WITH orgs AS (
  SELECT o.id, o.code,
         CASE WHEN o.timezone IN (SELECT name FROM pg_timezone_names) THEN o.timezone ELSE 'UTC' END AS tz
    FROM "Organization" o),
per AS (
  SELECT p.id, p."organizationId", o.code, p.name, p.status::text AS status, p."startDate",
         p."startDate" AT TIME ZONE o.tz AT TIME ZONE 'UTC' AS su,
         p."endDate" AT TIME ZONE o.tz AT TIME ZONE 'UTC' AS fu
    FROM "FiscalPeriod" p
    JOIN orgs o ON o.id = p."organizationId"
   WHERE p."deletedAt" IS NULL AND p.status::text IN ('closed', 'locked'))
SELECT per.code AS org, per.name AS period, per.status
  FROM per
 WHERE EXISTS (SELECT 1
                 FROM "JournalEntry" e
                 JOIN "JournalLine" l ON l."journalEntryId" = e.id
                 JOIN "Account" a ON a.id = l."accountId"
                 LEFT JOIN "AccountCategory" cat ON cat.id = a."categoryId"
                WHERE e."organizationId" = per."organizationId" AND e.status::text IN ('posted', 'reversed')
                  AND e."postingDate" BETWEEN per.su AND per.fu
                  AND coalesce(e."sourceType", '') NOT IN ('period_close', 'fiscal_year_close', 'reversal')
                  AND coalesce(a."reportSection"::text, cat."reportSection"::text)
                      IN ('revenue', 'contra_revenue', 'cogs', 'operating_expense', 'other_income', 'other_expense'))
   AND NOT EXISTS (SELECT 1 FROM "JournalEntry" c
                    WHERE c."organizationId" = per."organizationId" AND c."sourceType" = 'period_close'
                      AND c.status::text = 'posted'
                      AND (c."sourceId" = per.id
                        OR ((c."sourceId" IS NULL OR c."sourceId" NOT IN (SELECT id FROM "FiscalPeriod"
                                                                          WHERE "organizationId" = per."organizationId"))
                            AND c."postingDate" BETWEEN per.su AND per.fu)))
 ORDER BY 1, per."startDate";

\echo '== D7 (P2-4) VAT settlements whose offset entry is missing or no longer posted'
SELECT o.code AS org, s.id AS "settlementId", s."periodFrom", s."periodTo", s."inputTax", s."journalEntryId",
       e.status AS "entryStatus", s."settledAt", s."settledBy"
  FROM "TaxSettlement" s
  JOIN "Organization" o ON o.id = s."organizationId"
  LEFT JOIN "JournalEntry" e ON e.id = s."journalEntryId"
 WHERE (s."journalEntryId" IS NOT NULL AND (e.id IS NULL OR e.status::text <> 'posted'))
    OR (s."journalEntryId" IS NULL AND s."inputTax" > 0)
 ORDER BY 1, s."periodTo";

\echo '== D7b VAT settlements whose period figures changed since settling (re-measured as TaxSettlementService does)'
WITH orgs AS (
  SELECT o.id, o.code,
         CASE WHEN o.timezone IN (SELECT name FROM pg_timezone_names) THEN o.timezone ELSE 'UTC' END AS tz
    FROM "Organization" o),
acc AS (
  SELECT m."organizationId", m."accountId", 'input' AS side
    FROM "AccountMapping" m WHERE m.key = 'tax_receivable'
  UNION
  SELECT x."organizationId", x."accountId", 'output'
    FROM (SELECT "organizationId", "accountId" FROM "AccountMapping" WHERE key = 'tax_payable'
          UNION SELECT "organizationId", "accountId" FROM "Tax" WHERE "accountId" IS NOT NULL) x
   WHERE NOT EXISTS (SELECT 1 FROM "AccountMapping" r
                      WHERE r."organizationId" = x."organizationId" AND r.key = 'tax_receivable'
                        AND r."accountId" = x."accountId")),
mv AS (
  SELECT s.id,
         coalesce(-sum(l."baseDebit" - l."baseCredit") FILTER (WHERE acc.side = 'output'), 0) AS output,
         coalesce(sum(l."baseDebit" - l."baseCredit") FILTER (WHERE acc.side = 'input'), 0) AS input
    FROM "TaxSettlement" s
    JOIN orgs o ON o.id = s."organizationId"
    LEFT JOIN ("JournalLine" l
               JOIN "JournalEntry" e ON e.id = l."journalEntryId" AND e.status::text IN ('posted', 'reversed')
                    AND coalesce(e."sourceType", '') <> 'tax_settlement'
                    AND coalesce(e.dimensions ->> 'treasuryOperation', '') <> 'tax_payment'
                    AND NOT EXISTS (SELECT 1 FROM "JournalEntry" t WHERE t.id = e."reversalOfId"
                                      AND coalesce(t.dimensions ->> 'treasuryOperation', '') = 'tax_payment')
               JOIN acc ON acc."organizationId" = l."organizationId" AND acc."accountId" = l."accountId")
           ON l."organizationId" = s."organizationId"
          AND e."postingDate" >= s."periodFrom" AT TIME ZONE o.tz AT TIME ZONE 'UTC'
          AND e."postingDate" <= s."periodTo" AT TIME ZONE o.tz AT TIME ZONE 'UTC'
   GROUP BY s.id)
SELECT o.code AS org, s.id AS "settlementId", s."periodFrom", s."periodTo", s."outputTax" AS "settledOutput",
       mv.output AS "outputNow", s."inputTax" AS "settledInput", mv.input AS "inputNow", s."settledAt"
  FROM "TaxSettlement" s
  JOIN mv ON mv.id = s.id
  JOIN "Organization" o ON o.id = s."organizationId"
 WHERE abs(mv.output - s."outputTax") > 0.01 OR abs(mv.input - s."inputTax") > 0.01
 ORDER BY 1, s."periodTo";

\echo '== D8a overlapping live fiscal periods'
SELECT o.code AS org, a.name AS period, b.name AS "overlapsWith", a."startDate", a."endDate", b."startDate" AS "otherStart",
       b."endDate" AS "otherEnd"
  FROM "FiscalPeriod" a
  JOIN "FiscalPeriod" b ON b."organizationId" = a."organizationId" AND a.id < b.id AND b."deletedAt" IS NULL
       AND a."startDate" <= b."endDate" AND b."startDate" <= a."endDate"
  JOIN "Organization" o ON o.id = a."organizationId"
 WHERE a."deletedAt" IS NULL
 ORDER BY 1, a."startDate";

\echo '== D8b posted entries dated more than 31 days ahead or before 2000'
SELECT o.code AS org, e."entryNumber", e."postingDate", e."sourceType", e."postedAt"
  FROM "JournalEntry" e
  JOIN "Organization" o ON o.id = e."organizationId"
 WHERE e.status::text IN ('posted', 'reversed')
   AND (e."postingDate" > (now() AT TIME ZONE 'UTC') + interval '31 days' OR e."postingDate" < timestamp '2000-01-01')
 ORDER BY 1, e."postingDate";

\echo '== D8c posted entries that do not balance in base, have fewer than 2 lines, or carry an all-zero line'
SELECT o.code AS org, e."entryNumber", e."sourceType", e.status, count(l.id) AS lines,
       coalesce(sum(l."baseDebit"), 0) AS "baseDebit", coalesce(sum(l."baseCredit"), 0) AS "baseCredit",
       count(l.id) FILTER (WHERE l.debit = 0 AND l.credit = 0 AND l."baseDebit" = 0 AND l."baseCredit" = 0)
         AS "zeroLines"
  FROM "JournalEntry" e
  JOIN "Organization" o ON o.id = e."organizationId"
  LEFT JOIN "JournalLine" l ON l."journalEntryId" = e.id
 WHERE e.status::text IN ('posted', 'reversed')
 GROUP BY o.code, e.id, e."entryNumber", e."sourceType", e.status
HAVING coalesce(sum(l."baseDebit"), 0) <> coalesce(sum(l."baseCredit"), 0) OR count(l.id) < 2
    OR count(l.id) FILTER (WHERE l.debit = 0 AND l.credit = 0 AND l."baseDebit" = 0 AND l."baseCredit" = 0) > 0
 ORDER BY 1, 2;

\echo '== D8d journal lines that break the V499 amount rules (added NOT VALID, never validated against older rows)'
SELECT o.code AS org, e."entryNumber", l."lineNumber", l.debit, l.credit, l."baseDebit", l."baseCredit"
  FROM "JournalLine" l
  JOIN "JournalEntry" e ON e.id = l."journalEntryId"
  JOIN "Organization" o ON o.id = l."organizationId"
 WHERE NOT (l.debit >= 0 AND l.credit >= 0 AND l."baseDebit" >= 0 AND l."baseCredit" >= 0)
    OR (l.debit > 0 AND l.credit > 0) OR (l."baseDebit" > 0 AND l."baseCredit" > 0)
 ORDER BY 1, 2, 3;

\echo '== D8e entries reversed more than once (duplicate reversalOfId)'
SELECT o.code AS org, src."entryNumber" AS original, count(*) AS reversals, string_agg(r."entryNumber", ', ') AS entries
  FROM "JournalEntry" r
  JOIN "JournalEntry" src ON src.id = r."reversalOfId"
  JOIN "Organization" o ON o.id = r."organizationId"
 WHERE r.status::text IN ('posted', 'reversed')
 GROUP BY o.code, src."entryNumber"
HAVING count(*) > 1
 ORDER BY 1, 2;

\echo '== D9a (P2-5) receivable / payable control accounts: base GL balance and the part posted without a partner'
\echo '   Compare with POST /reports/run/ar.reconciliation, POST /reports/run/ap.reconciliation (OpenItems base residuals)'
\echo '   and GET /reports/accounting/tieout on the same copy.'
SELECT o.code AS org, cat.key AS side, a.code AS account, a.name, count(DISTINCT l."partnerId") AS partners,
       sum(l."baseDebit" - l."baseCredit") AS "glBalance",
       coalesce(sum(l."baseDebit" - l."baseCredit") FILTER (WHERE l."partnerId" IS NULL), 0) AS "withoutPartner"
  FROM "JournalLine" l
  JOIN "JournalEntry" e ON e.id = l."journalEntryId" AND e.status::text IN ('posted', 'reversed')
  JOIN "Account" a ON a.id = l."accountId"
  JOIN "AccountCategory" cat ON cat.id = a."categoryId" AND cat.key IN ('receivable', 'payable')
  JOIN "Organization" o ON o.id = l."organizationId"
 GROUP BY o.code, cat.key, a.code, a.name
 ORDER BY 1, 2, 3;

\echo '== D9b (P2-12) gratuity provision rows whose movement differs from the GL movement of the entry they point at'
WITH prov AS (
  SELECT g."organizationId", g."journalEntryId", g."asOf", count(*) AS rows, sum(g.movement) AS movement
    FROM "HrGratuityProvision" g
   WHERE g."deletedAt" IS NULL AND g."journalEntryId" IS NOT NULL
   GROUP BY g."organizationId", g."journalEntryId", g."asOf"),
gl AS (
  SELECT l."journalEntryId", sum(l."baseCredit" - l."baseDebit") AS movement
    FROM "JournalLine" l
    JOIN "AccountMapping" m ON m."organizationId" = l."organizationId" AND m."accountId" = l."accountId"
         AND m.key = 'gratuity_provision'
   WHERE l."journalEntryId" IN (SELECT "journalEntryId" FROM prov)
   GROUP BY l."journalEntryId")
SELECT o.code AS org, prov."asOf", e."entryNumber", prov.rows, prov.movement AS "provisionMovement",
       coalesce(gl.movement, 0) AS "glMovement"
  FROM prov
  JOIN "Organization" o ON o.id = prov."organizationId"
  JOIN "JournalEntry" e ON e.id = prov."journalEntryId"
  LEFT JOIN gl ON gl."journalEntryId" = prov."journalEntryId"
 WHERE abs(prov.movement - coalesce(gl.movement, 0)) > 0.01
 ORDER BY 1, 2;

ROLLBACK;
