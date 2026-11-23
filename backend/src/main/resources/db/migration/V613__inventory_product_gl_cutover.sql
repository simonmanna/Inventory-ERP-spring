-- Inventory hardening R3 (2026-11-23): from this release on, stock-account journal lines carry the product they move
-- (JournalLine.dimensions ->> 'inventoryProductId'). The per-product GL check of GET /inventory/reports/integrity
-- reconciles only what was recorded from this instant on; this marks that instant for every organisation that exists
-- now. Organisations created later have no marker: their whole history is stamped. Data only, no schema change.
INSERT INTO "Setting" (id, "organizationId", scope, "scopeType", "scopeId", key, value, "createdAt", "updatedAt")
SELECT gen_random_uuid()::text, o.id, 'organization', 'organization', '', 'inventory.productGlCutoverAt',
       to_jsonb(to_char(clock_timestamp()::timestamp(3), 'YYYY-MM-DD HH24:MI:SS.MS')), now(), now()
  FROM "Organization" o
ON CONFLICT ("organizationId", "scopeType", "scopeId", key) DO NOTHING;
