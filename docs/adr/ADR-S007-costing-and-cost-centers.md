# ADR-S007: Landed costing, configurable costing policy and cost-centre accounting

- Status: Accepted (autonomous, 2026-10-28) — owner / client accountant to confirm the defaults listed under "Open"
- Roadmap: item 1.8 (`docs/ROADMAP.md` §1.8)
- Migration: `V12__costing_and_cost_centers.sql`

## Context

A trading / distribution client (imported electronics, cement, steel; multi-branch, UGX with foreign-currency
imports) needs (1) landed cost — customs, freight, clearing, insurance added to the cost of the goods — across
receipts and suppliers, (2) a valuation method (weighted average or FIFO) that is an approved accounting policy, and
(3) Tally-style cost centres for management reporting. The parity port already had a single-receipt landed cost
(`/procurement/landed-costs`), per-product `costingMethod` and a flat `CostCenter` on journal lines.

## Decision

Additive extension; every Nest route keeps its path, verb, permission and behaviour.

### 1. Costing policy (`inventory`)

- `InventoryCostPolicy` per organisation or product category: method `AVCO | FIFO | SPECIFIC | STANDARD`,
  `effectiveFrom`, lifecycle draft → approved → active → superseded / cancelled, version per scope.
- `core` exposes the SPI `ProductCostingPolicy`; `ProductService` uses it: with an active policy, new products take the
  policy method, the other interchangeable method is refused, and AVCO ↔ FIFO cannot change on a product with stock
  movements. STANDARD / SPECIFIC stay selectable. Without a policy, behaviour is unchanged (Nest parity).
- Activation (manual, or the daily ShedLock job for due policies) re-costs products in scope preserving value:
  AVCO → FIFO adds one `OPEN-<policy>` lot at the running average for untracked quantity; FIFO → AVCO collapses lots
  to their weighted average (lots retired unless expiry / serial tracking needs them). Every re-costed quant is
  recorded in `InventoryCostMigrationLine`; a rounding difference ≥ 0.01 posts to stock valuation.

### 2. Landed cost voucher (`procurement`)

- New routes `/procurement/landed-cost-vouchers`, `/shipments`, `/cost-components`, `/landed-cost-reports/*`.
- A voucher (`LandedCost.voucherType = 'voucher'`) covers one or more posted receipts (bridge `LandedCostReceipt`,
  optionally via a `Shipment`) and charge lines with cost component, supplier + invoice ref (unique across live
  vouchers), currency + rate, recoverable tax, treatment (`capitalize | expense | recoverable_tax`), allocation basis
  (`value | quantity | equal | weight | volume | manual`) and an optional subset of receipt lines.
- Workflow draft → submitted → approved → posted → reversed; separate `landed_cost:*` permissions for create,
  approve, post, reverse, configure. Preview computes the allocation read-only.
- Posting (one transaction, `Idempotency-Key`, row claim on status): per capitalised charge, exact allocation
  (remainder on the last unit); the on-hand share raises the layer per the product's costing method (shared
  `LandedCostLayers`: lot, serials or AVCO average), the consumed share goes to COGS. Expensed charges debit their
  expense account with the cost centre; recoverable tax debits `tax_receivable`.
- **Credit side = landed-cost clearing account** (`2155`, mapping `landed_cost_clearing`) unless the line names a
  credit account. The forwarder / customs / insurer vendor bill is booked against the clearing account, so AP stays
  driven by vendor bills (AP tie-out intact) and nothing is expensed twice. A clearing report shows credited vs
  cleared and the open charges.
- Reversal removes the capitalisation from what is still on hand (per-unit increment × remaining quantity), reverses
  the entry and reclasses the part sold since from stock to COGS so the sub-ledger keeps tying to the GL.
- Product master gains `weight` and `volume` for the weight / volume bases.

### 3. Cost centres (`accounting`)

- `CostCenter` gains `parentId`, `branchId`, `category`, `description`, `managerUserId`, `validFrom/To`, and the
  tenancy + soft-delete Hibernate filters it was missing (the Nest-port list leaked across organisations — fixed).
- `CostCenterRulesService` runs inside `PostingService` for every entry: inline **split** rules
  (`dimensions.costAllocationRuleId` → one line per weighted target, exact sum), **defaults** for revenue / expense
  lines (partner → branch → user), **account rules** (`required` / `forbidden`), and validity (exists, active, valid on
  the posting date). Organisations that configure nothing post exactly as before.
- **Periodic allocation** rules move a source centre's period P&L balance to weighted targets with one balanced entry;
  once per rule and period, reversible.
- Reports: cost-centre P&L with hierarchy roll-up and an `unassigned` bucket (ties to the company P&L), centre ×
  account matrix. Budget vs actual per centre already existed (ADR-S005).
- Manual journal lines accept `costCenterId`, `branchId` and `dimensions`.

## Consequences

- One shared fix: `AccountResolverService.ensureByCode` now sets `isActive` / `isPostable` on a new account (with
  `@DynamicInsert` the DB defaults were not visible in the same persistence context, so a posting in the same
  transaction rejected the account as inactive).
- New permission keys are granted to existing Administrator roles by V12.
- Cost centre stays the first concrete dimension; roadmap 2.4 generic dimensions can generalise it without moving
  data (line-level `costCenterId` + `dimensions` JSON already exist).

## Known limitations (follow-ups)

- Estimated landed cost accrued at receipt with true-up on the actual invoice is not implemented; late actual costs
  (after receipt / partial sale) are.
- Supplier credit notes on a landed charge = reverse the voucher and post a corrected one.
- Allocation rows do not store the variant; variant products re-cost the base quant.
- FX difference between the voucher rate and settlement rate lands on settlement (not inventory), as intended.
- Approval routing by cost-centre manager uses the existing approvals engine only when configured; no automatic
  threshold routing yet.

## Open (client accountant to confirm; defaults shipped)

1. Valuation method per inventory class — default: no policy (legacy per product); recommended AVCO org-wide with a
   FIFO category policy for imported electronics.
2. Which components capitalise / which taxes are recoverable — defaults in the seeded cost-component master.
3. Cost-centre hierarchy, allocation drivers and required reports.
