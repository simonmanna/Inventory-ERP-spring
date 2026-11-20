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

## Correction 2026-11-20 — revenue, post-sale COGS, freight goods value

Shared source: `procurement/application/ProfitabilitySources` (used by `analysis/profitability` and
`product-profitability`). Tests: `LandedCostProfitabilityIT` + the corrected `profitabilityLateCharge`.

| Fix | Rule | Test |
|---|---|---|
| Revenue | POS receipts (`Invoice` posted/paid/refunded, by issue date) − POS refunds (`PosRefund`, by refund date) + sales invoices − credit notes (`Document` posted/paid, by issue date); line subtotal × document rate (base); a document whose `sourceId` is a POS receipt is skipped | `salesInvoiceRevenueAndStatuses`, `creditNotes`, `posAndInvoiceCountedOnce`, `foreignCurrencyInBase` |
| COGS | issue value + cost-value adjustment lines on units already issued, dated at posting (JE → voucher date → recorded) and taken back at `reversedAt` | `profitabilityLateCharge`, `lateChargeExactlyOnce`, `lateChargeReversed`, `cementAcceptance` |
| Reversal reclass | a voucher reversed through its lineage takes the landed share carried by units sold since back out of COGS on the reversal date (the GL's "consumed since" reclass); the report subtracts it in that period (average-cost, non-lot rows; lots unwind per batch and are not modelled) | `reversalAfterLaterSales` |
| Freight history | goods value = value basis for value-allocated charges, receipt stock-ledger value for every other basis | `freightHistoryQuantityAllocation` |

Rows now cover every product with revenue, an issue or a post-sale adjustment in the period (before: issue products
only in `analysis/profitability`, POS-invoiced products only in `product-profitability`).
