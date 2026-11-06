-- SG-0: the pricing engine returns source 'price_list' for named price lists (ADR-S022), but the line tables rejected it,
-- so any quotation or sales order line priced from one failed with a 500.
ALTER TABLE "SalesQuotationLine" DROP CONSTRAINT "SalesQuotationLine_priceSource_check";
ALTER TABLE "SalesQuotationLine" ADD CONSTRAINT "SalesQuotationLine_priceSource_check"
    CHECK ("priceSource" IN ('list', 'rule', 'customer', 'manual', 'pricelist', 'price_list', 'agreement'));

ALTER TABLE "SalesOrderLine" DROP CONSTRAINT "SalesOrderLine_priceSource_check";
ALTER TABLE "SalesOrderLine" ADD CONSTRAINT "SalesOrderLine_priceSource_check"
    CHECK ("priceSource" IN ('list', 'rule', 'customer', 'manual', 'pricelist', 'price_list', 'agreement', 'quotation'));
