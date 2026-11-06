-- SG-2: stock check policy on confirmation, alternative warehouses, alerts and "every order needs approval".
ALTER TABLE "SalesSettings"
    ADD COLUMN "stockCheckPolicy" TEXT NOT NULL DEFAULT 'warn' CHECK ("stockCheckPolicy" IN ('none', 'warn', 'block')),
    ADD COLUMN "allowAlternativeWarehouse" BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN "notifyOnHold" BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN "notifyOnShortage" BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN "orderApprovalAlways" BOOLEAN NOT NULL DEFAULT false;
