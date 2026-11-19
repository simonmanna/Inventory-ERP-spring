# Guide: in-app guided tours (ADR-S079)

Extension with no Nest counterpart. It teaches users the ERP on its real screens. Sales comes first.

## Routes

Any signed-in user may call these routes. Each one acts only on the caller's own rows in the caller's organization.

| Verb | Path | Body / response |
|---|---|---|
| GET | `/guide/progress` | `[{tourKey, tourVersion, status, currentStep, startedAt, completedAt, updatedAt}]`, most recent first |
| PUT | `/guide/progress/{tourKey}` | `{tourVersion: 1..10000, status: in_progress\|completed\|dismissed, currentStep: 0..500}` → the saved row |
| DELETE | `/guide/progress/{tourKey}` | 204. Forgets the tour; an unknown key is a no-op |

- `tourKey` must match `^[a-z0-9.-]{1,100}$`. Otherwise the response is 400 "tourKey must be 1-100 lowercase letters,
  digits, dots or dashes".
- `startedAt` is set on the first save.
- `completedAt` is set the first time the status is `completed` and is kept after that, even when the tour is replayed.

## Table

`GuideTourProgress` (V580): one row per (organization, user, tour). It has the tenant row-level-security policy, and
rows are removed when their user or organization is deleted.

## Web

- `web/src/features/guide/` holds the engine (`tour-store`, `tour-logic`, `TourOverlay`), the Learn panel
  (`GuidePanel`, opened by the graduation-cap button in the header) and the catalog (`tours/`).
- Every tour can also be read as a written tutorial ("Read as tutorial" in the Learn panel): the same steps,
  numbered and grouped by chapter.
- Anchors are `data-tour="…"` attributes. Sidebar links carry `nav:<path>`; tab triggers carry
  `sales.order.tab.<tab>` / `sales.cash.tab.<tab>`.
- A route pattern with a parameter (`/sales/orders/:id`) never matches the fixed pages beside it (`new`,
  `/sales/cash-sales/processing`), the same as the app's routes.
- Steps that depend on a document's state (e.g. "Confirm order" on a draft) show "I can't find this" with Skip when
  the button is not there.

### Sales tours

| Key | Status | Walks through |
|---|---|---|
| `sales.overview` | G1 | Dashboard flow: quotations → orders → deliver → collect |
| `sales.create-quotation` | G1 | New quotation: customer, dates, lines, totals, save, ribbon, next actions |
| `sales.quote-to-order` | G2 | Approve/accept (waits for Convert to appear) → convert dialog → sales order |
| `sales.confirm-and-deliver` (v2) | G3 | Confirm, delivery plan, add delivery, dispatch / confirm, delivery note PDF/print, line fulfilment |
| `sales.invoice-order` | G2 | Billing panel → draft invoice → post, register payment |
| `sales.cash-sale` (v2) | G3 | Branch cash sale: lines, payment, confirm & handover, delivery note, submit to head office |
| `sales.branch-cash-review` | G3 | Head office: processing queues, start review, checklist, approve/return/reject, generate invoice |
| `sales.branch-cash-full` | G3 | Five chapters: sale, handover + delivery note, submit, head-office review + invoice, Branch Cash Sales report |
| `sales.reports` | G3 | Reports hub filters, catalog, a report: rail, filters, drill-down, Excel/CSV/PDF/print |
| `sales.order-to-cash` (v2) | G3 | Five chapters: quotation, order, delivery + delivery note, invoice, Orders & Fulfilment report |

## Deviations

None. This is an extension, so there is no Nest behaviour to match.
