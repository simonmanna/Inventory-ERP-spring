# Procurement — `procurement`

| | |
|---|---|
| Phase | P4 |
| Nest source | `reference/nest-api/src/modules/procurement (+ kernel/three-way-match)` |
| Routes | 36 — 33 in [generated/procurement.md](generated/procurement.md) + 3 `/procurement/three-way-match/*` (Nest `kernel/three-way-match` controller) |
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

1. PO status as Nest drives it: `draft` (only while a `purchase_order` approval request is pending) → `active` →
   `partially_received` → `received`; `cancelled` from anything but received / billed / closed. The other enum
   values (`submitted`, `approved`, `sent`, ...) exist in the schema but no Nest code path sets them.
2. Over-receipt is a hard block (no tolerance): `applyReceiptToPO` is the single writer of PO receipt status, shared
   by `POST /purchase-orders/:id/receive` and `PATCH /goods-receipts/:id/post`. Posting a GRN stocks the warehouse
   (Dr Stock Valuation / Cr GRNI); a PO-linked receipt is also vouchered (Dr GRNI + Dr Input Tax / Cr AP) and a cash
   PO is settled from the caller's open till (Dr AP / Cr Cash + drawer pay-out). Ad-hoc receipts leave GRNI open for
   the vendor bill.
3. Landed cost only on a posted GRN; charge total > 0; allocation by value | quantity | equal; share for units still on hand raises AVCO and debits Stock Valuation; share for units already consumed is expensed to COGS; STANDARD-cost items expense the whole share; posting period must be open; code prefix `LC-`.
4. Three-way match: bill qty/price vs GRN vs PO within tolerances; override requires `three_way_match:override`.

Error messages: copy verbatim from the "Error messages" table in the generated spec.

## Events

`purchase_request.created|submitted|approved|rejected`, `purchase_order.created|received|paid|cancelled|activated`,
`goods_receipt.posted`, `debit_note.created|posted`, `three_way_match.computed` — published fire-and-forget at the
same points as Nest. No subscribers in this module.

## Scheduled jobs

—

## Notes and pitfalls

- Landed-cost frontend panel: `web/src/features/procurement/landed-cost-panel.tsx`.

## Porting checklist

- [x] Read every file under "Source files to port" in the generated spec
- [x] Entities tuned (collections/locks/query methods) — none needed: services are JdbcClient SQL
- [x] DTO records with Bean Validation mirroring class-validator rules (untyped Nest bodies stay unvalidated maps)
- [x] Services: rules above + verbatim errors + same transaction/locking boundaries
- [x] Controllers: same paths/verbs/status/permissions/response shapes
- [x] Events published/consumed as in Nest
- [x] Jest specs ported; integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/procurement-*.json` — written, not yet run against a live Nest
- [ ] Web screens for this module work against Spring (no console errors) — not exercised
- [x] `docs/PORT_STATUS.md` updated

## Deviations

Pending owner approval:

1. **Line units (ADR-S003, V5).** PO lines snapshot `unitOfMeasureId` + `uomFactor` (default: the product's
   purchase unit); GRN lines (`uomId`, `uomFactor`) inherit the PO line's snapshot, or take `uomId` /
   the purchase unit on ad-hoc receipts; debit-note lines accept `uomId` (default: purchase unit) and a
   return-to-vendor ships `quantity x uomFactor` base units (Nest shipped `quantity` base units). Stock
   posting uses the snapshot; pre-V5 lines keep the live purchase-unit conversion. New 400s:
   `Unit <code> is not enabled for purchasing on product <name>`, `Unit <code> is not active for product
   <name>`.

Same as Nest, kept on purpose:

- `GET /procurement/purchase-orders?search=…` answers **500**: Nest builds `contains` filters on the
  `paymentType` / `paymentStatus` / `status` enum columns, which Prisma rejects at validation time.
- Posting an **inbound** debit note that is not a return-to-vendor always fails with `400 Unbalanced entry…`:
  Nest credits AP *and* the expense line. Outbound notes and RTV (`inbound` + `returned_goods`) post normally.
- `GET /procurement/landed-costs/:id` answers 404 when missing, while the other detail routes answer 200 with an
  empty body (Nest returns `null`).
- `PATCH /goods-receipts/:id/reverse` cancels the receipt's draft landed costs (autocommit) *before* the bill /
  payment checks, so a refused reversal still leaves them cancelled.
- Approval gates Nest calls outside `$transaction` (`checkOrRequestApproval` on GRN / debit-note post and PO
  receive; `requestApproval` during PO create) run in their own transaction (`REQUIRES_NEW` for PO create), so the
  approval request survives a later failure.
- `PATCH /purchase-orders/:id` sets `expectedDeliveryDate` to `null` when the field is omitted (Nest passes
  `null` explicitly) but leaves `description` / `notes` / `terms` untouched when omitted.
- Business timestamps Nest sets with `new Date()` use the database clock (`clock_timestamp()`), as in every module.

## Status

P4: 36/36 routes (33 procurement + 3 three-way-match). Services: `PurchaseRequestsService`,
`PurchaseOrdersService` (create with approval gate, receive, `applyReceiptToPO`, `settleCashReceipt`, pay, update,
cancel, remove, activate), `GoodsReceiptsService`, `LandedCostService` (value / quantity / equal allocation; serial,
lot, STANDARD and AVCO layers; books lock), `DebitNotesService` (incl. RTV), `GrniReconciliationService`; the
existing `kernel.threewaymatch.ThreeWayMatchService` got its controller.

Tests: `ProcurementIT` (22 tests) ports `procurement-flow.spec.ts` (credit/cash receive GL identity, partial →
received, over-receipt rollback, fully received, pay Dr AP / Cr Bank, overpay), `purchase-bill-matching.spec.ts`
(partial bill, PPV, cancel releases the receipt), `supplier-payment-withholding.spec.ts`, and the purchasing cases of
`inv-audit-purchasing` (H1-A, H1-B, H18), `inv-audit3-remediation` (P2-02 landed cost AVCO + FIFO lot),
`inv-p0-remediation` (INV-003 concurrent GRN post), `inv-p1-integrity` (N3 ×2, INV-012),
`inv-p2-accounting` (INV-010 GRN reversal) and `inv-readiness-remediation` (P0-2 ×2, P1-3); plus purchase
requests, PO admin + approval gate, GRN lifecycle, debit notes incl. RTV, GRNI report, three-way match,
receive idempotency + replay, tenant isolation and the authorization matrix (36 routes).

Not ported here: `lakeview-15day.sim.spec.ts` and `lakeview-m3.sim.spec.ts` drive POS services (P5) and are skipped
in Nest unless `SIM_RUN=1` against a `pos_stage1_*` database — port them with P5. `inv-p2-accounting` INV-011 (org-wide GL tie-out after document
flows) is not ported: it asserts zero variance over the whole org, which the shared DEMO test org cannot guarantee.

JaCoCo line coverage (full build): `procurement.application` 91.1 %, `procurement.web` 97.0 %,
`kernel.threewaymatch` 91.7 %.

Parity scenarios: `contract-tests/scenarios/procurement-purchasing.json`, `procurement-landed-cost.json` — not run
against a live Nest yet. Web procurement screens (incl. `landed-cost-panel.tsx`) not exercised against Spring.

## ADR-S007 extensions — landed cost vouchers, shipments, cost components (roadmap 1.8)

New routes (no Nest counterpart; legacy `/procurement/landed-costs` unchanged except that a list filtered by
`goodsReceiptId` also returns vouchers covering that receipt, and `post` refuses a voucher):

| Method | Path | Permission |
|---|---|---|
| GET | `/procurement/landed-cost-vouchers` (`status`, `shipmentId`) | `landed_cost:read` |
| GET | `/procurement/landed-cost-vouchers/{id}` · `/{id}/preview` | `landed_cost:read` |
| POST | `/procurement/landed-cost-vouchers` (Idempotency-Key) | `landed_cost:create` |
| PUT | `/procurement/landed-cost-vouchers/{id}` (draft) | `landed_cost:create` |
| POST | `/procurement/landed-cost-vouchers/{id}/submit` · `/cancel` | `landed_cost:create` |
| POST | `/procurement/landed-cost-vouchers/{id}/approve` | `landed_cost:approve` |
| POST | `/procurement/landed-cost-vouchers/{id}/post` (Idempotency-Key) | `landed_cost:post` |
| POST | `/procurement/landed-cost-vouchers/{id}/reverse` (Idempotency-Key) | `landed_cost:reverse` |
| GET/POST/PATCH | `/procurement/shipments`, `/{id}`, `/{id}/receipts`, DELETE `/{id}/receipts/{grnId}` | `landed_cost:read` / `create` |
| GET | `/procurement/cost-components` | `landed_cost:read` |
| POST/PATCH | `/procurement/cost-components`, `/{id}` | `landed_cost:configure` |
| GET | `/procurement/landed-cost-reports/{by-shipment,by-item,by-supplier,product-profitability}?from&to`, `/clearing` | `landed_cost:read` |

GL per posted voucher: Dr stock (capitalised, per product stock account) · Dr COGS (consumed share, voucher cost
centre) · Dr expense (expensed charges, charge cost centre) · Dr `tax_receivable` (recoverable tax) / Cr each charge's
credit account, default landed-cost clearing `2155` (`landed_cost_clearing`). Reversal = reversing entry + Dr stock /
Cr COGS for the part sold since. A posted voucher blocks reversal of any of its receipts.

Supplier payables (V14, ADR-S007 §2a): charge field `billMode` = `generate | link | none` (+ `vendorBillId`,
`dueDate`). `generate` (default when a supplier + invoice no. are given and no credit account): posting also raises
one posted vendor bill per (supplier, invoice no.) — `sourceType = 'landed_cost'`, `sourceDocument` = voucher code,
lines Dr `2155` (charge + tax) / Cr that supplier's payable; charges of one invoice must share currency and rate.
`link`: references a bill already booked against clearing. Reversal cancels generated bills and is refused
(`Supplier bill … has payments; …`) once one is paid. `GET /{id}` adds `charges[].vendorBill` and a `suppliers[]`
summary (charges, tax, invoices with bill number / status / residual).

Tests: `LandedCostVoucherIT` (laptop 2,380,000 / unit; 19M + 19M after half sold; cement / steel 1.8M / 1.2M; weight;
manual; selected lines; expense with cost centre; recoverable tax; duplicate supplier invoice; workflow; reversal;
FIFO lots across a shipment; idempotent replay; GRN reversal block; tenant isolation; multi-supplier shipment with
two goods vendors, generated + linked supplier bills, supplier-scoped charge, paid-bill reversal guard; bill-mode
validation and one bill per invoice). Web: Landed Costs, Import
Shipments, Landed Cost Reports (incl. cost components) under Purchases.

## Phase 4 additions (built, unverified)

PROC-4: `GET /procurement/reports/supplier-performance?from&to&partnerId` (`purchase_order:read`), default the last 90 days.
Screen `/procurement/supplier-performance`. Tests: `SupplierPerformanceIT`, `SupplierPerformanceServiceTest`. Nothing was compiled or run.

## Phase 4 RFQ (built, unverified)

PROC-1: `/procurement/rfqs`: list, get, create, patch, send, cancel, quotations, comparison, award (`rfq:read|manage|award`). Award needs an Idempotency-Key and raises the PO through `PurchaseOrdersService.create`. Tables V73.
Screens `/procurement/rfqs`, `/procurement/rfqs/new`, `/procurement/rfqs/:id`, `/procurement/rfqs/:id/edit`. Test: `RfqIT`. Nothing was compiled or run.

## Phase 4 price agreements (built, unverified)

PROC-2: `/procurement/price-agreements` (list, get, lookup, create, patch, cancel) and `/procurement/reports/price-history` (`price_agreement:read|manage`). Table `SupplierPriceAgreement` (V74).
Screens `/procurement/price-agreements`, `/procurement/price-history`. Test: `PriceAgreementIT`. Nothing was compiled or run.
