-- ADR-S094 C2: returns linked to the delivery they reverse, quantity credited without a return, and the financial
-- outcome of a sale (§4.4, §4.5, §7; AD-9, AD-19, AD-20 approved 2026-11-28).

-- §4.5: the quantities that are no longer billable. W = credited without a return (goods kept or lost), through an
-- approved claim (AD-20). R in the billable formula = goods returned and settled by a credit (credit note or refund);
-- goods returned for a replacement or without a credit stay billable, so they are kept apart from qtyReturned (all
-- goods physically back).
ALTER TABLE "SalesOrderLine"
    ADD COLUMN "qtyWrittenOff" NUMERIC(20, 6) NOT NULL DEFAULT 0,
    ADD COLUMN "qtyReturnCredited" NUMERIC(20, 6) NOT NULL DEFAULT 0;
ALTER TABLE "SalesOrderLine" ADD CONSTRAINT "SalesOrderLine_qtyWrittenOff_check" CHECK ("qtyWrittenOff" >= 0);
ALTER TABLE "SalesOrderLine" ADD CONSTRAINT "SalesOrderLine_qtyReturnCredited_check" CHECK ("qtyReturnCredited" >= 0
    AND "qtyReturnCredited" <= "qtyReturned");

-- §4.4: what the sale invoiced, credited, received, refunded and kept as customer credit. Recomputed in the
-- transaction of every sales action that changes it, after invoicing events (receipts, credit notes) and nightly.
ALTER TABLE "SalesOrder"
    ADD COLUMN "amountInvoiced" NUMERIC(20, 6) NOT NULL DEFAULT 0,
    ADD COLUMN "amountCredited" NUMERIC(20, 6) NOT NULL DEFAULT 0,
    ADD COLUMN "amountWrittenOff" NUMERIC(20, 6) NOT NULL DEFAULT 0,
    ADD COLUMN "amountReceived" NUMERIC(20, 6) NOT NULL DEFAULT 0,
    ADD COLUMN "amountRefunded" NUMERIC(20, 6) NOT NULL DEFAULT 0,
    ADD COLUMN "amountRetainedAsCredit" NUMERIC(20, 6) NOT NULL DEFAULT 0,
    ADD COLUMN "amountOutstanding" NUMERIC(20, 6) NOT NULL DEFAULT 0,
    ADD COLUMN "advanceHeld" NUMERIC(20, 6) NOT NULL DEFAULT 0,
    ADD COLUMN "creditKept" NUMERIC(20, 6) NOT NULL DEFAULT 0,
    ADD COLUMN "paymentStatus" TEXT NOT NULL DEFAULT 'not_invoiced',
    ADD COLUMN "creditStatus" TEXT NOT NULL DEFAULT 'none',
    ADD COLUMN "settlementStatus" TEXT NOT NULL DEFAULT 'open',
    ADD COLUMN "outcomeAt" TIMESTAMP(3);

ALTER TABLE "SalesOrder" ADD CONSTRAINT "SalesOrder_paymentStatus_check" CHECK ("paymentStatus" IN ('no_charge',
    'not_invoiced', 'unpaid', 'partially_paid', 'paid', 'credit_balance', 'refunded', 'credited', 'written_off'));
ALTER TABLE "SalesOrder" ADD CONSTRAINT "SalesOrder_creditStatus_check" CHECK ("creditStatus" IN ('none', 'partial',
    'full'));
ALTER TABLE "SalesOrder" ADD CONSTRAINT "SalesOrder_settlementStatus_check" CHECK ("settlementStatus" IN ('open',
    'settled'));
ALTER TABLE "SalesOrder" ADD CONSTRAINT "SalesOrder_creditKept_check" CHECK ("creditKept" >= 0);

CREATE INDEX "SalesOrder_settlement_idx" ON "SalesOrder" ("organizationId", "settlementStatus", "paymentStatus")
    WHERE "deletedAt" IS NULL;

-- §7.2: a return line names the delivery (load line) it reverses, the serial numbers that came back and the condition
-- found at inspection.
ALTER TABLE "SalesReturnLine"
    ADD COLUMN "loadLineId" TEXT,
    ADD COLUMN "serialNumbers" TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN condition TEXT;
ALTER TABLE "SalesReturnLine" ADD CONSTRAINT "SalesReturnLine_condition_check" CHECK (condition IS NULL
    OR condition IN ('usable', 'damaged', 'defective'));
CREATE INDEX "SalesReturnLine_loadLine_idx" ON "SalesReturnLine" ("loadLineId") WHERE "loadLineId" IS NOT NULL;

-- Backfill (demo data; recomputed from the returns already recorded):
-- qtyReturned = goods physically back and kept (received − rejected); qtyReturnCredited = those settled by a credit;
-- W = quantity of closed claims without goods that were credited. Their billable decisions were recorded as manual
-- decisions, which keep applying.
UPDATE "SalesOrderLine" l
   SET "qtyReturned" = COALESCE((SELECT sum(rl."receivedQty" - COALESCE(rl."rejectedQty", 0)) FROM "SalesReturnLine" rl
                                   JOIN "SalesReturn" r ON r.id = rl."returnId"
                                  WHERE rl."orderLineId" = l.id AND r."physicalReturn"
                                    AND r.status IN ('received', 'inspected', 'closed')), 0),
       "qtyReturnCredited" = COALESCE((SELECT sum(rl."receivedQty" - COALESCE(rl."rejectedQty", 0)) FROM "SalesReturnLine" rl
                                         JOIN "SalesReturn" r ON r.id = rl."returnId"
                                        WHERE rl."orderLineId" = l.id AND r."physicalReturn" AND r.status = 'closed'
                                          AND r.outcome IN ('credit_note', 'refund')), 0),
       "qtyWrittenOff" = COALESCE((SELECT sum(rl.quantity) FROM "SalesReturnLine" rl
                                     JOIN "SalesReturn" r ON r.id = rl."returnId"
                                    WHERE rl."orderLineId" = l.id AND NOT r."physicalReturn" AND r.status = 'closed'
                                      AND r.outcome IN ('credit_note', 'refund')), 0)
 WHERE EXISTS (SELECT 1 FROM "SalesReturnLine" rl WHERE rl."orderLineId" = l.id);

-- AD-20: crediting goods without a return has its own permission. Every role that settles returns keeps the ability.
INSERT INTO "Permission" (id, key, resource, action, "createdAt")
SELECT gen_random_uuid()::text, 'sales:return:write_off', 'sales', 'return', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM "Permission" WHERE key = 'sales:return:write_off');

UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['sales:return:write_off']))
WHERE 'sales:return:approve' = ANY (permissions) AND NOT ('sales:return:write_off' = ANY (permissions));
