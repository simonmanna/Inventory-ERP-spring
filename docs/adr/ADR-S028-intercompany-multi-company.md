# ADR-S028 — Intercompany & multi-company (company groups, company switching, mirrored documents, eliminations)

Status: **accepted** (owner instruction "build PR-8, PR-11, PR-13", 2026-11-01; roadmap 2.7). Implemented in `V220`,
the new module `com.inventoryerp.intercompany` (`CompanyGroupService`, `CompanySwitchService`,
`IntercompanyPartnerService`, `IntercompanyMirrorService` / `IntercompanyMirrorSubscriber`,
`IntercompanyJournalService`, `IntercompanyConsolidationService`), `AuthService.issueTokensForSwitch` (kernel) and the
web page `/intercompany` (Accounting → Configuration) with a company switcher in the user menu.

## Context

Every company is an **organization** (tenant): its own chart of accounts, journals, partners, users and row-level
security. ADR-S005 #6 consolidates the branches of *one* organization and leaves intercompany eliminations out because
"a single legal entity has no intercompany accounts". Roadmap 2.7 asks for:

1. users who work for several companies,
2. intercompany transactions mirrored in the books of both companies,
3. eliminations when the group consolidates.

Tenancy stays as it is. A user row belongs to one organization, an access token to one organization, and every query
is filtered by `organizationId` (and RLS). Multi-company is built *on top of* that, never by widening a tenant.

## Decision

### 1. Company group

- `CompanyGroup` (install-wide, no tenant column): name, parent organization, group currency (the parent's currency).
- `CompanyGroupMember`: one row per member organization; an organization belongs to **at most one** group. The parent
  is a member with `ownershipPercent` 100. Subsidiaries carry an ownership percentage (0 < % ≤ 100) used for the
  non-controlling interest.
- **Consent.** Only the parent organization manages the group (`intercompany:manage`). Adding a company by its code
  needs the caller to have an **active user with the same e-mail in that company, holding `intercompany:manage`
  there**. Nobody can pull a company they do not administer into a group.
- The parent cannot be removed; a group is deleted by removing every subsidiary first (then `DELETE /intercompany/group`).

### 2. Multi-company users (company switching)

- A user "works for several companies" when the same e-mail has an **active, unlocked user** in another member of the
  group. `GET /intercompany/companies` lists them (current company first).
- `POST /intercompany/companies/:organizationId/switch` issues a fresh access + refresh token pair for the twin user in
  the target company (its own roles and permissions — nothing is carried across). Recorded as a successful
  `LoginAttempt` with reason `company_switch`, an audit row, and `user.logged_in`.
- **MFA.** If the target user has MFA enrolled and the current user does not, the switch is refused
  ("Sign in to this company directly: it requires MFA"). A session that already passed MFA may switch to an MFA user.
- Permission `intercompany:switch` (Administrator holds it); the target account's own roles then apply.

### 3. Intercompany partners

- `IntercompanyPartner` (tenant table): in company X, the partner that represents member company Y. One per pair.
  `PUT /intercompany/partners/:counterpartOrganizationId` links an existing partner or creates one (name of the
  company, customer + supplier, code `IC-<code>`).
- A linked partner is how the books know a document or a journal line is intercompany. Nothing else is flagged.

### 4. Mirrored documents

- `IntercompanySettings` per company: `mirrorMode` = `off` | `draft` (default) | `post`, and the expense account used
  on mirrored vendor-bill lines (`billExpenseAccountId`; default: the `default_expense` mapping).
- When X **posts a sales invoice** to the partner linked to Y, `invoice.posted` (outbox, at-least-once) creates in Y a
  **vendor bill** to the partner linked to X: same issue / due dates, currency (by code), reference = X's invoice
  number, one line per invoice line (description, quantity, unit price, discount; tax mapped by tax code, else by type
  + rate + inclusive flag; account = `billExpenseAccountId`). No product on the bill lines: goods move through their
  own receipt; the bill never touches stock.
- `post` mode posts the bill straight away (three-way-match and approval overrides are *not* used: a guard that blocks
  leaves the bill in draft and the transaction row records why).
- The source lines are read as the source company and the bill is written as the target company (each in its own
  transaction): under RLS neither tenant can see the other's rows.
- `IntercompanyTransaction` keeps one row per source document (unique `sourceOrganizationId` + `sourceDocumentId`):
  status `mirrored` | `failed` | `skipped`, the target bill id / number, the error. `POST …/:id/retry` retries a
  failed row. The subscriber never throws for a business error, so the outbox does not loop.
- Credit notes and vendor bills entered first in the buying company are **not** mirrored in this PR (owner decision
  log); their GL lines are still eliminated (§6).

### 5. Intercompany journals (`POST /intercompany/journals`, `Idempotency-Key` required, answers 200)

- One request posts **two balanced entries in one database transaction**, one per company, or nothing:
  - source: Dr *receivable from target* (source's partner-for-target, its receivable account) / Cr `sourceAccountId`;
  - target: Dr the target account / Cr *payable to source* (target's partner-for-source, its payable account). The
    caller cannot list the other company's chart, so the target account is given as `targetAccountCode` (or
    `targetAccountId`).
- Amount in the source company's currency; the target entry converts at the `CurrencyRate` valid on the date when the
  currencies differ. Both entries carry `sourceType = intercompany_journal` and the same `IntercompanyJournal` id.
- Technique: the transaction is opened without a tenant (like `InstallWide`), and each half runs inside
  `TenantContext.run(<company>)` so `PostingService` sees that company (all its queries filter `organizationId`
  explicitly). Fiscal-period and books-lock checks run for both companies; a refusal in either rolls back both.
- Permission `intercompany:post`, and the caller must be a multi-company user of the target (§2) holding
  `intercompany:post` there too. A missing intercompany partner on either side is created on first use.

### 6. Consolidation with eliminations (`GET /intercompany/consolidation?from&to&statement=pl|tb`)

- Parent company only (`intercompany:read`). One column per member, an `eliminations` column and a `consolidated`
  column. Accounts are matched **by account code** across companies (every company starts from the same CoA
  template); a code missing in the parent keeps the member's name.
- **Translation** to the group currency: one rate per member per report — `tb` at the closing rate on `to`, `pl` at
  the average of the rates dated in the period (closing rate when none). With a single rate per member each member's
  trial balance stays balanced, so no translation-reserve row is produced (a proper CTA needs historical equity rates;
  out of scope).
- **Eliminated lines** (per member, posted entries in range):
  (a) lines carrying an intercompany partner on an **AR or AP control account** — intercompany receivables and
  payables, including intercompany journals;
  (b) **P&L lines of the entries that contain an (a) line** — intercompany revenue, expense and cost of the invoices,
  bills and credit notes between members.
  Tax lines are not eliminated (they are real balances with the tax authority).
- If both sides are booked the eliminations net to zero; otherwise the residual is shown as an `intercompanyDifference`
  row, and `GET /intercompany/reconciliation?asOf` lists each pair (X's receivable from Y vs Y's payable to X, in group
  currency) with its difference.
- **Non-controlling interest**: `pl` totals split the consolidated net profit into `attributableToParent` and
  `nonControllingInterest` (Σ subsidiary net profit × (100 − ownership %)). No equity method, no goodwill.

## Routes (module `intercompany`, permissions `intercompany:read|manage|post|switch`)

`GET|POST|PATCH|DELETE /intercompany/group`, `POST /intercompany/group/members`,
`PATCH|DELETE /intercompany/group/members/:organizationId`, `GET /intercompany/companies`,
`POST /intercompany/companies/:organizationId/switch`, `GET /intercompany/partners`,
`PUT /intercompany/partners/:counterpartOrganizationId`, `GET|PUT /intercompany/settings`,
`GET /intercompany/transactions`, `POST /intercompany/transactions/:id/retry`, `GET|POST /intercompany/journals`,
`GET /intercompany/reconciliation`, `GET /intercompany/consolidation`.

## Consequences

- Additive: no existing route, payload or row changes; companies outside a group behave exactly as before.
- Group tables are install-wide (no `organizationId`), so RLS does not cover them; the service checks membership on
  every read and write. Tenant tables (`IntercompanyPartner`, `IntercompanySettings`, `IntercompanyTransaction`,
  `IntercompanyJournal`) get the V214 policy.
- Known limits: no automatic mirror of credit notes / buyer-first bills, no product mapping between companies, no
  equity-method or goodwill accounting, consolidation by account code only.
