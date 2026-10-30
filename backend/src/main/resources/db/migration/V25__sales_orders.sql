-- V25: Sales orders (ADR-S011, phase S1) — B2B sales order header / lines, immutable revisions and change requests
-- for confirmed orders. Quantity columns beyond ordered / cancelled are projections maintained by later phases
-- (reserved S4, delivered / billable S5, invoiced / credited S6, returned S7); the authoritative records stay in the
-- owning modules. Additive extension beyond the NestJS reference.

CREATE TABLE "SalesOrder" (
    id                   TEXT           NOT NULL PRIMARY KEY,
    "organizationId"     TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    number               TEXT           NOT NULL,
    revision             INTEGER        NOT NULL DEFAULT 0,
    status               TEXT           NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'pending_approval',
        'confirmed', 'in_fulfilment', 'done', 'cancelled')),
    "onHold"             BOOLEAN        NOT NULL DEFAULT false,
    "holdReason"         TEXT,
    "deliveryStatus"     TEXT           NOT NULL DEFAULT 'none' CHECK ("deliveryStatus" IN ('none', 'partial', 'full')),
    "invoiceStatus"      TEXT           NOT NULL DEFAULT 'nothing'
        CHECK ("invoiceStatus" IN ('nothing', 'to_invoice', 'partial', 'invoiced')),
    "partnerId"          TEXT           NOT NULL,
    "contactId"          TEXT,
    "quotationId"        TEXT REFERENCES "SalesQuotation" (id) ON DELETE SET NULL,
    "dealId"             TEXT,
    "branchId"           TEXT,
    -- Source warehouse / yard (default for lines).
    "locationId"         TEXT,
    "siteId"             TEXT,
    "deliveryAddress"    TEXT,
    "deliveryMethod"     TEXT           NOT NULL DEFAULT 'delivery' CHECK ("deliveryMethod" IN ('delivery', 'collect')),
    "paymentTermId"      TEXT,
    "pricelistId"        TEXT,
    "currencyCode"       TEXT           NOT NULL,
    -- Locked at confirmation.
    "exchangeRate"       NUMERIC(20, 10) NOT NULL DEFAULT 1 CHECK ("exchangeRate" > 0),
    "customerRef"        TEXT,
    "requestedDate"      DATE,
    "commitmentDate"     DATE,
    incoterm             TEXT,
    "ownerId"            TEXT,
    "teamId"             TEXT,
    notes                TEXT,
    terms                TEXT,
    subtotal             NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "discountTotal"      NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "taxAmount"          NUMERIC(20, 6) NOT NULL DEFAULT 0,
    total                NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "maxDiscountPercent" NUMERIC(9, 4)  NOT NULL DEFAULT 0,
    -- Per-order policy overrides (NULL = organization default from SalesSettings).
    "physicalMovement"   TEXT CHECK ("physicalMovement" IS NULL OR "physicalMovement" IN ('direct', 'via_transit')),
    "ownershipTransfer"  TEXT CHECK ("ownershipTransfer" IS NULL
        OR "ownershipTransfer" IN ('at_dispatch', 'at_delivery', 'at_acceptance')),
    "billingPolicy"      TEXT CHECK ("billingPolicy" IS NULL OR "billingPolicy" IN ('ordered', 'delivered')),
    "approvalReasons"    TEXT[]         NOT NULL DEFAULT ARRAY[]::TEXT[],
    "approvalRequestId"  TEXT,
    "confirmedAt"        TIMESTAMP(3),
    "confirmedById"      TEXT,
    "doneAt"             TIMESTAMP(3),
    "cancelledAt"        TIMESTAMP(3),
    "cancelledById"      TEXT,
    "cancelReason"       TEXT,
    version              INTEGER        NOT NULL DEFAULT 0,
    "createdById"        TEXT,
    "createdAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"          TIMESTAMP(3)
);
CREATE UNIQUE INDEX "SalesOrder_org_number_key" ON "SalesOrder" ("organizationId", number);
CREATE INDEX "SalesOrder_org_status_idx" ON "SalesOrder" ("organizationId", status) WHERE "deletedAt" IS NULL;
CREATE INDEX "SalesOrder_org_partner_idx" ON "SalesOrder" ("organizationId", "partnerId");
CREATE INDEX "SalesOrder_quotation_idx" ON "SalesOrder" ("quotationId");

CREATE TABLE "SalesOrderLine" (
    id                     TEXT            NOT NULL PRIMARY KEY,
    "organizationId"       TEXT            NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "orderId"              TEXT            NOT NULL REFERENCES "SalesOrder" (id) ON DELETE CASCADE,
    "sortOrder"            INTEGER         NOT NULL DEFAULT 0,
    "productId"            TEXT,
    description            TEXT            NOT NULL,
    quantity               NUMERIC(20, 6)  NOT NULL CHECK (quantity > 0),
    "uomId"                TEXT,
    "uomFactor"            NUMERIC(20, 10) NOT NULL DEFAULT 1 CHECK ("uomFactor" > 0),
    "listPrice"            NUMERIC(20, 6)  NOT NULL DEFAULT 0,
    "unitPrice"            NUMERIC(20, 6)  NOT NULL CHECK ("unitPrice" >= 0),
    "discountPercent"      NUMERIC(9, 4)   NOT NULL DEFAULT 0 CHECK ("discountPercent" BETWEEN 0 AND 100),
    "priceSource"          TEXT            NOT NULL DEFAULT 'list'
        CHECK ("priceSource" IN ('list', 'rule', 'customer', 'manual', 'pricelist', 'agreement', 'quotation')),
    "priceRuleId"          TEXT,
    "taxId"                TEXT,
    "taxInclusive"         BOOLEAN         NOT NULL DEFAULT false,
    "lineSubtotal"         NUMERIC(20, 6)  NOT NULL DEFAULT 0,
    "lineTax"              NUMERIC(20, 6)  NOT NULL DEFAULT 0,
    "lineTotal"            NUMERIC(20, 6)  NOT NULL DEFAULT 0,
    "promisedDate"         DATE,
    "sourceLocationId"     TEXT,
    "costSnapshot"         NUMERIC(20, 6),
    "tolerancePct"         NUMERIC(9, 4)   NOT NULL DEFAULT 0 CHECK ("tolerancePct" >= 0),
    "quotationLineId"      TEXT,
    -- Commercial: closed short (never delivered).
    "qtyCancelled"         NUMERIC(20, 6)  NOT NULL DEFAULT 0 CHECK ("qtyCancelled" >= 0),
    "closedShortAt"        TIMESTAMP(3),
    "closeShortReason"     TEXT,
    -- Projections (rebuildable from the owning modules).
    "qtyReserved"          NUMERIC(20, 6)  NOT NULL DEFAULT 0,
    "qtyDelivered"         NUMERIC(20, 6)  NOT NULL DEFAULT 0,
    "qtyBillable"          NUMERIC(20, 6)  NOT NULL DEFAULT 0,
    "qtyInvoiced"          NUMERIC(20, 6)  NOT NULL DEFAULT 0,
    "qtyCredited"          NUMERIC(20, 6)  NOT NULL DEFAULT 0,
    "qtyReturned"          NUMERIC(20, 6)  NOT NULL DEFAULT 0,
    CHECK ("qtyCancelled" <= quantity)
);
CREATE INDEX "SalesOrderLine_order_idx" ON "SalesOrderLine" ("orderId", "sortOrder");
CREATE INDEX "SalesOrderLine_org_product_idx" ON "SalesOrderLine" ("organizationId", "productId");

CREATE TABLE "SalesOrderRevision" (
    id                TEXT           NOT NULL PRIMARY KEY,
    "organizationId"  TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "orderId"         TEXT           NOT NULL REFERENCES "SalesOrder" (id) ON DELETE CASCADE,
    revision          INTEGER        NOT NULL,
    total             NUMERIC(20, 6) NOT NULL,
    snapshot          JSONB          NOT NULL,
    diff              JSONB,
    reason            TEXT,
    "changeRequestId" TEXT,
    "createdById"     TEXT,
    "createdAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE ("orderId", revision)
);

CREATE TABLE "SalesOrderChangeRequest" (
    id                  TEXT         NOT NULL PRIMARY KEY,
    "organizationId"    TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "orderId"           TEXT         NOT NULL REFERENCES "SalesOrder" (id) ON DELETE CASCADE,
    number              TEXT         NOT NULL,
    status              TEXT         NOT NULL DEFAULT 'pending_approval'
        CHECK (status IN ('pending_approval', 'applied', 'rejected', 'cancelled')),
    reason              TEXT         NOT NULL,
    changes             JSONB        NOT NULL,
    impact              JSONB,
    "baseRevision"      INTEGER      NOT NULL,
    "approvalRequestId" TEXT,
    "requestedById"     TEXT,
    "decidedById"       TEXT,
    "decidedAt"         TIMESTAMP(3),
    "decisionComment"   TEXT,
    "appliedRevision"   INTEGER,
    "createdAt"         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "SalesOrderChangeRequest_org_number_key" ON "SalesOrderChangeRequest" ("organizationId", number);
CREATE INDEX "SalesOrderChangeRequest_order_idx" ON "SalesOrderChangeRequest" ("orderId");

ALTER TABLE "SalesOrder" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SalesOrder" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "SalesOrderLine" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SalesOrderLine" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "SalesOrderRevision" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SalesOrderRevision"
    USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "SalesOrderChangeRequest" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SalesOrderChangeRequest"
    USING (("organizationId" = current_setting('app.org_id', true)));
