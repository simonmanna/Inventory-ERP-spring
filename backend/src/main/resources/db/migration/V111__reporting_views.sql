-- V111: read-only reporting views for BI tools (Phase 5, docs/integrations.md). Stable names and columns over the
-- operational tables: a BI tool connects to these, never to the tables, so a schema change has one place to absorb it.
--
-- Each view filters on the session's app.org_id, the setting the row-level policies use, and shows nothing when it is
-- unset. The application sets it per transaction; the CSV route (/bi/datasets) also filters by organization. A BI login
-- gets USAGE on this schema, SELECT on its views and app.org_id in its role settings. See docs/integrations.md.

CREATE SCHEMA IF NOT EXISTS reporting;

CREATE VIEW reporting.sales_lines AS
SELECT d."organizationId", d."documentNumber", d."documentType"::text AS "documentType", d.status::text AS status,
       d."issueDate", d."dueDate", d."branchId", b.name AS "branchName", d."partnerId", c.name AS "customerName",
       l."lineNumber", l."productId", p.code AS "productCode", p.name AS "productName", l.description, l.quantity,
       COALESCE(l."uomFactor", 1) AS "uomFactor", l."unitPrice", l."discountPercent", l.subtotal, l."taxAmount", l.total,
       d."exchangeRate"
FROM "Document" d
JOIN "DocumentLine" l ON l."documentId" = d.id
LEFT JOIN "Product" p ON p.id = l."productId"
LEFT JOIN "Partner" c ON c.id = d."partnerId"
LEFT JOIN "Branch" b ON b.id = d."branchId"
WHERE d."organizationId" = current_setting('app.org_id', true)
  AND d."documentType"::text IN ('sales_invoice', 'credit_note')
  AND d.status::text NOT IN ('draft', 'cancelled', 'rejected');

CREATE VIEW reporting.purchase_lines AS
SELECT o."organizationId", o."orderNumber", o.status::text AS status, o."orderDate", o."expectedDeliveryDate",
       o."branchId", o."partnerId", s.name AS "supplierName", l."lineNumber", l."productId", p.code AS "productCode",
       p.name AS "productName", l.description, l.quantity, l."receivedQuantity", l."billedQuantity", l."unitPrice",
       l.subtotal, o."currencyCode", o."exchangeRate"
FROM "PurchaseOrder" o
JOIN "PurchaseOrderLine" l ON l."purchaseOrderId" = o.id
LEFT JOIN "Product" p ON p.id = l."productId"
LEFT JOIN "Partner" s ON s.id = o."partnerId"
WHERE o."organizationId" = current_setting('app.org_id', true)
  AND o."deletedAt" IS NULL
  AND o.status::text NOT IN ('draft', 'cancelled');

CREATE VIEW reporting.stock_on_hand AS
SELECT s."organizationId", s."productId", p.code AS "productCode", p.name AS "productName", s."locationId",
       l.code AS "locationCode", l.name AS "locationName", s.quantity, s."runningAverageCost",
       s.quantity * s."runningAverageCost" AS "stockValue", s."updatedAt"
FROM "StockItem" s
JOIN "Product" p ON p.id = s."productId"
JOIN "InventoryLocation" l ON l.id = s."locationId"
WHERE s."organizationId" = current_setting('app.org_id', true);

CREATE VIEW reporting.crushing_output AS
SELECT r."organizationId", r.code AS "runCode", r."runDate", r.shift, pl.code AS "plantCode", pl.name AS "plantName",
       o."productId", p.code AS "productCode", p.name AS "productName", o.role, r."feedTonnes", o."expectedTonnes",
       o."actualTonnes", o.value, o."unitCost"
FROM "CrushingRunOutput" o
JOIN "CrushingRun" r ON r.id = o."runId"
JOIN "CrushingPlant" pl ON pl.id = r."plantId"
JOIN "Product" p ON p.id = o."productId"
WHERE r."organizationId" = current_setting('app.org_id', true)
  AND r.status = 'posted'
  AND r."deletedAt" IS NULL;

CREATE VIEW reporting.block_production AS
SELECT b."organizationId", b.code AS "batchCode", b."batchNumber", b."productId", p.code AS "productCode",
       p.name AS "productName", b.status, b.quantity, b."brokenAtPress", b."qtyGood", b."qtySecondGrade", b."qtyBroken",
       b."startedAt", b."curingUntil", b."cubedAt", b."releasedAt"
FROM "PrecastCuringBatch" b
JOIN "Product" p ON p.id = b."productId"
WHERE b."organizationId" = current_setting('app.org_id', true);

CREATE VIEW reporting.weighbridge_tickets AS
SELECT t."organizationId", t."ticketNumber", t.status, t."subjectType", t.direction, t."registrationPlate", t."partnerId",
       c.name AS "partnerName", t."productId", p.code AS "productCode", p.name AS "productName", t."grossKg", t."tareKg",
       t."netKg", t."netQty", t."varianceKg", t."withinTolerance", t."isManual", t."usedStoredTare", t."completedAt",
       t."createdAt"
FROM "WeighbridgeTicket" t
LEFT JOIN "Partner" c ON c.id = t."partnerId"
LEFT JOIN "Product" p ON p.id = t."productId"
WHERE t."organizationId" = current_setting('app.org_id', true);

CREATE VIEW reporting.delivery_lines AS
SELECT d."organizationId", d.code AS "deliveryOrderCode", d.status, d.priority, d."requestedDate", d."partnerId",
       c.name AS "customerName", d."branchId", l."lineNumber", l."productId", p.code AS "productCode",
       p.name AS "productName", l."orderedQty", l."plannedQty", l."dispatchedQty", l."deliveredQty", l."returnedQty",
       l."unitPrice"
FROM "FleetDeliveryOrder" d
JOIN "FleetDeliveryOrderLine" l ON l."deliveryOrderId" = d.id
LEFT JOIN "Product" p ON p.id = l."productId"
LEFT JOIN "Partner" c ON c.id = d."partnerId"
WHERE d."organizationId" = current_setting('app.org_id', true);

CREATE VIEW reporting.journal_lines AS
SELECT l."organizationId", e."entryNumber", e."postingDate", e.status::text AS status, e."sourceType", e."sourceId",
       l."accountId", a.code AS "accountCode", a.name AS "accountName", l.description, l."baseDebit", l."baseCredit",
       l."branchId", l."costCenterId", l."partnerId"
FROM "JournalLine" l
JOIN "JournalEntry" e ON e.id = l."journalEntryId"
JOIN "Account" a ON a.id = l."accountId"
WHERE l."organizationId" = current_setting('app.org_id', true)
  AND e.status::text IN ('posted', 'reversed');
