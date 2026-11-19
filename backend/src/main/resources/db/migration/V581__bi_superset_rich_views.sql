-- V581: richer embedded Superset dashboards (ADR-S078 B2). Same rules as V570: every view exposes "organizationId"
-- (the guest token's row-level security clause pins it), only superset_reader reads `bi` (afterMigrate re-grants).
--
-- Existing views are replaced with the same columns, in the same order and types, and new columns appended at the
-- end, so a dataset Superset already holds keeps working. New: product category on sales, purchases and stock; the
-- account category's report section and classification on journal lines (most accounts carry them only through
-- their category) plus a profit-and-loss amount; and two new views, expenses and the CRM pipeline.

-- ---- Sales ---------------------------------------------------------------------------------------------------------

CREATE OR REPLACE VIEW bi.sales_lines AS
SELECT d."organizationId", d.id AS "documentId", d."documentNumber", d."documentType"::text AS "documentType",
       d.status::text AS status, d."issueDate", d."dueDate", d."branchId", b.name AS "branchName", d."partnerId",
       c.name AS "customerName", d."salespersonName", l."lineNumber", l."productId", p.code AS "productCode",
       p.name AS "productName", l.description, l.quantity, COALESCE(l."uomFactor", 1) AS "uomFactor", l."unitPrice",
       l."discountPercent", l.subtotal, l."taxAmount", l.total,
       CASE WHEN d."documentType"::text = 'credit_note' THEN -l.subtotal ELSE l.subtotal END AS "signedSubtotal",
       d."exchangeRate",
       COALESCE(pc.name, 'Uncategorised') AS "categoryName"
FROM "Document" d
JOIN "DocumentLine" l ON l."documentId" = d.id
LEFT JOIN "Product" p ON p.id = l."productId"
LEFT JOIN "ProductCategory" pc ON pc.id = p."categoryId"
LEFT JOIN "Partner" c ON c.id = d."partnerId"
LEFT JOIN "Branch" b ON b.id = d."branchId"
WHERE d."documentType"::text IN ('sales_invoice', 'credit_note')
  AND d.status::text NOT IN ('draft', 'cancelled', 'rejected');

-- ---- Purchases -----------------------------------------------------------------------------------------------------

CREATE OR REPLACE VIEW bi.purchase_lines AS
SELECT o."organizationId", o.id AS "purchaseOrderId", o."orderNumber", o.status::text AS status, o."orderDate",
       o."expectedDeliveryDate", o."branchId", b.name AS "branchName", o."partnerId", s.name AS "supplierName",
       l."lineNumber", l."productId", p.code AS "productCode", p.name AS "productName", l.description, l.quantity,
       l."receivedQuantity", l."billedQuantity", l."unitPrice", l.subtotal, o."currencyCode", o."exchangeRate",
       COALESCE(pc.name, 'Uncategorised') AS "categoryName"
FROM "PurchaseOrder" o
JOIN "PurchaseOrderLine" l ON l."purchaseOrderId" = o.id
LEFT JOIN "Product" p ON p.id = l."productId"
LEFT JOIN "ProductCategory" pc ON pc.id = p."categoryId"
LEFT JOIN "Partner" s ON s.id = o."partnerId"
LEFT JOIN "Branch" b ON b.id = o."branchId";

-- ---- Inventory -----------------------------------------------------------------------------------------------------

CREATE OR REPLACE VIEW bi.stock_on_hand AS
SELECT s."organizationId", s."productId", p.code AS "productCode", p.name AS "productName", s."locationId",
       l.code AS "locationCode", l.name AS "locationName", l."branchId", b.name AS "branchName", s.quantity,
       s."runningAverageCost", s.quantity * s."runningAverageCost" AS "stockValue", s."updatedAt",
       COALESCE(pc.name, 'Uncategorised') AS "categoryName"
FROM "StockItem" s
JOIN "Product" p ON p.id = s."productId"
LEFT JOIN "ProductCategory" pc ON pc.id = p."categoryId"
JOIN "InventoryLocation" l ON l.id = s."locationId"
LEFT JOIN "Branch" b ON b.id = l."branchId";

CREATE OR REPLACE VIEW bi.stock_movements AS
SELECT g."organizationId", g."ledgerCode", g."createdAt", g.type::text AS "movementType", g."productId",
       p.code AS "productCode", p.name AS "productName", g."locationId", l.code AS "locationCode",
       l.name AS "locationName", l."branchId", g."quantityChange", g."balanceAfter", g."unitCost", g."totalValue",
       g."referenceType", g."referenceId",
       COALESCE(pc.name, 'Uncategorised') AS "categoryName", b.name AS "branchName",
       CASE WHEN g."quantityChange" >= 0 THEN 'in' ELSE 'out' END AS "flow"
FROM "InventoryLedger" g
JOIN "Product" p ON p.id = g."productId"
LEFT JOIN "ProductCategory" pc ON pc.id = p."categoryId"
LEFT JOIN "InventoryLocation" l ON l.id = g."locationId"
LEFT JOIN "Branch" b ON b.id = l."branchId";

-- ---- Finance -------------------------------------------------------------------------------------------------------

-- "reportSection" now falls back to the account category's section: most accounts leave their own empty.
-- "plAmount" is the profit-and-loss effect (credit minus debit) of revenue and expense lines, 0 for balance sheet ones,
-- so SUM("plAmount") is net profit and its sign reads naturally on a chart.
CREATE OR REPLACE VIEW bi.journal_lines AS
SELECT l."organizationId", e."entryNumber", e."postingDate", e.status::text AS status, e."sourceType", e."sourceId",
       l."accountId", a.code AS "accountCode", a.name AS "accountName",
       COALESCE(a."reportSection"::text, cat."reportSection"::text) AS "reportSection",
       a."normalBalance"::text AS "normalBalance", l.description, l."baseDebit", l."baseCredit",
       l."baseDebit" - l."baseCredit" AS "baseNet", l."branchId", l."costCenterId", l."partnerId",
       cat.classification::text AS classification, cat.name AS "categoryName", br.name AS "branchName",
       CASE WHEN cat.classification::text IN ('revenue', 'expense') THEN l."baseCredit" - l."baseDebit" ELSE 0 END
           AS "plAmount"
FROM "JournalLine" l
JOIN "JournalEntry" e ON e.id = l."journalEntryId"
JOIN "Account" a ON a.id = l."accountId"
LEFT JOIN "AccountCategory" cat ON cat.id = a."categoryId"
LEFT JOIN "Branch" br ON br.id = l."branchId"
WHERE e.status::text IN ('posted', 'reversed');

-- ---- Expenses ------------------------------------------------------------------------------------------------------

CREATE VIEW bi.expenses AS
SELECT x."organizationId", x.id AS "expenseId", x."expenseCode", x.title, x.status::text AS status,
       x."paymentStatus"::text AS "paymentStatus", x."paymentType"::text AS "paymentType", x."paidBy",
       x."expenseDate", x."dueDate", x."paidAt", COALESCE(c.name, x."categoryName", 'Uncategorised') AS "categoryName",
       x."supplierId", s.name AS "supplierName", x."branchId", b.name AS "branchName", x."costCenterId",
       x."currencyCode", x.amount, x."taxAmount", x."amountPaid", x.amount - x."amountPaid" AS "amountUnpaid",
       x.capitalize
FROM "Expense" x
LEFT JOIN "ExpenseCategory" c ON c.id = x."categoryId"
LEFT JOIN "Partner" s ON s.id = x."supplierId"
LEFT JOIN "Branch" b ON b.id = x."branchId"
WHERE x."deletedAt" IS NULL
  AND x.status::text NOT IN ('DRAFT', 'REJECTED', 'CANCELLED', 'VOID');

-- ---- CRM -----------------------------------------------------------------------------------------------------------

-- Leads and opportunities with their pipeline stage. "weightedAmount" = amount x win probability (the deal's own, else
-- its stage's), what the pipeline is worth if every deal closes at its odds.
CREATE VIEW bi.crm_deals AS
SELECT d."organizationId", d.id AS "dealId", d.name AS "dealName", d.kind, d.status,
       COALESCE(st.name, d.stage::text) AS "stageName", COALESCE(st.sequence, 0) AS "stageSequence",
       d."currencyCode", d.amount, COALESCE(d.probability, st.probability, 0) AS probability,
       d.amount * COALESCE(d.probability, st.probability, 0) / 100 AS "weightedAmount",
       d."expectedClose", d."createdAt", d."wonAt", d."lostAt", d."stageChangedAt",
       COALESCE(p.name, d."companyName", d."contactName") AS "customerName",
       NULLIF(TRIM(CONCAT(u."firstName", ' ', u."lastName")), '') AS "ownerName", t.name AS "teamName",
       src.name AS "sourceName", lr.name AS "lostReason", d.city, d.country,
       CASE WHEN d.status = 'won' THEN EXTRACT(EPOCH FROM (d."wonAt" - d."createdAt")) / 86400 END AS "daysToWin"
FROM "Deal" d
LEFT JOIN "CrmStage" st ON st.id = d."stageId"
LEFT JOIN "Partner" p ON p.id = d."partnerId"
LEFT JOIN "User" u ON u.id = d."ownerId"
LEFT JOIN "CrmTeam" t ON t.id = d."teamId"
LEFT JOIN "CrmSource" src ON src.id = d."sourceId"
LEFT JOIN "CrmLostReason" lr ON lr.id = d."lostReasonId"
WHERE d."deletedAt" IS NULL;

DO $bi$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'superset_reader') THEN
        GRANT SELECT ON ALL TABLES IN SCHEMA bi TO superset_reader;
    END IF;
END $bi$;
