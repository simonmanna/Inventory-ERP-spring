-- ADR-S094 C3b (§5.5, §7.4): the cash Sale's reversal and the fiscal outbox's recovery states.
--
-- * SalesOrder: the reversal of a cash sale. Asked for (reason, by whom), the credit note it is settled with (prepared
--   ahead when credit notes need approval), the return that took the goods back, and when it completed. A confirmed
--   cash sale is cancelled only by its reversal (I6).
-- * FiscalDocument:
--   - 'sent': a worker claimed the operation before calling the provider. A crash after the claim leaves the row
--     here, and the next worker looks the request up before sending anything again.
--   - 'review': the outcome is unknown and the provider can neither be asked about a request nor ignore a repeated
--     one. Nothing is sent again automatically; a person reconciles it.
--   - claimedAt / submittedAt time the claim and the provider's settlement window. submissions counts what was really
--     sent. The resolution fields record a manual reconciliation; alertedAt records the stuck-operation alert.

ALTER TABLE "SalesOrder"
    ADD COLUMN "reversalRequestedAt"   TIMESTAMP(3),
    ADD COLUMN "reversalRequestedById" TEXT,
    ADD COLUMN "reversalReason"        TEXT,
    ADD COLUMN "reversalCreditNoteId"  TEXT REFERENCES "Document" (id) ON DELETE SET NULL,
    ADD COLUMN "reversalReturnId"      TEXT REFERENCES "SalesReturn" (id),
    ADD COLUMN "reversedAt"            TIMESTAMP(3),
    ADD COLUMN "reversedById"          TEXT;
ALTER TABLE "SalesOrder"
    -- I6: a confirmed cash sale is cancelled only by its reversal
    ADD CONSTRAINT "SalesOrder_cash_reversal_check" CHECK ("saleType" <> 'cash' OR status <> 'cancelled'
        OR "confirmedAt" IS NULL OR "reversedAt" IS NOT NULL),
    ADD CONSTRAINT "SalesOrder_reversed_check" CHECK ("reversedAt" IS NULL OR (status = 'cancelled'
        AND "saleType" = 'cash' AND "reversalReturnId" IS NOT NULL));

ALTER TABLE "FiscalDocument" DROP CONSTRAINT "FiscalDocument_status_check";
ALTER TABLE "FiscalDocument"
    ADD CONSTRAINT "FiscalDocument_status_check" CHECK (status IN ('pending', 'sent', 'unknown', 'review', 'succeeded',
        'failed')),
    ADD COLUMN "claimedAt"    TIMESTAMP(3),
    ADD COLUMN "submittedAt"  TIMESTAMP(3),
    ADD COLUMN "submissions"  INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN "resolvedById" TEXT,
    ADD COLUMN "resolvedAt"   TIMESTAMP(3),
    ADD COLUMN "resolution"   TEXT,
    ADD COLUMN "alertedAt"    TIMESTAMP(3);
ALTER TABLE "FiscalDocument"
    ADD CONSTRAINT "FiscalDocument_claim_check" CHECK ((status = 'sent') = ("claimedAt" IS NOT NULL));

-- One live operation of each kind per owner: sent and review are live too.
DROP INDEX "FiscalDocument_live_op_key";
CREATE UNIQUE INDEX "FiscalDocument_live_op_key" ON "FiscalDocument" ("organizationId", "submissionId", operation)
    WHERE status IN ('pending', 'sent', 'unknown', 'review');
DROP INDEX "FiscalDocument_sale_live_op_key";
CREATE UNIQUE INDEX "FiscalDocument_sale_live_op_key" ON "FiscalDocument" ("organizationId", "salesOrderId", operation)
    WHERE status IN ('pending', 'sent', 'unknown', 'review') AND "salesOrderId" IS NOT NULL;
DROP INDEX "FiscalDocument_open_idx";
CREATE INDEX "FiscalDocument_open_idx" ON "FiscalDocument" (status) WHERE status IN ('pending', 'sent', 'unknown',
    'review');
