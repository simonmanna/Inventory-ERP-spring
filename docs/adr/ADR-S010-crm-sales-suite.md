# ADR-S010: CRM sales suite — configurable pipeline, leads, quotations, customer pricing, reports

- Status: Proposed (2026-10-29) — owner approval pending
- Spec: [`docs/spec/crm.md`](../spec/crm.md) § "Sales suite (ADR-S010)"
- Migration: `V16__crm_sales_suite.sql`
- UI references: `crm_reference/` (Odoo CRM screens), Real-Estate-Website CRM (stages with category / colour /
  probability, sources, lost reasons, tasks with reminders, saved views, round-robin assignment, management report)

## Context

The parity port of the Nest `crm` module (P3c, 21 routes) is a fixed six-value `DealStage` enum, a deal list and a
basic activity log. The owner wants an Odoo-class CRM that stays easy to use: customer and contact management, a lead
and opportunity pipeline, tasks / reminders, quotation management with approval, customer-specific pricing,
integration with sales orders, inventory and receivables, sales dashboards, role permissions, audit history, dynamic
stages configured by an admin, and a filterable CRM report.

## Decision

Extend the `crm` module additively; keep the 21 parity routes and their JSON unchanged.

1. **One record, two kinds.** `Deal.kind = lead | opportunity`. Leads may have no customer (`partnerId` becomes
   nullable); `POST /crm/leads/:id/convert` links or creates the customer (through `core`'s `PartnerService`) and puts
   it on the pipeline.
2. **Configurable stages** (`CrmStage`: name, sequence, probability, won flag, fold, colour, requirements, team,
   rotting days). Each stage maps to a legacy `DealStage` (`legacyStage`) which is written to `Deal.stage`, so the
   parity routes, the legacy analytics and the Android app keep working. Outcome is a separate `Deal.status`
   (`open / won / lost`) with lost reason + note, as in Odoo. Legacy stage writes (`/crm/deals`) sync `status` and
   `stageId` back. A fresh organization is seeded with New → Qualified → Proposition → Negotiation → Won.
3. **Stage history** (`CrmStageHistory`, seconds in the stage left) feeds the time-in-stage report, the funnel and the
   stage bar on the form; every move also logs the legacy `deal_stage_change` activity.
4. **Planned activities** reuse `Activity` (+ `assignedToId`, `remindAt`, `reminderSentAt`, `outcome`). A ShedLock
   job (`crm-activity-reminders`, every minute) sends in-app notifications. Cards show the next-activity state
   (overdue / today / planned / none) like Odoo.
5. **Quotations** (`CrmQuotation` + lines, numbered `QT-YYYY-nnnnn`) priced by price rules and taxed by the invoicing
   tax engine (`DocumentBuilderService.prepareLines`). Submit checks: extra discount beyond the authorised (rule)
   price vs. `quoteMaxDiscountPercent`, total vs. `quoteApprovalAmount`, customer credit hold / limit vs. open
   receivables + quote. A breach goes to the kernel approval engine (`crm_quotation`) when a workflow exists,
   otherwise to any `crm:quotation:approve` holder (not the creator). Accepted quotes convert to an Order
   (`OrdersService.createOrder`, stock re-checked; back-order needs an explicit flag) or a proforma invoice
   (`DocumentBuilderService.createDocument("proforma_invoice")`, own `PRO-YYYY-` numbering). Conversion can mark the
   opportunity won.
6. **Customer pricing** (`CrmPriceRule`): customer / customer category / everyone × product / product category /
   every product, min quantity, validity, fixed price or % discount, priority. Resolution: priority → customer
   specificity → product specificity → min quantity; fallback to the partner's (or category's) standing discount, then
   the list price.
7. **Visibility**: without `crm:deal:view_all` a user sees own, unassigned and led-team records (pipeline, lists,
   reports, quotations).
8. **Reports**: `/crm/reports` (KPIs, funnel, by stage / salesperson / team / source / tag / country, lost reasons,
   monthly trend, forecast, time in stage, quotation outcomes, activities) with filters; `/crm/dashboard`; customer
   directory and Customer 360 with receivables and orders (read-only queries on `Partner`, `Document`, `Order`,
   `StockItem`, `StockReservation`).
9. **Permissions** (new): `crm:deal:view_all`, `crm:lead:read|write`, `crm:quotation:read|write|approve|convert`,
   `crm:pricing:read|manage`, `crm:report:read`, `crm:configure`. V16 grants them to Administrator and the lead /
   quotation / pricing-read / report subset to roles that already have `crm:deal:write`.
10. Tables of the suite are accessed with plain SQL (`CrmDb`), like the parity service; no JPA entities.

## Consequences

- Parity routes unchanged; `Deal` gains columns (not in their JSON because rows are projected through the `Deal`
  entity). The authorization matrix of the `crm` package is now 88 routes (21 parity + 67 suite).
- `DocumentBuilderService.createDocument` gains a `proforma_invoice` numbering branch (previously unused path).
- Web: `/crm/deals*` redirects to the new pipeline / opportunity form; the partner page's CRM tab still uses the
  legacy deal dialog.
- Users already logged in must sign in again to pick up the new permissions in the web client.
