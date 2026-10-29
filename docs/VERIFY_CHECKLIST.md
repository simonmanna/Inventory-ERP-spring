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

## Phase 1.5 — Fleet: fuel & energy (ADR-S009)

- Migration: `V901__fleet_fuel.sql` (placeholder; renumbered at merge). 34 routes under `/fleet/fuel*`.
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
