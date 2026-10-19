<!-- spec: generated from tools/specs/module-specs.mjs; delete this line to edit by hand -->
# Invoicing, payments, AR/AP — `invoicing`

| | |
|---|---|
| Phase | P3b |
| Nest source | `reference/nest-api/src/modules/invoicing` |
| Routes | 30 — full table: [generated/invoicing.md](generated/invoicing.md) |
| Feature flag | always on |
| Java package | `com.inventoryerp.invoicing` |

## Purpose

Customer invoices, credit notes, vendor bills (+ receipt matching, supplier ledger), customer payments with allocations and withholding, supplier payments, tax calculation, AR/AP reports.

## Functional areas

| Area | Endpoints / scope |
|---|---|
| invoices | CRUD, journal-preview, post, cancel |
| credit notes | CRUD, post |
| vendor bills | CRUD, post, cancel, supplier ledger |
| payments | list/get/create/void (idempotent), allocations; supplier payments |
| reports | AR aging, customer balances, outstanding, revenue by customer/period; AP aging |

## Business rules to reproduce exactly

1. Posting an invoice creates its journal entry (AR / revenue / tax) in the same tx; posted invoices are immutable except cancel (reversal).
2. `amountResidual` = total − paid − credited; `PaymentStatus` not_paid/partial/paid/overpaid derived exactly as Nest.
3. Payment allocation cannot exceed residual; void reverses allocations and GL.
4. Tax calc: inclusive/exclusive per tax, rounding per line then total as Nest (`tax` service).

Error messages: copy verbatim from the "Error messages" table in the generated spec.

## Events

`invoice.created/posted/paid/cancelled`, `creditnote.issued`, `payment.received/allocated`.

## Scheduled jobs

—

## Notes and pitfalls

- POS invoices are created by the pos module through these services (`pos/billing`).

## Porting checklist

- [ ] Read every file under "Source files to port" in the generated spec
- [ ] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [ ] DTO records with Bean Validation mirroring class-validator rules
- [ ] Services: rules above + verbatim errors + same transaction/locking boundaries
- [ ] Controllers: same paths/verbs/status/permissions/response shapes
- [ ] Events published/consumed as in Nest
- [ ] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/invoicing-*.json` — zero unexplained diffs
- [ ] Web screens for this module work against Spring (no console errors)
- [ ] `docs/PORT_STATUS.md` updated

## Deviations

_None. Any deviation from Nest behaviour must be listed here with the owner's approval._

## Status

Not started.
