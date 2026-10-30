# ADR-PROCUREMENT-001: Enterprise Source-to-Pay architecture

- Status: **Proposed**. Owner approval is needed before the first P1 build. P0 fixes may proceed under the existing
  post-parity fix rule.
- Brief: Enterprise Procurement & Source-to-Pay Architecture Audit (2026-10-30)
- Evidence: [`docs/audit/procurement-current-state-audit.md`](../audit/procurement-current-state-audit.md)
- Specs: [`spec/procurement.md`](../spec/procurement.md#target-source-to-pay-design-adr-procurement-001),
  [`procurement-postings.md`](../spec/procurement-postings.md),
  [`procurement-landed-cost.md`](../spec/procurement-landed-cost.md),
  [`procurement-approval-policies.md`](../spec/procurement-approval-policies.md),
  [`procurement-integration-contracts.md`](../spec/procurement-integration-contracts.md)
- Roadmap: [`docs/roadmap/procurement-roadmap.md`](../roadmap/procurement-roadmap.md)
- Migrations: none in this ADR. Future work uses `V{n}__procurement_<area>.sql` from the next free number (V26 as of
  2026-10-30; re-check `backend/src/main/resources/db/migration` first).

## Context

The construction-materials client (quarry, aggregates, concrete blocks, trading) needs a full Source-to-Pay system.
The parity port gives a PR → PO → GRN → bill → payment chain with GRNI, landed-cost vouchers and a weighbridge feed.
The audit found:
- Six P0 integrity defects.
- A duplicate supplier-payment engine inside procurement.
- No sourcing, agreements, change control, service receiving, inspection, commitment budgets or supplier qualification.

Two things constrain the design. Every Nest route keeps its contract (`AGENTS.md` §1, `docs/ROADMAP.md`:16). And no
duplicate inventory, AP, payment or GL engine may be introduced.

## Decision

### 1. Ownership (one owner per concept)

| Concept | Owner | Procurement's role |
|---|---|---|
| Requisitions, RFQs, quotations, evaluations, awards, agreements, POs, PO revisions, supplier confirmations | **procurement** | owns |
| Goods-receipt and service-acceptance *evidence* (GRN, service entry sheet), supplier returns, supplier claims | **procurement** | owns the document; stock effects go through inventory |
| Supplier master | **core** `Partner` | adds `SupplierProfile` (qualification, certificates, bank accounts, scorecard) as a procurement-owned 1:1 extension keyed by `partnerId` |
| Stock, ledger, valuation, cost layers, landed-cost revaluation of layers | **inventory** | calls `StockService` only |
| Weighbridge tickets, freight, fuel | **fleet** | consumes the ticket through its existing port; policy decides whether a ticket is mandatory |
| Incoming inspection | **procurement** at first (`IncomingInspection`). It moves to a `quality` module if one is ever split out of manufacturing. | owns |
| Vendor bills, AP, credit notes, **all supplier payments**, WHT at payment, realized FX | **invoicing** | supplies PO and receipt evidence; never posts AP or payments itself |
| GL, periods, budgets, **commitments / encumbrances** (new) | **accounting** | calls `PostingService` and the new `CommitmentService` |
| Approvals, workflow, sequences, audit, idempotency, outbox, files, settings | **kernel** | reuses them; no new approval framework |
| MRP demand | **manufacturing** | calls a new procurement port `PurchaseDemandPort.raise(...)` instead of inserting rows |
| Capital purchases | **fixedasset** | receives an `asset.acquired` hand-off from GRN or bill |

### 2. Payments: a single engine

`PurchasePayment` stops being written for new transactions once owner decision OD-3 is approved. The parity routes
change as follows:
- `POST /procurement/purchase-orders/{id}/pay` keeps its path, permission, request and response JSON. It becomes an
  adapter that calls `invoicing.PaymentService.record` with an allocation to the PO's posted bill(s). If no bill
  exists, it records an **advance / prepayment** against the PO (postings spec §6).
- `settleCashReceipt` does the same for cash POs.
- Existing `PurchasePayment` rows stay readable and are reported. They are never rewritten.

### 3. Additive extensions only

- New documents get new tables and new routes under `/procurement/...`.
- Parity tables may gain nullable columns that are not exposed in parity JSON (the ADR-S010 pattern).
- Parity bugs are fixed as `fix(procurement)!: … (post-parity)` with a Deviations entry, an
  `contract-tests/allowed-diffs.json` line and an IT.

### 4. A policy layer instead of hard-coded rules

`procurement.application.policy`:
- `ProcurementPolicy` is effective-dated, versioned, approved and audited, and scoped to org → branch → category →
  department.
- It is read through `ProcurementPolicyResolver`.
- With no policy configured, behaviour equals today's (Nest parity).
- Keys and resolution rules are in [`procurement-approval-policies.md`](../spec/procurement-approval-policies.md).

### 5. Document chain and traceability

Every downstream document stores the id of its upstream document(s): PR line → RFQ line → quotation line → award →
PO line (or agreement release) → GRN / service-entry line → bill line match → payment allocation. The chain is
queryable through `GET /procurement/documents/{type}/{id}/trace`. Posted documents are immutable; corrections are
made by reversal or by a new revision.

### 6. Concurrency and idempotency standard

- Every state-changing procurement route that moves money, stock or commitment takes a **required** `Idempotency-Key`.
- State transitions use `UPDATE … WHERE status = :expected` (or `@Version`).
- Cross-document consumption (bill ↔ receipt, release ↔ agreement, PO ↔ PR quantity) locks the consumed rows with
  `FOR UPDATE` in id order.
- All quantity and money arithmetic uses `BigDecimal` with `RoundingMode.HALF_UP` (`kernel/common/Money`).

### 7. Events

New events are added to `packages/shared` `DomainEvents` and regenerated (`tools/contracts2java`). Procurement listeners
that must not act on rolled-back work (approval decided → PO activate, receipt posted → commitment relieved) use the
transactional `EventOutboxService` path, not `EventBus.publish`, until the kernel ADR for P1-11 lands.

## Consequences

- P0 fixes come first and are small. The payment consolidation is the largest of them. It changes postings for
  `pay` (same JSON) and needs a parity allowed-diff entry for the GL rows.
- Each feature area ships as its own PR with `docs/PORT_STATUS.md` extension rows, ITs, authorization and tenancy tests.
- Supplier-portal and AI features read only through public application APIs. AI only recommends; approval, award and
  posting stay with authorized users.

## Alternatives considered

- **A new `s2p` module that replaces `procurement`.** Rejected: it would duplicate the 36 parity routes and the GRNI
  machinery, which already work.
- **Supplier payments owned by procurement.** Rejected: invoicing already owns AP residuals, WHT and payment approval.
- **An approval engine specific to procurement.** Rejected: kernel `ApprovalsService` plus policy-derived rules is
  enough once the amount re-check and segregation of duties are added.

## Open (owner / client accountant)

OD-1 to OD-7 in `docs/DECISIONS_FOR_OWNER.md`:
- The approval matrix values.
- VAT and WHT treatment for Uganda.
- Deprecating PO `pay` and cash auto-settlement.
- The default match mode.
- AP at receipt versus at bill.
- Approval of ADR-S003 line units.
- Explosives compliance fields.
