-- Review 2026-11-17 (costing review, Tier 3): joint cost by net realisable value. The NRV method shares a joint cost
-- by each output's value at the split-off point: quantity × (sales value − the costs per unit still to come after the
-- split-off, such as further processing and selling). Those separable costs are kept on the main output's allocation
-- rule and on each co-product of the BOM.
ALTER TABLE "MfgCostAllocationRule" DROP CONSTRAINT "MfgCostAllocationRule_method_check";
ALTER TABLE "MfgCostAllocationRule" ADD CONSTRAINT "MfgCostAllocationRule_method_check"
    CHECK (method = ANY (ARRAY['quantity'::text, 'weight'::text, 'market_value'::text, 'fixed_pct'::text,
        'net_realisable_value'::text]));
ALTER TABLE "MfgCostAllocationRule" ADD COLUMN "mainSeparableCostPerUnit" numeric(20, 6)
    CONSTRAINT "MfgCostAllocationRule_mainSeparableCostPerUnit_check" CHECK ("mainSeparableCostPerUnit" >= 0);
ALTER TABLE "BomCoProduct" ADD COLUMN "separableCostPerUnit" numeric(20, 6)
    CONSTRAINT "BomCoProduct_separableCostPerUnit_check" CHECK ("separableCostPerUnit" >= 0);
