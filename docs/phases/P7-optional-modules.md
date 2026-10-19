# Phase P7 — Optional modules (parallelizable)

Prerequisite: P2–P5 merged (each module uses core, accounting, inventory, invoicing services).
Each module is independent: run them as parallel sessions/branches, one PR series per module.
Every module sits behind its `ENABLE_*` flag (`@ConditionalOnBooleanProperty("app.modules.<x>")`)
and `@RequiresModule` per organization. Read `AGENTS.md`, `docs/CONTRACT.md`, the module spec and
its generated table; the Nest module is the specification.

| Module | Spec | Routes | Notes |
|---|---|---|---|
| beverage | `docs/spec/beverage.md` | 10 | port `kernel/common/beverage-math.ts` + tests |
| fixedasset | `docs/spec/fixedasset.md` | 54 | no Nest specs — write tests from services (depreciation!) |
| hr | `docs/spec/hr.md` | 140 | split PRs: org → employees → time → leave → payroll → loans → reviews → self-service → reports |
| manufacturing | `docs/spec/manufacturing.md` | 42 | production consume/output through StockService, WIP account |
| rental | `docs/spec/rental.md` | 47 | availability overlap rules, deposits ledger, housekeeping job |
| repair | `docs/spec/repair.md` | 64 | status history, billing via invoicing, housekeeping job |
| orders | `docs/spec/orders.md` | 11 | reuse pos/invoicing services |
| communication | `docs/spec/communication.md` | 22 | Telegram + WhatsApp Cloud only (approved deviation), SSE stream, dispatch worker |

## Exit criteria per module
- Routes complete, Jest specs ported (where they exist) + new tests for untested services.
- Flag off → routes respond as Nest does with the module not imported; flag on → parity 0.
- Web screens for the module work (build web with matching `VITE_ENABLE_*`).
- PORT_STATUS updated.
