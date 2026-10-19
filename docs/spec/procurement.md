<!-- spec: generated from tools/specs/module-specs.mjs; delete this line to edit by hand -->
# Procurement — `procurement`

| | |
|---|---|
| Phase | P4 |
| Nest source | `reference/nest-api/src/modules/procurement (+ kernel/three-way-match)` |
| Routes | 33 — full table: [generated/procurement.md](generated/procurement.md) |
| Feature flag | always on |
| Java package | `com.inventoryerp.procurement` |

## Purpose

Purchase requests, purchase orders, goods receipt notes (incl. ad-hoc), landed costs, debit notes, three-way match (PO/GRN/bill), GRNI report.

## Functional areas

| Area | Endpoints / scope |
|---|---|
| purchase requests | CRUD, submit, approve, reject (approval workflows) |
| purchase orders | CRUD, receive, pay, cancel, activate; `version` optimistic lock |
| goods receipts | adhoc, create, post (stock receipt + GRNI accrual), reverse |
| landed costs | CRUD, post, cancel |
| debit notes | CRUD, post, cancel (return to supplier) |
| three-way match | GET, recompute/:orderId, validate-bill/:billId |
| reports | GRNI |

## Business rules to reproduce exactly

1. PO status machine: draft → submitted → approved → sent → acknowledged → partially_received → received → billed → closed; cancelled; `active` for blanket orders.
2. Receipt quantities cannot exceed ordered (tolerance per settings); posting a GRN increases stock (inventory) and credits GR/IR (GRNI).
3. Landed cost only on a posted GRN; charge total > 0; allocation by value | quantity | equal; share for units still on hand raises AVCO and debits Stock Valuation; share for units already consumed is expensed to COGS; STANDARD-cost items expense the whole share; posting period must be open; code prefix `LC-`.
4. Three-way match: bill qty/price vs GRN vs PO within tolerances; override requires `three_way_match:override`.

Error messages: copy verbatim from the "Error messages" table in the generated spec.

## Events

`goods_receipt.posted`, `purchase_order.*`, …

## Scheduled jobs

—

## Notes and pitfalls

- Landed-cost frontend panel: `web/src/features/procurement/landed-cost-panel.tsx`.

## Porting checklist

- [ ] Read every file under "Source files to port" in the generated spec
- [ ] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [ ] DTO records with Bean Validation mirroring class-validator rules
- [ ] Services: rules above + verbatim errors + same transaction/locking boundaries
- [ ] Controllers: same paths/verbs/status/permissions/response shapes
- [ ] Events published/consumed as in Nest
- [ ] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/procurement-*.json` — zero unexplained diffs
- [ ] Web screens for this module work against Spring (no console errors)
- [ ] `docs/PORT_STATUS.md` updated

## Deviations

_None. Any deviation from Nest behaviour must be listed here with the owner's approval._

## Status

Not started.
