-- Landed cost LC-7 (ADR-S053): advanced.
--
-- 1. Customs tariff engine: versioned tariff lines (import country, HS code, origin, period, valuation basis, duty
--    type and rates, trade preference, additional duty, excise, legal reference, source). An edit creates a new version;
--    a past calculation is never altered.
-- 2. CustomsCalculation: one immutable record per calculation — its inputs, the tariff version used with copies of the
--    rates and legal reference, its outputs, and any manual adjustment with its reason. Only the link to the charge it
--    was used for may be set afterwards (trigger).
-- 3. Allocation templates: reusable charge presets for vouchers.
-- 4. Product HS code; Incoterm on purchase orders and import shipments.

ALTER TABLE "Product" ADD COLUMN "hsCode" TEXT;

-- Incoterm of the purchase and of the import shipment (responsibility matrix; accrual rules by Incoterm).
ALTER TABLE "PurchaseOrder" ADD COLUMN incoterm TEXT;
ALTER TABLE "Shipment" ADD COLUMN incoterm TEXT;

CREATE TABLE "CustomsTariff" (
    id                  TEXT PRIMARY KEY,
    "organizationId"    TEXT NOT NULL REFERENCES "Organization" (id),
    "importCountry"     TEXT NOT NULL,
    "hsCode"            TEXT NOT NULL,
    "originCountry"     TEXT,
    "preferenceCode"    TEXT,
    "effectiveFrom"     DATE NOT NULL,
    "effectiveTo"       DATE,
    "valuationBasis"    TEXT NOT NULL DEFAULT 'cif' CHECK ("valuationBasis" IN ('cif', 'fob', 'transaction')),
    "dutyType"          TEXT NOT NULL CHECK ("dutyType" IN ('ad_valorem', 'specific', 'compound', 'exempt')),
    "adValoremPct"      NUMERIC(9, 4) NOT NULL DEFAULT 0,
    "specificAmount"    NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "specificUom"       TEXT,
    "additionalDutyPct" NUMERIC(9, 4) NOT NULL DEFAULT 0,
    "excisePct"         NUMERIC(9, 4) NOT NULL DEFAULT 0,
    "legalReference"    TEXT,
    source              TEXT,
    version             INTEGER NOT NULL DEFAULT 1,
    "previousVersionId" TEXT REFERENCES "CustomsTariff" (id),
    "isActive"          BOOLEAN NOT NULL DEFAULT TRUE,
    "createdBy"         TEXT,
    "createdAt"         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "CustomsTariff_period" CHECK ("effectiveTo" IS NULL OR "effectiveTo" >= "effectiveFrom")
);
CREATE INDEX "CustomsTariff_lookup_idx" ON "CustomsTariff" ("organizationId", "importCountry", "hsCode") WHERE "isActive";

CREATE TABLE "CustomsCalculation" (
    id                  TEXT PRIMARY KEY,
    "organizationId"    TEXT NOT NULL REFERENCES "Organization" (id),
    "importCountry"     TEXT NOT NULL,
    "hsCode"            TEXT NOT NULL,
    "originCountry"     TEXT,
    "preferenceCode"    TEXT,
    "declarationDate"   DATE NOT NULL,
    "tariffId"          TEXT NOT NULL REFERENCES "CustomsTariff" (id),
    "tariffVersion"     INTEGER NOT NULL,
    "valuationBasis"    TEXT NOT NULL,
    "dutyType"          TEXT NOT NULL,
    "adValoremPct"      NUMERIC(9, 4) NOT NULL,
    "specificAmount"    NUMERIC(20, 6) NOT NULL,
    "additionalDutyPct" NUMERIC(9, 4) NOT NULL,
    "excisePct"         NUMERIC(9, 4) NOT NULL,
    "legalReference"    TEXT,
    inputs              JSONB NOT NULL,
    "customsValue"      NUMERIC(20, 2) NOT NULL,
    quantity            NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "dutyAmount"        NUMERIC(20, 2) NOT NULL,
    "additionalAmount"  NUMERIC(20, 2) NOT NULL,
    "exciseAmount"      NUMERIC(20, 2) NOT NULL,
    "manualAdjustment"  NUMERIC(20, 2) NOT NULL DEFAULT 0,
    "adjustmentReason"  TEXT,
    "totalAmount"       NUMERIC(20, 2) NOT NULL,
    "landedCostChargeId" TEXT,
    "createdBy"         TEXT,
    "createdAt"         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "CustomsCalculation_adjustment" CHECK ("manualAdjustment" = 0 OR "adjustmentReason" IS NOT NULL)
);
CREATE INDEX "CustomsCalculation_org_idx" ON "CustomsCalculation" ("organizationId", "declarationDate");

CREATE OR REPLACE FUNCTION customs_calculation_immutable() RETURNS trigger AS $$
BEGIN
    IF (to_jsonb(NEW) - 'landedCostChargeId') IS DISTINCT FROM (to_jsonb(OLD) - 'landedCostChargeId') THEN
        RAISE EXCEPTION 'A customs calculation is immutable; calculate again instead';
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER "CustomsCalculation_immutable" BEFORE UPDATE ON "CustomsCalculation"
    FOR EACH ROW EXECUTE FUNCTION customs_calculation_immutable();
CREATE OR REPLACE FUNCTION customs_calculation_immutable_delete() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'A customs calculation is immutable and cannot be deleted';
END $$ LANGUAGE plpgsql;
CREATE TRIGGER "CustomsCalculation_no_delete" BEFORE DELETE ON "CustomsCalculation"
    FOR EACH ROW EXECUTE FUNCTION customs_calculation_immutable_delete();

CREATE TABLE "LandedCostTemplate" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    name             TEXT NOT NULL,
    description      TEXT,
    charges          JSONB NOT NULL,
    "isActive"       BOOLEAN NOT NULL DEFAULT TRUE,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "LandedCostTemplate_name_uq" ON "LandedCostTemplate" ("organizationId", lower(name)) WHERE "isActive";

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['landed_cost:customs_adjust']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['landed_cost:customs_adjust'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;

DO $$
DECLARE
    t text;
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    FOREACH t IN ARRAY ARRAY['CustomsTariff', 'CustomsCalculation', 'LandedCostTemplate'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
