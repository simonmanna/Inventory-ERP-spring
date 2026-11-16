-- Phase A A4 (plan §5, §6, §10, §13): invoice ownership per delivery-order source, the Fleet goods-invoice ledger,
-- hardening of the Sales invoice link, and the review of historical Fleet goods invoices on sales orders.
-- No GL or accounting-policy change; no UPDATE of business rows.

-- Fleet goods ledger (delivery orders whose source is not a sales order). Immutable: an allocation is live while its
-- document is not cancelled or rejected, so cancelling the invoice releases it in the same transaction, exactly once.
CREATE TABLE "FleetDeliveryInvoiceLine" (
    id                    TEXT           NOT NULL PRIMARY KEY,
    "organizationId"      TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "deliveryOrderId"     TEXT           NOT NULL REFERENCES "FleetDeliveryOrder" (id),
    "deliveryOrderLineId" TEXT           NOT NULL REFERENCES "FleetDeliveryOrderLine" (id),
    "documentId"          TEXT           NOT NULL REFERENCES "Document" (id),
    "documentLineId"      TEXT           NOT NULL REFERENCES "DocumentLine" (id),
    quantity              NUMERIC(20, 6) NOT NULL CHECK (quantity > 0),
    "uomId"               TEXT,
    "createdById"         TEXT,
    "createdAt"           TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "FleetDeliveryInvoiceLine_documentLine_key" UNIQUE ("documentLineId"),
    CONSTRAINT "FleetDeliveryInvoiceLine_document_line_key" UNIQUE ("documentId", "deliveryOrderLineId")
);
CREATE INDEX "FleetDeliveryInvoiceLine_order_idx" ON "FleetDeliveryInvoiceLine" ("deliveryOrderId");
CREATE INDEX "FleetDeliveryInvoiceLine_line_idx" ON "FleetDeliveryInvoiceLine" ("deliveryOrderLineId");

CREATE OR REPLACE FUNCTION fleet_delivery_invoice_line_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'FleetDeliveryInvoiceLine rows are immutable';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER "FleetDeliveryInvoiceLine_immutable" BEFORE UPDATE OR DELETE ON "FleetDeliveryInvoiceLine"
    FOR EACH ROW EXECUTE FUNCTION fleet_delivery_invoice_line_immutable();

-- Review of historical Fleet goods invoices on sales orders (plan §6). An order with an unreviewed one is blocked
-- from Sales invoicing; an approved review writes legacy_fleet links; an exception keeps it blocked.
CREATE TABLE "SalesLegacyInvoiceReview" (
    id                TEXT         NOT NULL PRIMARY KEY,
    "organizationId"  TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "orderId"         TEXT         NOT NULL REFERENCES "SalesOrder" (id),
    "deliveryOrderId" TEXT         NOT NULL,
    "documentId"      TEXT         NOT NULL REFERENCES "Document" (id),
    matches           JSONB        NOT NULL DEFAULT '[]',
    -- open (reviewer editing) → submitted (approval-engine request pending) → reconciled; exception keeps it blocked.
    status            TEXT         NOT NULL DEFAULT 'open'
                                   CHECK (status IN ('open', 'submitted', 'reconciled', 'exception')),
    notes             TEXT,
    "evidenceFileIds" TEXT[]       NOT NULL DEFAULT '{}',
    "reviewedById"    TEXT,
    "reviewedAt"      TIMESTAMP(3),
    "approvedById"    TEXT,
    "approvedAt"      TIMESTAMP(3),
    -- The kernel approval-engine request (entity type sales_legacy_invoice_review) of the current submission.
    "approvalRequestId" TEXT,
    "createdAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "SalesLegacyInvoiceReview_order_document_key" UNIQUE ("orderId", "documentId")
);
CREATE INDEX "SalesLegacyInvoiceReview_org_idx" ON "SalesLegacyInvoiceReview" ("organizationId", status);

-- Sales invoice link: the invoice line it allocates, its origin, and the legacy review that wrote it.
ALTER TABLE "SalesInvoiceLink"
    ADD COLUMN "documentLineId" TEXT,
    ADD COLUMN origin           TEXT NOT NULL DEFAULT 'sales' CHECK (origin IN ('sales', 'legacy_fleet')),
    ADD COLUMN "legacyReviewId" TEXT REFERENCES "SalesLegacyInvoiceReview" (id);

-- One regular link per invoice and order line. Pre-flight: when duplicates already exist the index is not created
-- (a warning is raised) and the financial reconciliation reports them as duplicate_regular_link for review.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM "SalesInvoiceLink" WHERE kind = 'regular' AND "orderLineId" IS NOT NULL
               GROUP BY "documentId", "orderLineId" HAVING count(*) > 1) THEN
        RAISE WARNING 'SalesInvoiceLink has duplicate regular links per invoice and order line; unique index skipped';
    ELSE
        CREATE UNIQUE INDEX "SalesInvoiceLink_regular_key" ON "SalesInvoiceLink" ("documentId", "orderLineId")
            WHERE kind = 'regular';
    END IF;
END $$;

-- Sales reconciliation runs: fulfilment (existing) or financial (A4).
ALTER TABLE "SalesReconciliation"
    ADD COLUMN kind TEXT NOT NULL DEFAULT 'fulfilment' CHECK (kind IN ('fulfilment', 'financial'));

-- Same policy as V214: a tenant transaction sees its own rows; work without a tenant (jobs, seeds) is not restricted.
ALTER TABLE "FleetDeliveryInvoiceLine" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "FleetDeliveryInvoiceLine"
    USING (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true))
    WITH CHECK (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true));
ALTER TABLE "SalesLegacyInvoiceReview" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SalesLegacyInvoiceReview"
    USING (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true))
    WITH CHECK (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true));
