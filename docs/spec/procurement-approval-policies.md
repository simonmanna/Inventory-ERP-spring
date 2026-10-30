# Procurement policies, approvals and authorization

- Status: **proposed** (ADR-PROCUREMENT-001).
- Reuses `kernel/approvals/ApprovalsService`, `kernel/workflow/WorkflowRegistry`, `kernel/audit/AuditService` and
  `kernel/settings`. **No new approval framework.**

## 1. Policy model

- `ProcurementPolicy` has: `id`, `organizationId`, `key`, `scope` (org | branch | category | department | costCenter |
  project), `scopeId`, `status` (draft → approved → active → superseded / cancelled), `effectiveFrom`, `effectiveTo`,
  `version`, `value` jsonb (validated against a JSON schema per key), `approvedById` (must differ from `createdBy`),
  and audit rows.
- **Resolution:** `ProcurementPolicyResolver.resolve(key, ctx)` picks the active version effective on the document
  date. It checks the most specific scope first: project → costCenter → department → category → branch → org →
  built-in default. The built-in default is **current behaviour**, so an org with no policies behaves exactly like
  the Nest parity build.
- **Snapshot:** a document stores the policy version ids it was evaluated under (`policySnapshot` jsonb), so later
  policy changes never re-judge posted documents.
- **Cache:** Caffeine, evicted on activation (ShedLock job for future-dated activations, like `InventoryCostPolicy`
  in ADR-S007).

## 2. Policy keys

| Key | Values (default in **bold**) | Enforced at |
|---|---|---|
| `route.requisitionRequired` | **false** / true / above amount | PO create |
| `route.directPoMaxAmount` | **∞** / amount | PO create |
| `route.rfqRequiredAbove` | **∞** / amount; `minQuotations` (**0**) | PO create from PR / award |
| `route.agreementRequiredCategories` | **[]** | PO create |
| `supplier.approvedRequired` | **off** / warn / block (exception with permission `supplier:override_status`) | PR source, RFQ invite, PO create |
| `supplier.certificatesRequired` | **[]** per category, e.g. `explosives_licence` | PO create, GRN post |
| `approval.rules` | list of `{ minAmount, maxAmount, dimensions, steps: [ { approverRole | approverUserId | managerOf, minApprovers } ] }` | PR submit, PO submit, PO change, agreement, award, bill post, payment |
| `approval.reapproveOnChange` | `{ amountIncreasePct: 0, fields: [supplier, price, qty, deliveryTerms] }` | PO change request |
| `budget.control` | **off** / warn / block / block-with-override (`budget:override`) | PR submit (if `precommit`), PO approve |
| `budget.precommit` | **false** | PR approve |
| `receiving.overReceiptTolerancePct` | **0** (today's hard block) per category or supplier | GRN post |
| `receiving.underDeliveryAutoClosePct` | **0** | GRN post (short-close) |
| `receiving.weighbridgeRequired` | **false** per category | GRN post |
| `receiving.inspectionRequired` | **false** per category / supplier risk | GRN post → inspection state |
| `match.mode` | 2-way / **3-way** / 4-way (inspection-accepted qty) | bill post |
| `match.quantityBasis` | ordered / **received** / accepted | bill post |
| `match.tolerances` | `{ qtyPct: 0, pricePct: from flag procurement.match_tolerance, priceAbs, taxAbs, freightAbs, fxPct }` | bill post |
| `match.onException` | **block** / hold-for-review | bill post |
| `bill.duplicateCheck` | **warn** / block on (supplier, supplier reference, ±amount, ±date window) | bill create |
| `bill.withoutReceipt` | **autoReceive** (today) / inTransit / block | bill post |
| `ap.recognition` | **receipt** / bill | GRN post |
| `landedCost.allocationDefaults` | per cost component | voucher create |
| `landedCost.consumedTarget` | **cogs** / wip | voucher post |
| `emergency.allowed` | **false**; `maxAmount`, `approverRole`, `postReviewDays` | PO create |
| `currency.allowed` | **[functional]** + list; `rateSource` (**receipt date** / PO fixed) | PO create, GRN |
| `tax.defaults` | input tax code per category, WHT code per supplier type **[accountant to confirm]** | PO / bill lines |

## 3. Approval engine changes (kernel and invoicing)

1. **Amount-aware re-approval (fix P1-4).** `checkOrRequestApproval` must compare the approved request's
   `payload.amount` (and the rule-relevant dimensions) with the current values. An increase beyond the rule threshold
   requires a new request. For `supplier_payment`, the entity key becomes the **payment id**, not the partner id.
2. **Segregation of duties.** Rules carry `excludeRequester=true` by default: the requester, the PO creator and the
   bill poster cannot decide their own request. Remove the self-approval path of `overrideApproval` on bill post
   (P1-5). Keep an emergency `approvals:decide_own` permission, off by default and audited.
3. **Multi-step and parallel steps** from `approval.rules[].steps`, persisted on `ApprovalRequest` as a step index.
   `decide` advances the step and emits `approval.decided` only when the last step finishes.
4. **Procurement listener (fix P1-2).** An outbox-based handler on `approval.decided` moves PR, PO, agreement, award
   and change request `submitted → approved` or `submitted → draft`. `activate` refuses when the latest decision is
   REJECTED.
5. **Delegation:** an approver can delegate for a date range (kernel `ApprovalDelegation`), and it is audited.
6. **Gates inside the business transaction** for new extension routes. The documented parity deviation (`REQUIRES_NEW`
   on PO create) stays for the parity route only.

### Illustrative matrix (values are the client's to configure) **[OD-1]**

| PO value (UGX) | Steps |
|---|---|
| < 500,000 | Department manager |
| 500,000 – 5M | Department manager → Finance officer |
| 5M – 20M | Procurement manager → Finance manager |
| 20M – 100M | Head of department → Finance director |
| > 100M | Executive (2 of N) |

Overlays: emergency purchases add an Operations director and a post-review; capital purchases (`lineType=asset`) add
Finance director; a supplier with `riskClass=high` adds Procurement manager; a budget override adds the budget owner.

## 4. Other controls

- A supplier bank-account change goes `pending_verification` → `verified` by a second user holding
  `supplier:verify_bank`. Payments to an unverified account are blocked (invoicing `PaymentService` check).
- Changing an approved PO goes through a `PoChangeRequest` and a new `PoRevision`. The original snapshot and the
  supplier confirmation are kept.
- Emergency purchases appear in `/procurement/reports/emergency` until they are post-reviewed.
- Every decision records user, timestamp, step, decision and comment (`ApprovalRequest` and the audit log).

## 5. Status mapping for parity JSON

| Internal (target) | Parity `status` returned on Nest routes |
|---|---|
| draft, submitted | `draft` |
| approved, sent, acknowledged | `active` |
| partially_received | `partially_received` |
| received, billed, closed | `received` |
| cancelled | `cancelled` |

The new extension routes return the internal status. Existing web screens keep working.

## 6. Authorization matrix

Roles are configured per org. These are the recommended defaults (R = read, C = create / edit, A = approve or decide,
P = post, X = override).

| Permission | Requester | Storekeeper | Buyer | Procurement mgr | Quality | Finance / AP | Finance mgr | Admin |
|---|---|---|---|---|---|---|---|---|
| `purchase_request:*` | C (own) | C | R | A | — | R | R | all |
| `supplier:read / manage / approve / verify_bank` | R | R | R C | R C A | R | R C | R A **verify_bank** | all |
| `rfq:read / manage / evaluate / award` | — | — | R C E | R C E **A** | E (quality criterion) | R | R | all |
| `purchase_agreement:*` | — | R | R C | A | — | R | A (> threshold) | all |
| `purchase_order:create / update / send` | — | — | C | C | — | R | R | all |
| `purchase_order:approve` | — | — | — | A | — | — | A | all |
| `goods_receipt:create / post` | — | C P | R | R | R | R | R | all |
| `goods_receipt:override_weighbridge` | — | — | — | X | — | — | X | all |
| `inspection:read / decide` | — | R | R | R | R A | R | R | all |
| `supplier_claim:manage` | — | C | C | A | C | R | R | all |
| `three_way_match:read / approve / override` | — | — | R | R A | — | R A | X | all |
| `vendor_bill:create / post` | — | — | — | — | — | C P | P | all |
| `supplier_payment:create / approve` | — | — | — | — | — | C | A | all |
| `budget:override` | — | — | — | — | — | — | X | all |
| `procurement_policy:manage / approve` | — | — | — | C | — | — | A | C |

**Fix:** `three_way_match` recompute and validate-bill (write routes) require `three_way_match:approve`, not `:read`
(P1-1). This is a post-parity permission fix; it needs an allowed-diff entry.

Tests: one authorization IT per new route (403 without the permission), a tenant-isolation IT per new table, and
segregation-of-duties ITs (requester cannot approve; bank-account creator cannot verify).
