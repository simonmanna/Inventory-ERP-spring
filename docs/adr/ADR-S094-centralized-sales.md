# ADR-S094 — Centralized Sales: one Sale document from order to invoice

- Status: **accepted for implementation** (owner, 2026-11-28), revision 3. After the C0 assessment the owner chose the
  recommended option for every open decision, dropped the historical migration (live holds demo data only) and asked
  for the build: see "Owner decisions (2026-11-28)" below. Deployment to live is a separate owner decision.
  Revisions 1 and 2 were reviewed and are replaced by this text.
- Extension: changes Spring-only sales and invoicing code. NestJS parity routes keep their contract: `POST /invoices`,
  `POST /credit-notes`, `/recurring`, POS, `/orders`, rental, repair (§8). One parity deviation, approved (OD-14): the
  idempotency fix for D-IDEM-1 (§6).
- Migrations: V730–V749 are reserved for this ADR (re-verified across all refs on 2026-11-28; the highest number in
  use on any branch is V722).
- Supersedes in part: ADR-S056 (branch cash sales with head-office invoicing), once C3 and C6 ship. Builds on
  ADR-S011 (sales O2C), ADR-S073 (deferred revenue) and ADR-S080 (FX deposits).
- Spec: [`sales.md`](../spec/sales.md), [`cash-sales.md`](../spec/cash-sales.md),
  [`invoicing.md`](../spec/invoicing.md), [`sales-postings.md`](../spec/sales-postings.md). Planned changes there are
  marked "ADR-S094 (proposed)".
- Evidence: read-only inspection of `origin/main` 77daa9fb. File references are given where a fact matters. C0
  read-only assessment of the 2026-11-28 nightly dump: [report](../audits/2026-11-28-adr-s094-c0-assessment.md).
- This revision is complete on its own. Revision 2 content that did not change is repeated here, not referenced.

**Review status (architecture review of revision 2, 2026-11-28)**

| Item | Decision |
|---|---|
| ADR-S094 architecture direction | Approved in principle |
| C0 documentation | Proceed |
| C0 read-only assessment | Proceed |
| C1 schema and state changes | Waits for this revision |
| C2 returns and credit changes | Waits for accountant decisions |
| C3 cash-sale implementation | Not approved |
| C5 historical migration | Not approved |
| Production deployment | **NO-GO** |

**Owner decisions (2026-11-28)**: "use recommended settings, you can clear the data. its just demo data. dont worry
to migrate or something. just code and develop."

| Topic | Decision | Effect on the build |
|---|---|---|
| Stages | C1a, C1b, C1, C2, C3, C4, C6 and C7 approved for development; the review gates above are replaced for development. | Built as stacked PRs, one per stage. |
| C5 historical migration | **Dropped.** Legacy cash sales (`SalesSubmission`) are demo data and are not migrated. | No migration, preflight guards, replay journal or freeze tooling. Legacy cash-sale data is cleared at deploy, after the owner confirms that step. OD-15 and AD-11 are moot. |
| Legacy cash-sale module | Replaced by the cash Sale. | After C6 the web no longer creates legacy cash sales; the legacy screens and routes stay read-only until C8 removes them. |
| AD-1 | Interim path: an order-type Sale with a full advance. | C3d (cash paid now, delivered later) is not built. |
| AD-2, AD-16 | Contract liability (`unearned_revenue`), released at **dispatch**. | F4 deferral is built (stage C2b). |
| AD-3 | Period-end accrual for delivered, not invoiced. | Stage C7b, after the core. P7 per delivery stays available where the revenue policy already uses it. |
| AD-4, AD-5 | Tax points as designed (§3); every sale invoice, the zero-total final invoice and reversal credit notes are fiscalised through the outbox. | — |
| AD-6, AD-7, AD-8 | Approved: P4 + P8 + P11 for cash; one gap-free invoice series per organization; walk-in partner per branch with `requireTaxNumberAbove`. | — |
| AD-9 | Existing returns accounts; restock at the original issue cost; damaged to quarantine, scrap to the scrap account; refund over the original tender by default; customer-credit retention with permission. | — |
| AD-10, OD-4 | Fleet transport charges stay on their own invoice. | C4f not built. |
| AD-12 | POS refunds and settled voids reduce the measures on the refund date; write-offs are excluded (bad debt). | §9 as written. |
| AD-13 | The module sources in §8.1 are approved. | — |
| AD-14 | **Pro-rata** deduction of taxed down payments. | C1b. |
| AD-15, AD-17 | Approved: a credit note on a down-payment invoice reduces its deposit and is refused once consumed; a zero-total final invoice is a tax invoice. | C1b. |
| AD-18 | Nothing to correct (C0: no occurrences). | — |
| AD-19 | Zero-value sales: delivery note only, no tax invoice. | C2. |
| AD-20 | Quantity credit without a return: own permission plus approval; no stock movement; posted like a return credit. | C2. |
| OD-1, OD-2 | Yes: counter pickup is exempt from delivery-posting approval and delivery QC; quotation → POS `Order` conversion is retired after a usage check. | OD-2 is a small separate change. |
| OD-3 | Manual customer invoices stay allowed; the per-organization switch is built, default `allowed`. | C4. |
| OD-5, OD-10, OD-11 | Release lead and window are set when a deploy is requested. Hypercare 7 days. No command journal (C5 dropped). | — |
| OD-6, OD-7 | Yes. | C3, C1. |
| OD-8 | No self-approval; an audited organization setting may allow it for `sales:configure` holders. | C1. |
| OD-9 | Excess non-cash payment is refused. | C3. |
| OD-12 | A kernel in-transaction outbox is a separate ADR, outside this one. | Not built here. |
| OD-13 | Enable WAL archiving on the live host at the next deploy (needs a restart). | Ops task, done with the owner at deploy. |
| OD-14 | The idempotency deviation is approved. | C1a; `CONTRACT.md` §6 updated. |

**Governing principle** (from the review): accounting correctness and historical integrity are never sacrificed to
make the new sales workflow simpler. Where the two conflict, the workflow is gated until a decision, not simplified.

---

## 0. Change summary

### 0.1 Revision 2 → revision 3

| Review ref | Topic | Change | Section |
|---|---|---|---|
| 1.1 · item 1 | Down payment and VAT | Journal examples J1–J4, with numbers, cover all six required scenarios (coverage and reconciliation tables). **Pre-existing defect D-VAT-1**: with `depositTaxPoint = receipt` the final invoice charges VAT on the advance again and leaves a receivable equal to that VAT. Revision 2's F2 Option C / F5 described that same flawed flow; corrected with a deduction line. Revision 2's `depositTaxPoint = invoice` was wrong: the values are `delivery` (default) and `receipt`. | §3 |
| 1.2 · item 2 | Rollback and recovery | Pre-activation rollback separated from post-activation recovery. Activation and release closure defined. Rollback window bounded (proposal 4 h). Freeze kept through any rollback. A restore after activation is **prohibited**, except L3 with deterministic command-journal replay. Mobile-money and fiscal reconciliation. Authorization matrix (rollback vs forward fix). The review's 15-cash-sale scenario is walked through. Rehearsal tests. WAL/PITR prerequisite. | §11 |
| 1.3 · item 3 | Idempotency | **Pre-existing defect D-IDEM-1**, inherited from the NestJS reference: a money request that runs longer than 2 minutes can be executed a second time by its retry. The fix is a recorded parity deviation (OD-14). Lease, owner token, expiry, compare-and-set takeover, and a fence in the business transaction. Executing vs abandoned is decided from the row lock. Nested transactions write evidence without blocking. Lost connections are covered, with database timeouts. | §6 |
| 1.4 · item 4 | Approval | Explicit submit / withdraw / resubmit. Commercial status stays `draft`. Approval is bound to a content hash. Edits are refused while pending and invalidate an approval after it. Rule changes and self-approval are handled. All enforced in backend commands and tested through the API. | §4.2 |
| 1.5 · item 5 | Payment model | Three separate concepts: sale type; payment terms (`paymentMode` = immediate / credit, plus `paymentTermId`); tender lines (cash, mobile money MTN / Airtel, bank, card, several per sale). Underpayment, overpayment, change and unverified tenders defined. | §5.1 |
| 1.6 · item 6 | Completion and outcome | Outcome amounts retained. `paymentStatus` covers refunded / credited / credit balance / no charge, plus a settlement status. A line-quantity model defines `done` for fully credited, written-off, value-credited, zero-value and short-closed sales. | §4.4–4.5 |
| §2 concern · item 7 | Propagation audit | Findings TA-1…TA-17 on every confirm-path service. TP-1…TP-6 and FI tests use the real HTTP route and production beans. | §5.4, §12 |
| item 8 | Clarifications | F4 deferral postings and release (§3.6); handover proof (§5.3); identical lines plus the post-guard history (§10.4); reporting baselines (§9.3); fiscal accepted-but-timed-out (§5.5). | as listed |
| §2 table | Returns | Return linked to the original stock issue (load line / issue rows). Returned serials and lots validated against what was issued. Migrated cash sales keep lot and serial identity. | §7.2 |
| §2 table | Invoice source | Typed `SourceRef` contract with one creation entry point, a code-scan test and a database guard. Parity-safe. | §8.2 |

### 0.2 Kept from revision 2
- Central Sale = `SalesOrder`, with `saleType` and `origin`.
- Quotation separate. POS separate and reported centrally.
- 27-creator invoice-source matrix; module-owned billing preserved; manual-invoice restriction off by default.
- Deterministic migration with collision and integrity checks, and an exceptions report.
- Five reporting measures; ledgers never rewritten.

### 0.3 Corrections from the C0 assessment (2026-11-28)

The C0 read-only assessment ([report](../audits/2026-11-28-adr-s094-c0-assessment.md)) checked this ADR against the code and a restored copy
of the live database. These corrections follow from it.

| # | Finding | Correction | Section |
|---|---|---|---|
| K-1 | The deposit account is the `customer_deposit` mapping: 2175 on live. 2350 is only the fallback code used when no mapping exists. | Journals and checks name the mapping, not 2350. | §3.3–3.5, AD-6 |
| K-2 | `DocumentBuilderService` exists and is on the confirm path (`InvoiceService.createCopied` → `createDocument`). Revision 3 removed it from TA-12 as "not found", which was wrong. A NUL byte in that file makes plain `grep` skip it. Re-checked with `grep -a`, it has no independent transaction. | Added to TA-12 and to the audit list. | §5.4 |
| K-3 | `Document` rows are inserted in only three places: the builder, `RecurringService.generateOne` and `DocumentEngineService.createCounterpart`. | The builder becomes the single entry point; no new factory class. | §8.2 |
| K-4 | A `NOT VALID` CHECK is still enforced on every UPDATE, so a payment on an old invoice without `sourceKind` would fail. The guard also named a `type` column; it is `documentType`. | Backfill `sourceKind` first, then add and validate the constraint. | §8.2, §12, §13 |
| K-5 | The stock ledger is `InventoryLedger` (`referenceType`, `referenceId`, `sourceLineId`). `sourceLineId` is empty on 122 of the 137 live cash-sale rows (written before 3d493469, 2026-11-15). `SalesSubmissionLine.issueLedgerCode` maps every line exactly. | Mapping, return linkage and checks use `sourceLineId`, or `issueLedgerCode` for older rows; new check L-2. | §7.2, §10.2, §10.3 |
| K-6 | Gate-pass and DMS keys are `GatePass.sourceType` / `sourceId` (+ `GatePassLine.sourceLineId`) and `DmsDocumentLink.resType` / `resId`. | Column names corrected. | §10.2, §10.3 |
| K-7 | Mobile-money providers are free text: `mtn`, `mtn mobile money`, `airtel`, `airtel money`. | The migration normalizes them through an approved mapping. | §10.2 |
| K-8 | Legacy pickups show `deliveryStatus = pending` although the stock was issued at confirm. 5 legacy cash sales use home delivery, 2 of them still open. There are 4 drafts. | Fulfilment comes from the issue rows; open deliveries and drafts are handled at drain (OD-15). | §10.2, §10.3, §14 |
| K-9 | WAL archiving is off on live, so recovery is to the last nightly dump only. Lock-release timeouts are off. | Facts recorded under §6 and OD-13. | §6, §14 |

---

## 1. Context and decisions

Sales are recorded today by unrelated documents:
- B2B `SalesOrder`;
- branch cash sale `SalesSubmission`;
- direct `POST /invoices`;
- POS `Order` → POS `Invoice` (also `/orders`, rental, repair).

Each has its own stock point, GL path and payment model. Reports read only `Document` invoices, so they miss POS
revenue and confirmed-but-uninvoiced sales.

**Owner decisions (2026-11-28, approved)**:
1. Quotation stays separate and converts into a Sale.
2. Cash sale = Sale of type `cash`: handover, payment and invoice at the counter.
3. Customer invoices come from a Sale. Module-owned billing keeps its source. Rolled out per organization after a gate.
4. POS stays separate (NestJS parity) and is reported centrally.

**The Sale record**:
- **Table**: `SalesOrder` / `SalesOrderLine` keep their names; the UI says "Sale".
- **New columns**: `saleType` (`order` | `cash`), `origin`, cash identity fields, `profileId`, `saleDate`,
  `fiscalStatus`, `legacyData`, `approvalStatus` with its binding fields, outcome amounts (§4.4), `qtyReturned` and
  `qtyWrittenOff` (§4.5).
- **New tables**: `SalesOrderPayment` (tender lines) and `SalesOrderEvent` (timeline).
- **Kept**: `BranchSalesProfile` holds the cash settings per branch.
- **Numbering**: `SO-YYYY-nnnnn`; cash `CSS-<prefix>-<yyyy>-nnnnn` on the existing counter `css:<branchId>:<year>`.
  Invoice numbers are gap-free at post.

---

## 2. Scope of the first release

| In scope | Gated until a decision | Unchanged |
|---|---|---|
| Cash sale with **pickup** (proven handover, §5.3) | Cash sale paid now, delivered later (AD-1) | POS, `/orders`, rental, repair, Android sync |
| State dimensions; approval transitions; completion rules (§4) | Revenue deferral for invoice-before-delivery orders (AD-2, §3.6) | Quotation features |
| D-VAT-1 correction (§3.5) | Freight on the sale invoice (AD-10, OD-4) | Exchange-rate lock at confirm |
| Idempotency lease and fencing; fixes D-IDEM-1 (§6) | Manual-invoice restriction switched on per organization (OD-3) | Legacy fleet invoice reviews |
| Returns linked to issues; serial/lot validation; credit kinds; financial outcome (§4.4, §7) | Kernel in-transaction event outbox (OD-12, separate ADR) | |
| Invoice from sale; typed invoice-source contract (§8) | Retiring quotation → POS `Order` conversion (OD-2) | |
| Cash-sale migration; consumer switch; rollout tooling (§10, §11) | | |
| Central reporting measures, POS included (§9) | | |

**Interim path for "paid now, delivered later"**: an order-type Sale with a full advance (J1 or J1-D below). It uses
only existing postings. In Mode R it needs the D-VAT-1 fix first.

---

## 3. Accounting model

### 3.1 Five events per sale

| Event | Meaning | Recorded by |
|---|---|---|
| E1 Invoice issuance | Legal tax invoice; gap-free number; fiscal document | invoicing `Document`; fiscal outbox |
| E2 Payment collection | Money received from the customer | `Payment` / `CustomerDeposit` |
| E3 Tax point | Output VAT liability arises | tax lines on the down-payment or final invoice |
| E4 Revenue recognition | Control of the goods transfers | revenue lines on the invoice, deferral release, or accrual |
| E5 COGS | Inventory leaves the books | stock issue at handover / dispatch (P4) |

Rule: **revenue follows E4, never E1 alone.** If E1 comes before E4, the revenue part goes to a contract liability, or
the money is held as a deposit, until E4.

### 3.2 Advance tax modes (existing setting `SalesSettings.depositTaxPoint`, V683, default `delivery`)

| Mode | Setting | What happens to an advance |
|---|---|---|
| **D** (default) | `delivery` | The advance is untaxed. A plain deposit (P2), or a down-payment invoice without tax, credits `customer_deposit` with the **gross** amount received. VAT arises on the final invoice, which is settled by applying the deposit (P9). |
| **R** | `receipt` | VAT is due on the advance. A plain deposit on a taxed order is refused (`SalesDepositReceiptGuard`). A **down-payment tax invoice** is issued: Dr AR gross / Cr `customer_deposit` net / Cr VAT (P3). `registerDownPayment` records a deposit of the **net** amount. |

**Corrected rule for Mode R (fixes D-VAT-1)**:
- The final invoice **deducts** the taxed down payment with a **negative line on the deposit account, carrying the same
  tax** (Odoo's method).
- The deduction consumes the deposit. P9 is used **only** for untaxed deposits (Mode D).
- Zero-total final invoices are allowed (100% advance; AD-17).

### 3.3 Journal examples

**Common data**: 10 units × 100.00 = net 1,000.00; VAT 18% = 180.00; gross 1,180.00; unit cost 60.00 (COGS 600.00).

**Deposit account**: the `customer_deposit` account mapping. It is 2175 in both live organizations; code 2350 is
created only when no mapping exists (`CustomerDepositService.depositAccount`). It is written "deposit" below.

| Required scenario | Where |
|---|---|
| 100% down payment with a tax invoice | J1 (Mode R); J1-D (Mode D) |
| Partial advance, then final delivery | J2 (both modes) |
| Deposit taxed at receipt | Mode R: J1, J2-R, J3, J4-A, J4-D |
| Deposit taxed at the final invoice | Mode D: J1-D, J2-D, J3 Mode D variant, J4-C |
| Partial delivery and partial invoicing | J3 (pro-rata, oldest-first and Mode D variants) |
| Credit note after an advance invoice | J4-A (cancel), J4-B (return), J4-D (advance reduced) |

**J1 — 100% advance with a tax invoice (Mode R)**

| Step | Entry | Dr | Cr |
|---|---|---|---|
| 1 Down-payment invoice DP-1 (P3) | AR | 1,180.00 | |
| | Customer deposit | | 1,000.00 |
| | Output VAT | | 180.00 |
| 2 Receipt (P11) | Bank / AR | 1,180.00 | 1,180.00 |
| 3 Dispatch 10 units (P4) | COGS / Inventory | 600.00 | 600.00 |
| 4 Final invoice INV-1: goods 1,000.00 + VAT 180.00; deduction −1,000.00 (deposit) VAT −180.00 → **total 0.00** | Customer deposit | 1,000.00 | |
| | Output VAT (deduction) | 180.00 | |
| | Revenue | | 1,000.00 |
| | Output VAT (goods) | | 180.00 |

Balances: AR 0 · deposit 0 · **output VAT 180.00 (once)** · revenue 1,000.00 · bank 1,180.00 · COGS 600.00.

*Today's code (D-VAT-1)*: step 4 posts Dr AR 1,180.00 / Cr revenue 1,000.00 / Cr VAT 180.00. The 1,000.00 deposit is
then applied, which leaves AR open at **180.00** and output VAT at **360.00**.

**J1-D — 100% advance, Mode D**: P2 Dr Bank / Cr deposit 1,180.00 → P4 → P8 Dr AR 1,180.00 / Cr revenue 1,000.00,
Cr VAT 180.00 → P9 Dr deposit / Cr AR 1,180.00. AR 0, deposit 0, VAT 180.00.

**J2 — 30% advance, then full delivery**

| Mode R (deposit taxed at receipt) | Dr | Cr |
|---|---|---|
| DP invoice: AR / deposit 300.00, VAT 54.00 | 354.00 | 354.00 |
| Receipt: Bank / AR | 354.00 | 354.00 |
| Dispatch: COGS / Inventory | 600.00 | 600.00 |
| Final invoice: AR 826.00, deposit 300.00, VAT 54.00 / revenue 1,000.00, VAT 180.00 | 1,180.00 | 1,180.00 |
| Receipt: Bank / AR | 826.00 | 826.00 |

Balances: AR 0, deposit 0, **VAT 54 + 180 − 54 = 180**, revenue 1,000, bank 1,180.

| Mode D (deposit taxed at the final invoice) | Dr | Cr |
|---|---|---|
| Deposit (P2): Bank / deposit | 354.00 | 354.00 |
| Dispatch: COGS / Inventory | 600.00 | 600.00 |
| Final invoice (P8): AR / revenue 1,000.00, VAT 180.00 | 1,180.00 | 1,180.00 |
| Apply deposit (P9): deposit / AR | 354.00 | 354.00 |
| Receipt (P11): Bank / AR | 826.00 | 826.00 |

Balances: AR 0, deposit 0, VAT 180, revenue 1,000, bank 1,180.

**J3 — Partial delivery and partial invoicing, 50% advance, Mode R, pro-rata deduction (AD-14)**

| Step | Entry |
|---|---|
| DP invoice 590.00 | Dr AR 590 / Cr deposit 500, Cr VAT 90; receipt 590 |
| Dispatch 4 units | Dr COGS 240 / Cr Inventory 240 |
| Invoice 1 (4 units): goods 400 + VAT 72; deduction 400/1,000 × 500 = 200 + VAT 36 → total **236.00** | Dr AR 236, Dr deposit 200, Dr VAT 36 / Cr revenue 400, Cr VAT 72; receipt 236 |
| Dispatch 6 units | Dr COGS 360 / Cr Inventory 360 |
| Invoice 2 (6 units): goods 600 + VAT 108; deduction rest 300 + VAT 54 → total **354.00** | Dr AR 354, Dr deposit 300, Dr VAT 54 / Cr revenue 600, Cr VAT 108; receipt 354 |

Balances: AR 0 · deposit 0 · **VAT 90 + 36 + 54 = 180** · revenue 1,000 · COGS 600 · bank 1,180.
- *Oldest-first variant*: invoice 1 deducts 400 + 72 (total 0); invoice 2 deducts 100 + 18 (total 590). VAT
  90 + 0 + 90 = 180.
- *Mode D variant*: deposit 590 untaxed; P9 applies 472 to invoice 1 and 118 to invoice 2; receipt 590. VAT 72 + 108 =
  180.

**J4 — Credit note after an advance invoice**

| Case | Entries | Balances after |
|---|---|---|
| A. Order cancelled before delivery; Mode R advance 354 (300 + 54) paid | Credit note on DP-1: Dr deposit 300, Dr VAT 54 / Cr AR 354. The linked deposit record is reduced by 300; refused if already consumed (AD-15). Refund: Dr AR 354 / Cr Bank 354, **or** keep as customer credit. | deposit 0, VAT 0, AR 0 (or a 354 customer credit), bank 0 |
| B. Return of 2 units after the J2-R final invoice | Credit note on INV-1, goods only: Dr sales returns 200, Dr VAT 36 / Cr AR 236. Stock return (P13): Dr Inventory 120 / Cr COGS 120. Refund 236 or keep as credit. The deduction is not reversed. | revenue net 800, VAT 144, COGS 480, deposit 0, customer net paid 944 |
| C. Order cancelled; Mode D deposit 354 | Refund (P10): Dr deposit 354 / Cr Bank 354; no VAT effect | deposit 0, VAT 0 |
| D. Advance reduced 354 → 236 before delivery (Mode R) | Credit note on DP-1: Dr deposit 100, Dr VAT 18 / Cr AR 118; refund 118. The final invoice deducts 200 + 36 → total 944. | VAT 54 − 18 + 180 − 36 = 180; deposit 0; cash 354 − 118 + 944 = 1,180 |

**Reconciliation summary** (closing balances; cash = net received after refunds):

| Example | AR open | Deposit | Output VAT | Revenue (net) | COGS | Cash | Check |
|---|---|---|---|---|---|---|---|
| J1-R, J1-D, J2-R, J2-D, J3 (all variants), J4-D | 0 | 0 | 180 | 1,000 | 600 | 1,180 | VAT = 18% × revenue; cash = revenue + VAT |
| J4-A, J4-C (cancelled, refunded) | 0 | 0 | 0 | 0 | 0 | 0 | nothing left open |
| J4-B (2 units returned, refunded) | 0 | 0 | 144 | 800 | 480 | 944 | VAT = 18% × 800 |
| **Today's code, J1-R (D-VAT-1)** | **180** | 0 | **360** | 1,000 | 600 | 1,180 | ✗ VAT counted twice; customer shown as owing 180 |

### 3.4 Invariants (asserted in every J test and in nightly reconciliation)
- **V1**: Σ output VAT over all documents of a sale = VAT on net delivered and invoiced goods. Nothing counted twice.
- **V2**: Deposit subledger (open deposits) = `customer_deposit` balance. For every deposit, consumed
  (deductions + applications + credit notes + refunds) ≤ amount.
- **V3**: AR subledger (open residuals) = AR control. For each sale, outstanding = §4.4 formula.
- **V4**: Revenue for a sale = Σ goods lines − credit notes (+ deferral releases, §3.6).
- **V5**: Trial balance sums to zero (existing `SalesLedgerAssertions`).

### 3.5 D-VAT-1 — pre-existing defect on `main` (stage C1b)
- **Evidence**:
  - `SalesBillingService.createInvoice` (regular mode, `:213-251`) copies the order lines with full tax and adds no
    deduction line.
  - When `depositTaxPoint = receipt`, the down payment (`:203-212`) is a tax-inclusive line on the deposit account.
  - `CustomerDepositService.registerDownPayment` (`:350-385`) records the **net** subtotal; `applyDeposits`
    (`:591-614`) settles only that net amount.
  - No test covers a taxed down payment followed by the final invoice. `SalesAccountingPolicyIT` only tests the
    refusal of a plain deposit.
  - Revision 2 of this ADR (F2 Option C, F5) described the same flow without noticing the defect.
- **Affected**: organizations with `depositTaxPoint = receipt`. The default `delivery` is not affected.
- **Fix (built in C1b, migration V731; AD-14 pro-rata, AD-15 and AD-17 approved 2026-11-28)**:
  - Regular invoices of orders with taxed down payments get one deduction line per down payment: deposit account, the
    down payment's tax, negative quantity. Share = what is left of the down payment × net of the invoice's goods ÷ net
    of the order not yet invoiced; the invoice that leaves nothing to invoice takes the rest. A whole down payment at
    once is its own line negated (exact net and VAT); a part is a tax-exclusive line, rounded by the tax engine to the
    currency's decimals like any line.
  - A regular invoice is refused while a taxed down-payment invoice of the order is a draft, and when the deductions
    would exceed the invoice.
  - The sales post guard rechecks each deduction (line unchanged, down payment still posted, never deducted beyond its
    net). Consumption is booked **in the posting transaction** through a new hook, `InvoicePostGuard.afterPost`,
    because `invoice.posted` subscribers run after commit and are not atomic with the post (TA-3). Cancelling a posted
    final invoice releases its deductions.
  - `DepositApplication` gains the kinds `deduction` and `credit_note` (no payment); `SalesInvoiceLink` gains the kind
    `deduction` with `downPaymentDocumentId`.
  - A credit note on a DP invoice credits the deposit account only and consumes the deposit; refused once nothing is
    left (AD-15); reversing it releases the deposit.
  - P9 (apply) and P10 (refund) are refused for a taxed down-payment deposit; automatic application skips it.
  - Zero-total invoices post without an AR line and are paid at once; negative lines post as debits; a negative total
    is refused. The V206 comment "never a negative invoice line" is superseded (V206 itself is not edited).
  - Tests: `SalesDownPaymentVatIT` (J1, J1-D, J2-R, J3, J4-A, J4-D, a rounding case in UGX, draft and cancel release,
    the draft down-payment guard), each asserting output VAT, deposits, AR, the AR tie-out and the trial balance.
    Details in `sales-postings.md` §5a.
- **Historical rows**: the C0 read-only assessment lists organizations with `receipt`, and any posted final invoices
  that followed taxed down payments. Corrections only with accountant approval, as credit notes, never by editing
  (AD-18).

### 3.6 F4 — invoice issued before ownership transfer (gated, AD-2)

Today a regular invoice issued before dispatch (product `invoicePolicy = ordered`) posts revenue at the invoice.
Proposed treatment (Option A):

| Event | Entry |
|---|---|
| Invoice post | For each line, deferred qty = invoiced qty − delivered qty at post (≥ 0). Dr AR (N + T) / Cr revenue (delivered share) / Cr **contract liability** (deferred share; the `unearned_revenue` mapping, 2400 in both live organizations, fallback code `DEFERRED-REVENUE`, as `DeferralService` uses for ADR-S073) / Cr VAT T (tax point at invoice) |
| Ownership transfer (current policy: **dispatch**; POD if the policy changes, AD-16) | Per load line: release = min(remaining deferred for the line, dispatched qty × line unit net after discounts). Dr contract liability / Cr revenue. Journal source `sales_revenue_release`, key `srr:<loadLineId>` (once). |
| Load bring-back after release | Reverse the release: Dr revenue / Cr contract liability |
| Quantity credit note on the undelivered part | Dr contract liability (not revenue) + Dr VAT / Cr AR |
| Close short of invoiced-but-undelivered qty | Refused until a credit note covers it (invariant: deferred balance per line ≥ 0, and = 0 when the sale is done) |
| FX | The contract liability is non-monetary: released at the invoice's carrying rate, never revalued |

- Option B instead of A: a period-end deferral journal for invoiced-undelivered quantities, reversed next period.
- Option C: accept the current behaviour under a materiality threshold.

AD-2 decides. **Nothing is built before AD-2.**

**As built (C2b, V734; AD-2 = Option A and AD-16 = dispatch, approved 2026-11-28)**:
- **What is deferred.** Per sale line, T = max(N − (D − Rc − W), 0), never above N:
  - N = net invoiced: posted regular links − posted credit-note links;
  - D = net dispatched: fleet load lines of the sale's live delivery orders, on trips dispatched or later, less what
    came back;
  - Rc = returned and credited; W = written off.

  T is recomputed at every event, not only at the invoice post. A line without a product, or worth nothing, never
  defers.
- **Layers.** `SalesRevenueDeferral` keeps T per posted invoice line, newest posted invoice first. Each layer carries
  its own functional amount per unit: line subtotal ÷ quantity × that invoice's rate. A dispatch releases the oldest
  invoiced units; a bring-back holds the newest again. The liability is never revalued. Every change is a
  `SalesRevenueMovement` and posts only the difference.
- **Journals.** The invoice keeps posting its revenue as before; `InvoicingWorkflowsInitializer` is unchanged. In the
  same transaction, a separate entry moves the deferred share:
  - defer: Dr revenue / Cr contract liability. Journal GEN, source `sales_revenue_deferral`, source id = the sale.
  - release: Dr contract liability / Cr revenue. Source `sales_revenue_release`.
  - The revenue line uses the invoice line's own account (the category's income account), partner, cost centre and
    dimensions. The liability line carries the customer. Both are in base currency.
  - The net effect equals the split invoice entry above (V4), while the invoice posting path stays as it is.
  - Posting key `<source>:<uuid>` instead of `srr:<loadLineId>`, because one entry can cover several lines and
    invoices. Being exactly once comes from the stored layers under the sale lines' locks.
- **Events.** Each runs inside the transaction of the change, under the sale lines' row locks (the same lock as the
  fulfilment rebuild):
  - **invoice post**: `InvoicePostGuard.afterPost` (the C1b hook). Dated like the invoice's entry.
  - **cancel of a posted invoice**: the new hook `InvoicePostGuard.afterCancel`, after the workflow has reversed the
    invoice's entry. Dated like that reversal: the invoice's own date while its period is open.
  - **dispatch, POD with goods brought back, load returned**: the new `DeliverySourcePort.deliveryChanged(sourceId,
    loadId, at)`, called by `FleetDispatchService`, so fleet stays independent of sales. Dated on the business day of
    the event. A POD that delivers everything moves nothing.
  - **catch-up**: quantity credits (`recomputeInvoiced`), a fulfilment rebuild that changed a line, and the nightly
    `SalesReconcileJob` step. The nightly step re-syncs confirmed sales with posted invoices and any sale still
    holding deferred revenue, one transaction per sale.
- **Credit undelivered.** New route `POST /sales/orders/{id}/lines/{lineId}/credit-undelivered`:
  - needs `sales:order:close_short` **and** `sales:credit_note:write`; Idempotency-Key and reason required.
  - For each posted invoice of the line, newest first, it posts a quantity credit note against that invoice. The credit
    uses the invoice's price, discount and tax, the revenue account the invoice line posted to, and that line's
    dimensions. It is linked as `credit_note`.
  - Then the line closes short. Net effect: Dr contract liability + Dr VAT / Cr AR.
- **Close short** is refused while the line has T > 0, with the message "<line>: <T> invoiced but not delivered.
  Deliver them, or credit the undelivered quantity to close the line".
- **Views and reversal.** The sale detail shows `qtyDeferred` / `amountDeferred` per line and `revenueDeferred` on the
  sale, in functional currency. Both sources are registered in `SubledgerSources`, so generic reversal is refused and
  names the credit-undelivered route or the trip return.
- **Deviation: value credit notes.** A value credit note (price or goodwill) on an invoice whose goods are still
  deferred debits revenue at once, and the deferral is not reduced, because value credits carry no quantity (§7.3).
  Until the goods leave, revenue is understated and the contract liability overstated by that credit. Once the goods
  leave, both are correct.
- **Not built:** the period-end accrual (AD-3), and cash sales delivered later (AD-1).

### 3.7 Scenario flows

```
F1  CASH PICKUP (T1; E1=E2=E3=E4=E5 at confirm)
    P4  Dr COGS / Cr Inventory (branch)      P8  Dr AR / Cr Revenue, Cr VAT      P11  Dr Tender / Cr AR (per tender)
F2  CASH PAID NOW, DELIVERED LATER (gated AD-1)
    A: invoice at confirm → Cr contract liability; release at dispatch (as F4 option A)
    C: interim = order + full advance (J1 Mode R with deduction, or J1-D Mode D)
    B: bill-and-hold (not recommended)
F3  ORDER DELIVERED THEN INVOICED: P4 at dispatch, P8 at invoice; period-end unbilled accrual gap → AD-3
F4  ORDER INVOICED BEFORE DELIVERY: §3.6 (AD-2)
F5  ADVANCE / DOWN PAYMENT: J1–J3 (Mode R deduction or Mode D application)
F6  RETURN → CREDIT NOTE → SETTLEMENT: J4-B; three separate records (§7)
F7  POS (unchanged): checkout journal + receipt; async stock job; refund = GL reversal + payout + disposition
```

---

## 4. State model

### 4.1 Dimensions

| Dimension | Column | Values | Changed by |
|---|---|---|---|
| Commercial | `status` | `draft`, `confirmed`, `done`, `cancelled` | sales commands |
| Approval | `approvalStatus` (new) | `not_required`, `required`, `pending`, `approved`, `rejected` | sales commands + approval engine |
| Hold (overlay) | `onHold`, `holdKind` | none / `manual`, `credit`, `customer` | sales, credit |
| Fulfilment | `deliveryStatus` (+ `returnStatus`) | `not_required`, `none`, `partial`, `full` | derived (fleet loads, returns, close short) |
| Invoice | `invoiceStatus` | `nothing`, `to_invoice`, `partial`, `invoiced` | derived (§4.5) |
| Payment | `paymentStatus`, `settlementStatus` (new) + outcome amounts | §4.4 | derived (invoices, credit notes, receipts, refunds) |

### 4.2 Approval

```
APPROVAL — independent of commercial status (the sale stays draft throughout)

   save/edit: rules evaluated                      save/edit: rules evaluated
 not_required ◀─────────────────────────────────────────▶ required
      ▲                                                     │ SUBMIT (explicit command)
      │                                                     ▼
      │                     WITHDRAW (submitter) ◀──── pending ────▶ edits of approvable fields refused
      │                        → required                   │
      │                               APPROVE ──────────────┴────────── REJECT (reason)
      │                                  ▼                                    ▼
      │                               approved                             rejected
      │                                  │ edit approvable fields             │ edit → re-evaluate (required / not_required)
      │                                  ▼                                    │ RESUBMIT unchanged + justification → pending
      └──────────────────────── re-evaluate (required / not_required) ◀───────┘

 Happy path:  draft·required ─submit→ draft·pending ─approve→ draft·approved ─confirm→ confirmed·approved
 CONFIRM allowed only if approvalStatus = not_required,
   or approvalStatus = approved ∧ approvedHash = current content hash ∧ current reasons ⊆ approved reasons
```

| Transition | Actor / permission | Rules |
|---|---|---|
| evaluate | system, on every save | Reasons = discount / margin / amount / agreement rules. None → `not_required`; some → `required`. |
| submit | creator or editor: `sales:order:write` (order), `sales:cash_sale:submit` (cash) | Only from `required`. Stores `approvalSubmittedHash` (hash of approvable content), the reasons and `ruleVersion` (hash of the active rules). Creates an approval request. |
| withdraw | submitter | `pending` → `required`; request cancelled |
| approve | `sales:order:approve` (order), `sales:cash_sale:approve` (cash); approval engine policy (multi-step allowed) | **Approver ≠ creator, submitter, or last editor of approvable fields.** Stores `approvedHash`, `approvedReasons`, `approvedRuleVersion`. |
| reject | same as approve | Reason required → `rejected` |
| resubmit | submitter | From `rejected`: with changed content (re-evaluation → `required` → submit), or unchanged with a written justification. Each submission is a new request; history kept. |
| invalidate | system | Any edit of approvable fields while `approved` → re-evaluate. Approvable fields: lines, product, variant, qty, unit, price, discounts, order discount, customer, payment terms, pricelist, currency/rate, agreement. Edits are refused while `pending`. Non-approvable fields (notes, contacts) don't invalidate. |
| rule change | system, at confirm | Recompute reasons with the current rules. New reasons not covered by the approval → `required`; only the new reasons need approval. Relaxed rules don't revoke an approval. |
| auto-confirm | system | **Order type only**, when the new org setting `confirmOnApproval` is on. Default on, because today `approve` confirms at once (`SalesOrderService:1003`). It runs the confirm transition with its own guards. **Never for the cash type.** |

**Enforcement in the backend**:
- Every transition is a backend command checked in `SalesOrderService`: state, approval status, permission,
  separation of duties and content hash. The UI only shows the actions the API returns as allowed.
- Commands: existing `approve` and `reject`; new `submit-approval` and `withdraw-approval`. `confirm` refuses a sale
  that needs approval: "This sale needs approval: submit it for approval first". There is no implicit submit.
- The submit button ships in C1 together with the backend change, so the web never breaks between stages.
- AP tests call the HTTP API.

**Self-approval**: today a `sales:configure` holder may approve their own order (`SalesOrderService:901`). Revision 3
proposes no self-approval at all, with an org setting to allow it for `sales:configure` holders, audited (OD-8).

**As built (C1, V732; OD-7 and OD-8 approved 2026-11-28)**:
- Routes: `POST /sales/orders/{id}/submit-approval` (`sales:order:write`, optional `justification`) and
  `POST /sales/orders/{id}/withdraw-approval` (`sales:order:write`; the submitter, or a `sales:configure` holder);
  `approve` (Idempotency-Key required, `sales:order:approve`) and `reject` keep their paths.
- The order detail carries `approvalStatus` as it stands now (for a draft it is recomputed with the current rules, so a
  stricter rule shows at once), the derived `stage`, and `actions`: the steps the caller may take now
  (`submit_approval`, `withdraw_approval`, `approve`, `reject`, `confirm`). The web shows only those buttons.
- Content hash (`SalesApprovalContent`, SHA-256): customer, payment mode and terms, pricelist, currency and entered
  rate, agreement, order discount; per line the product, variant, quantity, unit and factor, price, discounts, tax and
  tax-inclusive flag. Line order and line ids do not count. Notes, contacts, dates and delivery details are not
  approvable content. Edits of approvable content are refused while pending; notes can still be edited.
- Reasons are compared by their text. A reason whose text changes (for example a smaller agreement balance) needs
  approval again. A lapsed approval does not come back when the content is restored: the sale is submitted again.
- `SalesSettings.allowSelfApproval` (default off): when on, a `sales:configure` holder may approve an order they
  created, submitted or last edited, and the `approve` audit row records `selfApproval: true`. An inbox decision by one
  of the order's makers is not applied: the sale goes back to `required` (audit `approval: refused`).
- `SalesSettings.confirmOnApproval` (default on): an order, never a cash sale, confirms right after its approval with
  every confirm check, inside a savepoint. A refusal leaves the order approved; the approve response returns it as
  `confirmError` (inbox decisions keep it in the audit trail).
- Approvals inbox: the subscriber now loads the decider's permissions, so order visibility (`sales:order:read_all`) and
  the self-approval setting apply to inbox decisions. Before, a decider who did not own the order could not apply the
  decision (the confirm path re-read the order through the visibility filter and failed).
- Offline sync: when a device asks to confirm an order that needs approval, the order is submitted for approval on the
  rep's behalf, and the device outcome stays `pending_approval`.
- Fixed in passing: `approve_step` was not a valid audit action, so a multi-step approval answered 500.

### 4.3 Commercial transitions

```
draft ──discard──▶ cancelled
draft ──CONFIRM [approval OK; cash: tenders verified + paid in full + handover proven]──▶ confirmed
confirmed ──cancel [nothing delivered, nothing invoiced (net), no advance held]──▶ cancelled
confirmed ──auto [completion rule §4.5]──▶ done
done ──(cash) Reverse command completed──▶ cancelled (reversedAt; financial outcome retained, §4.4)
```

Invariants:
- **I1**: confirmed ⇒ the approval condition of §4.2 holds.
- **I2**: issuing a delivery ⇒ confirmed and not on hold.
- **I3**: creating an invoice ⇒ status ∈ {confirmed, done} and to-invoice > 0.
- **I4**: `done` ⇔ the completion rule of §4.5.
- **I5**: `cancelled` from `confirmed` ⇒ nothing delivered, net invoiced 0, no advance held.
- **I6**: `cancelled` after `done` happens only through the cash Reverse command, with a completed reversal record.
  Returns processed separately keep the sale `done`, with `returnStatus = full`.
- **I7**: cash ⇒ `paymentMode = cash` (immediate terms), `profileId` and `branchId` set, no change requests.

### 4.4 Financial outcome, payment and settlement status

Stored on the sale and recomputed in the same transaction as any invoice, credit note, receipt, deduction, refund or
credit retention linked to it:

| Amount | Definition |
|---|---|
| `amountInvoiced` | Σ posted regular invoice totals (goods + tax; advance deductions netted) |
| `amountCredited` | Σ posted credit notes linked to the sale's invoices (quantity and value kinds) |
| `netInvoiced` | `amountInvoiced − amountCredited` |
| `amountReceived` | Σ receipts allocated + deposit applications + advance deductions for the sale |
| `amountRefunded` | Σ refunds paid out for the sale |
| `amountRetainedAsCredit` | excess moved from the sale to the customer's on-account credit ("keep as customer credit") |
| net received `R′` | `amountReceived − amountRefunded − amountRetainedAsCredit` |
| `outstanding` | `netInvoiced − R′` |
| `advanceHeld` | open deposits of the sale, not yet applied or deducted |

| `paymentStatus` | Condition |
|---|---|
| `no_charge` | sale total = 0 and R′ = 0 (replacement, sample) |
| `not_invoiced` | total > 0 and `amountInvoiced = 0` (an advance may be held: see `advanceHeld`) |
| `unpaid` | `netInvoiced > 0`, R′ = 0 |
| `partially_paid` | 0 < R′ < `netInvoiced` |
| `paid` | R′ = `netInvoiced` > 0 |
| `credit_balance` | R′ > `netInvoiced` (the customer is owed money on this sale) |
| `refunded` | `netInvoiced = 0` after credit notes, money was received and R′ = 0 with `amountRefunded > 0` |
| `credited` | `netInvoiced = 0` after credit notes, R′ = 0 and nothing refunded (nothing paid, or all retained as credit) |

- `creditStatus`: `none` | `partial` | `full` (by `amountCredited` / `amountInvoiced`).
- `settlementStatus`: `settled` ⇔ `outstanding = 0` ∧ `advanceHeld = 0`, i.e. paymentStatus ∈ {`paid`, `refunded`,
  `credited`, `no_charge`}. Otherwise `open`.
- **Keep as customer credit** is an explicit action, with permission and audit. The excess moves from the sale to an
  unallocated on-account credit of the partner, and a timeline event records it. The sale becomes `settled`; the
  customer balance keeps the credit.

| Example | inv / cred / recv / refund / retained | paymentStatus | creditStatus | settlement | commercial |
|---|---|---|---|---|---|
| Cash sale, normal | 1,180 / 0 / 1,180 / 0 / 0 | paid | none | settled | done |
| Cash sale reversed and refunded | 1,180 / 1,180 / 1,180 / 1,180 / 0 | **refunded** | full | settled | cancelled (reversed) |
| Fully credited before payment | 1,180 / 1,180 / 0 / 0 / 0 | **credited** | full | settled | done (§4.5) |
| Partially credited 236 after full payment | 1,180 / 236 / 1,180 / 0 / 0 | **credit_balance** (236) | partial | open | order: done · cash: confirmed until settled |
| … then refunded 236 | 1,180 / 236 / 1,180 / 236 / 0 | paid | partial | settled | done |
| … or kept as customer credit | 1,180 / 236 / 1,180 / 0 / 236 | paid | partial | settled | done |
| Zero-value replacement | 0 / 0 / 0 / 0 / 0 | **no_charge** | none | settled | done when delivered |

The full history stays on the sale and its timeline. Nothing is collapsed to "not applicable".

**As built (C2, V733; AD-9, AD-19 and AD-20 approved 2026-11-28)**:
- Stored on `SalesOrder`: the eight amounts, `creditKept`, `paymentStatus`, `creditStatus`, `settlementStatus` and
  `outcomeAt`. `SalesOutcomeService` computes them from posted documents only.
- Definitions, where they differ from the table above:
  - `amountInvoiced` counts posted regular invoices **gross** of their down-payment deductions. A zero-total final
    invoice after a 100% advance still counts what it sold, so that sale is `paid`, not `no_charge`.
  - `amountCredited` counts posted credit notes linked to the sale's lines or issued against its regular invoices,
    of both kinds. Credit notes on its down-payment invoices are left out, because they reduce the advance (AD-15).
  - Bad-debt write-offs (`POST /sales/invoices/{id}/write-off`) are counted apart, in the new `amountWrittenOff`,
    with the new payment status `written_off`. They are not credits.
  - `amountOutstanding` = open invoice balances − the sale's open credit not kept as customer credit. It is negative
    when the customer is owed money.
  - `amountReceived` = what settled the sale's invoices (receipts, deposit applications, deductions), plus what was
    later refunded or kept as credit. Net received (R′ above) = received − refunded − retained.
  - `amountRetainedAsCredit` = the sale's credit used on other documents, plus credit kept by the explicit action.
- `paymentStatus` is decided in this order:
  1. nothing invoiced → `no_charge` when the total is 0 or the sale is done or cancelled, else `not_invoiced`;
  2. outstanding < 0 → `credit_balance`;
  3. outstanding > 0 → `partially_paid` when anything was received net, else `unpaid`;
  4. anything written off → `written_off`;
  5. net invoiced = 0 → `refunded` when anything was refunded, else `credited`;
  6. otherwise `paid`.
- `settlementStatus = settled` ⇔ outstanding = 0 ∧ `advanceHeld = 0` ∧ status not `draft` ∧ payment status not
  `not_invoiced`.
- When the outcome is recomputed:
  - in the transaction of every sales command that changes it (`refreshStatus` stores it);
  - on the invoicing events `payment.allocated`, `payment.voided` and `creditnote.issued` (`SalesOutcomeSubscriber`;
    an absolute recompute, safe to run twice; a failure is logged in the sales event inbox);
  - inside the deposit's own transaction after a receipt, application, refund, void or reversed application, through
    the new hook `DepositChangeListener` (invoicing calls it and depends on no module);
  - nightly, for every non-draft sale not settled or never computed (`SalesReconcileJob.refreshOutcomes`). This
    catches a credit allocated from the invoicing screens, which raises no event;
  - the order detail computes it live.
- **Keep as customer credit**: `POST /sales/orders/{id}/keep-credit` with `{reason}`.
  - Permission `sales:credit_note:write` (no new permission).
  - It adds the sale's open credit to `creditKept`, and the audit row records the amount and the reason.
  - The credit notes stay open for the customer's other invoices; the sale becomes settled.
  - The detail lists `keep_credit` in `actions` when there is open credit and the caller may keep it.
- The order list filters on `paymentStatus` and `settlementStatus` (comma-separated).

### 4.5 Quantities and completion

Per sale line, in base units:

| Symbol | Column | Meaning |
|---|---|---|
| Q | `quantity` | ordered |
| D | `qtyDelivered` (existing) | net dispatched (fleet; bring-backs deducted) |
| S | derived from `closedShort` (existing flag, `SalesOrderService.closeShort`) | Q − D when the line is closed short, else 0 |
| R | `qtyReturned` (new) | received back through verified returns (§7.2) |
| W | `qtyWrittenOff` (new) | quantity credited **without** a return (goods kept or lost), approved (AD-20) |
| I | `qtyInvoiced` (existing) | on posted regular invoices |
| C | `qtyCredited` (existing) | on posted **quantity** credit notes (§7.3); value credit notes never change it |

**Derived values**:
- **Fulfilment complete** ⇔ D + S ≥ Q. Returns don't reopen fulfilment; a replacement is a new sale.
- **Billable** B:
  - policy `delivered`: D − R − W;
  - policy `ordered`: Q − S − R − W;
  - zero-value lines: B = 0, unless AD-19 requires a zero-value tax invoice.
- **Net invoiced** N = I − C; to invoice = B − N. If B − N < 0, the line is over-invoiced and needs a credit note.
- **Invoice status**: `nothing` when B = N = 0 on all lines; `to_invoice` when no invoice has been posted yet;
  `partial` or `invoiced` otherwise.

**Completion rule (`done`)**:
- **Order type**: confirmed ∧ fulfilment complete on every goods line ∧ to-invoice = 0 on every line ∧ no return
  awaiting credit or disposition ∧ `advanceHeld = 0`. Payment does not block `done`, as today; receivables follow
  collections through `settlementStatus`.
- **Cash type**: the same ∧ `settlementStatus = settled`.

| Case | Quantities | Result |
|---|---|---|
| Fully credited with a full return | D = Q, R = Q, C = Q | B = 0, N = 0 → done; `refunded` / `credited`; `returnStatus = full` |
| Credited without return (written off) | W = Q, C = Q | B = 0, N = 0 → done; write-off approved (AD-20) |
| 100% value credit (price / goodwill) | C unchanged | B = N = Q → done; `amountCredited = amountInvoiced` → `credited` or `refunded` |
| Zero-value sale | price 0 | B = 0 (unless AD-19) → done when fulfilment is complete; `no_charge`, settled |
| Short-closed | S = Q − D | invoice for what was delivered; any excess advance is refunded or kept as credit before `done` |
| Cancel | D = 0, N = 0, `advanceHeld = 0` | `cancelled`; refused otherwise |

**As built (C2, V733)**:
- R is split in two columns:
  - `qtyReturned`: goods physically back and kept (received − rejected);
  - `qtyReturnCredited`: the part of those settled by a credit note or a refund. This is the R of the billable formula.

  Goods returned for a replacement, or with no settlement, stay billable. `qtyWrittenOff` is W.
- B follows the table, with R = `qtyReturnCredited`. A zero-value line is never billable (AD-19: delivery note only).
- How B moves:
  - Closing a return or claim with a credit lowers the line's `qtyBillable` by what that close settled. The decision
    is recorded as "Returned on RMA-…" or "Written off on RMA-…", on the basis the line follows now.
  - A manual billable decision stays manual and keeps applying.
  - Fleet rebuilds compute physical − R − W, so they land on the same figure.
  - Before C2, a sticky manual decision was recorded for the uninvoiced part only, so later deliveries no longer
    changed the line.
- Billing's to-invoice, the invoice post guard and the nightly financial reconciliation all compare B with N = I − C
  (the invariant `sales.md` already states: gross invoiced − credited ≤ billable). The code compared gross invoiced,
  which was consistent only while B still counted returned goods. With B net of R and W, a gross comparison would
  refuse the remainder of a line that was partly invoiced and then partly credited.
- `qtyCredited` is recomputed absolutely from posted `credit_note` links on every credit path:
  - return close;
  - the sales credit flow (post, allocate, refund);
  - late disputes;
  - credit notes issued from the invoicing screens (`creditnote.issued`).
- **Completion as built**: `done` ⇔ every condition below holds.
  1. The sale is confirmed.
  2. Every line is delivered to its ordered quantity less what was cancelled or closed short (within tolerance).
  3. Every line has B = I − C.
  4. No load of the sale is dispatched or in transit without its proof of delivery.
  5. No return is open (requested, approved, received or inspected).
  6. `advanceHeld = 0`.
  7. For the cash type only: the sale is settled.
- Condition 4 is new (deviation):
  - Billing follows what the customer accepted at the POD. While a load waits for its POD, its B is 0, and the sale
    would otherwise look complete.
  - This also closes a gap that existed before C2: an order with a second load in transit turned done as soon as the
    first load was invoiced.
  - An `ordered`-policy order invoiced at confirm now turns done at the POD, not at dispatch.
- A done sale with nothing invoiced (zero value, or written off before invoicing) shows `no_charge`.
- Rejecting or cancelling a return, and every deposit change, recompute the sale at once, because an open return or a
  held advance keeps it open.

### 4.6 Compatibility
- **Backfill**: `pending_approval` → `draft` + `approvalStatus = pending`; `in_fulfilment` → `confirmed`. Outcome
  amounts, `qtyReturned` and `paymentStatus` are computed from posted documents.
- **Consumers to update**: 20 backend classes that use the old values (`SalesOrderService`, `SalesBillingService`,
  `SalesDeliveryService`, `SalesFulfilmentService`, `SalesCreditContributor`, the report and analytics services,
  `PlmReportService`, …), plus 13 web files and 2 test files.
- **Responses**: carry a derived `stage` with the legacy value for one release, for cached clients.
- **As built (C1, V732)**:
  - The migration backfills with a proof: counts per old status must equal the new combinations, or it raises. A draft
    whose last approval was rejected becomes `rejected`; a confirmed, done or cancelled order with an approved
    approval request becomes `approved`.
  - Database checks: the status and approval values; I1 (`confirmed` or `done` ⇒ `not_required` or `approved`); a cash
    sale has `paymentMode = cash` (the branch and profile part of I7 follows in C3).
  - `refreshStatus` no longer sets `in_fulfilment`. List filters `status=pending_approval` / `in_fulfilment` match
    through the derived stage, and so does the report-center order filter. The list also filters on `approvalStatus`.
  - TA-6: `SALES-QUARANTINE` is created for every organization that sends rejected goods there by default.

---

## 5. Cash sale

### 5.1 Payment model

| Concept | Stored in | Values | Meaning |
|---|---|---|---|
| Sale type | `saleType` (new) | `order`, `cash` | The process. `cash` = handover + invoice + full payment at the counter, in one confirm. |
| Payment terms | `paymentMode` (existing, V408) + `paymentTermId` | `cash` = immediate; `credit` = on account, with a due-date term (e.g. Net 30) | When the customer must pay. **Not the instrument.** UI labels become "Immediate payment" / "Credit"; stored values unchanged. Credit control applies to `credit`; it is bypassed for `cash` when `cashBypassesCredit` (existing setting) is on. Cash type ⇒ immediate. |
| Tender lines | `SalesOrderPayment` (new; cash type) | method `cash`, `mobile_money` (provider required, e.g. `mtn_momo`, `airtel_money`), `bank` (today's three values), `card` (new); amount; reference; verification state | How the customer actually paid. Several lines per sale. Order-type sales keep receiving money through receipts and deposits (unchanged). |

**Tender line life cycle**:

```
captured (pending) ──verify──▶ verified ──CONFIRM (T1)──▶ posted (receipt created, paymentId)
   │      └──verification fails──▶ failed ──remove / replace──▶ (deleted)
   └──delete (draft only)
posted ──payment void (reversal T2, payment:void, approval if configured)──▶ voided (sale paymentStatus recomputed)
```

**Rules**:
- **Unverified**: confirm is refused while any tender is `pending`. A `failed` tender is excluded and must be
  replaced.
  - Mobile money: verified by provider status (callback / `MobileMoneyPollJob`), or manually with reference and
    evidence.
  - Bank: manual verification (statement match later).
  - Card: terminal approval code. The card number is never stored.
  - Manual verification needs `sales:cash_sale:verify_payment`; segregation (verifier ≠ collector) per profile.
- **Underpayment**: Σ verified < total → confirm refused: "The sale is not fully paid: <paid> of <total>". The cashier
  adds a tender, removes lines, or creates an order-type sale on credit (credit control applies). There is no
  automatic conversion.
- **Overpayment, physical cash**: the tender line records `amountTendered` and `changeGiven`. Only the applied amount
  is posted. Change is never revenue or a deposit; it shows in drawer reconciliation.
- **Overpayment, non-cash**: refused by default. Optionally, with a permission (OD-9), the excess is kept as customer
  credit (§4.4).
- **After confirm**: corrections only by payment void + new receipt. A provider reversal (chargeback) → payment void →
  `partially_paid` / `unpaid` → collections.

### 5.2 Confirm transaction T1

| Step | Work | Locks (in order) | Rows |
|---|---|---|---|
| S0 | Idempotency claim, **outside** T1 (§6) | unique (org, key) | `IdempotencyRecord` |
| S1 | **Fence**, first statement of T1: lock the idempotency row with my attempt and token, or abort (§6) | `IdempotencyRecord` row | — |
| S2 | Lock and guard the sale: version; approval condition; every tender verified; Σ = total; profile and accounts valid; handover block valid (§5.3) | `SalesOrder` `FOR UPDATE` | — |
| S3 | Commercial `confirmed`; **no** trip-dispatch credit gate for the cash type (TA-7) | (held) | `SalesOrder` |
| S4 | Pickup delivery: open the delivery order, add the load (carrier `manual`, `customer_collect`, profile location) | advisory lock in `FleetDeliveryOrderService.fromSource` | fleet rows |
| S5 | Stock issue (P4) with serial/lot; POD with the handover evidence (§5.3) | stock locks in `StockMove.inLockOrder` | stock ledger, COGS journal, load quantities |
| S6 | **Derived state, synchronously**: `SalesFulfilmentService.rebuild` (delivered, billable). Events are not relied on (TA-3). | (held) | `SalesOrderLine` |
| S7 | Invoice draft from the sale (`InvoiceService.createCopied`, regular, `billingPolicy = ordered`) | — | `Document`, lines, `SalesInvoiceLink` |
| S8 | Invoice post as a system step (P8); post guards | `GaplessCounter` row (held to commit) | `Document`, journal |
| S9 | One receipt per verified tender (P11), allocated to the invoice | `Document` `FOR UPDATE` | `Payment`, `PaymentAllocation`, journals, `SalesOrderPayment.paymentId` |
| S10 | **Derived state, synchronously**: `recomputeInvoiced`, outcome amounts (§4.4), `refreshStatus` → `done` | (held) | `SalesOrder`, lines |
| S11 | Fiscal outbox row (no network call) | — | `FiscalDocument` (pending, unique `requestRef`) |
| S12 | Timeline + business-outcome evidence + commit-evidence stamp on the idempotency row | (held) | `SalesOrderEvent`, `BusinessOutcome`, `IdempotencyRecord` |
| commit | all of S1–S12, or nothing | | |
| after | Idempotency completed (compare-and-set); event outbox and audit writes (detached, best effort: TA-3 / TA-4); the fiscal job sends | | |

### 5.3 Proof of goods handover at pickup
- **Required on confirm**, in a `handover` block:
  - per line, the handed-over qty, which must equal the ordered qty (partial handover is not a pickup and is refused),
    with serial/lot where tracked;
  - `receivedByName` (optional ID / phone);
  - `handoverAffirmed = true`: the cashier affirms that the customer physically received the goods (UI checkbox:
    "The customer has received these goods");
  - optional signature or photo (DMS).
- **Stored on the pickup load's POD**: handedOverById, receivedBy, timestamp, affirmation. The delivery note is
  printed.
- **Gated yards**: the gate pass is created from the pickup load (existing gate-pass sources). Gate-out is the
  physical exit evidence. A mismatch between handed-over and gated-out quantities raises an exception report.
- **Goods not handed over now** (customer collects later, yard loading later) are not a pickup. Use the interim path
  (§2) until AD-1.

### 5.4 Transaction propagation audit

**Method**: read-only inspection of every service on the T1 call path (2026-11-28).
1. Grep for `REQUIRES_NEW`, `NOT_SUPPORTED`, `NESTED`, `TransactionTemplate`, `@Async`, executors,
   `CompletableFuture`, extra DataSources and transaction managers, and module or transactional listeners.
2. Trace each hit to its callers.
3. Check each confirm-path class one by one: `FleetDispatchService`, `FleetDeliveryOrderService`, `StockService`,
   `StockPostingService`, `StockReversalService`, `InvoicingWorkflowsInitializer`, `DocumentBuilderService`, `PostingService`,
   `SalesBillingService`, `SalesDeliveryService`, `CustomerDepositService`, `SalesOrderService`,
   `GaplessSequenceService`, `CreditService`. None has an independent transaction or async call.

C0 records the findings; the TP tests prove them before any C3 code.

| # | Component on the T1 path | Finding | Impact | Requirement |
|---|---|---|---|---|
| TA-1 | `GaplessSequenceService.next` | `Propagation.MANDATORY`; updates the counter row or takes an advisory lock | joins T1; lock held to commit | invoice post placed late (S8) |
| TA-2 | `SequenceService.next` (CSS, DN, DEP numbers) | no transaction annotation or template (verified) | joins T1 (not gap-free; gaps acceptable) | — |
| TA-3 | `EventBus.publish` / `publishOnCommit` | The outbox row is written by `DetachedWrites` on a virtual thread **after** the caller's transaction completes, in its own transaction. `publish` runs on commit and on rollback; `publishOnCommit` on commit only. | **Not atomic with T1.** A crash after commit loses the event, and subscribers that drive state may not run: `SalesBillingSubscriber` (qtyInvoiced, deposit application), `SalesFulfilmentSubscriber` (rebuild), `CustomerDepositSubscriber`, notifications. | T1 computes all derived sale state itself (S6, S10); for cash confirm, events are notifications only. The nightly `SalesReconcileJob` detects drift. A kernel in-transaction outbox goes to a separate ADR (OD-12). |
| TA-4 | `AuditService.record` | detached after completion, own transaction, `synchronous_commit = off` | best effort; not evidence | the business audit trail is `SalesOrderEvent`, written inside T1 |
| TA-5 | `CashSaleFiscalService` | `request()` inserts `FiscalDocument` in the caller's transaction: a true transactional outbox. Provider `none` completes inline, with no network call. `retry`, `processAll` and the cancel step of `gateReversal` use `REQUIRES_NEW`. | Confirm is fine. Reversal commits the fiscal-cancel request first, by design. | Keep. Reversal is two-phase (§7.4). |
| TA-6 | `VirtualLocationService.ensure`, via `SalesDeliverySource.rejectedGoodsLocation` | Find-or-create of `SALES-QUARANTINE` runs in `REQUIRES_NEW`, not named `RETRY_SAFE_TX`. Reached from `FleetDispatchService.proofOfDelivery` when goods are returned (`returned > 0`) and from the rejection bring-back, the first time per organization. | Not on the full-handover pickup path (§5.3). On POD routes it commits on its own and uses a second pooled connection. Its `beforeCommit` stamp writes commit evidence, so a retry after a failed POD gets `OPERATION_PENDING`. Under the §6 fence, its stamp would block on the outer lock (self-deadlock). | Create the quarantine location when the setting is saved, and in the C1 backfill. Name the transaction `RETRY_SAFE_TX`. §6 handles nested transactions. |
| TA-7 | `FleetDispatchService.creditGate` (`trip_dispatch`, called from `dispatchAt`) | `CreditService.check` and `CreditService.override` are `@Transactional` (REQUIRED) and write `CreditDecision` rows in the caller's transaction. No independent transaction. | Joins T1. But a fully paid cash pickup is refused for a blocked customer, and for a customer on hold unless the cashier holds `credit:override`. | The cash type bypasses the trip-dispatch gate and records a `CreditDecision` "prepaid" in T1 (OD-6). |
| TA-8 | `InvoiceService.newTx` (`RETRY_SAFE_TX`) | only used for the `invoice_cancel` approval | not on confirm | — |
| TA-9 | `PaymentService.newTx` (`REQUIRES_NEW`) | declared but never used (verified) | none | remove when the file is next touched |
| TA-10 | `CreditNoteService.newTx` (`RETRY_SAFE_TX`) | credit-note approval request in its own transaction | reversal T2: a required approval commits its request even if T2 rolls back (retry-safe by design) | reversal handles "approval pending" explicitly (§7.4) |
| TA-11 | `SalesFulfilmentService.perOrder`, `SalesAtpService.perOrder` | `TransactionTemplate` with default `REQUIRED` (used by jobs) | joins when called inside T1 | — |
| TA-12 | `InvoiceService.createCopied` → `DocumentBuilderService.createDocument`, `InvoicingWorkflowsInitializer` (post), `StockService` / `StockPostingService`, `FleetDispatchService.dispatchAt` / `proofOfDelivery`, `SalesBillingService`, `PostingService` | no `REQUIRES_NEW`, template or async call (verified per class; `DocumentBuilderService` re-checked with `grep -a`, because a NUL byte in that file makes plain `grep` skip it) | join T1 | proven by TP-1 |
| TA-13 | `IdempotencyService`, `BusinessOutcome.record`, `IdempotencyTransactionListener` | Claim and completion run outside the business transaction, by design. The record is locked only when the outcome or the `beforeCommit` stamp is written, at the end of T1. Takeover after 2 minutes is delete-and-reinsert. Stamp and completion match on (organization, key) only. | **D-IDEM-1** (§6) | lease + fencing |
| TA-14 | `DetachedWrites` | virtual-thread executor for writes after the transaction completes (users: EventBus, AuditService, Savepoints, POS) | any business write routed through it is not atomic | allowlist: audit and event outbox only; TP-3 enforces it |
| TA-15 | Not on the T1 path (checked) | `LineageExceptionService` (only its controller); `SalesCollectionsService.afterCommitTx` (dunning delivery status); `SalesQuotationService` / `SalesQuoteLinkService` templates (quotations); the approval / isolation templates of `InventoryCountService`, `SupplierPaymentControls`, `VendorBillService`, `DocumentReversalService`, `DeferralService`, `BankMatchingService` | — | TP-3 catches a later change |
| TA-16 | Second DataSource or transaction manager | none found; `CronWorkers` uses the single DataSource only for the ShedLock lock provider | — | TP-3 |
| TA-17 | `@Async`, `@ApplicationModuleListener`, `@TransactionalEventListener` in sales, fleet, invoicing, inventory, accounting, credit | none found. Reactions are `EventBus.subscribe` handlers fed from the outbox, e.g. `SalesBillingSubscriber` on `invoice.posted` / `invoice.cancelled`, `SalesFulfilmentSubscriber`, `CustomerDepositSubscriber`. | covered by TA-3 | — |

**TP tests**:
- All TP tests run through the real HTTP route with production beans (`ApiTestSupport`); nothing is mocked.
- **TP-1, one transaction**: a test-installed trigger records `txid_current()` for every insert or update on the
  business tables:
  - `SalesOrder*`, `SalesOrderPayment`, `SalesOrderEvent`;
  - fleet rows and stock ledger tables;
  - `JournalEntry` / lines, `Document` / lines, `SalesInvoiceLink`, `GaplessCounter`;
  - `Payment` / allocations, `FiscalDocument`, `BusinessOutcome`, `CreditDecision`.

  A real confirm must show **one** txid. Allowlisted outside it: `AuditLog` and `EventOutbox` (after commit), and
  `IdempotencyRecord` (claim and completion).
- **TP-2, rollback**: the FI tests (§12), using the same triggers.
- **TP-3, no independent transaction**: a test transaction-execution listener counts the physical transactions begun
  on the request thread during confirm. Only T1 is allowed.
- **TP-4, event loss tolerated**: the detached outbox write fails after commit. The sale is still complete, because its
  state was computed in T1, and reconciliation shows no drift.
- **TP-5, customer on credit hold or blocked, cash pickup**: the confirm succeeds without `credit:override` and records
  a `CreditDecision` "prepaid" inside T1 (if OD-6 = yes).
- **TP-6, POD with returned goods, organization without a quarantine location** (after the TA-6 fix): no transaction
  other than the request's own commits, and a failed POD can be retried with the same key.

### 5.5 Fiscal outbox and recovery
- **States**: `pending → sent → succeeded | failed | unknown`; `unknown → (lookup) → succeeded | pending (resend)`.
- **Adapter contract** (new requirement): `issue(requestRef, payload)`, idempotent by `requestRef` at the provider
  when supported, and `lookup(requestRef)`. Only the `none` and `mock` adapters exist today.
- **Provider accepted but the app timed out**:
  1. The row becomes `unknown` and is never re-sent blindly.
  2. The job calls `lookup(requestRef)`.
  3. Found → `succeeded`, with the fiscal number and response stored.
  4. Not found after the provider's settlement time → re-send with the **same** `requestRef`, never a new reference.
  5. Provider without lookup and without dedupe → no automatic re-send. A manual reconciliation queue for the owner
     or accountant.
- **While `unknown`**: reversal and identity amendments are blocked (existing gate); printed invoices show "fiscal
  pending"; an alert is raised after N minutes.
- **Tests**: the mock adapter gains timeout, duplicate and lookup behaviour (FR-1…FR-5).

---

## 6. Idempotency lease and fencing (kernel, every money route)

**Today** (`IdempotencyService`, `BusinessOutcome`, `IdempotencyTransactionListener`):
- The request hash is checked (409 on a different body).
- On outcome or evidence routes, a pending record with no outcome is **deleted and re-run** once it is older than 2
  minutes (`:169-180`). The age is `ABANDONED_AFTER_MS` (`:76`), counted from the claim.
- The outcome and the commit-evidence stamp are written at the end of T1 (`beforeCommit`). For most of T1 the record
  row is unlocked and shows no outcome.
- The stamp and the completion update `WHERE "organizationId" = ? AND key = ?`, with no attempt check
  (`BusinessOutcome.record`, `IdempotencyService:252` and `:285`).

**D-IDEM-1 (pre-existing)**: if a money request runs longer than 2 minutes, a retry with the same key deletes the
record and runs the handler again while the first run is still going.
- With a business guard (row lock + state check), the second run fails.
- Without one (e.g. a new payment), the route can post twice.
- The first worker's stamp and completion can also land on the second attempt's record.

The probability is low, since it needs a 2-minute request, but the risk is real. C1a fixes it for every money route.

**Parity**: the 2-minute rule comes from the NestJS reference and is written into `CONTRACT.md` §6 ("Money route
pending > 2 min with empty outcome = abandoned → delete and re-run"). So the fix is a **deliberate deviation**: it
needs owner approval (OD-14) and an update of `CONTRACT.md` §6 in C1a.
- What a client sees changes only while the first execution is still running: it gets 409 `OPERATION_PENDING` instead
  of a second execution.
- A truly abandoned operation is still re-run after the lease expires, as today.
- Response bodies, status codes, messages and the request hash (`sha256(method + path + rawBody)`) are unchanged.

**Design** (as built in C1a, migration V730; this replaces the revision 3 table):

| Element | Design |
|---|---|
| New columns | `attempt` (fencing token, monotonic), `ownerToken` (UUID per attempt), `leaseExpiresAt`, `committedAttempt` (the attempt whose outermost transaction committed) |
| New table | `IdempotencyEvidence` (organizationId, key, attempt, createdAt): commit evidence from nested transactions. No foreign key: the key-share lock it would take waits for the outer transaction, the deadlock this table avoids. |
| Claim (autocommit) | `INSERT … status = pending, attempt = 1, ownerToken = t, leaseExpiresAt = now() + L`; the unique (organization, key) index is the lock, as before |
| Existing record | hash differs → 409 (unchanged); completed → replay (unchanged) |
| Pending record | lease running → 409 `OPERATION_PENDING`. Lease ended: a money route (OUTCOME or EVIDENCE route) whose attempt left no commit evidence is taken over; a POS sale route is taken over with its recovery evidence (it resumes from it, as before); anything else → 409. |
| Takeover (compare-and-set, never delete) | one statement: `UPDATE … SET attempt = attempt + 1, ownerToken = t2, leaseExpiresAt = now() + L WHERE id = (SELECT id … FOR UPDATE NOWAIT) AND attempt = :seen AND status = 'pending' AND leaseExpiresAt < now()` and no evidence. One row → new owner; zero rows → read again once; lock not available (the owner is committing) → 409. |
| **Fence (every outermost write transaction, just before it commits)** | `UPDATE "IdempotencyRecord" SET "committedAttempt" = :mine WHERE org = ? AND key = ? AND attempt = :mine AND "ownerToken" = :t`. Zero rows → the key was taken over → 409, and the transaction rolls back. The row lock it takes is held through the commit, so no takeover can slip in between. The stamp is also the outermost transaction's commit evidence. Hooked into `IdempotencyTransactionListener.beforeCommit`. |
| Nested write transactions (`REQUIRES_NEW` inside another transaction of the same attempt) | They never lock or update the record: the outer transaction may hold that lock and is waiting for them, a deadlock PostgreSQL cannot see. Just before commit they check attempt and token with a plain read and insert an `IdempotencyEvidence` row. `RETRY_SAFE_TX` transactions are exempt, as before. |
| Evidence | a saved outcome or `committed` stamp in `responseJson`, `committedAttempt`, or an evidence row |
| Complete, `safeToRetry` cache, release, sync `releaseStale` | Only the record of the operation's own attempt (`attempt` and `ownerToken` in the `WHERE`). Release and `releaseStale` never delete a record with evidence; `releaseStale` skips a locked record. |
| Superseded attempt | Its fence fails: it answers 409 `OPERATION_PENDING` ("A retry with the same Idempotency-Key took this operation over; nothing of this attempt was saved. Retry with the same key."), or the winner's stored response if the winner has completed. |
| External side effects | none before or inside T1. Network calls go only through outboxes, after commit (fiscal, notifications). |
| Defence in depth | business guards stay: sale row lock + state precondition; unique constraints (one active sale invoice link per mode, tender reference uniqueness); the gap-free counter |
| Lease length `L` | `erp.idempotency.lease-seconds`, default 120 s (the old abandonment age) |

**Why the fence runs at commit, not as the first statement** (changed from revision 3):
- A lock taken at the first statement is held for the whole transaction. A nested `REQUIRES_NEW` transaction that
  writes the record (an outcome save, for example) would then wait for its own outer transaction: a deadlock
  PostgreSQL cannot detect, and `lock_timeout` is 0 on live.
- Spring's `afterBegin` hook cannot fail a transaction cleanly: the transaction is already bound to the thread.
- A fence at commit gives the same guarantee, at most one attempt commits, with the lock held only for the commit
  itself. The trade-off: a stalled attempt does not block a retry once its lease has ended. The retry takes over and
  runs, and the stalled attempt fails at its fence and rolls back. Either way exactly one execution commits.

**Pending record, decided from the record** (never from age alone):

| Observation | Answer |
|---|---|
| Completed | replay |
| Pending, lease running | 409 `OPERATION_PENDING` (the owner may still be running) |
| Pending, lease ended, commit evidence | 409 `OPERATION_PENDING` with the recovery evidence: never a second execution |
| Pending, lease ended, no evidence | takeover (attempt + 1), run once; the old attempt, if still alive, is fenced out |
| The owner is committing (record locked) | 409 `OPERATION_PENDING` |

**Lost connection**:
- **Worker dies or its connection drops inside T1**: PostgreSQL aborts the transaction; nothing is stamped. Retries get
  `OPERATION_PENDING` until the lease ends, then one takes over and runs once.
- **Connection lost during COMMIT**: if the commit happened, `committedAttempt` is set and there is no takeover; if it
  did not, a retry takes over after the lease.
- **Timeouts**: the fence decides correctness, so database timeouts are no longer needed for it. They would only free
  the business-row locks of a hung worker sooner. Live has them off (C0: `idle_in_transaction_session_timeout = 0`,
  keepalives at the OS default of 7,200 s, no role settings); setting them per role stays an ops decision for the next
  deploy, and C1a does not change them.

**Tests** (`IdempotencyLeaseIT`, all through the production transaction manager and listener):
- **IL-1**: T1 stalled beyond the lease. A retry inside the lease → 409 `OPERATION_PENDING`, no execution. After the
  lease, the retry takes over (attempt 2) and runs once; the stalled original then fails at its fence, rolls back and
  answers with the winner's result. One effect.
- **IL-2**: worker stopped before T1 → after the lease the retry takes over and runs once; the stale worker then runs
  T1 → fenced, rolled back, no effect. **IL-2b**: the same while the retry is still running → the stale worker gets
  the 409 "took this operation over".
- **IL-3**: crash after commit, before completion → the retry gets `OPERATION_PENDING`, also after the lease; no second
  effect (before C1a the record was deleted and the operation ran again).
- **IL-4**: simultaneous first requests → one owner; the other gets 409.
- **IL-5**: same key with a different body → 409 (unchanged).
- **IL-6**: two takeovers racing → exactly one compare-and-set wins.
- **IL-7**: a failed attempt whose work rolled back releases the key → an immediate retry runs once (HTTP).
- **IL-8**: existing idempotency, sync, POS and sales money-route ITs unchanged.
- **IL-9**: a nested `REQUIRES_NEW` write inside a fenced attempt whose outer transaction holds the record lock → no
  self-deadlock; evidence row inserted; outer rollback → retry gets `OPERATION_PENDING`, also over HTTP.
- **IL-10**: connection killed (`pg_terminate_backend`) inside T1 → nothing commits; the retry gets
  `OPERATION_PENDING` until the lease ends, then runs once.
- **TP-6**: a retry-safe find-or-create inside a failed keyed request (TA-6) leaves no evidence; the retry runs.

---

## 7. Returns, credits and refunds

### 7.1 Three records, always separate
1. **Stock return**: `SalesReturn` / `SalesReturnLine` (RMA) → stock ledger (P13 / P13a / P13b).
2. **Credit note**: `Document` `credit_note` linked to the sale invoice and its sale lines (P12).
3. **Settlement**, one of:
   - **refund**: outbound payment over a chosen tender;
   - **customer credit**: kept on the customer's account (§4.4);
   - **replacement**: a new Sale at price 0 (existing).

| Record | Can exist without the others? | Example |
|---|---|---|
| Stock return only | yes | replacement sale; goods returned for exchange |
| Credit note only | yes, with approval | price adjustment (value credit); goods not returned (written off, AD-20) |
| Refund | only against a credit balance (credit note or overpayment) | — |

### 7.2 Return linked to the original stock issue; serial and lot validation

**Today**:
- `SalesReturnService.receive` already returns goods "on the lot, expiry and serial the delivery issued them from, at
  that cost" (R1). It calls `StockReversalService.returnFromSource` with the ledger codes of **all** the order line's
  load lines (`issueLedgerCodes`: `FleetLoadLine` → `FleetDeliveryOrderLine.sourceLineId`).
- It passes no `issueLineId` and no `serialNumbers`, so for serial-tracked products the system picks which issued
  serials come back.
- Goods without a recorded issue (legacy) are restocked without lot identity.
- `SalesReturnLine` has no delivery reference.
- POD returns already validate serials: "Serial number … was not dispatched on this line"
  (`FleetDispatchService.returnedSerials`).

**Design**:

| Rule | Detail |
|---|---|
| Delivery reference | Each return line references the delivery it reverses: `loadId` + `loadLineId` (new columns), or the migrated cash-sale issue (`InventoryLedger.referenceType = 'cash_sale'`, `referenceId` = sale id, and `sourceLineId` = sale line id or, for rows written before 2026-11-15, the line's `issueLedgerCode`; ids preserved by the migration) |
| Issue rows | The return posts against **that** load line's issue rows only (`IssueSource.ledgerCodes` of the load line), so cost, lot and expiry come from the original issue |
| Returnable | per load line = delivered (pickup: handed over) − already returned on that load line; `receivedQty` ≤ returnable |
| Serial-tracked | Serials must be named. Each must have been issued on that load line (`InventorySerial` + the issue rows) to this customer, and not already returned; otherwise refused ("Serial number … was not delivered on this delivery" / "… was already returned"). Passed to `returnFromSource` as `serialNumbers` with the `issueLineId`. |
| Lot-tracked | Returned qty per lot ≤ issued qty per lot on that load line − already returned. Lot and expiry are restored from the issue rows; no free-text lot. |
| Receive | Mandatory receiving location (explicit; profile or yard location; quarantine allowed), receivedBy, receivedAt |
| Inspection | Condition per line (`usable`, `damaged`, `defective`, `missing`) and disposition: restock (P13), quarantine (P13a), scrap (P13b). Photos optional. |
| Credit | credit qty ≤ received qty (quantity credit, R). Credit without return (W) needs its own permission, a reason and an approval (AD-20). |
| Migrated cash sales | Resolve issue rows by `referenceType = 'cash_sale'` and `referenceId`, then `sourceLineId`, or `issueLedgerCode` where `sourceLineId` is empty (122 of 137 rows on live, §10.2), so returns keep lot and serial identity. The legacy fallback stays only for goods with no recorded issue. |

### 7.3 Credit-note kinds and `qtyCredited`
- Every credit note linked to a sale invoice carries `creditKind`:
  - `quantity`: reduces C, with a return (R) or a write-off (W);
  - `value`: amount only; never changes C.
- New `POST /sales/invoices/{id}/credit-note` writes `credit_note` links per sale line.
- A subscriber on `creditnote.issued`, plus a synchronous recompute when the credit is posted from a sales command,
  updates `qtyCredited` and the outcome amounts (§4.4) for every credit note linked to a sale invoice. Today only
  returns update it.
- **Classification of the creators** (confirmed in C0):
  - return close → quantity, with a return;
  - `SalesCreditFlowService` → by type (return types quantity, price types value);
  - `SalesRebateService` → value;
  - `FleetDiscrepancyService` (no product lines) → value, referencing the delivery shortage;
  - late dispute → by dispute type;
  - `POST /credit-notes` → value, unless its lines reference sale lines.
- **Credit note on a down-payment invoice**: reduces its deposit (J4-A / J4-D, AD-15).

**As built (C2, V733; AD-9 and AD-20 approved 2026-11-28)**:
- §7.2 delivery reference:
  - `SalesReturnLine` gains `loadLineId`, `serialNumbers` (text[]) and `condition`.
  - On create, each line comes back from the delivery it names, which must be a delivered load line of that order
    line ("X was not delivered on that delivery").
  - Without a name, it comes back from the only delivery with something left. With several: "Choose the delivery X
    comes back from".
  - What can come back from one load line = accepted on it − what other returns hold. Once a return is received,
    it holds received − rejected; before that, the quantity asked for. The order-line limit uses the same rule, so
    goods asked for but never received can come back on another return.
  - Goods delivered without a recorded load keep the earlier behaviour: no load line, and all the line's issues.
- `GET /sales/returns/returnable?orderId=` (`sales:return:read`) answers, per order line, the quantity that can come
  back and, per delivery, {loadLineId, loadCode, deliveredAt, delivered, returnable, serialNumbers}.
- Receipt:
  - The goods come back against that load line's issue rows only, so they take the cost, lot and expiry of that
    delivery (`issueUnitCost` from the load line).
  - Lines accept `receivedQty` and `serialNumbers`.
  - A serial-tracked line names one serial per base unit. Each serial must be on that load line ("Serial number S was
    not delivered on this delivery") and not on another received return of the line ("… was already returned").
  - The serials go to the stock engine, which checks them against its issue rows as well.
- Lots (deviation): there is no lot entry on a return. The lot and expiry come from the load line's issue rows, so a
  return cannot name a lot that delivery did not issue.
- Inspection:
  - The condition is `usable`, `damaged` or `defective` (deviation: no `missing`; goods that never came back are a
    claim without goods, AD-20).
  - It defaults from the disposition: restock → usable, scrap → defective, quarantine or damaged → damaged.
  - Restocking damaged or defective goods is refused (AD-9).
- Close:
  - The credit quantity = received − rejected, up to what is invoiced net. The uninvoiced rest needs no credit: it
    leaves B (§4.5).
  - A refund goes out over the tender of the invoice's latest receipt, unless the closer chooses one (AD-9).
- AD-20 claims (`physicalReturn = false`):
  - They need the new permission `sales:return:write_off`; V733 grants it to every role holding
    `sales:return:approve`.
  - Someone other than the requester approves ("You cannot approve your own claim"). The exception is
    `allowSelfApproval` with an approver holding `sales:configure`.
  - W is set when the claim closes. No stock moves, and the credit posts like a return credit (sales returns account).
- §7.3 credit kinds:
  - `Document.data.creditKind` is `quantity` or `value`.
  - Deviation: there is no new `POST /sales/invoices/{id}/credit-note`. Quantity credits go through returns (goods
    back) and claims (AD-20). Value credits go through the existing `POST /sales/credit-notes` (price_adjustment,
    goodwill, billing_error) and write-offs.
  - The credit flow now refuses type `return` against a sale invoice: "Goods returned on a sales order are credited
    through a sales return, so the stock and the order quantities follow".
  - A late-dispute credit is a quantity credit.
- §7.4, the cash Reverse command, is built in C3 with the cash sale.

### 7.4 Cash "Reverse" command (two-phase)
1. The wizard pre-fills an RMA with every delivered line and its load reference. The cashier still confirms received
   qty, serials/lots, location and condition per line; verification is never skipped.
2. **Phase 1, fiscal cancel**: requested and committed in its own transaction (TA-5). T2 starts only when the cancel
   has succeeded. While it is `unknown`, the sale shows "fiscal cancel pending".
3. **Phase 2, T2**, one transaction: return receipt → credit note → settlement (refund or credit) → sale `cancelled`
   (reversed), outcome amounts retained.
   - If a credit-note approval is required, T2 stops with status "approval pending" (TA-10).
   - If T2 fails after a successful fiscal cancel, the sale stays "fiscal cancelled, reversal pending". The cashier
     retries T2 with the same key. If the reversal is abandoned, a fiscal re-issue is required (accountant).
4. Partial returns use the same wizard with edited quantities, through the normal return flow (the sale stays `done`).
5. If nothing was dispatched (once AD-1 allows delivery later): cancel the delivery order + credit note + settlement;
   no stock return.

---

## 8. Invoice source ownership

### 8.1 Matrix: every creator of a customer invoice or credit note (`origin/main`)

| # | Creator (class) | Route / trigger | Document | sourceType today → after | Owner | Nest parity | Policy |
|---|---|---|---|---|---|---|---|
| 1 | `SalesBillingService.createInvoice` regular | `POST /sales/orders/{id}/invoices` | sales_invoice | `sales_order` | sales | no | sale-owned, allowed |
| 2 | same, down payment | same, mode `down_payment` | sales_invoice | `sales_order` + `downPaymentFor` | sales | no | allowed |
| 3 | `SalesBillingService.batch` | `POST /sales/invoices/batch` | sales_invoice | `sales_order` | sales | no | allowed |
| 4 | `SalesMilestoneService.invoice` | `POST /sales/orders/{id}/milestones/{mid}/invoice` | sales_invoice (down payment) | `sales_order` | sales | no | allowed |
| 5 | `CashSaleService` → `InvoiceService.createForCashSale` | `/sales/cash-sales/{id}/generate-invoice`, `/approve-and-invoice` | sales_invoice | `cash_sale` | sales | no | **retired at cutover** (drain, §10); replaced by cash confirm (#1 path) |
| 6 | `InvoiceController` → `InvoiceService.create` | `POST /invoices` | sales_invoice | NULL (kind `manual`) | invoicing | **yes** | allowed while the org restriction is off (§8.3) |
| 7 | `ContractService.invoice` (projects) | contract billing | sales_invoice | NULL → kind `project_contract` | projects | no | module-owned, allowed |
| 8 | `SalesCollectionsService` (dunning fee) | collections run | sales_invoice (account 4290) | NULL → kind `collections_fee` | sales | no | module-owned, allowed |
| 9 | `OpeningBalanceService` | opening balance import | sales_invoice | `opening_balance` | invoicing | no | allowed |
| 10 | `FleetDeliveryInvoiceService` goods + transport (non-Sales delivery orders) | `POST /fleet/delivery-orders/{id}/invoice` | sales_invoice (+ `stockIssuedBy`) | NULL → kind `fleet_delivery` | fleet | no | **preserved**, module-owned |
| 11 | `FleetDeliveryInvoiceService` transport only (Sales-owned delivery orders) | same | sales_invoice (account 4250) | NULL → kind `fleet_charges` (+ sale reference) | fleet | no | **preserved**; freight onto the sale invoice later is optional (AD-10, OD-4) |
| 12 | kernel `RecurringService.generateOne` (direct insert, tax 0) | `/recurring` templates, scheduled | any type incl. sales_invoice | `recurring` | kernel | **yes** | **preserved**; no template disabled until an approved replacement and template migration exist |
| 13 | `DocumentEngineService.createCounterpart` | documents engine reverse / reissue | counterpart | relation `REVERSES` / `REISSUES` → kind `counterpart` | documents | **yes** | allowed (derived document) |
| 14 | `CreditNoteService.create` via controller | `POST /credit-notes` | credit_note | `reversedDocumentId` optional | invoicing | **yes** | allowed; with the restriction on, a customer credit note needs `reversedDocumentId` or a module source |
| 15 | `SalesReturnService.close` | `/sales/returns/{id}/close` | credit_note (posted) | linked to invoice | sales | no | allowed; quantity kind; writes `credit_note` links |
| 16 | `SalesBillingService.lateDisputeCredit` | dispute after invoicing | credit_note (draft) | linked to invoice | sales | no | allowed; **adds** `credit_note` links |
| 17 | `SalesCreditFlowService.post` | sales credit flow (return / price types) | credit_note (posted) | invoice optional | sales | no | allowed; links when the invoice is a sale invoice |
| 18 | `SalesRebateService` | rebate settlement (P15a) | credit_note | — | sales | no | allowed; value kind |
| 19 | `FleetDiscrepancyService` | fleet discrepancy credit request | credit_note (draft, no product lines) | linked to goods invoice | fleet | no | allowed; value kind |
| 20 | `SalesProformaService.fromOrder` | `POST /sales/orders/{id}/proforma` | proforma_invoice (never posts) | `sales_order_proforma`, sourceId NULL → + id | sales | no | allowed (not an invoice) |
| 21 | `SalesQuotationService.convert` → proforma | `/sales/quotations/{id}/convert` | proforma_invoice | `crm_quotation` | sales | no | allowed (not an invoice) |
| 22 | `SalesQuotationService.convert` → POS `Order` | same, target `order` | POS `Order` → POS `Invoice` | — | sales → orders | no | retire after a usage check (OD-2) |
| 23 | `PosInvoiceService.generateInvoice` | `/pos/checkout`, `/pos/orders/{id}/invoice`, digital menu, sync `sale.checkout` | POS `Invoice` | `pos_invoice` | pos | **yes** | unchanged |
| 24 | `OrdersService.generateInvoice` | `POST /orders/{id}/invoice` | POS `Invoice` | — | orders | **yes** | unchanged |
| 25 | `RentalPostingService` | rental checkout / extension / return / swap | POS `Invoice` | — | rental | **yes** | unchanged |
| 26 | `RepairPostingService` | `POST /repair/orders/{id}/bill` | POS `Invoice` | — | repair | **yes** | unchanged |
| 27 | `PosInvoiceService.refund` / `writeOff`, POS void | `/pos/invoices/{id}/refund`, `/pos/sales/{id}/void`, `/write-off` | POS reversal (no credit note) | — | pos | **yes** | unchanged (reporting only, §9) |

`IntercompanyMirrorService` creates the vendor bill in the target organization from a posted sales invoice. It is not
a customer-invoice creator; the source invoice comes from one of the creators above.

### 8.2 Typed source ownership contract
- **`SourceRef`**: a sealed type in the invoicing application API, one record per kind:
  - `Sale(saleId, lineIds)`, `SaleDownPayment(saleId)`, `SaleMilestone(saleId, milestoneId)`;
  - `ProjectContract(contractId)`, `CollectionsFee(dunningEntryId)`;
  - `FleetDelivery(deliveryOrderId)`, `FleetCharges(deliveryOrderId, saleId)`;
  - `Recurring(templateId)`, `OpeningBalance(batchId)`, `Counterpart(documentId, relation)`;
  - `CreditFor(invoiceId, creditKind)`, `Manual()`.
- **Each kind declares**: owner module, allowed document types, required links, and whether the manual-invoice
  restriction applies (only `Manual` and a standalone `CreditFor`).
- **One entry point**: the existing `DocumentBuilderService.createDocument`, extended to require a `SourceRef` for
  customer document types. `Document` rows are inserted in only three places (C0, searched with `grep -a`): the
  builder, `RecurringService.generateOne` (#12) and `DocumentEngineService.createCounterpart` (#13). Every other
  creator already goes through the builder. The two direct inserts move onto it. POS creators #23–#27 write the POS
  `Invoice` table and are out of scope.
- **Parity-safe storage**:
  - a new column `Document.sourceKind` holds the kind;
  - `sourceType` keeps today's values (NULL for manual invoices), so no parity response changes;
  - controllers return records, so the new column is not serialized unless mapped.
- **Enforcement**:
  1. **Code-scan test** (unit, no Spring context): fails if a class other than the builder inserts a `Document` row.
     It reads every file as text, because one source file contains a NUL byte and plain `grep` skips it. As a
     self-check, it must find the builder's own insert.
  2. **Backfill, then database guard**: first `sourceKind` is set on every existing customer document. It comes from
     today's `sourceType` and module references: `FleetDeliveryOrder.goodsInvoiceId` / `chargesInvoiceId`,
     `FleetDeliveryCharge.invoiceId`, `DunningEntry.feeInvoiceId`, `RecurringDocumentRun.documentId`,
     `DocumentRelation`, and project billing. Rows with no evidence become `Manual` and are listed. This is metadata
     only: amounts, numbers and ledgers are untouched. Then
     `CHECK ("documentType" NOT IN ('sales_invoice', 'credit_note') OR "sourceKind" IS NOT NULL)` is added and
     validated. A `NOT VALID` constraint alone is not enough: PostgreSQL checks it on every UPDATE, so a payment on
     an old invoice would fail. On live, all 11 customer documents without `sourceType` are manual.
  3. **ITs**: one per creator, asserting its `sourceKind`.

### 8.3 Manual customer invoices (policy)
- **Org setting** `invoicing.manualCustomerInvoices`: `allowed` (default for every organization at release) |
  `blocked`. Only creators #6 and #14 (standalone) depend on it.
- **Switching an organization to `blocked`** is an owner action (OD-3), after:
  1. this matrix is approved;
  2. a read-only report of that organization's last 12 months of manual invoices by purpose (goods, services,
     intercompany, other) has been reviewed;
  3. each purpose has a working replacement (a Sale with service lines, or an intercompany Sale);
  4. users have been told.
- Recurring sales invoices stay on #12 until a separate approved replacement exists.
- **Parity**: Nest scenarios (`invoicing-ar-ap.json`) and the roughly 40 ITs using `POST /invoices` keep working,
  because the default is `allowed`. `ManualInvoicePolicyIT` covers `blocked`.

---

## 9. Reporting

### 9.1 Measures

| Measure | Definition | Source of truth | Date basis | POS | Reconciles to |
|---|---|---|---|---|---|
| **M1 Commercial sales (bookings)** | Confirmed sale value, net of line and order discounts, excl. tax; minus cancellations, close-short and amendment deltas. Returns don't reduce bookings. | `SalesOrder` / lines, `SalesOrderRevision` | confirm / cancel / approval date | checkout value; void of an **unsettled** order = cancellation | bridge M1 → M3 (open backlog, invoice-before-delivery, advances) |
| **M2 Fulfilment** | Delivered qty and value (at cost and at sale price), net of bring-backs and returns; accepted qty; backlog = ordered − cancelled − delivered | fleet loads, returns, stock ledger | dispatch / POD / return date | stock issue rows `pos_invoice` / `pos_order` | COGS GL from sales, delivery and POS issues (R5e) |
| **M3 Billing** | Posted sales invoice lines (net, tax and discount shown apart) minus posted credit notes. Down-payment invoices shown as advances, not sales; advance deductions net out on final invoices. | `Document` / lines + POS `Invoice` / `InvoiceItem` | invoice / credit note date | POS invoices minus POS refunds and **settled** voids (refund date); write-offs excluded (bad debt) | revenue + VAT GL by source (R5a–c) |
| **M4 Cash collection** | Inbound receipts per tender minus refund payouts. Deposits included as advances; deposit applications and deductions excluded (non-cash). | `Payment` / allocations, `CustomerDeposit` | receipt date | POS tenders minus payouts; reconciles to cash sessions | tender GL debits for sales sources (R5d) |
| **M5 Recognized revenue** | GL lines on revenue accounts from sales sources: invoices, credit notes, POS invoices and refunds, deferral releases, accruals | journal lines | posting date | `pos_invoice` journals and refund reversals | P&L revenue, exactly |

**Channel** on every measure:
- `order`, `cash`: from the Sale;
- `pos`: from POS tables;
- `project`, `fee`, `fleet`, `recurring`, `intercompany`, `counterpart`: from `sourceKind`;
- `manual`, or `direct` for legacy NULL rows.

### 9.2 Rules and reconciliation
- **Discounts**: gross = qty × unit price before line discount; the order discount is allocated to lines. Gross −
  discount = net, and net reconciles to revenue.
- **Tax**: always reported apart from net. M3 tax − credit-note tax = output VAT GL movement for the same sources.
- **FX**: functional currency for reconciliation; transaction currency shown. FX gains and losses are never revenue.
- **POS refunds and voids**: a refund, or a void of a **settled** sale, is a negative M3 and M5 line (and a negative
  M4 payout) on the refund date. Stock disposition goes to M2. A void before settlement is excluded from M3.

**Checks** (tolerance 0 for quantities; 0.01 functional per document for FX rounding):

| Check | Rule |
|---|---|
| R5a | M3 (`Document`) = GL revenue from `sales_invoice` / `credit_note` sources |
| R5b | M3 (POS) = GL revenue from `pos_invoice` journals − refund reversals |
| R5c | M3 tax = output VAT GL movement by source |
| R5d | M4 = tender debits − refund credits for sales sources |
| R5e | M2 cost = COGS GL from sales, delivery and POS issues |
| R5f | M5 = M3 + deferral releases − deferrals + accruals (if AD-1 / AD-2 / AD-3 add them) |
| R5g | Σ output VAT per sale = V1 (no VAT counted twice) |

**Views**:
- `reporting.sales_orders` (M1, M2 per sale line);
- `reporting.sales_register` (sale lines ∪ POS order lines);
- `bi.sales_orders`, plus `channel` added to `bi.sales_lines`.

`SalesReportFacts` gains the POS union (missing today) and the channel. `BranchCashSaleReports` is rebuilt on
`saleType = 'cash'`.

### 9.3 Equivalent baselines before and after adding POS
1. **Freeze baselines before the switch**, on the rehearsal copy and on live at freeze, for the last 13 months and
   the current period:
   - report-center totals by the old channel (`branch_cash`, `sales_order`, `pos` (empty today), `direct`);
   - `SalesAnalysisService` / `SalesKpiService` totals (already `Document` + POS);
   - BI view totals;
   - GL revenue and VAT by source.
2. **Bridge after the switch**, per period:
   - new M3 total = old report-center total + POS invoices − POS refunds and settled voids;
   - re-labels (`direct` → `fleet`, `project`, `fee`, `manual`) net to 0;
   - KPI dashboard totals unchanged;
   - M5 = GL revenue.
3. **Tolerance**: any difference above 0.01 per period goes into a variance report signed by the owner.
   - RP-1: fixture with every channel;
   - RP-2: rehearsal copy;
   - RP-3: live, read-only, after go-live.

---

## 10. Historical migration

### 10.1 Phases
1. **Preflight** (read-only SQL + report, on a restored copy, never on live). Produces the exceptions report and the
   proposed line mapping as a reviewable file.
2. **Drain** (on live, with the **old** code, inside the freeze of §11). Every confirmed-but-not-invoiced submission is
   invoiced or voided through its normal business action. Pending fiscal operations and pending outbox or
   notification events for `sales.cash_sale.*` are completed.
3. **Approval**: zero exceptions, or each one has an owner-approved resolution in the release file.
4. **Migration**: one transactional Flyway SQL migration (PostgreSQL rolls back DDL and DML together). It re-runs every
   preflight check as a guard (`RAISE EXCEPTION` listing offenders), copies, relinks, then runs post-checks that raise
   on any mismatch.
5. **Verification**: R6, consumer proofs and snapshot equality (§11.8).

### 10.2 Deterministic mapping

| Relationship | Mapping key | Evidence |
|---|---|---|
| Sale ← submission | same id (UUID) | collision check C-1 |
| Sale line ← submission line | same id | collision check C-2 |
| Stock ledger row → sale line | `InventoryLedger.referenceType = 'cash_sale'`, `referenceId` = submission id, and `sourceLineId` = submission line id (written since 3d493469, 2026-11-15). Older rows have no `sourceLineId` (122 of 137 on live). For them, `SalesSubmissionLine.issueLedgerCode` = `InventoryLedger.ledgerCode` within the same organization and sale. | exact (L-2); ids preserved |
| Invoice → sale | `Document.sourceId` = submission id **and** `SalesSubmission.currentInvoiceId` = document id **and** the active `SalesSubmissionInvoice` row = document id | three-way agreement or exception |
| Invoice line → sale line | pairing by the post-guard ordering (sale lines by `sortOrder, id` ↔ invoice lines by `lineNumber, id`) **plus** equality of product, variant, tax, quantity, unit price, discount %, unit and unit factor, line subtotal / tax / total, **plus** a one-to-one check | persisted in `SalesCentralLineMap` (submissionLineId, documentLineId, basis, checksum); the migration reads only this table |
| Tender → deposit → application | `SalesSubmissionPayment.customerDepositId` → `CustomerDeposit` → `DepositApplication.documentId` | exact |
| Fiscal document → sale | `FiscalDocument.submissionId` | exact |
| Gate pass, DMS attachment → sale | `GatePass.sourceType` / `sourceId` (+ `GatePassLine.sourceLineId`); `DmsDocumentLink.resType` / `resId` | exact; existence checked |
| Tender provider | Free text today (live: `mtn`, `mtn mobile money`, `airtel`, `airtel money`). Mapped to provider codes (`mtn_momo`, `airtel_money`) by an owner-approved table; unknown values are exceptions. | D-1 runs on the mapped values |
| Fulfilment of legacy pickups | From the stock issue rows, not `deliveryStatus`: live pickups show `deliveryStatus = pending` although the stock was issued at confirm (37 of 39 invoiced pickups). | every tracked line has its issue (L-2) |
| Legacy home-delivery cash sales | Kept as cash Sales with the delivery fields in `legacyData`; fulfilment from `deliveryStatus`. Open ones (live: 1 scheduled, 1 dispatched) are delivered or cancelled during the drain (OD-15). | ST-1 |

### 10.3 Checks run by preflight and repeated as migration guards

| Code | Check | Failure means |
|---|---|---|
| C-1 / C-2 | No id overlap between `SalesSubmission*` and `SalesOrder*`, `SalesOrderLine`, `SalesOrderPayment`, `SalesOrderEvent` | exception (no automatic re-key) |
| C-3 | No (org, number) overlap between CSS numbers and `SalesOrder.number`; `css:` sequence keys don't collide with sales-order keys | exception |
| RI-1 | Every partner, branch, profile, location, product, variant, unit, tax and user referenced exists in the same organization | exception |
| RI-2 | Invoice three-way agreement for every invoiced submission | exception |
| RI-3 | Every `CustomerDeposit.cashSaleId`, `FiscalDocument.submissionId`, `GatePass.sourceId` / `GatePassLine.sourceLineId`, `DmsDocumentLink.resId` and `InventoryLedger.referenceId` / `sourceLineId` resolve | exception |
| ST-1 | Allowed state combinations only; drain complete (no confirmed-not-invoiced; no pending or unknown fiscal operation; no open legacy delivery, OD-15) | blocks deploy |
| M-1 | Submission totals = Σ lines; `paidTotal` = Σ verified tenders; invoice total = submission total; Σ deposit applications = invoice total; `cash_sale` deposits fully applied, refunded or released | exception |
| M-2 | Accrual entries (1215 / 2216) for each invoiced submission net to zero | exception |
| L-1 | Line mapping complete, one-to-one and attribute-equal for every invoiced submission | exception |
| L-2 | Every tracked line of a confirmed submission resolves to its issue rows (by `sourceLineId` or `issueLedgerCode`), with issued base quantity = line quantity × unit factor | exception |
| D-1 | Tender references unique per (org, method, provider, reference) after re-keying | exception |
| V-ID-1…4 | Identical lines (§10.4) | exception or owner-approved list |

### 10.4 Is the post-guard ordering historically reliable? Identical lines
- **History**:
  - The guard (`SalesInvoicePostGuard.cashSaleProblems`) was introduced on 2026-11-24 (c446e1d9, sales readiness R1).
  - Cash sales exist since 2026-11-04 (350005e6).
  - Cash-sale invoices posted before the release containing c446e1d9 were **not** protected: head office could have
    changed their lines before posting.
- **Consequence**: the migration never trusts the ordering alone.
  - Every historical invoice is paired by ordinal **and** must pass full attribute equality and the one-to-one check
    (L-1). A mismatch is an exception, never a silent mapping.
  - C0 counts the invoices posted before the guard, and how many fail L-1, on the restored copy.
- **Identical lines** (same product, variant, tax, quantity, unit price, discount, unit, factor and amounts, within one
  sale):

| Check | Rule |
|---|---|
| V-ID-1 | Aggregate equality per group: Σ qty and Σ amounts of the submission lines = those of the invoice lines |
| V-ID-2 | Stock: each submission line's ledger rows (by `sourceLineId`, or `issueLedgerCode` for older rows) match that line's qty and cost, so the stock mapping is exact even when invoice lines are identical |
| V-ID-3 | Downstream references (credit-note lines, return lines, DMS, gate-pass lines) that point at a **specific** document line or submission line force the pairing. Conflicting forced pairings → exception. |
| V-ID-4 | Unforced identical pairs are mapped by ordinal with `basis = ordinal_identical`, then counted and listed in the report for the owner to approve. Every attribute is equal, so no amount, quantity or tax can differ by pairing. |

### 10.5 Exception handling
- Exceptions are resolved only through:
  - **(a)** a business action with the old code before deploy (invoice, void, correct); or
  - **(b)** an owner-approved resolution entry. Example: migrate a submission as `legacy_unlinked`, without line links,
    with a note and an `invoiceStatus` forced from the posted documents.
- Never automatically, and never by editing ledgers.
- The release file lists each exception, its resolution, the approver and the date.

### 10.6 What the migration changes, and what it never changes
- **Writes**:
  - `SalesOrder` / lines / payments / events (inserts);
  - `SalesInvoiceLink` (inserts);
  - `Document.sourceType` `cash_sale` → `sales_order` (metadata only), plus `sourceKind`;
  - the `FiscalDocument` column rename;
  - `GatePass.sourceType`; DMS resource keys;
  - `CustomerDeposit.salesOrderId` (new column).
- **Never writes**: journal entries or lines, stock ledger, valuation layers, payments, allocations, deposit
  applications, invoice amounts or numbers.
- **Mapping to the Sale**: `SubledgerSources`, `CostExplanationService` and `LedgerDetailService` map source
  `cash_sale` to the `SalesOrder` row (ids preserved). Returns resolve `cash_sale` issue rows (§7.2).
- **Excluded from rebuild**: migrated sales (`origin = legacy_cash_sale`) are skipped by
  `SalesFulfilmentService.rebuild` and the nightly reconciliation, because their stock moved by direct issue, not
  fleet.
- **Legacy tables**: kept for one release behind a write-block trigger; dropped in C8.

---

## 11. Release, rollback and recovery

### 11.1 Milestones and the rollback window

| Phase | What happens | Rollback possible? |
|---|---|---|
| P0 Prepare | rehearsal passed (§11.9); drain plan; communication; command journal deployed but off; WAL archiving with a tested PITR restore on the live host (OD-13) | — |
| P1 **Freeze** (T0) | business writes stopped (§11.2); outboxes and queues drained; freeze watermark recorded (WAL LSN + time) | yes |
| P2 Backup | `pg_dump` (custom format, `erp_backup` role) + uploads snapshot (`STORAGE_LOCAL_DIR`); restore list verified; checksums | yes |
| P3 Deploy + migrate | new jar; Flyway (guards + post-checks) | yes |
| P4 Verify | R1–R6, snapshots, consumer proofs, baselines (§9.3); DEMO-org smoke with fiscal `none` and no mobile money | yes |
| P5 **Go / no-go** | owner decides (accountant consulted on financial checks) | **NO-GO → pre-activation rollback** |
| P6 **Activation (A0)** | freeze lifted; jobs resumed in order (mobile-money poll and fiscal first); command journal on | **no restore from here**; post-activation recovery only |
| P7 Release closure ("fully activated") | after hypercare (default 7 days, OD-10): R1–R6 green every day, no open L2 incident. Command journal may be switched off; C8 eligible one release later. | — |

- **Activation (A0)** = the moment the freeze is lifted and the first user business write is accepted. It is recorded
  in the release file (time + WAL LSN).
- **Rollback window** = T0 to A0, with a maximum length (proposal: 4 hours, in a low-traffic window, OD-11). If GO is
  not given within the window, NO-GO is automatic and the pre-activation rollback runs.
- **Writes after the backup**: the only ones allowed inside the window are the listed DEMO-org smoke rows. The restore
  discards them by design; write counters above the freeze watermark verify this.

### 11.2 Business-write freeze
- **API**: every non-GET route answers 503 `MAINTENANCE` (auth and health exempt); the web shows a banner.
- **Sync push** (Android POS, web field sales): rejected with a retryable status. Devices keep their offline queues and
  push after activation (RB-2 proves it).
- **Public endpoints and callbacks** (portal, quote links, mobile-money callbacks): 503, so providers retry. The poll
  job reconciles after activation or rollback.
- **Scheduled jobs paused** by flag: fiscal outbox, mobile-money poll, recurring generation, dunning, quotation expiry,
  reconcile / backorder, BI refresh.
- **Drained before backup**: event outbox, fiscal outbox (no `pending` / `unknown`), pending idempotency records (no
  live leases), notifications.
- **The freeze stays on through any rollback**, until the restored system has passed verification.

### 11.3 Pre-activation rollback (NO-GO)
1. Keep the freeze on.
2. Stop the new jar.
3. Restore the P2 backup (database + uploads snapshot).
4. Start the old jar.
5. Verify the snapshots equal the pre-freeze snapshots; reconcile mobile money and fiscal (§11.6), which must show
   nothing in the window.
6. Resume jobs, then unfreeze.

Nothing accepted from users is lost, because writes were frozen.

### 11.4 Post-activation recovery and authorization

**After A0, a database restore without a verified replay is prohibited.** Transactions accepted after activation are
never discarded.

| Situation | Action | Who decides | Who executes |
|---|---|---|---|
| A P4 check fails, or the window expires before GO | NO-GO → pre-activation rollback | release lead (named in the release file); automatic on a failed check | release lead |
| All checks pass | GO → activation | owner (accountant consulted on financial checks) | release lead |
| Post-activation, feature-scoped defect | **L1 kill switch**: feature flags (cash confirm, new screens, new reports, invoice-source restriction) turn the feature off while the rest runs | owner or on-call delegate | on-call |
| Post-activation defect with a bounded financial effect | **L2 forward fix**: hotfix + compensating business transactions (reversals, credit notes, each with its fiscal counterpart) + repair scripts after a read-only assessment | owner; accountant approves financial repairs | developer + accountant |
| Integrity broken and not fixable forward within the agreed time | **L3**: PITR + replay (§11.5) + reconciliation (§11.6) | owner **and** accountant, in writing | release lead + DBA |
| Restore without replay after A0 | **prohibited** | — | — |

### 11.5 Command journal and deterministic replay (L3 only)
- **Capture** (hypercare): for each write request: time, org, user, method, route, idempotency key or op id, request
  body, response status, the outcome summary (document numbers, totals, statuses) and the response body hash.
  - Redaction: PINs, passwords and tokens are removed (`SECURITY.md`). Card numbers are never stored; only terminal
    approval codes.
  - The bodies contain customer data, so the journal is encrypted with limited retention (OD-10).
- **Storage**: in the database **and** appended to an encrypted, append-only store outside the database (a separate
  volume or object storage), so it survives a restore.
- **PITR** to the last consistent point before the damage, under freeze. It needs WAL archiving: `OPERATIONS.md` leaves
  WAL archiving to the hosting environment, and its status on live is unverified (OD-13).
- **Replay rules**:
  - single-threaded, in original commit order, under freeze;
  - each command carries its original business date (replay mode); creation timestamps are new, and the original time
    is recorded;
  - external side effects suppressed: no fiscal sends, no mobile-money initiation, no notifications;
  - each replayed command must reproduce the recorded outcome (document numbers, totals, statuses). The first
    divergence stops the replay for manual resolution, because later gap-free numbers would shift;
  - background-job outputs in the window (recurring documents, dunning fees, accruals) are regenerated by running each
    job for the window in catch-up mode after the replay, and compared with the job-run log;
  - Android and web sync pushes are API requests in the journal and replay with their original op ids.

### 11.6 External reconciliation (after any rollback, restore or replay)
- **Mobile money**:
  - list provider collections in the window (provider API or statement) and match them by provider transaction id
    against `MobileMoneyRequest` / `MobileMoneyCollection` / receipts;
  - unmatched provider successes → receipt through a reconciliation queue (owner approval);
  - ERP receipts without a provider confirmation → investigate;
  - callbacks are idempotent by provider transaction id.
- **Fiscal**: provider lookup by `requestRef` / invoice number for every `FiscalDocument` in the window.
  - Issued at the provider but `unknown` or missing in the ERP → `succeeded`, with the number stored.
  - Succeeded in the ERP but missing at the provider → re-send with the same `requestRef`.
  - Fiscalised at the provider, but the invoice no longer exists in the ERP → fiscal cancel or credit (accountant).
- **Unfreeze** only when both reconciliation reports are empty or every item has an approved resolution.

### 11.7 The review's scenario: 15 cash sales after deploy, then a serious defect
1. A0 has passed, so the restore-based rollback is closed. Nothing is restored by default.
2. **L1**: the owner or on-call delegate turns off cash confirm. Orders, POS and payments continue.
3. **Assess** (read-only): R1–R6 and V1–V5 per sale show which of the 15 are wrong and how.
4. **L2 (expected)**: fix forward. Each wrong sale is corrected by compensating transactions (Reverse with credit note
   and, if needed, a re-issue), each with its fiscal cancel or credit, so the provider's records and the ERP stay in
   step. The 15 mobile-money collections stay matched to their receipts.
5. **L3 (last resort, owner + accountant in writing)**, only if the database cannot be repaired forward in time:
   1. PITR to the last consistent point.
   2. Replay the journal: the 15 confirms re-create the same sale numbers, invoice numbers and receipts.
   3. Mobile-money receipts are matched to the existing provider transaction ids; no new collection is initiated.
   4. Fiscal rows are re-created as `unknown` and resolved by `lookup(requestRef)` to the original fiscal numbers,
      never re-issued.
   5. Unfreeze after both reconciliation reports are clean.

### 11.8 Verification: consumer proofs, snapshots and other assets

| Consumer | Proof |
|---|---|
| gatepass `GatePassSources` / `GatePassService` | IT: a migrated cash sale opens, prints and gates out; payment status correct |
| dms `DmsResourceRegistry`, `CashSaleResourceProvider` | IT + snapshot: same attachment count per migrated sale before and after |
| kernel `SearchService` | IT: a CSS number finds the sale |
| communication `DiscussViews` | IT: threads stay attached |
| customization `CustomEntities` / `PrintContextProviders` / `AutomationCatalog` | IT: print context renders; automations on `sales_order` fire for the cash type |
| accounting `SubledgerSources`, `PostingService` | IT: drill-down from a `cash_sale` journal entry reaches the sale; sales classification unchanged |
| invoicing `InvoiceService`, `StatementService`, `InvoicingNotificationRoutes`, `CustomerDepositService` | ITs: invoice detail shows the sale; statements unchanged; deposits read-only and linked |
| inventory `CostExplanationService`, `LedgerDetailService` | IT: the ledger row's source opens the sale |
| sales `SalesHandoverService`, `SalesInvoicePostGuard`, `SalesCreditContributor`, `BranchCashSaleReports` | ITs + report snapshot equal |
| credit exposure | snapshot equal per partner |
| BI views / Superset datasets | view row counts and totals equal (channel alias kept) |
| customer portal | portal invoice list unchanged |

**Snapshot equality**: taken before and after the migration, on the rehearsal copy and again on live. Each must match
exactly:
- trial balance per account / branch / currency;
- AR aging per partner;
- stock quantity and valuation per product / location;
- deposit balances;
- credit exposure;
- the 8 cash reports and the sales report-center totals per period.

| Other asset | Handling |
|---|---|
| Attachments (`STORAGE_LOCAL_DIR`, default `var/uploads`) | No files moved (only database keys change); a file-level snapshot is taken at P2 |
| Event registry / notifications | Drained before backup; old `sales.cash_sale.*` event types stay readable for one release |
| Superset / BI | Views recreated by the migration; column names kept; `channel` added |

### 11.9 Rehearsal proof (production-like copy, isolated stack with its own compose project and ports)

| Test | Scenario | Pass condition |
|---|---|---|
| RB-1 | Full P0–P5 with NO-GO → pre-activation rollback | snapshots equal pre-freeze; old screens work; within the window |
| RB-2 | Freeze effectiveness | write attempts rejected; Android and web offline queues pushed after unfreeze; no lost operations |
| RB-3 | GO → synthetic traffic (cash sales, orders, payments, mock mobile-money callbacks, mock fiscal) → L1 kill switch | feature off; other traffic unaffected |
| RB-4 | L3: PITR to a mid-traffic point + replay from the external journal + reconciliation, including the §11.7 scenario | zero lost and zero duplicated business transactions; same document numbers; R1–R6 = 0; mobile money and fiscal reconciled |
| RB-5 | Backup / restore timing and checksum | within the window |
| RB-6 | Go/no-go checklist dry run | every item has evidence |

**Go / no-go criteria**:
- preflight: zero unresolved exceptions; migration guards passed;
- R1–R6 = 0 and snapshots equal; consumer proofs pass; baseline bridge explained;
- drain empty; backup verified;
- WAL archiving active with a tested PITR restore, or the owner accepts in writing that L3 is unavailable;
- RB-1…RB-6 passed within the last 7 days;
- owner sign-off recorded.

---

## 12. Test catalogue

All integration tests extend `ApiTestSupport` and call the real HTTP routes with production beans. Failure injection
uses test-installed PostgreSQL triggers: no new Spring context and no `@MockitoBean`, per `CLAUDE.md`. The triggers
are dropped after each test.

**Atomicity (cash confirm)**: each injection must give a full rollback:
- sale draft and version unchanged;
- zero rows in fleet, stock, `Document`, journal, `Payment`, `FiscalDocument`, `SalesOrderEvent`;
- `GaplessCounter` unchanged; lease released;
- a retry with the same key runs once.

| Test | Injection point |
|---|---|
| FI-1 | S4 `FleetLoad` insert |
| FI-2 | S5 stock ledger insert (reservations unchanged) |
| FI-3 | S6 `SalesOrderLine` update (derived state) |
| FI-4 | S7 `DocumentLine` insert |
| FI-5 | S8 journal-entry insert at invoice post (no invoice number consumed: gap-free proof) |
| FI-6 | S9 second `PaymentAllocation` (multi-tender; the first receipt rolls back too) |
| FI-7 | S10 `SalesOrder` outcome update |
| FI-8 | S11 `FiscalDocument` insert |
| FI-9 | S12 `SalesOrderEvent` insert |
| TP-1…TP-6 | §5.4 |

**Recovery and concurrency**:

| Test | Scenario | Expected |
|---|---|---|
| RC-1 | Commit succeeds, the response write fails | retry with the same key replays the stored outcome; one invoice, one receipt set |
| RC-3 | Connection dropped inside T1 | as IL-10 |
| RC-5 | Fiscal job crash with a claimed row | ShedLock expiry → re-claimed; lookup first; exactly one send |
| RC-6 | Detached outbox write lost after commit | as TP-4; the notification gap appears in reconciliation |
| FR-1…FR-5 | Fiscal timeout / lookup found / lookup not found → resend same `requestRef` / provider without lookup → manual queue / reversal blocked while `unknown` | §5.5 |
| IL-1…IL-10 | Idempotency | §6 |
| CC-1 | Same key + same body, twice in parallel | one effect; the second replays or gets 409 pending |
| CC-2 | Same key + different body | 409 |
| CC-3 | Different keys, same sale, in parallel | one confirm; the other gets 400 "already confirmed"; one invoice |
| CC-4 | Two sales with shared products in opposite line order, in parallel | both succeed (lock order); no deadlock |
| CC-5 | 20 parallel confirms in one organization | invoice numbers contiguous, no gaps or duplicates; no timeout |
| CC-6 | Confirm vs edit / add tender / delete tender / reverse | serialized by the sale lock; final state valid |
| CC-7 | Negative stock at the branch location | refused, nothing posted |

**Accounting, state and payment**:

| Group | Tests |
|---|---|
| VAT / advances | VAT-J1…J4: every case of §3.3 in both modes, asserting exact journal lines and V1–V5. Plus: D-VAT-1 regression; deduction > remaining refused; credit note on a DP invoice reduces the deposit; zero-total final invoice posts. |
| Deferral (if AD-2 = A) | DF-1 invoice before dispatch defers; DF-2 release per load line, once; DF-3 bring-back reverses; DF-4 credit note on the deferred part; DF-5 close short blocked while deferred > 0 |
| Approval (via API) | AP-1 submit only from `required`; AP-2 edit refused while pending; AP-3 withdraw; AP-4 approve by creator / submitter / last editor refused; AP-5 edit after approval invalidates; AP-6 resubmit unchanged needs a justification; AP-7 rule change adds reasons → `required`; AP-8 cash type never auto-confirms; AP-9 order auto-confirm per setting; AP-10 confirm on a sale needing approval refused with the submit message |
| Payment model | PM-1 underpayment refused; PM-2 unverified refused; PM-3 failed tender excluded; PM-4 cash change recorded, not posted; PM-5 non-cash overpayment refused, or kept as credit with permission; PM-6 multi-tender (cash + MTN + card) posts one receipt each; PM-7 payment void after confirm → `partially_paid`; PM-8 tender reference uniqueness |
| Financial outcome | FO-1…FO-7: the examples in §4.4, including "keep as customer credit" |
| Completion | CS-1 fully credited with return → done, refunded; CS-2 written-off credit → done; CS-3 100% value credit → done, credited; CS-4 zero-value sale → done, no_charge; CS-5 short-closed with an excess advance → not done until refund or retention; CS-6 cancel refused once anything is delivered, invoiced or held |
| Handover | PH-1 confirm without affirmation refused; PH-2 partial handover refused; PH-3 POD evidence stored; PH-4 gate-out mismatch flagged |

**Returns, sources, migration, rollout, reporting**:

| Group | Tests |
|---|---|
| Returns | RT-1 a return line must reference a delivery load line, or a migrated cash-sale issue line; RT-2 received qty ≤ returnable per load line; RT-3 serial not issued on that load line, or already returned → refused; RT-4 lot qty ≤ issued − returned, lot / expiry / cost restored from the issue rows; RT-5 migrated cash sale return keeps lot and serial identity; RT-6 condition and disposition required; RT-7 credit qty ≤ received qty, credit without return needs permission + approval |
| Reversal | RV-1…RV-4: injection at return receipt, credit-note post and refund payment, and T2 failing after the fiscal cancel succeeded → "fiscal cancelled, reversal pending", then retry once. Plus the approval-pending case. |
| Invoice source | IS-1 every creator #1–#21 writes its `sourceKind`; IS-2 the code-scan test fails on a new direct insert; IS-3 the database guard rejects a customer invoice without `sourceKind`; IS-6 after the backfill every existing customer document has a `sourceKind`, and a payment on an old invoice still posts; IS-4 `ManualInvoicePolicyIT` (allowed / blocked); IS-5 parity scenarios unchanged |
| Migration | MG-1 guard failure (seeded collision, broken three-way link, mapping mismatch) → aborts with offenders listed, schema and data unchanged; MG-2 seeded legacy rows of every state → R6 equal, snapshots equal; MG-3 two identical lines + a credit note referencing the second → forced pairing honoured; conflicting references → migration aborts; an invoice edited before the guard → exception; MG-4 stock rows without `sourceLineId` map through `issueLedgerCode`, and an unknown tender provider is an exception |
| Rollout | RB-1…RB-6 (§11.9) |
| Reporting | RP-1…RP-3, R5a–R5g |

---

## 13. Stages and acceptance criteria

General rules:
- One PR per stage. The owner approved development of every stage below except C5 (dropped) on 2026-11-28; each PR
  still waits for the owner's merge.
- Dedicated worktree; the stage PRs stack on the C0 docs PR.
- Migration block V730–V749, re-verified across all refs.
- Fast loop only (`compileJava compileTestJava` + `fastTest --tests`). No full build unless the owner asks.

C1a and C1b are proposed as the first PRs because they fix defects that exist on `main` today, independent of the
redesign.

| Stage | Content | Acceptance |
|---|---|---|
| **C0** Docs + assessment (approved to proceed) | ADR rev 3 in `docs/adr`; spec updates (`sales.md`, `cash-sales.md` superseded, `invoicing.md`, `sales-postings.md` with J1–J4, F1–F7, §3.6 and the sign-off table). Read-only on a **restored copy**: preflight, D-VAT-1 assessment, count of invoices posted before the guard (§10.4), credit-note creator classification (§7.3). WAL / PITR status of the live host checked read-only. **Assessment done 2026-11-28**: [report](../audits/2026-11-28-adr-s094-c0-assessment.md). | Owner approves the ADR · §8 matrix approved · AD and OD answered or gated · reports with zero unexplained items |
| **C1a** Idempotency lease and fencing (kernel) + TA-6 fix | §6; quarantine location created when the setting is saved and named `RETRY_SAFE_TX` | IL-1…IL-10 green (IL-1 / IL-2 shown failing before the change) · TP-6 · all existing idempotency ITs and parity scenarios unchanged · `CONTRACT.md` §6 updated for the approved deviation only (OD-14) |
| **C1b** D-VAT-1 correction | §3.5, after AD-14 / 15 / 17 (built, V731) | VAT-J1…J4 green in both modes · V1–V5 hold · historical assessment signed off (AD-18) |
| **C1** Schema + state dimensions + approval (built, V732) | §4, including the submit button in the web; order behaviour preserved except the approved approval changes | Backfill proof (counts per old status = new combinations) · AP-1…AP-10 · I1–I7 · authorization matrix · tenant isolation · every existing SalesOrder IT green |
| **C2** Returns / credits / outcome / completion (after AD-9, AD-15, AD-20) | §7, §4.4, §4.5 | RT, CS and FO groups · `qtyCredited` for every credit path · `SalesReturnFlowIT` green |
| **C3** Cash pickup | §5: payment model, T1, handover, fiscal outbox | Exact F1 journals · PM, PH, FI, TP, RC, FR, CC, RV groups green · R1–R3 = 0 · 20-line confirm p95 < 1.5 s and CC-5 without timeout on the rehearsal DB |
| **C4** Invoice from sale + typed source contract | §8 | IS group · `sourceKind` backfilled on every existing customer document before the constraint is added and validated · parity scenarios unchanged · recurring unchanged |
| **C2b** Revenue deferral for invoice-before-delivery (AD-2 = A, AD-16 = dispatch) | §3.6 | DF-1…DF-5 · V1–V5 hold |
| ~~**C5** Migration + consumers + rollout tooling~~ | **Dropped by the owner (2026-11-28)**: no historical migration; legacy demo data is cleared at deploy. §10 and §11 stay as the reference if a real migration is ever needed. | — |
| **C6** Web | screens incl. approval submit / withdraw, tender panel (card, change), handover block, return serial / lot entry, outcome and settlement badges | build · i18n · e2e smoke F1 / F3 / F5 / F6 · no console errors |
| **C7** Reporting | §9 | R5a–R5g = 0 · RP-1 and RP-2 bridges explained |
| **C7b** Period-end accrual of delivered, not invoiced (AD-3) | §3.7 F3 | accrual and next-period reversal ITs · R5f |
| **Release** | C1a–C7b, when the owner asks for a deploy | standard pre-deploy dump; legacy cash-sale demo data cleared only after the owner confirms; WAL archiving enabled (OD-13); RP-3 after go-live |
| **C8** Cleanup (next release) | drop legacy tables and code; command journal off after release closure | owner verification · one release elapsed · no reads of legacy tables · fresh backup |
| Not built | C3d cash delivered later (AD-1 chose the interim path); C4f freight on the invoice (OD-4: no); kernel in-transaction outbox (OD-12: separate ADR) | — |

---

## 14. Decisions (answered by the owner on 2026-11-28; see the table at the top)

### Accountant (go-live gate)

Recorded in `sales-postings.md` with: question · decision · approved treatment · approver · date · implementation ref
· test evidence.

| # | Question | Options (recommended first) |
|---|---|---|
| AD-1 | Cash sale paid now, delivered later | interim order + full advance / contract liability (F2-A) / bill-and-hold |
| AD-2 | Invoice before ownership transfer (F4) | contract liability released at dispatch (§3.6) / period-end deferral journal / materiality threshold |
| AD-3 | Delivered-not-invoiced at period end | period-end accrual / per-delivery P7 |
| AD-4 | Tax point per scenario (pickup; advances in Mode R vs Mode D; invoice before delivery) under local VAT law | confirm against the VAT Act |
| AD-5 | EFRIS timing (cash confirm; all sale invoices; credit notes on reversal) and the zero-total invoice | — |
| AD-6 | Cash postings P4 + P8 + P11 with no unbilled-receivable (1215), deposit or 2216 entries | approve / amend |
| AD-7 | Branch-issued invoices from the single org gap-free series | — |
| AD-8 | Walk-in invoicing (walk-in partner per branch, buyer identity, `requireTaxNumberAbove`) | — |
| AD-9 | Returns accounts, restock cost, damaged / scrap accounts, refund tender rules, credit retention | — |
| AD-10 | Transport revenue account if freight moves onto the sale invoice | — |
| AD-11 | Migrated history acceptance (1215 / 2216 residuals zero after drain) | — |
| AD-12 | POS refunds / voids / write-offs in the revenue measures | — |
| AD-13 | Module sources allowed to issue customer invoices (§8.1) | — |
| AD-14 | Deducting taxed down payments across partial invoices | pro-rata / oldest-first / manual |
| AD-15 | Credit note on a down-payment invoice reduces the deposit; refused once consumed | approve / amend |
| AD-16 | Contract-liability account and release trigger for F4 | dispatch / POD |
| AD-17 | Zero-total final invoice after a 100% taxed advance, issued as a tax invoice | — |
| AD-18 | Historical D-VAT-1 corrections (credit notes), if any occurred. C0: none on live (no organization has ever used `receipt`, and no down-payment invoice exists); re-checked at C1b | — |
| **AD-19** | Zero-value sales (replacement, sample): tax invoice required, or delivery note only? | — |
| **AD-20** | Quantity credit without a return (write-off): accounts, approval level, stock treatment | — |

### Owner (product, controls, operations)

| # | Question |
|---|---|
| OD-1 | Counter pickup exempt from delivery-posting approval and delivery QC (as today)? |
| OD-2 | Retire quotation → POS `Order` conversion after the usage check? |
| OD-3 | When, and for which organizations, to block manual customer invoices (after the gate)? |
| OD-4 | Move fleet transport charges onto the sale invoice later? |
| OD-5 | Release window and rehearsal sign-off owner; name the release lead |
| OD-6 | May a customer on credit hold or block buy for cash at the counter (bypass the trip-dispatch credit gate for the cash type)? |
| OD-7 | Approval-rule changes: re-evaluate at confirm and ask only for new reasons (proposed)? |
| OD-8 | Remove self-approval for `sales:configure` holders (proposed), or keep it as an audited setting? |
| OD-9 | Allow excess non-cash payment to be kept as customer credit? |
| OD-10 | Hypercare length; command-journal retention and storage location |
| OD-11 | Maximum rollback window (proposal 4 hours) and the time limit before L3 is considered |
| OD-12 | Commission a separate kernel ADR for an in-transaction event outbox (TA-3, system-wide) |
| OD-13 | Enable WAL archiving and point-in-time recovery on the live host before release? Without it, L3 is unavailable and only L1 / L2 apply after activation. C0: `archive_mode = off` and nothing has ever been archived; backups are 14 nightly logical dumps (02:00 UTC), so recovery today is to the last dump only. |
| OD-14 | Approve the idempotency fix as a recorded NestJS parity deviation (§6): a retry while the first execution is still running gets `OPERATION_PENDING` instead of a second execution; `CONTRACT.md` §6 updated in C1a. |
| OD-15 | Legacy cash sales at cutover: migrate drafts as draft cash Sales (proposed), and require open home deliveries to be delivered or cancelled during the drain (proposed)? |

---

## 15. Next steps
- C0 is done (this ADR, the spec notes and the [assessment](../audits/2026-11-28-adr-s094-c0-assessment.md)).
- Build order: C1a → C1b → C1 → C2 → C2b → C3 → C4 → C6 → C7 → C7b, one stacked PR each. Web changes ship in the same
  PR as the backend change they depend on, so every PR leaves the web working.
- No deploy until the owner asks for one.
