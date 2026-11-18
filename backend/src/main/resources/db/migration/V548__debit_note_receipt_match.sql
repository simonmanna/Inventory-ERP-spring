-- Gap fix h03-F4 / h03-F5 (readiness gaps, stream gD): a return to the supplier that names no supplier bill consumes the
-- supplier's open (not yet billed) receipt quantity, as a bill would. Goods from a receipt without a purchase order
-- clear GRNI; goods from a purchase-order receipt unwind the payable its receipt voucher raised. Each consumption is
-- recorded here so a reversal of the note gives the quantity back, and a goods-receipt reversal sees the return.
CREATE TABLE "DebitNoteReceiptMatch" (
    id                   TEXT PRIMARY KEY,
    "organizationId"     TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "debitNoteId"        TEXT           NOT NULL REFERENCES "DebitNote" (id) ON DELETE CASCADE,
    "debitNoteLineId"    TEXT           NOT NULL REFERENCES "DebitNoteLine" (id) ON DELETE CASCADE,
    "goodsReceiptLineId" TEXT           NOT NULL REFERENCES "GoodsReceiptLine" (id) ON DELETE CASCADE,
    quantity             NUMERIC(20, 6) NOT NULL CHECK (quantity > 0),
    vouchered            BOOLEAN        NOT NULL,
    "unitCost"           NUMERIC(20, 6) NOT NULL,
    "releasedAt"         TIMESTAMP(3),
    "createdAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "DebitNoteReceiptMatch_note" ON "DebitNoteReceiptMatch" ("organizationId", "debitNoteId");
CREATE INDEX "DebitNoteReceiptMatch_receipt_line" ON "DebitNoteReceiptMatch" ("organizationId", "goodsReceiptLineId");

ALTER TABLE "DebitNoteReceiptMatch" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "DebitNoteReceiptMatch" USING (("organizationId" = current_setting('app.org_id', true)));
