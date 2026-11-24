-- Finance readiness audit, fix step B0: register the granite quarry demo company's plant in the asset register.
--
-- The granite quarry demo seed (GraniteQuarryDemoSeed) posts the plant as an opening balance (journal gqdemo:opening:
-- fixed assets 300,000,000) and its first month of depreciation (journal gqdemo:depreciation: 1,080,000), but before
-- fix step B0 it wrote no asset register rows. The fixed asset reconciliation and integrity check I10 therefore show the
-- whole balance as a difference. Since B0 the seed registers the plant itself. This script gives a company seeded before
-- that the same rows: the category, the crusher plant and the block machine, and their first month's depreciation,
-- recorded as posted by the seed's own depreciation journal.
--
-- Register rows only, no journal entry: the general ledger already holds the plant. An acquisition or a depreciation
-- run through the application would post it a second time. Later months depreciate through the normal depreciation run.
--
-- Owner decision 2026-11-24: register the assets with this reviewed script. Rehearse it on a restored copy first, take
-- a fresh backup, then apply it once. The default is a dry run, which rolls everything back. Pass -v apply=1 to commit.
-- -v org=<code> names the company (default GQDEMO).
--
--   psql "$DATABASE_URL" -X -f docs/audits/2026-11-24-register-gqdemo-plant.sql               # dry run
--   psql "$DATABASE_URL" -X -v apply=1 -f docs/audits/2026-11-24-register-gqdemo-plant.sql    # apply
--
-- Guards (any failure stops the run and changes nothing):
--   - The company has both seed journals, posted, in the same month.
--   - The opening journal debits the fixed asset account by the plant's cost.
--   - The depreciation journal credits accumulated depreciation by the month's charge.
--   - No AST-GQ-* asset exists yet.
--   - Afterwards, the register ties to those accounts.
-- Each row written gets an AuditLog row.

\set ON_ERROR_STOP on
\pset null '∅'
\if :{?org}
\else
\set org GQDEMO
\endif
\if :{?apply}
\else
\set apply false
\endif

BEGIN;
SET LOCAL statement_timeout = '5min';

-- The company and the two seed journals. Opening day = purchase and in-service date; depreciation = the month's charge.
CREATE TEMP TABLE target AS
SELECT o.id AS org, o.code, op.id AS opening, op."branchId" AS branch, op."postingDate" AS "openingDate",
       dp.id AS depreciation, dp."postingDate" AS "depreciationDate"
  FROM "Organization" o
  JOIN "JournalEntry" op ON op."organizationId" = o.id AND op."postingKey" = 'gqdemo:opening:' || o.id
   AND op.status::text = 'posted'
  JOIN "JournalEntry" dp ON dp."organizationId" = o.id AND dp."postingKey" = 'gqdemo:depreciation:' || o.id
   AND dp.status::text = 'posted'
 WHERE o.code = :'org';

-- The plant of GraniteQuarryDemoSeed.PLANT: straight line over 240 months, (cost - salvage) / 240 a month.
CREATE TEMP TABLE plant AS
SELECT v.name, v.code, v.cost::numeric AS cost, v.salvage::numeric AS salvage, v.month::numeric AS month,
       gen_random_uuid()::text AS asset, gen_random_uuid()::text AS depreciation
  FROM (VALUES ('Crusher plant', 'AST-GQ-CRUSHER', 280000000, 40000000, 1000000),
               ('Block machine', 'AST-GQ-BLOCK', 20000000, 800000, 80000)) v(name, code, cost, salvage, month);

DO $$
DECLARE
    t record;
BEGIN
    IF (SELECT count(*) FROM target) <> 1 THEN
        RAISE EXCEPTION 'No company with both granite quarry seed journals (gqdemo:opening, gqdemo:depreciation) posted';
    END IF;
    SELECT * INTO t FROM target;
    IF to_char(t."openingDate", 'YYYY-MM') <> to_char(t."depreciationDate", 'YYYY-MM') THEN
        RAISE EXCEPTION 'The seed journals are in different months (% and %)', t."openingDate", t."depreciationDate";
    END IF;
    IF EXISTS (SELECT 1 FROM "Asset" a WHERE a."organizationId" = t.org AND a."assetCode" LIKE 'AST-GQ-%') THEN
        RAISE EXCEPTION 'The plant is already registered: an AST-GQ-* asset exists';
    END IF;
    IF (SELECT coalesce(sum(l."baseDebit" - l."baseCredit"), 0) FROM "JournalLine" l
          JOIN "AccountMapping" m ON m."accountId" = l."accountId" AND m."organizationId" = t.org
           AND m.key = 'fixed_asset_valuation' AND m."deletedAt" IS NULL
         WHERE l."journalEntryId" = t.opening) <> (SELECT sum(cost) FROM plant) THEN
        RAISE EXCEPTION 'The opening journal does not debit the fixed asset account by the plant''s cost';
    END IF;
    IF (SELECT coalesce(sum(l."baseCredit" - l."baseDebit"), 0) FROM "JournalLine" l
          JOIN "AccountMapping" m ON m."accountId" = l."accountId" AND m."organizationId" = t.org
           AND m.key = 'accumulated_depreciation' AND m."deletedAt" IS NULL
         WHERE l."journalEntryId" = t.depreciation) <> (SELECT sum(month) FROM plant) THEN
        RAISE EXCEPTION 'The depreciation journal does not credit accumulated depreciation by the month''s charge';
    END IF;
END $$;

-- Register (purchase cost, posted depreciation) against the general ledger on the mapped fixed asset, accumulated
-- depreciation and accumulated impairment accounts. A guard, not the full I10 check: run that check after applying.
CREATE TEMP VIEW tie AS
SELECT r.cost AS "registerCost", g.cost AS "glCost", r.acc AS "registerAccumulated", g.acc AS "glAccumulated",
       r.cost = g.cost AND r.acc = g.acc AS ties
  FROM (SELECT coalesce((SELECT sum(a."purchaseCost") FROM "Asset" a
                          WHERE a."organizationId" = t.org AND a."deletedAt" IS NULL), 0) AS cost,
               coalesce((SELECT sum(d."depreciationAmount") FROM "AssetDepreciation" d JOIN "Asset" a ON a.id = d."assetId"
                          WHERE d."organizationId" = t.org AND d."isPosted" AND a."deletedAt" IS NULL), 0) AS acc
          FROM target t) r,
       (SELECT coalesce(sum(l."baseDebit" - l."baseCredit") FILTER (WHERE m.key = 'fixed_asset_valuation'), 0) AS cost,
               coalesce(sum(l."baseCredit" - l."baseDebit") FILTER (WHERE m.key <> 'fixed_asset_valuation'), 0) AS acc
          FROM target t
          JOIN "JournalEntry" e ON e."organizationId" = t.org AND e.status::text IN ('posted', 'reversed')
          JOIN "JournalLine" l ON l."journalEntryId" = e.id
          JOIN "AccountMapping" m ON m."organizationId" = t.org AND m."accountId" = l."accountId" AND m."deletedAt" IS NULL
           AND m.key IN ('fixed_asset_valuation', 'accumulated_depreciation', 'accumulated_impairment')) g;

\echo '== Before: asset register against the general ledger'
SELECT * FROM tie;

-- The category: the seed's, or an existing one of the same name.
CREATE TEMP TABLE category AS
SELECT coalesce(k.id, gen_random_uuid()::text) AS id, k.id IS NOT NULL AS existed
  FROM target t
  LEFT JOIN "AssetCategory" k ON k."organizationId" = t.org AND k.name = 'Plant and machinery';

INSERT INTO "AssetCategory" (id, "organizationId", name, description, "depreciationMethod", "defaultUsefulLife",
                             "isActive", "updatedAt")
SELECT c.id, t.org, 'Plant and machinery', 'Quarry and block plant', 'straight_line'::"DepreciationMethod", 240, true,
       now()
  FROM category c CROSS JOIN target t
 WHERE NOT c.existed;

INSERT INTO "Asset" (id, "organizationId", "assetCode", name, "categoryId", "branchId", location, "purchaseDate",
                     "inServiceDate", "purchaseCost", "currentValue", "salvageValue", "usefulLife", "usefulLifeUnit",
                     "acquisitionMethod", status, notes, "updatedAt")
SELECT p.asset, t.org, p.code, p.name, c.id, t.branch, 'Quarry', date_trunc('day', t."openingDate"),
       t."openingDate"::date, p.cost, p.cost - p.month, p.salvage, 240, 'months',
       'purchased'::"AcquisitionMethod", 'active'::"AssetStatus", 'Opening balance (journal gqdemo:opening)', now()
  FROM plant p CROSS JOIN target t CROSS JOIN category c;

INSERT INTO "AssetDepreciation" (id, "organizationId", "assetId", period, method, "assetCost", "salvageValue",
                                 "usefulLife", "depreciationAmount", "accumulatedDepr", "bookValue", "isPosted",
                                 "journalEntryId", "postedAt", notes, "updatedAt")
SELECT p.depreciation, t.org, p.asset, to_char(t."depreciationDate", 'YYYY-MM'), 'straight_line'::"DepreciationMethod",
       p.cost, p.salvage, 240, p.month, p.month, p.cost - p.month, true, t.depreciation, t."depreciationDate",
       'Posted by the month-end depreciation journal (gqdemo:depreciation)', now()
  FROM plant p CROSS JOIN target t;

INSERT INTO "AuditLog" (id, "organizationId", entity, "entityId", action, "oldValues", "newValues", "userAgent")
SELECT gen_random_uuid()::text, t.org, 'AssetCategory', c.id, 'create'::"AuditAction", NULL::jsonb,
       jsonb_build_object('name', 'Plant and machinery', 'reason',
                          'Finance readiness audit B0: register the plant behind the seeded opening balance'),
       'docs/audits/2026-11-24-register-gqdemo-plant.sql'
  FROM category c CROSS JOIN target t
 WHERE NOT c.existed
UNION ALL
SELECT gen_random_uuid()::text, t.org, 'Asset', p.asset, 'create'::"AuditAction", NULL::jsonb,
       jsonb_build_object('assetCode', p.code, 'name', p.name, 'purchaseCost', p.cost, 'salvageValue', p.salvage,
                          'reason', 'Finance readiness audit B0: register the plant behind the seeded opening balance'
                                    || ' (journal ' || t.opening || '); no journal entry'),
       'docs/audits/2026-11-24-register-gqdemo-plant.sql'
  FROM plant p CROSS JOIN target t
UNION ALL
SELECT gen_random_uuid()::text, t.org, 'AssetDepreciation', p.depreciation, 'create'::"AuditAction", NULL::jsonb,
       jsonb_build_object('assetCode', p.code, 'period', to_char(t."depreciationDate", 'YYYY-MM'),
                          'depreciationAmount', p.month, 'journalEntryId', t.depreciation,
                          'reason', 'Finance readiness audit B0: already posted by the seed''s depreciation journal'),
       'docs/audits/2026-11-24-register-gqdemo-plant.sql'
  FROM plant p CROSS JOIN target t;

\echo '== Rows written'
SELECT a."assetCode", a.name, a."purchaseDate", a."inServiceDate", a."purchaseCost", a."salvageValue",
       a."currentValue", d.period, d."depreciationAmount", d."postedAt",
       d."journalEntryId" = t.depreciation AS "bySeedJournal"
  FROM plant p
  JOIN "Asset" a ON a.id = p.asset
  JOIN "AssetDepreciation" d ON d."assetId" = a.id
 CROSS JOIN target t
 ORDER BY a."assetCode";

\echo '== After: asset register against the general ledger (must tie)'
SELECT * FROM tie;

DO $$
BEGIN
    IF NOT (SELECT ties FROM tie) THEN
        RAISE EXCEPTION 'After the change the asset register does not tie to the general ledger: nothing is changed';
    END IF;
END $$;

\if :apply
COMMIT;
\echo 'Applied. Now run the integrity check (I10 fixed_assets) for the company.'
\else
ROLLBACK;
\echo 'Dry run: rolled back. Re-run with -v apply=1 to apply.'
\endif
