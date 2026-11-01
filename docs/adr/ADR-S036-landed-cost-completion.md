# ADR-S036 — Landed cost completion: accrual at receipt, legacy reversal (Cost Management CM-6)

- Status: accepted (CM-6 built 2026-11-01)
- Plan: `docs/roadmap/cost-management-plan.md` (rev 3), G3, G15
- Migration: `V229__cm6_landed_cost_accrual.sql`
- Extension: no NestJS counterpart (the legacy `/procurement/landed-costs` routes keep their Nest contract).

## Context

- Landed costs were recognised only when the actual voucher posted. Stock received before the freight and duty bills
  arrived was carried at purchase price, and margins on early sales were overstated.
- The legacy per-receipt landed cost (Nest route) could not be reversed.

## Decision

### 1. Accrual at receipt — `LandedCostAccrualService` (costing)

**Rules.** `LandedCostAccrualRule` holds a rate in % of the receipt value. The rule is resolved **per receipt layer**
in this order:

1. supplier of the goods receipt
2. product category
3. Incoterm of the purchase order
4. organisation default

There is one active rule per scope; saving a rule replaces it.

**Accrue** (`POST /procurement/landed-cost-accruals`, once per goods receipt while open):

1. Each receipt layer gets `value × rate`.
2. The estimate is placed through `LineageCapitalisation` at the moment of accrual:
   - on hand → 1396 adjunct, with a value-only row
   - already sold → cost of sales
3. The credit side is **2156 Landed Cost Accrual**.
4. Lineage adjustment type: `landed_accrual`.

**Clear** (`/clear`, with a posted voucher that covers the receipt):

- The voucher capitalises the **actual** charges through its own engine.
- The accrual is then taken out **in full, exactly where it went**:
  - the still-open adjunct, with a negative value-only row
  - shares consumed at accrual, back from their targets
  - shares the release run moved to cost of sales since
- Dr 2156.
- The accrual account returns to zero for that receipt. The actual-vs-estimate difference lands on hand vs consumed
  through the voucher's lineage. Estimate and actual are both recorded on the accrual.

**Reverse** (`/reverse`): the same unwinding, for an accrual no voucher will clear.

**Reconciliation** (`GET /procurement/landed-cost-accruals/reconciliation`): open accruals vs the 2156 balance must
be 0.

**FX.** A difference between the charge rate and the settlement rate is realised FX gain / loss of the payment
(IAS 21). It is never capitalised (IAS 2), and nothing in the accrual or voucher path capitalises it.

### 2. Legacy per-receipt landed cost: reversal through the voucher engine

`POST /procurement/landed-costs/:id/reverse` (extension, `landed_cost:reverse`, Idempotency-Key) calls
`LandedCostVoucherService.reverseReceipt`, which shares the voucher's reversal:

1. The capitalisation is removed from the layers (quant, lot or serial; for STANDARD, the open adjunct).
2. The journal is reversed.
3. The share sold since is reclassed to cost of sales.

The existing Nest routes and JSON are unchanged.

### 3. Component constraints

- CM-2 already refuses `capitalize` on a component classified as not capitalisable (demurrage, selling, admin,
  storage).
- The seeded components carry those classes.
- No kind-level rule is added, because the kind list (`duty`, `freight`, `handling`, `insurance`, `other`) has no
  non-capitalisable member.

## Routes

| Verb | Path | Permission |
|---|---|---|
| GET / POST | `/procurement/landed-cost-accrual-rules` | `landed_cost:read` / `landed_cost:configure` |
| DELETE | `/procurement/landed-cost-accrual-rules/:id` | `landed_cost:configure` |
| GET | `/procurement/landed-cost-accruals`, `/:id`, `/reconciliation` | `landed_cost:read` |
| POST | `/procurement/landed-cost-accruals` (Idempotency-Key) | `landed_cost:post` |
| POST | `/procurement/landed-cost-accruals/:id/clear` (Idempotency-Key) | `landed_cost:post` |
| POST | `/procurement/landed-cost-accruals/:id/reverse` (Idempotency-Key) | `landed_cost:reverse` |
| POST | `/procurement/landed-costs/:id/reverse` (Idempotency-Key) | `landed_cost:reverse` |

## Messages (verbatim)

- `No landed-cost accrual rule applies to <receipt>; define a default rate`
- `Goods receipt <n> already has an open landed-cost accrual`
- `Goods receipt <n> is not posted`
- `<n> put no goods into stock; nothing to accrue on`
- `Landed cost voucher <code> is not posted`
- `Landed cost voucher <code> does not cover this goods receipt`
- `Only an open accrual can be cleared or reversed`
- `ratePct must be greater than 0 and at most 100`
- `Only a posted landed cost can be reversed`

## Deferred, with the reason

| Item | Deferred to | Reason |
|---|---|---|
| Generated supplier bills carrying separate tax lines | Owner decision | Input tax is recognised on the voucher journal today, and the VAT return reads it there. Moving it to the bill changes where tax is reported, which needs a parity decision |
| `Product.isLandedCost` and "create voucher from bill" | CM-10 | A convenience; vouchers can already link existing bills |
| Duty proposal by HS code | — | Optional in the plan; needs a tariff table |
| Migrating the voucher's basis split onto `AllocationCalculator.LAST` | CM-10 | Its golden test against `LandedCostVoucherIT` data would also cover the weight / volume bases; it remains in the exception register |
| Automatic accrual on every goods receipt | Owner decision | Accrual is an explicit step so existing receipts and the GRNI flow are unchanged |

## Tests

`ImportLandedScenarioIT` (2):

- 10 % supplier rule beats the 5 % default, giving accrual 1000 (1396 / 2156), Idempotency-Key required, duplicate
  409. 40 % sold and the release run moves 400 to COGS. The actual voucher is 1200. Clearing gives Dr 2156 1000 /
  Cr 1396 600 / Cr COGS 400; the adjunct ledger and the 2156 net for the receipt are 0, the reconciliation difference
  is 0, a double clear is refused, and tenant isolation holds.
- An unvouchered accrual reverses to zero. With no rule, the accrual is refused. Rule validation. A legacy landed
  cost is posted, half sold, then reversed (Idempotency-Key required, double reversal refused). Authorization
  matrix (9 routes).

`ProcurementIT` authorization count: 174.
