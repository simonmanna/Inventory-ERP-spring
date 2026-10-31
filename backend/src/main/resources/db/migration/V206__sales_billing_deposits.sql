-- V30: Billing, customer deposits and mobile money (ADR-S011, phase S6; sales-postings.md §4-§5).
--
-- * CustomerDeposit (invoicing-owned): advance received before / without an invoice — Dr tender / Cr
--   "Customer Deposits" (liability). A down-payment invoice whose lines post to the deposit account also creates one
--   when it is posted. Deposits are applied to invoices through explicit DepositApplication rows (each one is a
--   store-credit receipt on the deposit account: Dr Customer Deposits / Cr AR) and can be refunded (Dr deposits / Cr
--   tender). Never a negative invoice line.
-- * SalesInvoiceLink: which sales-order line each invoice line bills (gross invoiced projection).
-- * MobileMoneyRequest: payment-gateway requests (MTN MoMo, Airtel Money, mock) and their outcome.

CREATE TABLE "CustomerDeposit" (
    id                TEXT           NOT NULL PRIMARY KEY,
    "organizationId"  TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    number            TEXT           NOT NULL,
    "partnerId"       TEXT           NOT NULL,
    "salesOrderId"    TEXT,
    source            TEXT           NOT NULL CHECK (source IN ('receipt', 'down_payment_invoice')),
    "documentId"      TEXT,
    "paymentMethod"   TEXT,
    "accountId"       TEXT,
    amount            NUMERIC(20, 6) NOT NULL CHECK (amount > 0),
    "appliedAmount"   NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("appliedAmount" >= 0),
    "refundedAmount"  NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("refundedAmount" >= 0),
    status            TEXT           NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'closed', 'void')),
    "receivedDate"    DATE           NOT NULL,
    "journalEntryId"  TEXT,
    reference         TEXT,
    notes             TEXT,
    "createdById"     TEXT,
    "createdAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"       TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ("appliedAmount" + "refundedAmount" <= amount)
);
CREATE UNIQUE INDEX "CustomerDeposit_org_number_key" ON "CustomerDeposit" ("organizationId", number);
CREATE INDEX "CustomerDeposit_org_partner_idx" ON "CustomerDeposit" ("organizationId", "partnerId", status);
CREATE INDEX "CustomerDeposit_order_idx" ON "CustomerDeposit" ("salesOrderId");
CREATE UNIQUE INDEX "CustomerDeposit_document_key" ON "CustomerDeposit" ("documentId") WHERE "documentId" IS NOT NULL;

CREATE TABLE "DepositApplication" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "depositId"      TEXT           NOT NULL REFERENCES "CustomerDeposit" (id),
    "documentId"     TEXT           NOT NULL,
    "paymentId"      TEXT           NOT NULL,
    amount           NUMERIC(20, 6) NOT NULL CHECK (amount > 0),
    kind             TEXT           NOT NULL DEFAULT 'application' CHECK (kind IN ('application', 'refund')),
    "createdById"    TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "reversedAt"     TIMESTAMP(3)
);
CREATE INDEX "DepositApplication_deposit_idx" ON "DepositApplication" ("depositId");
CREATE INDEX "DepositApplication_document_idx" ON "DepositApplication" ("documentId");

CREATE TABLE "SalesInvoiceLink" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "orderId"        TEXT           NOT NULL REFERENCES "SalesOrder" (id) ON DELETE CASCADE,
    "orderLineId"    TEXT REFERENCES "SalesOrderLine" (id) ON DELETE CASCADE,
    "documentId"     TEXT           NOT NULL,
    kind             TEXT           NOT NULL CHECK (kind IN ('regular', 'down_payment', 'freight', 'credit_note')),
    quantity         NUMERIC(20, 6) NOT NULL DEFAULT 0,
    amount           NUMERIC(20, 6) NOT NULL DEFAULT 0,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "SalesInvoiceLink_order_idx" ON "SalesInvoiceLink" ("orderId");
CREATE INDEX "SalesInvoiceLink_document_idx" ON "SalesInvoiceLink" ("documentId");

CREATE TABLE "MobileMoneyRequest" (
    id               TEXT           NOT NULL PRIMARY KEY,
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    provider         TEXT           NOT NULL,
    "partnerId"      TEXT           NOT NULL,
    phone            TEXT           NOT NULL,
    amount           NUMERIC(20, 6) NOT NULL CHECK (amount > 0),
    "currencyCode"   TEXT           NOT NULL,
    purpose          TEXT           NOT NULL CHECK (purpose IN ('invoice', 'deposit', 'on_account')),
    "documentId"     TEXT,
    "salesOrderId"   TEXT,
    status           TEXT           NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'succeeded', 'failed')),
    "providerRef"    TEXT,
    "paymentId"      TEXT,
    "depositId"      TEXT,
    error            TEXT,
    "createdById"    TEXT,
    "createdAt"      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "completedAt"    TIMESTAMP(3)
);
CREATE UNIQUE INDEX "MobileMoneyRequest_provider_ref_key" ON "MobileMoneyRequest" (provider, "providerRef")
    WHERE "providerRef" IS NOT NULL;

ALTER TABLE "SalesSettings"
    -- How deposits meet final invoices: manually chosen, or automatically oldest first when invoicing an order.
    ADD COLUMN "depositApplication" TEXT NOT NULL DEFAULT 'manual'
        CHECK ("depositApplication" IN ('manual', 'oldest_first')),
    -- VAT tax point of advances: on delivery (deposit without VAT) or on receipt (down-payment invoice with VAT).
    ADD COLUMN "depositTaxPoint"    TEXT NOT NULL DEFAULT 'delivery' CHECK ("depositTaxPoint" IN ('delivery', 'receipt'));

ALTER TABLE "CustomerDeposit" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CustomerDeposit" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "DepositApplication" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "DepositApplication" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "SalesInvoiceLink" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SalesInvoiceLink" USING (("organizationId" = current_setting('app.org_id', true)));
ALTER TABLE "MobileMoneyRequest" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "MobileMoneyRequest" USING (("organizationId" = current_setting('app.org_id', true)));

INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, k, split_part(k, ':', 1), split_part(k, ':', 2), CURRENT_TIMESTAMP
FROM unnest(ARRAY['sales:invoice:create', 'sales:deposit:record', 'sales:deposit:refund']) AS k
ON CONFLICT (key) DO NOTHING;
UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['sales:invoice:create', 'sales:deposit:record',
        'sales:deposit:refund'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE name = 'Administrator' AND "deletedAt" IS NULL;
