-- Purchase readiness fixes (owner-approved 2026-11-27), stream "bills".
--
-- B3: a goods receipt line can never be billed (or claimed) beyond what it received. Added NOT VALID first so the
--     migration never fails on historic rows; it is then validated when the existing data allows it. When it does not
--     (a receipt line already over-billed by the pre-fix matcher), the constraint stays NOT VALID — it still guards
--     every new write — and a notice names how many rows need repair.
-- H1: VendorBillAutoReceipt records what a bill received into stock itself (procurement.billWithoutReceipt =
--     auto_receive, no goods receipt to match). A goods receipt of the same supplier, product and variant posted later
--     absorbs that quantity (no second stock-in, no second payable) instead of receiving it again.

ALTER TABLE "GoodsReceiptLine" ADD CONSTRAINT "GoodsReceiptLine_billed_le_received"
    CHECK ("billedQuantity" <= quantity + 0.0001) NOT VALID;

DO $$
DECLARE
    bad bigint;
BEGIN
    SELECT count(*) INTO bad FROM "GoodsReceiptLine" WHERE "billedQuantity" > quantity + 0.0001;
    IF bad = 0 THEN
        ALTER TABLE "GoodsReceiptLine" VALIDATE CONSTRAINT "GoodsReceiptLine_billed_le_received";
    ELSE
        RAISE NOTICE 'GoodsReceiptLine_billed_le_received left NOT VALID: % receipt line(s) are billed beyond their quantity', bad;
    END IF;
END $$;

CREATE TABLE "VendorBillAutoReceipt" (
    id                     TEXT PRIMARY KEY,
    "organizationId"       TEXT NOT NULL REFERENCES "Organization" (id),
    "vendorBillId"         TEXT NOT NULL REFERENCES "Document" (id),
    "documentLineId"       TEXT NOT NULL,
    "partnerId"            TEXT NOT NULL,
    "productId"            TEXT NOT NULL,
    "variantId"            TEXT,
    "baseQuantity"         NUMERIC(20, 6) NOT NULL,
    "absorbedBaseQuantity" NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "createdAt"            TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "VendorBillAutoReceipt_quantities" CHECK ("baseQuantity" > 0 AND "absorbedBaseQuantity" >= 0
        AND "absorbedBaseQuantity" <= "baseQuantity" + 0.0001)
);
CREATE INDEX "VendorBillAutoReceipt_open_idx" ON "VendorBillAutoReceipt" ("organizationId", "partnerId", "productId")
    WHERE "absorbedBaseQuantity" < "baseQuantity";
CREATE INDEX "VendorBillAutoReceipt_bill_idx" ON "VendorBillAutoReceipt" ("vendorBillId");

DO $$
DECLARE
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
BEGIN
    ALTER TABLE public."VendorBillAutoReceipt" ENABLE ROW LEVEL SECURITY;
    DROP POLICY IF EXISTS tenant_isolation ON public."VendorBillAutoReceipt";
    EXECUTE format('CREATE POLICY tenant_isolation ON public."VendorBillAutoReceipt" USING (%s) WITH CHECK (%s)',
        expr, expr);
END $$;
