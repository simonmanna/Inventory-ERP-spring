# ADR-S085 — Gate pass (gate-out document beside every delivery)

- Status: proposed (2026-11-23). Owner request of 2026-11-23; the owner reviews it when merging.
- Extension: no NestJS counterpart. These are new routes in a new module (`com.inventoryerp.gatepass`); no existing
  route or response changed.
- Migration: `V621__gate_pass.sql` (`GatePass`, `GatePassLine`, `GatePassEvent`, four `gate_pass:*` permissions).
- Spec: `docs/spec/gate-pass.md`.

## Context

When goods leave the premises, the gate (security) needs a document that says who may take what out. That person
may be a driver with a vehicle, a person on foot, or a courier. The system has delivery notes (trip note, invoice and
cash-sale handover note) and weighbridge tickets. None of them is a gate control:
- nothing is verified at the gate;
- nothing stops the same goods going out twice;
- most delivery points have no document at all: stock transfers, pick orders, rental checkouts, repair returns and
  supplier returns.

The owner asked for a gate pass "anywhere that delivery is there" and decided the following (AskUserQuestion,
2026-11-23):
1. All delivery points in one release.
2. A pass may be issued at any time. The print then carries a warning when the goods are not yet issued from stock or
   not paid.
3. When the guard records the gate-out, the source is marked "departed" where it has that step.

## Decision

- **One document and one table for every source.** `GatePass.sourceType` is one of delivery (FleetLoad: sales order
  deliveries and fleet trips), sales_order and invoice (their handover), cash_sale, stock_transfer, pick_order, rental,
  repair or supplier_return (an inbound returned_goods debit note).
  - `sourceId` is a soft reference.
  - The source is read with read-only SQL (`GatePassSources`): number, branch, partner, whether it is cancelled,
    whether its goods are issued, payment state, transport details to prefill, and lines.
- **Snapshot plus live check.** The pass stores its lines and the warnings as they were at issue. The gate check
  re-reads the source: a source cancelled after issue blocks the gate-out.
- **No double pass.**
  - For each source line, the quantities on passes that are not void never exceed the source line quantity.
  - Related documents share a root: an invoice raised from a cash sale or sales order, and a delivery of an order, belong
    to that sale or order. Per product, the passes of the whole family never exceed the root's quantity.
  - An advisory transaction lock per root document serializes concurrent issues. `GatePassIT.concurrentIssue` proves
    exactly one of four simultaneous issues wins.
  - Voiding a pass frees its quantities.
- **Verification.** The QR code holds `number|verifyCode`, a random 6-character code that is never returned by the
  JSON API.
  - A scan checks the code.
  - A typed number is accepted, and the screen tells the guard to compare the printed pass.
  - The optional plate the guard sees must match the pass's plate. Letters and digits are compared; spaces, dashes
    and case are ignored.
  - A pass is single-use (`used`) and expires (`validUntil`: 1 to 168 hours, default 24).
- **Gate-out side effect, two-phase.**
  1. The gate-out commits first: the pass becomes `used`, with an event.
  2. In a new transaction, the source is marked departed through its owning module's application API, only where
     that step posts nothing:
     - FleetLoad: `FleetDispatchService.depart`, only from `dispatched`, so stock is already issued.
     - Handover of an order or invoice: `SalesHandoverService.update` with status dispatched.
     - Cash sale, including an invoice whose delivery the cash sale manages: `CashSaleService.updateDelivery` with
       deliveryStatus dispatched.
     - The driver and vehicle are filled from the pass when the source had none.
  - If the source's own rules refuse (for example a delivery handover without an address), the gate-out still stands
    and the reason is stored on the gate_out event (`sourceUpdate`).
  - Transfers, pick orders, rentals, repairs and supplier returns have no departure step that posts nothing. Their
    dispatch, ship, checkout or post steps move stock, so the pass only records the gate-out.
- **Branch scope.** A pass carries the source's branch. Issue, view, verify and gate-out call
  `BranchScopeService.assertBranch`, and the register filters by the user's branches.
- **Permissions.**

  | Permission | What it allows | Granted to (V621) |
  |---|---|---|
  | `gate_pass:read` | register, PDF | roles that run a delivery point |
  | `gate_pass:issue` | issue a pass | roles that run a delivery point |
  | `gate_pass:void` | void a pass | roles that approve or cancel deliveries |
  | `gate_pass:check` | gate screen: verify, gate-out, refuse | Administrators only; give it to the security role explicitly |

## Consequences

- The gate pass module depends on sales, fleet and core (application APIs) and on kernel. Nothing depends on it, so
  there is no module cycle.
- Each source's SQL is checked against the schema by `GatePassIT.sourcesSmoke`. A renamed column in a source table
  breaks that test, not production.
- Payment state is a guide on the print, not a block (owner decision 2). Deposits count as payment for an order.
  Credit customers will see "not paid" until they are invoiced and pay.
- Not done: Android, offline gate check, a camera QR scanner in the browser (USB and phone scanners that type into
  the box work), and automatic expiry jobs. Expiry is computed on read.
