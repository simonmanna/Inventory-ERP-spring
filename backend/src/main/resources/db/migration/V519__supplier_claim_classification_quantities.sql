-- Owner decision (supplier claims): a claim without quantities must not automatically credit stock or GRNI. A claim is
-- settled under an explicit classification (price variance, expense correction, VAT adjustment, stock adjustment);
-- a stock adjustment needs quantity lines tied to goods-receipt lines and verified by a second user.
-- See docs/spec/procurement-postings.md §7. Existing claims keep a NULL classification (settled before this change).

ALTER TABLE "SupplierClaim" ADD COLUMN "classification" TEXT
    CHECK ("classification" IN ('price_variance', 'expense_correction', 'vat_adjustment', 'stock_adjustment'));
-- Input VAT the settlement reversed (Cr tax_receivable, document currency): caps later VAT adjustments on the same bill.
ALTER TABLE "SupplierClaim" ADD COLUMN "settledTax" NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("settledTax" >= 0);

CREATE TABLE "SupplierClaimLine" (
    id                   TEXT           NOT NULL PRIMARY KEY,
    "organizationId"     TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "claimId"            TEXT           NOT NULL REFERENCES "SupplierClaim" (id) ON DELETE CASCADE,
    "goodsReceiptId"     TEXT           NOT NULL REFERENCES "GoodsReceiptNote" (id),
    "goodsReceiptLineId" TEXT           NOT NULL REFERENCES "GoodsReceiptLine" (id),
    "productId"          TEXT           NOT NULL REFERENCES "Product" (id),
    "variantId"          TEXT,
    -- In the receipt line's unit; unitCost = the receipt line's unit cost (receipt / PO currency).
    quantity             NUMERIC(20, 6) NOT NULL CHECK (quantity > 0),
    "unitCost"           NUMERIC(20, 6) NOT NULL CHECK ("unitCost" > 0),
    -- issue: the goods leave stock at settlement (return-to-supplier stock issue); written_off: they already left
    -- stock through a write-off, so the credit reverses that loss (stock_adjustment_expense).
    "stockAction"        TEXT           NOT NULL DEFAULT 'issue' CHECK ("stockAction" IN ('issue', 'written_off')),
    notes                TEXT,
    "createdBy"          TEXT,
    "verifiedBy"         TEXT,
    "verifiedAt"         TIMESTAMP(3),
    -- Settlement results: quantity credited against open GRNI (unvouchered receipt), stock value the engine issued and
    -- the stock ledger code of that issue.
    "grniQuantity"       NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("grniQuantity" >= 0),
    "issuedValue"        NUMERIC(20, 6),
    "stockLedgerCode"    TEXT,
    "createdAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "SupplierClaimLine_verified" CHECK (("verifiedBy" IS NULL) = ("verifiedAt" IS NULL)),
    CONSTRAINT "SupplierClaimLine_grni" CHECK ("grniQuantity" <= quantity)
);
CREATE INDEX "SupplierClaimLine_claim_idx" ON "SupplierClaimLine" ("claimId");
CREATE INDEX "SupplierClaimLine_receipt_line_idx" ON "SupplierClaimLine" ("organizationId", "goodsReceiptLineId");

ALTER TABLE "SupplierClaimLine" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SupplierClaimLine" USING (("organizationId" = current_setting('app.org_id', true)));
