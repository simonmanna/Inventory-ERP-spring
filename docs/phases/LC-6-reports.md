# LC-6 — Landed cost reports (done-report)

- Plan: [`docs/roadmap/landed-cost-plan.md`](../roadmap/landed-cost-plan.md) § LC-6 · ADR: [ADR-S053](../adr/ADR-S053-landed-cost-erp-grade.md) § LC-6

| Report | Test (`LandedCostReportsIT`) |
|---|---|
| Variance (estimate vs actual, realised FX apart) | `estimateVariance` |
| Valuation impact by warehouse / product / method; by warehouse | `valuationImpactByWarehouse` |
| By cost centre | `byCostCentre` |
| Profitability by cost layer (sales after posting and late charges) | `profitabilityByLayer`, `profitabilityLateCharge` |
| Operational cost structure per output unit with yield | `productionStructure` |

UI: six tabs on *Landed Cost Reports*, CSV export on each. Endpoint: `GET /procurement/landed-cost-reports/analysis/{kind}`.
