-- V487 (ADR-S072): supplies side — a customer site can be linked to the customer's job (project), price lists can be
-- scoped to a customer site (resolution: site -> customer -> branch -> default), and delivery orders carry the project
-- of their site.

ALTER TABLE "FleetCustomerSite" ADD COLUMN "projectId" TEXT REFERENCES "Project" (id);
CREATE INDEX "FleetCustomerSite_project_idx" ON "FleetCustomerSite" ("organizationId", "projectId")
    WHERE "projectId" IS NOT NULL;

ALTER TABLE "Pricelist" ADD COLUMN "customerSiteId" TEXT REFERENCES "FleetCustomerSite" (id);
CREATE INDEX "Pricelist_site_idx" ON "Pricelist" ("organizationId", "customerSiteId") WHERE "customerSiteId" IS NOT NULL;

ALTER TABLE "FleetDeliveryOrder" ADD COLUMN "projectId" TEXT REFERENCES "Project" (id);
