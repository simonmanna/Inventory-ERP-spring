-- Sales readiness X-7: the delivery reconciliation reads the delivery-order lines of each sales order line.
CREATE INDEX IF NOT EXISTS "FleetDeliveryOrderLine_source_line_idx" ON "FleetDeliveryOrderLine" ("sourceLineId");
-- The invoice list merges back-office documents and POS invoices newest first per organisation.
CREATE INDEX IF NOT EXISTS "Document_org_created_idx" ON "Document" ("organizationId", "createdAt" DESC);
