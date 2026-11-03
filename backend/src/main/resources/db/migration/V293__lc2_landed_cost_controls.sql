-- Landed cost LC-2 (ADR-S053): financial controls.
--
-- 1. Approval: the hash of every money driver and the per-target disposition at approval; the disposition actually
--    posted (audit keeps both and their difference).
-- 2. Duplicate supplier invoices: the normalised reference, a "live" flag maintained with the voucher status, and an
--    explicit override document. Two partial unique indexes over live invoice registrations (no sentinel values):
--      * without an override: one live voucher per (org, supplier, normalised reference);
--      * with an override: one live voucher per override (and the override is used once).
--    Credit charges (they reference their original) and linked bills are exempt.
-- 3. Allocation history: the weight, volume and unit used on each allocation row, so a later product edit never
--    changes what was posted.

ALTER TABLE "LandedCost"
    ADD COLUMN "approvedHash"        TEXT,
    ADD COLUMN "approvedDisposition" JSONB,
    ADD COLUMN "postedDisposition"   JSONB;

CREATE TABLE "InvoiceDuplicateOverride" (
    id                       TEXT PRIMARY KEY,
    "organizationId"         TEXT NOT NULL REFERENCES "Organization" (id),
    "supplierId"             TEXT NOT NULL,
    "supplierInvoiceRef"     TEXT NOT NULL,
    "supplierInvoiceRefNorm" TEXT NOT NULL,
    reason                   TEXT NOT NULL,
    status                   TEXT NOT NULL DEFAULT 'approved' CHECK (status IN ('approved', 'revoked')),
    "approvedBy"             TEXT,
    "approvedAt"             TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "revokedBy"              TEXT,
    "revokedAt"              TIMESTAMP(3),
    "usedByChargeId"         TEXT
);
-- usedByChargeId holds the LandedCostInvoiceRef the override was used by (one use).
CREATE UNIQUE INDEX "InvoiceDuplicateOverride_used_uq" ON "InvoiceDuplicateOverride" ("usedByChargeId")
    WHERE "usedByChargeId" IS NOT NULL;
CREATE INDEX "InvoiceDuplicateOverride_ref_idx"
    ON "InvoiceDuplicateOverride" ("organizationId", "supplierId", "supplierInvoiceRefNorm");

ALTER TABLE "LandedCostCharge"
    ADD COLUMN "supplierInvoiceRefNorm" TEXT,
    ADD COLUMN live                     BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN "duplicateOverrideId"    TEXT REFERENCES "InvoiceDuplicateOverride" (id);

-- strict normalisation (the default): upper case, trimmed, the separators " -_/." removed.
UPDATE "LandedCostCharge"
SET "supplierInvoiceRefNorm" = upper(regexp_replace(btrim("supplierInvoiceRef"), '[ \-_/.]', '', 'g'))
WHERE "supplierInvoiceRef" IS NOT NULL;

UPDATE "LandedCostCharge" c SET live = FALSE
FROM "LandedCost" l WHERE l.id = c."landedCostId" AND l.status IN ('cancelled', 'reversed');

-- One supplier invoice may span several charge lines of one voucher (freight + handling on one invoice), so the
-- uniqueness lives on a registry with one row per (voucher, supplier, normalised reference), not on the charges.
CREATE TABLE "LandedCostInvoiceRef" (
    id                       TEXT PRIMARY KEY,
    "organizationId"         TEXT NOT NULL REFERENCES "Organization" (id),
    "landedCostId"           TEXT NOT NULL REFERENCES "LandedCost" (id) ON DELETE CASCADE,
    "supplierId"             TEXT NOT NULL,
    "supplierInvoiceRef"     TEXT NOT NULL,
    "supplierInvoiceRefNorm" TEXT NOT NULL,
    live                     BOOLEAN NOT NULL DEFAULT TRUE,
    "duplicateOverrideId"    TEXT REFERENCES "InvoiceDuplicateOverride" (id),
    "createdAt"              TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "LandedCostInvoiceRef_voucher_uq"
    ON "LandedCostInvoiceRef" ("landedCostId", "supplierId", "supplierInvoiceRefNorm");

INSERT INTO "LandedCostInvoiceRef" (id, "organizationId", "landedCostId", "supplierId", "supplierInvoiceRef",
                                    "supplierInvoiceRefNorm", live, "createdAt")
SELECT gen_random_uuid()::text, c."organizationId", c."landedCostId", c."supplierId", min(c."supplierInvoiceRef"),
       c."supplierInvoiceRefNorm", bool_and(c.live), min(l."createdAt")
FROM "LandedCostCharge" c JOIN "LandedCost" l ON l.id = c."landedCostId"
WHERE c."supplierId" IS NOT NULL AND c."supplierInvoiceRefNorm" IS NOT NULL AND c."creditOfChargeId" IS NULL
  AND c."billMode" <> 'link'
GROUP BY c."organizationId", c."landedCostId", c."supplierId", c."supplierInvoiceRefNorm";

-- Historic duplicates among live vouchers: every later one gets its own migration override, so the unique index can
-- be built without hiding them (they are listed with the overrides).
WITH ranked AS (
    SELECT r.*, row_number() OVER (PARTITION BY r."organizationId", r."supplierId", r."supplierInvoiceRefNorm"
                                   ORDER BY r."createdAt", r.id) AS rn
    FROM "LandedCostInvoiceRef" r WHERE r.live
), dup AS (
    SELECT ranked.*, gen_random_uuid()::text AS override_id FROM ranked WHERE rn > 1
), ins AS (
    INSERT INTO "InvoiceDuplicateOverride" (id, "organizationId", "supplierId", "supplierInvoiceRef",
                                            "supplierInvoiceRefNorm", reason, "usedByChargeId")
    SELECT override_id, "organizationId", "supplierId", "supplierInvoiceRef", "supplierInvoiceRefNorm",
           'Historic duplicate found by migration V293', id FROM dup
)
UPDATE "LandedCostInvoiceRef" r SET "duplicateOverrideId" = d.override_id FROM dup d WHERE d.id = r.id;

CREATE UNIQUE INDEX "LandedCostInvoiceRef_normal_uq" ON "LandedCostInvoiceRef"
    ("organizationId", "supplierId", "supplierInvoiceRefNorm") WHERE "duplicateOverrideId" IS NULL AND live;
CREATE UNIQUE INDEX "LandedCostInvoiceRef_override_uq" ON "LandedCostInvoiceRef"
    ("organizationId", "supplierId", "supplierInvoiceRefNorm", "duplicateOverrideId")
    WHERE "duplicateOverrideId" IS NOT NULL AND live;

ALTER TABLE "LandedCostAllocation"
    ADD COLUMN "weightUsed" NUMERIC(20, 6),
    ADD COLUMN "volumeUsed" NUMERIC(20, 6),
    ADD COLUMN "uomId"      TEXT;

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['landed_cost:override_duplicate']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['landed_cost:override_duplicate'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['InvoiceDuplicateOverride', 'LandedCostInvoiceRef'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
