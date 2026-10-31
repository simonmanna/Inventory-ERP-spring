# Owner verification checklist (Track C — construction materials)

> **Read this first. Everything merged after commit `bd3f34b` has never been compiled or run.** That is the rest of
> Phase 2 and all of Phases 3, 4, 5 and 6: more than 90 commits, about 70,000 lines and 32 migrations (V30 to V120). No
> Gradle, no tests, no tsc, no lint, no server start. Phase 6 reviewed it by reading and with static scripts, which do not
> replace a build. Where a section below says "verified" or "green", it describes code at or before `bd3f34b`.
>
> **A shared database must only be migrated from V31 after the whole set is merged.** Flyway runs with `out-of-order`
> off, so a database that has taken a higher version refuses a lower one that arrives later, and the numbers have gaps
> by design. Never migrate a shared database from a partial branch; use a throwaway database for trials.
>
> Work top to bottom through the [run-book](#run-book-build-to-backup-drill) at the end of this file. If step 1 fails, go
> to [first build triage](#first-build-triage).

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

- Migration: `V22__fleet_dispatch.sql` (placeholder; renumbered at merge). 55 routes (`/fleet/customer-sites`,
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
## Phase 1.4a — Fleet: weighbridge core (ADR-S009)

- Commands: `./gradlew test --tests 'com.inventoryerp.fleet.application.WeighbridgePoliciesTest' --tests 'com.inventoryerp.fleet.FleetWeighbridgeIT'`; web `pnpm web:typecheck`, `pnpm web:i18n`.
- Migration: `V23__fleet_weighbridge.sql` (placeholder, renumbered at merge).
- Screens (ENABLE_FLEET=true): Fleet → Weighbridge Settings: add a station (receiving store) and a *Simulator* indicator, *Set weight* 12 000 → *Test read* shows 12 000 kg.
  New ticket (sale delivery, 20 t of a TON product) → *Capture first weight*; set 32 050 → *Capture second weight* → completed, net 20 050 kg / 20.05 t, *Print ticket* opens the PDF; a second print says "Copy 2".
  Set 33 000 on another ticket → exception; approve as a different user in Weighbridge Exceptions (the operator sees "Your own ticket").
  Inbound ticket against a PO → completed ticket shows a draft goods receipt in Purchases → Goods receipts.
- Expected GL: none from the weighbridge (GRN posting is procurement's).

## Phase 1.4b — Fleet: weighbridge ↔ dispatch + Phase 1 exit E2E

- Commands: `./gradlew test --tests 'com.inventoryerp.fleet.FleetWeighbridgeDispatchIT' --tests 'com.inventoryerp.fleet.FleetPhase1EndToEndIT'`; web `pnpm web:typecheck`, `pnpm web:i18n`.
- Migration: `V24__fleet_weighbridge_dispatch.sql` (placeholder, renumbered at merge): ticket FKs to delivery order / trip, one live ticket per trip, `FleetWeighbridgeRequirement`.
- Click-through (ENABLE_FLEET=true, ENABLE_ORDERS=true, admin + a second user with `weighbridge:approve_exception`):
  1. Weighbridge Settings → *Dispatch rule* → add a rule for the branch (or whole organization): *Bulk products*.
  2. Sales order 20 t of a TON product (with the branch) → Delivery order from the sales order → plan loads on a 30 t
     tipper + driver. The trip says "Weighbridge required for dispatch"; *Dispatch* is refused, *Loaded quantity* is hidden.
  3. Trip → *Weigh* → ticket form pre-filled (truck, driver, customer, product, 20 TON, DO) → register → simulator 12 000 →
     first weight → 32 050 → second weight → completed, "Fed the trip"; the trip is *weighed* with 20.05 t and lists the
     ticket (net, variance, link).
  4. *Dispatch* → stock −20.05 t (Dr COGS / Cr stock balanced) → POD 20.05 → close (end odometer) → Delivery order →
     *Create invoice* with the transport charge → post: AR / revenue / 4250 balanced, stock unchanged.
  5. Void the ticket of the dispatched trip → refused ("its stock is issued").
  6. Another trip: weigh 18 800 kg for 20 t → exception, trip stays unfed and cannot be dispatched; the operator cannot
     approve; the second user approves → trip weighed 18.8 → dispatch issues 18.8.
  7. Another trip: weigh → void the ticket → trip back to *loading* with no loaded quantity; weigh again → void with
     reissue → reissued ticket feeds the trip.
- Expected GL: only the 1.3 postings (dispatch issue at the weighed quantity, invoice posting); the weighbridge itself posts nothing.
## Phase 1.9 — Fleet: costing, profitability, reports, freight bills, reminders (ADR-S009)

- Commands: `./gradlew test --tests 'com.inventoryerp.fleet.application.FleetCostingPoliciesTest' --tests 'com.inventoryerp.fleet.FleetCostingIT' --tests 'com.inventoryerp.fleet.FleetRemindersPayrollIT' --tests 'com.inventoryerp.fleet.FleetDemoSeedIT' --tests 'com.inventoryerp.fleet.FleetIncidentsIT' --tests 'com.inventoryerp.hr.*'`; web `pnpm web:typecheck`, `pnpm web:i18n`.
- Migration: `V25__fleet_costing.sql` (placeholder, renumbered at merge) — also widens `HrPayrollInput.kind`.
- Demo: `SEED_FLEET_DEMO=true SPRING_PROFILES_ACTIVE=seed` on an empty database, `ENABLE_FLEET=true ENABLE_HR=true` →
  Fleet Overview shows the month's KPI tiles (≈ 24 deliveries, on time 100 %, transport cost / t, availability).
- Click-through (admin):
  1. Fleet Reports → Cost of ownership (last 7 days): TRK-01..03 with fuel, insurance and licence fees, tyres; LDR-01
     with maintenance (oil + labour). By class / month. Export → CSV, Excel, PDF open.
  2. Delivery profitability → by trip: revenue, transport charge on the invoiced order, product cost, trip cost (per
     km), margin; by customer. Deliveries & on time, Tonnes (by product), Transport cost per tonne (own vs
     transporters), Utilisation, Fuel (L/100 km), Renewals (road licences due in 10 / 30 / 50 days).
  3. Freight Bills → the week's draft bill of Lakeside Haulage (3 trips × 87,500) → Match with invoice "LH-001" for
     262,500 → billed, AP draft bill in Vendor Bills, journal Dr 6460 / Cr 2169 balanced. Match another bill 20 % over →
     disputed; a user with `fleet_freight:approve_variance` accepts. Cancel a billed bill → reversal; trips billable again.
  4. Freight Bills → Inbound freight: pick a posted goods receipt + transporter, invoice 150,000 → landed cost voucher
     posted, the product's average cost rises; cancel reverses it.
  5. Fleet Reminders → Run now: one reminder per expiring document / licence / tare / due service; Run now again → 0
     sent, all "already sent today"; notifications bell shows them.
  6. Traffic Fines → charge a fine to a driver (active employee) → HR → payroll run: the fine is a deduction line, net
     pay reduced; approve → journal credits 2186; the fine shows settled (after the event is processed). Reverse the
     run → fine charged again.
- Expected GL: freight accrual Dr 6460 / Cr 2169 (+ variance on 6460); AP bill Dr 2169 / Cr supplier when posted;
  inbound landed cost Dr inventory / Cr clearing, generated bill Dr clearing / Cr supplier; payroll Cr 2186 for fine
  deductions. No posting from reports or reminders.
- Doubts: account codes 6460 / 2169 / 2186 are proposals; own-fleet trip cost is analytic only; reports are computed on
  the fly (consider a monthly snapshot for very large fleets).

## Phase 2.1 — Multi-stage production & WIP between stages (ADR-S011)

- `cd backend && ./gradlew test --tests 'com.inventoryerp.manufacturing.*'` (new: `MultiStageProductionIT`,
  `JointCostAllocatorTest`, `StageYieldCalculatorTest`, `OrderRollupTest`; `ManufacturingIT` matrix now 131 routes).
- Migration `V26__manufacturing_multistage.sql` (placeholder; renumbered at merge).
- Recipes (BOM) → a BOM with a routing → "Stages (routing set-up)": set an intermediate + stage location on stage 1,
  manual issue on stage 2, QC gate where needed; "Co-products, by-products & waste": add a co-product and a by-product.
- Orders → new order for that BOM → "Run in stages" → Confirm → per stage: Output (partial, then "completes"), Issue
  on manual stages, Cost; gated stage: Pass / Fail QC. Expect WIP per stage to return to 0, finished goods in stock.
- Manufacturing → Stage WIP: stage rows of the open order, GL WIP balance vs expected, default allocation setting.
- Reverse the latest posting, then "Reverse all stages": stock and WIP return to where they were.
## Phase 2.2 — Quarry & extraction (ADR-S012)

- Migrations: `V28__quarry.sql` (placeholder; 19 tables + 19 permissions), licence server `V4__quarry_feature.sql`.
- Env: `ENABLE_QUARRY=true` (backend) and the `quarry` licence feature; switch the module on in Settings → Modules.
- Tests: `g.py --db c_quarry test --tests 'com.inventoryerp.quarry.*'` (QuarryIT, QuarryCalculatorsTest,
  QuarryModuleGateTest) and `--tests 'com.inventoryerp.kernel.licensing.LicensingIT'`.
- Click through: Quarry → Quarry Sites → add a site, a zone + bench, a ROM stockpile (location auto-created);
  Materials & Grades → map a granite product (AVCO); Quarry Settings → fuel / labour rates, an EXCAVATOR class rate,
  a royalty per net tonne. New extraction log (on a phone): 10 loads of a 20 t tipper, an excavator 8 h + 100 L,
  15 t overburden → Save & post → the log shows net tonnes, cost per tonne and a balanced entry (Dr 1400 / Cr 5361 +
  2177); Quarry → ROM Stockpiles shows the tonnes at that cost. Reverse it → stock back to zero.
- Explosives: Magazines & Explosives → add a magazine, register an explosive product, receive it with a goods receipt
  into the magazine location; Drilling & Blasting → plan a blast (pattern → estimated tonnes), Request explosives →
  approve it as another user in Approvals → Issue from magazine as the keeper (the requester is refused) → Record
  firing → Reconcile return → Close blast; an extraction log linked to the blast picks up its cost per tonne.
- Reports: Quarry Reports → extraction vs target, cost per tonne (by month / week / day / site), losses, royalties.

## Phase 2.4 — Concrete block / precast manufacturing (ADR-S014)

- `cd backend && ./gradlew test --tests 'com.inventoryerp.manufacturing.PrecastManufacturingIT' --tests
  'com.inventoryerp.manufacturing.application.BatchScalerTest' --tests
  'com.inventoryerp.manufacturing.application.MaturityCalculatorTest'`; `ManufacturingIT` matrix now 157 routes.
- Migration `V29__manufacturing_blocks_precast.sql` (placeholder; renumbered at merge) — adds `minCuringDays` to
  `RoutingStageSetup` / `ProductionOrderStage`, `qcPassDueAt`, and five `Precast*` tables.
- Config: `PRECAST_CURING_CRON` (default every 15 minutes) for the curing tick.
- Manufacturing → Precast set-up: a block spec (units per pallet 100, second-grade product) and a curing yard.
- Mix designs → new mix (100 blocks / batch: cement 50 kg, sand, aggregate; cement 50 kg / bag; 7 curing days) →
  scaling calculator: 1,000 blocks → 10 batches, 10 bags, 10 pallets → Activate.
- Precast orders → new order (basis units 1,000) → status confirmed, two stages (pressing, curing gated).
- Order → Pressing run (good + broken, last run) → a curing batch appears; cement / sand stock drops by the recipe.
- Curing board (phone width): batch card, reading, move; Cube is refused before the curing end date. For a quick test
  set the pressing date 8+ days back. Cube (good + second grade + broken = batch quantity) → good blocks wait in
  QC-QUARANTINE, second grade in the output location.
- With a quality in-process plan on the mix design's checkpoint code: Quality → lot → enter the 7-day strength →
  Release → batch `released`, blocks in finished goods; without a plan use Gate → Passed.
- Order → costing: cost per acceptable block = FG value ÷ good blocks; FG + second grade + abnormal scrap = total cost;
  Stage WIP report back to zero; journals with `sourceType = production_stage` balanced.
- Labels: one per pallet with a scannable QR. Reports: cost per block by product, breakage by reason.
- Doubts: the stage-level gate means a later pressing run of the same order delays the gate hold of earlier batches
  (recommend one order per day / shift); reversing only a gate release leaves the batch `released`.

## Phase 2.7 — Shop-floor execution & data capture (ADR-S015)

- `cd backend && ./gradlew test --tests 'com.inventoryerp.manufacturing.*'` (new: `ShopFloorExecutionIT`,
  `OeeCalculatorTest`, `ShiftClockTest`, `MachineEventParserTest`, `MachineAdaptersTest`; `ManufacturingIT` matrix now
  172 routes); web `pnpm web:typecheck`, `pnpm web:i18n`, `pnpm --filter @erp/web lint`.
- Migration `V30__manufacturing_shop_floor.sql` (placeholder; renumbered at merge). New permissions
  `shop_floor:approve`, `shop_floor:machine_data` (granted to Administrator by the migration).
- Click-through (admin, `ENABLE_MANUFACTURING=true`, ideally on a tablet / phone width):
  1. Manufacturing → Shop-floor Setup → Shifts: "Load Day / Evening / Night"; Downtime reasons: "Add standard
     reasons"; Work centres: set an ideal rate (e.g. 120 t/h), machine code, meters `hours, kwh`.
  2. Orders → a confirmed order whose routing operations have this work centre → Work orders → Generate (or
     "Prepare work orders" on the terminal).
  3. Operator Terminal → pick the work centre → Clock on → Start → Stop (pick "Mechanical breakdown") → the red
     banner shows the stop; Shop-floor Live shows the centre **Down** → Resume → Report output (good + scrap + scrap
     reason; on a staged order tick "completes the stage" → stock / WIP move, see the order's Stages panel) → Meter
     reading → Finish.
  4. Shop-floor Setup → Machine data → "Poll simulator" → the terminal lists machine counts / stops to confirm; confirm
     one (correct the count), reject one.
  5. Shift Reports → the shift's report: OEE tiles, output, downtime, crew, meters → Submit; as a *different* user with
     `shop_floor:approve` → Approve (or Reject with a reason → open again). After approval the terminal refuses records
     for that shift.
  6. OEE → last 7 days: per work centre and per shift A / P / Q / OEE.
- Expected GL: only staged-order output postings (Dr stock / Cr WIP etc., `sourceType = production_stage`) and the
  work-order labour absorbed by the stage engine; nothing from downtime, clock entries, meters or machine drafts.
- Doubts: four-eyes approval may be too strict for a one-supervisor plant; the REST adapter's gateway contract
  (`GET /events?machine=&since=&until=`) is a proposal; OEE ignores shifts with no record at all.

## Phase 2.3 — Crushing, screening & aggregate processing (ADR-S016)

- Migration `V31__manufacturing_crushing.sql` (placeholder; 10 tables + 6 permissions).
- Tests: `g.py --db c_crush test --tests 'com.inventoryerp.manufacturing.CrushingIT' --tests
  'com.inventoryerp.manufacturing.application.CrushingCalculatorTest'`; `ManufacturingIT` matrix now 167 routes.
- Prerequisites: Quarry → a site with a ROM stockpile holding ROM stone, and product stockpiles (kind *product*) for
  20 mm / 10 mm / dust; products for each fraction (AVCO, stock-tracked).
- Production → Crushing Plants → New plant: site, default feed stockpile, 150 t/h, diesel / kWh / labour rates; stages
  Primary (jaw, engine hours + kWh), Secondary (cone, kWh), Screening (screen, conveyor) → Save: units appear as work
  centres `<PLANT>-<UNIT>`, a routing "Crushing circuit … v1" exists. New grading profile: 20 mm main 40 % → its pile,
  10 mm co-product 25 %, dust by-product 20 % at NRV, scalpings waste 8 % stage 1 not stocked → Activate (BOM
  "… (system)" active, expected recovery 93 %).
- Crushing Runs → New crushing run on a phone: feed 1000 t, outputs 390 / 250 / 200 / 80, meters, crew 16 h, wear
  parts → Save draft → detail shows yield 92 % vs 93 %, estimated cost → Post run: ROM −1000 t, piles +390 / +250 /
  +200, GL entries balanced, cost per tonne per fraction; Reverse → stock back.
- Aggregate Stockpiles → Survey a pile (volume × density) → Submit → approve as another user (the submitter is refused)
  → stock adjusted, GL entry; Reverse restores it.
- Crushing Reports: yield and loss, cost per tonne (product, plant, by kind), plant hours / throughput / utilisation.
- Doubts: normal loss is measured on the last stage's input; system BOMs are active without the BOM approval gate.

## MFG-1 — Advanced planning & scheduling (ADR-S017)

Nothing here has been compiled or run. Run `PlanningIT`, `PlanningCalendarTest`, `DemandNettingTest` and
`CapacityCalculatorTest` first. Migration `V40__manufacturing_planning.sql`.

- `/manufacturing/planning-board`: Demand tab lists delivery orders in priority order with covered and uncovered
  quantities. Pick a location, create a plan from the shortfall, confirm it at `/manufacturing/planning`.
- Order priorities tab: the order from that plan shows the customer, the due date and the priority. Press Schedule.
- `/manufacturing/gantt`: drag a bar onto a closed day or a night. It lands on the next working minute.
- `/manufacturing/capacity`: load, bottleneck badge, crushing circuit t/h and the aggregate plan per grading profile.
- `/manufacturing/shift-plans`: add a shift plan, publish it, confirm it can no longer be deleted; add a target.
- `/manufacturing/plan-performance`: orders on time, production vs plan and target, shortfall.
- Curing order: set minimum curing days on a stage and check the 7-day gap between the two bars.

**Doubts**: curing and quarantined stock counts as supply; drag-and-drop was never tried in a browser;
Tigrinya and Luganda phrases are machine-quality and need a native reader.

## MFG-2 — Manufacturing costing & analytics (ADR-S018)

Nothing here has been compiled or run. Run `ManufacturingCostingIT`, `CostAbsorptionCalculatorTest` and
`VarianceCalculatorTest` first. Migration `V41__manufacturing_costing.sql`.

- `/manufacturing/costing` → Rates: set labour, machine and overhead rates on a work centre.
- Run a work order, then open its order → "Actual cost and variances": the amounts due match hours × rates. Press Absorb
  into WIP, reopen, confirm nothing is due and the journal has Dr WIP / Cr overhead absorbed.
- Reverse that stage posting: the amount is due again.
- `/manufacturing/costing`: cost by shift, plant, product and month; variances of completed orders; gross margin.

**Doubts**: absorption must be pressed before the stages close; margin is an estimate, not booked COGS.

## MFG-3 — Phase 2 end-to-end tests

Written, never run. Run `Phase2ExitIT`, `PrecastManufacturingIT.actualCostPerAcceptableBlock` and
`MultiStageProductionIT.multiLevelTraceability`. A failure may be the test's fixture rather than the product. The blast in
exit (a) is planned, not fired.

## MFG-4 — Construction manufacturing demo seed

Written, never run. Run `ManufacturingDemoSeedIT`, then start once with `SEED_MANUFACTURING_DEMO=true` and the `seed`
profile on a throwaway database.

- `/quarry/...`: site `DM-Q1` with ROM and three product stockpiles, rising ROM stock.
- `/manufacturing/crushing`: about 26 posted runs, cost per tonne in the reports.
- `/manufacturing/precast/orders`: about 8 orders; recent batches still curing, older ones released.
- `/manufacturing/plan-performance`: the month's plant and block targets against actuals.

## Phase 3 — Requirements verification (`docs/requirements/GAP_ANALYSIS.md`)

Nothing below was clicked, compiled or run in this pass: the matrix comes from reading code and existing tests. Use
this list to confirm the `present-verified` screens of the core ERP. Fleet, quarry, crushing, precast, shop-floor and
quality screens have their own sections above.

**Commands**: none added. No migrations, no new tests.

**Click-through** (as an administrator)

- Finance
  - Accounts and Journal Entries: create a journal, post it as a second user; `/trial-balance` stays balanced.
  - `/budgets` → New budget with lines → `/budgets/:id` shows budget vs actual and variance; Approve locks the lines.
  - `/fiscal-periods` → Close a period: later postings into it are refused; Reopen reverses the closing entry.
  - `/consolidated-report` shows one column per branch; `/cost-center-accounting` shows the cost centre matrix.
  - `/fixed-assets/register` → register, depreciate and dispose of an asset.
- Procurement
  - `/procurement/purchase-orders/new` → receive → `/procurement/goods-receipts` → `/procurement/three-way-match`.
  - `/procurement/debit-notes/new` → post a supplier return; `/procurement/landed-cost-vouchers/new` → allocate
    freight, insurance and duty and check the unit cost.
- Inventory
  - `/inventory/locations`, `/inventory/adjustments`, `/inventory/transfers` (approve → dispatch → receive),
    `/inventory/count`, `/inventory/waste`, `/inventory/ledger`.
  - `/inventory/reports`: reorder and stock health (aging, slow and dead stock); `/inventory-valuation` agrees with
    `/inventory-gl-tieout`.
  - `/uom`: product with a pack unit, then sell in that unit.
- Sales
  - `/crm/quotations/new` → submit → revise → convert; a discount above policy goes to approval.
  - `/invoices/new` → post → `/payments` allocate part of the invoice → residual updates; `/credit-notes/new`.
  - `/returns/new`, `/ar-aging`, `/statements`.
- HR
  - `/hr/employees` → add; `/hr/shifts`, `/hr/attendance`, `/hr/leave`.
  - `/hr/payroll/settings` → components and tax table; `/hr/payroll` → run, calculate, approve → `/hr/payslips`.
  - `/hr/advances-loans`: a loan deducts in the run.
- CRM: `/crm/leads` → convert → `/crm/pipeline` → won; `/crm/activities`; `/crm/customers/:id` shows purchase history.
- Reports and control
  - `/reports`: export a tab as CSV and PDF; `/settings/report-permissions` hides a report from a role.
  - `/approvals`: the requester cannot approve their own request; `/audit-log` shows the change.
  - `/settings/backup`: run a backup, then a restore drill.

**Doubts**: see the `present-partial` and `missing` rows. Most urgent to look at: user branch restriction is not
enforced (`BranchScopeService` has no callers), locations have no branch, credit limits apply only on quotations and
POS, and vendor bills, bank reconciliation, AP aging and stock reservations have no screen.

## Phase 4 lane D: finance and procurement (nothing here was compiled or run)

- FIN-2 Vendor bills
  - `/vendor-bills` → New vendor bill: one product line and one service line with a GL account → Save draft → Edit → Post.
  - Filter by status and supplier; search by number. A draft of a PO-linked bill with a mismatch shows the hold banner.
  - Pay supplier from the bill (bank + named account), then `/ap-aging` falls by that amount; as-of date changes buckets.
  - Void a posted, unpaid bill: the journal entry reverses.
- PROC-4 Payments and suppliers
  - `/payment-schedule`: a posted bill due in 3 days shows under Next 7 days; Plan it for +20 days and it moves to Next 30 days; Hold moves it out of the total.
  - `/procurement/supplier-performance`: receive one PO on time and one late, check on-time 50 %; a failed incoming lot lowers Quality pass.
  - `/reports` → Purchases tab: orders in the date range match `/procurement/purchase-orders`.
- FIN-1 Bank reconciliation
  - `/bank-reconciliation`: choose the bank account, import a CSV (date, reference, description, amount; money out negative); importing it again adds nothing.
  - Auto-match pairs a -70 line with a supplier payment of 70 and a +90 line with a receipt of 90, within the tolerance.
  - Match by hand from the candidates; Exclude a bank charge with a reason; Undo returns either to unmatched.
  - Payments not on a statement falls as lines match. Check the book balance against `/accounts/cash-accounts/accounts`.
- FIN-3 Statements, tax and close
  - `/profit-and-loss` and `/cash-flow` for a month with known journals: figures match a hand calculation; cash flow closing equals the ledger.
  - `/taxes`: add, edit and delete a rate.
  - `/tax-report`: Tax return pack shows 13 boxes, says whether it agrees with the ledger, and Download CSV works.
  - `/fiscal-periods` → Checklist: a draft journal shows as a warning; sign off a step, remove it; a closed period is read-only.
- PROC-1 RFQ
  - `/procurement/rfqs` → New RFQ with two lines and two suppliers → Mark as sent → Record quotation for each supplier, one in another currency with a rate.
  - Comparison marks the cheapest per line and Best on the cheapest complete quote; an expired quote cannot be awarded.
  - Award: choose a warehouse; the PO opens at the quoted prices and terms; the RFQ shows Awarded.
- PROC-2 Price agreements
  - `/procurement/price-agreements`: add an agreement; a second one overlapping the same dates is refused; End the first, then add the new price.
  - Quantity breaks: two agreements with From quantity 0 and 50; `GET /procurement/price-agreements/lookup` picks by quantity.
  - `/procurement/price-history?product=<id>`: lowest, highest, weighted average, latest; variance shows when PO and agreement share a currency.
- PROC-6 Handling
  - `/procurement/landed-cost-vouchers/new`: add a Loading & handling charge on a two-line receipt; it spreads by quantity and raises the stock cost.
## Phase 4 lane B — Approvals, procurement control, numbering

**Nothing here was compiled or run.** Compile first, then run `ApprovalControlsIT` and the suites of the touched modules.

CTRL-1, migration `V50__approval_controls.sql`:

- `/approval-workflows`: create a workflow for one branch and another without a branch. A document of that branch uses the branch workflow. A step set to Department manager only takes that manager.
- `/approval-controls` → Limits: add a limit for `purchase_order:approve` of 1,000. An approver with that permission is refused on a 5,000 request and the step stays open.
- `/approval-controls` → Separation of duties: switch on Stock adjustment. The creator of an adjustment gets a 403 on approve; another user can post it. `/audit-log`, entity `SodCheck`, shows both.
- Switch on Production loss write-off, then complete an order with zero output as the user who raised it: refused.
- Add a workflow for Configuration Changes, then flip a pair. The change appears under Held changes, the requester cannot approve it, a second person can, and the switch then moves.

PROC-3, migration `V51__procurement_budgets_delegation.sql`:

- `/procurement/budgets`: create a block budget of 1,000 for a branch, activate. Submit a PR of 600: Reserved shows 600. A 500 purchase for that branch shows an alert and is refused. Convert the PR to a purchase: Committed 600, Reserved 0.
- `/procurement/delegations`: add a Central rule for a branch. A user without `procurement_delegation:central` cannot raise a purchase there. Add a Delegated rule with a limit: the delegate can buy up to it.

PROC-5 (no migration): add a workflow for Purchase Requests, then raise and submit a PR at `/procurement/purchase-requests/new`. The requester cannot approve it. Each step moves on Approve or in `/approvals`, the card shows the step, and the last approval sets it to approved. Reject keeps the reason.

SALES-2 (no migration; needs `ENABLE_ORDERS=true`): add a workflow for Sales Orders, create an order, open it, Billing tab. It shows the approval pending and Generate Invoice is disabled. Approve as another user, then add a line: billing asks again. Approve again and bill.

CTRL-2, migration `V52__document_numbering.sql`:

- `/settings/document-numbering`: set Purchase request to prefix `REQ/{YY}/`, 3 digits. The next PR takes that number and continues the same counter. Use default restores it. Next number refuses a value at or below the current one. A format without the year on Purchase order is refused.
- Confirm an invoice and a POS sale still number as before.
## Phase 4 lane E — plant, field and integrations (built, unverified; nothing compiled or run)

**CMMS-1 fixed plant** (`cd backend && ./gradlew test --tests 'com.inventoryerp.fleet.FleetPlantIT'`)

- `/fleet/plant`: the page seeds five plant classes. Register a crusher through "Register plant" (class Crusher, a home
  location, opening hours) and see it in the list, filtered by location.
- Open the unit: Maintenance tab, add an hours schedule, read the meter past the lead, raise and start a work order. The
  unit shows `in workshop`; issue a part and labour; complete it and it returns to active.
- `/fleet/plant/costs`: maintenance, hours, cost per hour and downtime for the unit.
- Link a fixed asset on the unit. The asset detail page shows the link; dispose the asset and the unit becomes `disposed`.
- A trip assignment to the crusher is refused ("fixed plant and cannot carry a load").

**DRIVER-1 driver app** (`./gradlew test --tests 'com.inventoryerp.fleet.FleetDriverAppIT'`)

- Create a role with `fleet_trip:read` + `fleet_trip:deliver`, a user with it, and set that user on the driver's hr employee.
  Assign a truck to the driver.
- On a phone open `/driver`: the truck shows "Pre-trip check not done today". Install to the home screen; it opens at `/driver`.
- Pre-trip check: fail one item with a photo; the unit shows the defect, and the office sees it under `/fleet/defects`.
- Log fuel with a receipt photo: a draft appears in `/fleet/fuel`; a second truck's id is refused (403).
- Trips: open a dispatched trip, capture signature and photo, confirm POD.

**INT-2 telematics adapter** (`./gradlew test --tests '*RestTelematicsProviderTest'`)

- `/fleet/telematics` lists providers `mock` and `rest`. A device on `rest` with no `FLEET_TELEMATICS_URL` shows the
  "not configured" error after a sync; other devices keep syncing.
- With a vendor or gateway answering the contract in `docs/spec/fleet.md`, positions and meters arrive on the next sync.

**PORTAL-1 customer portal** (migration `V80__customer_portal.sql`; `ENABLE_PORTAL=true`;
`./gradlew test --tests 'com.inventoryerp.portal.PortalIT'`)

- As staff: `/settings/portal-accounts` → Open account for a customer.
- Sign in as that customer on the normal login page: it lands on `/portal`, with no staff menu.
- Orders, invoices (PDF opens) and statement show only that customer's documents.
- Dispatch a trip for the customer with a telematics device on the truck: `/portal/deliveries/:id` moves on its own and links
  to the truck's position; after POD it shows who received it.
- Type a staff URL such as `/fleet`: back to `/portal`. Disable the account: the next portal request fails at once.
- Check the role "Customer Portal" holds only `portal:customer`. Adding any other permission to it removes the lock on routes
  open to any signed-in user.

**INT-1 mobile money** (migration `V81__mobile_money.sql`;
`./gradlew test --tests '*MobileMoneyIT' --tests '*MobileMoneyProviderTest'`)

- `/payments/mobile-money` → Request payment, number ending in 0: "Waiting for customer"; Check now: "Received", with a link
  to the receipt. The journal shows Dr mobile money, Cr receivable.
- Number ending in 9: "Failed", no receipt. Ending in 8: stays waiting.
- Set `MOBILE_MONEY_CALLBACK_SECRET` and post a signed callback (`docs/spec/invoicing.md`): the receipt appears once, however
  many times the callback repeats.

**INT-3 bank files and labels** (migration `V82__label_printing.sql`; `./gradlew test --tests '*BankStatementParserTest'
--tests '*LabelRendererTest' --tests '*LabelsAndBankFilesIT'`)

- `/accounts/bank-import`: upload a real statement from the client's bank. Preview shows the lines and totals; check the signs
  and dates. Import, then import again: the second run adds nothing.
- `/labels`: pick products, Print PDF, scan the printed QR and Code 128 with a handheld scanner. Download ZPL and send it to a
  Zebra printer, if the client has one.
- Repeat for a stockpile and a weighbridge ticket.
## Phase 4 lane C: sales and branches (nothing compiled or run)

- UOM-1
  - `/uom`: M3, BAG, TRUCK exist.
  - Product edit → Density & Weights: sand 1.6 t/m³, bag 50 kg, truck 30 t; save, reload, values stay.
  - `/invoices/new`: line in TRUCK, price scales; post; stock falls by 30 t per truck.
  - `/orders/new`: line in TRUCK; detail shows "Entered as 2 TRUCK"; bill it; stock falls by the tonnes.
  - Inventory item detail: on hand shows the equivalents line.
  - Weighbridge ticket for a delivery line in m³: tonnes convert with the density.
- SALES-1
  - `/credit-control`: search any customer, open it, set a limit of 1000.
  - `/orders/new`: 600 passes, a second 600 is refused with "Credit limit exceeded".
  - `/invoices`: posting past the limit is refused.
  - Put on hold: credit order refused, cash order accepted.
  - Grant an override as user A: user A's sale is still refused, user B's passes.
  - POS credit sale behaves as before.
- BR-1 (high risk)
  - Before any grant: log in as a normal user with an active branch set; every list shows what it showed before.
  - POS sale and sync as a user with a grant: unchanged.
  - `/inventory/locations`: set a branch on each yard.
  - Staff → building icon: tick branch A for a clerk. The clerk sees only that branch's yards, stock, orders and invoices; a direct URL to branch B gives 404, a filter on it 403.
  - Untick everything: the clerk sees everything again.
- BR-2
  - `/reports/branches`: rows per branch and a Consolidated row; figures match `/consolidated-report` for revenue, expenses and net profit.
  - Stock value by branch follows the location form's branch.
- SALES-3
  - `/price-lists`: list Contract A for one customer, sand 90, from 100 at 80, truck price 2500.
  - `/invoices/new`: leave the price empty (API or a cleared field): the list price appears after save; a typed price stays.
  - `/orders/new`, delivery order and quotation for the same customer take the same price.
  - Another customer keeps the catalogue price.
- SALES-4
  - `/commissions`: add a 5% rule. Post an invoice with a salesperson; Calculate shows the entry; calculate again adds nothing.
  - Approve, send to payroll; run payroll for the employee: the commission appears as an allowance line.
  - Cancel a posted invoice and calculate: its entry is cancelled.
- SALES-5
  - `/reports/sales-analysis`: group by product, branch, customer, salesperson, month; totals agree with `/reports/ar/revenue-by-customer` for the same dates (ERP invoices).

## Phase 4 HR-1 — recruitment and onboarding (built, not run)

- [ ] `/hr/onboarding` → Templates: add "Safety induction", due after 3 days.
- [ ] `/hr/recruitment`: create an opening (headcount 1), set it to open, add an applicant, schedule an interview.
- [ ] Move the applicant to offer, hire: an employee appears, the opening closes, `/hr/onboarding` shows the task due 3 days after the hire date.
- [ ] A second hire of the same applicant is refused; a required task cannot be skipped.

## Phase 4 HR-2 — contract records (built, not run)

- [ ] `/hr/contracts`: create a fixed-term contract, activate it: the employee detail shows the contract end date.
- [ ] Activate a second contract for the same employee: the first becomes superseded.
- [ ] Renew: a draft starts the day after the old end date. Terminate needs a reason.
- [ ] Set an end date within 60 days: it appears under "Ending soon". The daily job at 01:20 expires it after the end date.

## Phase 4 HR-3 — configurable overtime (built, not run)

- [ ] `/hr/payroll/settings` → Overtime: add a rule, weekday 1.25, weekend 1.5, holiday 2, weekend days SUNDAY.
- [ ] Record attendance overtime on a weekday, a Sunday and a holiday, calculate a run: overtime pay uses each multiplier.
- [ ] With no active rule, overtime is 1.5 times the hourly rate, as before.

## Phase 4 HR-4 — performance review screen (built, not run)

- [ ] `/hr/reviews`: create a review with a rating, filter by employee, submit and approve it, edit it, delete it.
- [ ] The menu entry shows only with `hr:performance`.

## Phase 4 HR-5 — payroll cost allocation (built, not run)

- [ ] `/hr/payroll/settings` → Cost allocation: split a department 100 % to one cost centre, one employee 50/50 across two.
- [ ] Approve a run: the journal shows salaries debits per cost centre and branch, debits = credits.
- [ ] The report on the same tab shows the amounts by department and cost centre. Reversing the run empties it.
- [ ] With no splits at all, the journal has one salaries debit, as before.
- [ ] Accounting: a cost-centre report for the period shows the same totals.

## Phase 4 CRM-1 — complaints and service requests (built, not run)

- [ ] `/crm/cases`: create a complaint; the due time follows the priority. Filter by status, type, priority, overdue.
- [ ] On the case: add a note, assign an owner (they get a notification), start, resolve with a resolution, close.
- [ ] `/fleet/delivery-discrepancies` → Open case: a high-priority delivery complaint opens with the customer and quantities; the button again opens the same case.
- [ ] Approve the discrepancy: the case timeline shows the decision and any credit note; the case stays open.

## Phase 4 CRM-2 — customer data tests and segments (built, not run)

- [ ] Run `CrmCustomerDataIT` (contacts, reminder job, saved filters, segments, product categories).
- [ ] `/crm/customers`: filter by category, name a segment, save it, clear the filters, pick it from Segments.

## Phase 4 INV-1 — stock reservation screen (built, not run)

- [ ] `/inventory/reservations` → New reservation: pick a location and product, see on hand, reserved and available; reserve more than available: a warning shows, the save still works (soft reservation).
- [ ] Filter by status and location. Release, then Consume on another row. Check `/inventory/reservations/atp` follows.

## Phase 4 INV-2 — product attributes and grades (built, not run)

- [ ] `/products/specifications` → Grades: add C25. Attributes: add Strength (number, MPa), Size (choice 10 mm, 20 mm), one required.
- [ ] Products tab: pick a product, set grade, standard and attributes. A required attribute left blank blocks Save; the server refuses it too.
- [ ] A grade in use cannot be deleted. POS still sells the product and its JSON has no new field.

## Phase 4 INV-3 — replenishment (built, not run)

- [ ] `/inventory/replenishment` → Rules: add a rule with minimum 10 for a product with 6 in stock. Scan now: an alert shows available 6 and the suggested quantity.
- [ ] Reserve stock on `/inventory/reservations` and scan: available drops by the reservation.
- [ ] Tick the alert, Raise requisition: status goes to requested, then requisitioned with a PR number within a minute; `/procurement/purchase-requests` shows a draft, one per supplier.
- [ ] A rule with Automatic ticked does the same on a scan. Bring stock above the minimum and scan: the alert resolves.
- [ ] Dismiss needs a reason, and the rule stays quiet until stock recovers.

## Phase 5 alerts, forecast and BI (built, not run)

Set `ENABLE_ALERTS=true` and `VITE_ENABLE_ALERTS=true`, then restart. Migrations V110 and V111 must apply; V111 fails at
boot if a view names a missing column.

- [ ] `/alerts` → Settings lists 9 types. Edit Expiring documents: threshold 60, weekly, In-app. Reload: it stayed.
- [ ] Give a quarry site a licence expiring in 10 days. Scan now: one alert in the list, one bell for each `alerts:read` user.
- [ ] Scan now again: nothing new, no second bell. Set the licence to yesterday and scan: a new critical alert.
- [ ] Two sites at once: one bell with both lines, two rows in the list.
- [ ] Each type with its data: a customer over limit, an approved budget overspent, a pending weighbridge exception, a curing batch ready, an overdue CAPA, a crushing week under the minimum yield, an overdue fleet service, an expiring employee document.
- [ ] Switch a type off: Scan now leaves it out. Tick Email with no SMTP: the log shows `[DEV-EMAIL]`.
- [ ] WhatsApp: a user whose e-mail matches a partner with a WhatsApp identity and channel gets the digest once.
- [ ] Exception report totals match the list. Export CSV opens in a spreadsheet. A second organization sees none of it.
- [ ] A user without `alerts:manage` has no Edit or Scan now; the routes answer 403.
- [ ] With `app.jobs.enabled=true`, the logs show `alert scan hourly` and `alert scan daily`, and `/actuator/prometheus` shows `alerts_raised_total`.
- [ ] `/manufacturing/planning` → Forecast: products with invoiced sales show next week, trend and period forecast. Family filter narrows them.
- [ ] Demand tab: Include forecast, then Smoothed: a block product with history shows a Forecast row, less firm demand. Create plan from shortfall makes a pegged line; an aggregate with no BOM comes back as skipped.
- [ ] With Smoothed off, the demand figures match the previous release.
- [ ] `/reports/bi-exports`: every dataset downloads, with a header even when empty. A second organization's file has none of the first's rows.
- [ ] Create the `bi_reader` role from `docs/integrations.md`: `SELECT` on a `reporting` view works and `SELECT` on `"Invoice"` is refused.
## Phase 5 dashboards and KPIs (built, not compiled or run)

Nothing here has been compiled. Start with `AnalyticsIT`, `KpiMathTest`, `AnalyticsViewTest`, `ModularityTests` and the
tests of the services that gained overloads: `QuarryIT`, `CrushingIT`, `PrecastManufacturingIT`, `FleetCostingIT`,
supplier performance tests and the manufacturing costing tests. Their routes must answer as before.

- [ ] Sign in as Administrator after V100. The Analytics menu lists Dashboards Overview and ten dashboards.
- [ ] `/analytics`: headline tiles per dashboard. Change the dates and a branch; the figures move and the URL keeps them.
- [ ] For each dashboard, compare two tiles with the report they come from (`docs/spec/analytics-kpis.md` names it): for
      example Extraction cost per tonne with `/quarry/reports`, Input-to-output yield with the crushing reports.
- [ ] Click a tile with a drill-down: the list opens, pages, and each number opens its document.
- [ ] Click a row in Extraction by site, Sales by customer and Branch performance: the list narrows to that row.
- [ ] Add a role with `analytics:view` and `analytics:sales` only: the menu shows those two; `/analytics/finance` refuses.
- [ ] Restrict a user to one branch with the Staff data access dialog: figures shrink, another branch gives 403, the branch list shows
      one branch, Finance hides cash and says why, Maintenance says OEE is unavailable.
- [ ] Switch a module off for the organization: its dashboard refuses, and the overview leaves it out.
- [ ] Open a dashboard on a phone width: filters wrap, tables scroll sideways, the page does not.
- [ ] With production volumes, time `/analytics/inventory` and `/analytics/finance` over a year: inventory runs one stock
      health report per location for a branch view, finance one consolidation per month.

## Phase 7 — Construction materials demo organization (built, not compiled or run)

Written, never run. Run `ConstructionDemoSeedIT` (slow: it seeds the whole quarter), then start once on an empty
throwaway database with `SEED_CONSTRUCTION_DEMO=true`, the `seed` profile and `ENABLE_FLEET`, `ENABLE_QUARRY`,
`ENABLE_MANUFACTURING` and `ENABLE_HR` on (quality has no module switch). Sign in to organization `CMDEMO` as
`admin@construction.test` (`SEED_ADMIN_PASSWORD`, default `Admin@123`); role users have password `Demo@123`.

- A failure here is most likely a fixture or an unchecked rule in `FleetConstructionDemoSeed`, not the product. The first
  things to suspect: stock running short at `KLA-YARD` (`DEPOT_STOCK`), the block lots released before the depot transfer,
  and `orders.close` on short-weighed orders.
- Fleet: 13 units, 15 drivers, 20 sites, about 460 own and 38 transporter trips, each closed.
- Weighbridge: one completed ticket per closed trip, dated across the quarter; about one in twelve own tickets manual.
- Journal: entries of `fleet_trip` in at least three calendar months; every entry balanced (the IT checks both).
- Accounting: `wip` maps to 1420 on the account mapping screen.
- Sales: one posted invoice per closed trip, older ones paid by bank, one in five half paid; newest month unpaid.
- Driver app: `driver@construction.test` sees today's open delivery on `CM-T01`.
## Phase 6 — production hardening (reviewed by reading, nothing compiled or run)

**What the review covered.** Static scripts over the whole backend and web, calibrated on `bd3f34b`, which is known to
build: anything reported there is noise. Java syntax, imports, constructor and method arity, argument, assignment and
return types where the receiver could be inferred (about 44 % of calls; the rest are JDK, third-party or lambda
parameters), unknown identifiers, unimplemented interface methods. Spring beans, constructor cycles, conditional beans. Modulith cycles and internals. Flyway replayed in order
into a schema model. Entities against columns. Every SQL string parsed and checked for tables, columns, placeholder
counts, enum and `CHECK` values, `NOT NULL` inserts. Web imports, exports, routes and permission keys. Third-party calls
against the real jars.

**Fixed:** one compile error (`ProcurementReplenishmentService` called a record with the old field count); portal
lock-down lost when the role held a second permission; a cost lookup with no organization filter; the mock mobile money
provider could post real receipts in production; a retried credit override doubled the waiver; every client behind nginx
shared one rate-limit bucket; `VITE_ENABLE_*` never reached the Docker build. Added 14 indexes, failure metrics for the jobs
and mobile money, alert rules, the route census in `ArchitectureTests` and `tools/perf/construction-load.mjs`.

**Commands**

```bash
cd backend && ./gradlew test --tests 'com.inventoryerp.ArchitectureTests' --tests 'com.inventoryerp.portal.PortalIT'
cd backend && ./gradlew test --tests 'com.inventoryerp.invoicing.CreditControlIT' --tests 'com.inventoryerp.invoicing.Mobile*'
```

**Migration**: `V120__hardening_indexes.sql` (14 indexes, no POS table; see `docs/OPERATIONS.md` 4a).

**Click-through**

- [ ] Give the Customer Portal role a second permission (`partner:read`), wait 60 s: that login gets `/partners`, and
      `/search`, `/files`, `/approvals` still answer 403. An administrator still reaches all of them.
- [ ] Under `SPRING_PROFILES_ACTIVE=prod` with `MOBILE_MONEY_PROVIDER=mock`: Collect is refused ("not configured").
- [ ] Double-submit a credit override: one row, and the waiver is not doubled.
- [ ] Behind nginx, sign in with a wrong password 11 times from one machine: only that machine gets 429.
- [ ] Stop a provider or break a job: `erp_job_failures_total` / `erp_provider_failures_total` rise at `/api/v1/metrics`.

**Open doubts**: the route list in `docs/DECISIONS_FOR_OWNER.md` (Phase 6) needs your decision before go-live.

## POS and sync regression

`git diff --name-only 9c82a91 HEAD` touches no file under `backend/.../pos`, `backend/.../sync`, their tests, `android/`,
`web/src/{features,pages}/{pos,tables,menu}`, `reference/`, `V1` or `V2`. No POS table gained, lost or changed a column.

POS does use nine shared classes that did change, so run the suite rather than trust the diff: `ExpensesService`,
`DirectStockService`, `InventoryCountService`, `StockReservationService`, `StockService`, `DocumentBuilderService`,
`SequenceService`, `Permissions`, `DomainEvents`. Their removed lines were read: a comment, a trailing comma, re-wrapped
signatures and one query that gained an optional batch filter. Two migrations touch tables POS reads:
`InventoryLocation` gains a nullable `branchId` (V62) and `Role` gains permissions (each `UPDATE "Role"`).

```bash
cd backend && ./gradlew test --tests 'com.inventoryerp.pos.*' --tests 'com.inventoryerp.sync.*'
cd backend && ./gradlew test --tests 'com.inventoryerp.invoicing.*' --tests 'com.inventoryerp.inventory.*'  # shared spines
cd android && ./gradlew :app:testDebugUnitTest :app:lintDebug
node contract-tests/run.mjs --module pos       # Nest parity: needs both stacks, see contract-tests/README.md
pnpm vitest run tests/e2e                      # copy reference/e2e to tests/e2e first; API_URL defaults to :3001
API_BASE=http://localhost:3001 TERMINALS=50 DURATION_S=30 node tools/perf/checkout-smoke.mjs   # p95 < 150 ms
```

Then by hand on a till: PIN login, open shift, sale, split bill, tab edit, offline sale and sync, Z-report, close shift.

## Run-book: build to backup drill

Do these in order and stop at the first failure. Use a throwaway database until step 4 passes.

### 1. Build

```bash
cd backend && ./gradlew clean compileJava compileTestJava spotlessCheck
node tools/contracts2java/generate.mjs && git diff --exit-code -- backend  # generated files are current
pnpm install --frozen-lockfile && pnpm shared:build
pnpm web:typecheck && pnpm --filter @erp/web lint && pnpm web:i18n && pnpm web:build
cd license-server && ./gradlew build
```

### 2. Boot-level tests

These fail fast and name most wiring problems. Docker for Testcontainers, or a local Postgres 16 with
`max_locks_per_transaction=1024`:
`IT_EXTERNAL_DB=true JDBC_DATABASE_URL='jdbc:postgresql://localhost:5436/<db>?stringtype=unspecified' DB_USER=postgres DB_PASSWORD=postgres`.

```bash
cd backend && ./gradlew test --tests 'com.inventoryerp.ModularityTests' --tests 'com.inventoryerp.ArchitectureTests'
cd backend && ./gradlew test --tests 'com.inventoryerp.ApplicationIT'    # every migration, ddl-auto=validate, context
```

Start the app once with every `ENABLE_*` off and once with all on: conditional beans only fail in one of the two.

### 3. POS and sync first

Run the [POS and sync regression](#pos-and-sync-regression). Do not go on with a POS failure.

### 4. Full suite

```bash
cd backend && ./gradlew build     # tests + JaCoCo gate + spotless + bootJar, 6 to 8 minutes
```

### 5. Stack up

```bash
cp .env.example .env     # set JWT secrets, DB_PASSWORD, METRICS_TOKEN, BACKUP_ENCRYPTION_PASSWORD
# API modules: ENABLE_MANUFACTURING ENABLE_FLEET ENABLE_QUARRY ENABLE_HR ENABLE_ORDERS ENABLE_ASSETS ENABLE_COMMUNICATION
#              ENABLE_ALERTS ENABLE_PORTAL. Web menu, rebuilt into the image: VITE_ENABLE_MANUFACTURING VITE_ENABLE_HR
#              VITE_ENABLE_ORDERS VITE_ENABLE_ALERTS
docker compose up -d --build
docker compose logs -f api     # Flyway reaches V120, "Started Application"
```

Check `/api/v1/health/ready`, then turn each module on for the organization under Settings → Modules, confirm its licence
feature, and load the demo with `SEED_MANUFACTURING_DEMO=true` and the `seed` profile. Check the seeded GL balances.

### 6. Click-through per module

Use the sections above, in this order, because later ones depend on earlier ones:

| Order | Module | Sections |
|---|---|---|
| 1 | Masters, units, branches and data scope | Phase 4 lane C |
| 2 | Inventory, replenishment, reservations | Phase 4 INV-1 to INV-3 |
| 3 | Procurement, approvals, numbering, RFQ | Phase 4 lanes B and D |
| 4 | Finance, period close, bank | Phase 4 lane D |
| 5 | Fleet, dispatch, weighbridge | Phases 1.1 to 1.9 |
| 6 | Quarry, quality | Phase 2.2; quality from `docs/spec/quality.md` and its gates in 2.1 and 2.4 |
| 7 | Multi-stage, crushing, blocks, shop floor | Phases 2.1, 2.3, 2.4, 2.7 |
| 8 | Planning, costing, demo seed | MFG-1 to MFG-4 |
| 9 | Sales, credit, price lists, commissions | Phase 4 lane C |
| 10 | HR, CRM | Phase 4 HR-1 to HR-5, CRM-1, CRM-2 |
| 11 | Portal, mobile money, labels, field | Phase 4 lane E |
| 12 | Alerts, forecast, BI, dashboards | Phase 5 sections |
| 13 | Hardening | Phase 6 |

Each module: one document end to end, its GL entry balances, a reversal, a refused action as a user without the
permission, and a second organization sees nothing.

### 7. Performance

Seed volume first: the target is p95 < 300 ms on list and detail routes with 1M rows. Restart the API with
`THROTTLE_ENABLED=false`.

```bash
API_BASE=http://localhost:3001 node tools/perf/construction-load.mjs             # all read groups, 20 users, 30 s each
GROUPS=dispatch,weighbridge VUS=50 DURATION_S=120 node tools/perf/construction-load.mjs
WRITES=1 PERF_STATION_ID=<id> PERF_PLANT_ID=<id> node tools/perf/construction-load.mjs writes   # throwaway database
```

Look first at `/analytics/inventory`, `/analytics/finance` and the customer portal. For a slow route, use
`pg_stat_statements` (`docs/OPERATIONS.md` 7b) and the manual indexes in 4a. Turn throttling back on afterwards.

### 8. Backup and restore drill

```bash
ADMIN_EMAIL=... ADMIN_PASSWORD=... node scripts/dr-drill.mjs      # backup, restore, second API, equal trial balance
```

The drill drops its scratch database, so restore once more by hand for the new data (`POST /api/v1/admin/backups/restore`
with `{scope:"database", backupFile, targetDatabase}`) and compare live with restored:

```sql
SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE';
SELECT count(*) FROM information_schema.views WHERE table_schema = 'reporting';    -- 8
SELECT max(version::int) FROM flyway_schema_history WHERE success;                 -- 120
SELECT (SELECT count(*) FROM "WeighbridgeTicket"), (SELECT count(*) FROM "CrushingRun"), (SELECT count(*) FROM "PortalAccount"),
       (SELECT count(*) FROM "MobileMoneyCollection"), (SELECT count(*) FROM "AlertEvent"), (SELECT count(*) FROM "PriceList");
```

Open one fleet document and one label from the restored system to confirm the uploads came back. Drop the scratch database.

### 9. Release

Only with every step above green: decide the routes in `docs/DECISIONS_FOR_OWNER.md` (Phase 6), set a real
`MOBILE_MONEY_PROVIDER` or leave collections unused, then migrate the shared database once, from the merged set.

## First build triage

Expect some errors: this is the first compile of more than 90 commits. Work in this order, because one early error hides
later ones.

1. **`compileJava` fails.** Fix from the top. The review found no missing import, wrong arity or wrong argument type, so
   look first where the scripts could not: lambda parameters, generic inference, calls on third-party classes, checked
   exceptions. Likely spots: the `TenantContext.run` lambdas in `MobileMoneyService` (`Supplier` against `Runnable`),
   `CrushingReportService`, `AnalyticsDocuments`. A record that gained a field: `grep -rn 'new <Record>(' backend/src`.
2. **`compileTestJava` fails.** Tests were checked the same way but are the least-read code. Fix the test unless it shows
   a real signature mistake.
3. **`spotlessCheck` fails.** `./gradlew spotlessApply`; it only touches whitespace.
4. **`ModularityTests` fails.** Read the named dependency. Fix it through an `application` API or an event; never open an
   internal package. Unused imports do not count, but a `var` whose type is another module's internal class does.
5. **`ApplicationIT` fails on Flyway.** V31 and below are already applied: add a new migration, never edit those. A
   failing V40 or later has not been applied to the shared database yet, so fix it in place, then drop the throwaway
   database and start again.
6. **`ApplicationIT` fails on Hibernate validate.** The message names the column and type. Fix the entity, not the table.
7. **Context fails to start.** `UnsatisfiedDependency` or circular reference: fix the wiring with `ObjectProvider` where
   the module is optional. `Ambiguous mapping`: rename the newer route. Then repeat with all `ENABLE_*` flags flipped.
8. **Tests fail by the hundred.** Look for one cause first: a shared fixture, the demo seed, `ApiTestSupport`, a permission
   the seed role lacks, an expected route count. `SqlRows` returns `LocalDate` for `date` columns and `Instant` for
   `timestamp`, so a wrong cast shows up as `ClassCastException` at runtime only.
9. **`jacocoTestCoverageVerification` fails.** `build` enforces a line-coverage floor per `*.application` package
   (`coverage.application.min`, 80 % by default). It is a ratchet: never lower it. A low package usually means its
   tests did not run, or a new package such as `alerts`, `biexport` or `analytics` needs more.
10. **`web:typecheck` fails.** Same order: shared build first, then imports. The scripts confirmed imports, exports,
    routes and permission keys, not types. `pnpm web:i18n` gaps are listed per locale; nine repeated phrases are harmless.

After each fix, rerun only the step that failed, then go on. If a fix changes behaviour, log it in
`docs/DECISIONS_FOR_OWNER.md`.
