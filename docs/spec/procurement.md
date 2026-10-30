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

## Target Source-to-Pay design (ADR-PROCUREMENT-001)

Status: **proposed**. Nothing in this section is built yet. Evidence:
[`docs/audit/procurement-current-state-audit.md`](../audit/procurement-current-state-audit.md). Delivery order:
[`docs/roadmap/procurement-roadmap.md`](../roadmap/procurement-roadmap.md).

Companion specs:
- [`procurement-postings.md`](procurement-postings.md): debit / credit rules.
- [`procurement-landed-cost.md`](procurement-landed-cost.md).
- [`procurement-approval-policies.md`](procurement-approval-policies.md): policies, approvals, authorization matrix.
- [`procurement-integration-contracts.md`](procurement-integration-contracts.md): ports and events.

All of it is additive. The parity routes above keep their path, verb, permission and JSON. With no policy configured,
behaviour equals today's.

### Purchasing routes (not every purchase follows every step)

| Route | Steps | Example |
|---|---|---|
| `direct` | PO → GRN → bill → payment | Small office purchase under the direct-PO threshold |
| `requisition` | PR → PO → GRN → bill | Routine store replenishment |
| `competitive` | PR → RFQ → quotations → evaluation and award → PO → … | Above the RFQ threshold |
| `agreement` | Agreement → release (PO) → GRN → bill | Aggregate or fuel under a rate contract |
| `planned` | Reorder rule or MRP → PR (auto) → … | Cement for block production |
| `emergency` | PO (flagged) → GRN → bill, then post-review | Crusher breakdown spare part |
| `capital` | PR → RFQ → award → PO → GRN → bill → asset | Truck, crusher, generator |
| `service` | PO (service lines) → service entry sheet → bill | Equipment hire, repairs, haulage |

`ProcurementPolicy.route.*` chooses which routes are allowed per amount, category and branch (approval-policies spec §3).

### State machines

```mermaid
stateDiagram-v2
    direction LR
    state "Purchase requisition" as PR {
        [*] --> draft
        draft --> submitted
        submitted --> approved
        submitted --> rejected
        approved --> partially_sourced: some lines on RFQ / PO
        partially_sourced --> converted: all approved qty sourced
        approved --> converted
        draft --> cancelled
        approved --> cancelled: unsourced qty only
    }
```

```mermaid
stateDiagram-v2
    direction LR
    state "RFQ" as RFQ {
        [*] --> draft
        draft --> sent: suppliers invited
        sent --> closed: deadline reached or closed manually
        closed --> evaluated: evaluation submitted
        evaluated --> awarded: award approved
        draft --> cancelled
        sent --> cancelled
    }
    state "Supplier quotation" as SQ {
        [*] --> received
        received --> revised: new revision (old one kept)
        revised --> received
        received --> accepted: line(s) awarded
        received --> rejected
        received --> expired: validUntil passed
    }
```

```mermaid
stateDiagram-v2
    direction LR
    state "Purchase agreement" as PA {
        [*] --> draft
        draft --> pending_approval
        pending_approval --> active
        active --> on_hold
        on_hold --> active
        active --> exhausted: committed qty or value released
        active --> expired: validTo passed
        active --> terminated
    }
```

```mermaid
stateDiagram-v2
    direction LR
    state "Purchase order (target)" as PO {
        [*] --> draft
        draft --> submitted: submit for approval
        submitted --> approved: approval.decided APPROVED
        submitted --> draft: rejected (edit and resubmit)
        approved --> sent: emailed or PDF issued
        sent --> acknowledged: supplier confirmation
        approved --> partially_received
        sent --> partially_received
        acknowledged --> partially_received
        partially_received --> received
        received --> billed: billed qty = received qty
        billed --> closed: paid, or closed manually
        partially_received --> closed: short-close remaining qty
        draft --> cancelled
        submitted --> cancelled
        approved --> cancelled: nothing received
    }
```

Today's `active` status stays as an alias. A PO created with no approval required goes straight to `approved`, and
parity JSON keeps reporting `active` (mapping table in the approval-policies spec §5). Hard delete is allowed only in
`draft` with no GRN (fix for P0-2).

```mermaid
stateDiagram-v2
    direction LR
    state "PO change request" as CR {
        [*] --> draft
        draft --> pending_approval
        pending_approval --> approved
        pending_approval --> rejected
        approved --> applied: new PoRevision; PO lines updated
    }
    state "GRN / service entry" as GRN {
        [*] --> draft
        draft --> inspection: inspection required by policy
        inspection --> posted: accepted qty posted
        draft --> posted
        draft --> cancelled
        posted --> reversed
    }
    state "Incoming inspection" as QI {
        [*] --> pending
        pending --> passed
        pending --> partially_accepted
        pending --> failed
        failed --> claimed: supplier claim raised
    }
```

### Proposed entities

New tables (`V{n}__procurement_<area>.sql`). They are org-scoped (`organizationId` plus RLS policy like the
baseline), use UUID ids, carry `version` for optimistic locking, and have `createdAt`/`updatedAt`/`createdBy`.

| Area | Entity | Key fields | Relations |
|---|---|---|---|
| Supplier | `SupplierProfile` | `partnerId` (unique), `lifecycleStatus` (prospect / provisional / approved / suspended / blacklisted), `riskClass`, `categories[]`, `currencyCode`, `leadTimeDays`, `approvedById/At` | 1:1 `Partner` |
| | `SupplierBankAccount` | `bankName`, `accountNumberMasked`, `accountNumberEnc`, `currency`, `status` (pending_verification / verified / revoked), `verifiedById` (must differ from `createdBy`) | n:1 profile |
| | `SupplierCertificate` | `type` (TIN, trade licence, explosives licence, ISO, KS/UNBS), `number`, `validFrom/To`, `fileId` | n:1 profile; expiry alert job |
| | `SupplierProduct` | `productId`, `supplierSku`, `purchaseUomId`, `minOrderQty`, `leadTimeDays`, `preferred` | price history = `SupplierPrice` |
| | `SupplierPrice` | `productId`, `uomId`, `currency`, `unitPrice`, `validFrom/To`, `source` (quotation / agreement / PO / manual) | append-only |
| | `SupplierScorecardPeriod` | period, `onTimePct`, `inFullPct`, `rejectPct`, `priceIndex`, `leadTimeAvg`, `score` | computed nightly (ShedLock) |
| Requirements | `PurchaseRequestLine` (exists) + columns `sourcedQuantity`, `orderedQuantity`, `costCenterId`, `projectId`, `requiredDate`, `deliveryLocationId`, `demandSource` (manual / reorder / mrp / maintenance / project), `demandRef` | | PR (exists) |
| Sourcing | `Rfq`, `RfqLine`, `RfqSupplier` | `deadline`, `deliveryTerms`, `evaluationTemplateId`; invite status | RfqLine → PR line(s) (n:m via `RfqLineSource`) |
| | `SupplierQuotation`, `SupplierQuotationLine` | `revision`, `validUntil`, `unitPrice`, `currency`, `leadTimeDays`, `freightIncluded`, `paymentTermId` | → Rfq / RfqSupplier |
| | `EvaluationTemplate`, `EvaluationCriterion` | criterion (price / quality / delivery / terms / performance), `weightPct` (sum 100) | per category |
| | `SourcingEvaluation`, `SourcingScore` | evaluator, per-supplier per-criterion score, `justification` | → Rfq |
| | `SourcingAward`, `SourcingAwardLine` | awarded quotation line, qty, `reason`, approval | creates PO line(s) or agreement |
| Agreements | `PurchaseAgreement`, `PurchaseAgreementLine` | `type` (blanket / rate / scheduled), `validFrom/To`, `committedQty`/`committedValue`, `releasedQty`/`releasedValue`, `price`, `tolerancePct` | releases = PO with `agreementLineId` |
| PO | `PurchaseOrderLine` (exists) + columns `agreementLineId`, `requestLineId`, `lineType` (goods / service / asset / expense), `deliveryLocationId`, `projectId`, `costCenterId`, `cancelledQuantity` | | |
| | `PurchaseOrderSchedule` | line, `dueDate`, qty, location | |
| | `PoRevision` | `revisionNo`, `snapshot` jsonb, `reason`, `approvedById` | uses existing `PurchaseOrder.snapshot` for revision 0 |
| | `PoChangeRequest` | changes jsonb, `status`, approval | → `PoRevision` |
| | `SupplierConfirmation` | `confirmedAt`, `supplierRef`, `promisedDate`, deviations jsonb | |
| Receiving | `GoodsReceiptLine` (exists) + columns `rejectedQuantity`, `acceptedQuantity`, `weighbridgeTicketId`, `inspectionId`, `deliveryNoteRef`, `projectId` | | |
| | `ServiceEntrySheet`, `ServiceEntryLine` | period, `acceptedValue`, acceptor | → PO service line |
| | `IncomingInspection`, `InspectionResult` | plan (re-use `QcPlan` shape), parameter results, decision, photos (files) | → GRN line |
| | `SupplierClaim` | type (shortage / quality / damage / price), qty, amount, status (open / credit_requested / settled / rejected) | → GRN line, debit note, supplier credit note |
| Budget | `Commitment` (accounting) | source (PR / PO), account / cost centre / project, amount, relieved amount | relieved by GRN / bill |
| Policy | `ProcurementPolicy`, `ProcurementPolicyVersion` | scope, key, value jsonb, `effectiveFrom`, status | approval-policies spec |

### Extension routes (all under `/api/v1`)

Every route takes `@RequirePermissions`. Every POST or PATCH that moves stock, money or commitment requires an
`Idempotency-Key`. Lists use the standard envelope (`CONTRACT.md`).

| Method | Path | Permission |
|---|---|---|
| GET/POST/PATCH | `/procurement/suppliers/{partnerId}/profile` | `supplier:read` / `supplier:manage` |
| POST | `/procurement/suppliers/{partnerId}/profile/{approve,suspend,blacklist,reinstate}` | `supplier:approve` |
| GET/POST | `/procurement/suppliers/{partnerId}/bank-accounts`, POST `/{id}/verify` | `supplier:manage` / `supplier:verify_bank` |
| GET/POST/DELETE | `/procurement/suppliers/{partnerId}/certificates` | `supplier:manage` |
| GET | `/procurement/suppliers/{partnerId}/scorecard`, `/price-history?productId` | `supplier:read` |
| POST | `/procurement/purchase-requests/{id}/cancel`, `/consolidate` (body: request ids) | `purchase_request:update` |
| GET | `/procurement/purchase-requests/open-lines` (sourcing workbench) | `rfq:read` |
| GET/POST/PATCH | `/procurement/rfqs`, `/{id}` | `rfq:read` / `rfq:manage` |
| POST | `/procurement/rfqs/{id}/{send,close,cancel}` | `rfq:manage` |
| GET/POST | `/procurement/rfqs/{id}/quotations`, POST `/quotations/{qid}/revise` | `rfq:read` / `rfq:manage` |
| GET | `/procurement/rfqs/{id}/comparison` | `rfq:read` |
| POST | `/procurement/rfqs/{id}/evaluation`, `/award` | `rfq:evaluate` / `rfq:award` |
| POST | `/procurement/awards/{id}/create-orders` (Idempotency-Key) | `purchase_order:create` |
| GET/POST/PATCH | `/procurement/agreements`, `/{id}`, POST `/{id}/{submit,activate,hold,terminate}` | `purchase_agreement:read` / `manage` / `approve` |
| POST | `/procurement/agreements/{id}/releases` (Idempotency-Key) | `purchase_order:create` |
| POST | `/procurement/purchase-orders/{id}/{submit,send,acknowledge,short-close}` | `purchase_order:update` / `send` |
| GET/POST | `/procurement/purchase-orders/{id}/change-requests`, POST `/{crId}/{submit,apply}` | `purchase_order:update` / `approve` |
| GET | `/procurement/purchase-orders/{id}/revisions` | `purchase_order:read` |
| GET/POST | `/procurement/service-entries`, POST `/{id}/{accept,reverse}` (Idempotency-Key) | `goods_receipt:create` / `post` |
| GET/POST | `/procurement/inspections`, POST `/{id}/decide` | `inspection:read` / `inspection:decide` |
| GET/POST/PATCH | `/procurement/claims`, POST `/{id}/{submit,settle,reject}` | `supplier_claim:manage` |
| GET | `/procurement/documents/{type}/{id}/trace` | type's `:read` |
| GET/POST | `/procurement/policies`, POST `/{id}/{approve,activate}` | `procurement_policy:read` / `manage` / `approve` |
| GET | `/procurement/reports/{open-orders,overdue,spend,price-variance,supplier-performance,commitments,emergency}` | `procurement_report:read` |

New permission keys are added to `packages/shared/src/permissions.ts`, then `node tools/contracts2java/generate.mjs`
is run. The migration grants them to Administrator, following the ADR-S007 / V12 pattern.

### Industry requirements (construction materials)

- **Weighbridge-controlled receipts.** Policy `receiving.weighbridgeRequired`, per category (aggregate, sand, cement
  bulk), makes a GRN line post only with a completed `WeighbridgeTicket` (`subjectType='purchase_receipt'`) whose net
  weight converts to the line unit. The existing `WeighbridgeTicketService.feedGoodsReceipt` stays the producer, and a
  manual override needs `goods_receipt:override_weighbridge` plus a reason (audited).
- **UoM with density.** Tonne ↔ m³ uses a per-product (or per-lot) density on `ProductUom`. This depends on ADR-S003
  approval and the line-unit work in `AUTONOMOUS_BUILD_CONSTRUCTION.md` Phase 4.
- **Direct-to-site.** A PO line has `deliveryLocationId` of type `virtual/site` plus `projectId`. Its GRN is a *site
  acceptance*: expensed to the project or WIP account, or stocked at a site location per policy. The acceptor is
  recorded.
- **Controlled items** (explosives, detonators). A category flag `controlled=true` requires a supplier certificate of
  type `explosives_licence` that is valid on the PO date, a named recipient, and a quantity check against the permit.
  Each movement is written to the audit log.
- **Emergency purchases.** `emergency=true` with a justification. It skips RFQ but not approval (an emergency
  approver role). It appears in `/reports/emergency` and must be post-reviewed within N days (policy).
- **Capital purchases.** `lineType=asset` posts to `fixed_asset_clearing`. The GRN or bill emits `asset.acquired` to
  `fixedasset`, which creates the asset from `AssetAcquisition` (integration contracts §4).
- **Equipment spares.** `SupplierProduct` plus a product attribute `compatibleEquipment[]`, so a maintenance work order
  (fleet) can raise a PR with `demandSource=maintenance`.
