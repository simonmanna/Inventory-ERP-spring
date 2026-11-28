-- ADR-S094 C4 (§8.2): typed source ownership of customer documents.
--
-- Every sales invoice, credit note and proforma names the source that created it (Document.sourceKind). The value
-- comes from the code path that inserts the row (DocumentBuilderService, the only insert point). sourceType /
-- sourceId keep their existing values (parity).
--
-- Existing rows are backfilled from stored evidence, in this order: recurring run, engine counterpart, sale
-- milestone / down payment / sale, legacy cash sale, opening balance, delivery order (goods invoiced by fleet:
-- fleet_delivery; transport only on a Sales order's delivery order: fleet_charges), dunning fee, project billing,
-- credit-note links. Rows with no evidence become 'manual' and are listed as notices. This changes metadata only:
-- amounts, numbers and ledgers are untouched. Then the check is added and validated. NOT VALID alone is not enough,
-- because PostgreSQL re-checks a row on every UPDATE, so a payment on an old invoice would fail.

ALTER TABLE "Document" ADD COLUMN "sourceKind" text;

UPDATE "Document" d
SET "sourceKind" = CASE
    WHEN d."sourceType" = 'recurring' THEN 'recurring'
    WHEN EXISTS (SELECT 1 FROM "DocumentRelation" r JOIN "DocumentRelationType" t ON t.id = r."relationTypeId"
                 WHERE r."toDocumentId" = d.id AND r."organizationId" = d."organizationId"
                   AND t.code IN ('CANCELS', 'REISSUES') AND r.payload ->> 'engine' = 'true') THEN 'counterpart'
    WHEN d."documentType" = 'proforma_invoice' THEN CASE d."sourceType"
        WHEN 'sales_order_proforma' THEN 'sale'
        WHEN 'crm_quotation' THEN 'quotation'
        END
    WHEN d."documentType" = 'credit_note' THEN CASE
        WHEN d."reversedDocumentId" IS NOT NULL THEN 'credit'
        WHEN d.data ->> 'creditType' IS NOT NULL OR d.data ->> 'creditKind' IS NOT NULL THEN 'credit'
        WHEN EXISTS (SELECT 1 FROM "SalesReturn" x WHERE x."creditNoteId" = d.id) THEN 'credit'
        WHEN EXISTS (SELECT 1 FROM "RebateAccrual" x WHERE x."creditNoteId" = d.id) THEN 'credit'
        WHEN EXISTS (SELECT 1 FROM "FleetDeliveryDiscrepancy" x WHERE x."creditNoteId" = d.id) THEN 'credit'
        WHEN EXISTS (SELECT 1 FROM "SalesOrder" x WHERE x."reversalCreditNoteId" = d.id) THEN 'credit'
        ELSE 'manual'
        END
    WHEN d."documentType" = 'sales_invoice' THEN CASE
        WHEN d."sourceType" = 'sales_order' THEN CASE
            WHEN EXISTS (SELECT 1 FROM "SalesOrderMilestone" m WHERE m."documentId" = d.id) THEN 'sale_milestone'
            WHEN d.data ->> 'downPaymentFor' IS NOT NULL THEN 'sale_down_payment'
            ELSE 'sale'
            END
        WHEN d."sourceType" = 'cash_sale' THEN 'cash_sale'
        WHEN d."sourceType" = 'opening_balance' THEN 'opening_balance'
        WHEN EXISTS (SELECT 1 FROM "FleetDeliveryOrder" o WHERE o."goodsInvoiceId" = d.id)
            OR EXISTS (SELECT 1 FROM "FleetDeliveryInvoiceLine" l WHERE l."documentId" = d.id) THEN 'fleet_delivery'
        WHEN EXISTS (SELECT 1 FROM "FleetDeliveryOrder" o
                     WHERE (o."chargesInvoiceId" = d.id
                            OR EXISTS (SELECT 1 FROM "FleetDeliveryCharge" c
                                       WHERE c."deliveryOrderId" = o.id AND c."invoiceId" = d.id))
                       AND o."sourceType" = 'sales_order' AND o."sourceId" IS NOT NULL
                       AND EXISTS (SELECT 1 FROM "SalesOrder" s WHERE s.id = o."sourceId")) THEN 'fleet_charges'
        WHEN EXISTS (SELECT 1 FROM "FleetDeliveryOrder" o WHERE o."chargesInvoiceId" = d.id)
            OR EXISTS (SELECT 1 FROM "FleetDeliveryCharge" c WHERE c."invoiceId" = d.id) THEN 'fleet_delivery'
        WHEN EXISTS (SELECT 1 FROM "DunningEntry" e WHERE e."feeInvoiceId" = d.id) THEN 'collections_fee'
        WHEN EXISTS (SELECT 1 FROM "CustomerAdvance" a WHERE a."invoiceId" = d.id)
            OR EXISTS (SELECT 1 FROM "ProgressClaim" c WHERE c."invoiceId" = d.id OR c."retentionInvoiceId" = d.id)
            OR EXISTS (SELECT 1 FROM "RetentionLedger" r WHERE r."releaseDocumentId" = d.id) THEN 'project_contract'
        ELSE 'manual'
        END
    END
WHERE d."documentType" IN ('sales_invoice', 'credit_note', 'proforma_invoice')
   OR d."sourceType" = 'recurring'
   OR EXISTS (SELECT 1 FROM "DocumentRelation" r JOIN "DocumentRelationType" t ON t.id = r."relationTypeId"
              WHERE r."toDocumentId" = d.id AND t.code IN ('CANCELS', 'REISSUES') AND r.payload ->> 'engine' = 'true');

DO $$
DECLARE
    r record;
BEGIN
    FOR r IN SELECT "organizationId" AS org, "documentType"::text AS kind, count(*) AS n FROM "Document"
             WHERE "sourceKind" = 'manual' GROUP BY 1, 2 ORDER BY 1, 2 LOOP
        RAISE NOTICE 'ADR-S094 C4: % % document(s) of organization % have no module source and are manual',
            r.n, r.kind, r.org;
    END LOOP;
END $$;

ALTER TABLE "Document" ADD CONSTRAINT "Document_source_kind_value_check" CHECK ("sourceKind" IS NULL OR "sourceKind" IN
    ('sale', 'sale_down_payment', 'sale_milestone', 'cash_sale', 'quotation', 'project_contract', 'collections_fee',
     'fleet_delivery', 'fleet_charges', 'recurring', 'opening_balance', 'counterpart', 'credit', 'manual'));

ALTER TABLE "Document" ADD CONSTRAINT "Document_customer_source_kind_check"
    CHECK ("documentType" NOT IN ('sales_invoice', 'credit_note') OR "sourceKind" IS NOT NULL);

COMMENT ON COLUMN "Document"."sourceKind" IS
    'ADR-S094 §8.2: kind of the source that created the document (SourceRef); required on sales invoices and credit notes';
