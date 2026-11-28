-- =====================================================================================================================
-- Inventory production readiness (IPR) Phase 0, task T06: historical data checks.
--
-- READ-ONLY. Every statement below is a SELECT. Nothing is written, locked for update or repaired.
--
-- Run against a RESTORED COPY of a backup (never the live database without the owner's separate OK), as a role that
-- sees every organisation (the schema owner; the application role is under row-level security and sees nothing outside
-- a tenant scope):
--
--   psql -v ON_ERROR_STOP=1 -X -h <host> -p <port> -U <owner> -d <restored_db> \
--        -f docs/audits/2026-11-27-inventory-data-checks.sql > inventory-data-checks.out
--
-- Optional thresholds (defaults in brackets):
--   -v stale_job_hours=6      stock-posting job pending/processing longer than this is stale        [6]
--   -v transit_days=7         open transit transfer older than this                                  [7]
--   -v x1_window_hours=72     X-1: sale rows written up to this long after a count was submitted      [72]
--   -v lookback_days=366      period-shifted movements: how far back (by ledger createdAt)            [366]
--   -v row_limit=200          rows printed per detail query                                           [200]
--
-- Reading the results. Each query is labelled "-- [ID] title" and prints a banner. Zero rows = no finding.
-- Rows = candidates to review; the "meaning" line under each label says what a row is evidence of. The quantity
-- tolerance is 0.000001 (the integrity report's), value tolerance 0.01. The last query is a per-organisation summary.
-- The same detectors run live (per organisation, report-only) in GET /inventory/reports/integrity → reportOnlyChecks.
-- =====================================================================================================================

\if :{?stale_job_hours} \else \set stale_job_hours 6 \endif
\if :{?transit_days} \else \set transit_days 7 \endif
\if :{?x1_window_hours} \else \set x1_window_hours 72 \endif
\if :{?lookback_days} \else \set lookback_days 366 \endif
\if :{?row_limit} \else \set row_limit 200 \endif

\pset pager off
\pset null '∅'
BEGIN TRANSACTION READ ONLY;

-- ---------------------------------------------------------------------------------------------------------------------
-- [F1] Posted invoice handed to a delivery whose delivery orders are cancelled/closed, stock never issued
-- meaning: a row = (invoice, product) where invoiced − credited > delivered (net fleet_trip issues − put-backs + own
--          sales_invoice issue). The customer was billed for goods that never left stock: on-hand is overstated.
-- ---------------------------------------------------------------------------------------------------------------------
\echo '[F1] posted invoices with an undelivered, never-issued quantity'
WITH inv AS (SELECT d.id, d."organizationId" AS org, d."documentNumber", d.status::text AS status, d."sourceType",
                    d."sourceId", d.data->>'stockIssuedBy' AS issued_by
               FROM "Document" d
              WHERE d."documentType" = 'sales_invoice' AND d.status::text NOT IN ('draft', 'cancelled')
                AND d.data->>'stockIssuedBy' IS NOT NULL AND d."sourceType" IS DISTINCT FROM 'cash_sale'),
     dos AS (SELECT inv.id AS iid, o.id AS oid, o.status FROM inv JOIN "FleetDeliveryOrder" o
                 ON o."organizationId" = inv.org AND o."sourceType" = 'invoice' AND o."sourceId" = inv.id
             UNION
             SELECT inv.id, o.id, o.status FROM inv JOIN "FleetDeliveryOrder" o
                 ON o."organizationId" = inv.org AND o."goodsInvoiceId" = inv.id
             UNION
             SELECT inv.id, o.id, o.status FROM inv JOIN "FleetDeliveryOrder" o
                 ON o."organizationId" = inv.org AND inv."sourceType" = 'sales_order'
                AND o."sourceType" = 'sales_order' AND o."sourceId" = inv."sourceId"
             UNION
             SELECT inv.id, o.id, o.status FROM inv JOIN "FleetDeliveryInvoiceLine" fil
                 ON fil."organizationId" = inv.org AND fil."documentId" = inv.id
               JOIN "FleetDeliveryOrder" o ON o.id = fil."deliveryOrderId"),
     judged AS (SELECT iid, string_agg(DISTINCT status, ',') AS statuses FROM dos GROUP BY iid
                HAVING bool_and(status NOT IN ('open', 'planned', 'in_progress'))
                   AND bool_or(status IN ('cancelled', 'closed'))),
     trips AS (SELECT DISTINCT dos.iid, l.id AS tid, l."organizationId" AS org FROM dos
                 JOIN judged ON judged.iid = dos.iid JOIN "FleetLoad" l ON l."deliveryOrderId" = dos.oid),
     moved AS (SELECT trips.iid, g."productId" AS pid,
                      SUM(CASE WHEN g."referenceType" = 'fleet_trip' THEN -g."quantityChange" ELSE 0 END) AS issued,
                      SUM(CASE WHEN g."referenceType" = 'fleet_trip_return' THEN g."quantityChange" ELSE 0 END)
                          AS put_back
                 FROM trips JOIN "InventoryLedger" g ON g."organizationId" = trips.org
                      AND g."referenceType" IN ('fleet_trip', 'fleet_trip_return') AND g."referenceId" = trips.tid
                GROUP BY 1, 2),
     own AS (SELECT judged.iid, g."productId" AS pid, -SUM(g."quantityChange") AS issued
               FROM judged JOIN inv ON inv.id = judged.iid
               JOIN "InventoryLedger" g ON g."organizationId" = inv.org AND g."referenceType" = 'sales_invoice'
                    AND g."referenceId" = judged.iid
              GROUP BY 1, 2),
     lines AS (SELECT dl."documentId" AS iid, dl."productId" AS pid,
                      SUM(dl.quantity * COALESCE(NULLIF(dl."uomFactor", 0), 1)) AS invoiced
                 FROM judged JOIN "DocumentLine" dl ON dl."documentId" = judged.iid
                 JOIN "Product" p ON p.id = dl."productId" AND p."trackInventory" = true
                      AND p."productType"::text IN ('stockable', 'consumable')
                GROUP BY 1, 2),
     credited AS (SELECT c."reversedDocumentId" AS iid, cl."productId" AS pid,
                         SUM(cl.quantity * COALESCE(NULLIF(cl."uomFactor", 0), 1)) AS qty
                    FROM judged JOIN "Document" c ON c."reversedDocumentId" = judged.iid
                         AND c."documentType" = 'credit_note' AND c.status::text NOT IN ('draft', 'cancelled')
                    JOIN "DocumentLine" cl ON cl."documentId" = c.id
                   GROUP BY 1, 2)
SELECT o.code AS org, inv."documentNumber", inv.status, inv.issued_by AS "stockIssuedBy", inv."sourceType",
       judged.statuses AS "deliveryOrderStatuses", p.code AS product, lines.invoiced,
       COALESCE(credited.qty, 0) AS credited,
       COALESCE(moved.issued, 0) - COALESCE(moved.put_back, 0) + COALESCE(own.issued, 0) AS delivered,
       lines.invoiced - COALESCE(credited.qty, 0) - (COALESCE(moved.issued, 0) - COALESCE(moved.put_back, 0)
           + COALESCE(own.issued, 0)) AS undelivered
  FROM lines JOIN inv ON inv.id = lines.iid JOIN judged ON judged.iid = lines.iid
  JOIN "Organization" o ON o.id = inv.org
  LEFT JOIN moved ON moved.iid = lines.iid AND moved.pid = lines.pid
  LEFT JOIN own ON own.iid = lines.iid AND own.pid = lines.pid
  LEFT JOIN credited ON credited.iid = lines.iid AND credited.pid = lines.pid
  LEFT JOIN "Product" p ON p.id = lines.pid
 WHERE lines.invoiced - COALESCE(credited.qty, 0) - (COALESCE(moved.issued, 0) - COALESCE(moved.put_back, 0)
       + COALESCE(own.issued, 0)) > 0.000001
 ORDER BY o.code, inv."documentNumber", p.code
 LIMIT :row_limit;

-- ---------------------------------------------------------------------------------------------------------------------
-- [F2] Sales returns restocked without any issue to return against
-- meaning: a row = a sales return whose restock rows (sales_return inflows) carry no reversesLedgerId: the legacy path
--          (SalesReturnService.receive → receiveReturn) created stock that no issue of the order took out, or took out
--          through a path the return could not see. Compare "restocked" with what the order really issued.
-- ---------------------------------------------------------------------------------------------------------------------
\echo '[F2] sales returns restocked without a linked issue'
SELECT o.code AS org, r.number AS "return", r.status, r."orderId", p.code AS product,
       SUM(l."quantityChange") AS restocked, MIN(l."createdAt") AS "firstAt"
  FROM "InventoryLedger" l
  JOIN "SalesReturn" r ON r.id = l."referenceId" AND r."organizationId" = l."organizationId"
  JOIN "Organization" o ON o.id = l."organizationId"
  LEFT JOIN "Product" p ON p.id = l."productId"
 WHERE l."referenceType" = 'sales_return' AND l."quantityChange" > 0 AND l."reversesLedgerId" IS NULL
 GROUP BY o.code, r.number, r.status, r."orderId", p.code
 ORDER BY o.code, r.number
 LIMIT :row_limit;

-- ---------------------------------------------------------------------------------------------------------------------
-- [F3] Put-back at proof of delivery plus linked returns (credit note, invoice cancel, sales return) above the
--      quantity the trips issued
-- meaning: a row = (delivery group, product) where goods came back twice: once by the fleet put-back (no link to the
--          issue) and once by a return linked to the trip's issue rows. On-hand is overstated by "excess".
-- ---------------------------------------------------------------------------------------------------------------------
\echo '[F3] delivery put-backs + linked returns above the issued quantity'
WITH o AS (SELECT o.id, o."organizationId" AS org,
                  CASE WHEN o."sourceType" = 'sales_order' THEN 'sales_order:' || o."sourceId"
                       WHEN o."goodsInvoiceId" IS NOT NULL THEN 'invoice:' || o."goodsInvoiceId"
                       WHEN o."sourceType" = 'invoice' THEN 'invoice:' || o."sourceId"
                       ELSE 'delivery_order:' || o.id END AS grp
             FROM "FleetDeliveryOrder" o WHERE o."stockMode" = 'dispatch'),
     trips AS (SELECT o.org, o.grp, l.id AS tid FROM o JOIN "FleetLoad" l ON l."deliveryOrderId" = o.id
                WHERE l."stockIssuedAt" IS NOT NULL),
     iss AS (SELECT trips.org, trips.grp, g.id, g."productId" AS pid, g."quantityChange" AS q
               FROM trips JOIN "InventoryLedger" g ON g."organizationId" = trips.org
                    AND g."referenceType" = 'fleet_trip' AND g."referenceId" = trips.tid
              WHERE g."quantityChange" < 0),
     issued AS (SELECT org, grp, pid, -SUM(q) AS qty FROM iss GROUP BY 1, 2, 3),
     put_back AS (SELECT trips.org, trips.grp, g."productId" AS pid, SUM(g."quantityChange") AS qty
                    FROM trips JOIN "InventoryLedger" g ON g."organizationId" = trips.org
                         AND g."referenceType" = 'fleet_trip_return' AND g."referenceId" = trips.tid
                   GROUP BY 1, 2, 3),
     linked AS (SELECT iss.org, iss.grp, iss.pid, SUM(r."quantityChange" + COALESCE(x.q, 0)) AS qty
                  FROM iss JOIN "InventoryLedger" r ON r."organizationId" = iss.org AND r."reversesLedgerId" = iss.id
                  LEFT JOIN LATERAL (SELECT SUM(y."quantityChange") AS q FROM "InventoryLedger" y
                                      WHERE y."organizationId" = iss.org AND y."reversesLedgerId" = r.id) x ON true
                 GROUP BY 1, 2, 3)
SELECT og.code AS org, issued.grp AS "deliveryGroup", p.code AS product, issued.qty AS issued,
       COALESCE(put_back.qty, 0) AS "putBack", COALESCE(linked.qty, 0) AS "linkedReturns",
       COALESCE(put_back.qty, 0) + COALESCE(linked.qty, 0) - issued.qty AS excess
  FROM issued JOIN "Organization" og ON og.id = issued.org
  LEFT JOIN put_back ON put_back.grp = issued.grp AND put_back.pid = issued.pid
  LEFT JOIN linked ON linked.grp = issued.grp AND linked.pid = issued.pid
  LEFT JOIN "Product" p ON p.id = issued.pid
 WHERE COALESCE(put_back.qty, 0) + COALESCE(linked.qty, 0) > issued.qty + 0.000001
 ORDER BY og.code, 2, p.code
 LIMIT :row_limit;

-- ---------------------------------------------------------------------------------------------------------------------
-- [X-1a] Sales that happened before a count but were written after it was submitted (late POS drain / offline sync)
-- meaning: a row = a POS issue row at the counted location and product, written after the count was submitted (within
--          :x1_window_hours), whose sale time (invoice issueDate, else order createdAt) is before the line's countedAt.
--          The count variance already absorbed this sale, so the late row double-counts it (stock understated).
-- ---------------------------------------------------------------------------------------------------------------------
\echo '[X-1a] POS sales effective before a count, written after its submit'
SELECT o.code AS org, s."countCode", s."countType", s."submittedAt", cl."countedAt", p.code AS product,
       l."referenceType", l."ledgerCode", l."quantityChange", COALESCE(i."issueDate", ord."createdAt") AS "saleAt",
       l."createdAt" AS "writtenAt", l."createdAt" - s."submittedAt" AS "writtenAfterSubmit"
  FROM "InventoryCountSession" s
  JOIN "InventoryCountLine" cl ON cl."sessionId" = s.id
  JOIN "InventoryLedger" l ON l."organizationId" = s."organizationId" AND l."locationId" = s."locationId"
       AND l."productId" = cl."productId" AND l."quantityChange" < 0
       AND l."referenceType" IN ('pos_invoice', 'pos_order', 'pos_invoice_combo', 'menu_recipe', 'pos_invoice_extra')
       AND l."createdAt" > s."submittedAt"
       AND l."createdAt" <= s."submittedAt" + make_interval(hours => :x1_window_hours)
  LEFT JOIN "Invoice" i ON l."referenceType" = 'pos_invoice' AND i.id = l."referenceId"
  LEFT JOIN "Order" ord ON l."referenceType" = 'pos_order' AND ord.id = l."referenceId"
  JOIN "Organization" o ON o.id = s."organizationId"
  LEFT JOIN "Product" p ON p.id = cl."productId"
 WHERE s.status::text = 'submitted' AND s."deletedAt" IS NULL AND s."submittedAt" IS NOT NULL
   AND COALESCE(i."issueDate", ord."createdAt") < COALESCE(cl."countedAt", s."startedAt")
 ORDER BY o.code, s."countCode", p.code, l."createdAt"
 LIMIT :row_limit;

-- ---------------------------------------------------------------------------------------------------------------------
-- [X-1b] Offline stock documents that happened before a count but posted after its submit
-- meaning: a row = an adjustment or transfer recorded offline (occurredAt < countedAt) at the counted location, posted
--          after the count was submitted: the count already saw its effect.
-- ---------------------------------------------------------------------------------------------------------------------
\echo '[X-1b] offline stock documents effective before a count, posted after its submit'
WITH docs AS (SELECT a."organizationId" AS org, 'stock_adjustment' AS kind, a."adjCode" AS code, a."locationId" AS loc,
                     a."occurredAt", a."postedAt"
                FROM "StockAdjustment" a WHERE a."recordedOffline" = true AND a."occurredAt" IS NOT NULL
              UNION ALL
              SELECT t."organizationId", 'stock_transfer', t."transferCode", t."fromLocId", t."occurredAt",
                     COALESCE(t."dispatchedAt", t."postedAt")
                FROM "StockTransfer" t WHERE t."recordedOffline" = true AND t."occurredAt" IS NOT NULL)
SELECT o.code AS org, s."countCode", s."submittedAt", docs.kind, docs.code, docs."occurredAt", docs."postedAt"
  FROM "InventoryCountSession" s
  JOIN docs ON docs.org = s."organizationId" AND docs.loc = s."locationId"
  JOIN "Organization" o ON o.id = s."organizationId"
 WHERE s.status::text = 'submitted' AND s."deletedAt" IS NULL AND s."submittedAt" IS NOT NULL
   AND docs."occurredAt" < s."startedAt" + interval '1 day' AND docs."occurredAt" < s."submittedAt"
   AND docs."postedAt" > s."submittedAt"
   AND EXISTS (SELECT 1 FROM "InventoryCountLine" cl WHERE cl."sessionId" = s.id
                AND docs."occurredAt" < COALESCE(cl."countedAt", s."startedAt"))
 ORDER BY o.code, s."countCode", docs."occurredAt"
 LIMIT :row_limit;

-- ---------------------------------------------------------------------------------------------------------------------
-- [S4] Two count sessions on the same location overlapping in time
-- meaning: a row = a pair of live sessions (not cancelled) on one location whose [startedAt, submittedAt or now]
--          intervals overlap: their variances can double-adjust the same quant.
-- ---------------------------------------------------------------------------------------------------------------------
\echo '[S4] overlapping count sessions on one location'
SELECT o.code AS org, l.name AS location, a."countCode" AS first, a.status::text AS "firstStatus",
       b."countCode" AS second, b.status::text AS "secondStatus", a."startedAt" AS "firstStarted",
       a."submittedAt" AS "firstSubmitted", b."startedAt" AS "secondStarted", b."submittedAt" AS "secondSubmitted"
  FROM "InventoryCountSession" a
  JOIN "InventoryCountSession" b ON b."organizationId" = a."organizationId" AND b."locationId" = a."locationId"
       AND b.id > a.id AND b."deletedAt" IS NULL AND b.status::text <> 'cancelled'
  JOIN "Organization" o ON o.id = a."organizationId"
  LEFT JOIN "InventoryLocation" l ON l.id = a."locationId"
 WHERE a."deletedAt" IS NULL AND a.status::text <> 'cancelled'
   AND a."startedAt" < COALESCE(b."submittedAt", now()) AND b."startedAt" < COALESCE(a."submittedAt", now())
 ORDER BY o.code, l.name, a."startedAt"
 LIMIT :row_limit;

-- ---------------------------------------------------------------------------------------------------------------------
-- [S1] Opening counts on locations that already had stock history
-- meaning: a row = an opening-type count whose location had ledger rows before the count started. Opening variances
--          post to opening equity (retained earnings), so a routine shrinkage booked this way bypasses the P&L.
-- ---------------------------------------------------------------------------------------------------------------------
\echo '[S1] opening counts on locations with earlier ledger history'
SELECT o.code AS org, s."countCode", s.status::text AS status, l.name AS location, s."startedAt",
       (SELECT count(*) FROM "InventoryLedger" g WHERE g."organizationId" = s."organizationId"
          AND g."locationId" = s."locationId" AND g."createdAt" < s."startedAt") AS "earlierLedgerRows"
  FROM "InventoryCountSession" s
  JOIN "Organization" o ON o.id = s."organizationId"
  LEFT JOIN "InventoryLocation" l ON l.id = s."locationId"
 WHERE s."countType"::text = 'opening' AND s."deletedAt" IS NULL AND s.status::text <> 'cancelled'
   AND EXISTS (SELECT 1 FROM "InventoryLedger" g WHERE g."organizationId" = s."organizationId"
                AND g."locationId" = s."locationId" AND g."createdAt" < s."startedAt")
 ORDER BY o.code, s."startedAt"
 LIMIT :row_limit;

-- ---------------------------------------------------------------------------------------------------------------------
-- [S1b] Opening counts used as routine counts (more than one opening count on a location)
-- meaning: a row = a location with 2+ submitted opening counts; every one after the first is a routine count booked
--          against opening equity.
-- ---------------------------------------------------------------------------------------------------------------------
\echo '[S1b] locations with more than one opening count'
SELECT o.code AS org, l.name AS location, count(*) AS "openingCounts", MIN(s."startedAt") AS "first",
       MAX(s."startedAt") AS "last", string_agg(s."countCode", ', ' ORDER BY s."startedAt") AS counts
  FROM "InventoryCountSession" s
  JOIN "Organization" o ON o.id = s."organizationId"
  LEFT JOIN "InventoryLocation" l ON l.id = s."locationId"
 WHERE s."countType"::text = 'opening' AND s."deletedAt" IS NULL AND s.status::text = 'submitted'
 GROUP BY o.code, l.name
HAVING count(*) > 1
 ORDER BY o.code, l.name
 LIMIT :row_limit;

-- ---------------------------------------------------------------------------------------------------------------------
-- [C-1] Serial + lot products: Σ open lots ≠ max(0, on-hand) per cell
-- meaning: a row = a quant of a serial-and-lot product whose lots drifted from the quantity (a serial issue that did
--          not decrement its lot, or a transfer that moved serials without their lots).
-- ---------------------------------------------------------------------------------------------------------------------
\echo '[C-1] serial+lot quants whose lots do not match on-hand'
WITH lots AS (SELECT b."organizationId" AS org, b."productId" AS pid, COALESCE(b."variantId", '') AS vk,
                     b."locationId" AS lid, SUM(b.quantity) AS qty
                FROM "InventoryBatch" b JOIN "Product" p ON p.id = b."productId"
               WHERE b."isActive" = true AND b.quantity > 0 AND p."serialTracking" AND p."batchTracking"
               GROUP BY 1, 2, 3, 4)
SELECT o.code AS org, p.code AS product, l.name AS location, s."variantKey", s.quantity AS "onHand",
       COALESCE(lots.qty, 0) AS "openLots", GREATEST(s.quantity, 0) - COALESCE(lots.qty, 0) AS difference
  FROM "StockItem" s JOIN "Product" p ON p.id = s."productId" AND p."serialTracking" AND p."batchTracking"
  JOIN "Organization" o ON o.id = s."organizationId"
  LEFT JOIN lots ON lots.org = s."organizationId" AND lots.pid = s."productId" AND lots.vk = s."variantKey"
       AND lots.lid = s."locationId"
  LEFT JOIN "InventoryLocation" l ON l.id = s."locationId"
 WHERE abs(GREATEST(s.quantity, 0) - COALESCE(lots.qty, 0)) > 0.000001
 ORDER BY o.code, p.code, l.name
 LIMIT :row_limit;

-- ---------------------------------------------------------------------------------------------------------------------
-- [C-2] Serial-product issue events whose serial rows do not cover the issued quantity (approximation per ledger
--       reference)
-- meaning: a row = (source document, product) of a serial product where the outflow rows that carry a serial cover
--          fewer (or more) units than the event issued. Under the default capture_optional policy fewer is allowed;
--          review against the document lines (more serials than units is always wrong).
-- ---------------------------------------------------------------------------------------------------------------------
\echo '[C-2] serial-product issues whose serial rows differ from the issued quantity'
SELECT o.code AS org, l."referenceType", l."referenceId", p.code AS product,
       -SUM(l."quantityChange") AS issued, count(l."serialId") AS "serialRows",
       count(l."serialId") - (-SUM(l."quantityChange")) AS "serialsMinusIssued"
  FROM "InventoryLedger" l
  JOIN "Product" p ON p.id = l."productId" AND p."serialTracking"
  JOIN "Organization" o ON o.id = l."organizationId"
 WHERE l."quantityChange" < 0 AND l.type::text NOT IN ('transfer_out')
 GROUP BY o.code, l."referenceType", l."referenceId", p.code
HAVING count(l."serialId") <> -SUM(l."quantityChange")
 ORDER BY o.code, abs(count(l."serialId") - (-SUM(l."quantityChange"))) DESC
 LIMIT :row_limit;

-- ---------------------------------------------------------------------------------------------------------------------
-- [C-3a] AVCO quants on hand at a zero running average
-- meaning: a row = stock valued at 0 (or at the product cost-price fallback in the valuation) because receipts came
--          in at zero cost; COGS of later issues is understated.
-- ---------------------------------------------------------------------------------------------------------------------
\echo '[C-3a] AVCO quants with quantity > 0 and runningAverageCost = 0'
SELECT o.code AS org, p.code AS product, l.name AS location, s."variantKey", s.quantity, p."costPrice"
  FROM "StockItem" s JOIN "Product" p ON p.id = s."productId" AND p."costingMethod"::text = 'AVCO'
  JOIN "Organization" o ON o.id = s."organizationId"
  LEFT JOIN "InventoryLocation" l ON l.id = s."locationId"
 WHERE s.quantity > 0 AND COALESCE(s."runningAverageCost", 0) = 0
 ORDER BY o.code, p.code
 LIMIT :row_limit;

-- ---------------------------------------------------------------------------------------------------------------------
-- [C-3b] Zero-cost receipts, by source type
-- meaning: a row = how many receipt-type inflows came in at unit cost 0 per organisation and source type (D3: blank
--          cost should resolve to the current cost; an explicit 0 needs a reason).
-- ---------------------------------------------------------------------------------------------------------------------
\echo '[C-3b] zero-cost receipt rows per source type'
SELECT o.code AS org, l."referenceType", l.type::text AS type, count(*) AS rows, SUM(l."quantityChange") AS quantity,
       MIN(l."createdAt") AS "firstAt", MAX(l."createdAt") AS "lastAt"
  FROM "InventoryLedger" l JOIN "Organization" o ON o.id = l."organizationId"
 WHERE l."quantityChange" > 0 AND l.type::text IN ('receipt', 'opening_balance', 'adjustment_in', 'production_output')
   AND COALESCE(l."unitCost", 0) = 0 AND COALESCE(l."totalValue", 0) = 0 AND l."valuationComponent" IS NULL
 GROUP BY o.code, l."referenceType", l.type
 ORDER BY o.code, count(*) DESC
 LIMIT :row_limit;

-- ---------------------------------------------------------------------------------------------------------------------
-- [GL-2] STANDARD products: production outputs / by-products and quality downgrades where ledger value ≠ journal value
-- meaning: a row = (document, product) of a STANDARD product where Σ signed ledger value differs from Σ product-stamped
--          stock journal lines (dimension inventoryProductId) of the same document by more than 0.01.
-- ---------------------------------------------------------------------------------------------------------------------
\echo '[GL-2] STANDARD production / downgrade: ledger value vs journal value'
WITH lg AS (SELECT l."organizationId" AS org, l."referenceType" AS rt, l."referenceId" AS rid, l."productId" AS pid,
                   SUM(CASE WHEN l."quantityChange" >= 0 THEN COALESCE(l."totalValue", 0)
                            ELSE -COALESCE(l."totalValue", 0) END) AS value
              FROM "InventoryLedger" l JOIN "Product" p ON p.id = l."productId"
             WHERE p."costingMethod"::text = 'STANDARD' AND l."valuationComponent" IS NULL
               AND l."referenceType" IN ('production_order', 'production_stage', 'quality_downgrade', 'quality_lot')
             GROUP BY 1, 2, 3, 4),
     gl AS (SELECT e."organizationId" AS org, e."sourceId" AS rid, jl.dimensions->>'inventoryProductId' AS pid,
                   SUM(COALESCE(jl."baseDebit", 0) - COALESCE(jl."baseCredit", 0)) AS value
              FROM "JournalEntry" e JOIN "JournalLine" jl ON jl."journalEntryId" = e.id
             WHERE e.status::text <> 'draft' AND jl.dimensions ? 'inventoryProductId'
               AND e."sourceId" IN (SELECT rid FROM lg)
             GROUP BY 1, 2, 3)
SELECT o.code AS org, lg.rt AS "referenceType", lg.rid AS "referenceId", p.code AS product, lg.value AS "ledgerValue",
       COALESCE(gl.value, 0) AS "journalValue", lg.value - COALESCE(gl.value, 0) AS difference
  FROM lg JOIN "Organization" o ON o.id = lg.org
  LEFT JOIN gl ON gl.org = lg.org AND gl.rid = lg.rid AND gl.pid = lg.pid
  LEFT JOIN "Product" p ON p.id = lg.pid
 WHERE abs(lg.value - COALESCE(gl.value, 0)) > 0.01
 ORDER BY o.code, abs(lg.value - COALESCE(gl.value, 0)) DESC
 LIMIT :row_limit;

-- ---------------------------------------------------------------------------------------------------------------------
-- [GL-1] GRNI lines in a non-base currency
-- meaning: a row = per organisation, the GRNI (grni_accrued) lines whose document amount differs from the base amount.
--          GRNI reconciliation and the GL tie-out that sum debit − credit misstate these by "currencyGap".
-- ---------------------------------------------------------------------------------------------------------------------
\echo '[GL-1] GRNI journal lines whose amount differs from the base amount'
SELECT o.code AS org, a.code AS account, count(*) AS lines,
       SUM(jl.debit - jl.credit) AS "documentAmount",
       SUM(COALESCE(jl."baseDebit", 0) - COALESCE(jl."baseCredit", 0)) AS "baseAmount",
       SUM(jl.debit - jl.credit) - SUM(COALESCE(jl."baseDebit", 0) - COALESCE(jl."baseCredit", 0)) AS "currencyGap"
  FROM "AccountMapping" m
  JOIN "JournalLine" jl ON jl."accountId" = m."accountId" AND jl."organizationId" = m."organizationId"
  JOIN "JournalEntry" e ON e.id = jl."journalEntryId" AND e.status::text <> 'draft'
  JOIN "Organization" o ON o.id = m."organizationId"
  LEFT JOIN "Account" a ON a.id = m."accountId"
 WHERE m.key = 'grni_accrued'
   AND (jl.debit <> COALESCE(jl."baseDebit", jl.debit) OR jl.credit <> COALESCE(jl."baseCredit", jl.credit))
 GROUP BY o.code, a.code
 ORDER BY o.code
 LIMIT :row_limit;

-- =====================================================================================================================
-- The Phase 0 integrity detectors (0b) as SQL, all organisations. Same definitions as reportOnlyChecks of
-- GET /inventory/reports/integrity (InventoryIntegrityService).
-- =====================================================================================================================

\echo '[0b negative_quants] StockItem.quantity < 0'
SELECT o.code AS org, p.code AS product, l.name AS location, s."variantKey", s.quantity
  FROM "StockItem" s JOIN "Organization" o ON o.id = s."organizationId"
  LEFT JOIN "Product" p ON p.id = s."productId" LEFT JOIN "InventoryLocation" l ON l.id = s."locationId"
 WHERE s.quantity < -0.000001
 ORDER BY o.code, s.quantity
 LIMIT :row_limit;

-- meaning: an issued serial whose (latest) issuing row was reversed by ≥ 1 unit net: the unit is back in stock but the
--          serial still says issued (it cannot be sold or counted by serial).
\echo '[0b issued_serial_located] issued serials whose issue row came back'
WITH iss AS (SELECT DISTINCT ON (l."serialId") l."serialId" AS sid, l.id AS lid, l."organizationId" AS org,
                    l."ledgerCode"
               FROM "InventoryLedger" l JOIN "InventorySerial" sr ON sr.id = l."serialId" AND sr.status = 'issued'
              WHERE l."quantityChange" < 0
              ORDER BY l."serialId", l."createdAt" DESC, l.seq DESC),
     back AS (SELECT iss.lid, SUM(r."quantityChange" + COALESCE(x.q, 0)) AS qty
                FROM iss JOIN "InventoryLedger" r ON r."organizationId" = iss.org AND r."reversesLedgerId" = iss.lid
                LEFT JOIN LATERAL (SELECT SUM(y."quantityChange") AS q FROM "InventoryLedger" y
                                    WHERE y."organizationId" = iss.org AND y."reversesLedgerId" = r.id) x ON true
               GROUP BY 1)
SELECT o.code AS org, sr."serialNumber", p.code AS product, iss."ledgerCode" AS "issueLedgerCode", back.qty AS returned
  FROM iss JOIN back ON back.lid = iss.lid JOIN "InventorySerial" sr ON sr.id = iss.sid
  JOIN "Organization" o ON o.id = iss.org LEFT JOIN "Product" p ON p.id = sr."productId"
 WHERE back.qty >= 1 - 0.000001
 ORDER BY o.code, p.code, sr."serialNumber"
 LIMIT :row_limit;

-- meaning: a ledger row reversed (net) by more than its own quantity: returned more than issued, or reversed more than
--          received.
\echo '[0b over_returned] rows reversed beyond their quantity'
WITH r AS (SELECT r.id, r."organizationId" AS org, r."reversesLedgerId" AS oid, r."quantityChange" AS q
             FROM "InventoryLedger" r WHERE r."reversesLedgerId" IS NOT NULL AND r."quantityChange" <> 0),
     rr AS (SELECT oid, SUM(q) AS q FROM r GROUP BY oid),
     net AS (SELECT r.org, r.oid, SUM(r.q + COALESCE(rr.q, 0)) AS back FROM r LEFT JOIN rr ON rr.oid = r.id
              GROUP BY 1, 2)
SELECT o.code AS org, g."ledgerCode", p.code AS product, g."referenceType", g."referenceId", g."quantityChange",
       net.back AS reversed, abs(net.back) - abs(g."quantityChange") AS excess
  FROM net JOIN "InventoryLedger" g ON g.id = net.oid AND g."organizationId" = net.org
  JOIN "Organization" o ON o.id = net.org LEFT JOIN "Product" p ON p.id = g."productId"
 WHERE g."quantityChange" <> 0 AND sign(net.back) = -sign(g."quantityChange")
   AND abs(net.back) > abs(g."quantityChange") + 0.000001
 ORDER BY o.code, abs(net.back) - abs(g."quantityChange") DESC
 LIMIT :row_limit;

-- meaning (informational): the scale of the P1 1d gap — restock / undo rows that do not link what they undo.
\echo '[0b unlinked_restocks] undo rows without reversesLedgerId, per source type'
SELECT o.code AS org, l."referenceType", CASE WHEN l."quantityChange" > 0 THEN 'inflow' ELSE 'outflow' END AS direction,
       count(*) AS rows, SUM(abs(l."quantityChange")) AS quantity, MIN(l."createdAt") AS "firstAt",
       MAX(l."createdAt") AS "lastAt"
  FROM "InventoryLedger" l JOIN "Organization" o ON o.id = l."organizationId"
 WHERE l."reversesLedgerId" IS NULL AND l."quantityChange" <> 0
   AND ((l."referenceType" IN ('cash_sale_void', 'credit_note', 'debit_note_reversal', 'fleet_trip_return',
                               'pos_order_cancel', 'pos_order_void', 'pos_refund', 'production_order_cancel',
                               'project_issue_reversal', 'quarry_explosive_return', 'repair_part_return',
                               'sales_invoice_cancel', 'sales_return') AND l."quantityChange" > 0)
     OR (l."referenceType" IN ('credit_note_cancel', 'vendor_bill_cancel') AND l."quantityChange" < 0)
     OR l."referenceType" = 'production_reversal'
     OR (l."referenceType" = 'fleet_work_order' AND l.type::text = 'return_in'))
 GROUP BY o.code, l."referenceType", 3
 ORDER BY o.code, count(*) DESC
 LIMIT :row_limit;

-- meaning: per inventory control account, the gap between document amounts and base amounts (GL-5): the GL tie-out
--          sums debit − credit, so a foreign-currency line misstates it. The full valuation-vs-GL per account needs the
--          costing-method valuation: use reportOnlyChecks[gl_tieout_by_account] of the integrity report.
\echo '[0b gl_tieout_by_account] inventory accounts: document vs base amounts'
WITH accts AS (SELECT "organizationId" AS org, "accountId" AS aid FROM "AccountMapping"
                WHERE key IN ('stock_valuation', 'inventory', 'inventory_price_difference', 'inventory_nrv_allowance')
               UNION SELECT "organizationId", "inventoryAccountId" FROM "ProductCategory"
                      WHERE "inventoryAccountId" IS NOT NULL
               UNION SELECT "organizationId", "inventoryAccountOverrideId" FROM "Product"
                      WHERE "inventoryAccountOverrideId" IS NOT NULL)
SELECT o.code AS org, a.code AS account, a.name, SUM(jl.debit - jl.credit) AS "documentBalance",
       SUM(COALESCE(jl."baseDebit", 0) - COALESCE(jl."baseCredit", 0)) AS "baseBalance",
       SUM(jl.debit - jl.credit) - SUM(COALESCE(jl."baseDebit", 0) - COALESCE(jl."baseCredit", 0)) AS "currencyGap"
  FROM accts JOIN "JournalLine" jl ON jl."accountId" = accts.aid AND jl."organizationId" = accts.org
  JOIN "JournalEntry" e ON e.id = jl."journalEntryId" AND e.status::text <> 'draft'
  JOIN "Organization" o ON o.id = accts.org LEFT JOIN "Account" a ON a.id = accts.aid
 GROUP BY o.code, a.code, a.name
 ORDER BY o.code, a.code
 LIMIT :row_limit;

-- meaning: POS stock that has not reached the ledger: failed jobs, or jobs pending/processing too long.
\echo '[0b stock_posting_jobs] failed or stale stock-posting jobs'
SELECT o.code AS org, j.status, count(*) AS jobs, MIN(j."createdAt") AS oldest, MAX(j.attempts) AS "maxAttempts",
       (array_agg(left(j."lastError", 200) ORDER BY j."createdAt") FILTER (WHERE j."lastError" IS NOT NULL))[1]
           AS "firstError"
  FROM "StockPostingJob" j JOIN "Organization" o ON o.id = j."organizationId"
 WHERE j.status = 'failed'
    OR (j.status IN ('pending', 'processing') AND j."createdAt" < now() - make_interval(hours => :stale_job_hours))
 GROUP BY o.code, j.status
 ORDER BY o.code, j.status;

\echo '[0b transit_age] transit transfers open longer than' :transit_days 'days'
SELECT o.code AS org, t."transferCode", t.status::text AS status, t."dispatchedAt",
       date_part('day', now() - COALESCE(t."dispatchedAt", t."updatedAt"))::int AS "ageDays"
  FROM "StockTransfer" t JOIN "Organization" o ON o.id = t."organizationId"
 WHERE t.mode = 'transit' AND t.status::text IN ('in_transit', 'partially_received')
   AND COALESCE(t."dispatchedAt", t."updatedAt") < now() - make_interval(days => :transit_days)
 ORDER BY o.code, COALESCE(t."dispatchedAt", t."updatedAt")
 LIMIT :row_limit;

-- meaning: a quality lot on hold whose stock is still in a saleable location (X-2 / D2).
\echo '[0b quality_hold_unquarantined] quality lots on hold outside QC quarantine'
SELECT o.code AS org, q."lotNumber", p.code AS product, l.name AS location,
       q.quantity - q."releasedQty" - q."rejectedQty" - q."downgradedQty" AS "openQuantity",
       (SELECT SUM(s.quantity) FROM "StockItem" s WHERE s."organizationId" = q."organizationId"
          AND s."productId" = q."productId" AND s."locationId" = q."locationId"
          AND s."variantKey" = COALESCE(q."variantId", '')) AS "onHandAtLocation"
  FROM "QualityInspectionLot" q JOIN "Organization" o ON o.id = q."organizationId"
  LEFT JOIN "InventoryLocation" l ON l.id = q."locationId" LEFT JOIN "Product" p ON p.id = q."productId"
 WHERE q.status = 'hold' AND q."stockControl" = 'quality' AND q."quarantineLocationId" IS NULL
   AND q."locationId" IS NOT NULL AND COALESCE(l.code, '') <> 'QC-QUARANTINE'
   AND q.quantity - q."releasedQty" - q."rejectedQty" - q."downgradedQty" > 0.000001
 ORDER BY o.code, q."lotNumber"
 LIMIT :row_limit;

-- meaning (informational, R3): movements whose journal is dated in another month than the movement was recorded (org
--          time zone). The integrity report uses fiscal periods; this SQL uses calendar months.
\echo '[0b period_shifted] movements posted in another month than recorded, last' :lookback_days 'days'
WITH je AS (SELECT e."organizationId" AS org, e."sourceId" AS sid, MIN(e."postingDate") AS pd,
                   MAX(e."postingDate") AS pd_max
              FROM "JournalEntry" e
             WHERE e.status::text <> 'draft' AND e."sourceId" IS NOT NULL
               AND e."createdAt" >= now() - make_interval(days => :lookback_days + 1)
             GROUP BY 1, 2),
     x AS (SELECT l."organizationId" AS org, l."referenceType",
                  ((l."createdAt" AT TIME ZONE 'UTC') AT TIME ZONE COALESCE(NULLIF(og.timezone, ''), 'Africa/Kampala'))::date
                      AS moved_on,
                  COALESCE(CASE WHEN date_trunc('day', jc.pd) = date_trunc('day', jc.pd_max) THEN jc.pd END,
                           CASE WHEN date_trunc('day', jr.pd) = date_trunc('day', jr.pd_max) THEN jr.pd END)::date
                      AS posted_on
             FROM "InventoryLedger" l JOIN "Organization" og ON og.id = l."organizationId"
             LEFT JOIN je jc ON jc.org = l."organizationId" AND jc.sid = l."ledgerCode"
             LEFT JOIN je jr ON jr.org = l."organizationId" AND jr.sid = l."referenceId"
            WHERE l."createdAt" >= now() - make_interval(days => :lookback_days) AND l."valuationComponent" IS NULL)
SELECT o.code AS org, x."referenceType", to_char(x.moved_on, 'YYYY-MM') AS "movedMonth",
       to_char(x.posted_on, 'YYYY-MM') AS "postedMonth", count(*) AS rows
  FROM x JOIN "Organization" o ON o.id = x.org
 WHERE x.posted_on IS NOT NULL AND to_char(x.moved_on, 'YYYY-MM') <> to_char(x.posted_on, 'YYYY-MM')
 GROUP BY o.code, x."referenceType", 3, 4
 ORDER BY o.code, count(*) DESC
 LIMIT :row_limit;

-- =====================================================================================================================
-- [SUMMARY] One row per organisation: counts of the cheap detectors (0 everywhere = clean). F1/F3/X-1 need the detail
-- queries above; their counts here are the row counts of simplified forms (candidates, not confirmed findings).
-- =====================================================================================================================
\echo '[SUMMARY] per organisation'
SELECT o.code AS org, o.name,
       (SELECT count(*) FROM "StockItem" s WHERE s."organizationId" = o.id AND s.quantity < -0.000001)
           AS negative_quants,
       (SELECT count(*) FROM "InventoryLedger" l WHERE l."organizationId" = o.id AND l."reversesLedgerId" IS NULL
           AND l."quantityChange" > 0 AND l."referenceType" IN ('pos_refund', 'fleet_trip_return', 'sales_return',
               'quarry_explosive_return', 'project_issue_reversal', 'production_order_cancel'))
           AS unlinked_restock_rows,
       (SELECT count(*) FROM "StockPostingJob" j WHERE j."organizationId" = o.id AND (j.status = 'failed'
           OR (j.status IN ('pending', 'processing')
               AND j."createdAt" < now() - make_interval(hours => :stale_job_hours)))) AS stale_or_failed_jobs,
       (SELECT count(*) FROM "StockTransfer" t WHERE t."organizationId" = o.id AND t.mode = 'transit'
           AND t.status::text IN ('in_transit', 'partially_received')
           AND COALESCE(t."dispatchedAt", t."updatedAt") < now() - make_interval(days => :transit_days))
           AS old_transit_transfers,
       (SELECT count(*) FROM "QualityInspectionLot" q LEFT JOIN "InventoryLocation" l ON l.id = q."locationId"
         WHERE q."organizationId" = o.id AND q.status = 'hold' AND q."stockControl" = 'quality'
           AND q."quarantineLocationId" IS NULL AND q."locationId" IS NOT NULL
           AND COALESCE(l.code, '') <> 'QC-QUARANTINE') AS held_lots_unquarantined,
       (SELECT count(*) FROM "Document" d WHERE d."organizationId" = o.id AND d."documentType" = 'sales_invoice'
           AND d.status::text NOT IN ('draft', 'cancelled') AND d.data->>'stockIssuedBy' IS NOT NULL
           AND d."sourceType" IS DISTINCT FROM 'cash_sale'
           AND EXISTS (SELECT 1 FROM "FleetDeliveryOrder" f WHERE f."organizationId" = o.id
                        AND f.status IN ('cancelled', 'closed')
                        AND ((f."sourceType" = 'invoice' AND f."sourceId" = d.id) OR f."goodsInvoiceId" = d.id))
           AND NOT EXISTS (SELECT 1 FROM "FleetDeliveryOrder" f WHERE f."organizationId" = o.id
                        AND f.status IN ('open', 'planned', 'in_progress')
                        AND ((f."sourceType" = 'invoice' AND f."sourceId" = d.id) OR f."goodsInvoiceId" = d.id)))
           AS f1_candidate_invoices,
       (SELECT count(*) FROM "InventoryCountSession" s WHERE s."organizationId" = o.id
           AND s."countType"::text = 'opening' AND s."deletedAt" IS NULL AND s.status::text <> 'cancelled'
           AND EXISTS (SELECT 1 FROM "InventoryLedger" g WHERE g."organizationId" = o.id
                        AND g."locationId" = s."locationId" AND g."createdAt" < s."startedAt")) AS s1_opening_counts,
       (SELECT count(*) FROM "StockItem" s JOIN "Product" p ON p.id = s."productId"
         WHERE s."organizationId" = o.id AND p."costingMethod"::text = 'AVCO' AND s.quantity > 0
           AND COALESCE(s."runningAverageCost", 0) = 0) AS c3_zero_cost_avco_quants,
       (SELECT count(*) FROM "InventoryException" x WHERE x."organizationId" = o.id AND x.status::text = 'open'
           AND x.kind LIKE 'integrity\_%') AS open_integrity_findings
  FROM "Organization" o
 WHERE o."deletedAt" IS NULL
 ORDER BY o.code;

COMMIT;
