-- V28 (placeholder number, renumbered at merge): quarry & extraction management (ADR-S012, Track C Phase 2.2).
--
-- Additive: new quarry tables + the quarry permissions only. Raw stone enters stock through StockService into a ROM
-- (run-of-mine) stockpile — an ordinary inventory location — at the extraction cost per tonne; explosives are ordinary
-- inventory products held in magazine locations and flagged as controlled here. The GL goes through the posting
-- engine. Cross-module references (branch, cost centre, inventory location / product, fleet vehicle / class / trip,
-- weighbridge ticket, hr employee / shift, partner, journal entry, approval request, user) are plain text ids validated
-- by the quarry services (org-scoped, read-only).

-- ---------------------------------------------------------------- settings (one row per organization)

CREATE TABLE "QuarrySettings" (
    id                        TEXT           NOT NULL PRIMARY KEY,
    "organizationId"          TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    -- net_tonnes: the whole extraction cost is carried by the tonnes that reach the ROM stockpile (losses absorbed);
    -- gross_tonnes: cost per gross tonne moved, the loss share is written off to the extraction-loss account.
    "allocationBasis"         TEXT           NOT NULL DEFAULT 'net_tonnes'
        CHECK ("allocationBasis" IN ('net_tonnes', 'gross_tonnes')),
    -- Fallback rates when an extraction line and the equipment-rate table give none.
    "fuelCostPerLitre"        NUMERIC(14, 4) NOT NULL DEFAULT 0 CHECK ("fuelCostPerLitre" >= 0),
    "labourRatePerHour"       NUMERIC(14, 4) NOT NULL DEFAULT 0 CHECK ("labourRatePerHour" >= 0),
    "equipmentRatePerHour"    NUMERIC(14, 4) NOT NULL DEFAULT 0 CHECK ("equipmentRatePerHour" >= 0),
    -- The person who issues explosives from the magazine must not be the one who requested them.
    "distinctExplosiveIssuer" BOOLEAN        NOT NULL DEFAULT true,
    "updatedBy"               TEXT,
    "createdAt"               TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"               TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "QuarrySettings_org_key" ON "QuarrySettings" ("organizationId");

-- ---------------------------------------------------------------- sites, zones / benches / faces, materials

CREATE TABLE "QuarrySite" (
    id                   TEXT           NOT NULL PRIMARY KEY,
    "organizationId"     TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code                 TEXT           NOT NULL,
    name                 TEXT           NOT NULL,
    status               TEXT           NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'suspended', 'closed')),
    "branchId"           TEXT,
    "costCenterId"       TEXT,
    address              TEXT,
    latitude             NUMERIC(10, 7),
    longitude            NUMERIC(10, 7),
    "areaHectares"       NUMERIC(12, 3) CHECK ("areaHectares" IS NULL OR "areaHectares" >= 0),
    -- Mining / quarry licence of the site (compliance).
    "licenceNumber"      TEXT,
    "licenceAuthority"   TEXT,
    "licenceExpiresOn"   DATE,
    notes                TEXT,
    "createdBy"          TEXT,
    "updatedBy"          TEXT,
    "createdAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"          TIMESTAMP(3)
);
CREATE UNIQUE INDEX "QuarrySite_org_code_key" ON "QuarrySite" ("organizationId", code) WHERE "deletedAt" IS NULL;
CREATE INDEX "QuarrySite_org_status_idx" ON "QuarrySite" ("organizationId", status);

CREATE TABLE "QuarryMaterial" (
    id                TEXT           NOT NULL PRIMARY KEY,
    "organizationId"  TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code              TEXT           NOT NULL,
    name              TEXT           NOT NULL,
    -- Geology: granite, limestone, basalt, sandstone, gravel, laterite, ...
    "rockType"        TEXT,
    grade             TEXT,
    "densityTPerM3"   NUMERIC(8, 3) CHECK ("densityTPerM3" IS NULL OR "densityTPerM3" > 0),
    -- The inventory product raw stone of this type / grade is stocked as (tonnes; AVCO or FIFO costed).
    "productId"       TEXT           NOT NULL,
    "isActive"        BOOLEAN        NOT NULL DEFAULT true,
    notes             TEXT,
    "createdBy"       TEXT,
    "updatedBy"       TEXT,
    "createdAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"       TIMESTAMP(3)
);
CREATE UNIQUE INDEX "QuarryMaterial_org_code_key" ON "QuarryMaterial" ("organizationId", code) WHERE "deletedAt" IS NULL;
CREATE INDEX "QuarryMaterial_product_idx" ON "QuarryMaterial" ("organizationId", "productId");

CREATE TABLE "QuarryZone" (
    id                TEXT           NOT NULL PRIMARY KEY,
    "organizationId"  TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "siteId"          TEXT           NOT NULL REFERENCES "QuarrySite" (id),
    -- zone → bench → face (a face sits on a bench, a bench in a zone; each level may be used alone).
    "parentId"        TEXT REFERENCES "QuarryZone" (id),
    kind              TEXT           NOT NULL DEFAULT 'zone' CHECK (kind IN ('zone', 'bench', 'face')),
    code              TEXT           NOT NULL,
    name              TEXT           NOT NULL,
    "materialId"      TEXT REFERENCES "QuarryMaterial" (id),
    geology           TEXT,
    "elevationM"      NUMERIC(10, 2),
    "benchHeightM"    NUMERIC(8, 2) CHECK ("benchHeightM" IS NULL OR "benchHeightM" > 0),
    status            TEXT           NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'inactive', 'exhausted')),
    notes             TEXT,
    "createdBy"       TEXT,
    "updatedBy"       TEXT,
    "createdAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"       TIMESTAMP(3)
);
CREATE UNIQUE INDEX "QuarryZone_site_code_key" ON "QuarryZone" ("organizationId", "siteId", code) WHERE "deletedAt" IS NULL;
CREATE INDEX "QuarryZone_parent_idx" ON "QuarryZone" ("organizationId", "parentId");

-- A stockpile is an inventory location: kind rom = run-of-mine stone from the pit (crushing 2.3 consumes it).
CREATE TABLE "QuarryStockpile" (
    id                TEXT           NOT NULL PRIMARY KEY,
    "organizationId"  TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "siteId"          TEXT           NOT NULL REFERENCES "QuarrySite" (id),
    code              TEXT           NOT NULL,
    name              TEXT           NOT NULL,
    kind              TEXT           NOT NULL DEFAULT 'rom' CHECK (kind IN ('rom', 'product')),
    "locationId"      TEXT           NOT NULL,
    "capacityT"       NUMERIC(14, 3) CHECK ("capacityT" IS NULL OR "capacityT" > 0),
    "isDefault"       BOOLEAN        NOT NULL DEFAULT false,
    "isActive"        BOOLEAN        NOT NULL DEFAULT true,
    notes             TEXT,
    "createdBy"       TEXT,
    "updatedBy"       TEXT,
    "createdAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"       TIMESTAMP(3)
);
CREATE UNIQUE INDEX "QuarryStockpile_org_code_key" ON "QuarryStockpile" ("organizationId", code) WHERE "deletedAt" IS NULL;
CREATE UNIQUE INDEX "QuarryStockpile_org_location_key" ON "QuarryStockpile" ("organizationId", "locationId")
    WHERE "deletedAt" IS NULL;
CREATE INDEX "QuarryStockpile_site_idx" ON "QuarryStockpile" ("organizationId", "siteId");

-- ---------------------------------------------------------------- cost rates

-- Hourly rate of mobile plant: per vehicle (wins) or per fleet vehicle class.
CREATE TABLE "QuarryEquipmentRate" (
    id                TEXT           NOT NULL PRIMARY KEY,
    "organizationId"  TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "categoryId"      TEXT,
    "vehicleId"       TEXT,
    "ratePerHour"     NUMERIC(14, 4) NOT NULL CHECK ("ratePerHour" >= 0),
    notes             TEXT,
    "updatedBy"       TEXT,
    "createdAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "QuarryEquipmentRate_target_chk" CHECK (("categoryId" IS NULL) <> ("vehicleId" IS NULL))
);
CREATE UNIQUE INDEX "QuarryEquipmentRate_category_key" ON "QuarryEquipmentRate" ("organizationId", "categoryId")
    WHERE "categoryId" IS NOT NULL;
CREATE UNIQUE INDEX "QuarryEquipmentRate_vehicle_key" ON "QuarryEquipmentRate" ("organizationId", "vehicleId")
    WHERE "vehicleId" IS NOT NULL;

-- Royalties, levies and fees per tonne (blank site / material = every site / material), effective-dated.
CREATE TABLE "QuarryRoyaltyRate" (
    id                   TEXT           NOT NULL PRIMARY KEY,
    "organizationId"     TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    name                 TEXT           NOT NULL,
    kind                 TEXT           NOT NULL DEFAULT 'royalty' CHECK (kind IN ('royalty', 'levy', 'fee')),
    "siteId"             TEXT REFERENCES "QuarrySite" (id),
    "materialId"         TEXT REFERENCES "QuarryMaterial" (id),
    basis                TEXT           NOT NULL DEFAULT 'net_tonnes' CHECK (basis IN ('net_tonnes', 'gross_tonnes')),
    "ratePerTonne"       NUMERIC(14, 4) NOT NULL CHECK ("ratePerTonne" >= 0),
    -- The authority / council the amount is owed to (a Partner) — partner of the payable line.
    "authorityPartnerId" TEXT,
    "effectiveFrom"      DATE           NOT NULL,
    "effectiveTo"        DATE,
    "isActive"           BOOLEAN        NOT NULL DEFAULT true,
    notes                TEXT,
    "createdBy"          TEXT,
    "updatedBy"          TEXT,
    "createdAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"          TIMESTAMP(3),
    CONSTRAINT "QuarryRoyaltyRate_period_chk" CHECK ("effectiveTo" IS NULL OR "effectiveTo" >= "effectiveFrom")
);
CREATE INDEX "QuarryRoyaltyRate_org_idx" ON "QuarryRoyaltyRate" ("organizationId", "isActive");

-- ---------------------------------------------------------------- plans & targets

CREATE TABLE "QuarryExtractionPlan" (
    id                    TEXT           NOT NULL PRIMARY KEY,
    "organizationId"      TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code                  TEXT           NOT NULL,
    name                  TEXT           NOT NULL,
    "siteId"              TEXT           NOT NULL REFERENCES "QuarrySite" (id),
    "zoneId"              TEXT REFERENCES "QuarryZone" (id),
    "materialId"          TEXT REFERENCES "QuarryMaterial" (id),
    "periodStart"         DATE           NOT NULL,
    "periodEnd"           DATE           NOT NULL,
    "targetTonnes"        NUMERIC(14, 3) NOT NULL CHECK ("targetTonnes" > 0),
    "budgetCostPerTonne"  NUMERIC(14, 4) CHECK ("budgetCostPerTonne" IS NULL OR "budgetCostPerTonne" >= 0),
    status                TEXT           NOT NULL DEFAULT 'active' CHECK (status IN ('draft', 'active', 'closed')),
    notes                 TEXT,
    "createdBy"           TEXT,
    "updatedBy"           TEXT,
    "createdAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"           TIMESTAMP(3),
    CONSTRAINT "QuarryExtractionPlan_period_chk" CHECK ("periodEnd" >= "periodStart")
);
CREATE UNIQUE INDEX "QuarryExtractionPlan_org_code_key" ON "QuarryExtractionPlan" ("organizationId", code)
    WHERE "deletedAt" IS NULL;
CREATE INDEX "QuarryExtractionPlan_site_period_idx" ON "QuarryExtractionPlan" ("organizationId", "siteId", "periodStart");

-- ---------------------------------------------------------------- explosives: magazines, registry, blasts, issues

CREATE TABLE "QuarryMagazine" (
    id                   TEXT           NOT NULL PRIMARY KEY,
    "organizationId"     TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "siteId"             TEXT REFERENCES "QuarrySite" (id),
    code                 TEXT           NOT NULL,
    name                 TEXT           NOT NULL,
    -- The inventory location holding the magazine's explosive stock.
    "locationId"         TEXT           NOT NULL,
    "licenceNumber"      TEXT,
    "licenceAuthority"   TEXT,
    "licenceExpiresOn"   DATE,
    "capacityKg"         NUMERIC(14, 3) CHECK ("capacityKg" IS NULL OR "capacityKg" > 0),
    "keeperEmployeeId"   TEXT,
    "isActive"           BOOLEAN        NOT NULL DEFAULT true,
    notes                TEXT,
    "createdBy"          TEXT,
    "updatedBy"          TEXT,
    "createdAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"          TIMESTAMP(3)
);
CREATE UNIQUE INDEX "QuarryMagazine_org_code_key" ON "QuarryMagazine" ("organizationId", code) WHERE "deletedAt" IS NULL;
CREATE UNIQUE INDEX "QuarryMagazine_org_location_key" ON "QuarryMagazine" ("organizationId", "locationId")
    WHERE "deletedAt" IS NULL;

-- An inventory product flagged as a controlled explosive (issued only through an approved explosive issue).
CREATE TABLE "QuarryExplosive" (
    id                     TEXT           NOT NULL PRIMARY KEY,
    "organizationId"       TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "productId"            TEXT           NOT NULL,
    kind                   TEXT           NOT NULL DEFAULT 'high_explosive'
        CHECK (kind IN ('high_explosive', 'blasting_agent', 'detonator', 'booster', 'cord', 'other')),
    -- UN hazard classification (e.g. 1.1D) and number (e.g. UN0241).
    "hazardClass"          TEXT,
    "unNumber"             TEXT,
    -- Net explosive quantity in kg per stock unit (1 for products stocked in kg; 0 for detonators counted in pieces).
    "netExplosiveKg"       NUMERIC(12, 4) NOT NULL DEFAULT 1 CHECK ("netExplosiveKg" >= 0),
    controlled             BOOLEAN        NOT NULL DEFAULT true,
    "isActive"             BOOLEAN        NOT NULL DEFAULT true,
    notes                  TEXT,
    "createdBy"            TEXT,
    "updatedBy"            TEXT,
    "createdAt"            TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"            TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"            TIMESTAMP(3)
);
CREATE UNIQUE INDEX "QuarryExplosive_org_product_key" ON "QuarryExplosive" ("organizationId", "productId")
    WHERE "deletedAt" IS NULL;

-- Drilling & blasting record: pattern, drilling, explosives (from issues), cost pool absorbed per tonne, compliance.
CREATE TABLE "QuarryBlast" (
    id                     TEXT           NOT NULL PRIMARY KEY,
    "organizationId"       TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code                   TEXT           NOT NULL,
    "siteId"               TEXT           NOT NULL REFERENCES "QuarrySite" (id),
    "zoneId"               TEXT REFERENCES "QuarryZone" (id),
    "materialId"           TEXT REFERENCES "QuarryMaterial" (id),
    status                 TEXT           NOT NULL DEFAULT 'planned'
        CHECK (status IN ('planned', 'fired', 'closed', 'cancelled')),
    "plannedAt"            TIMESTAMP(3),
    "firedAt"              TIMESTAMP(3),
    "closedAt"             TIMESTAMP(3),
    -- drilling pattern
    "patternType"          TEXT CHECK ("patternType" IS NULL OR "patternType" IN ('square', 'staggered', 'other')),
    "holeCount"            INTEGER CHECK ("holeCount" IS NULL OR "holeCount" > 0),
    "holeDiameterMm"       NUMERIC(8, 1) CHECK ("holeDiameterMm" IS NULL OR "holeDiameterMm" > 0),
    "averageDepthM"        NUMERIC(8, 2) CHECK ("averageDepthM" IS NULL OR "averageDepthM" > 0),
    "burdenM"              NUMERIC(8, 2) CHECK ("burdenM" IS NULL OR "burdenM" > 0),
    "spacingM"             NUMERIC(8, 2) CHECK ("spacingM" IS NULL OR "spacingM" > 0),
    "subDrillM"            NUMERIC(8, 2) CHECK ("subDrillM" IS NULL OR "subDrillM" >= 0),
    "stemmingM"            NUMERIC(8, 2) CHECK ("stemmingM" IS NULL OR "stemmingM" >= 0),
    "drilledMetres"        NUMERIC(12, 2),
    "drillVehicleId"       TEXT,
    "drillHours"           NUMERIC(10, 2) CHECK ("drillHours" IS NULL OR "drillHours" >= 0),
    "estimatedVolumeM3"    NUMERIC(14, 3),
    "estimatedTonnes"      NUMERIC(14, 3) CHECK ("estimatedTonnes" IS NULL OR "estimatedTonnes" > 0),
    -- cost pool: explosives (net of returns, from the explosive issues) + drilling + other; absorbed by extraction logs
    "explosivesKg"         NUMERIC(14, 3) NOT NULL DEFAULT 0,
    "explosivesCost"       NUMERIC(18, 2) NOT NULL DEFAULT 0,
    "drillingCost"         NUMERIC(18, 2) NOT NULL DEFAULT 0 CHECK ("drillingCost" >= 0),
    "otherCost"            NUMERIC(18, 2) NOT NULL DEFAULT 0 CHECK ("otherCost" >= 0),
    "absorbedCost"         NUMERIC(18, 2) NOT NULL DEFAULT 0,
    -- compliance
    "shotfirerEmployeeId"  TEXT,
    "shotfirerLicenceNo"   TEXT,
    "blastPermitNo"        TEXT,
    "permitExpiresOn"      DATE,
    "exclusionZoneM"       NUMERIC(8, 1) CHECK ("exclusionZoneM" IS NULL OR "exclusionZoneM" > 0),
    "warningSignalAt"      TIMESTAMP(3),
    "allClearAt"           TIMESTAMP(3),
    "misfireCount"         INTEGER        NOT NULL DEFAULT 0 CHECK ("misfireCount" >= 0),
    "misfireNotes"         TEXT,
    "vibrationPpvMmS"      NUMERIC(8, 2) CHECK ("vibrationPpvMmS" IS NULL OR "vibrationPpvMmS" >= 0),
    "airblastDb"           NUMERIC(8, 2) CHECK ("airblastDb" IS NULL OR "airblastDb" >= 0),
    weather                TEXT,
    notes                  TEXT,
    "createdBy"            TEXT,
    "updatedBy"            TEXT,
    "createdAt"            TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"            TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"            TIMESTAMP(3)
);
CREATE UNIQUE INDEX "QuarryBlast_org_code_key" ON "QuarryBlast" ("organizationId", code) WHERE "deletedAt" IS NULL;
CREATE INDEX "QuarryBlast_site_status_idx" ON "QuarryBlast" ("organizationId", "siteId", status);

CREATE TABLE "QuarryExplosiveIssue" (
    id                      TEXT           NOT NULL PRIMARY KEY,
    "organizationId"        TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code                    TEXT           NOT NULL,
    "blastId"               TEXT           NOT NULL REFERENCES "QuarryBlast" (id),
    "magazineId"            TEXT           NOT NULL REFERENCES "QuarryMagazine" (id),
    -- pending_approval → approved | rejected; approved → issued → reconciled (unused returned); pending / approved →
    -- cancelled.
    status                  TEXT           NOT NULL DEFAULT 'pending_approval'
        CHECK (status IN ('pending_approval', 'approved', 'rejected', 'issued', 'reconciled', 'cancelled')),
    purpose                 TEXT,
    "shotfirerEmployeeId"   TEXT,
    "shotfirerLicenceNo"    TEXT,
    "requestedBy"           TEXT,
    "requestedAt"           TIMESTAMP(3),
    "approvalRequestId"     TEXT,
    "decidedBy"             TEXT,
    "decidedAt"             TIMESTAMP(3),
    "issuedBy"              TEXT,
    "issuedAt"              TIMESTAMP(3),
    "issueJournalEntryId"   TEXT,
    "issuedValue"           NUMERIC(18, 2) NOT NULL DEFAULT 0,
    "reconciledBy"          TEXT,
    "reconciledAt"          TIMESTAMP(3),
    "returnJournalEntryId"  TEXT,
    "returnedValue"         NUMERIC(18, 2) NOT NULL DEFAULT 0,
    "cancelReason"          TEXT,
    notes                   TEXT,
    "createdAt"             TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"             TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "QuarryExplosiveIssue_org_code_key" ON "QuarryExplosiveIssue" ("organizationId", code);
CREATE INDEX "QuarryExplosiveIssue_blast_idx" ON "QuarryExplosiveIssue" ("organizationId", "blastId");
CREATE INDEX "QuarryExplosiveIssue_status_idx" ON "QuarryExplosiveIssue" ("organizationId", status);

CREATE TABLE "QuarryExplosiveIssueLine" (
    id                  TEXT           NOT NULL PRIMARY KEY,
    "organizationId"    TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "issueId"           TEXT           NOT NULL REFERENCES "QuarryExplosiveIssue" (id) ON DELETE CASCADE,
    "lineNumber"        INTEGER        NOT NULL,
    "explosiveId"       TEXT           NOT NULL REFERENCES "QuarryExplosive" (id),
    "productId"         TEXT           NOT NULL,
    "requestedQty"      NUMERIC(14, 3) NOT NULL CHECK ("requestedQty" > 0),
    "issuedQty"         NUMERIC(14, 3) NOT NULL DEFAULT 0 CHECK ("issuedQty" >= 0),
    "returnedQty"       NUMERIC(14, 3) NOT NULL DEFAULT 0 CHECK ("returnedQty" >= 0),
    "unitCost"          NUMERIC(18, 6),
    "issuedValue"       NUMERIC(18, 2) NOT NULL DEFAULT 0,
    "returnedValue"     NUMERIC(18, 2) NOT NULL DEFAULT 0,
    "stockLedgerCode"   TEXT,
    "returnLedgerCode"  TEXT,
    CONSTRAINT "QuarryExplosiveIssueLine_return_chk" CHECK ("returnedQty" <= "issuedQty")
);
CREATE INDEX "QuarryExplosiveIssueLine_issue_idx" ON "QuarryExplosiveIssueLine" ("issueId");

-- ---------------------------------------------------------------- haulage (face → ROM stockpile / crusher)

CREATE TABLE "QuarryHaulage" (
    id                    TEXT           NOT NULL PRIMARY KEY,
    "organizationId"      TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code                  TEXT           NOT NULL,
    "siteId"              TEXT           NOT NULL REFERENCES "QuarrySite" (id),
    "fromZoneId"          TEXT REFERENCES "QuarryZone" (id),
    destination           TEXT           NOT NULL DEFAULT 'rom_stockpile'
        CHECK (destination IN ('rom_stockpile', 'crusher', 'waste_dump', 'other')),
    "stockpileId"         TEXT REFERENCES "QuarryStockpile" (id),
    "destinationNote"     TEXT,
    "materialId"          TEXT REFERENCES "QuarryMaterial" (id),
    "hauledOn"            DATE           NOT NULL,
    shift                 TEXT           NOT NULL CHECK (shift IN ('day', 'night', 'morning', 'afternoon')),
    "truckVehicleId"      TEXT,
    "driverEmployeeId"    TEXT,
    -- loads × capacity, or the net weight of a completed weighbridge ticket.
    method                TEXT           NOT NULL CHECK (method IN ('loads', 'weighbridge')),
    "loadCount"           INTEGER CHECK ("loadCount" IS NULL OR "loadCount" > 0),
    "capacityT"           NUMERIC(12, 3) CHECK ("capacityT" IS NULL OR "capacityT" > 0),
    "weighbridgeTicketId" TEXT,
    -- Read-only reference to a fleet 1.3 trip (load) when the haul was dispatched as one.
    "fleetTripId"         TEXT,
    tonnes                NUMERIC(14, 3) NOT NULL CHECK (tonnes > 0),
    notes                 TEXT,
    "createdBy"           TEXT,
    "updatedBy"           TEXT,
    "createdAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"           TIMESTAMP(3)
);
CREATE UNIQUE INDEX "QuarryHaulage_org_code_key" ON "QuarryHaulage" ("organizationId", code);
CREATE UNIQUE INDEX "QuarryHaulage_org_ticket_key" ON "QuarryHaulage" ("organizationId", "weighbridgeTicketId")
    WHERE "weighbridgeTicketId" IS NOT NULL AND "deletedAt" IS NULL;
CREATE INDEX "QuarryHaulage_site_day_idx" ON "QuarryHaulage" ("organizationId", "siteId", "hauledOn");

-- ---------------------------------------------------------------- daily extraction logs

CREATE TABLE "QuarryExtractionLog" (
    id                    TEXT           NOT NULL PRIMARY KEY,
    "organizationId"      TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code                  TEXT           NOT NULL,
    "siteId"              TEXT           NOT NULL REFERENCES "QuarrySite" (id),
    "zoneId"              TEXT REFERENCES "QuarryZone" (id),
    "materialId"          TEXT           NOT NULL REFERENCES "QuarryMaterial" (id),
    "stockpileId"         TEXT           NOT NULL REFERENCES "QuarryStockpile" (id),
    "blastId"             TEXT REFERENCES "QuarryBlast" (id),
    "extractedOn"         DATE           NOT NULL,
    shift                 TEXT           NOT NULL CHECK (shift IN ('day', 'night', 'morning', 'afternoon')),
    "hrShiftId"           TEXT,
    "supervisorEmployeeId" TEXT,
    status                TEXT           NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'posted', 'reversed')),
    "grossTonnes"         NUMERIC(14, 3) NOT NULL DEFAULT 0,
    "lossTonnes"          NUMERIC(14, 3) NOT NULL DEFAULT 0,
    "netTonnes"           NUMERIC(14, 3) NOT NULL DEFAULT 0,
    "otherCost"           NUMERIC(18, 2) NOT NULL DEFAULT 0 CHECK ("otherCost" >= 0),
    "otherCostNote"       TEXT,
    -- cost snapshot written when posted
    "allocationBasis"     TEXT CHECK ("allocationBasis" IS NULL OR "allocationBasis" IN ('net_tonnes', 'gross_tonnes')),
    "fuelCost"            NUMERIC(18, 2) NOT NULL DEFAULT 0,
    "labourCost"          NUMERIC(18, 2) NOT NULL DEFAULT 0,
    "equipmentCost"       NUMERIC(18, 2) NOT NULL DEFAULT 0,
    "blastingCost"        NUMERIC(18, 2) NOT NULL DEFAULT 0,
    "royaltyCost"         NUMERIC(18, 2) NOT NULL DEFAULT 0,
    "totalCost"           NUMERIC(18, 2) NOT NULL DEFAULT 0,
    "capitalisedCost"     NUMERIC(18, 2) NOT NULL DEFAULT 0,
    "lossCost"            NUMERIC(18, 2) NOT NULL DEFAULT 0,
    "unitCost"            NUMERIC(18, 6),
    "stockLedgerCode"     TEXT,
    "journalEntryId"      TEXT,
    "reversalEntryId"     TEXT,
    "postedBy"            TEXT,
    "postedAt"            TIMESTAMP(3),
    "reversedBy"          TEXT,
    "reversedAt"          TIMESTAMP(3),
    "reversalReason"      TEXT,
    notes                 TEXT,
    "createdBy"           TEXT,
    "updatedBy"           TEXT,
    "createdAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"           TIMESTAMP(3)
);
CREATE UNIQUE INDEX "QuarryExtractionLog_org_code_key" ON "QuarryExtractionLog" ("organizationId", code);
CREATE INDEX "QuarryExtractionLog_site_day_idx" ON "QuarryExtractionLog" ("organizationId", "siteId", "extractedOn");
CREATE INDEX "QuarryExtractionLog_status_idx" ON "QuarryExtractionLog" ("organizationId", status);
CREATE INDEX "QuarryExtractionLog_blast_idx" ON "QuarryExtractionLog" ("organizationId", "blastId");

-- Quantity lines: loads × truck capacity, a completed weighbridge ticket, a haulage record, or a survey figure.
-- "active" is cleared when the log is deleted or reversed, which releases its tickets / haulage records.
CREATE TABLE "QuarryExtractionLoad" (
    id                    TEXT           NOT NULL PRIMARY KEY,
    "organizationId"      TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "logId"               TEXT           NOT NULL REFERENCES "QuarryExtractionLog" (id) ON DELETE CASCADE,
    "lineNumber"          INTEGER        NOT NULL,
    method                TEXT           NOT NULL CHECK (method IN ('loads', 'weighbridge', 'haulage', 'survey')),
    "truckVehicleId"      TEXT,
    "loadCount"           INTEGER CHECK ("loadCount" IS NULL OR "loadCount" > 0),
    "capacityT"           NUMERIC(12, 3) CHECK ("capacityT" IS NULL OR "capacityT" > 0),
    "weighbridgeTicketId" TEXT,
    "haulageId"           TEXT REFERENCES "QuarryHaulage" (id),
    "fleetTripId"         TEXT,
    tonnes                NUMERIC(14, 3) NOT NULL CHECK (tonnes > 0),
    active                BOOLEAN        NOT NULL DEFAULT true,
    notes                 TEXT
);
CREATE INDEX "QuarryExtractionLoad_log_idx" ON "QuarryExtractionLoad" ("logId");
CREATE UNIQUE INDEX "QuarryExtractionLoad_ticket_key" ON "QuarryExtractionLoad" ("organizationId", "weighbridgeTicketId")
    WHERE active AND "weighbridgeTicketId" IS NOT NULL;
CREATE UNIQUE INDEX "QuarryExtractionLoad_haulage_key" ON "QuarryExtractionLoad" ("organizationId", "haulageId")
    WHERE active AND "haulageId" IS NOT NULL;

-- Equipment (fleet mobile plant) and labour (hr employees) of the shift; rates resolved and costed when posted.
CREATE TABLE "QuarryExtractionResource" (
    id                   TEXT           NOT NULL PRIMARY KEY,
    "organizationId"     TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "logId"              TEXT           NOT NULL REFERENCES "QuarryExtractionLog" (id) ON DELETE CASCADE,
    "lineNumber"         INTEGER        NOT NULL,
    kind                 TEXT           NOT NULL CHECK (kind IN ('equipment', 'labour')),
    "vehicleId"          TEXT,
    "employeeId"         TEXT,
    role                 TEXT,
    hours                NUMERIC(10, 2) NOT NULL CHECK (hours >= 0),
    "meterStart"         NUMERIC(14, 1),
    "meterEnd"           NUMERIC(14, 1),
    "fuelLitres"         NUMERIC(12, 3) CHECK ("fuelLitres" IS NULL OR "fuelLitres" >= 0),
    -- per-line overrides of the rate tables / settings
    "ratePerHour"        NUMERIC(14, 4) CHECK ("ratePerHour" IS NULL OR "ratePerHour" >= 0),
    "fuelCostPerLitre"   NUMERIC(14, 4) CHECK ("fuelCostPerLitre" IS NULL OR "fuelCostPerLitre" >= 0),
    -- resolved when posted
    "appliedRate"        NUMERIC(14, 4),
    "appliedFuelRate"    NUMERIC(14, 4),
    "hoursCost"          NUMERIC(18, 2),
    "fuelCost"           NUMERIC(18, 2),
    notes                TEXT,
    CONSTRAINT "QuarryExtractionResource_ref_chk" CHECK (
        (kind = 'equipment' AND "vehicleId" IS NOT NULL) OR (kind = 'labour' AND "employeeId" IS NOT NULL))
);
CREATE INDEX "QuarryExtractionResource_log_idx" ON "QuarryExtractionResource" ("logId");
CREATE INDEX "QuarryExtractionResource_vehicle_idx" ON "QuarryExtractionResource" ("organizationId", "vehicleId");

-- The cost build-up of a posted log, one row per component line (reports read it).
CREATE TABLE "QuarryExtractionCost" (
    id                   TEXT           NOT NULL PRIMARY KEY,
    "organizationId"     TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "logId"              TEXT           NOT NULL REFERENCES "QuarryExtractionLog" (id) ON DELETE CASCADE,
    component            TEXT           NOT NULL
        CHECK (component IN ('fuel', 'labour', 'equipment', 'blasting', 'royalty', 'other')),
    description          TEXT           NOT NULL,
    quantity             NUMERIC(18, 4),
    rate                 NUMERIC(18, 6),
    amount               NUMERIC(18, 2) NOT NULL,
    "refType"            TEXT,
    "refId"              TEXT,
    "partnerId"          TEXT
);
CREATE INDEX "QuarryExtractionCost_log_idx" ON "QuarryExtractionCost" ("logId");

-- Losses: overburden, unusable rock, waste, spillage — on a log (reduce its net tonnes) or standalone (stripping).
CREATE TABLE "QuarryLoss" (
    id                 TEXT           NOT NULL PRIMARY KEY,
    "organizationId"   TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "siteId"           TEXT           NOT NULL REFERENCES "QuarrySite" (id),
    "zoneId"           TEXT REFERENCES "QuarryZone" (id),
    "logId"            TEXT REFERENCES "QuarryExtractionLog" (id) ON DELETE CASCADE,
    "materialId"       TEXT REFERENCES "QuarryMaterial" (id),
    "lossOn"           DATE           NOT NULL,
    kind               TEXT           NOT NULL
        CHECK (kind IN ('overburden', 'unusable_rock', 'waste', 'spillage', 'other')),
    tonnes             NUMERIC(14, 3) NOT NULL CHECK (tonnes > 0),
    reason             TEXT,
    "createdBy"        TEXT,
    "createdAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"        TIMESTAMP(3)
);
CREATE INDEX "QuarryLoss_site_day_idx" ON "QuarryLoss" ("organizationId", "siteId", "lossOn");
CREATE INDEX "QuarryLoss_log_idx" ON "QuarryLoss" ("logId");

-- ---------------------------------------------------------------- row-level security

ALTER TABLE "QuarrySettings" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "QuarrySettings" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "QuarrySite" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "QuarrySite" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "QuarryMaterial" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "QuarryMaterial" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "QuarryZone" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "QuarryZone" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "QuarryStockpile" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "QuarryStockpile" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "QuarryEquipmentRate" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "QuarryEquipmentRate" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "QuarryRoyaltyRate" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "QuarryRoyaltyRate" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "QuarryExtractionPlan" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "QuarryExtractionPlan" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "QuarryMagazine" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "QuarryMagazine" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "QuarryExplosive" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "QuarryExplosive" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "QuarryBlast" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "QuarryBlast" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "QuarryExplosiveIssue" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "QuarryExplosiveIssue" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "QuarryExplosiveIssueLine" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "QuarryExplosiveIssueLine" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "QuarryHaulage" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "QuarryHaulage" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "QuarryExtractionLog" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "QuarryExtractionLog" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "QuarryExtractionLoad" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "QuarryExtractionLoad" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "QuarryExtractionResource" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "QuarryExtractionResource" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "QuarryExtractionCost" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "QuarryExtractionCost" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "QuarryLoss" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "QuarryLoss" USING (("organizationId" = current_setting('app.org_id', true)));

-- ---------------------------------------------------------------- permissions (ADR-S012 §6)

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY[
    'quarry:report', 'quarry:settings',
    'quarry_site:read', 'quarry_site:create', 'quarry_site:update', 'quarry_site:delete',
    'quarry_extraction:read', 'quarry_extraction:create', 'quarry_extraction:update', 'quarry_extraction:post',
    'quarry_extraction:plan', 'quarry_extraction:haul',
    'quarry_blasting:read', 'quarry_blasting:create', 'quarry_blasting:update', 'quarry_blasting:request_explosive',
    'quarry_blasting:approve_explosive', 'quarry_blasting:issue_explosive', 'quarry_blasting:magazine']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY[
        'quarry:report', 'quarry:settings',
        'quarry_site:read', 'quarry_site:create', 'quarry_site:update', 'quarry_site:delete',
        'quarry_extraction:read', 'quarry_extraction:create', 'quarry_extraction:update', 'quarry_extraction:post',
        'quarry_extraction:plan', 'quarry_extraction:haul',
        'quarry_blasting:read', 'quarry_blasting:create', 'quarry_blasting:update',
        'quarry_blasting:request_explosive', 'quarry_blasting:approve_explosive', 'quarry_blasting:issue_explosive',
        'quarry_blasting:magazine'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
