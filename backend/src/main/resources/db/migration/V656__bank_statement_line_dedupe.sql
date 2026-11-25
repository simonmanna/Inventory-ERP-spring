-- Finance readiness audit B4: a duplicate key for bank statement lines.
--
-- Re-importing a statement (or two imports racing) must not insert its lines twice. Lines with a bank reference were
-- checked by reference before insert (racy); lines without one were not checked at all. Every line now carries
-- "dedupeKey", unique per bank account, and the import inserts with ON CONFLICT DO NOTHING (skipped):
--   with a reference:    'ref:' || externalRef
--   without a reference: 'line:' || UTC day || '|' || amount (trailing zeros trimmed) || '|' || md5(description)
--                        || '#' || its occurrence among identical lines of the same file
-- Existing rows are keyed the same way, numbered in creation order, so the unique index can be built over them.
ALTER TABLE "BankStatementLine" ADD COLUMN "dedupeKey" TEXT;

UPDATE "BankStatementLine" b SET "dedupeKey" = k.key
  FROM (SELECT id,
               CASE WHEN coalesce("externalRef", '') <> ''
                    THEN 'ref:' || "externalRef" || CASE WHEN rn > 1 THEN '#' || rn ELSE '' END
                    ELSE 'line:' || to_char("postedAt", 'YYYY-MM-DD') || '|' || trim_scale(amount)::text || '|'
                         || md5(coalesce(description, '')) || '#' || rn END AS key
          FROM (SELECT id, "externalRef", "postedAt", amount, description,
                       row_number() OVER (PARTITION BY "organizationId", "bankAccountId",
                           CASE WHEN coalesce("externalRef", '') <> '' THEN 'r' || "externalRef"
                                ELSE 'l' || to_char("postedAt", 'YYYY-MM-DD') || '|' || trim_scale(amount)::text || '|'
                                     || md5(coalesce(description, '')) END
                           ORDER BY "createdAt", id) AS rn
                  FROM "BankStatementLine") x) k
 WHERE k.id = b.id;

CREATE UNIQUE INDEX "BankStatementLine_dedupe_key" ON "BankStatementLine" ("organizationId", "bankAccountId", "dedupeKey");
