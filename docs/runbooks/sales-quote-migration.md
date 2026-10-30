# Runbook — CRM → Sales quotation migration (V24, ADR-S011 phase S0)

V24 copies `CrmQuotation` / `CrmQuotationLine` into `SalesQuotation` / `SalesQuotationLine` with the same ids,
numbers, statuses, approval-request ids and converted-document references, moves the quote policy from
`CrmSettings` to `SalesSettings`, and write-blocks the CRM tables. `/crm/quotations*` keeps its JSON (it delegates to
`SalesQuotationService`).

## 1. Verify after deploy (per organization)

```sql
-- Row counts and totals match
SELECT c."organizationId",
       count(*)                       AS crm_quotes,
       (SELECT count(*) FROM "SalesQuotation" s WHERE s."organizationId" = c."organizationId"
          AND s."migratedFrom" = 'crm') AS sales_quotes,
       sum(c.total)                   AS crm_total,
       (SELECT sum(total) FROM "SalesQuotation" s WHERE s."organizationId" = c."organizationId"
          AND s."migratedFrom" = 'crm') AS sales_total
FROM "CrmQuotation" c GROUP BY 1;

-- Field-by-field differences (expect 0 rows)
SELECT c.id FROM "CrmQuotation" c JOIN "SalesQuotation" s ON s.id = c.id
WHERE (c.number, c.revision, c.status, c.total, c."partnerId", coalesce(c."dealId", ''),
       coalesce(c."approvalRequestId", ''), coalesce(c."convertedId", ''))
   IS DISTINCT FROM
      (s.number, s.revision, s.status, s.total, s."partnerId", coalesce(s."dealId", ''),
       coalesce(s."approvalRequestId", ''), coalesce(s."convertedId", ''));

-- Lines (expect equal counts and amounts)
SELECT (SELECT count(*) FROM "CrmQuotationLine"), (SELECT count(*) FROM "SalesQuotationLine" l
   JOIN "SalesQuotation" q ON q.id = l."quotationId" WHERE q."migratedFrom" = 'crm'),
       (SELECT sum("lineTotal") FROM "CrmQuotationLine"), (SELECT sum(l."lineTotal") FROM "SalesQuotationLine" l
   JOIN "SalesQuotation" q ON q.id = l."quotationId" WHERE q."migratedFrom" = 'crm');

-- Pending approvals still resolvable (entity type unchanged: crm_quotation)
SELECT r.id, r."entityId" FROM "ApprovalRequest" r LEFT JOIN "SalesQuotation" s ON s.id = r."entityId"
WHERE r."entityType" = 'crm_quotation' AND s.id IS NULL;   -- expect 0 rows

-- Settings copied
SELECT c."organizationId" FROM "CrmSettings" c JOIN "SalesSettings" s USING ("organizationId")
WHERE (c."quoteMaxDiscountPercent", c."quoteApprovalAmount", c."quoteValidityDays", c."creditCheck")
   IS DISTINCT FROM (s."quoteMaxDiscountPercent", s."quoteApprovalAmount", s."quoteValidityDays", s."creditCheck");
```

Then click through `docs/VERIFY_CHECKLIST.md` → "Sales S0": open an old quotation in the CRM Quotations screen,
check number / lines / status, revise it, approve a pending one.

## 2. Known differences
- Audit rows written before V24 use entity `CrmQuotation`; new rows use `SalesQuotation`. Query both when tracing a
  quotation's history.
- Revisions made before V24 have no snapshot (CRM overwrote them in place); snapshots start with the first revise
  after V24.
- `CrmSettings` quote columns are still written (mirror) by `/crm/config/settings` but are no longer read.

## 3. Rollback (only if the release is withdrawn)

1. Deploy the previous release (pre-V24 code). Do **not** run Flyway clean.
2. Copy quotations created or changed after V24 back to CRM:

```sql
BEGIN;
SET LOCAL app.allow_crm_quote_write = 'on';
INSERT INTO "CrmQuotation" (id, "organizationId", number, revision, "dealId", "partnerId", "contactId", "ownerId",
       status, "issueDate", "validUntil", "currencyCode", subtotal, "discountTotal", "taxAmount", total,
       "maxDiscountPercent", notes, terms, "approvalReasons", "approvalRequestId", "approvedById", "approvedAt",
       "rejectedById", "rejectionReason", "sentAt", "acceptedAt", "convertedType", "convertedId", "convertedNumber",
       "convertedAt", "createdById", "createdAt", "updatedAt", "deletedAt")
SELECT id, "organizationId", number, revision, "dealId", "partnerId", "contactId", "ownerId", status, "issueDate",
       "validUntil", "currencyCode", subtotal, "discountTotal", "taxAmount", total, "maxDiscountPercent", notes,
       terms, "approvalReasons", "approvalRequestId", "approvedById", "approvedAt", "rejectedById",
       "rejectionReason", "sentAt", "acceptedAt",
       CASE WHEN "convertedType" = 'sales_order' THEN NULL ELSE "convertedType" END, "convertedId",
       "convertedNumber", "convertedAt", "createdById", "createdAt", "updatedAt", "deletedAt"
FROM "SalesQuotation"
ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, revision = EXCLUDED.revision, total = EXCLUDED.total,
       subtotal = EXCLUDED.subtotal, "taxAmount" = EXCLUDED."taxAmount", "discountTotal" = EXCLUDED."discountTotal",
       "approvalRequestId" = EXCLUDED."approvalRequestId", "convertedType" = EXCLUDED."convertedType",
       "convertedId" = EXCLUDED."convertedId", "updatedAt" = EXCLUDED."updatedAt", "deletedAt" = EXCLUDED."deletedAt";
DELETE FROM "CrmQuotationLine" WHERE "quotationId" IN (SELECT id FROM "SalesQuotation");
INSERT INTO "CrmQuotationLine" (id, "organizationId", "quotationId", "sortOrder", "productId", description, quantity,
       "listPrice", "unitPrice", "discountPercent", "priceSource", "priceRuleId", "taxId", "taxInclusive",
       "lineSubtotal", "lineTax", "lineTotal")
SELECT id, "organizationId", "quotationId", "sortOrder", "productId", description, quantity, "listPrice",
       "unitPrice", "discountPercent",
       CASE WHEN "priceSource" IN ('pricelist', 'agreement') THEN 'manual' ELSE "priceSource" END, "priceRuleId",
       "taxId", "taxInclusive", "lineSubtotal", "lineTax", "lineTotal"
FROM "SalesQuotationLine";
UPDATE "CrmSettings" c SET "quoteMaxDiscountPercent" = s."quoteMaxDiscountPercent",
       "quoteApprovalAmount" = s."quoteApprovalAmount", "creditCheck" = s."creditCheck",
       "quoteValidityDays" = s."quoteValidityDays", "defaultQuoteTerms" = s."defaultQuoteTerms",
       "autoWinOnConversion" = s."autoWinOnConversion"
FROM "SalesSettings" s WHERE s."organizationId" = c."organizationId";
DROP TRIGGER "CrmQuotation_write_block" ON "CrmQuotation";
DROP TRIGGER "CrmQuotationLine_write_block" ON "CrmQuotationLine";
COMMIT;
```

3. The sales tables stay (unused by the old code) until the release is re-applied; the Flyway history keeps V24.
