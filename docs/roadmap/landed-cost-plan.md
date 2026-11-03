# Landed cost to ERP grade — roadmap LC-0 … LC-7 (rev 3.1)

> **Status (2026-11-03):** LC-0 … LC-7 delivered; see ADR-S053 and the done-reports in `docs/phases/LC-*.md`
> (deviations are listed there).

> Rev 3.1 changes:
> 1. Transfer pairing moves to a lineage table (supports splits).
> 2. Negative-stock cap.
> 3. Two-index duplicate design.
> 4. Reapproval rule is OR, as the owner decided.
> 5. FX kept apart from estimate variance.
> 6. Further refinements.
> 7. LC-0 step 0 is a technical implementation map, written before any migration.
> 8. Clean fixtures.

## Context

Three review rounds on the landed-cost subsystem (per-receipt landed cost, voucher, accrual). Rev 2 was approved in
principle. Rev 3 adds these refinements:

- Exact transfer-line matching, with cycle detection.
- A full AVCO distribution identity.
- VAT default taken from tax policy, not assumed.
- Duplicate-override that still works with the unique index.
- Reapproval compared on full disposition.
- Movement-level drill-down.
- FX on accrual clearing.
- Configurable return policy.
- Quarry and manufacturing cost reports.
- Tariff versioning.
- An end-to-end business scenario.

**Standing rules for the implementer:**
1. LC-4 and later do not start until LC-0 to LC-3 pass their financial acceptance tests.
2. Never weaken an acceptance test to make a phase pass.
3. If the existing architecture cannot meet an accounting requirement, document the defect and propose the smallest
   safe correction. Get the owner's approval before changing shared or legacy behaviour.
4. Reuse; never build a second engine. Only change what a failing test proves wrong.
5. One phase at a time: phase doc `docs/phases/LC-n-*.md`, then build, then a done-report. A phase is never closed on
   compilation or unit tests alone.

**Review claims checked against the code:**

| Claim | Verdict | Evidence |
|---|---|---|
| VAT is debited twice | No. Tax on top and a `recoverable_tax` charge are different amounts, and the entry balances. Gaps: tax on a `recoverable_tax` charge is accepted, and there is no non-recoverable tax case. | `LandedCostVoucherService.compute()` |
| AVCO allocation is wrong | Valid. `bumpAverage` uses `min(pool qty, receipt qty)`, so transfers count as consumption. STANDARD already uses `CostLineageService.distribute`. | `LandedCostLayers` |
| No link from a transfer-out row to its transfer-in row | Confirmed. `InventoryLedger` has only `referenceType`/`referenceId`, no pair column. | `V1__baseline.sql` |
| Reversal after consumption untested; approve from draft; exact-match duplicates; UI gaps | Valid. | ITs, `approve()`, `assertNoDuplicateInvoices`, web |

**Reuse:**
- `CostLineageService` (`distribute`, `byTarget`, `recordAdjustment`, `release`, `trace`) and `AvcoLineageCalculator`.
- `LineageCapitalisation`.
- `ApprovalLimitService.assertWithin`.
- `AllocationCalculator`.
- `GET /reports/inventory/gl-tieout`.
- The accrual reconciliation.
- `CostCloseService` and `QuarryCostingService` (for LC-6 reports).

**Constraint:** legacy `/procurement/landed-costs` keeps Nest behaviour. Changes go into the voucher and accrual
paths. Any legacy change is owner-approved and listed under Deviations.

ADR: `docs/adr/ADR-S053-landed-cost-erp-grade.md` records every accounting decision. Migrations: V290+ (check `main`
first; parallel sessions share it). Roadmap doc: `docs/roadmap/landed-cost-plan.md`.

---

## LC-0 — Accounting audit (gate)

**Step 0, before any migration or service change: `docs/audits/landed-cost-implementation-map.md`.** The owner reviews
it before step 1 starts. It is built by reading the code and covers:

- The actual schema of every table involved: `LandedCost*`, `InventoryLedger`, `StockTransfer` and its lines,
  `CostValueAdjustment`, `LandedCostAccrual*`, `Document` (vendor bills).
- Every posting flow, with file:line: GRN, transfer dispatch → transit → partial receipts in `StockDocService`
  (≈L800–912), production issue and completion, sale, return.
- Lineage services and how far they cover the targets.
- The tests affected.
- The proposed migrations with V numbers.
- The open questions.

**Fixtures:** every financial acceptance IT builds its own organisation and fixtures on the Testcontainers database.
No test relies on seed or demo data.

**Deliverable 1: `docs/audits/landed-cost-gl-trace.md`.** One debit/credit table per event. Each row names the account,
the posting code line, the stock-ledger value row and the lineage target. Events:

1. GRN.
2. Voucher post, for each treatment: capitalise, expense, recoverable tax, tax on top.
3. Generated bill and linked bill.
4. Payment with an FX difference.
5. Sale.
6. Production issue and completion.
7. Transfer.
8. Purchase return.
9. Release run.
10. Accrual accrue, clear and reverse.
11. Voucher reversal and legacy reversal.

**Deliverable 2: `LandedCostAuditIT`** (extends `ApiTestSupport`). After every step it asserts:
- Σ Dr = Σ Cr.
- `gl-tieout` within 0.05.
- The 2155, 2156 and 1396 subledger equals the GL.
- **Classification:** each landed-cost amount is in its expected account (stock, adjunct, WIP, COGS, input tax,
  expense).

**VAT:**
- New per-charge field `taxRecoverable`. For a new charge its default comes from the org tax configuration and the tax
  code of the component or charge. The `IMPORT_VAT` component and the tax codes carry recoverability.
- Overriding the default needs the new permission `landed_cost:override_tax`, and every override is audited.
- **Migration of existing charges:** a charge whose tax code has an explicit recoverability gets that value. Every
  other charge with `taxAmount > 0` gets `taxRecoverable = NULL`, meaning *unreviewed*. Unreviewed charges are listed in
  a review report (`GET /procurement/landed-cost-reports/tax-review`). Nothing is silently assumed recoverable.
- Historic postings stay unchanged (they were recognised as input tax). The ADR records this.
- **Guard:** a charge with `taxRecoverable = NULL` is refused by every new posting, reversal, reclass, credit-voucher
  and copy path, with `Charge <label>: tax recoverability is unreviewed`. It must be resolved in the tax review first.
  The resolution is audited.

| Case | Expected |
|---|---|
| Recoverable VAT on a capitalised charge | Dr stock (net) + Dr input tax / Cr clearing (gross) |
| Recoverable VAT on an expensed charge | Dr expense (net) + Dr input tax / Cr clearing |
| Non-recoverable VAT on a capitalised charge | Tax added to the allocated base and capitalised; no input tax |
| Non-recoverable VAT on an expensed charge | Tax included in the expense |
| `taxAmount > 0` on a `recoverable_tax` charge | Rejected: `Charge <label>: a recoverable tax charge cannot carry tax of its own` |
| Every case | Input tax is never debited twice |

**Scenarios** (AVCO, FIFO/lot, serial and STANDARD where relevant):
1. Reverse before any sale, after a partial sale, and after a full sale.
2. Reverse after partial production consumption.
3. Reverse after a transfer.
4. Reverse refused when a generated bill has payments.
5. Accrual lower than actual, and higher than actual.
6. Multi-currency charges; the FX difference at payment goes to the FX account and is never capitalised.
7. Two concurrent posts → one succeeds, the other gets 409.
8. Closed period:
   - Posting refused inside the books-lock period.
   - A late charge for a closed period posts with an open-period date (policy in the ADR).
9. Duplicate invoice rejected, including two concurrent submissions.
10. Multiple vouchers on one GRN, each layering on top of the last.
11. Tenant isolation.

Failures that belong to LC-1 or LC-2 stay `@Disabled("LC-n")` and are switched on there. Other defects are fixed in
LC-0.

## LC-1 — Valuation correctness (gate)

**Rule:** a transfer is not consumption. Landed cost follows the goods to wherever they physically are now.

### Transfer lineage (many-to-many)

Transfers already split: dispatch moves stock to a transit location, then partial receipts can land in several rows
(`partially_received`). A single pair column cannot represent that.

- **Migration:** new table `InventoryTransferLineage`:
  - Columns: id, organizationId, transferId, transferLineId, sourceLedgerId, destinationLedgerId, quantity, createdAt.
  - Unique key on (sourceLedgerId, destinationLedgerId).
  - Indexes on source and destination.
- **Written by the transfer posting in `StockDocService`, in the same transaction:**
  - Dispatch: source row → transit row.
  - Each partial receipt: transit row → destination row, for the received quantity.
  - Hops are chained: A → transit → B → transit → C.
- **Invariant, checked by tests and by the reconciliation:** for each source row, Σ quantity over its destinations ≤
  |source quantity|. The open remainder means the goods are in transit.
- **Backfill** historic rows conservatively from `StockTransfer` lines plus the ledger. A link is recorded only when
  quantity and identity (product, variant, batch, transfer line) match without ambiguity. Everything else goes to
  `LineageException` (`GET /inventory/lineage-exceptions`).
- **Distribution over a split:** the transfer share is divided over the destination rows pro rata by lineage
  quantity. The share for the untransferred remainder is parked in 1396 as in-transit, and released when the receipt
  posts.

### Distribution

`distribute(layer, c)` walks the receipt's movements in posting order. Targets:

| Target | Treatment |
|---|---|
| `on_hand` | Into the receipt pool (formula below) |
| `wip` | WIP, tagged with its production order |
| `cogs` | COGS |
| `scrap` | Scrap |
| `return` | Per the return policy below |
| `negative_stock` | Policy in the ADR |
| `transfer` | Follow the pair: recursively distribute the share over the destination pool from the paired in row |

**Rules for following transfers:**
- Every recursive share carries `rootAdjustmentId` and `sourceReceiptLedgerId`. The adjustment's unique key per
  (root, consumption ledger row) prevents double distribution.
- **Cycle detection:** the visited set of ledger IDs on the path. Revisiting an ID is a lineage defect: it is logged as
  a `LineageException`, the share is parked in 1396 with an exception flag, and the posting continues with that flag.
  The posting is never silently wrong.
- A depth guard (default 20) also applies.
- **Exception resolution workflow** (`landed_cost:resolve_lineage`, audited):
  - Assign the destination row by hand, or reassign a link.
  - Then release the parked 1396 amount through the normal distribution.
  - Or write it off to a mapped account, with a reason.
  - Every action is recorded against the exception.
- **Missing or unpaired transfer-in:**
  - Goods still in transit → park in the 1396 adjunct, released when the in row posts.
  - Historical unmatched → park in 1396 with an exception flag and list it on the reconciliation page.

### AVCO maths (ADR plus golden test)

**Definitions:**
- `c`: the charge allocated to one receipt layer.
- `s`: the survival fraction. It is the share of that receipt layer's units still in its valuation pool, computed by
  `AvcoLineageCalculator` from `qtyBefore`, the receipt quantity and every later event in the pool:
  - Pooled AVCO: each issue removes the same proportion of every unit in the pool, so after an issue of `q` from pool
    quantity `P`, `s ← s × (P−q)/P`.
  - Later receipts add units and don't change `s`.
  - Transfer-out is an issue for `s` and creates a transfer share that is followed.
  - Returns are issues routed to `return`.
  - Negative stock: an issue beyond the available quantity takes `s → 0`, and the uncovered part goes to the
    `negative_stock` target.

**Distribution:**
- Consumed shares per issue event: `q_avail = min(q, P)`, then `c × s_before × q_avail / P`, each to that issue's
  target. Because `q_avail ≤ P`, an issue can never take more than the remaining share.
- The excess `q − q_avail` (the issue that drove the pool negative) takes **none** of this receipt's charge. It is
  valued under the negative-stock policy.
- When a later receipt settles the negative quantity, that settlement is a new receipt layer with its own lineage. It
  never redistributes the earlier charge. A `settledNegativeLedgerId` marker prevents a second distribution.
- Golden test of its own: available 100, remaining share 100, issue 120 → 100 distributed, never 120.
- On-hand share: `c × s_final`.
- Pool update: only when `Q > 0` (current pool quantity) and the on-hand share > 0: `A_new = (V + c×s_final)/Q`.
- When `Q ≤ 0`, the on-hand share goes to the `negative_stock` policy target. It never goes into the average.
- When the receipt is fully consumed (`s_final = 0`), nothing goes to stock; the whole charge goes to the consumption
  targets.

**Identity and rounding:**
- The distribution must satisfy: `c = stock + WIP + COGS + scrap + return + negative_stock (+ transfer shares,
  recursively resolved)`, exactly at 6 dp.
- Each share is rounded HALF_UP to 6 dp. The last share in posting order absorbs the residual.
- Golden test `AvcoLandedMathTest` (pure JUnit): two receipts, issues, a partial transfer to B, a sale at B, a later
  receipt, negative stock, and a late charge. It checks the new averages, every share, and the identity, all against
  hand-computed figures.

### Lots, serials, STANDARD, production

- **Lots:** a split by transfer is followed through the lot's paired rows.
- **Serials:** each serial carries its own increment.
- **STANDARD:** keeps the adjunct. The test proves a transferred adjunct is released when the destination consumes it.
- **WIP → FG:**
  - On production completion the open WIP landed share moves to finished goods exactly once: the FG layer value, or
    the adjunct for STANDARD FG.
  - The FG sale then recognises it in COGS.
  - Verify in `StageExecutionService` and production completion, and wire it in if missing.
  - Do **not** redesign manufacturing costing; only carry the landed share.

### Returns policy

New setting `costing.landedCost.returnPolicy`, one value per return reason or one default:

| Policy | When | Treatment |
|---|---|---|
| `recover_from_supplier` | The supplier credits freight/duty too | Landed share parked in 1396 flagged `awaiting_recovery`. **No receivable** until the supplier credit note posts; the credit note then clears it. A configurable deadline (days) → expense, with an alert first |
| `expense` | No credit expected | Landed share → mapped "landed cost on returned goods" expense |
| `defect_claim` | Defective goods | Landed share → expense when returned. A claims receivable is recognised **only** when the claim is accepted (an accepted supplier or insurer document), crediting the recovery account. IAS 37 contingent-asset rule, stated in the ADR |

- The share is always taken from the returned units' lineage, never left in stock.
- A return after a partial reversal uses the recorded open adjustment, so nothing is taken out twice.
- The default policy is chosen by the owner and recorded in the ADR.

### Credit notes

- A credit voucher (`voucherType = 'credit'`) references the original voucher and carries negative charges. It runs
  through the same engine with the sign reversed.
- The vendor credit note debits the supplier and credits clearing.

### Reversal

`remove()` uses the recorded `CostValueAdjustment` rows per target, including destination pools and WIP. A reversal
mirrors its posting exactly.

### Acceptance tests

- Multi-hop transfer A→B→C.
- Partial transfer.
- Multi-product transfer.
- Cycle injected → exception raised.
- Unpaired transfer-in → parked in 1396 and listed.
- Sale and production at the destination.
- Scrap.
- Negative stock followed by a later receipt.
- WIP → FG → sale.
- Each return policy.
- Credit note.
- Every after-step tie-out green, with correct classification.

`LandedCostVoucherIT` figures may change only where a transfer or a later receipt is involved; every change is
documented.

## LC-2 — Financial controls (gate)

### Approval

- New setting `costing.landedCost.allowDirectApproval` (default false). When false, `approve` accepts only
  `submitted`.
- `ApprovalLimitService.assertWithin("landed_cost_voucher", total, …)` applies thresholds.
- **`approvedHash`** is a canonical JSON hash of every money driver:
  - Shipment.
  - Receipt IDs and line IDs, products, quantities.
  - The eligible line set.
  - Per charge: component, treatment, basis, manual allocations, weight/volume/UoM used, amount, tax,
    `taxRecoverable`, currency, rate, supplier, invoice reference, bill mode, vendor bill, accounts, cost centres.
- **`approvedDisposition`** snapshot: every target with its amount — stock per product and location, WIP per production
  order, COGS per source, scrap, return, adjunct.
- **At posting:**
  1. **Inputs:** recompute the hash. **Any** change → refuse: "Voucher changed since approval; reopen and re-approve".
  2. **Inventory movement since approval:** recompute the disposition from current lineage; that figure is what gets
     posted. Compare it with the approved disposition **target by target**: stock per product and location, WIP per
     production order, COGS per source, scrap, return, adjunct.
     - **OR rule** (owner decision): refuse and require re-approval when any target's change is greater than
       `costing.landedCost.reapprovalTolerancePct` (default 5%) **or** greater than
       `costing.landedCost.reapprovalToleranceAmount` (default 1,000 base currency).
     - To switch one threshold off, set it very high. Setting a threshold to 0 means any change triggers.
     - **Zero approved amount, or a new target** (absent at approval, e.g. a new production order): the percentage is
       undefined, so only the amount threshold applies. Any non-zero new target counts as a change.
     - **A target that disappeared** compares to 0 the same way.
     - Example: a WIP→COGS shift at the same capitalised total still triggers.
- New action `reopen`: approved or submitted → draft, clears the approval.
- The audit keeps the approved snapshot, the posted snapshot and their diff.

### Duplicate invoices

- Store the original reference and `supplierInvoiceRefNorm`.
- Normalisation policy: setting `procurement.invoiceRef.normalization`, `strict` (default: upper case, trimmed, the
  separators ` -_/.` removed) or `exact` (upper case, trimmed only). Documented in the ADR, with the false-positive
  example `AB-12` vs `A-B12`.
- **Uniqueness that still allows an override:**
  - New column `duplicateOverrideId` (text FK to `InvoiceDuplicateOverride`: id, org, supplier, refNorm, reason,
    status `approved`|`revoked`, approvedBy, approvedAt, usedByChargeId).
  - Two partial unique indexes over live charges (status not cancelled or reversed, via a `live` boolean maintained on
    status change). No sentinel values are used.
    ```sql
    CREATE UNIQUE INDEX "LandedCostCharge_invoice_normal_uq" ON "LandedCostCharge"
        ("organizationId", "supplierId", "supplierInvoiceRefNorm")
        WHERE "duplicateOverrideId" IS NULL AND live;
    CREATE UNIQUE INDEX "LandedCostCharge_invoice_override_uq" ON "LandedCostCharge"
        ("organizationId", "supplierId", "supplierInvoiceRefNorm", "duplicateOverrideId")
        WHERE "duplicateOverrideId" IS NOT NULL AND live;
    ```
  - Plus a unique index on `InvoiceDuplicateOverride."usedByChargeId"`, so one override is used once.
  - **Before use, the service checks:** the override is approved, not revoked, in the same org, for the same supplier
    and refNorm, and unused. It sets `usedByChargeId` in the same transaction.
  - Concurrency: the advisory lock on (org, supplier, refNorm) serialises the check. The indexes are the backstop; a
    unique violation is mapped to the same 409.
- An override needs `landed_cost:override_duplicate` and a reason, is audited, and is listed in the reports.
- The conflict message shows the **original** conflicting reference and the voucher code.
- Credit vouchers are exempt; they reference their original.

### Allocation validation

- Weight or volume basis lists the products missing the data.
- Quantities are converted to the base UoM; refused when there is no conversion.
- Zero or negative units refused.
- Σ allocated == charge base.
- The weight, volume and UoM used are stored on the allocation row, so later product edits never change history.

### Engine consolidation

The voucher basis split moves onto `AllocationCalculator` (LAST rounding), with golden tests against the current
figures.

## LC-3 — Reconciliation (gate)

`GET /procurement/landed-cost-reports/reconciliation` returns six rows (subledger, GL, difference, drill-down):

| Row | Proves |
|---|---|
| 2155 | Outstanding supplier invoices |
| 2156 | Open estimates and open remainders |
| 1396 | Unreleased adjustments (filtered to landed-cost source types; optional dedicated `landed_cost_adjunct` mapping, decided in the ADR) |
| Stock | Landed-cost stock value vs GL |
| COGS | Landed-cost consumption vs GL |
| WIP | Open landed shares per production order vs GL |

**Also covered:**
- **Classification check:** every landed-cost journal line is compared with the account its lineage target implies;
  mismatches are listed even when totals balance.
- **Location check:** stock adjustments are compared per warehouse against their lineage location.
- **Exceptions:** `LineageException` rows and parked 1396 amounts.

**Drill-down chain:** `GET /procurement/landed-cost-reports/trace?voucherId|chargeId|journalLineId`.
- Requires `landed_cost:read`. Every hop is filtered by `organizationId`, and RLS applies.
- A test proves that org B gets a 404 on org A's IDs and that no foreign rows appear in a chain.
- Purchase chain: voucher → charge → receipt → receipt line → stock movement (incl. transfer hops) → cost adjustment →
  journal line.
- Production chain: voucher → receipt → material issue → production order → WIP → FG layer → FG sale → COGS line.
- Built on `CostLineageService.trace`.

**UI and close:**
- "Reconciliation" tab with cards, an exception list and a trace view (tree).
- Period-close checklist item: differences 0, no classification or location mismatches, no unresolved exceptions.

## LC-4 — Accrual management

**Rule migration:**
- `name`, `effectiveFrom`/`effectiveTo`, `isActive`.
- `rateType` (`percent` | `per_unit` | `per_kg`), `currencyCode`, `uomId`, `weightBasis` (`net` | `gross`).
- `expectedSettlementDays`.

**Rule logic:**
- Precedence is fixed: supplier > category > Incoterm > default.
- Overlapping active rules at the same scope → rejected.
- Quantities are converted through the UoM; per-kg uses product weight × base quantity.
- Missing weight → refused, with the products listed.

**FX and variance are kept apart.** Four effects, each with its own calculation and journal classification:

| Effect | Amount | Posted by | Accounts |
|---|---|---|---|
| 1. Accrual reversal | Original functional amount × cleared fraction (receipt-date rate) | Accrual clear | Dr 2156 / Cr the original targets |
| 2. Actual landed cost | Actual charge × **voucher/bill recognition rate** | Voucher | Dr targets (lineage) / Cr clearing |
| 3. Estimate variance | (2) − (1), in functional currency at recognition. Never contains FX movement after recognition | Implied by 1 + 2 | Lands on the targets through lineage; reported separately |
| 4. Realised FX | Payment rate vs recognition rate on the bill | Payment | FX gain/loss only; never capitalised |

- Each clearing row stores the original currency, amount and rate, the actual currency, amount and recognition rate,
  and the variance.
- Tests: an accrual in USD, an actual in EUR, payment at a third rate, and partial clears. They prove the four effects
  sum correctly, do not overlap, and that nothing from (4) reaches stock, WIP or COGS.
- The monitor and variance reports show the estimate variance and realised FX in separate columns.

**Partial clearing:**
- New `LandedCostAccrualClearing` table (accrual, voucher, functional amount, original and actual currency, amounts,
  rates).
- New accrual columns `clearedAmount` and `remainingAmount`; status `open` | `partially_cleared` | `cleared` |
  `reversed`.
- Each clear unwinds `min(the voucher's capitalised share on this GRN, remaining)` pro rata over the original targets.
- `close` unwinds the remainder. Over-clearing is refused.

**Tests:**
- 1000 accrued, cleared 600 → 250 → 150, remainder 0.
- Clearing above the remainder refused.
- Partial clears with an accrual in USD and vouchers in EUR and the functional currency.

**Monitor and UI:**
- `GET …/landed-cost-accruals/monitor`: age buckets, overdue, partly cleared, estimate vs actual.
- Rules page `/costing/landed-cost-accrual-rules` and a monitoring tab.

## LC-5 — Voucher UX

**Stepper edit:** General → Receipts → Charges → Allocation → Review.
- Autosave of drafts.
- Sticky summary: total, capitalised, expensed, tax, unallocated.
- Warnings:
  - Missing weight or volume.
  - No eligible goods.
  - Receipt reversed.
  - Pool changed.
  - Unpaired transfer or lineage exception.
  - Allocation changed since the last preview.
- `preview` returns `journal` lines, the disposition, `previewHash` and `computedAt`. The UI marks a preview stale once
  inputs change or the hash differs, and never shows it as current.
- The review step shows the actual journal and the supplier bills.

**Detail page:**
- Tabs: Overview, Receipts, Charges, Allocation (formula per row), Accounting, Supplier Bills, Trace (the LC-3 chain),
  Audit Trail.
- Approved vs posted diff.

**Other:**
- **Exceptions workspace:** unmatched transfer lineage (assign, reassign, release, write off) and the historical tax
  review (`taxRecoverable = NULL` charges).
- Responsive: horizontal scroll, or cards below 768px.
- GRN panel: Reverse button and "Create voucher from this receipt".
- Navigation: Cost Management → Configuration / Landed cost operations / Analysis and reconciliation.
- i18n: English keys only.

## LC-6 — Reports

**Landed cost:**
- Variance (estimate vs actual).
- Valuation impact by warehouse, product and method.
- By warehouse.
- By cost centre.
- Profitability before/after landed cost: same units sold and the same period, cost assigned by the units actually
  sold. Columns: purchase, landed, conversion, distribution. Selling and admin expenses shown separately, never added
  into product cost.

**Operational cost structure** (reporting extension only, no change to the valuation engine):
- Extraction → processing (crushing, screening, washing) → conversion → landed → internal distribution → FG cost per
  output UoM (tonne, m³, block), with production yield → COGS and margin.
- Built on `QuarryCostingService`, `CostCloseService` and the lineage data.
- Unit cost = cost ÷ **actual good output quantity** of the production or crushing run, in the output UoM (from
  completion and stage records), never input quantity.
- Yield = output ÷ input, shown alongside.
- By-products and co-products follow the existing quarry allocation.

CSV export on each tab.

## LC-7 — Advanced (owner may trim)

- **Incoterm responsibility matrix:** warn on charges that conflict with the PO's Incoterm.
- **Customs tariff engine:**
  - Versioned tariff lines (import country, HS code, origin country, effective from/to, source and legal reference,
    version).
  - Valuation basis (CIF/FOB/transaction), duty types (ad valorem / specific / compound).
  - Trade-agreement preferences, exemptions and special regimes, additional duties and excise.
  - Rates are validated at the **declaration date**.
  - Each calculation saves its inputs, the tariff version used and its outputs as a `CustomsCalculation` record, so it
    can be reproduced after tariff changes.
  - The record copies the rate and legal reference values, not just a pointer, and is immutable. A tariff edit creates
    a new version and never alters a past calculation.
  - Manual adjustments need permission and are audited.
  - Treatment follows the component and tax policy; nothing is auto-capitalised.
- Allocation templates.
- Freight and supplier cost history.

---

## Final acceptance (after LC-3, re-run at every later phase)

`LandedCostEndToEndIT` runs the real business chain:

1. Import raw material and spares → receive into warehouse W1.
2. Freight, customs and handling vouchers (multi-supplier, FX).
3. Transfer part of the goods to plant P.
4. Issue to production with conversion costs.
5. **Late landed charge** posts while some stock is still in W1, some in WIP and some already in FG.
6. Complete production → FG.
7. Transfer the FG to depot D.
8. Sell from D.
9. Accrue-then-clear, plus a supplier credit note.
10. **Reverse the late-charge voucher** after the goods have passed through two warehouses and production. Every
    target unwinds exactly what it received: W1 stock, WIP, FG at D, COGS.
11. Re-post a corrected voucher.

Checks:
- Every amount of the late charge lands in its correct current destination (W1 stock, WIP, FG at D, COGS).
- Reconciliation: 0 differences, 0 mismatches.
- The trace chain resolves from the supplier invoice to the COGS line.
- `gl-tieout` within tolerance.

## Critical files

- `backend/.../procurement/application/LandedCostLayers.java`, `LandedCostVoucherService.java`,
  `LandedCostReportService.java`
- `backend/.../inventory/application/CostLineageService.java`, `AvcoLineageCalculator.java`, `StockPostingService.java`,
  and the stock-transfer posting (`StockService.transfer`, `InventoryTransferLineage`)
- `backend/.../costing/application/LandedCostAccrualService.java`, `LineageCapitalisation.java`,
  `AllocationCalculator.java`, `QuarryCostingService.java`
- `backend/.../manufacturing/application/StageExecutionService.java`
- `backend/.../invoicing/application/VendorBillService.java`
- `backend/.../kernel/settings/SettingRegistry.java`, `kernel/approvals/ApprovalLimitService.java`
- `web/src/pages/costing/LandedCostVouchersPage.tsx`, `LandedCostReportsPage.tsx`, `CostOperationsPage.tsx`
- `web/src/features/procurement/landed-cost-panel.tsx`, `web/src/components/layout/app-shell.tsx`
- Tests: `LandedCostAuditIT`, `LandedCostEndToEndIT`, `AvcoLandedMathTest` (new), `LandedCostVoucherIT`,
  `ImportLandedScenarioIT`, `ValuationIntegrityIT`
- Docs: ADR-S053, `docs/roadmap/landed-cost-plan.md`, `docs/phases/LC-*.md`, `docs/audits/landed-cost-gl-trace.md`,
  `docs/PORT_STATUS.md`, `docs/spec/procurement.md` (Deviations)

## Verification (each phase)

1. `./gradlew compileJava compileTestJava -q`, then
   `./gradlew fastTest --tests '*LandedCost*' --tests '*ImportLanded*' --tests '*ValuationIntegrity*' --tests '*AvcoLanded*' --tests '*ProcurementIT'`.
2. Financial acceptance:
   - Every step balances.
   - `gl-tieout` within 0.05.
   - Reconciliation differences 0; no classification, location or unresolved lineage exceptions.
   - Golden AVCO identity exact.
   - `LandedCostEndToEndIT` green (from LC-3 on).
3. UI phases: `pnpm --filter web typecheck`, then preview against the local API. Walk through: voucher stepper (autosave,
   stale preview, journal) → submit → approve → post → reverse; accrual → partial clears → close; the reconciliation
   and trace views. Zero console errors; screenshots.
4. Close a phase: `cd backend && ./gradlew build` green (JaCoCo ≥ 0.80), push to main, done-report.
