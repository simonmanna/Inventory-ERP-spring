-- V739: central sales reporting views (ADR-S094 C7, §9.1).
--
-- reporting.sales_orders    one row per sale line, order and cash sales: bookings (M1) and fulfilment (M2). Amounts
--                           are the line's own (net of line and order discounts, tax apart) in the sale currency;
--                           "functionalSubtotal" is the net in the functional currency.
-- reporting.sales_register  every billed line (M3): posted sales invoices and credit notes, POS invoices and POS
--                           refunds, with the sales channel. "signed*" amounts are negative for credit notes and
--                           refunds; "functional*" are the signed amounts in the functional currency, at the
--                           document's carrying rate (Σ base ÷ Σ transaction amount of its live journal, else the stated
--                           rate: a document created through the API stores rate 1 when none is stated); a POS refund
--                           at its own journal's rate.
-- bi.sales_orders           the sale lines for Superset (no organization filter: the guest token's row-level security
--                           clause pins "organizationId", as for every bi view).
-- bi.sales_lines            "channel" and "functionalSubtotal" (signed, at the carrying rate) appended; the columns
--                           before them are unchanged, so a Superset dataset keeps working (V581 rule).
--
-- The channel is the report center's rule (SalesReportFacts.channelSql), written out in each view because the
-- Superset reader may read only the bi views: a credit note takes the channel of the invoice it credits ("cd" = the
-- channel document); otherwise the document's source kind decides. SalesCentralReportingIT keeps the views and the
-- report center in step.

CREATE VIEW reporting.sales_orders AS
SELECT o."organizationId", o.id AS "saleId", o.number, o."saleType", o.status, o."approvalStatus",
       o."deliveryStatus", o."invoiceStatus", o."paymentStatus", o."settlementStatus",
       COALESCE(o."saleDate", o."confirmedAt"::date, o."createdAt"::date) AS "saleDate", o."confirmedAt", o."doneAt",
       o."cancelledAt", o."reversedAt", o."branchId", b.name AS "branchName", o."partnerId",
       COALESCE(c.name, o."customerName") AS "customerName", o."ownerId" AS "salespersonId", o."teamId",
       l.id AS "lineId", l."sortOrder", l."productId", p.code AS "productCode", p.name AS "productName", l.description,
       l.quantity, COALESCE(l."uomFactor", 1) AS "uomFactor", l."unitPrice", l."discountPercent",
       l."lineSubtotal" AS subtotal, l."lineTax" AS "taxAmount", l."lineTotal" AS total,
       COALESCE(l."qtyCancelled", 0) AS "qtyCancelled", COALESCE(l."qtyDelivered", 0) AS "qtyDelivered",
       COALESCE(l."qtyInvoiced", 0) AS "qtyInvoiced", COALESCE(l."qtyReturned", 0) AS "qtyReturned",
       COALESCE(l."qtyCredited", 0) AS "qtyCredited",
       GREATEST(l.quantity - COALESCE(l."qtyCancelled", 0) - COALESCE(l."qtyDelivered", 0), 0) AS "qtyBacklog",
       o."currencyCode", COALESCE(o."exchangeRate", 1) AS "exchangeRate",
       l."lineSubtotal" * COALESCE(o."exchangeRate", 1) AS "functionalSubtotal"
FROM "SalesOrder" o
JOIN "SalesOrderLine" l ON l."orderId" = o.id
LEFT JOIN "Product" p ON p.id = l."productId"
LEFT JOIN "Partner" c ON c.id = o."partnerId"
LEFT JOIN "Branch" b ON b.id = o."branchId"
WHERE o."organizationId" = current_setting('app.org_id', true)
  AND o."deletedAt" IS NULL;

CREATE VIEW bi.sales_orders AS
SELECT o."organizationId", o.id AS "saleId", o.number, o."saleType", o.status, o."deliveryStatus",
       o."invoiceStatus", o."paymentStatus", COALESCE(o."saleDate", o."confirmedAt"::date, o."createdAt"::date) AS "saleDate",
       o."branchId", b.name AS "branchName", o."partnerId", COALESCE(c.name, o."customerName") AS "customerName",
       NULLIF(TRIM(CONCAT(u."firstName", ' ', u."lastName")), '') AS "salespersonName", l."productId",
       p.code AS "productCode", p.name AS "productName", COALESCE(pc.name, 'Uncategorised') AS "categoryName",
       l.quantity, COALESCE(l."qtyCancelled", 0) AS "qtyCancelled", COALESCE(l."qtyDelivered", 0) AS "qtyDelivered",
       COALESCE(l."qtyInvoiced", 0) AS "qtyInvoiced",
       GREATEST(l.quantity - COALESCE(l."qtyCancelled", 0) - COALESCE(l."qtyDelivered", 0), 0) AS "qtyBacklog",
       l."lineSubtotal" AS subtotal, l."lineTax" AS "taxAmount", o."currencyCode",
       COALESCE(o."exchangeRate", 1) AS "exchangeRate", l."lineSubtotal" * COALESCE(o."exchangeRate", 1) AS "functionalSubtotal"
FROM "SalesOrder" o
JOIN "SalesOrderLine" l ON l."orderId" = o.id
LEFT JOIN "Product" p ON p.id = l."productId"
LEFT JOIN "ProductCategory" pc ON pc.id = p."categoryId"
LEFT JOIN "Partner" c ON c.id = o."partnerId"
LEFT JOIN "Branch" b ON b.id = o."branchId"
LEFT JOIN "User" u ON u.id = o."ownerId"
WHERE o."deletedAt" IS NULL
  AND o.status IN ('confirmed', 'done', 'cancelled');

CREATE VIEW reporting.sales_register AS
SELECT d."organizationId", 'document' AS source, d.id AS "documentId", d."documentNumber",
       d."documentType"::text AS "documentType", d.status::text AS status, d."issueDate"::date AS "issueDate",
       d."branchId", b.name AS "branchName", d."partnerId", c.name AS "customerName",
       CASE WHEN cd."sourceKind" IN ('sale', 'sale_down_payment', 'sale_milestone')
                 THEN COALESCE((SELECT x."saleType" FROM "SalesOrder" x WHERE x.id = cd."sourceId"), 'order')
            WHEN cd."sourceKind" = 'cash_sale' THEN 'cash'
            WHEN cd."sourceKind" IN ('fleet_delivery', 'fleet_charges') THEN 'fleet'
            WHEN cd."sourceKind" = 'project_contract' THEN 'project'
            WHEN cd."sourceKind" = 'collections_fee' THEN 'fee'
            WHEN cd."sourceKind" = 'recurring' THEN 'recurring'
            WHEN cd."sourceKind" = 'counterpart' THEN 'counterpart'
            WHEN cd."sourceKind" = 'opening_balance' THEN 'opening'
            WHEN EXISTS (SELECT 1 FROM "IntercompanyTransaction" x WHERE x."sourceDocumentId" = cd.id) THEN 'intercompany'
            WHEN cd."tableId" IS NOT NULL OR cd."sourceType" LIKE 'pos%' THEN 'pos'
            WHEN cd."sourceKind" IS NULL THEN 'direct'
            ELSE 'manual' END AS channel, d."sourceKind", l."lineNumber", l."productId",
       p.code AS "productCode", p.name AS "productName", l.description, l.quantity,
       COALESCE(l."uomFactor", 1) AS "uomFactor", l."unitPrice", l."discountPercent", l.subtotal,
       COALESCE(l."taxAmount", 0) AS "taxAmount", l.total, s.sg * l.subtotal AS "signedSubtotal",
       s.sg * COALESCE(l."taxAmount", 0) AS "signedTax", fx.rate AS "exchangeRate",
       s.sg * l.subtotal * fx.rate AS "functionalSubtotal",
       s.sg * COALESCE(l."taxAmount", 0) * fx.rate AS "functionalTax"
FROM "Document" d
JOIN "DocumentLine" l ON l."documentId" = d.id
CROSS JOIN LATERAL (SELECT CASE WHEN d."documentType"::text = 'credit_note' THEN -1 ELSE 1 END AS sg) s
CROSS JOIN LATERAL (SELECT CASE WHEN d."currencyId" IS NULL AND d."exchangeRate" = 1 THEN 1
                                ELSE COALESCE((SELECT sum(fj."baseDebit" + fj."baseCredit") / NULLIF(sum(fj.debit + fj.credit), 0)
                                                 FROM "JournalEntry" fe JOIN "JournalLine" fj ON fj."journalEntryId" = fe.id
                                                WHERE fe."sourceType" IN ('invoice', 'credit_note') AND fe."sourceId" = d.id
                                                  AND fe."reversedEntryId" IS NULL), d."exchangeRate") END AS rate) fx
LEFT JOIN "Document" rd ON rd.id = d."reversedDocumentId"
JOIN "Document" cd ON cd.id = COALESCE(rd.id, d.id)
LEFT JOIN "Product" p ON p.id = l."productId"
LEFT JOIN "Partner" c ON c.id = d."partnerId"
LEFT JOIN "Branch" b ON b.id = d."branchId"
WHERE d."organizationId" = current_setting('app.org_id', true)
  AND d."documentType"::text IN ('sales_invoice', 'credit_note')
  AND d.status::text IN ('posted', 'paid')
UNION ALL
SELECT v."organizationId", 'pos', v.id, v."invoiceNumber", 'pos_invoice', v.status::text, v."issueDate"::date,
       v."branchId", b.name, v."partnerId", c.name, 'pos', NULL, i."lineNumber", i."productId", p.code, p.name,
       i.description, i.quantity, COALESCE(i."uomFactor", 1), i."unitPrice", i."discountPercent", i.subtotal,
       COALESCE(i."taxAmount", 0), i.total, i.subtotal, COALESCE(i."taxAmount", 0), fx.rate,
       i.subtotal * fx.rate, COALESCE(i."taxAmount", 0) * fx.rate
FROM "Invoice" v
JOIN "InvoiceItem" i ON i."invoiceId" = v.id
CROSS JOIN LATERAL (SELECT CASE WHEN v."currencyId" IS NULL AND v."exchangeRate" = 1 THEN 1
                                ELSE COALESCE((SELECT sum(fj."baseDebit" + fj."baseCredit") / NULLIF(sum(fj.debit + fj.credit), 0)
                                                 FROM "JournalEntry" fe JOIN "JournalLine" fj ON fj."journalEntryId" = fe.id
                                                WHERE fe."sourceType" IN ('pos_invoice') AND fe."sourceId" = v.id
                                                  AND fe."reversedEntryId" IS NULL), v."exchangeRate") END AS rate) fx
LEFT JOIN "Product" p ON p.id = i."productId"
LEFT JOIN "Partner" c ON c.id = v."partnerId"
LEFT JOIN "Branch" b ON b.id = v."branchId"
WHERE v."organizationId" = current_setting('app.org_id', true)
  AND v.status::text IN ('posted', 'paid', 'refunded')
UNION ALL
SELECT r."organizationId", 'pos', r.id, v."invoiceNumber", 'pos_refund', 'posted', r."createdAt"::date, v."branchId",
       b.name, v."partnerId", c.name, 'pos', NULL, i."lineNumber", i."productId", p.code, p.name, i.description, rq.qty,
       COALESCE(i."uomFactor", 1), i."unitPrice", i."discountPercent", rq.sub, rq.tax, rq.sub + rq.tax, -rq.sub,
       -rq.tax, fx.rate, -rq.sub * fx.rate, -rq.tax * fx.rate
FROM "PosRefund" r
JOIN "Invoice" v ON v.id = r."invoiceId"
CROSS JOIN LATERAL (SELECT CASE WHEN v."currencyId" IS NULL AND v."exchangeRate" = 1 THEN 1
                                ELSE COALESCE((SELECT sum(fj."baseDebit" + fj."baseCredit") / NULLIF(sum(fj.debit + fj.credit), 0)
                                                 FROM "JournalEntry" fe JOIN "JournalLine" fj ON fj."journalEntryId" = fe.id
                                                WHERE fe."sourceType" = 'pos_refund' AND fe."sourceId" = r.id
                                                  AND fe."reversedEntryId" IS NULL),
                                              (SELECT sum(fj."baseDebit" + fj."baseCredit") / NULLIF(sum(fj.debit + fj.credit), 0)
                                                 FROM "JournalEntry" fe JOIN "JournalLine" fj ON fj."journalEntryId" = fe.id
                                                WHERE fe."sourceType" = 'pos_invoice' AND fe."sourceId" = v.id
                                                  AND fe."reversedEntryId" IS NULL), v."exchangeRate") END AS rate) fx
CROSS JOIN LATERAL (SELECT e ->> 'lineId' AS line, COALESCE((e ->> 'quantity')::numeric, 0) AS qty,
                           COALESCE((e ->> 'subtotal')::numeric, 0) AS sub,
                           COALESCE((e ->> 'taxAmount')::numeric, 0) AS tax
                      FROM jsonb_array_elements(r.items::jsonb) e) rq
LEFT JOIN "InvoiceItem" i ON i.id = rq.line
LEFT JOIN "Product" p ON p.id = i."productId"
LEFT JOIN "Partner" c ON c.id = v."partnerId"
LEFT JOIN "Branch" b ON b.id = v."branchId"
WHERE r."organizationId" = current_setting('app.org_id', true);

CREATE OR REPLACE VIEW bi.sales_lines AS
SELECT d."organizationId", d.id AS "documentId", d."documentNumber", d."documentType"::text AS "documentType",
       d.status::text AS status, d."issueDate", d."dueDate", d."branchId", b.name AS "branchName", d."partnerId",
       c.name AS "customerName", d."salespersonName", l."lineNumber", l."productId", p.code AS "productCode",
       p.name AS "productName", l.description, l.quantity, COALESCE(l."uomFactor", 1) AS "uomFactor", l."unitPrice",
       l."discountPercent", l.subtotal, l."taxAmount", l.total,
       CASE WHEN d."documentType"::text = 'credit_note' THEN -l.subtotal ELSE l.subtotal END AS "signedSubtotal",
       d."exchangeRate",
       COALESCE(pc.name, 'Uncategorised') AS "categoryName",
       CASE WHEN cd."sourceKind" IN ('sale', 'sale_down_payment', 'sale_milestone')
                 THEN COALESCE((SELECT x."saleType" FROM "SalesOrder" x WHERE x.id = cd."sourceId"), 'order')
            WHEN cd."sourceKind" = 'cash_sale' THEN 'cash'
            WHEN cd."sourceKind" IN ('fleet_delivery', 'fleet_charges') THEN 'fleet'
            WHEN cd."sourceKind" = 'project_contract' THEN 'project'
            WHEN cd."sourceKind" = 'collections_fee' THEN 'fee'
            WHEN cd."sourceKind" = 'recurring' THEN 'recurring'
            WHEN cd."sourceKind" = 'counterpart' THEN 'counterpart'
            WHEN cd."sourceKind" = 'opening_balance' THEN 'opening'
            WHEN EXISTS (SELECT 1 FROM "IntercompanyTransaction" x WHERE x."sourceDocumentId" = cd.id) THEN 'intercompany'
            WHEN cd."tableId" IS NOT NULL OR cd."sourceType" LIKE 'pos%' THEN 'pos'
            WHEN cd."sourceKind" IS NULL THEN 'direct'
            ELSE 'manual' END AS channel,
       CASE WHEN d."documentType"::text = 'credit_note' THEN -l.subtotal ELSE l.subtotal END * fx.rate AS "functionalSubtotal"
FROM "Document" d
JOIN "DocumentLine" l ON l."documentId" = d.id
LEFT JOIN "Document" rd ON rd.id = d."reversedDocumentId"
JOIN "Document" cd ON cd.id = COALESCE(rd.id, d.id)
CROSS JOIN LATERAL (SELECT CASE WHEN d."currencyId" IS NULL AND d."exchangeRate" = 1 THEN 1
                                ELSE COALESCE((SELECT sum(fj."baseDebit" + fj."baseCredit") / NULLIF(sum(fj.debit + fj.credit), 0)
                                                 FROM "JournalEntry" fe JOIN "JournalLine" fj ON fj."journalEntryId" = fe.id
                                                WHERE fe."sourceType" IN ('invoice', 'credit_note') AND fe."sourceId" = d.id
                                                  AND fe."reversedEntryId" IS NULL), d."exchangeRate") END AS rate) fx
LEFT JOIN "Product" p ON p.id = l."productId"
LEFT JOIN "ProductCategory" pc ON pc.id = p."categoryId"
LEFT JOIN "Partner" c ON c.id = d."partnerId"
LEFT JOIN "Branch" b ON b.id = d."branchId"
WHERE d."documentType"::text IN ('sales_invoice', 'credit_note')
  AND d.status::text NOT IN ('draft', 'cancelled', 'rejected');
