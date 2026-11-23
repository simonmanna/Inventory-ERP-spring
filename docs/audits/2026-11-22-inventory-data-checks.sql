-- Inventory hardening R0: historical data assessment (read-only).
--
-- Companion of docs/audits/2026-11-22-inventory-review.md. Every block is a single SELECT: nothing here writes,
-- locks or repairs anything. Each block names the defect, what one row means and a suggested MANUAL remedy. A remedy
-- is a proposal for an owner-approved repair plan, never a script to run: no automatic repair of historical data.
--
-- How to run (owner's OK required before any live database):
--   psql "<connection>" -v ON_ERROR_STOP=1 -f docs/audits/2026-11-22-inventory-data-checks.sql
-- Run as a role that bypasses row-level security (the schema owner) to see every organisation, or as the API role
-- after SET app.org_id = '<organization id>' to see one. Every query returns "organizationId" first; add
-- AND x."organizationId" = '<id>' to narrow one down.
--
-- The live, per-organisation counterpart of the quantity and value invariants is GET /api/v1/inventory/reports/integrity
-- (inventory_valuation:read) and the daily integrity monitor (InventoryException kinds integrity_*).

BEGIN TRANSACTION READ ONLY;

-- ---------------------------------------------------------------------------------------------------------------
-- (a) P0-1 Rental fee invoices that issued stock.
-- A row = one stock ledger row written by the POS stock posting (pos_invoice / pos_order) for a product that sits on
-- a rental fee line (OrderItem.rentalFeeType set: RENT / LATE / DAMAGE / EXTEND) of the same order. A fee is a charge,
-- not goods: the rented item (or the fee product) left stock a second time and COGS was posted for it.
-- Remedy (manual): for each row, confirm the item is physically still in stock (or back from the renter), then
-- reverse that ledger row with a counted adjustment at the row's unit cost and the original location (reason
-- "rental fee issued stock, R0 finding"), and reverse its COGS journal. Record the ledgerCode in the repair log.
SELECT l."organizationId", o.id AS "orderId", o."orderNumber", o."transactionKind", oi.id AS "orderItemId",
       oi."rentalFeeType", l."ledgerCode", l."referenceType", l."referenceId", l."productId", l."locationId",
       l."quantityChange", l."unitCost", l."totalValue", l."createdAt"
  FROM "InventoryLedger" l
  JOIN "Order" o ON o."organizationId" = l."organizationId"
       AND ((l."referenceType" = 'pos_invoice' AND l."referenceId" = o."invoiceId")
         OR (l."referenceType" = 'pos_order' AND l."referenceId" = o.id))
  JOIN "OrderItem" oi ON oi."orderId" = o.id AND oi."productId" = l."productId" AND oi."rentalFeeType" IS NOT NULL
 WHERE l."quantityChange" < 0
 ORDER BY l."organizationId", l."createdAt";

-- (a2) Summary per organisation and fee type (how big the problem is).
SELECT l."organizationId", oi."rentalFeeType", count(DISTINCT o.id) AS orders, count(*) AS "ledgerRows",
       -sum(l."quantityChange") AS "unitsIssued", sum(l."totalValue") AS "valueIssued"
  FROM "InventoryLedger" l
  JOIN "Order" o ON o."organizationId" = l."organizationId"
       AND ((l."referenceType" = 'pos_invoice' AND l."referenceId" = o."invoiceId")
         OR (l."referenceType" = 'pos_order' AND l."referenceId" = o.id))
  JOIN "OrderItem" oi ON oi."orderId" = o.id AND oi."productId" = l."productId" AND oi."rentalFeeType" IS NOT NULL
 WHERE l."quantityChange" < 0
 GROUP BY 1, 2
 ORDER BY 1, 2;

-- ---------------------------------------------------------------------------------------------------------------
-- (b) P1-1 Serials stuck "issued" after a return or a cancel.
-- (b1) A row = a serial still marked issued whose issue movement (its own ledger row) was reversed afterwards
-- (reversal_in / return_in rows under <issue referenceType>_reversal for the same source and product). The unit came
-- back as a quantity but the serial was not put back in stock.
-- Remedy (manual): check the unit physically; if it is on the shelf, set the serial back to in_stock at the
-- reversal row's location through an owner-approved repair (status, locationId, issuedAt/issueRef cleared), leaving
-- the quantities alone (they are already right).
SELECT s."organizationId", s.id AS "serialId", s."serialNumber", s."productId", s."locationId", s."issueRef",
       iss."ledgerCode" AS "issueLedgerCode", iss."referenceType", iss."referenceId",
       rv."ledgerCode" AS "reversalLedgerCode", rv.type::text AS "reversalType", rv."locationId" AS "reversalLocationId",
       rv."createdAt" AS "reversedAt"
  FROM "InventorySerial" s
  JOIN "InventoryLedger" iss ON iss."organizationId" = s."organizationId" AND iss."serialId" = s.id
       AND iss."quantityChange" < 0
  JOIN "InventoryLedger" rv ON rv."organizationId" = s."organizationId" AND rv."productId" = s."productId"
       AND rv.type::text IN ('reversal_in', 'return_in') AND rv."quantityChange" > 0
       AND rv."referenceType" = iss."referenceType" || '_reversal' AND rv."referenceId" = iss."referenceId"
       AND rv."createdAt" >= iss."createdAt"
 WHERE s.status = 'issued'
 ORDER BY s."organizationId", s."serialNumber";

-- (b2) A row = a return / reversal receipt of a serial-tracked product that restored no serial: no InventorySerial
-- carries its receipt reference. The quantity came back without its identity (the serial stays issued, and the unit
-- now counts as an untracked remainder of the quant).
-- Remedy (manual): match the returned units to their original issue rows (same referenceId family), confirm the
-- serial numbers physically and restore them to in_stock at that location in an owner-approved repair.
SELECT l."organizationId", l."ledgerCode", l.type::text AS type, l."referenceType", l."referenceId", l."productId",
       p.code, p.name, l."locationId", l."quantityChange", l."createdAt"
  FROM "InventoryLedger" l
  JOIN "Product" p ON p.id = l."productId" AND p."serialTracking" = true
 WHERE l.type::text IN ('return_in', 'reversal_in') AND l."quantityChange" > 0 AND l."serialId" IS NULL
   AND NOT EXISTS (SELECT 1 FROM "InventorySerial" s
                    WHERE s."organizationId" = l."organizationId" AND s."productId" = l."productId"
                      AND s."receiptRef" = COALESCE(l."referenceType", 'receipt') || ':'
                                           || COALESCE(l."referenceId", l."ledgerCode"))
 ORDER BY l."organizationId", l."createdAt";

-- ---------------------------------------------------------------------------------------------------------------
-- (c) P1-1 Tracked returns that created new lots.
-- A row = a return / reversal receipt of a batch-tracked product that landed in a lot created by that same receipt
-- (lot created within 5 seconds of the ledger row), instead of going back into the lot it was issued from. The
-- original lot's expiry and cost history is lost for those units; FEFO picks them in the wrong order.
-- Remedy (manual): for each row find the issue rows of the same source (referenceType without "_reversal", same
-- referenceId) and their batchId; in an owner-approved repair move the quantity back into the original lot (or, if
-- that lot is closed, re-date the new lot's expiryDate / unitCost from the original) and record both ledger codes.
SELECT l."organizationId", l."ledgerCode", l.type::text AS type, l."referenceType", l."referenceId", l."productId",
       p.code, p.name, l."locationId", l."quantityChange", b.id AS "batchId", b."batchNumber", b."expiryDate",
       b."unitCost" AS "batchUnitCost", b."createdAt" AS "batchCreatedAt", l."createdAt"
  FROM "InventoryLedger" l
  JOIN "Product" p ON p.id = l."productId" AND p."batchTracking" = true
  JOIN "InventoryBatch" b ON b.id = l."batchId"
 WHERE l.type::text IN ('return_in', 'reversal_in') AND l."quantityChange" > 0
   AND b."createdAt" BETWEEN l."createdAt" - interval '5 seconds' AND l."createdAt" + interval '5 seconds'
 ORDER BY l."organizationId", l."createdAt";

-- ---------------------------------------------------------------------------------------------------------------
-- (d) P1 STANDARD-costed products that received stock without an active standard.
-- A row = a STANDARD product with inbound ledger rows but no ProductStandardCost in status 'active'. Its receipts
-- entered at the actual cost instead of the standard (no PPV), while the valuation prices the quant at
-- Product.costPrice: valuation (qty × costPrice) and the stock ledger value (Σ signed totalValue) disagree by
-- "difference", and so does the GL (it follows the ledger).
-- Remedy (manual): approve and activate a standard for the product (the activation revalues the quant and posts the
-- difference), or switch the product to AVCO with an owner-approved revaluation; then re-run the integrity report
-- (valuation_vs_subledger) for the product.
SELECT p."organizationId", p.id AS "productId", p.code, p.name, p."costPrice",
       q.qty AS "onHand", q.qty * COALESCE(p."costPrice", 0) AS "valuationAtCostPrice", lg.value AS "ledgerValue",
       q.qty * COALESCE(p."costPrice", 0) - lg.value AS difference, lg.receipts AS "inboundRows"
  FROM "Product" p
  JOIN (SELECT "productId", sum(quantity) AS qty FROM "StockItem" GROUP BY 1) q ON q."productId" = p.id
  JOIN (SELECT "productId",
               sum(CASE WHEN "quantityChange" >= 0 THEN "totalValue" ELSE -"totalValue" END) AS value,
               count(*) FILTER (WHERE "quantityChange" > 0 AND type::text IN ('receipt', 'opening_balance',
                   'adjustment_in', 'return_in', 'production_output')) AS receipts
          FROM "InventoryLedger" GROUP BY 1) lg ON lg."productId" = p.id
 WHERE p."costingMethod"::text = 'STANDARD' AND lg.receipts > 0
   AND NOT EXISTS (SELECT 1 FROM "ProductStandardCost" sc
                    WHERE sc."organizationId" = p."organizationId" AND sc."productId" = p.id AND sc.status = 'active')
 ORDER BY p."organizationId", abs(q.qty * COALESCE(p."costPrice", 0) - lg.value) DESC;

-- ---------------------------------------------------------------------------------------------------------------
-- (e) P1-13 Duplicate offline transfer receipts.
-- (e1) A row = one transfer + receipt payload that was applied from more than one resolved
-- offline_awaiting_dispatch exception (the same device receipt applied twice: two drains raced, or a retry after a
-- crash). Each extra application moved the received quantity a second time.
-- Remedy (manual): compare the transfer's StockTransferReceipt rows and transit ledger with the device receipt;
-- reverse the surplus receipt through an owner-approved counted adjustment at the destination (and back into
-- transit if still open), then mark the duplicate exception with a resolution note.
SELECT e."organizationId", e.payload->>'transferId' AS "transferId", t."transferCode", count(*) AS applications,
       min(e."resolvedAt") AS "firstResolvedAt", max(e."resolvedAt") AS "lastResolvedAt",
       array_agg(e.id ORDER BY e."resolvedAt") AS "exceptionIds"
  FROM "InventoryException" e
  LEFT JOIN "StockTransfer" t ON t.id = e.payload->>'transferId'
 WHERE e.kind = 'offline_awaiting_dispatch' AND e.status = 'resolved'
 GROUP BY e."organizationId", e.payload->>'transferId', t."transferCode", e.payload->'receipt'
HAVING count(*) > 1
 ORDER BY 1, 3;

-- (e2) A row = two transfer receipt movements (transfer_in at the destination) of the same transfer, product,
-- location and quantity written within 5 seconds of each other: the signature of a receipt applied twice.
-- Remedy (manual): as (e1); confirm against the device's receipt and the StockTransferReceipt lines first, since two
-- genuine partial receipts of the same quantity can also look like this.
SELECT a."organizationId", a."referenceId" AS "transferCode", a."productId", a."locationId", a."quantityChange",
       a."ledgerCode" AS "firstLedgerCode", b."ledgerCode" AS "secondLedgerCode", a."createdAt" AS "firstAt",
       b."createdAt" AS "secondAt"
  FROM "InventoryLedger" a
  JOIN "InventoryLedger" b ON b."organizationId" = a."organizationId" AND b."referenceType" = a."referenceType"
       AND b."referenceId" = a."referenceId" AND b."productId" = a."productId" AND b."locationId" = a."locationId"
       AND b.type = a.type AND b."quantityChange" = a."quantityChange" AND b.id <> a.id
       AND b."ledgerCode" <> a."ledgerCode"
       AND b."createdAt" BETWEEN a."createdAt" AND a."createdAt" + interval '5 seconds'
       AND (b."createdAt", b.id) > (a."createdAt", a.id)
 WHERE a."referenceType" = 'stock_transfer_receipt' AND a.type::text = 'transfer_in'
 ORDER BY a."organizationId", a."createdAt";

-- ---------------------------------------------------------------------------------------------------------------
-- (f) Orphan reservations and open transit balances.
-- (f1) A row = an active reservation whose source is missing, closed or cancelled (invoice cancelled / refunded,
-- sales order done / cancelled / deleted, production order completed / cancelled, processing job cancelled /
-- deleted), or whose product or location no longer exists or is inactive. It holds stock back from ATP for nothing.
-- Remedy (manual): release it (POST /inventory/reservations/release with its sourceType / sourceId) after checking
-- the source; no stock moves.
SELECT r."organizationId", r.id AS "reservationId", r."sourceType", r."sourceId", r."productId", r."locationId",
       r.quantity, r."createdAt",
       CASE WHEN p.id IS NULL OR p."deletedAt" IS NOT NULL THEN 'product missing'
            WHEN loc.id IS NULL OR loc."isActive" = false OR loc."deletedAt" IS NOT NULL THEN 'location missing'
            ELSE 'source closed or missing' END AS reason
  FROM "StockReservation" r
  LEFT JOIN "Product" p ON p.id = r."productId"
  LEFT JOIN "InventoryLocation" loc ON loc.id = r."locationId"
 WHERE r.status = 'active' AND (
       p.id IS NULL OR p."deletedAt" IS NOT NULL OR loc.id IS NULL OR loc."isActive" = false
    OR loc."deletedAt" IS NOT NULL
    OR (r."sourceType" = 'invoice' AND NOT EXISTS (SELECT 1 FROM "Invoice" i WHERE i.id = r."sourceId"
            AND i.status::text NOT IN ('cancelled', 'refunded')))
    OR (r."sourceType" = 'sales_order_line' AND NOT EXISTS (SELECT 1 FROM "SalesOrderLine" sl
            JOIN "SalesOrder" so ON so.id = sl."orderId"
            WHERE sl.id = r."sourceId" AND so.status NOT IN ('done', 'cancelled') AND so."deletedAt" IS NULL))
    OR (r."sourceType" = 'production_order' AND NOT EXISTS (SELECT 1 FROM "ProductionOrder" po
            WHERE po.id = r."sourceId" AND po.status::text NOT IN ('completed', 'cancelled')))
    OR (r."sourceType" = 'processing_job_input' AND NOT EXISTS (SELECT 1 FROM "ProcessingJobInput" pi
            JOIN "ProcessingJob" pj ON pj.id = pi."jobId"
            WHERE pi.id = r."sourceId" AND pj."cancelledAt" IS NULL AND pj."deletedAt" IS NULL)))
 ORDER BY r."organizationId", r."createdAt";

-- (f2) A row = a product whose quantity at the transit location(s) differs from what open transit transfers still
-- have in transit (dispatched − received − damaged − short − recalled over transfers in_transit /
-- partially_received). Stock stuck in transit with no open document, or an open document with no stock behind it.
-- Remedy (manual): for stock with no open document, find the transfer from the transit-location ledger rows
-- (referenceId = transfer code) and receive, write off or recall it through the transfer; for an open document with
-- no stock, check its receipts and close it with an owner-approved correction.
WITH op AS (
    SELECT i."organizationId", i."productId", COALESCE(i."variantId", '') AS vk,
           sum(i."qtyDispatched" - i."qtyReceived" - i."qtyDamaged" - i."qtyShort" - i."qtyRecalled") AS open_qty
      FROM "StockTransferItem" i JOIN "StockTransfer" t ON t.id = i."transferId"
     WHERE t.mode = 'transit' AND t.status::text IN ('in_transit', 'partially_received')
     GROUP BY 1, 2, 3),
tq AS (
    SELECT s."organizationId", s."productId", s."variantKey" AS vk, sum(s.quantity) AS qty
      FROM "StockItem" s JOIN "InventoryLocation" l ON l.id = s."locationId"
     WHERE l.type::text = 'transit'
     GROUP BY 1, 2, 3)
SELECT COALESCE(op."organizationId", tq."organizationId") AS "organizationId",
       COALESCE(op."productId", tq."productId") AS "productId", COALESCE(op.vk, tq.vk) AS "variantKey",
       COALESCE(op.open_qty, 0) AS "openInTransit", COALESCE(tq.qty, 0) AS "transitLocationQty",
       COALESCE(op.open_qty, 0) - COALESCE(tq.qty, 0) AS difference
  FROM op FULL JOIN tq ON tq."organizationId" = op."organizationId" AND tq."productId" = op."productId"
       AND tq.vk = op.vk
 WHERE abs(COALESCE(op.open_qty, 0) - COALESCE(tq.qty, 0)) > 0.000001
 ORDER BY 1, 2;

-- (f3) A row = a transit transfer still open more than 30 days after dispatch (an operational backlog, not
-- necessarily corruption). Remedy (manual): receive, write off or recall it with the branch.
SELECT t."organizationId", t.id AS "transferId", t."transferCode", t.status::text AS status, t."dispatchedAt",
       sum(i."qtyDispatched" - i."qtyReceived" - i."qtyDamaged" - i."qtyShort" - i."qtyRecalled") AS "openQty"
  FROM "StockTransfer" t JOIN "StockTransferItem" i ON i."transferId" = t.id
 WHERE t.mode = 'transit' AND t.status::text IN ('in_transit', 'partially_received')
   AND t."dispatchedAt" < now() - interval '30 days'
 GROUP BY 1, 2, 3, 4, 5
 ORDER BY t."organizationId", t."dispatchedAt";

-- ---------------------------------------------------------------------------------------------------------------
-- (g) P1 Branch-crossing reversals.
-- A row = a reversal movement (reversal_in / reversal_out) performed by a user whose data scope (UserDataScope: a
-- list of branches and/or locations; no rows = unrestricted) does not include the movement's location, directly or
-- through the location's branch. The reversal moved stock outside the user's branch.
-- Remedy (manual): confirm with the branch whether the reversal was legitimate (it may have been requested by an
-- authorised person); if not, raise it with the owner. Do not re-reverse automatically: the quantities are
-- consistent, only the authorisation is in question.
SELECT l."organizationId", l."ledgerCode", l.type::text AS type, l."referenceType", l."referenceId", l."productId",
       l."locationId", loc.name AS "locationName", loc."branchId", l."performedBy", u.email AS "performedByEmail",
       l."quantityChange", l."createdAt"
  FROM "InventoryLedger" l
  JOIN "InventoryLocation" loc ON loc.id = l."locationId"
  LEFT JOIN "User" u ON u.id = l."performedBy"
 WHERE l.type::text IN ('reversal_in', 'reversal_out') AND l."performedBy" IS NOT NULL
   AND EXISTS (SELECT 1 FROM "UserDataScope" ds
                WHERE ds."organizationId" = l."organizationId" AND ds."userId" = l."performedBy")
   AND NOT EXISTS (SELECT 1 FROM "UserDataScope" ds
                    WHERE ds."organizationId" = l."organizationId" AND ds."userId" = l."performedBy"
                      AND (ds."locationId" = l."locationId" OR ds."branchId" = loc."branchId"))
 ORDER BY l."organizationId", l."createdAt";

-- ---------------------------------------------------------------------------------------------------------------
-- (h) P1 Cancelled orders whose pos_order stock issue was never reversed.
-- A row = a POS order in status cancelled with stock issued against it before invoicing (referenceType pos_order,
-- the at_confirmation / at_fulfillment timings) whose issue rows are not netted back by reversal rows
-- (pos_order_reversal). The goods are counted as sold although the order was cancelled.
-- Remedy (manual): check whether the goods were consumed (kitchen waste) or are back on the shelf; for goods back on
-- the shelf, reverse the order's pos_order rows at their original cost and lots (R4 adds this as the cancel
-- behaviour; for history use an owner-approved reversal per order), for consumed goods record waste instead.
SELECT o."organizationId", o.id AS "orderId", o."orderNumber", o."cancelledAt",
       sum(l."quantityChange") FILTER (WHERE l."referenceType" = 'pos_order') AS "issuedQty",
       COALESCE(sum(l."quantityChange") FILTER (WHERE l."referenceType" = 'pos_order_reversal'), 0) AS "reversedQty",
       sum(l."totalValue") FILTER (WHERE l."referenceType" = 'pos_order') AS "issuedValue",
       count(DISTINCT l."productId") AS products
  FROM "Order" o
  JOIN "InventoryLedger" l ON l."organizationId" = o."organizationId" AND l."referenceId" = o.id
       AND l."referenceType" IN ('pos_order', 'pos_order_reversal')
 WHERE o.status::text = 'cancelled'
 GROUP BY o."organizationId", o.id, o."orderNumber", o."cancelledAt"
HAVING sum(l."quantityChange") < -0.000001
 ORDER BY o."organizationId", o."cancelledAt";

ROLLBACK;
