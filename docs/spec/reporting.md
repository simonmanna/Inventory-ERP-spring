# Reporting — financial report engine and library

Extension module (not in the Nest reference). Design: [ADR-S043](../adr/ADR-S043-financial-report-engine.md).
Roadmap: [financial-reporting-plan.md](../roadmap/financial-reporting-plan.md).

## Routes

| Verb | Path | Permission | Purpose |
|---|---|---|---|
| GET | `/reports/library` | `report_library:read` | 15 categories with the reports the caller may run (engine reports and links) |
| GET | `/reports/library/{id}` | `report_library:read` + report's | Definition, period presets, compare options, layouts, export formats, `canSchedule` |
| POST | `/reports/run/{id}` | `report_library:read` + report's | Run with a `ReportRequest` body; returns header, columns, rows, totals, checks, meta, periods |
| POST | `/reports/run/{id}/export?format=csv\|xlsx\|pdf` | same | Same run as a file |
| GET | `/reports/runs?reportId=` | `report_library:read` | Recent runs (own; everyone's with `manage`) |
| POST | `/reports/runs/{runId}/verify` | `report_library:read` | Re-run and compare the result hash |
| GET/POST/DELETE | `/reports/views[?reportId=][/{id}]` | `report_library:read` (`manage` to share) | Saved views |
| GET/POST/PATCH/DELETE | `/reports/schedules[/{id}]`, POST `/{id}/run` | `report_library:schedule` | Scheduled delivery |
| GET | `/reports/fs-layouts[/{code}]` | `report_library:read` | Statement layouts |
| POST/PUT/DELETE | `/reports/fs-layouts[/{code}]`, POST `/{code}/reset` | `report_library:manage` | Edit layouts |

## Request (`ReportRequest`)

`period` (TODAY, YESTERDAY, THIS_MONTH, LAST_MONTH, THIS_QUARTER, LAST_QUARTER, THIS_YEAR, LAST_YEAR), `from`, `to`,
`asOf`, `branchIds`, `costCenterIds`, `partnerIds`, `accountIds`, `journalIds`, `sourceTypes`,
`dimensions` (`{"projectId": ["…"]}`), `postingStatus` (POSTED | ALL), `compare` (PRIOR_PERIOD, PRIOR_YEAR, VARIANCE,
VARIANCE_PCT), `layoutCode`, `page`, `limit`. A filter the report does not accept → 400
`"<field> is not a filter of report <id>"`.

## Engine reports (FR-0)

| Id | Category | Notes |
|---|---|---|
| `fs.profit-and-loss` | 01 | Layout `IFRS_SME_PL` by default; comparatives; account detail rows; KPI lines (EBITDA, GP %, OP %, NP %) |
| `fs.balance-sheet` | 01 | Layout `IFRS_SME_BS`; as-of; comparatives; profit not yet closed shown in equity |
| `gl.journal-lines` | 02 | Every drill lands here; opening and running balance for one account; paged |

Checks: `ALL_ACCOUNTS_MAPPED`, `NO_DOUBLE_MAPPING`, `BALANCED_<column>`, `TIES_TO_GL_<column>`, `DEBITS_EQUAL_CREDITS`.

## Default layouts

P&L (all lines credit − debit, so costs are negative): Revenue, Sales returns and discounts, **Net revenue**, Cost of
sales, **Gross profit**, Other operating income, Operating expenses (excl. 5600, 7200), Depreciation (5600),
**Operating profit**, Finance costs and FX losses (7200), Other expenses, **Profit before tax**, Income tax (no accounts
by default), **Net profit**; KPIs.

Financial position: cash and equivalents, receivables, inventories (incl. WIP), prepayments, other current assets;
PPE (incl. CIP), accumulated depreciation; payables, tax, deferred revenue, other current liabilities; non-current
liabilities; equity, OCI reserve, profit not yet closed to retained earnings.

## Deviations

None (extension module).
