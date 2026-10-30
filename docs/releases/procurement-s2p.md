# Source-to-Pay purchasing — release notes

ADR-PROCUREMENT-001, waves W1–W8, migrations V26–V32. The 36 Nest-parity procurement routes keep their contract; the
deliberate fixes are listed under *Deviations* in `docs/spec/procurement.md`. Everything new is off or neutral until an
organisation configures it on the **Purchasing Policies** screen.

## Highlights

- **Integrity fixes.**
  - Receipts are vouchered at their actual value.
  - Payments are capped at what was received.
  - Debit notes post exactly once.
  - The three-way match runs in exact decimals.
  - Foreign-currency orders post in the functional currency.
  - Tenancy checks are applied on every foreign id.
- **Suppliers.**
  - Lifecycle: approved, provisional, suspended, blacklisted.
  - Bank accounts are encrypted, and a second person verifies them.
  - Certificates expire with reminders, and there is a catalogue and price history.
- **Requisition to order.**
  - Sourcing workbench and RFQs with comparison and split awards.
  - Blanket and rate agreements with releases.
  - Order lifecycle: send, supplier confirmation, short-close and close.
- **Controls.**
  - Budget commitments and budget control.
  - Order change requests with revisions and re-approval.
  - Match tolerances and two-way mode.
  - Duplicate-bill detection and supplier claims.
- **Receiving.**
  - Incoming inspection holds the bill and raises quality claims.
  - Weighbridge evidence for bulk material.
  - Emergency purchases with independent review.
- **Planning and insight.**
  - Replenishment from reorder rules.
  - Supplier scorecards and price anomaly checks.
  - Spend analysis, demand forecast, risk alerts and a purchasing dashboard.
- **Supplier portal.** Secure, expiring link per RFQ or order. Suppliers quote or confirm without an account.

## Upgrade

Flyway applies V26–V32 automatically. The migrations are additive: new tables, nullable or defaulted columns, and
permissions granted to Administrator. Give other roles the new permissions (`supplier:*`, `procurement_policy:*`,
`rfq:*`, `purchase_agreement:*`, `po_change:*`, `supplier_claim:*`, `budget:override`, `commitment:read`,
`inspection:*`, `emergency_purchase:review`, `receiving:override_evidence`, `replenishment:*`,
`procurement_analytics:read`, `supplier_portal:manage`) on **Roles & Permissions**.

The supplier portal page is `/supplier-portal/:token` on the web app. Its API is `/api/v1/supplier-portal/*`, which is
public. Expose both through the reverse proxy.

## Not in this release

- Landed-cost gaps G1–G8 beyond the existing landed-cost vouchers.
- Invoice-before-receipt (`goods_in_transit`).
- Direct-to-site acceptance.
- Asset hand-off to fixed assets.
- Density-based unit conversion.
