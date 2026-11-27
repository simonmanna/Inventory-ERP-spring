-- Purchase readiness H10a / H10b (owner-approved 2026-11-27): purchase request and purchase order lines cannot carry a
-- negative or zero quantity, a negative price or a tax rate outside 0..100 (a negative line lowered a request's value
-- below its budget and approval band; a client tax rate flowed into the receipt voucher's input VAT).
--
-- The constraints are added NOT VALID, so rows written before this migration never block it; each is then validated
-- when the existing rows already satisfy it. A constraint left NOT VALID (a database with old violating rows) still
-- refuses every new or updated row; run ALTER TABLE ... VALIDATE CONSTRAINT after correcting those rows.

ALTER TABLE "PurchaseRequestLine"
    ADD CONSTRAINT "PurchaseRequestLine_quantity_positive" CHECK (quantity > 0) NOT VALID;
ALTER TABLE "PurchaseRequestLine"
    ADD CONSTRAINT "PurchaseRequestLine_estimatedUnitPrice_nonneg"
        CHECK ("estimatedUnitPrice" IS NULL OR "estimatedUnitPrice" >= 0) NOT VALID;
ALTER TABLE "PurchaseOrderLine"
    ADD CONSTRAINT "PurchaseOrderLine_quantity_positive" CHECK (quantity > 0) NOT VALID;
ALTER TABLE "PurchaseOrderLine"
    ADD CONSTRAINT "PurchaseOrderLine_unitPrice_nonneg" CHECK ("unitPrice" >= 0) NOT VALID;
ALTER TABLE "PurchaseOrderLine"
    ADD CONSTRAINT "PurchaseOrderLine_taxRate_range" CHECK ("taxRate" >= 0 AND "taxRate" <= 100) NOT VALID;

DO $$
DECLARE
    c record;
BEGIN
    FOR c IN SELECT * FROM (VALUES
            ('PurchaseRequestLine', 'PurchaseRequestLine_quantity_positive'),
            ('PurchaseRequestLine', 'PurchaseRequestLine_estimatedUnitPrice_nonneg'),
            ('PurchaseOrderLine', 'PurchaseOrderLine_quantity_positive'),
            ('PurchaseOrderLine', 'PurchaseOrderLine_unitPrice_nonneg'),
            ('PurchaseOrderLine', 'PurchaseOrderLine_taxRate_range')) AS v(tbl, con)
    LOOP
        BEGIN
            EXECUTE format('ALTER TABLE %I VALIDATE CONSTRAINT %I', c.tbl, c.con);
        EXCEPTION WHEN check_violation THEN
            RAISE NOTICE 'Constraint % on % left NOT VALID: existing rows violate it', c.con, c.tbl;
        END;
    END LOOP;
END $$;
