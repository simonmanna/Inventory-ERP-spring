-- V570: the `bi` schema, read by the embedded Apache Superset dashboards (ADR-S078, docs/integrations.md).
--
-- Unlike the `reporting` views (V111), these do NOT filter on app.org_id: Superset reads through a pooled connection
-- and cannot set it per viewer. Every view instead exposes "organizationId", and tenant isolation is the row-level
-- security clause `"organizationId" = '<org>'` that the API puts in each Superset guest token (SupersetEmbedService),
-- taken from the signed-in user's organization. A view without an "organizationId" column fails every embedded query,
-- so a mistake fails closed. The views run with their owner's rights; the base tables' policies are not forced.
--
-- Only superset_reader reads them: USAGE on `bi` and SELECT on its views, nothing on the tables. The role is created
-- NOLOGIN; operations give it a password and LOGIN when Superset is deployed (docs/integrations.md).
--
-- The views are a contract like `reporting`: a later migration that drops or retypes a column they use recreates them.

CREATE SCHEMA IF NOT EXISTS bi;

-- ---- Sales ---------------------------------------------------------------------------------------------------------

CREATE VIEW bi.sales_lines AS
SELECT d."organizationId", d.id AS "documentId", d."documentNumber", d."documentType"::text AS "documentType",
       d.status::text AS status, d."issueDate", d."dueDate", d."branchId", b.name AS "branchName", d."partnerId",
       c.name AS "customerName", d."salespersonName", l."lineNumber", l."productId", p.code AS "productCode",
       p.name AS "productName", l.description, l.quantity, COALESCE(l."uomFactor", 1) AS "uomFactor", l."unitPrice",
       l."discountPercent", l.subtotal, l."taxAmount", l.total,
       CASE WHEN d."documentType"::text = 'credit_note' THEN -l.subtotal ELSE l.subtotal END AS "signedSubtotal",
       d."exchangeRate"
FROM "Document" d
JOIN "DocumentLine" l ON l."documentId" = d.id
LEFT JOIN "Product" p ON p.id = l."productId"
LEFT JOIN "Partner" c ON c.id = d."partnerId"
LEFT JOIN "Branch" b ON b.id = d."branchId"
WHERE d."documentType"::text IN ('sales_invoice', 'credit_note')
  AND d.status::text NOT IN ('draft', 'cancelled', 'rejected');

-- One row per customer invoice or credit note, with what is still owed: the receivables aging.
CREATE VIEW bi.sales_invoices AS
SELECT d."organizationId", d.id AS "documentId", d."documentNumber", d."documentType"::text AS "documentType",
       d.status::text AS status, d."paymentStatus"::text AS "paymentStatus", d."issueDate", d."dueDate",
       d."branchId", b.name AS "branchName", d."partnerId", c.name AS "customerName", d."salespersonName",
       d.subtotal, d."taxAmount", d."totalAmount", d."amountPaid", d."amountResidual", d."exchangeRate",
       GREATEST(0, CURRENT_DATE - d."dueDate"::date) AS "daysOverdue",
       CASE WHEN COALESCE(d."amountResidual", 0) = 0 THEN 'settled'
            WHEN d."dueDate" IS NULL OR d."dueDate"::date >= CURRENT_DATE THEN 'current'
            WHEN CURRENT_DATE - d."dueDate"::date <= 30 THEN '1-30'
            WHEN CURRENT_DATE - d."dueDate"::date <= 60 THEN '31-60'
            WHEN CURRENT_DATE - d."dueDate"::date <= 90 THEN '61-90'
            ELSE '90+' END AS "agingBucket"
FROM "Document" d
LEFT JOIN "Partner" c ON c.id = d."partnerId"
LEFT JOIN "Branch" b ON b.id = d."branchId"
WHERE d."documentType"::text IN ('sales_invoice', 'credit_note')
  AND d.status::text NOT IN ('draft', 'cancelled', 'rejected');

-- ---- Purchases -----------------------------------------------------------------------------------------------------

CREATE VIEW bi.purchase_lines AS
SELECT o."organizationId", o.id AS "purchaseOrderId", o."orderNumber", o.status::text AS status, o."orderDate",
       o."expectedDeliveryDate", o."branchId", b.name AS "branchName", o."partnerId", s.name AS "supplierName",
       l."lineNumber", l."productId", p.code AS "productCode", p.name AS "productName", l.description, l.quantity,
       l."receivedQuantity", l."billedQuantity", l."unitPrice", l.subtotal, o."currencyCode", o."exchangeRate"
FROM "PurchaseOrder" o
JOIN "PurchaseOrderLine" l ON l."purchaseOrderId" = o.id
LEFT JOIN "Product" p ON p.id = l."productId"
LEFT JOIN "Partner" s ON s.id = o."partnerId"
LEFT JOIN "Branch" b ON b.id = o."branchId"
WHERE o."deletedAt" IS NULL
  AND o.status::text NOT IN ('draft', 'cancelled');

-- One row per vendor bill or debit note, with what is still owed: the payables aging.
CREATE VIEW bi.vendor_bills AS
SELECT d."organizationId", d.id AS "documentId", d."documentNumber", d."documentType"::text AS "documentType",
       d.status::text AS status, d."paymentStatus"::text AS "paymentStatus", d."issueDate", d."dueDate",
       d."branchId", b.name AS "branchName", d."partnerId", s.name AS "supplierName", d.subtotal, d."taxAmount",
       d."totalAmount", d."amountPaid", d."amountResidual", d."exchangeRate",
       GREATEST(0, CURRENT_DATE - d."dueDate"::date) AS "daysOverdue",
       CASE WHEN COALESCE(d."amountResidual", 0) = 0 THEN 'settled'
            WHEN d."dueDate" IS NULL OR d."dueDate"::date >= CURRENT_DATE THEN 'current'
            WHEN CURRENT_DATE - d."dueDate"::date <= 30 THEN '1-30'
            WHEN CURRENT_DATE - d."dueDate"::date <= 60 THEN '31-60'
            WHEN CURRENT_DATE - d."dueDate"::date <= 90 THEN '61-90'
            ELSE '90+' END AS "agingBucket"
FROM "Document" d
LEFT JOIN "Partner" s ON s.id = d."partnerId"
LEFT JOIN "Branch" b ON b.id = d."branchId"
WHERE d."documentType"::text IN ('vendor_bill', 'debit_note')
  AND d.status::text NOT IN ('draft', 'cancelled', 'rejected');

-- ---- Inventory -----------------------------------------------------------------------------------------------------

CREATE VIEW bi.stock_on_hand AS
SELECT s."organizationId", s."productId", p.code AS "productCode", p.name AS "productName", s."locationId",
       l.code AS "locationCode", l.name AS "locationName", l."branchId", b.name AS "branchName", s.quantity,
       s."runningAverageCost", s.quantity * s."runningAverageCost" AS "stockValue", s."updatedAt"
FROM "StockItem" s
JOIN "Product" p ON p.id = s."productId"
JOIN "InventoryLocation" l ON l.id = s."locationId"
LEFT JOIN "Branch" b ON b.id = l."branchId";

CREATE VIEW bi.stock_movements AS
SELECT g."organizationId", g."ledgerCode", g."createdAt", g.type::text AS "movementType", g."productId",
       p.code AS "productCode", p.name AS "productName", g."locationId", l.code AS "locationCode",
       l.name AS "locationName", l."branchId", g."quantityChange", g."balanceAfter", g."unitCost", g."totalValue",
       g."referenceType", g."referenceId"
FROM "InventoryLedger" g
JOIN "Product" p ON p.id = g."productId"
LEFT JOIN "InventoryLocation" l ON l.id = g."locationId";

-- ---- Finance -------------------------------------------------------------------------------------------------------

CREATE VIEW bi.journal_lines AS
SELECT l."organizationId", e."entryNumber", e."postingDate", e.status::text AS status, e."sourceType", e."sourceId",
       l."accountId", a.code AS "accountCode", a.name AS "accountName", a."reportSection"::text AS "reportSection",
       a."normalBalance"::text AS "normalBalance", l.description, l."baseDebit", l."baseCredit",
       l."baseDebit" - l."baseCredit" AS "baseNet", l."branchId", l."costCenterId", l."partnerId"
FROM "JournalLine" l
JOIN "JournalEntry" e ON e.id = l."journalEntryId"
JOIN "Account" a ON a.id = l."accountId"
WHERE e.status::text IN ('posted', 'reversed');

CREATE VIEW bi.payments AS
SELECT y."organizationId", y."paymentNumber", y.direction::text AS direction, y."paymentDate", y."paymentMethod",
       y."partnerId", c.name AS "partnerName", y."branchId", b.name AS "branchName", y.amount, y."allocatedAmount",
       y."unallocatedAmount", y."exchangeRate", y.status::text AS status
FROM "Payment" y
LEFT JOIN "Partner" c ON c.id = y."partnerId"
LEFT JOIN "Branch" b ON b.id = y."branchId"
WHERE y."voidedAt" IS NULL
  AND y.status::text NOT IN ('draft', 'cancelled', 'rejected');

-- ---- The Superset login --------------------------------------------------------------------------------------------

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'superset_reader') THEN
    CREATE ROLE superset_reader NOLOGIN;
  END IF;
EXCEPTION
  WHEN insufficient_privilege THEN
    RAISE NOTICE 'Role superset_reader not created (no CREATEROLE): create it by hand, see docs/integrations.md';
  WHEN duplicate_object THEN
    NULL;
END $$;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'superset_reader') THEN
    GRANT USAGE ON SCHEMA bi TO superset_reader;
    GRANT SELECT ON ALL TABLES IN SCHEMA bi TO superset_reader;
  END IF;
END $$;
