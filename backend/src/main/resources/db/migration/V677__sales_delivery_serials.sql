-- Sales readiness R4d (DEL-2): serial numbers issued on a delivery line (base units; one per unit of a
-- serial-tracked product). Captured at dispatch, passed to the stock issue, and handed back on a return.
ALTER TABLE "FleetLoadLine" ADD COLUMN "serialNumbers" JSONB;
