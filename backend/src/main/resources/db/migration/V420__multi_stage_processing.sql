-- Multi-stage processing (ADR-S060): templates, jobs, genealogy, routes. Additive; numbered above the unmerged
-- sales branch (V401-V409).

CREATE TABLE "ProcessingTemplate" (
    id text PRIMARY KEY,
    "organizationId" text NOT NULL REFERENCES "Organization"(id),
    "code" text NOT NULL,
    "version" integer NOT NULL DEFAULT 1,
    "name" text NOT NULL,
    "description" text,
    "status" text NOT NULL DEFAULT 'draft',
    "workCenterId" text,
    "allocationMethod" text NOT NULL DEFAULT 'market_value',
    "yieldBasisUnit" text NOT NULL DEFAULT 'unit',
    "expectedYieldPct" numeric(7,4) NOT NULL DEFAULT 100,
    "qcGate" boolean NOT NULL DEFAULT false,
    "qcCheckpointCode" text,
    "defaultInputLocationId" text,
    "defaultOutputLocationId" text,
    "costRates" jsonb,
    "bomId" text,
    "routingId" text,
    "activatedAt" timestamp(3),
    "activatedBy" text,
    "createdBy" text,
    "updatedBy" text,
    "createdAt" timestamp(3) NOT NULL DEFAULT now(),
    "updatedAt" timestamp(3) NOT NULL DEFAULT now(),
    "deletedAt" timestamp(3)
);
ALTER TABLE "ProcessingTemplate" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProcessingTemplate" USING (("organizationId" = current_setting('app.org_id', true)));

CREATE TABLE "ProcessingTemplateInput" (
    id text PRIMARY KEY,
    "organizationId" text NOT NULL REFERENCES "Organization"(id),
    "templateId" text NOT NULL,
    "sequence" integer NOT NULL DEFAULT 0,
    "productId" text NOT NULL,
    "role" text NOT NULL DEFAULT 'traceable',
    "genealogyBasis" text NOT NULL DEFAULT 'mass',
    "fixedPct" numeric(7,4),
    "basisFactor" numeric(20,6),
    "createdAt" timestamp(3) NOT NULL DEFAULT now()
);
ALTER TABLE "ProcessingTemplateInput" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProcessingTemplateInput" USING (("organizationId" = current_setting('app.org_id', true)));

CREATE TABLE "ProcessingTemplateOutput" (
    id text PRIMARY KEY,
    "organizationId" text NOT NULL REFERENCES "Organization"(id),
    "templateId" text NOT NULL,
    "sequence" integer NOT NULL DEFAULT 0,
    "productId" text NOT NULL,
    "role" text NOT NULL DEFAULT 'main',
    "expectedPct" numeric(7,4) NOT NULL DEFAULT 0,
    "valuation" text NOT NULL DEFAULT 'allocated',
    "nrvUnitValue" numeric(20,6),
    "marketValue" numeric(20,6),
    "basisFactor" numeric(20,6),
    "requiredMeasures" jsonb,
    "stocked" boolean NOT NULL DEFAULT true,
    "locationId" text,
    "createdAt" timestamp(3) NOT NULL DEFAULT now()
);
ALTER TABLE "ProcessingTemplateOutput" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProcessingTemplateOutput" USING (("organizationId" = current_setting('app.org_id', true)));

CREATE TABLE "ProcessingJob" (
    id text PRIMARY KEY,
    "organizationId" text NOT NULL REFERENCES "Organization"(id),
    "code" text NOT NULL,
    "templateId" text NOT NULL,
    "templateVersion" integer NOT NULL,
    "jobDate" date NOT NULL,
    "locationId" text NOT NULL,
    "outputLocationId" text NOT NULL,
    "orderId" text,
    "stageId" text,
    "reprocessOfJobId" text,
    "snapshot" jsonb,
    "overYieldReason" text,
    "notes" text,
    "cancelledAt" timestamp(3),
    "cancelledBy" text,
    "cancelReason" text,
    "createdBy" text,
    "updatedBy" text,
    "createdAt" timestamp(3) NOT NULL DEFAULT now(),
    "updatedAt" timestamp(3) NOT NULL DEFAULT now(),
    "deletedAt" timestamp(3)
);
ALTER TABLE "ProcessingJob" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProcessingJob" USING (("organizationId" = current_setting('app.org_id', true)));

CREATE TABLE "ProcessingJobInput" (
    id text PRIMARY KEY,
    "organizationId" text NOT NULL REFERENCES "Organization"(id),
    "jobId" text NOT NULL,
    "templateInputId" text,
    "productId" text NOT NULL,
    "role" text NOT NULL DEFAULT 'traceable',
    "genealogyBasis" text NOT NULL DEFAULT 'mass',
    "fixedPct" numeric(7,4),
    "batchNumber" text,
    "plannedQty" numeric(20,6) NOT NULL,
    "issuedQty" numeric(20,6) NOT NULL DEFAULT 0,
    "issuedCost" numeric(20,6) NOT NULL DEFAULT 0,
    "basisQty" numeric(20,6) NOT NULL DEFAULT 0,
    "basisSource" text NOT NULL DEFAULT 'template_factor',
    "manualSharePct" numeric(7,4),
    "materialId" text,
    "createdAt" timestamp(3) NOT NULL DEFAULT now()
);
ALTER TABLE "ProcessingJobInput" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProcessingJobInput" USING (("organizationId" = current_setting('app.org_id', true)));

CREATE TABLE "ProcessingJobOutput" (
    id text PRIMARY KEY,
    "organizationId" text NOT NULL REFERENCES "Organization"(id),
    "jobId" text NOT NULL,
    "templateOutputId" text,
    "productId" text NOT NULL,
    "role" text NOT NULL,
    "batchNumber" text,
    "outputLineId" text,
    "quantity" numeric(20,6) NOT NULL DEFAULT 0,
    "value" numeric(20,6) NOT NULL DEFAULT 0,
    "unitCost" numeric(20,6),
    "grade" text,
    "basisQty" numeric(20,6) NOT NULL DEFAULT 0,
    "basisSource" text NOT NULL DEFAULT 'template_factor',
    "basisFactor" numeric(20,6),
    "marketValue" numeric(20,6),
    "weight" numeric(20,6),
    "length" numeric(20,6),
    "width" numeric(20,6),
    "thickness" numeric(20,6),
    "area" numeric(20,6),
    "volume" numeric(20,6),
    "density" numeric(20,6),
    "createdAt" timestamp(3) NOT NULL DEFAULT now(),
    "updatedAt" timestamp(3) NOT NULL DEFAULT now()
);
ALTER TABLE "ProcessingJobOutput" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProcessingJobOutput" USING (("organizationId" = current_setting('app.org_id', true)));

CREATE TABLE "ProcessingJobCost" (
    id text PRIMARY KEY,
    "organizationId" text NOT NULL REFERENCES "Organization"(id),
    "jobId" text NOT NULL,
    "category" text NOT NULL,
    "description" text,
    "amount" numeric(20,6) NOT NULL,
    "currency" text,
    "exchangeRate" numeric(20,6),
    "baseAmount" numeric(20,6) NOT NULL,
    "costDate" date NOT NULL,
    "sourceType" text,
    "sourceId" text,
    "allocationMethod" text NOT NULL DEFAULT 'joint',
    "posted" boolean NOT NULL DEFAULT false,
    "postingId" text,
    "creditAccountId" text,
    "createdBy" text,
    "reversedAt" timestamp(3),
    "createdAt" timestamp(3) NOT NULL DEFAULT now()
);
ALTER TABLE "ProcessingJobCost" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProcessingJobCost" USING (("organizationId" = current_setting('app.org_id', true)));

CREATE TABLE "ProcessingJobLoss" (
    id text PRIMARY KEY,
    "organizationId" text NOT NULL REFERENCES "Organization"(id),
    "jobId" text NOT NULL,
    "kind" text NOT NULL,
    "basisQty" numeric(20,6) NOT NULL,
    "value" numeric(20,6) NOT NULL DEFAULT 0,
    "reason" text,
    "approvedBy" text,
    "postingId" text,
    "reversedAt" timestamp(3),
    "createdAt" timestamp(3) NOT NULL DEFAULT now()
);
ALTER TABLE "ProcessingJobLoss" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProcessingJobLoss" USING (("organizationId" = current_setting('app.org_id', true)));

CREATE TABLE "LotGenealogyLink" (
    id text PRIMARY KEY,
    "organizationId" text NOT NULL REFERENCES "Organization"(id),
    "jobId" text NOT NULL,
    "postingId" text,
    "sourceProductId" text NOT NULL,
    "sourceBatchNumber" text,
    "sourceQty" numeric(20,6) NOT NULL,
    "sourceUomId" text,
    "outputProductId" text NOT NULL,
    "outputBatchNumber" text,
    "attributedQty" numeric(20,6) NOT NULL,
    "outputUomId" text,
    "allocationMethod" text NOT NULL,
    "sharePct" numeric(9,4) NOT NULL,
    "attributedCost" numeric(20,6) NOT NULL DEFAULT 0,
    "reversedAt" timestamp(3),
    "createdAt" timestamp(3) NOT NULL DEFAULT now()
);
ALTER TABLE "LotGenealogyLink" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "LotGenealogyLink" USING (("organizationId" = current_setting('app.org_id', true)));

CREATE TABLE "ProcessingRoute" (
    id text PRIMARY KEY,
    "organizationId" text NOT NULL REFERENCES "Organization"(id),
    "code" text NOT NULL,
    "name" text NOT NULL,
    "description" text,
    "isActive" boolean NOT NULL DEFAULT true,
    "createdBy" text,
    "createdAt" timestamp(3) NOT NULL DEFAULT now(),
    "updatedAt" timestamp(3) NOT NULL DEFAULT now(),
    "deletedAt" timestamp(3)
);
ALTER TABLE "ProcessingRoute" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProcessingRoute" USING (("organizationId" = current_setting('app.org_id', true)));

CREATE TABLE "ProcessingRouteStep" (
    id text PRIMARY KEY,
    "organizationId" text NOT NULL REFERENCES "Organization"(id),
    "routeId" text NOT NULL,
    "sequence" integer NOT NULL,
    "templateCode" text NOT NULL,
    "notes" text,
    "createdAt" timestamp(3) NOT NULL DEFAULT now()
);
ALTER TABLE "ProcessingRouteStep" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProcessingRouteStep" USING (("organizationId" = current_setting('app.org_id', true)));

CREATE UNIQUE INDEX "ProcessingTemplate_code_version_key" ON "ProcessingTemplate" ("organizationId", code, version);
CREATE INDEX "ProcessingTemplateInput_template_idx" ON "ProcessingTemplateInput" ("templateId");
CREATE INDEX "ProcessingTemplateOutput_template_idx" ON "ProcessingTemplateOutput" ("templateId");
CREATE UNIQUE INDEX "ProcessingJob_code_key" ON "ProcessingJob" ("organizationId", code);
CREATE INDEX "ProcessingJob_order_idx" ON "ProcessingJob" ("orderId");
CREATE INDEX "ProcessingJobInput_job_idx" ON "ProcessingJobInput" ("jobId");
CREATE INDEX "ProcessingJobOutput_job_idx" ON "ProcessingJobOutput" ("jobId");
-- A lot number belongs to one job output per product (ADR-S060).
CREATE UNIQUE INDEX "ProcessingJobOutput_lot_key" ON "ProcessingJobOutput" ("organizationId", "productId", "batchNumber")
    WHERE "batchNumber" IS NOT NULL;
CREATE INDEX "ProcessingJobCost_job_idx" ON "ProcessingJobCost" ("jobId");
-- The same source record cannot be captured as job cost twice (ADR-S060).
CREATE UNIQUE INDEX "ProcessingJobCost_source_key" ON "ProcessingJobCost" ("organizationId", "sourceType", "sourceId")
    WHERE "sourceType" IS NOT NULL AND "reversedAt" IS NULL;
CREATE INDEX "ProcessingJobLoss_job_idx" ON "ProcessingJobLoss" ("jobId");
CREATE INDEX "LotGenealogyLink_source_idx" ON "LotGenealogyLink" ("organizationId", "sourceProductId", "sourceBatchNumber");
CREATE INDEX "LotGenealogyLink_output_idx" ON "LotGenealogyLink" ("organizationId", "outputProductId", "outputBatchNumber");
CREATE UNIQUE INDEX "ProcessingRoute_code_key" ON "ProcessingRoute" ("organizationId", code) WHERE "deletedAt" IS NULL;
CREATE INDEX "ProcessingRouteStep_route_idx" ON "ProcessingRouteStep" ("routeId");

-- Sell at any stage: a product can be switched off for sale without touching its stock.
ALTER TABLE "Product" ADD COLUMN "canBeSold" boolean NOT NULL DEFAULT true;

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['processing:read', 'processing:create', 'processing:post', 'processing:reverse',
                  'processing:approve_loss']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['processing:read', 'processing:create',
        'processing:post', 'processing:reverse', 'processing:approve_loss'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
