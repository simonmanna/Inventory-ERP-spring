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
