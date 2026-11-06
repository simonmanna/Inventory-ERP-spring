-- SG-3: internal notes, rate and agreement on quotations, a lost-reason catalog, transport units.
-- SG-4: proforma bank and mobile money details, default advance. Advance on a proforma lives in Document.data.
ALTER TABLE "SalesQuotation"
    ADD COLUMN "internalNotes" TEXT,
    ADD COLUMN "exchangeRate" NUMERIC(20, 8) NOT NULL DEFAULT 1 CHECK ("exchangeRate" > 0),
    ADD COLUMN "agreementId" TEXT,
    ADD COLUMN "lostReasonId" TEXT;

CREATE TABLE "SalesLostReason" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    name             TEXT         NOT NULL,
    "isActive"       BOOLEAN      NOT NULL DEFAULT true,
    "sortOrder"      INTEGER      NOT NULL DEFAULT 0,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE ("organizationId", name)
);
ALTER TABLE "SalesLostReason" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SalesLostReason" USING (("organizationId" = current_setting('app.org_id', true)));

ALTER TABLE "SalesSettings"
    ADD COLUMN "proformaBankDetails" TEXT,
    ADD COLUMN "proformaMobileDetails" TEXT,
    ADD COLUMN "proformaAdvancePercent" NUMERIC(7, 4) NOT NULL DEFAULT 0
        CHECK ("proformaAdvancePercent" >= 0 AND "proformaAdvancePercent" <= 100);

INSERT INTO "UnitOfMeasure" (id, "organizationId", code, name, symbol, "categoryId", category, factor, ratio,
                             "roundingPrecision", "uomType", "isBase", "isActive", "updatedAt")
SELECT gen_random_uuid()::text, c."organizationId", u.code, u.name, u.symbol, c.id, lower(c.name), 1, 1, 0.01, 'bigger',
       false, true, CURRENT_TIMESTAMP
FROM "UomCategory" c
JOIN (VALUES ('TRIP', 'Trip', 'trip'), ('LOAD', 'Load', 'load')) AS u(code, name, symbol) ON c.name = 'Quantity'
WHERE NOT EXISTS (SELECT 1 FROM "UnitOfMeasure" x WHERE x."organizationId" = c."organizationId" AND x.code = u.code);
