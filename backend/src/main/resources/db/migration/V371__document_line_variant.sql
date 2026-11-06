-- ADR-S058: invoice, vendor-bill and credit-note lines (DocumentLine) keep the product variant, so restocks,
-- auto-issues and printed documents use the variant that was sold or bought. Null = product without variants.
ALTER TABLE "DocumentLine"
    ADD COLUMN "variantId"   TEXT REFERENCES "ProductVariant" (id) ON DELETE SET NULL,
    ADD COLUMN "variantName" TEXT;
CREATE INDEX "DocumentLine_variantId_idx" ON "DocumentLine" ("variantId") WHERE "variantId" IS NOT NULL;
