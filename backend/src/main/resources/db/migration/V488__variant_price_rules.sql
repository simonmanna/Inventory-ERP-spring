-- Phase I3 (audit P-1, ADR-S058 addendum): a pricing rule may name a product variant. A rule with a variant applies
-- only to that variant and beats the same tier's rule for the whole product; a rule without one keeps applying to
-- every variant (rules made before this change).

ALTER TABLE "PricelistItem" ADD COLUMN "variantId" TEXT REFERENCES "ProductVariant" (id) ON DELETE CASCADE;
ALTER TABLE "SalesPromotion" ADD COLUMN "variantId" TEXT REFERENCES "ProductVariant" (id) ON DELETE CASCADE;
ALTER TABLE "SalesAgreementLine" ADD COLUMN "variantId" TEXT REFERENCES "ProductVariant" (id);
