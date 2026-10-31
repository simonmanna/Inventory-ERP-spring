# ADR-S016: Crushing, screening & aggregate processing (manufacturing 2.3)

- Status: Accepted (autonomous, 2026-10-30)
- Brief: [`AUTONOMOUS_BUILD_CONSTRUCTION.md`](../AUTONOMOUS_BUILD_CONSTRUCTION.md) Phase 2.3; requirements
  [§3.2](../requirements/construction-materials-erp.md)
- Spec: [`docs/spec/manufacturing.md`](../spec/manufacturing.md) §2.3 — routes, permissions, data model, GL, status
- Migration: `V31__manufacturing_crushing.sql` (placeholder number, renumbered at merge)
- Related: ADR-S011 (multi-stage engine — reused, not extended), ADR-S012 (quarry: ROM / product stockpiles),
  ADR-S013 (quality: final lots), ADR-S008 (process costing — untouched, see §6)

## Context

Crushed aggregates are joint products: one tonne of ROM granite through a jaw → cone → screen circuit yields dust,
10 mm, 20 mm, base course and oversize in a split that drifts with the rock and the crusher settings. The client needs
plant / circuit set-up, runs by shift with feed in and product split out (belt scale, weighbridge or a survey %),
recirculated oversize, yield and loss, conversion cost (diesel, kWh, labour, wear parts, maintenance), cost per tonne per
product, stockpile surveys with approved adjustments, and plant hours / throughput. The 2.1 stage engine already has
stages, WIP per stage, co-/by-products / waste with joint-cost allocation, normal vs abnormal loss and LIFO reversal.

## Decision

1. **Sub-area of `manufacturing`, not a new module.** Routes under `/manufacturing/crushing/**`, gated by the
   manufacturing module switch; classes `Crushing*` in `manufacturing.{domain,application,web}`. It reads the quarry
   stockpile tables (quarry's exposed domain) and calls quality's `QualityLotService`; no cycle (quarry and quality do
   not depend on manufacturing).
2. **Plant circuit = routing + work centres.** A plant's ordered stages are the operations of a system-created routing
   with a manual-issue stage set-up; every equipment unit (jaw / cone / VSI / impact crusher, screen, conveyor, feeder,
   washer, generator) is a `WorkCenter` (`<PLANT>-<UNIT>`) with capacity t/h, power kW and its meter kind. Changing the
   stage list (names, order, lead work centre) creates a **new routing version**; profiles activated earlier keep theirs.
3. **Grading profile = versioned expected split → system-managed BOM.** One line per size fraction (main product,
   co-products, by-products, waste; stage that yields it; stockpile; valuation allocated / NRV / zero). Activation builds
   a BOM (feed 100 t → main %, co-products per stage with their stockpile locations, the profile's allocation rule) and
   sets it active **without** the BOM approval gate and **without** archiving other active BOMs of the same output (two
   plants can both make 20 mm). Only one active profile per plant × feed; a new version archives the old one and its BOM.
4. **A posted run is a multi-stage production order** of that BOM (feed location = feed stockpile, output location = the
   main fraction's stockpile, batch = run code), driven through the 2.1 engine in **one transaction**: issue the feed to
   stage 1; conversion cost (diesel litres × price and kWh × tariff per unit, crew hours × rate, manual wear-part /
   maintenance lines) to the stage of its unit (Dr WIP / Cr overhead absorbed); output stage by stage — earlier-stage
   fractions leave at their stage, pass-through good tonnes carry the cost on, the last stage yields the main fraction
   and its co-products and the rest is process loss. The last stage's expected yield is set to the profile's expected
   recovery, so loss up to it is absorbed and loss above it is written off as abnormal (engine rules). Reversal = the
   engine's `reverseOrder` (LIFO, products must still be in their stockpiles). Recirculated oversize is measured (KPI:
   circulating load) but never stocked.
5. **Stockpile surveys** (volume × bulk density = tonnes) snapshot book stock and variance on submit; approval needs
   `crushing_survey:approve` and a person other than the submitter, and applies the variance to the stock on hand
   through `StockService.adjust` (ADJUSTMENT_GAIN / LOSS via the posting engine, source `crushing_survey`); a posted
   survey can be reversed by the opposite adjustment.
6. **Process costing (ADR-S008) is not duplicated**: runs are order-costed through the stage engine; period process
   costing remains available for clients that prefer it on the same products.

## Consequences

- No engine change: everything 2.1 tests (WIP, joint cost, reversal) applies to crushing; the run detail links to its
  production order. System BOMs appear in the BOM list (name ends with "(system)"; notes say to edit the profile).
- New permissions `crushing:{read,setup}`, `crushing_run:{record,post}`, `crushing_survey:{record,approve}`; reports reuse
  `production:report`. Event `crushing.run.posted`.
- Gaps: no auto feed from `quarry.extraction.posted` (runs choose the stockpile); belt-scale / PLC capture is 2.7's SPI;
  per-unit fleet cost per hour and payroll actuals are 2.8; quality final lots open only when a final plan exists.
