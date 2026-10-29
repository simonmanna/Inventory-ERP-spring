# Owner verification checklist (Track C — construction materials)

Per module: what to run, the migrations added, the screens to click through with the expected result, and open
doubts. Work through it top to bottom on a machine with Docker (Testcontainers) or a local Postgres
(`IT_EXTERNAL_DB=true`, see `docs/BUILD_LOG.md` → "Environment notes").

## Phase 1.1 — Fleet: vehicle & equipment registry (ADR-S009)

Built and verified on the session-B box: full backend `./gradlew build` green against local Postgres 16 (694 tests,
0 failed); web typecheck green, lint 0 errors (no new warnings), i18n check shows no new gaps (the pre-existing gaps
remain). License server: unit tests green; `LicenseServerIT` / `LicenseAdminIT` need Docker (not available on that box)
— run them here. `V3__fleet_feature.sql` was applied by hand to a scratch database (V1→V3) and checked.

**Commands**

```bash
cd backend && ./gradlew test --tests 'com.inventoryerp.fleet.*'          # FleetPoliciesTest + FleetRegistryIT
cd backend && ./gradlew test --tests 'com.inventoryerp.ModularityTests' --tests 'com.inventoryerp.ApplicationIT'
cd license-server && ./gradlew build                                     # V3__fleet_feature.sql, KNOWN_FEATURES
pnpm web:typecheck && pnpm --filter @erp/web lint && pnpm web:i18n       # i18n: no new gaps from fleet phrases
```

**Migrations**: `backend/.../V15__fleet_vehicle_registry.sql` (7 tables + RLS + 53 permissions);
`license-server/.../V3__fleet_feature.sql` (adds `fleet` to enterprise-yearly and perpetual-onprem).

**Configuration**: `ENABLE_FLEET=true` on the API; the organization's module `fleet` on (Settings → Modules) and the
`fleet` feature licensed (Developer Settings shows "Fleet & Logistics").

**Click-through** (as an administrator)

1. Sidebar → **Fleet** → Fleet Overview: tiles show 0 vehicles; **Vehicle Classes** lists 11 default classes.
2. Vehicles & Equipment → Register vehicle: class Tipper truck, plate `uat 123x`, payload 25, volume 18, axle 6x4,
   opening odometer 1000 → saved as `VEH-0001`, plate shown `UAT 123X`, status Active. Without a plate → error
   "A registration plate is required for road vehicles and trailers".
3. Register an Excavator (no plate, opening engine hours 3500, ownership Hired + a partner) → meter type engine hours.
4. List: filter by class / status / ownership, search by plate; pagination and sort by code.
5. Detail → Meters: record 1500 km, then 1200 km → refused ("lower than the previous reading of 1500 km … Request a
   meter correction instead."). Correct the 1500 reading to 1150 → "sent for approval"; Approvals inbox shows a
   "Fleet Meter Corrections" request; the requester cannot approve it; a second user with `fleet_vehicle:approve_meter`
   approves → the correction is Applied within seconds (or "Check approval"), the original reading is struck through
   when "Show corrected originals" is on.
6. Meters → "Odometer replaced" at 0 → after approval the meter shows 0 km, lifetime continues from the old value.
7. Change status → Breakdown (reason required) → In workshop → Active; Status history lists each change with meters.
8. Compliance: add Insurance expiring in 10 days (state Expiring soon), Registration expired yesterday (Expired) →
   banner on the detail page; Fleet → Compliance & Renewals lists both; Renew the insurance → old row "Renewed".
9. Photos & documents: add a photo (camera on a phone), add a PDF, attach a scan to the insurance row; open / remove.
10. Link a fixed asset (Edit → Fixed asset search) → Fixed asset tab shows book value and depreciation lines.
11. Dispose the vehicle (reason) → edit / status / readings are refused.
12. As a user with only `fleet_vehicle:read`: no create / edit / record buttons; API answers 403 for writes.

**Open doubts**: default GL account codes for 1.2+ (ADR-S009 §6) need the accountant's confirmation; the reminder job
for expiring documents arrives with 1.9 (the query exists now).

## Phase 1.2 — Fleet: drivers & operators (ADR-S009)

Built and verified in the main checkout: full backend build against local Postgres 16 (see `docs/BUILD_LOG.md` 1.2 row
for the test count); web typecheck green, lint 0 errors (no new warnings), i18n: every 1.2 phrase translated in ti / lg /
sw (no new gaps).

**Commands**

```bash
cd backend && ./gradlew test --tests 'com.inventoryerp.fleet.*'   # + DriverEligibilityPolicyTest, AllowanceCalculatorTest, FleetDriversIT, FleetClaimsIT
cd backend && ./gradlew test --tests 'com.inventoryerp.hr.*' --tests 'com.inventoryerp.invoicing.InvoicingIT'   # payroll + expenses unchanged
pnpm web:typecheck && pnpm --filter @erp/web lint && pnpm web:i18n
```

**Migration**: `V16__fleet_drivers_operators.sql` (9 fleet tables + hr `HrPayrollInput`, RLS; no new permissions).

**Click-through** (administrator + a second user with `fleet_driver:read`, `fleet_driver:approve_allowance`,
`expense:post`)

1. HR → add an employee; Fleet → **Drivers & Operators** → Add driver: pick the employee, licence `dl 123` classes
   `c, ce`, expiry next year, qualified class Tipper truck → `DRV-0001`, licence shown `DL123`, classes `C, CE`.
2. Detail → Licence & qualifications shows the documents card (licence Valid). Set the medical certificate to
   yesterday → Expired; the list filter "Expired documents" finds the driver.
3. Vehicle detail → **Driver assignments / check-outs** → Assign driver: an expired licence / unqualified class /
   vehicle in workshop shows the reason and the Assign button stays disabled; the API answers with the same message.
4. Assign primary driver A, then primary driver B → A's row is Ended ("Replaced by driver …"). A temporary cover
   without an end is refused.
5. **Check-out / Check-in** (on a phone): pick the truck → choose the driver, odometer above the current reading, fuel
   75 %, condition Fair, damage Mirrors, take a photo → "Checked out (CHK-…)". A second check-out of the truck or of the
   same driver on another truck → 409 with the vehicle / driver named. Check in with a lower odometer → refused; with a
   higher one + "Report a breakdown" → closed, distance shown, the vehicle is Breakdown (status history source
   `check_in`), meter readings show sources check_out / check_in.
6. Fleet → **Allowance Rules** (`fleet:settings`): per day 20,000 general, 25,000 for Tipper truck, per night 35,000;
   a second general per-day rule overlapping → refused.
7. **Driver Claims** → New claim for the driver with the tipper: 2 × per day + fuel 15,000 → total 65,000 (tipper rate);
   add a receipt; Submit → Waiting for approval; the submitter's Approve → 403; the second user approves.
8. Pay via expenses (bank account) → Paid; Expenses shows `EXP-…` POSTED / PAID; the journal debits 6440 on the
   truck's cost centre and credits the bank (balanced). Reverse → the expense is VOID, the journal reversed, the claim
   Approved again.
9. Export to payroll → Exported; HR → Payroll: a run for a period containing the claim date lists "Driver claim CLM-…"
   as an allowance; approving the run debits 2185 for that amount (2185 nets to zero). Reversing the claim while the
   input is in the run is refused; reverse the run first.

**Open doubts**: account codes 6440 / 2185 and the "non-taxable by default" payroll treatment need the accountant's
confirmation; claims are not yet linked to trips (1.3 adds the foreign key on `tripId`).
## Phase 1.6 — Fleet: maintenance (schedules, work orders, downtime, inspections)

```bash
cd backend && ./gradlew test --tests 'com.inventoryerp.fleet.*'   # + FleetMaintenancePoliciesTest, FleetMaintenanceIT (8)
pnpm web:typecheck && pnpm --filter @erp/web lint && pnpm web:i18n  # no new i18n gaps from the maintenance phrases
```

Click through (ENABLE_FLEET=true, admin): Fleet → Service Templates → new template for Tipper (10 000 km / 180 days,
lead 500 km) → Apply to the class; record an odometer reading past the due point → Services Due shows it overdue →
Raise work order → Start (vehicle "in workshop", downtime open) → Issue part (stock − qty; journal Dr 6420 on the
vehicle cost centre / Cr stock) → Book labour → Add workshop bill (draft vendor bill in AP; journal Dr 6420 / Cr 2168)
→ tick tasks → Complete (vehicle active, schedule re-based). On a phone: Fleet → Inspections → New inspection → fail
"Brakes" → vehicle detail → Inspections shows "Blocked from dispatch" until the defect's work order is completed.
Downtime → Report breakdown; Availability & Reliability shows availability / MTBF / MTTR.

## Phase 1.5 — Fleet: fuel & energy (ADR-S009)

- Migration: `V19__fleet_fuel.sql` (placeholder; renumbered at merge). 34 routes under `/fleet/fuel*`.
- Tests: `./gradlew test --tests 'com.inventoryerp.fleet.*'` — `FuelPoliciesTest`, `FleetFuelIT` (9), `FleetRegistryIT`.
- Click-through (ENABLE_FLEET=true, admin):
  1. Fleet → Fuel Tanks → Add tank on an inventory location with a diesel product (litres); receive 1,000 L with a goods
     receipt (Procurement → Goods receipts, ad hoc, warehouse = tank location) → tank level 1,000 L.
  2. On a phone: Fleet → Fuel Logs → Record fuel → vehicle, Site tank, 120 L, odometer → Save & post → stock 880 L;
     log detail shows Dr Fleet Fuel & Energy (vehicle cost centre) / Cr stock, balanced.
  3. Record a station fill paid by fuel card (card registered under Fuel Cards: only `**** 1234` is shown) → Dr fuel /
     Cr Fuel Card Payable; by cash → an expense "Fleet fuel" paid in Expenses; on credit → unpaid expense, pay it in
     Expenses → the payment line carries the vehicle cost centre.
  4. Two full-tank fills → efficiency (L/100 km) on the log and on the vehicle's Fuel tab chart; a fill above the
     tank size or with the same odometer appears under Fuel Anomalies → Acknowledge with a note.
  5. Tank detail → Record dip 990 L → Reconcile (reason) → adjusted at once; dip 700 L → waits for approval; approve
     as another user with `fleet_fuel:post` (Approvals inbox) → adjusted, stock follows.
  6. Reverse a posted log → GL mirrored, tank stock back, expense voided.
- Expected GL: every entry balanced; mapping keys `fleet_fuel_expense` (6410) and `fleet_fuel_card_payable` (2165)
  created on first use.
- Doubts: expense workflows configured by the client make supplier-credit fuel wait for approval in Expenses (by design).

## Phase 1.3 — Fleet: trips, delivery planning & dispatch (ADR-S009)

- Migration: `V931__fleet_dispatch.sql` (placeholder; renumbered at merge). 55 routes (`/fleet/customer-sites`,
  `/fleet/delivery-orders`, `/fleet/dispatch-board`, `/fleet/dispatch/*`, `/fleet/trips`, `/fleet/delivery-discrepancies`,
  `/fleet/transporter-rates`, `/fleet/delivery-charge-rules`, `/fleet/product-load-factors`).
- Tests: `DispatchPoliciesTest`, `FleetDispatchIT` (9), `FleetClaimsIT` (trip FK).
- Click-through (ENABLE_FLEET=true, ENABLE_ORDERS=true, admin):
  1. Fleet → Customer Sites → add a site for a customer (GPS, gate notes, zone, 42 km). Dispatch Settings → Load
     factors: aggregate 1 t per unit; Delivery charges: per tonne 2,000 for the customer.
  2. Create a sales order (Orders) for 50 t aggregate → Fleet → Delivery Orders → Create from sales order → plan loads
     with a 20 t tipper + driver → 3 trips (20 / 20 / 10 t). An expired-licence driver or a 10 t truck is refused.
  3. Trip 1: Start loading (odometer) → Loaded quantity 19.5 → Dispatch → stock −19.5 t, Dr COGS / Cr stock balanced;
     print the delivery note (PDF); a second dispatch is refused.
  4. On a phone (driver user): My Trips → trip → POD: delivered 19, reason spillage, receiver, signature, photo →
     trip delivered; Delivery Discrepancies shows the 0.5 t shortage; the recorder cannot approve, a dispatch manager
     can → billable 19 t.
  5. Close the trips (end odometer) → distance from the odometer. Short-close the order → Create invoice → draft
     invoice with 49 t + transport charge 98,000 → post it in Invoicing: AR / revenue / 4250, stock does not move again.
  6. Posted-invoice source → delivery → shortage approved → a draft credit note against the invoice appears in Credit
     Notes (not posted). A returned load puts its stock back (Dr stock / Cr COGS).
- Doubts: billing a delivered sales order through the orders module's `generateInvoice` would deduct stock again —
  invoice deliveries from the delivery order.
