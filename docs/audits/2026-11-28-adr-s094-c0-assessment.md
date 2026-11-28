# ADR-S094 C0 read-only assessment (2026-11-28)

This is the C0 read-only assessment for [ADR-S094](../adr/ADR-S094-centralized-sales.md), centralized sales. No code,
migration, setting or data was changed. The corrections it led to are listed in ADR-S094 §0.3 (K-1…K-9).

## Method

- **Data source**: the nightly logical backup of the live database, taken 2026-11-28 02:00 UTC, with its checksum
  verified. The last migration it contains was applied 2026-11-27 08:14 UTC; live runs `main` 77daa9fb.
- **Restored copy**: a throwaway PostgreSQL 16 container with its data directory held in memory. The host disk was
  99% full (1.5 GB free), and a restore written to disk could have stopped the live database. No port was published,
  and the container was removed afterwards.
- **Checks**: [2026-11-28-adr-s094-c0-assessment.sql](2026-11-28-adr-s094-c0-assessment.sql). It runs in one
  transaction that is rolled back, and is meant to be re-run as the C5 preflight on a fresh copy. It must never run on
  live.
- **Live host**: read-only settings queries only (`pg_settings`, `pg_stat_archiver`, `pg_roles`).
- **Code**: `origin/main` 77daa9fb, searched with `grep -a` (see §6).
- **Limits**: live holds demo data in two organizations (DEMO, GQDEMO). Counts are as of the dump.

## Summary

| Area | Result | Follow-up |
|---|---|---|
| D-VAT-1 history | No organization has ever used `depositTaxPoint = receipt`, and no down-payment invoice exists. | Nothing to correct (AD-18). The defect stays latent until C1b. |
| Cash-sale invoices before the post guard | All 42 were posted between 2026-11-10 and 2026-11-22, before the guard went live (2026-11-25 10:13 UTC). | All 42 pass L-1. There are no identical lines. |
| Migration preflight (C-1 … D-1) | No id or number collisions, no broken references, and all totals reconcile. | — |
| Drain | 39 confirmed cash sales are not invoiced. They hold 51,280,366 UGX of open deposits, equal to their unbilled receivable. | Invoice or void them before any migration (C5). |
| Stock-issue linkage | `sourceLineId` is empty on 122 of 137 cash-sale ledger rows. `issueLedgerCode` maps every line exactly. | ADR corrected (K-5, new check L-2). |
| Tender providers | Free text, in four spellings. | Mapping table at migration (K-7). |
| Legacy home deliveries and drafts | 5 home-delivery cash sales (2 still open) and 4 drafts. | OD-15. |
| Credit notes | 1 on live: manual and standalone. | Value kind. |
| Invoice creation points | `Document` rows are inserted in 3 places. | ADR §8.2 simplified (K-3). Backfill before the guard (K-4). |
| Live recovery | WAL archiving is off; backups are nightly logical dumps only; lock timeouts are off. | OD-13; C1a timeouts. |
| Tooling | One source file contains a NUL byte, and plain `grep` skips it. | Search code with `grep -a`; fix proposed in §9. |

## 1. D-VAT-1 (taxed down payments)

| Check | Result |
|---|---|
| A1 Organizations with `depositTaxPoint = receipt` | 0. Both use `delivery`. |
| A2 Down-payment links / down-payment documents (all, taxed) | 0 / 0, 0 |
| A3 Regular invoices posted after a taxed down payment on the same order | 0 |
| A4 Audit entries that ever set `depositTaxPoint` to `receipt` | 0 |

So the defect has never occurred on live, and there is no history to correct. Any organization that switches to
`receipt` before the C1b fix would hit it.

## 2. Cash-sale invoices and the post guard (ADR §10.4)

- **When the guard went live**:
  - The guard (`SalesInvoicePostGuard.cashSaleProblems`, c446e1d9) reached `main` through PR #57 on 2026-11-25 at
    06:00 UTC.
  - It first went live with the 287bebaa deploy. Flyway V674 was installed that day at 10:13 UTC.
  - The earlier Wave B deploy that day (c414aa6d) did not contain it.
- **B1**: 42 cash-sale invoices were posted before that time, and none after.
- **L-1**: all 42 pass. Each invoice line pairs one-to-one with its sale line by order, and every compared attribute is
  equal: line count, product, variant, tax, quantity, unit price, discount, unit, unit factor, subtotal, tax amount,
  total and tax-inclusive flag.
- **B3 (V-ID)**: no sale has identical lines.

The unprotected history is therefore intact on live. The migration still runs L-1 on every invoice, as the ADR requires.

## 3. Migration preflight (ADR §10.3)

| Check | Result |
|---|---|
| C-1 Sale ids also used in `SalesOrder` | 0 |
| C-2 Line or tender ids also used in `SalesOrderLine` / `SalesOrder` | 0 |
| C-3 (organization, number) overlap | 0. All 87 numbers start with `CSS-` and no sales-order number does. The CSS sequences are per branch (`seq_<org>_css_<branch>_2026`), separate from `seq_<org>_sales_order_2026`. |
| RI-1 Partner, branch, profile, location, salesperson, product, variant, unit and tax references | 0 dangling or cross-organization. No sale lacks a partner. |
| RI-2 Three-way invoice agreement | 42 of 42 agree. No orphan cash-sale invoice. No non-invoiced sale has a current invoice. |
| RI-3 Deposits, fiscal documents, gate passes (2) and their lines, ledger rows, DMS links | All resolve, except the missing `sourceLineId` (§4). There are no fiscal documents and no DMS links. |
| ST-1 States | See below. 39 to drain, 4 drafts, no fiscal operation pending. |
| M-1 Totals | 0 mismatches: header vs lines; grand total = subtotal + tax; paid = verified tenders = grand total; invoice total = sale total; residual 0; deposit applications = invoice total; deposits consumed; deposits of voided and cancelled sales closed. |
| M-2 Unbilled receivable (1215) | Zero on all 42 invoiced sales and on the voided, cancelled and draft ones. The 39 confirmed, not-invoiced sales carry 51,280,366 UGX, exactly their open deposits. |
| D-1 Tender references | No duplicates, after mapping the providers. Every mobile-money and bank tender has a reference; 43 of 45 cash tenders have none, which is expected. |
| L-1, V-ID | §2 |

**ST-1 states** (87 cash sales, all in UGX):

| Execution | Processing | Delivery method / status | Count |
|---|---|---|---|
| confirmed | invoiced | pickup / pending | 37 |
| confirmed | invoiced | pickup / delivered | 2 |
| confirmed | invoiced | delivery / delivered | 3 |
| confirmed | approved | pickup / pending · pickup / delivered | 11 · 1 |
| confirmed | not_submitted | pickup / pending · pickup / delivered | 9 · 1 |
| confirmed | under_review | pickup / pending · delivery / dispatched | 8 · 1 |
| confirmed | submitted | pickup / pending · delivery / scheduled | 3 · 1 |
| confirmed | returned_for_correction | pickup / pending | 3 |
| confirmed | rejected | pickup / pending | 1 |
| draft | not_submitted | pickup / pending | 4 |
| voided · cancelled | not_submitted | pickup / pending | 1 · 1 |

Before any migration, the drain must invoice or void the 39 confirmed, not-invoiced sales. It must also finish or
cancel the 2 open home deliveries (OD-15).

## 4. Stock-issue linkage (ADR §7.2, §10.2)

- **The stock ledger** is `InventoryLedger`. A cash-sale issue row has `referenceType = 'cash_sale'`, `referenceId` =
  sale id and `sourceLineId` = sale line id.
- **Missing line ids**: of 137 cash-sale rows, only 15 have a `sourceLineId`, all written from 2026-11-22 on. The 122
  without one were all written before 2026-11-13 14:15 UTC. The issue call gained the line id in 3d493469
  (2026-11-15).
- **The fallback works**: `SalesSubmissionLine.issueLedgerCode` holds the issue's `ledgerCode`, and the results are
  exact:
  - all 134 tracked lines on confirmed sales have a code that resolves to rows of the same sale;
  - each of the 122 rows without a line id matches exactly one line of its sale;
  - issued base quantity = line quantity × unit factor on all 137 lines that have a code.
- **Key**: ledger codes (`STK/nnnnnn`) are numbered per organization, so the key is (organization, sale, code).
- **Serials and lots**: no cash-sale row carries a serial or a batch today.
- **Pickup status**: 37 invoiced pickups show `deliveryStatus = pending`, yet every tracked line has its stock issued.
  The old flow issues stock at confirm, so the migration takes fulfilment from the issue rows.

## 5. Credit notes (ADR §7.3)

**Data**: live holds one credit note. It is posted, was created through `POST /credit-notes`, has no
`reversedDocumentId` and no sales link. It is a value credit.

**Code**: every sales and fleet creator calls `CreditNoteService.create`, which calls
`DocumentBuilderService.createDocument("credit_note")`.
- **Return close** (`SalesReturnService`) writes `credit_note` links (`:440`). It recomputes `qtyCredited` from posted
  links (`:466`). It is the only writer of `qtyCredited`.
- **Late dispute** (`SalesBillingService:482-491`) creates one account line with no product, so no stock moves. It
  writes a `credit_note` link with a quantity, but does not recompute `qtyCredited`. That quantity reaches
  `qtyCredited` only when a later return on the same order closes. Classify it as a quantity credit without return
  (W, ADR §4.5).
- **The other creators** are as listed in ADR §7.3.

## 6. Invoice creation points (ADR §8)

- **Insert sites**: `Document` rows are inserted in exactly three places:
  - `DocumentBuilderService.createDocument` (`:687`; its lines at `:719`). Every other creator reaches it, directly or
    through `InvoiceService` / `CreditNoteService`: the sales, fleet, projects, collections and opening-balance
    creators (for example `OpeningBalanceService:203` calls `invoices.create`, and `FleetDiscrepancyService:508` calls
    `creditNotes.create`);
  - `RecurringService.generateOne` (`:162`; its lines at `:181`);
  - `DocumentEngineService.createCounterpart` (`:405`).
- **Customer documents without `sourceType` on live** (11): 10 manual invoices in DEMO (1 draft, 8 posted, 1 paid) and
  the manual credit note. No module table refers to them. Every `*invoiceId` / `*documentId` column was checked;
  payment allocations and price snapshots are the only references.
- **NUL byte**: `DocumentBuilderService.java:843` uses a raw NUL byte as a separator in an in-memory map key. It is
  harmless at run time, but plain `grep` then treats the file as binary and silently skips its matches. Revision 3 of
  the ADR missed the class for this reason (K-2). This assessment searched with `grep -a`. It is the only file in the
  repository with a NUL byte.

## 7. Live recovery capability (ADR §6, §11, OD-13)

| Item | Live value |
|---|---|
| `wal_level` | `replica` |
| `archive_mode` / `archive_command` | `off` / disabled; nothing has ever been archived |
| Backup tools | `pg_basebackup` present; no pgBackRest, WAL-G or Barman |
| Backups | 14 nightly logical dumps (2026-11-16 → 2026-11-28, 02:00 UTC, with checksums), plus pre-deploy dumps |
| Recovery point today | The last dump. Up to a day of transactions could be lost; there is no point-in-time recovery. |
| `idle_in_transaction_session_timeout`, `statement_timeout`, `lock_timeout` | 0 (off) |
| TCP keepalives | Server 0, so the OS default of 7,200 s applies. No role-level settings. |
| API → database | Same host (127.0.0.1) |

L3 recovery in ADR §11 is therefore unavailable until WAL archiving is enabled and a restore is tested (OD-13). Turning
on `archive_mode` needs a database restart and archive storage.

## 8. ADR corrections

Recorded in ADR-S094 §0.3:
- **K-1**: deposit account code.
- **K-2**: `DocumentBuilderService` added to TA-12.
- **K-3**: single creation entry point.
- **K-4**: `sourceKind` backfill before the guard.
- **K-5**: stock ledger columns and the `issueLedgerCode` fallback.
- **K-6**: gate-pass and DMS columns.
- **K-7**: provider mapping.
- **K-8**: legacy fulfilment, deliveries and drafts.
- **K-9**: recovery and timeout facts.

`docs/spec/sales-postings.md` §5a now names the deposit account by its mapping.

## 9. Open items (nothing done; each needs the owner)

1. **OD-15**: legacy drafts and open home deliveries at cutover.
2. **Provider mapping**: approve a mapping of the free-text providers (`mtn`, `mtn mobile money` → `mtn_momo`;
   `airtel`, `airtel money` → `airtel_money`) before C5.
3. **NUL byte**: a one-line code change would replace the raw NUL in `DocumentBuilderService.java:843` with the
   escape `"\u0000"`. The run-time value is identical, and the file becomes searchable. It is a separate, small PR,
   outside C0.
4. **Disk space**: the live host disk was 99% full during this assessment. The release backup (ADR §11, P2) and any
   restore rehearsal need free space.
5. **OD-13**: WAL archiving and point-in-time recovery on the live host.
6. **Re-run at C5**: re-run the SQL on a fresh copy as the C5 preflight; numbers will have changed.
