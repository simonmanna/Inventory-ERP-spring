# ADR-S074 — Bonds and guarantees, subcontractor compliance, CIP capitalisation

- Status: accepted (built 2026-11-10)
- Migrations: `V492__project_bonds.sql`, `V493__subcontract_compliance.sql`, `V494__cip_capitalisation.sql`
- Extension: no NestJS counterpart. Module `com.inventoryerp.projects` (the fixed-asset register is written through
  `fixedasset.application.AssetCapitalisationService`; supplier payments call the `invoicing` SPI
  `SupplierPaymentGuard`).
- Builds on ADR-S038 (project core), ADR-S039 (contract commercials), ACC-5 (fixed-asset GL lifecycle).

## Context

Construction firms in East Africa work under bonds and guarantees on both sides of the contract. Clients and lenders
also expect subcontractor paperwork to be in order before money moves: tax clearance, NSSF clearance, insurance, a
trade licence and lien waivers. Firms that build their own depots, plants and quarries need those projects capitalised
under IAS 16 rather than recognised as contract revenue. None of this existed. The accounting spec listed "CIP
capitalisation" as not built.

## Decision

### 1. Bonds and guarantees (`ProjectBond`, `ProjectBondEvent`)

- Types: `performance`, `bank_guarantee`, `advance_payment`, `retention`, `bid`. Direction `issued` (our bank or
  insurer guarantees us to a client) or `received` (a subcontractor's or supplier's bank guarantees them to us).
- Register fields: project (optional; a bid bond may come before the project), subcontract (optional; it defaults the
  project and the counterparty), counterparty, issuer, issuer type `bank | insurer`, bank's bond number, amount,
  currency, issue and expiry dates, DMS attachment (`documentId`, plus record type `project_bond`).
- Lifecycle: `draft → active → extended / reduced → released | called | expired`. Only a draft can be edited. Every
  step writes an event row, and the event id keys its journal entry, so a replay never posts twice.
- GL (optional, base currency, bank account `bankAccountId` or mapping `default_bank`):

| Step | Posting |
|---|---|
| activate: margin (issued only) | Dr `bond_margin_deposit` (1482) / Cr bank |
| activate / extend: commission (issued only) | Dr `bank_charges` (6730) / Cr bank |
| reduce (margin returned) / release (all margin left) | Dr bank / Cr 1482 |
| call, received, `recoveryTo = project_cost` | Dr bank / Cr the project cost account (WIP or CIP) on the subcontract's WBS, plus a negative `ProjectCostLine` (`project_back_charge`, cost element `subcontract`) |
| call, received, `recoveryTo = claim_income` | Dr bank / Cr `bond_claim_income` (4295) |
| call, issued | Dr `bond_call_expense` (6995) / Cr 1482 (margin held) and bank (the rest) |

- A bond recovery reuses the `project_back_charge` cost-line source. A new source value would have to change the
  `ProjectCostLine_source` check constraint, and other branches also redefine that constraint. A recovery against a
  subcontractor's bond is a back-charge in substance.
- Expiry: `ProjectBondJob` (daily, `app.projects.bond-expiry-cron`, default 00:15, ShedLock `project-bond-expiry`) marks
  live bonds whose expiry date has passed as `expired`. Any held margin stays until the bond is released.
- Alerts: `ConstructionAlertSource` feeds the existing `expiring_document` alert type, so the alert catalog does not
  change. The rule's threshold is the outer lead time (default 30 days). Inside it, the tier (`30`, `14`, `7`,
  `expired`) is part of the subject key, so a bond alerts once on entering the window, again at 14 and 7 days, and
  again after it expires. At 7 days or less the alert is critical.
- Exposure report (`GET /project-bonds/exposure`): live bonds per issuer and per project, by direction and currency,
  with the amount expiring within 30 days and the margin held.

### 2. Subcontractor compliance (`ComplianceDocument`, `ComplianceRequirement`, `ComplianceOverride`, `ComplianceGateLog`)

- Document types: `insurance_certificate`, `tax_clearance`, `social_security_clearance` (NSSF), `trade_licence`,
  `lien_waiver`. A document belongs to a supplier and optionally to one subcontract. Fields: number, issuer,
  validity, amount and a DMS attachment (record type `compliance_document`). A document counts only after a user
  verifies it (`verifiedBy`, `verifiedAt`). A verified document is frozen. A rejected one never counts.
- Lien waivers: `conditional | unconditional`, scope `valuation` (with the valuation) or `payment` (with an outbound
  payment to the supplier), amount and through-date. A lien waiver must belong to a subcontract.
- Gating is configured per organisation and document type: `off` (the default, and the value for a type with no row),
  `warn` or `block`. When nothing is gated, existing behaviour is unchanged. Gates:
  - **Valuation certification** (`POST /projects/subcontracts/:id/valuations`). Each gated certificate type needs a
    verified document that is valid today. A document can be missing, awaiting verification, expired, or not valid
    yet. When lien waivers are gated, the latest payment on the subcontract needs an unconditional waiver (for that
    payment, or with a through-date on or after it).
  - **Payment** (`POST /supplier-payments`, through `invoicing.application.SupplierPaymentGuard`). The guard checks
    every allocated vendor bill that belongs to a subcontract: a valuation's net bill, or a subcontractor retention
    release bill. It applies the same checks, and a valuation's bill also needs a waiver for that valuation.
- A `block` refuses with `400 Compliance check failed: …`. The refusal is logged in its own transaction, so it
  survives the rollback. A `warn` passes; the valuation response carries `complianceWarnings` and the gate is logged.
- An override (`POST /subcontract-compliance/overrides`, `compliance:override`) needs a reason of at least 10
  characters. It opens one valuation of the subcontract, or the payment of one named bill, and the next matching
  gate consumes it. Creating it and consuming it are both audited and logged.

### 3. CIP capitalisation (`Project.isCapital`, `ProjectCapitalisation`, `ProjectCapitalisationAsset`, `Asset.inServiceDate`)

- A project is flagged as capital with `PUT /projects/:id/capital`. The flag changes only before any cost line exists,
  and not on a project with claims or revenue runs.
- `ProjectCostService.costAccount(project)` returns **1690 Construction in Progress** (category
  `construction_in_progress`, mapping `construction_in_progress`) for a capital project and 1460 for any other
  project. Every capture path, subcontract valuations, back-charges and bond recoveries use it. The cost report's tie-out
  compares the project's own account and subtracts both revenue-run releases and capitalisations.
- Capitalisation (`POST /projects/:id/capitalisations`): one or more assets, each with a name, asset category, amount,
  useful life in months (default from the category), salvage value and in-service date. Partial capitalisation is
  allowed, up to the CIP balance (cost captured minus capitalised). It posts one journal entry: Dr
  `fixed_asset_valuation` per asset / Cr 1690. The assets are created through
  `AssetCapitalisationService.createConstructed`: active, acquisition method `constructed`, cost = capitalised =
  current value = amount, with the in-service date. No acquisition record is written, so the ACC-5 acquisition posting
  never runs for these assets, and they do not go through the `asset_acquisition` approval gate. The capitalisation
  route needs `project:post`.
- Depreciation starts in the in-service month: `AssetDepreciationService` skips periods before it. Assets without an
  in-service date (everything created before this ADR) are unaffected.
- Reversal (`POST /projects/capitalisations/:id/reverse`) reverses the whole capitalisation while none of its assets has
  posted depreciation. It reverses the entry, deletes unposted depreciation rows and soft-deletes the assets.
- CIP report: `GET /projects/:id/cip` covers one project and `GET /projects/cip-report` covers every capital project.
  Both reconcile the sub-ledger (cost captured − capitalised) to the GL CIP account, per project dimension and in
  total.

## Accounts

| Code | Name | Category | Mapping key |
|---|---|---|---|
| 1482 | Bond & Guarantee Margin Deposits | current_asset | `bond_margin_deposit` |
| 1690 | Construction in Progress | construction_in_progress | `construction_in_progress` |
| 4295 | Bond & Guarantee Claim Income | other_income | `bond_claim_income` |
| 6730 | Bank Charges & Guarantee Commission | operating_expense | `bank_charges` |
| 6995 | Bonds & Guarantees Called | other_expense | `bond_call_expense` |

Accounts are created on first use (`AccountResolverService.ensureByCode`); an existing mapping wins.

## Permissions

`bond:read`, `bond:manage`, `bond:post`; `compliance:read`, `compliance:manage`, `compliance:override` (granted to the
Administrator role by V504 / V505, like the other `contract:*`-style families). The CIP routes use `project:read`,
`project:manage` (capital flag) and `project:post` (capitalise, reverse).

## Consequences

- `ContractService.valuation` calls the compliance gate through a setter-injected hook, and `PaymentService` calls
  `SupplierPaymentGuard` beans through a setter. Neither constructor changed.
- Gating is organisation-wide, so tests that switch it on must switch it off again (`ConstructionControlsIT` does
  this after each test).
- Not built: multi-currency GL amounts for bonds (GL amounts are taken in the base currency); automatic margin
  release on expiry; per-subcontract requirement overrides; gating of payments recorded outside `POST
  /supplier-payments` (mobile money, cash sessions).
