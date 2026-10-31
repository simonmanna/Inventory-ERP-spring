-- ADR-S022 §4: one sales pricing engine — configurable tier precedence and immutable pricing snapshots.

CREATE TABLE "PricingPolicy" (
    "organizationId" TEXT         NOT NULL PRIMARY KEY REFERENCES "Organization" (id) ON DELETE CASCADE,
    -- Tier order; empty = the engine default. The catalogue list price is always the last resort.
    precedence       TEXT[]       NOT NULL DEFAULT ARRAY[]::TEXT[],
    "updatedById"    TEXT,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE "PricingPolicy" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "PricingPolicy" USING (("organizationId" = current_setting('app.org_id', true)));

-- One row per document line, written when the transaction is committed commercially (sales order confirmed, invoice
-- posted, order billed on credit, manual delivery order created). Insert-only: the trigger below refuses changes.
CREATE TABLE "PriceSnapshot" (
    id                TEXT           NOT NULL PRIMARY KEY,
    "organizationId"  TEXT           NOT NULL,
    "documentType"    TEXT           NOT NULL,
    "documentId"      TEXT           NOT NULL,
    "lineId"          TEXT           NOT NULL,
    "productId"       TEXT,
    "partnerId"       TEXT,
    quantity          NUMERIC(20, 6) NOT NULL,
    -- The agreed line values as stored on the document.
    "unitPrice"       NUMERIC(20, 6) NOT NULL,
    "discountPercent" NUMERIC(9, 4)  NOT NULL DEFAULT 0,
    "taxId"           TEXT,
    -- The engine's breakdown at that moment (source tier, base, discount, net, tax, final, line totals).
    breakdown         JSONB          NOT NULL,
    -- True when the agreed price differs from what the engine gives (typed, approved or carried from a source).
    "manualPrice"     BOOLEAN        NOT NULL DEFAULT false,
    "agreedHash"      TEXT           NOT NULL,
    "createdById"     TEXT,
    "createdAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "PriceSnapshot_line_key" ON "PriceSnapshot" ("organizationId", "documentType", "documentId",
    "lineId", "agreedHash");
CREATE INDEX "PriceSnapshot_document_idx" ON "PriceSnapshot" ("organizationId", "documentType", "documentId");
ALTER TABLE "PriceSnapshot" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "PriceSnapshot" USING (("organizationId" = current_setting('app.org_id', true)));

CREATE FUNCTION price_snapshot_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'PriceSnapshot rows are immutable';
END;
$$;
CREATE TRIGGER price_snapshot_immutable BEFORE UPDATE OR DELETE ON "PriceSnapshot"
    FOR EACH ROW EXECUTE FUNCTION price_snapshot_immutable();
