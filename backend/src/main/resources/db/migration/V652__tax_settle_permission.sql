-- V652: the tax:settle permission (finance readiness audit A4, P2-10; owner decision 2026-11-23).
--
-- POST /tax-settlements posts the VAT offset entry and locks the period's tax postings in one step, and
-- POST /tax-settlements/{id}/reverse undoes the latest settlement. Both used to need only journal_entry:create, the
-- permission of the maker of a draft journal entry. They now need tax:settle.
--
-- Granted to:
--   - Administrator, which by convention holds every permission (the boot-time permission sync does the same);
--   - every role that holds journal_entry:post, the permission to post entries to the ledger. Those roles could settle
--     before (they hold journal_entry:create in practice) and keep it.
-- A role that holds journal_entry:create without journal_entry:post can no longer settle VAT: that is the point of the
-- change. Give it tax:settle by a role edit where that is wanted.
INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['tax:settle']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = coalesce(permissions, ARRAY[]::text[]) || ARRAY['tax:settle'],
    "updatedAt" = CURRENT_TIMESTAMP
WHERE "deletedAt" IS NULL
  AND (name = 'Administrator' OR coalesce(permissions, ARRAY[]::text[]) @> ARRAY['journal_entry:post'])
  AND NOT (coalesce(permissions, ARRAY[]::text[]) @> ARRAY['tax:settle']);
