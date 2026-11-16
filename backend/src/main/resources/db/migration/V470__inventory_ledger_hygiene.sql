-- V470: inventory ledger hygiene (phase/inv-hardening, owner-approved deviation; Nest has the same gaps).

-- (a) Redundant InventoryLedger indexes. Every write maintains them; none adds an access path:
--   InventoryLedger_pool_idx (V224) = ("organizationId", "productId", "locationId", "createdAt"), an exact duplicate of
--     V1's InventoryLedger_organizationId_productId_locationId_created_idx.
--   InventoryLedger_organizationId_idx (V1) = ("organizationId"), a leading prefix of
--     InventoryLedger_organizationId_createdAt_idx / _ledgerCode_idx / _productId_locationId_created_idx, which serve
--     every "organizationId = ?" lookup it served.
-- Kept on purpose: InventoryLedger_component_idx (V224) is partial (WHERE "valuationComponent" IS NOT NULL) and tiny,
-- the access path of the CM-1 adjunct-component sums; InventoryLedger_consumption_idx (V156, org/product/createdAt)
-- is not a prefix of the org/product/location/createdAt index.
DROP INDEX IF EXISTS "InventoryLedger_pool_idx";
DROP INDEX IF EXISTS "InventoryLedger_organizationId_idx";

-- (b) The evidence-guard bypass (app.evidence_purge = 'on', used by test fixtures and maintenance purges) could be
-- switched on by any session, including the API role, which defeated every append-only guard (InventoryLedger,
-- CashMovement, posted journals, ...). It now also requires the session user to be a member of the schema owner (the
-- Flyway / migration role, or a superuser). With one shared role (development default) nothing changes; in
-- production the API connects as a non-owner role (DB_USER vs FLYWAY_USER, afterMigrate.sql) and can no longer
-- bypass the guards.
CREATE OR REPLACE FUNCTION public.evidence_purge_enabled() RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
  SELECT coalesce(current_setting('app.evidence_purge', true), '') = 'on'
     AND pg_has_role(session_user,
                     (SELECT c.relowner FROM pg_catalog.pg_class c WHERE c.oid = 'public."InventoryLedger"'::regclass),
                     'MEMBER')
$$;
