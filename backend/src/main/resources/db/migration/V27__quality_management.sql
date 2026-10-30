-- V27 (placeholder number, renumbered at merge): quality management (ADR-S013, construction-materials Phase 2.6).
--
-- New tables only; no existing table is changed. Cross-module references (product, variant, location, partner,
-- work centre, user, journal entry, GRN / production order / trip ids) are plain text ids like the rest of the schema:
-- quality validates them in its services and never writes those tables (stock through StockService, GL through
-- PostingService).

-- ---------------------------------------------------------------- specifications (versioned)

CREATE TABLE "QualitySpecification" (
    id                  TEXT         NOT NULL PRIMARY KEY,
    "organizationId"    TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code                TEXT         NOT NULL,
    version             INTEGER      NOT NULL DEFAULT 1 CHECK (version >= 1),
    name                TEXT         NOT NULL,
    -- Optional: a specification may describe a grade used by several products (plans link product and spec).
    "productId"         TEXT,
    grade               TEXT,
    -- draft (editable) → active (one per code) → superseded (by a newer active version) | archived.
    status              TEXT         NOT NULL DEFAULT 'draft'
        CHECK (status IN ('draft', 'active', 'superseded', 'archived')),
    standard            TEXT,
    "effectiveFrom"     DATE,
    notes               TEXT,
    "revisedFromId"     TEXT REFERENCES "QualitySpecification" (id),
    "activatedAt"       TIMESTAMP(3),
    "activatedBy"       TEXT,
    "createdBy"         TEXT,
    "updatedBy"         TEXT,
    "createdAt"         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"         TIMESTAMP(3)
);
CREATE UNIQUE INDEX "QualitySpecification_org_code_version_key" ON "QualitySpecification" ("organizationId", code, version);
CREATE UNIQUE INDEX "QualitySpecification_one_active_key" ON "QualitySpecification" ("organizationId", code)
    WHERE status = 'active' AND "deletedAt" IS NULL;
CREATE UNIQUE INDEX "QualitySpecification_one_draft_key" ON "QualitySpecification" ("organizationId", code)
    WHERE status = 'draft' AND "deletedAt" IS NULL;
CREATE INDEX "QualitySpecification_org_product_idx" ON "QualitySpecification" ("organizationId", "productId");

CREATE TABLE "QualitySpecParameter" (
    id                  TEXT           NOT NULL PRIMARY KEY,
    "organizationId"    TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "specificationId"   TEXT           NOT NULL REFERENCES "QualitySpecification" (id) ON DELETE CASCADE,
    code                TEXT           NOT NULL,
    name                TEXT           NOT NULL,
    -- numeric: min / max / target; sieve: grading envelope; boolean: expectedText 'true' | 'false'; text: info or
    -- must equal expectedText.
    kind                TEXT           NOT NULL DEFAULT 'numeric' CHECK (kind IN ('numeric', 'sieve', 'boolean', 'text')),
    unit                TEXT,
    "testMethod"        TEXT,
    "minValue"          NUMERIC(18, 6),
    "maxValue"          NUMERIC(18, 6),
    "targetValue"       NUMERIC(18, 6),
    decimals            INTEGER        NOT NULL DEFAULT 2 CHECK (decimals BETWEEN 0 AND 6),
    "expectedText"      TEXT,
    -- [{sieveMm, minPassingPct, maxPassingPct}] for kind = sieve.
    "sieveEnvelope"     JSONB          NOT NULL DEFAULT '[]'::jsonb,
    -- false = an out-of-spec value is a deviation (warning), not a failure.
    "isCritical"        BOOLEAN        NOT NULL DEFAULT true,
    "sortOrder"         INTEGER        NOT NULL DEFAULT 0,
    "createdAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ("minValue" IS NULL OR "maxValue" IS NULL OR "minValue" <= "maxValue")
);
CREATE UNIQUE INDEX "QualitySpecParameter_spec_code_key" ON "QualitySpecParameter" ("specificationId", code);
CREATE INDEX "QualitySpecParameter_org_idx" ON "QualitySpecParameter" ("organizationId");

-- ---------------------------------------------------------------- inspection plans

CREATE TABLE "QualityInspectionPlan" (
    id                    TEXT           NOT NULL PRIMARY KEY,
    "organizationId"      TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    code                  TEXT           NOT NULL,
    name                  TEXT           NOT NULL,
    stage                 TEXT           NOT NULL CHECK (stage IN ('incoming', 'in_process', 'final')),
    "productId"           TEXT           NOT NULL,
    -- incoming: a supplier-specific plan wins over the generic one.
    "supplierPartnerId"   TEXT,
    -- in_process: the stage / routing-operation code of 2.1 (null = any stage).
    "stageKey"            TEXT,
    "specificationId"     TEXT           NOT NULL REFERENCES "QualitySpecification" (id),
    "samplingMethod"      TEXT           NOT NULL DEFAULT 'fixed'
        CHECK ("samplingMethod" IN ('fixed', 'percent', 'sqrt', 'per_quantity')),
    "sampleSize"          INTEGER        CHECK ("sampleSize" IS NULL OR "sampleSize" >= 1),
    "samplePct"           NUMERIC(9, 4)  CHECK ("samplePct" IS NULL OR ("samplePct" > 0 AND "samplePct" <= 100)),
    "perQuantity"         NUMERIC(20, 6) CHECK ("perQuantity" IS NULL OR "perQuantity" > 0),
    "minSamples"          INTEGER        NOT NULL DEFAULT 1 CHECK ("minSamples" >= 1),
    "maxSamples"          INTEGER        CHECK ("maxSamples" IS NULL OR "maxSamples" >= 1),
    "holdPolicy"          TEXT           NOT NULL DEFAULT 'none' CHECK ("holdPolicy" IN ('quarantine', 'hold', 'none')),
    "independentRelease"  BOOLEAN        NOT NULL DEFAULT true,
    "autoNcr"             BOOLEAN        NOT NULL DEFAULT true,
    "isActive"            BOOLEAN        NOT NULL DEFAULT true,
    notes                 TEXT,
    "createdBy"           TEXT,
    "updatedBy"           TEXT,
    "createdAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"           TIMESTAMP(3)
);
CREATE UNIQUE INDEX "QualityInspectionPlan_org_code_key" ON "QualityInspectionPlan" ("organizationId", code)
    WHERE "deletedAt" IS NULL;
CREATE INDEX "QualityInspectionPlan_lookup_idx" ON "QualityInspectionPlan" ("organizationId", stage, "productId");

-- ---------------------------------------------------------------- inspection lots

CREATE TABLE "QualityInspectionLot" (
    id                    TEXT           NOT NULL PRIMARY KEY,
    "organizationId"      TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "lotNumber"           TEXT           NOT NULL,
    stage                 TEXT           NOT NULL CHECK (stage IN ('incoming', 'in_process', 'final')),
    "planId"              TEXT REFERENCES "QualityInspectionPlan" (id),
    "specificationId"     TEXT REFERENCES "QualitySpecification" (id),
    "productId"           TEXT           NOT NULL,
    "variantId"           TEXT,
    "batchNumber"         TEXT,
    quantity              NUMERIC(20, 6) NOT NULL CHECK (quantity > 0),
    "uomId"               TEXT,
    -- Where the stock is when the lot opens; quarantineLocationId while it sits in QC-QUARANTINE.
    "locationId"          TEXT,
    "quarantineLocationId" TEXT,
    -- quality: decisions move stock; source: the source module owns the stock (WIP, manufacturing qc_hold).
    "stockControl"        TEXT           NOT NULL DEFAULT 'quality' CHECK ("stockControl" IN ('quality', 'source')),
    "sourceType"          TEXT           NOT NULL DEFAULT 'manual',
    "sourceId"            TEXT,
    "sourceLineId"        TEXT,
    "sourceRef"           TEXT,
    "stageKey"            TEXT,
    "supplierPartnerId"   TEXT,
    "workCenterId"        TEXT,
    "sampleSize"          INTEGER        NOT NULL DEFAULT 1 CHECK ("sampleSize" >= 1),
    -- open → hold | quarantined → closed (open quantity 0); cancelled.
    status                TEXT           NOT NULL DEFAULT 'open'
        CHECK (status IN ('open', 'hold', 'quarantined', 'closed', 'cancelled')),
    result                TEXT           NOT NULL DEFAULT 'pending' CHECK (result IN ('pending', 'pass', 'fail')),
    -- {parameters: [...], failures: n, deviations: n} of the last evaluation.
    evaluation            JSONB          NOT NULL DEFAULT '{}'::jsonb,
    "releasedQty"         NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "rejectedQty"         NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "downgradedQty"       NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "independentRelease"  BOOLEAN        NOT NULL DEFAULT true,
    "autoNcr"             BOOLEAN        NOT NULL DEFAULT true,
    "inspectedBy"         TEXT,
    "inspectedAt"         TIMESTAMP(3),
    "closedAt"            TIMESTAMP(3),
    "cancelReason"        TEXT,
    notes                 TEXT,
    version               INTEGER        NOT NULL DEFAULT 0,
    "createdBy"           TEXT,
    "updatedBy"           TEXT,
    "createdAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ("releasedQty" + "rejectedQty" + "downgradedQty" <= quantity)
);
CREATE UNIQUE INDEX "QualityInspectionLot_org_number_key" ON "QualityInspectionLot" ("organizationId", "lotNumber");
-- One live lot per source line and stage (idempotent hooks: GRN subscriber, 2.1 stage gates).
CREATE UNIQUE INDEX "QualityInspectionLot_source_key" ON "QualityInspectionLot"
    ("organizationId", "sourceType", "sourceId", COALESCE("sourceLineId", ''), COALESCE("stageKey", ''))
    WHERE "sourceId" IS NOT NULL AND status <> 'cancelled';
CREATE INDEX "QualityInspectionLot_org_status_idx" ON "QualityInspectionLot" ("organizationId", status, stage);
CREATE INDEX "QualityInspectionLot_org_product_batch_idx" ON "QualityInspectionLot" ("organizationId", "productId", "batchNumber");
CREATE INDEX "QualityInspectionLot_org_supplier_idx" ON "QualityInspectionLot" ("organizationId", "supplierPartnerId");
CREATE INDEX "QualityInspectionLot_org_created_idx" ON "QualityInspectionLot" ("organizationId", "createdAt");

CREATE TABLE "QualityLotResult" (
    id                  TEXT           NOT NULL PRIMARY KEY,
    "organizationId"    TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "lotId"             TEXT           NOT NULL REFERENCES "QualityInspectionLot" (id) ON DELETE CASCADE,
    "parameterId"       TEXT           NOT NULL REFERENCES "QualitySpecParameter" (id),
    "sampleNo"          INTEGER        NOT NULL DEFAULT 1 CHECK ("sampleNo" >= 1),
    "numericValue"      NUMERIC(18, 6),
    "textValue"         TEXT,
    -- [{sieveMm, passingPct}] for sieve parameters.
    "sieveResults"      JSONB          NOT NULL DEFAULT '[]'::jsonb,
    passed              BOOLEAN,
    source              TEXT           NOT NULL DEFAULT 'manual' CHECK (source IN ('manual', 'csv', 'rest', 'simulator')),
    "labReference"      TEXT,
    "testedAt"          TIMESTAMP(3),
    "recordedBy"        TEXT,
    "createdAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "QualityLotResult_lot_param_sample_key" ON "QualityLotResult" ("lotId", "parameterId", "sampleNo");
CREATE INDEX "QualityLotResult_org_idx" ON "QualityLotResult" ("organizationId");

CREATE TABLE "QualityLotDecision" (
    id                    TEXT           NOT NULL PRIMARY KEY,
    "organizationId"      TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "lotId"               TEXT           NOT NULL REFERENCES "QualityInspectionLot" (id) ON DELETE CASCADE,
    decision              TEXT           NOT NULL
        CHECK (decision IN ('hold', 'quarantine', 'release', 'reject', 'downgrade', 'cancel')),
    quantity              NUMERIC(20, 6),
    "fromLocationId"      TEXT,
    "toLocationId"        TEXT,
    -- reject: scrap (waste issue) | return_to_supplier.
    disposition           TEXT CHECK (disposition IS NULL OR disposition IN ('scrap', 'return_to_supplier')),
    "downgradeProductId"  TEXT,
    "downgradeBatchNumber" TEXT,
    "downgradeQty"        NUMERIC(20, 6),
    "unitCost"            NUMERIC(20, 6),
    "stockValue"          NUMERIC(20, 6),
    "writeDownValue"      NUMERIC(20, 6),
    "ledgerCodes"         TEXT,
    "journalEntryId"      TEXT,
    reason                TEXT,
    concession            BOOLEAN        NOT NULL DEFAULT false,
    "decidedBy"           TEXT,
    "decidedAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "createdAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "QualityLotDecision_lot_idx" ON "QualityLotDecision" ("lotId");
CREATE INDEX "QualityLotDecision_org_downgrade_idx" ON "QualityLotDecision" ("organizationId", "downgradeProductId",
    "downgradeBatchNumber") WHERE decision = 'downgrade';

-- ---------------------------------------------------------------- NCR / CAPA

CREATE TABLE "QualityNcr" (
    id                    TEXT           NOT NULL PRIMARY KEY,
    "organizationId"      TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "ncrNumber"           TEXT           NOT NULL,
    title                 TEXT           NOT NULL,
    description           TEXT,
    source                TEXT           NOT NULL DEFAULT 'internal'
        CHECK (source IN ('incoming', 'in_process', 'final', 'customer', 'supplier', 'internal')),
    severity              TEXT           NOT NULL DEFAULT 'minor' CHECK (severity IN ('minor', 'major', 'critical')),
    -- open → investigating → action → closed; open | investigating | action → cancelled.
    status                TEXT           NOT NULL DEFAULT 'open'
        CHECK (status IN ('open', 'investigating', 'action', 'closed', 'cancelled')),
    "lotId"               TEXT REFERENCES "QualityInspectionLot" (id),
    "productId"           TEXT,
    "batchNumber"         TEXT,
    "supplierPartnerId"   TEXT,
    "customerPartnerId"   TEXT,
    "workCenterId"        TEXT,
    "quantityAffected"    NUMERIC(20, 6) CHECK ("quantityAffected" IS NULL OR "quantityAffected" >= 0),
    disposition           TEXT,
    "rootCause"           TEXT,
    "ownerUserId"         TEXT,
    "reportedBy"          TEXT,
    "closedAt"            TIMESTAMP(3),
    "closedBy"            TEXT,
    "closureNotes"        TEXT,
    "createdBy"           TEXT,
    "updatedBy"           TEXT,
    "createdAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"           TIMESTAMP(3)
);
CREATE UNIQUE INDEX "QualityNcr_org_number_key" ON "QualityNcr" ("organizationId", "ncrNumber");
CREATE INDEX "QualityNcr_org_status_idx" ON "QualityNcr" ("organizationId", status);
CREATE INDEX "QualityNcr_lot_idx" ON "QualityNcr" ("lotId");

CREATE TABLE "QualityCapa" (
    id                    TEXT           NOT NULL PRIMARY KEY,
    "organizationId"      TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "capaNumber"          TEXT           NOT NULL,
    "ncrId"               TEXT REFERENCES "QualityNcr" (id),
    title                 TEXT           NOT NULL,
    "rootCause"           TEXT,
    -- five_why, fishbone, fault_tree, other.
    "rootCauseMethod"     TEXT,
    "ownerUserId"         TEXT,
    "dueDate"             DATE,
    -- open → in_progress → verification → closed; open | in_progress | verification → cancelled.
    status                TEXT           NOT NULL DEFAULT 'open'
        CHECK (status IN ('open', 'in_progress', 'verification', 'closed', 'cancelled')),
    "effectivenessNotes"  TEXT,
    "verifiedBy"          TEXT,
    "verifiedAt"          TIMESTAMP(3),
    "closedAt"            TIMESTAMP(3),
    "closedBy"            TEXT,
    "createdBy"           TEXT,
    "updatedBy"           TEXT,
    "createdAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"           TIMESTAMP(3)
);
CREATE UNIQUE INDEX "QualityCapa_org_number_key" ON "QualityCapa" ("organizationId", "capaNumber");
CREATE INDEX "QualityCapa_org_status_idx" ON "QualityCapa" ("organizationId", status, "dueDate");
CREATE INDEX "QualityCapa_ncr_idx" ON "QualityCapa" ("ncrId");

CREATE TABLE "QualityCapaAction" (
    id                    TEXT           NOT NULL PRIMARY KEY,
    "organizationId"      TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "capaId"              TEXT           NOT NULL REFERENCES "QualityCapa" (id) ON DELETE CASCADE,
    kind                  TEXT           NOT NULL DEFAULT 'corrective' CHECK (kind IN ('corrective', 'preventive')),
    description           TEXT           NOT NULL,
    "ownerUserId"         TEXT,
    "dueDate"             DATE,
    status                TEXT           NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'done', 'cancelled')),
    "completedAt"         TIMESTAMP(3),
    "completedBy"         TEXT,
    notes                 TEXT,
    "createdAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "QualityCapaAction_capa_idx" ON "QualityCapaAction" ("capaId");
CREATE INDEX "QualityCapaAction_org_due_idx" ON "QualityCapaAction" ("organizationId", status, "dueDate");

-- ---------------------------------------------------------------- lab imports (LabResultAdapter SPI)

CREATE TABLE "QualityLabImport" (
    id                  TEXT         NOT NULL PRIMARY KEY,
    "organizationId"    TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    adapter             TEXT         NOT NULL,
    filename            TEXT,
    status              TEXT         NOT NULL CHECK (status IN ('completed', 'partial', 'failed')),
    "rowsTotal"         INTEGER      NOT NULL DEFAULT 0,
    "rowsImported"      INTEGER      NOT NULL DEFAULT 0,
    "rowsRejected"      INTEGER      NOT NULL DEFAULT 0,
    -- [{row, lotNumber, parameterCode, message}]
    errors              JSONB        NOT NULL DEFAULT '[]'::jsonb,
    "lotIds"            JSONB        NOT NULL DEFAULT '[]'::jsonb,
    "importedBy"        TEXT,
    "createdAt"         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "QualityLabImport_org_created_idx" ON "QualityLabImport" ("organizationId", "createdAt");

-- ---------------------------------------------------------------- row level security

ALTER TABLE "QualitySpecification" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "QualitySpecification" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "QualitySpecParameter" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "QualitySpecParameter" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "QualityInspectionPlan" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "QualityInspectionPlan" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "QualityInspectionLot" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "QualityInspectionLot" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "QualityLotResult" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "QualityLotResult" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "QualityLotDecision" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "QualityLotDecision" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "QualityNcr" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "QualityNcr" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "QualityCapa" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "QualityCapa" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "QualityCapaAction" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "QualityCapaAction" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "QualityLabImport" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "QualityLabImport" USING (("organizationId" = current_setting('app.org_id', true)));

-- ---------------------------------------------------------------- permissions (ADR-S013 §5)

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY[
    'quality:read', 'quality:configure', 'quality:inspect', 'quality:hold', 'quality:release', 'quality:dispose',
    'quality:ncr', 'quality:capa']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY[
        'quality:read', 'quality:configure', 'quality:inspect', 'quality:hold', 'quality:release', 'quality:dispose',
        'quality:ncr', 'quality:capa'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
