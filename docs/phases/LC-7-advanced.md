# LC-7 — Landed cost advanced (done-report)

- Plan: [`docs/roadmap/landed-cost-plan.md`](../roadmap/landed-cost-plan.md) § LC-7 · ADR: [ADR-S053](../adr/ADR-S053-landed-cost-erp-grade.md) § LC-7
- Migration: `V295__lc7_customs_tariffs_templates.sql`

| Plan item | Test (`LandedCostCustomsIT`) |
|---|---|
| Incoterm responsibility matrix (preview warning; shipment / PO Incoterm) | `incotermWarning` |
| Versioned tariff lines; calculation reproducible after a tariff change; immutable and undeletable record | `versionedTariffAndReproducibleCalculation` |
| Valuation basis, ad valorem / specific / compound, additional duty, excise | `versionedTariffAndReproducibleCalculation`, `compoundDuty` |
| Preferences / origin, validity at the declaration date | `preferenceAndDateValidation` |
| Manual adjustment: permission, reason, audit | `manualAdjustment` (403 / 400 / 200) |
| Allocation templates | `templatesAndHistory` |
| Freight and supplier cost history | `templatesAndHistory` |

Fix found on the way: the CM-6 accrual rule by Incoterm never matched (wrong table); it now reads the shipment, else
the purchase order.
