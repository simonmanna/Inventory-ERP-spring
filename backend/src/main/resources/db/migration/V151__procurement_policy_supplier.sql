-- V27: procurement policy layer and supplier lifecycle (ADR-PROCUREMENT-001, wave 2 / roadmap P1).
-- Additive extension beyond the NestJS reference: new tables only. Without an active policy every purchasing rule keeps
-- today's behaviour; a partner without a SupplierProfile behaves exactly as before.

-- ── Configurable, effective-dated, approved purchasing policies ───────────────────────────────────────────────
CREATE TABLE "ProcurementPolicy" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    key              TEXT         NOT NULL,
    "scopeType"      TEXT         NOT NULL DEFAULT 'org'
        CHECK ("scopeType" IN ('org', 'branch', 'category', 'department', 'cost_center', 'project')),
    "scopeId"        TEXT,
    version          INTEGER      NOT NULL DEFAULT 1,
    status           TEXT         NOT NULL DEFAULT 'draft'
        CHECK (status IN ('draft', 'approved', 'active', 'superseded', 'cancelled')),
    "effectiveFrom"  DATE         NOT NULL,
    value            JSONB        NOT NULL,
    notes            TEXT,
    "createdBy"      TEXT,
    "approvedBy"     TEXT,
    "approvedAt"     TIMESTAMP(3),
    "activatedAt"    TIMESTAMP(3),
    "supersededAt"   TIMESTAMP(3),
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (("scopeType" = 'org') = ("scopeId" IS NULL))
);
CREATE UNIQUE INDEX "ProcurementPolicy_active_key" ON "ProcurementPolicy" ("organizationId", key, "scopeType",
    COALESCE("scopeId", '')) WHERE status = 'active';
CREATE INDEX "ProcurementPolicy_org_key_idx" ON "ProcurementPolicy" ("organizationId", key, status);

-- ── Supplier lifecycle: one profile per partner (core Partner stays the master record) ──────────────────────────
CREATE TABLE "SupplierProfile" (
    id                   TEXT         NOT NULL PRIMARY KEY,
    "organizationId"     TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "partnerId"          TEXT         NOT NULL REFERENCES "Partner" (id) ON DELETE CASCADE,
    "lifecycleStatus"    TEXT         NOT NULL DEFAULT 'prospect'
        CHECK ("lifecycleStatus" IN ('prospect', 'provisional', 'approved', 'suspended', 'blacklisted')),
    "riskClass"          TEXT         NOT NULL DEFAULT 'medium' CHECK ("riskClass" IN ('low', 'medium', 'high')),
    categories           TEXT[]       NOT NULL DEFAULT '{}',
    "currencyCode"       TEXT,
    "leadTimeDays"       INTEGER      CHECK ("leadTimeDays" IS NULL OR "leadTimeDays" >= 0),
    "registrationNumber" TEXT,
    "tradingName"        TEXT,
    "statusReason"       TEXT,
    notes                TEXT,
    "approvedBy"         TEXT,
    "approvedAt"         TIMESTAMP(3),
    "createdBy"          TEXT,
    "updatedBy"          TEXT,
    version              INTEGER      NOT NULL DEFAULT 1,
    "createdAt"          TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"          TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "SupplierProfile_partner_key" ON "SupplierProfile" ("organizationId", "partnerId");
CREATE INDEX "SupplierProfile_status_idx" ON "SupplierProfile" ("organizationId", "lifecycleStatus");

-- Bank details are encrypted at rest (AES-GCM via kernel EncryptionService); only the last 4 digits are readable.
-- A change goes pending_verification → verified by a second user (dual control) before payments may use it.
CREATE TABLE "SupplierBankAccount" (
    id                    TEXT         NOT NULL PRIMARY KEY,
    "organizationId"      TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "partnerId"           TEXT         NOT NULL REFERENCES "Partner" (id) ON DELETE CASCADE,
    "bankName"            TEXT         NOT NULL,
    "branchName"          TEXT,
    "accountName"         TEXT         NOT NULL,
    "accountNumberLast4"  TEXT         NOT NULL,
    "accountNumberEnc"    JSONB        NOT NULL,
    "swiftCode"           TEXT,
    "currencyCode"        TEXT,
    "isDefault"           BOOLEAN      NOT NULL DEFAULT false,
    status                TEXT         NOT NULL DEFAULT 'pending_verification'
        CHECK (status IN ('pending_verification', 'verified', 'revoked')),
    "createdBy"           TEXT,
    "verifiedBy"          TEXT,
    "verifiedAt"          TIMESTAMP(3),
    "revokedBy"           TEXT,
    "revokedAt"           TIMESTAMP(3),
    "createdAt"           TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"           TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "SupplierBankAccount_partner_idx" ON "SupplierBankAccount" ("organizationId", "partnerId", status);

CREATE TABLE "SupplierCertificate" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "partnerId"      TEXT         NOT NULL REFERENCES "Partner" (id) ON DELETE CASCADE,
    type             TEXT         NOT NULL,
    number           TEXT,
    issuer           TEXT,
    "validFrom"      DATE,
    "validTo"        DATE,
    "fileId"         TEXT,
    notes            TEXT,
    "createdBy"      TEXT,
    "deletedAt"      TIMESTAMP(3),
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ("validTo" IS NULL OR "validFrom" IS NULL OR "validTo" >= "validFrom")
);
CREATE INDEX "SupplierCertificate_partner_idx" ON "SupplierCertificate" ("organizationId", "partnerId")
    WHERE "deletedAt" IS NULL;
CREATE INDEX "SupplierCertificate_expiry_idx" ON "SupplierCertificate" ("organizationId", "validTo")
    WHERE "deletedAt" IS NULL;

-- What a supplier sells us (catalogue) and every price we were offered or paid (append-only history).
CREATE TABLE "SupplierProduct" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "partnerId"      TEXT           NOT NULL REFERENCES "Partner" (id) ON DELETE CASCADE,
    "productId"      TEXT           NOT NULL,
    "supplierSku"    TEXT,
    "purchaseUomId"  TEXT,
    "minOrderQty"    NUMERIC(20, 6) CHECK ("minOrderQty" IS NULL OR "minOrderQty" >= 0),
    "leadTimeDays"   INTEGER        CHECK ("leadTimeDays" IS NULL OR "leadTimeDays" >= 0),
    preferred        BOOLEAN        NOT NULL DEFAULT false,
    notes            TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "SupplierProduct_key" ON "SupplierProduct" ("organizationId", "partnerId", "productId");
CREATE INDEX "SupplierProduct_product_idx" ON "SupplierProduct" ("organizationId", "productId");

CREATE TABLE "SupplierPrice" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "partnerId"      TEXT           NOT NULL REFERENCES "Partner" (id) ON DELETE CASCADE,
    "productId"      TEXT           NOT NULL,
    "uomId"          TEXT,
    "currencyCode"   TEXT           NOT NULL,
    "unitPrice"      NUMERIC(20, 6) NOT NULL CHECK ("unitPrice" >= 0),
    quantity         NUMERIC(20, 6),
    "validFrom"      DATE           NOT NULL,
    "validTo"        DATE,
    source           TEXT           NOT NULL CHECK (source IN ('quotation', 'agreement', 'purchase_order', 'manual')),
    "sourceId"       TEXT,
    "createdBy"      TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "SupplierPrice_product_idx" ON "SupplierPrice" ("organizationId", "productId", "validFrom" DESC);
CREATE INDEX "SupplierPrice_partner_idx" ON "SupplierPrice" ("organizationId", "partnerId", "productId");
CREATE UNIQUE INDEX "SupplierPrice_source_key" ON "SupplierPrice" ("organizationId", source, "sourceId", "productId")
    WHERE "sourceId" IS NOT NULL;

-- ── Permissions (granted to existing Administrator roles; new orgs get Permissions.ALL) ──────────────────────────
INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['supplier:read', 'supplier:manage', 'supplier:approve', 'supplier:verify_bank',
                  'procurement_policy:read', 'procurement_policy:manage', 'procurement_policy:approve']) AS k
ON CONFLICT (key) DO NOTHING;

UPDATE "Role" SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['supplier:read', 'supplier:manage',
        'supplier:approve', 'supplier:verify_bank', 'procurement_policy:read', 'procurement_policy:manage',
        'procurement_policy:approve'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;

-- ── Row-level security ─────────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE "ProcurementPolicy" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "ProcurementPolicy" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "SupplierProfile" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SupplierProfile" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "SupplierBankAccount" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SupplierBankAccount" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "SupplierCertificate" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SupplierCertificate" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "SupplierProduct" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SupplierProduct" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "SupplierPrice" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SupplierPrice" USING (("organizationId" = current_setting('app.org_id', true)));
