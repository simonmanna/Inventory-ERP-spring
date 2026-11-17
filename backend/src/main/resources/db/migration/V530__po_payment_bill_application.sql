-- Review 2026-11-17 (ACR-2): a PO receipt raises AP (receipt voucher) and a supplier bill matched to that receipt
-- becomes the open AP item for it. Payments already made on the purchase order are applied to the bill that takes the
-- receipt over, so the supplier is never shown (or paid) twice: the PO open item keeps only unbilled receipts less
-- unapplied PO payments, and the bill's residual is net of the PO payments applied to it.
--
-- One row per (bill, purchase order) application; released (not deleted) when the bill is voided, which returns the
-- amount to the purchase order.

CREATE TABLE "PurchaseOrderBillApplication" (
    id                TEXT           NOT NULL PRIMARY KEY,
    "organizationId"  TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "purchaseOrderId" TEXT           NOT NULL REFERENCES "PurchaseOrder" (id) ON DELETE CASCADE,
    "vendorBillId"    TEXT           NOT NULL REFERENCES "Document" (id) ON DELETE CASCADE,
    amount            NUMERIC(20, 6) NOT NULL CHECK (amount > 0),
    "createdBy"       TEXT,
    "createdAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "releasedAt"      TIMESTAMP(3)
);
CREATE INDEX "PurchaseOrderBillApplication_po_idx"
    ON "PurchaseOrderBillApplication" ("organizationId", "purchaseOrderId") WHERE "releasedAt" IS NULL;
CREATE INDEX "PurchaseOrderBillApplication_bill_idx"
    ON "PurchaseOrderBillApplication" ("organizationId", "vendorBillId");
