# ADR-S090 — Opening-stock import

- Status: **Proposed (2026-11-27, IPR Phase 4, task T32, requirement R5)**; built on branch `fix/ipr-4-opening`.
  Awaiting owner sign-off (see "Owner sign-off items").
- Source: IPR plan rev 3, R5 and Phase 4; readiness finding **S-5** ("There is no opening-stock import and no inventory
  go-live checklist").
- Extension: no NestJS counterpart (Nest has no opening-stock import). New routes only; no existing path, verb,
  permission string or response shape changes.
- Migration: `V715__opening_stock_import.sql` (new tables `OpeningStockImportRun`, `OpeningStockImportBatch`, RLS
  `tenant_isolation`). V716–V719 stay reserved and unused.
- Evidence: `docs/audits/ipr-evidence/4-opening-stock.md`.

## Context

Before this change opening stock could only enter through an approved **opening count** (owner decision D5, Phase I5),
one location at a time, with every line typed in the count screen. Since IPR 1b (S1) an opening count is only allowed
on a location with no stock history and only once. That is right for one store, but a go-live with thousands of items,
lots and serial numbers needs a file import that is:

- atomic (no half-imported location),
- idempotent (a retried request never posts twice),
- previewable (the exact journal before anything posts),
- reconcilable (stock ledger = journal = valuation), and
- limited to empty locations, with an approval.

## Decision

### Flow

| Step | Route | Permission | Writes |
|---|---|---|---|
| Preview | `POST /inventory/opening-stock/preview` | `inventory_valuation:manage` | none |
| Import (approve + post) | `POST /inventory/opening-stock/import` (Idempotency-Key required) | `inventory_valuation:post` | run, batches, then each batch's postings |
| Runs | `GET /inventory/opening-stock/runs`, `GET …/runs/{id}` | `inventory_valuation:read` | none |
| Batch detail (+ revalidation of an unposted batch) | `GET …/runs/{id}/batches/{batchId}` | `inventory_valuation:read` | none |
| Retry a failed batch | `POST …/runs/{id}/batches/{batchId}/retry` (Idempotency-Key required) | `inventory_valuation:post` | the batch's postings |
| Reconciliation | `GET …/runs/{id}/reconciliation` | `inventory_valuation:read` | none |

`inventory_valuation:manage` is the authority an opening count already needs (it values stock against equity);
`inventory_valuation:post` is the existing "post journal entries of valuation documents" permission.

### Rows and validation

The client parses the CSV (columns product, variant, location, quantity, unit_cost, lot, expiry, serials) and sends text
fields; the server validates each row: product (code or SKU) exists, is active and keeps stock; variant required for a
product with variants and refused otherwise; location exists, is active, is not a system location (`virtual`,
`transit`), is within the caller's data access, **has no ledger history** and no open count; quantity > 0 (≤ 6 dp, whole
for serial products); unit cost > 0 (≤ 6 dp; blank = the product's / variant's cost price; a STANDARD product enters at
its governing standard and an entered cost must equal it); lot required for lot-tracked products (unless
`inventory.batchAutoNumber`), refused otherwise; expiry `YYYY-MM-DD`, required for expiry-tracked products, only for
lot-tracked ones; serial numbers only for serial products, as many as the quantity when `inventory.serialPolicy` is
`required`, never more than the quantity, unique in the file and not already known; no duplicate (product, variant,
location, lot). Quantities are in the product's base unit. At most 20,000 rows per file.

### Batches, token, approval

Valid rows are grouped by location (first appearance order) and cut into **batches of at most 2,000 rows**. For each
batch the server computes a `batchToken` = SHA-256 of the batch's canonical rows plus a **snapshot** of every input the
posting reads: per product the costing method, cost price, tracking flags, `hasVariants`, product type, active flag,
category, `updatedAt`, the active standard id and governing standard, the resolved stock account and the serial policy at
the location; per variant active / deleted, cost price, `updatedAt`; the location's type, active flag, history state, open
count and access; the opening-equity account; the lot auto-number setting. `previewToken` = SHA-256 of the ordered batch
tokens. A preview with any error returns no token.

The import needs the `previewToken`, `approve: true` and an Idempotency-Key. If the organisation configures an approval
workflow for entity type `opening_stock_import`, the import is 403 "Opening stock import requires approval. Request ID: …"
until that request (entity id = the preview token, amount = the total value) is approved: **the approval is bound to the
token**, so any change of file or inputs needs a new approval. Without a workflow the poster's explicit `approve: true`
is the approval (see sign-off item 2).

### Idempotency

- Kernel Idempotency-Key, scoped to (organisation, key): a replay returns the first response.
- `OpeningStockImportRun ("organizationId", "importHash")` unique: `importHash` = SHA-256 of the file's canonical rows
  (resolved ids, normalised numbers, the unit cost as entered). The same file again (new key) is 409 "This file was
  already imported (run OSI-…)". The hash is checked before row validation, so the answer names the run even though
  the locations now have history.
- `OpeningStockImportBatch ("organizationId", "importHash")` unique (location + batch rows): the same location rows
  cannot be imported in two runs.

### Posting (one transaction per batch)

1. L1: run row `FOR UPDATE`, then batch row `FOR UPDATE`; a posted batch is 409 "Batch n of run OSI-… is already posted".
2. L3: costing keys shared — organisation, then each product of the batch in id order (`CostingLocks.shareProduct`).
3. L3b: the location freeze key **exclusively**, polled with `pg_try_advisory_xact_lock` (never queued, 10 s, then 409
   "Stock is moving at this location right now; retry the batch in a moment"). Every non-sale move at the location takes
   this key shared, so none can slip in between the history check and the posting.
4. The batch is validated again and its token recomputed under these locks. History of the run's own earlier batches
   (same location split over several batches) does not count. Any difference → 409 "Preview is stale — preview and
   approve again" and nothing of the batch is written.
5. `StockService.receive` per row (engine lock order `(productId, variantId)`): ledger type `opening_balance`,
   `referenceType = 'opening_stock_import'`, `referenceId` = batch id; lots and serial units are created by the engine.
   The engine takes L3/L3b again (re-entrant), then L4 quant, L5 `StockItem`, L6 lot / serial rows.
6. L7: one journal per batch, journal `INV`, `sourceType = 'opening_stock_import'`, `sourceId` = batch id, posting key
   `inventory:opening_stock_import:<org>:<batch>`: Dr each product's stock account (`StockPostingService.stockAccount`,
   STOCK_IN rule else `stock_valuation`; lines stamped with the product and location dimensions) at the ledger's own
   value (6 dp), Cr the `retained_earnings` mapping (opening equity, accounting policy P1) for the total.
7. The batch stores the ledger value, journal id, valuation of the location (products of the batch) before and after,
   and the run status is recomputed (`completed` when every batch is posted, else `partial` / `failed` / `posting`).

A batch that fails rolls back completely; its error and attempt count are written in a separate transaction and the
other batches continue. `retry` posts it again under the same rules. When its inputs changed meanwhile, retry answers
409 stale; `GET` of the batch then shows `revalidation` (valid, row errors, the new `batchToken` and the journal as it
would post now) and a retry carrying that `batchToken` is the re-approval.

### Reconciliation

Per batch and run: Σ ledger `totalValue` of the batch's rows = stock-account debits of its journal = its equity credit
= valuation delta recorded at posting = previewed value (tolerance 0.01), and the entry balances. The run is
`reconciled` only when every batch is posted.

## Consequences

- Go-live of a new store or organisation no longer needs line-by-line opening counts; integrity and GL tie-out are
  green on day 1 (gate G4 evidence: `OpeningStockImportIT`).
- The opening count stays (it is still the right tool for one small location); both post against opening equity.
- Ledger rows and journals are only appended (R1). Wrong opening stock is corrected like any other stock: a count or
  adjustment. There is no "reverse import" route; the generic journal reversal refuses the entry (it names the source).
- An opening import is dated **now** (posting date and effective time); there is no "as at" date. A go-live after the
  books' opening date must post in an open period.

## Owner sign-off items

1. Opening equity account = `retained_earnings` mapping (same as the opening count, policy P1).
2. Approval: without a configured `opening_stock_import` workflow, the poster (`inventory_valuation:post`) approves by
   confirming the previewed journal; there is no built-in second person. Configure the workflow for maker-checker.
3. Zero-cost opening rows are refused (unit cost > 0 required; blank takes the product cost price).
4. A STANDARD product enters at its standard; a different file cost is refused rather than posted as a variance.
5. Import dated now (no back-dated opening date).
