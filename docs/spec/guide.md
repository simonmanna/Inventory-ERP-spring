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
- Anchors are `data-tour="…"` attributes. Sidebar links carry `nav:<path>`.

### Sales tours

| Key | Status | Walks through |
|---|---|---|
| `sales.overview` | G1 | Dashboard flow: quotations → orders → deliver → collect |
| `sales.create-quotation` | G1 | New quotation: customer, dates, lines, totals, save, ribbon, next actions |
| `sales.quote-to-order` | G2 | Convert dialog → sales order |
| `sales.confirm-and-deliver` | G2 | Confirm, availability, delivery |
| `sales.invoice-order` | G2 | Billing panel → invoice |
| `sales.cash-sale` | G2 | Walk-in cash sale |
| `sales.order-to-cash` | G2 | Chains the four above |

## Deviations

None. This is an extension, so there is no Nest behaviour to match.
