# ADR-S025 — Generic analytic dimensions

Status: **accepted** (owner instruction "build it production ready", 2026-10-31; roadmap 2.4, PR-3). Implemented in
`V217`, `AnalyticDimensionService`, `AnalyticReportService`, `AnalyticDimensionController`,
`DocumentLineDimensionService` / `DocumentLineDimensionController` and the web page `/analytic-dimensions`.

## Context

`JournalEntry.dimensions` and `JournalLine.dimensions` (jsonb, baseline) already exist and are filled by fleet, HR,
expenses and cost-allocation splits with free-form tags (`vehicleId`, `driverId`, `costAllocationRuleId`, ...). Nothing
defines which keys are meaningful, validates the values, or reports on them. The cost centre (ADR-S007) is the only
first-class dimension, in its own column, with account rules (`AccountDimensionRule`, `dimension = 'costCenter'`).

## Decision

1. **Master data.** `AnalyticDimension` (code, name, active, sort order) and `AnalyticDimensionValue` (code, name,
   parent, active, valid from / to). The dimension code is the json key on ledger lines and never changes; a value
   code can change only until it is posted. `costCenter` and `costAllocationRuleId` are reserved codes.
2. **Storage stays the existing jsonb**, `{"<dimension code>": "<value code>"}`, so no data migration and no change to
   existing posting callers. Value *codes* (not ids) are stored so ledger lines read without joins and exports stay
   meaningful. A GIN index (`jsonb_path_ops`) supports containment lookups.
3. **Validation at posting** (`PostingService` → `AnalyticDimensionService.validate`), after the cost-centre rules: for
   each line's effective dimensions (its own, else the entry's) every key that names a defined dimension must be
   active and hold an active value valid on the posting date. Keys that are not defined dimensions pass unchanged, so
   system tags keep working and an organization that defines nothing posts exactly as before.
4. **Account rules generalise.** `AccountDimensionRule.dimension` accepts any dimension code (the old check allowed
   only `costCenter`). `required`: every line on the account must carry the dimension (closing journals —
   `period_close` / `fiscal_year_close` — are exempt because they move whole balances); `forbidden`: a line's own
   dimensions must not contain it. Cost-centre rules stay under `/cost-centers/account-rules` and the cost-centre
   service now ignores rules of other dimensions.
5. **Cost centre as a dimension.** Reports and document lines address it with the reserved code `costCenter` (value =
   cost centre id), mapped to the existing `costCenterId` column. That is the generalisation ADR-S007 §1.8.4 asked for,
   without moving data.
6. **Document lines.** `DocumentLine.dimensions` (jsonb) on invoices, credit notes and vendor bills, set while the
   document is a draft through `PUT /analytic-dimensions/documents/:id/lines` (a separate route keeps the Nest
   invoice contract unchanged). At posting, `DocumentBuilderService.groupForPosting` splits the revenue / expense
   lines per (account, dimensions); `costCenter` goes to the GL line's cost centre. Documents without line dimensions
   post exactly the same lines as before. On a vendor bill with a price variance the largest GRNI line absorbs it.
7. **Reports** (`report:accounting`): `GET /analytic-dimensions/reports/summary` (P&L by value with per-account detail,
   roll-up over child values, `unassigned` so totals tie to the company P&L), `/reports/trial-balance` and
   `/reports/lines` (drill-down). Filter syntax `dimension:value,dimension:value`; a parent value matches its children.
8. **Permissions**: `analytic_dimension:read`, `analytic_dimension:manage` (Administrator gets both).

## Consequences

- Dimensions are optional and additive; parity routes are untouched.
- A `forbidden` rule does not strip a dimension inherited from the entry header (unlike the cost centre, whose
  inherited value is dropped); it only refuses explicit line tags.
- Not in this ADR: dimension defaults (per partner / product / user), budgets per dimension, dimension sets on
  POS and stock postings. They can follow the cost-centre pattern later.
