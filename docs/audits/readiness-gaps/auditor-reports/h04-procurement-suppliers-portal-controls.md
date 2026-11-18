# Auditor report: procurement suppliers portal controls (read-only audit of main 46281ee2, 2026-11-17)

Line numbers refer to 46281ee2. Findings fixed since then are listed in [HANDOFF.md](../HANDOFF.md).

VERDICT (procurement extensions): Not ready — foreign-currency quotes and agreement releases create POs at rate 1 (stock/GRNI booked in the wrong currency), and the budget, award-approval, bank-account and inspection controls can each be sidestepped with ordinary calls.

Paths: `J/` = `backend/src/main/java/com/inventoryerp/`, `PA/` = `J/procurement/application/`.

FINDINGS

F1 [P0] Foreign-currency sourcing books POs at rate 1
  where: PA/RfqService.java:240–241, 254, 317–318, 514, 530, 596–598; PA/PurchaseAgreementService.java:213–217; PA/PurchaseOrdersService.java:272, 330, 839–851; J/procurement/web/SupplierPortalController.java:43; web/src/pages/procurement/s2p/SupplierPortalPage.tsx:141
  what: A quotation's rate defaults to 1. The portal form asks suppliers for a currency but cannot send a rate, and agreement releases pass a null rate. A foreign PO at rate 1 makes functionalRate value receipts 1:1. The comparison also ranks foreign prices 1:1, and the award-approval amount is summed in the quotation currency.
  scenario: UGX org; a supplier quotes USD 10 × 1,000 in the portal → ranked cheapest against 37,000 UGX; the award amount of 10,000 stays under a 5M rfq_award threshold; createOrders → USD PO at rate 1 → the receipt debits stock/GRNI 10,000 UGX for about 37M of goods. Budget and delegation checks see 10,000 too.
  confidence: confirmed; test: none (SourcingIT is single-currency); known: P0-5 (re-opened via these paths)

F2 [P1] Budget and delegation limits skipped on sourcing, RFQ, agreement and change paths
  where: PA/RequisitionToOrderService.java:188–190; PA/ProcurementBudgetService.java:119, 147, 184–186; PA/ProcurementDelegationService.java:47–50; PA/PoChangeService.java:193–249; PA/PurchaseOrdersService.java:240–244
  what: Sourcing, RFQ and agreement orders carry no departmentId, and branch is optional. Scoped budgets ignore documents with a null scope value. A request's reservation disappears once any PO references it. Delegation returns early on a null branch, and applied PO changes re-check neither limit. budget.control passes no requestLineId, so cost-centre budgets never match, and it runs without a lock.
  scenario: D1 budget is 10M (block). A 9M D1 request is reserved. The buyer converts it via POST /procurement/sourcing/orders → the PO falls outside D1 and the reservation is gone → D1 shows 0 used, and the next 9M request passes (18M against 10M). A delegate capped at 2M can also omit branchId, or get a 1M→50M PO change approved, and no limit check runs.
  confidence: confirmed; test: none for these paths; known: new

F3 [P1] rfq_award approval can be bypassed; the approver can see a stale award
  where: PA/RfqService.java:485, 530–541, 564–570; J/kernel/approvals/ApprovalsService.java:182–186, 199–206, 406, 433
  what: createOrders re-checks a closed RFQ with amount 0. The kernel ignores REJECTED requests and filters workflows and steps by minAmount, so after a rejection nothing applies and the check passes. A pending request keeps its first snapshot while award() (allowed when closed) replaces the award rows. After approval, any re-award at or below the approved total passes. Nothing handles rfq_award decisions.
  scenario: workflow minimum 10M. Award 50M → rejected → POST /procurement/rfqs/{id}/orders → the RFQ becomes awarded and POs are raised for the rejected award. Or: award 12M → pending → re-award 80M → the approver approves the 12M snapshot → orders go out for 80M.
  confidence: confirmed; test: none; known: P1-4/P1-2 pattern (fixed for POs, open here)

F4 [P1] Bank-account dual control gates nothing, and the copy people actually use is unverified plain text
  where: PA/SupplierService.java:279–331 (SoD at 309); J/projects/application/SubcontractComplianceService.java:53 (only SupplierPaymentGuard); J/core/application/PartnerProfileService.java:32, 159–177; J/core/web/PartnerController.java:133–136; docs/spec/procurement-approval-policies.md:85–86
  what: No bill or payment path reads SupplierBankAccount, although the spec says payments to unverified accounts are blocked. Partner.bankAccounts holds full account numbers and IBANs in plain text. One user with partner:update can edit them, and they are shown in full, while the verified store shows only the last 4 digits. The SoD check is skipped when user() is null.
  scenario: a clerk PATCHes /partners/{id}/profile with their own account. There is no pending state and no second person, and AP pays from the partner page. Separately, an OAuth client holding supplier:manage and supplier:verify_bank can add and verify an account on its own.
  confidence: confirmed (actual loss depends on how payments are executed); test: ProcurementSupplierPolicyIT.bankAccountDualControl (SoD only); known: new

F5 [P1] Inspection holds neither stock nor payment; rejected goods stay on the books
  where: PA/PurchaseOrdersService.java:800–801, 810; PA/ReceivingControls.java:85–99; PA/BillControls.java:39–46; PA/IncomingInspectionService.java:154–156, 170–177; PA/SupplierClaimService.java:455–462
  what: Stock is available as soon as it is received. The inspection is opened only after the receipt commits, and cash POs are paid inside the receipt. The bill hold only finds bills linked through VendorBillLink, and cancel lifts the hold without a reason or SoD. The automatic quality claim has no quantity lines, so settlement defaults to price variance (Dr AP / Cr PPV) while the rejected units stay in stock.
  scenario: inspectionRequired is on for cement. Receive 10 bags, then post a bill without purchaseOrderIds → no hold. Inspection rejects 2 bags → the 60,000 claim settles to PPV, and both bags stay sellable at cost. Posting a purchase return as well would recover the amount twice.
  confidence: confirmed (hold bypass), likely (GL outcome); test: ReceivingQualityIT.inspection (linked bill only); known: new

F6 [P1] RFQ bids are not sealed; a zero-quantity quote distorts the ranking
  where: PA/RfqService.java:225, 302–303, 337–338, 365–367, 401–402; backend/src/main/resources/db/migration/V153__procurement_sourcing.sql:11; J/procurement/web/SupplierPortalController.java:36 vs J/procurement/web/RfqController.java:56
  what: The deadline is stored but never checked. The buyer sees all prices live, and suppliers can revise without limit, which allows last-look bid shopping. A zero-quantity line gets a delivered cost of 0, is flagged lowest, and zeroes the competitors' price score on that line.
  scenario: the buyer reads the comparison before the deadline and tips off a favoured supplier, who re-quotes in the portal after the deadline, just under the lowest bid. Separately, a supplier quoting quantity 0 appears best, so awarding the truly cheapest supplier now needs a justification.
  confidence: confirmed; test: none; known: new

F7 [P2] Portal: bad dates cause a 500 that logs the token; the promised date can be moved at will
  where: PA/RequisitionToOrderService.java:273–274; PA/RfqService.java:251–253, 273, 464–467; J/procurement/web/SupplierPortalController.java:43, 54; J/kernel/web/GlobalExceptionHandler.java:251; PA/SupplierPortalService.java:191–213; PA/ProcurementAnalyticsService.java:76
  what: validUntil and promisedDate are unvalidated strings parsed with substring, LocalDate.parse or ::date. A parse failure is an unhandled 500, and the error log line includes the URI, which contains the live portal token. Confirm can be repeated while the PO is open, overwriting promisedDate, which drives on-time KPIs and RFQ delivery scores.
  scenario: a supplier types "31/12/2026" → 500, and the token is logged at ERROR. A supplier re-confirms with a later date just before a late delivery → the delivery counts as on time.
  confidence: confirmed; test: PlanningIT.supplierPortal (happy path only); known: new

F8 [P2] RFQ order creation races and can strand an RFQ
  where: PA/RfqService.java:564–584, 595–600, 662–670
  what: There is no RFQ lock, and award rows are linked without a purchaseOrderId IS NULL guard. The closed→awarded transition autocommits before the open-awards check. The idempotency interceptor does not open a transaction around the handler.
  scenario: two buyers with different Idempotency-Keys create orders at the same time → two POs per supplier, one of them orphaned. Separately, closing quotations and then calling /orders with no workflow leaves the RFQ awarded with no awards; it can no longer be awarded, ordered or cancelled.
  confidence: confirmed (the race needs concurrency); test: none; known: new

F9 [P2] Budget consumption over-counts, giving false 409s
  where: PA/ProcurementBudgetService.java:169–172, 184–186; PA/RequisitionToOrderService.java:186
  what: Every non-cancelled PO counts at its original subtotal, including short-closed balances and drafts whose approval was rejected. A PO built from several requests records only the first requestId, so the other requests stay reserved as well.
  scenario: an 8M PO short-closed after 2M was received still consumes 8M.
  confidence: confirmed; test: none; known: new

F10 [P2] Replenishment mixes scopes and trusts ids
  where: PA/PurchaseReplenishmentService.java:93–105, 180–194, 272–276
  what: On-hand stock is per location, but on-order and requested quantities are org-wide, and requested quantities are not converted between units of measure. Each rule costs five queries, and raise is check-then-act. preferredSupplierId and branchId are stored unchecked and read back through a Partner join with no org filter.
  scenario: an open PO for 100 at store A suppresses store B's reorder (on hand 0, minimum 20).
  confidence: confirmed; test: PlanningIT.replenishment (one location); known: P0-6 class

F11 [P2] Maker-checker gaps
  where: PA/ProcurementPolicyService.java:223–251; PA/SupplierService.java:242–262, 309; PA/PoChangeService.java:114; PA/PurchaseAgreementService.java:123–125
  what: Any policy manager can edit someone else's draft and then approve it, because only createdBy is compared. Every SoD check is skipped for OAuth tokens that carry no user. Supplier approval has no maker-checker at all.
  scenario: user B edits A's draft budget.control from block to off, then approves and activates it alone.
  confidence: confirmed; test: ProcurementSupplierPolicyIT.policyLifecycle (author≠approver only); known: new

RE-CHECKED EARLIER ITEMS:
- P0-5: partly fixed — receipts use the PO rate (PA/PurchaseOrdersService.java:839–851), but RFQ, portal and agreement POs get rate 1 (F1).
- P0-6: fixed on PO create (PA/PurchaseOrdersService.java:192–202); new unchecked ids in reorder rules, agreements (PA/PurchaseAgreementService.java:95–100) and delegations.
- P1-2: fixed for POs (PA/PurchaseOrderApprovalSubscriber.java); recurs for rfq_award (F3).
- P1-4: fixed for APPROVED requests (J/kernel/approvals/ApprovalsService.java:409–431); pending snapshots never refresh (433), and RFQs re-check with amount 0 (F3).
- P1-10: partly fixed — SoD applies only when a purchase_request workflow exists; otherwise PA/PurchaseRequestsService.java:278–279 lets the requester approve their own request.
- P1-11: still open (J/kernel/events/EventBus.java:41–48). Suspected impact: the PR subscriber trusts the event payload, and lastDecision falls back to a null approver (PA/PurchaseRequestsService.java:329–342, 358–362), so a rolled-back decision could still approve a PR. An IT that forces a rollback after decide would confirm it.
- P1-14: improved — @Idempotent is on quotations, award and RFQ orders (J/procurement/web/RfqController.java:148, 164, 172), agreement releases and inspection complete. It is missing on replenishment raise (J/procurement/web/ProcurementPlanningController.java:99–104) and on RFQ create/send/close, and it only dedupes per key (F8).
- docs/spec/procurement.md:222, 233 still say PROC-3/PROC-5 are "built, not compiled, not run"; commit 01062da4 made those tests pass.

STRENGTHS:
- Portal tokens are 256-bit, stored as SHA-256 hashes, expire in 1–90 days, can be revoked and are bound to one document and partner. partnerId is taken from the link, and suppliers see only their own quote (PA/SupplierPortalService.java:66–69, 98–107, 148–150, 170–179, 215–227).
- Budget enforce locks active budgets FOR UPDATE in id order inside the document transaction (ProcurementControlIT.concurrentOrders).
- Agreement releases lock the header and lines and cap quantity and value (SourcingIT.blanketAgreementReleases).
- The daily job uses ShedLock, a per-org tenant context and per-org error isolation (PA/ProcurementPolicyJob.java:46–60); the new tables have RLS and org-scoped keys.
- organizationId filters are applied explicitly throughout, and every new controller has authorization tests.

TEST GAPS:
- Foreign-currency RFQ/portal/agreement → PO → receipt valuation, and the award-approval amount.
- rfq_award: orders after a rejection, re-award while pending or after approval, orders on a closed RFQ with no awards.
- Portal negatives: malformed dates, expired or revoked token, re-confirm, quantity 0, quote after the deadline.
- Budgets with orders from sourcing, RFQs and agreements, PO-change increases, release on short-close and multi-request POs, concurrent budget.control.
- Inspection with an unlinked bill, a cash PO or PO pay; the stock and GL effect of a rejected quantity.
