-- ADR-S094 C3 (§5): the branch cash sale is a Sale of type cash (SalesOrder."saleType" = 'cash'). It is drafted at a
-- branch from its BranchSalesProfile, paid with verified tender lines, and confirmed in one transaction: counter
-- pickup (fleet load, stock issue, proof of handover), invoice, one receipt per tender, fiscal outbox row (F1).
--
-- * SalesOrder: the profile, the sale date and the walk-in buyer's identity; the fiscal status of the sale's invoice.
-- * SalesOrderPayment: tender lines (cash, mobile money, bank, card), verified before confirm, each posted as a receipt.
-- * SalesOrderEvent: the cash sale's timeline (tenders, verification, confirm, fiscal).
-- * FiscalDocument: a fiscal operation belongs to a legacy submission or to a sale.

ALTER TABLE "SalesOrder"
    ADD COLUMN "profileId"       TEXT REFERENCES "BranchSalesProfile" (id),
    ADD COLUMN "saleDate"        DATE,
    ADD COLUMN "customerName"    TEXT,
    ADD COLUMN "customerPhone"   TEXT,
    ADD COLUMN "customerTin"     TEXT,
    ADD COLUMN "customerAddress" TEXT,
    ADD COLUMN "fiscalStatus"    TEXT NOT NULL DEFAULT 'none';
ALTER TABLE "SalesOrder"
    ADD CONSTRAINT "SalesOrder_fiscalStatus_check" CHECK ("fiscalStatus" IN ('none', 'pending', 'issued', 'failed',
        'cancellation_pending', 'cancelled')),
    -- I7: a cash sale belongs to a branch and its profile
    ADD CONSTRAINT "SalesOrder_cash_profile_check" CHECK ("saleType" <> 'cash' OR ("profileId" IS NOT NULL AND
        "branchId" IS NOT NULL));
CREATE INDEX "SalesOrder_cash_idx" ON "SalesOrder" ("organizationId", "branchId", "createdAt") WHERE "saleType" = 'cash';

CREATE TABLE "SalesOrderPayment" (
    id                   TEXT           NOT NULL PRIMARY KEY,
    "organizationId"     TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "orderId"            TEXT           NOT NULL REFERENCES "SalesOrder" (id) ON DELETE CASCADE,
    method               TEXT           NOT NULL CHECK (method IN ('cash', 'mobile_money', 'bank', 'card')),
    provider             TEXT,
    "paymentModeId"      TEXT,
    "paymentModeLabel"   TEXT,
    "accountId"          TEXT           NOT NULL REFERENCES "Account" (id),
    amount               NUMERIC(20, 6) NOT NULL CHECK (amount > 0),
    -- physical cash: what the customer handed over and the change given back (never posted)
    "amountTendered"     NUMERIC(20, 6),
    "changeGiven"        NUMERIC(20, 6) NOT NULL DEFAULT 0 CHECK ("changeGiven" >= 0),
    reference            TEXT,
    "paymentDate"        DATE,
    "payerName"          TEXT,
    "payerAccount"       TEXT,
    notes                TEXT,
    "attachmentIds"      TEXT[]         NOT NULL DEFAULT '{}',
    "collectedById"      TEXT,
    "collectedAt"        TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "verificationStatus" TEXT           NOT NULL DEFAULT 'pending'
        CHECK ("verificationStatus" IN ('pending', 'verified', 'failed')),
    "verifiedById"       TEXT,
    "verifiedAt"         TIMESTAMP(3),
    "verificationSource" TEXT CHECK ("verificationSource" IN ('collector', 'verifier', 'confirmer_cash', 'momo_gateway')),
    "verificationNote"   TEXT,
    status               TEXT           NOT NULL DEFAULT 'captured' CHECK (status IN ('captured', 'posted', 'voided')),
    "paymentId"          TEXT REFERENCES "Payment" (id),
    "createdAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "SalesOrderPayment_provider_check" CHECK (method <> 'mobile_money' OR provider IS NOT NULL),
    CONSTRAINT "SalesOrderPayment_tendered_check" CHECK ("amountTendered" IS NULL OR (method = 'cash'
        AND "amountTendered" >= amount AND "changeGiven" = "amountTendered" - amount)),
    CONSTRAINT "SalesOrderPayment_posted_check" CHECK (status <> 'posted' OR ("paymentId" IS NOT NULL
        AND "verificationStatus" = 'verified'))
);
CREATE INDEX "SalesOrderPayment_order_idx" ON "SalesOrderPayment" ("orderId");
-- PM-8: a non-cash reference is recorded once (a failed line is kept as evidence and may be captured again)
CREATE UNIQUE INDEX "SalesOrderPayment_reference_key" ON "SalesOrderPayment"
    ("organizationId", method, COALESCE(provider, ''), reference)
    WHERE reference IS NOT NULL AND "verificationStatus" <> 'failed';

CREATE TABLE "SalesOrderEvent" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "orderId"        TEXT         NOT NULL REFERENCES "SalesOrder" (id) ON DELETE CASCADE,
    action           TEXT         NOT NULL,
    "fromStatus"     TEXT,
    "toStatus"       TEXT,
    note             TEXT,
    data             JSONB,
    "userId"         TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "SalesOrderEvent_order_idx" ON "SalesOrderEvent" ("orderId", "createdAt");

ALTER TABLE "FiscalDocument"
    ALTER COLUMN "submissionId" DROP NOT NULL,
    ADD COLUMN "salesOrderId" TEXT REFERENCES "SalesOrder" (id) ON DELETE CASCADE;
ALTER TABLE "FiscalDocument"
    ADD CONSTRAINT "FiscalDocument_owner_check" CHECK (num_nonnulls("submissionId", "salesOrderId") = 1);
-- One live (not failed) operation of each kind per sale at a time.
CREATE UNIQUE INDEX "FiscalDocument_sale_live_op_key" ON "FiscalDocument" ("organizationId", "salesOrderId", operation)
    WHERE status IN ('pending', 'unknown') AND "salesOrderId" IS NOT NULL;
CREATE INDEX "FiscalDocument_sale_idx" ON "FiscalDocument" ("salesOrderId") WHERE "salesOrderId" IS NOT NULL;

DO $$
DECLARE
    expr text := 'coalesce(current_setting(''app.org_id'', true), '''') = '''' OR "organizationId" = '
                 || 'current_setting(''app.org_id'', true)';
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['SalesOrderPayment', 'SalesOrderEvent'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON public.%I', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING (%s) WITH CHECK (%s)', t, expr, expr);
    END LOOP;
END $$;
