-- Sales readiness INV-12: dunning runs per customer and currency (amounts in different currencies are never added), so
-- each entry records the currency of its overdue amount. NULL on entries written before (single-currency runs).
ALTER TABLE "DunningEntry" ADD COLUMN "currencyCode" TEXT;
