# Fleet & logistics — `fleet`

| | |
|---|---|
| Phase | Track C Phase 1 (construction materials), roadmap Tier 4 |
| Nest source | none — new module (no parity constraints; house rules apply) |
| Design | [ADR-S009](../adr/ADR-S009-fleet-and-logistics.md) |
| Routes | 32 (1.1) + 37 (1.2) — tables below; 1.3–1.9 outlined |
| Feature flag | `ENABLE_FLEET` → `app.modules.fleet` (controllers + module manifest only when `true`), licence feature `fleet`, module name `fleet`, web feature key `fleet` |
| Java package | `com.inventoryerp.fleet` (`domain`, `application` are named interfaces) |
| Migrations | `V15__fleet_vehicle_registry.sql`, `V16__fleet_drivers_operators.sql` (1.2 + hr `HrPayrollInput`) |

## Purpose

Vehicles and mobile / fixed equipment of a quarrying, crushing, block-making and construction-materials distribution
business: the unit register with meters and compliance (1.1), drivers and operators (1.2), delivery planning, dispatch
and trips with proof of delivery (1.3), the weighbridge (1.4), fuel (1.5), maintenance (1.6), tyres / incidents / fines
(1.7), telematics (1.8) and fleet costing, delivery profitability and reports (1.9).

## Functional areas

| # | Area | Status | Scope |
|---|---|---|---|
| 1.1 | Vehicle & equipment registry | **done** | configurable classes, vehicle master, status machine + history, odometer / hour-meter readings with the monotonic rule, approval-gated corrections, compliance documents + expiry query, photos / documents, linked fixed asset (read-only), lookups, overview |
| 1.2 | Drivers & operators | **done** | driver / operator profile → hr employee (licence number / classes / issue / expiry, medical, operator permit), qualified classes, expiring-documents query, eligibility policy, assignments (primary / temporary), check-out / check-in (meters, fuel, condition, damage, photos), allowance rules, claims → approval → paid via expenses or exported to hr payroll, reversal |
| 1.3 | Trips, delivery planning & dispatch | planned | customer sites, delivery orders from sales orders, dispatch board, loads within payload / volume, own fleet or transporter, trip lifecycle, delivery note, POD, stock issue once per load, discrepancies → approval → credit-note request, transport charges |
| 1.4 | Weighbridge | planned | stations, devices (`WeighbridgeAdapter` SPI: ASCII TCP / serial + simulator), stored tare with expiry, ticket workflow, tolerance exceptions with SoD approval, immutable tickets (void + reissue), inbound weighing → GRN |
| 1.5 | Fuel & energy | planned | fuel logs, cards, efficiency + anomaly flags, site tanks / bowsers (GRN in, stock issue out, dips, reconciliation), GL |
| 1.6 | Maintenance | planned | service templates / schedules (km / hours / calendar), fleet-owned work orders (parts, labour, external bills), downtime, availability, MTBF / MTTR, inspections (DVIR) → defects → work orders, dispatch block |
| 1.7 | Tyres, incidents, fines | planned | tyre positions / fitment / rotation / tread, cost per km; incidents, claims; fines charged to driver (payroll deduction) or company |
| 1.8 | Telematics SPI | planned | `TelematicsProvider` (positions, meters, events) + mock; positions feed trip distance and POD location |
| 1.9 | Costing, profitability & reports | planned | cost per vehicle / km / hour / tonne / trip, TCO, delivery profitability, transporter freight bills, utilisation, renewals / services due, exports; ShedLock reminder jobs |

## Routes (1.1)

All under `/api/v1`, module-gated (`@RequiresModule("fleet")`), JSON records / maps (never entities).

| # | Method | Path | Permission | Status | Notes |
|---|---|---|---|---|---|
| 1 | GET | `/fleet/categories` | `fleet_vehicle:read` | 200 | `includeInactive`; each with `vehicleCount`; seeds defaults on first use |
| 2 | POST | `/fleet/categories` | `fleet:settings` | 201 | 409 duplicate code |
| 3 | PATCH | `/fleet/categories/{id}` | `fleet:settings` | 200 | |
| 4 | DELETE | `/fleet/categories/{id}` | `fleet:settings` | 200 | 400 when used by vehicles (deactivate instead) |
| 5 | GET | `/fleet/overview` | `fleet_vehicle:read` | 200 | counts by status / class, compliance expired / expiring, pending corrections |
| 6 | GET | `/fleet/lookups` | `fleet_vehicle:read` | 200 | active classes, branches, inventory locations, cost centres |
| 7 | GET | `/fleet/lookups/assets` | `fleet_vehicle:read` | 200 | `search`; with the vehicle already linked |
| 8 | GET | `/fleet/lookups/partners` | `fleet_vehicle:read` | 200 | `search` (lessors, hire companies, transporters) |
| 9 | GET | `/fleet/vehicles` | `fleet_vehicle:read` | 200 | `{data, meta}`; `search, categoryId, kind, status, branchId, ownership, page, pageSize (≤200), sortBy (code, registrationPlate, make, status, createdAt), sortOrder` |
| 10 | GET | `/fleet/vehicles/{id}` | `fleet_vehicle:read` | 200 | + class, branch, site, cost centre, owner, asset, meters, compliance counts, pending corrections |
| 11 | POST | `/fleet/vehicles` | `fleet_vehicle:create` | 201 | opening odometer / hours → opening readings |
| 12 | PATCH | `/fleet/vehicles/{id}` | `fleet_vehicle:update` | 200 | `null` = unchanged, `clear: [...]` empties fields; refused when disposed |
| 13 | DELETE | `/fleet/vehicles/{id}` | `fleet_vehicle:delete` | 200 | soft delete; 400 with operating history |
| 14 | POST | `/fleet/vehicles/{id}/status` | `fleet_vehicle:status` | 201 | `{status, reason}` |
| 15 | GET | `/fleet/vehicles/{id}/status-history` | `fleet_vehicle:read` | 200 | newest first |
| 16 | GET | `/fleet/vehicles/{id}/asset` | `fleet_vehicle:read` | 200 | linked fixed asset, accumulated depreciation, book value, last 24 depreciation lines |
| 17 | GET | `/fleet/vehicles/{id}/attachments` | `fleet_vehicle:read` | 200 | with signed download `url` |
| 18 | POST | `/fleet/vehicles/{id}/attachments` | `fleet_vehicle:update` | 201 | multipart `file`, `kind` (photo / document), `title`, `complianceDocumentId` |
| 19 | DELETE | `/fleet/vehicles/{id}/attachments/{attachmentId}` | `fleet_vehicle:update` | 200 | removes the stored file too |
| 20 | GET | `/fleet/vehicles/{id}/meter-readings` | `fleet_vehicle:read` | 200 | `{data, meta}`; `meterType, includeSuperseded, page, pageSize` |
| 21 | POST | `/fleet/vehicles/{id}/meter-readings` | `fleet_vehicle:meter` | 201 | `{meterType, value, readingAt?, notes?}` |
| 22 | GET | `/fleet/vehicles/{id}/meter-corrections` | `fleet_vehicle:read` | 200 | |
| 23 | POST | `/fleet/vehicles/{id}/meter-corrections` | `fleet_vehicle:meter` | 201 | `{meterType, kind: adjust/replace, readingId?, correctedValue, readingAt?, reason}` → `pending` + `approvalRequestId` (or `applied` without an active workflow) |
| 24 | GET | `/fleet/meter-corrections/{id}` | `fleet_vehicle:read` | 200 | |
| 25 | POST | `/fleet/meter-corrections/{id}/sync` | `fleet_vehicle:meter` | 201 | apply the decided approval now; 400 while pending |
| 26 | GET | `/fleet/compliance-documents` | `fleet_vehicle:read` | 200 | expiry query: `due` = attention (default) / expired / expiring / all, `vehicleId, docType, branchId, asOf, page, pageSize` |
| 27 | GET | `/fleet/vehicles/{id}/compliance-documents` | `fleet_vehicle:read` | 200 | active first, with `state`, `daysToExpiry`, attachments |
| 28 | POST | `/fleet/vehicles/{id}/compliance-documents` | `fleet_vehicle:compliance` | 201 | |
| 29 | GET | `/fleet/compliance-documents/{id}` | `fleet_vehicle:read` | 200 | |
| 30 | PATCH | `/fleet/compliance-documents/{id}` | `fleet_vehicle:compliance` | 200 | `clear: [...]` |
| 31 | POST | `/fleet/compliance-documents/{id}/renew` | `fleet_vehicle:compliance` | 201 | new active document; old → `renewed` |
| 32 | DELETE | `/fleet/compliance-documents/{id}` | `fleet_vehicle:compliance` | 200 | soft delete |

Meter corrections are decided in the approvals inbox (`POST /approvals/{requestId}/decide`, entity type
`fleet_meter_correction`).

### Planned route groups (1.2–1.9, outline)

| Area | Base paths | Permissions |
|---|---|---|
| 1.2 | built — see "Routes (1.2)" | `fleet_driver:*`, `fleet:settings` |
| 1.3 | `/fleet/sites`, `/fleet/delivery-orders`, `/fleet/dispatch/board`, `/fleet/loads` (trips) + lifecycle actions, `/fleet/loads/{id}/delivery-note`, `/pod`, `/fleet/discrepancies`, `/fleet/transporter-rates` | `fleet_dispatch:*`, `fleet_trip:*` |
| 1.4 | `/fleet/weighbridge/stations`, `/devices`, `/tares`, `/tickets` (+ `/first-weigh`, `/second-weigh`, `/print`, `/void`), `/exceptions` | `weighbridge:*` |
| 1.5 | `/fleet/fuel-cards`, `/fleet/fuel-logs` (+ `/post`, `/reverse` — idempotent), `/fleet/fuel-tanks`, `/dips`, `/reconciliation` | `fleet_fuel:*` |
| 1.6 | `/fleet/service-templates`, `/fleet/service-schedules`, `/fleet/work-orders` (+ lines, start, complete — idempotent), `/fleet/downtime`, `/fleet/inspection-templates`, `/fleet/inspections`, `/fleet/defects` | `fleet_maintenance:*` |
| 1.7 | `/fleet/tyres`, `/fleet/incidents`, `/fleet/claims`, `/fleet/fines` (+ `/charge`) | `fleet_maintenance:tyre`, `fleet_incident:*` |
| 1.8 | `/fleet/telematics/devices`, `/fleet/telematics/positions`, provider webhook (signed) | `fleet:settings`, `fleet_trip:read` |
| 1.9 | `/fleet/reports/*` (cost, TCO, utilisation, deliveries, tonnes, weighbridge discrepancies, fuel, renewals), `/fleet/freight-bills` | `fleet:report`, `fleet_dispatch:*` |

## Routes (1.2)

All under `/api/v1`, module-gated. Money routes (`pay`, `export-payroll`, `reverse`) are `@Idempotent(required = true)`
and answer `200` like every idempotent route; other POSTs answer `201`.

| # | Method | Path | Permission | Notes |
|---|---|---|---|---|
| 1 | GET | `/fleet/drivers` | `fleet_driver:read` | `{data, meta}`; `search, status, driverType, categoryId` (qualified class), `documents` (attention / expired / expiring), `page, pageSize, sortBy (code, name, status, licenceExpiresOn, createdAt), sortOrder`; rows carry employee, qualifications, document states, open check-out |
| 2 | GET | `/fleet/drivers/{id}` | `fleet_driver:read` | + active assignments |
| 3 | POST | `/fleet/drivers` | `fleet_driver:create` | `employeeId` (hr, one live profile each), licence fields, `qualifiedCategoryIds`, medical / permit expiry; code `DRV-0001` when blank |
| 4 | PATCH | `/fleet/drivers/{id}` | `fleet_driver:update` | `status` active / suspended (reason) / inactive; `qualifiedCategoryIds` replaces; `clear: [...]` |
| 5 | DELETE | `/fleet/drivers/{id}` | `fleet_driver:delete` | soft delete; 400 with assignments / check-outs / claims |
| 6 | GET | `/fleet/drivers/{id}/eligibility` | `fleet_driver:read` | `vehicleId, on?, action?` → `{eligible, reasons[]}` |
| 7 | GET | `/fleet/driver-documents` | `fleet_driver:read` | expiring-documents query: one row per licence / medical / permit, `due` = attention (default) / expired / expiring / all, `asOf, page, pageSize` |
| 8 | GET | `/fleet/lookups/employees` | `fleet_driver:read` | hr employees with the linked driver code (`search`) |
| 9 | GET | `/fleet/assignments` | `fleet_driver:read` | `vehicleId, driverId, status (active / ended), page, pageSize` |
| 10 | POST | `/fleet/assignments` | `fleet_driver:assign` | `{vehicleId, driverId, kind: primary / temporary, startsAt?, endsAt?, notes?}`; eligibility on the start day |
| 11 | POST | `/fleet/assignments/{id}/end` | `fleet_driver:assign` | `{endsAt?, reason?}` |
| 12 | GET | `/fleet/checkouts` | `fleet_driver:read` | `vehicleId, driverId, status (open / closed), page, pageSize` |
| 13 | GET | `/fleet/checkouts/{id}` | `fleet_driver:read` | + photos (signed URLs) |
| 14 | GET | `/fleet/vehicles/{id}/open-checkout` | `fleet_driver:read` | `{checkout: … / null}` |
| 15 | POST | `/fleet/vehicles/{id}/check-out` | `fleet_driver:checkout` | `{driverId, checkedOutAt?, odometer?, hours?, fuelLevelPct?, condition?, damageFlags?, notes?, purpose?, tripId?}`; tracked meters required |
| 16 | POST | `/fleet/checkouts/{id}/check-in` | `fleet_driver:checkout` | same meters / fuel / condition / damage + `reportBreakdown` (vehicle → breakdown, source `check_in`) |
| 17 | POST | `/fleet/checkouts/{id}/photos` | `fleet_driver:checkout` | multipart `file` (image), `phase` out / in → vehicle attachment (kind photo) linked to the check-out |
| 18 | GET | `/fleet/allowance-rules` | `fleet_driver:read` | `includeInactive, basis`; with class and `usage` |
| 19 | POST | `/fleet/allowance-rules` | `fleet:settings` | `{code, name, basis: per_trip / per_day / per_night, amount, effectiveFrom, effectiveTo?, categoryId?, routeZone?}`; currency = organization's; 409 on overlap |
| 20 | PATCH | `/fleet/allowance-rules/{id}` | `fleet:settings` | basis / currency fixed; `clear: [...]` |
| 21 | DELETE | `/fleet/allowance-rules/{id}` | `fleet:settings` | deletes when unused, otherwise deactivates (`{deleted: false, deactivated: true}`) |
| 22 | GET | `/fleet/claims` | `fleet_driver:read` | `search, status, driverId, vehicleId, settlement, from, to, page, pageSize` |
| 23 | GET | `/fleet/claims/{id}` | `fleet_driver:read` | + lines, receipts, cost centre, approval request, expense, payroll input |
| 24 | POST | `/fleet/claims` | `fleet_driver:allowance` | draft `{driverId, vehicleId?, tripId?, claimDate, routeZone?, description?, notes?, lines?}` |
| 25 | PATCH | `/fleet/claims/{id}` | `fleet_driver:allowance` | draft only; date / vehicle / zone change re-prices allowance lines |
| 26 | POST | `/fleet/claims/{id}/lines` | `fleet_driver:allowance` | allowance `{basis, quantity}` (rate from the rule) or expense `{expenseType, amount}` |
| 27 | DELETE | `/fleet/claims/{id}/lines/{lineId}` | `fleet_driver:allowance` | draft only |
| 28 | POST | `/fleet/claims/{id}/receipts` | `fleet_driver:allowance` | multipart `file`, `lineId?`, `title?` (kernel file store, owner type `fleet_driver_claim`) |
| 29 | POST | `/fleet/claims/{id}/submit` | `fleet_driver:allowance` | → approval engine (auto-approve without an active workflow) |
| 30 | POST | `/fleet/claims/{id}/cancel` | `fleet_driver:allowance` | draft / rejected only |
| 31 | POST | `/fleet/claims/{id}/approve` | `fleet_driver:approve_allowance` | decides the request's current step; submitter / driver refused (403) |
| 32 | POST | `/fleet/claims/{id}/reject` | `fleet_driver:approve_allowance` | `{notes}` required |
| 33 | POST | `/fleet/claims/{id}/sync` | `fleet_driver:approve_allowance` | apply a decision taken in the approvals inbox now |
| 34 | POST | `/fleet/claims/{id}/pay` | `fleet_driver:approve_allowance` (+ `expense:post`) | `{paymentMethod, accountId, reference?, notes?}` → expense, Idempotency-Key |
| 35 | POST | `/fleet/claims/{id}/export-payroll` | `fleet_driver:approve_allowance` | `{earnedOn?}` → accrual + hr payroll input, Idempotency-Key |
| 36 | POST | `/fleet/claims/{id}/reverse` | `fleet_driver:approve_allowance` | `{reason}` → back to approved, Idempotency-Key |
| 37 | GET | `/fleet/lookups/payment-accounts` | `fleet_driver:approve_allowance` | cash / bank / mobile-money accounts with balances (expenses module list) |

Claims are also decided in the approvals inbox (`POST /approvals/{requestId}/decide`, entity type `fleet_driver_claim`).

## Permissions (all Phase 1 keys, declared in `packages/shared/src/permissions.ts`)

| Key | Granted for | Used by |
|---|---|---|
| `fleet:report` | dashboards, reports, exports | 1.9 |
| `fleet:settings` | vehicle classes, fleet thresholds, telematics / allowance configuration | 1.1 classes, 1.2, 1.8 |
| `fleet_vehicle:read` | register, detail, meters, compliance lists, lookups, overview | 1.1 |
| `fleet_vehicle:create` / `update` / `delete` | register / edit / soft delete; upload & remove files (`update`) | 1.1 |
| `fleet_vehicle:status` | status changes | 1.1 |
| `fleet_vehicle:meter` | readings, correction requests, sync | 1.1 |
| `fleet_vehicle:approve_meter` | approver permission of the default meter-correction workflow | 1.1 (approval engine) |
| `fleet_vehicle:compliance` | compliance documents (add, edit, renew, delete) | 1.1 |
| `fleet_driver:read` / `create` / `update` / `delete` | driver profiles, licences | 1.2 |
| `fleet_driver:assign` | vehicle ↔ driver assignments | 1.2 |
| `fleet_driver:checkout` | vehicle check-out / check-in | 1.2 |
| `fleet_driver:allowance` / `approve_allowance` | allowances and trip expenses (SoD) | 1.2 |
| `fleet_dispatch:read` / `create` / `update` / `cancel` | sites, delivery orders, board | 1.3 |
| `fleet_dispatch:plan` | split into loads, assign truck / driver / transporter | 1.3 |
| `fleet_dispatch:approve_discrepancy` | approve delivered-vs-ordered discrepancies (SoD) | 1.3 |
| `fleet_trip:read` / `create` / `update` / `cancel` / `close` | trips (loads) | 1.3 |
| `fleet_trip:dispatch` | loading → dispatched → in transit | 1.3 |
| `fleet_trip:deliver` | proof of delivery (driver) | 1.3 |
| `fleet_fuel:read` / `create` / `update` | fuel logs, cards | 1.5 |
| `fleet_fuel:post` | post / reverse fuel to the GL | 1.5 |
| `fleet_fuel:tank` | site tanks, dips, reconciliation | 1.5 |
| `fleet_maintenance:read` / `create` / `update` | work orders, downtime | 1.6 |
| `fleet_maintenance:schedule` | templates, schedules | 1.6 |
| `fleet_maintenance:complete` | complete work orders (parts issue, cost posting) | 1.6 |
| `fleet_maintenance:inspect` | inspections, defects | 1.6 |
| `fleet_maintenance:tyre` | tyres | 1.7 |
| `fleet_incident:read` / `create` / `update` | incidents, claims, fines | 1.7 |
| `fleet_incident:charge` | charge a fine to a driver or the company | 1.7 |
| `weighbridge:read` | tickets, stations | 1.4 |
| `weighbridge:weigh` | operator: register, weigh, print | 1.4 |
| `weighbridge:manual_entry` | key in a weight (always flagged) | 1.4 |
| `weighbridge:approve_exception` | approve out-of-tolerance tickets (never one's own) | 1.4 |
| `weighbridge:void` | void + reissue a printed ticket | 1.4 |
| `weighbridge:configure` | stations, devices, tolerances, tare validity | 1.4 |

## Data model (1.1)

All tables org-scoped (`organizationId` + RLS `tenant_isolation`), quoted camelCase, text UUID ids, timestamps
`TIMESTAMP(3)` UTC.

| Table | Purpose | Notable columns / constraints |
|---|---|---|
| `FleetVehicleCategory` | configurable classes | `code` unique per org, `kind` (road_vehicle, trailer, mobile_plant, fixed_plant, other), `defaultMeterType`, `isSystem`, `isActive`, `sortOrder` |
| `FleetVehicle` | unit master | `code` / `registrationPlate` / `vin` / `assetId` unique per org among live rows; `ownership`, `ownerPartnerId`, `payloadCapacityT` (12,3), `bodyVolumeM3` (12,3), `axleConfig`, `fuelType`, `tankCapacityL`, `meterType`, `currentOdometer` / `odometerOffset` / `odometerAt`, `currentHours` / `hoursOffset` / `hoursAt`, `branchId`, `homeLocationId`, `costCenterId`, `status`, `statusReason`, `statusChangedAt`, `disposedAt`, soft delete |
| `FleetVehicleStatusHistory` | status changes | `fromStatus`, `toStatus`, `reason`, `source`, `sourceId`, meter snapshot, `changedBy` |
| `FleetMeterReading` | odometer / hour readings | `meterType`, `readingAt`, `value`, `lifetimeValue`, `source` (opening, manual, correction, replacement, check_out, check_in, trip, fuel, work_order, inspection, telematics, import), `sourceId` (unique per vehicle / meter / source while valid), `status` valid / superseded, `correctionId` |
| `FleetMeterCorrection` | approval-gated rollback | `kind` adjust (needs `readingId`) / replace, `previousValue`, `correctedValue`, `readingAt`, `reason`, `status` pending / applied / rejected / failed, `approvalRequestId`, `failureReason`, `decidedBy/At`, `appliedReadingId`; one pending per vehicle + meter |
| `FleetComplianceDocument` | registration, insurance, … | `docType`, `documentNumber`, `issuer`, `issuedOn`, `expiresOn` (≥ issue), `reminderLeadDays` (0–365), `status` active / renewed, `renewedById`, soft delete; index on active expiry |
| `FleetVehicleAttachment` | photos / documents | `fileId` (kernel `File`, unique), `kind` photo / document, `complianceDocumentId`, `title` |

Org setting `fleet.complianceReminderDays` (default 30) — the default reminder lead of new documents.

## Data model (1.2)

| Table | Purpose | Notable columns / constraints |
|---|---|---|
| `FleetDriver` | driver / operator profile | `code` (DRV-0001), `employeeId` (hr, unique among live), `driverType` driver / operator / driver_operator, `licenceNumber` (unique, normalised), `licenceClasses` text[], `licenceIssuer`, `licenceIssuedOn` / `licenceExpiresOn`, `medicalExpiresOn`, `permitNumber` / `permitExpiresOn`, `reminderLeadDays` (default org setting `fleet.complianceReminderDays`), `status` active / suspended / inactive + `statusReason`, soft delete |
| `FleetDriverQualification` | qualified vehicle classes | `driverId` + `categoryId` unique |
| `FleetAssignment` | vehicle ↔ driver history | `kind` primary / temporary (needs `endsAt`), `startsAt`, `endsAt`, `status` active / ended, `endReason`; one active primary per vehicle (partial unique index) |
| `FleetCheckout` | check-out / check-in | `code` (CHK-000001), `assignmentId` (covering assignment), out / in meters, `…FuelLevelPct` 0–100, `…Condition` good / fair / poor, `…DamageFlags` text[], notes, `distanceKm` / `hoursUsed` (lifetime difference), `tripId` (1.3, no FK yet); one open per vehicle and per driver (partial unique indexes) |
| `FleetCheckoutPhoto` | photos per phase | `attachmentId` → `FleetVehicleAttachment` (1.1 file store), `phase` out / in |
| `FleetAllowanceRule` | allowance rates | `basis` per_trip / per_day / per_night, `amount` (14,2), `currencyCode` (organization's), `effectiveFrom` / `effectiveTo`, optional `categoryId` / `routeZone`, `isActive` |
| `FleetDriverClaim` | allowance / trip-expense claim | `code` (CLM-000001), `driverId`, `vehicleId?`, `tripId?` (**1.3 adds the FK to its trips table**), `claimDate`, `routeZone`, `status`, `totalAmount`, `currencyCode`, `costCenterId` (vehicle's at submit), approval fields, `settlement` expenses / payroll + `expenseId` / `payrollInputId` / `journalEntryId`, `settlementCount`, last reversal |
| `FleetDriverClaimLine` | lines | `lineType` allowance (basis + `ruleId`, rate × quantity) / expense (`expenseType` fuel, toll, parking, meals, lodging, repairs, loading, other) |
| `FleetClaimReceipt` | receipts | kernel `fileId` (unique), optional `lineId` |
| `HrPayrollInput` (hr) | one-off payroll earnings queued by other modules | `employeeId`, `name`, `amount`, `earnedOn`, `isTaxable` (default false), `debitAccountId` (clearing liability), `source` / `sourceId` (unique while not cancelled), `status` pending / included / cancelled, `runId` / `itemId` |

## State machines

- **Vehicle status**: `active ⇄ in_workshop ⇄ breakdown ⇄ idle` (any to any), any → `disposed` (terminal). Reason
  required for `breakdown` and `disposed`. Only `active` is operational.
- **Meter correction**: `pending → applied | rejected | failed` (failed = the approved correction no longer fits the
  meter's neighbours when applied).
- **Compliance document**: `active → renewed`; derived state `valid / expiring / expired / no_expiry`.
- **Driver**: `active ⇄ suspended ⇄ inactive` (suspension needs a reason); only `active` drivers are eligible.
- **Assignment**: `active → ended` (explicitly, or a new primary ends the previous primary at its start).
- **Check-out**: `open → closed` (check-in).
- **Claim**: `draft → submitted → approved | rejected`; `approved → paid | exported`; `paid | exported → approved`
  (reverse); `draft | rejected → cancelled`.

## Business rules (1.1)

1. Monotonic meter rule on lifetime values (raw + replacement offset): ≥ highest earlier, ≤ lowest later reading; not
   > 5 min in the future; not before the last replacement; only tracked meters; vehicle row locked per reading.
   Messages: `Odometer reading 1700 km is lower than the previous reading of 2000 km on 2026-10-29. Request a meter
   correction instead.` / `… is higher than the later reading of …`.
2. Corrections need approval (default workflow created on first use; requester cannot approve); applied by the
   `approval.decided` subscriber or `/sync`; adjust supersedes the original reading, replace re-bases the offset.
3. Plate required for road vehicles and trailers; owner partner required for hired / third-party units; model year
   ≤ next year; one vehicle per fixed asset; references (branch, site, cost centre, partner, asset) must exist in the
   caller's organization.
4. Disposed vehicles cannot be edited or re-statused and take no new meter readings, corrections or compliance
   documents.
5. A vehicle with operating history (non-opening readings or corrections) cannot be deleted — dispose it.
6. Compliance expiry query: active documents of live, non-disposed vehicles; `expired` < today ≤ `expiring` ≤ today +
   lead; renewal must expire after the current document.

## Business rules (1.2)

1. **Eligibility** (`DriverEligibilityPolicy`, assignment on its start day and check-out): vehicle operational (only
   `active`); driver `active` and the hr employee active; road vehicles / trailers need a driving licence that has not
   expired; mobile plant needs a valid licence or a valid operator permit; fixed plant / other need neither; a medical
   certificate on file must not have expired; the vehicle's class must be a qualified class. Documents expire at the end
   of their expiry day; a document without expiry is valid. Messages, in that order: `Vehicle VEH-0001 is in workshop
   and cannot be assigned` / `… checked out`, `Driver DRV-0001 is suspended and cannot be assigned`, `Driver DRV-0001 is
   not an active employee in HR`, `Driver DRV-0001 has no driving licence on file`, `Driver DRV-0001's driving licence
   expired on 2026-10-28`, `Driver DRV-0001 has no valid driving licence or operator permit for Excavator`, `Driver
   DRV-0001's medical certificate expired on …`, `Driver DRV-0001 is not qualified for vehicle class Tipper truck
   (TIPPER)`.
2. **Assignments**: a primary assignment must start after the current primary and ends it; temporary covers need an
   end after the start; the same driver cannot hold two overlapping active assignments on one vehicle (409).
3. **Check-out / check-in**: tracked meters are mandatory and recorded with `FleetMeterService.recordAutomated`
   (`source` check_out / check_in, `sourceId` = check-out id), so the monotonic rule applies (a check-in below the
   check-out reading is refused with the 1.1 message); not more than 5 min in the future; check-in after check-out;
   damage flags from a fixed list. **Concurrency**: the vehicle row then the driver row are locked `FOR UPDATE` before
   the open check-outs are read (same order in assignment), backed by partial unique indexes — `Vehicle VEH-0001 is
   already checked out to driver DRV-0002 (CHK-000123)` / `Driver DRV-0001 already has vehicle VEH-0002 checked out
   (CHK-…)` (409).
4. **Allowance pricing** (`AllowanceCalculator`): active rules of the basis effective on the claim date whose optional
   class / zone match; the most specific wins (class + zone > class > zone > general), then the latest `effectiveFrom`;
   amount = rate × quantity, HALF_UP 2 decimals. Two active rules with the same basis + class + zone may not overlap in
   time (409). No rule → `No active allowance rule pays per day on 2026-10-29 for vehicle class Tipper truck in zone
   North`.
5. **Claims**: lines only while draft; submit needs ≥ 1 line and a positive total and snapshots the vehicle's cost
   centre; approval through the kernel engine (default workflow "Fleet driver claim", step "Fleet manager", approver
   permission `fleet_driver:approve_allowance`, created on first use; an inactive workflow auto-approves); the
   submitter and the driver's own user cannot approve or reject (403). Paying needs `expense:post` as well.
6. **Settlement / reversal**: see GL postings; a payroll export can be reversed only while its payroll input is not in a
   payroll run (`The payroll input is already in payroll run PR-…; reverse or recalculate that run first`).

## Events

| Event | When | Payload |
|---|---|---|
| `fleet.vehicle.created` | vehicle registered | `organizationId, vehicleId, code, categoryId` |
| `fleet.vehicle.status_changed` | any status change | `organizationId, vehicleId, fromStatus, toStatus, reason, source, sourceId` |
| `fleet.meter.corrected` | correction applied | `organizationId, vehicleId, correctionId, meterType, kind, previousValue, correctedValue` |
| `fleet.vehicle.checked_out` | check-out (1.2) | `organizationId, checkoutId, vehicleId, driverId, checkedOutAt` |
| `fleet.vehicle.checked_in` | check-in (1.2) | `… checkedInAt, distanceKm, hoursUsed` |
| `fleet.claim.settled` | claim paid / exported (1.2) | `organizationId, claimId, code, driverId, vehicleId, settlement, amount, currencyCode, journalEntryId` |

Consumed: `approval.decided` (entity types `fleet_meter_correction`, `fleet_driver_claim`).

## Scheduled jobs

None in 1.1 / 1.2. 1.9 adds `fleet-reminders` (ShedLock, daily) over `FleetComplianceService.dueForOrganization`
and `FleetDriverService.documentsDueForOrganization` (licences, medical certificates, permits).

## GL postings

None in 1.1 (the registry has no money effect). Posting rules of 1.3–1.9: ADR-S009 §6. **1.2** (all with the claim's
cost centre on the expense line and dimensions `vehicleId, driverId, claimId`; accounts created on first use):

| Event | Debit | Credit | Posted by |
|---|---|---|---|
| Claim paid via expenses | 6440 Driver Allowances & Trip Expenses (`fleet_driver_allowance_expense`) | the chosen cash / bank / mobile-money account | `ExpensesService.createPaidFromSource` (expense `POSTED` + payment, journal key `expense_payment:<id>`) |
| Claim exported to payroll (accrual) | 6440 | 2185 Driver Allowances Payable (Payroll) (`fleet_driver_allowance_payable`) | fleet, key `fleet:claim:<id>:payroll:<n>` |
| Payroll run approved with the input | 2185 (instead of salaries expense for that amount) | net pay payable etc. (payroll's journal) | hr `HrPayrollService.approveRun` |
| Reversal of a paid claim | — | — | `ExpensesService.voidExpense` reverses the payment journal |
| Reversal of an exported claim | — | — | `PostingService.reverse` of the accrual; the pending payroll input is cancelled |

## Public API for later sub-modules

- `FleetVehicleService.require(id, lock)`, `changeStatus(id, to, reason, source, sourceId)`, `get(id)`.
- `VehicleStatusPolicy.isOperational(status)` — dispatch / assignment / check-out guard.
- `FleetMeterService.recordAutomated(vehicleId, meterType, value, readingAt, source, sourceId, notes)` — idempotent.
- `FleetComplianceService.dueForOrganization(organizationId, asOf)` — reminder job.
- `FleetCategoryService.requireActive(id)`; `FleetAttachmentService.upload(...)` (check-in photos, POD, receipts).
- 1.2: `FleetDriverService.require(id, lock)`, `facts(driver)` + `vehicleFacts(vehicleId, lock)` +
  `DriverEligibilityPolicy.assertEligible(…)` (1.3 dispatch assigns drivers to loads the same way),
  `documentsDueForOrganization(org, asOf)`; `FleetCheckoutService` (1.3 trips can set `tripId` on a check-out);
  `FleetClaimService` (1.3 fills `tripId` on claims and adds the FK to its trips table).

### Additive APIs in other modules (1.2)

- **hr** — `HrPayrollInputService` (type-level named interface `payroll-inputs`; the rest of `hr.application` stays
  internal): `addEarning(employeeId, name, amount, earnedOn, taxable, debitAccountId, source, sourceId)` (idempotent
  per source document), `find`, `cancel`. The payroll calculation adds pending inputs of active employees earned up to
  the period end as allowance lines (non-taxable inputs are kept out of the PAYE base), approval marks them
  `included` and debits their `debitAccountId` instead of salaries expense, recalculation / delete / reversal releases
  them. Without inputs the payroll engine, its routes and its journal are unchanged (HrIT green).
- **expenses** — `ExpensesService.createPaidFromSource(CreateInput, approvedById, SourcePosting(expenseAccountId,
  costCenterId, dimensions))`: an expense approved elsewhere, created `POSTED` and paid at once with the same treasury
  guards; existing routes and postings unchanged.

## Web

Sidebar **Fleet**: Fleet Overview `/fleet`, Vehicles & Equipment `/fleet/vehicles` (+ `/new`, `/:id`, `/:id/edit`),
Compliance & Renewals `/fleet/compliance`, Vehicle Classes `/fleet/settings/classes`. Detail tabs: overview, meters
(record reading, corrections), compliance, status history, fixed asset, photos & documents, **driver assignments /
check-outs** (1.2). 1.2 screens: Drivers & Operators `/fleet/drivers` (+ `/new`, `/:id`, `/:id/edit`; detail tabs
licence & qualifications, assignments, check-outs, claims), Check-out / Check-in `/fleet/checkout?vehicleId=` (phone
first: big inputs, fuel / condition buttons, damage chips, camera capture), Driver Claims `/fleet/claims` (+ `/new`,
`/:id` with submit / approve / reject / pay / export / reverse), Allowance Rules `/fleet/settings/allowances`. Hooks in
`web/src/features/fleet/drivers-api.ts`. Routes in
`web/src/pages/fleet/routes.tsx`, hooks in `web/src/features/fleet/api.ts`.

## Deviations

None from any parity contract (new module). Autonomous decisions are listed in `docs/DECISIONS_FOR_OWNER.md`
(1.1 and 1.2 rows). Against the ADR-S009 §3 table names: the planned `FleetDriverLicence` is folded into `FleetDriver`
(one current licence per profile; changes are audited), `FleetCheckout` also stores check-in, and the planned
`FleetDriverAllowance` is `FleetDriverClaim` + `FleetDriverClaimLine` (+ `FleetClaimReceipt`).

## Status

| Sub-module | Status | Tests |
|---|---|---|
| foundation (module, flag, licence, permissions, ADR, spec, menu) | done | `ModularityTests`, `ArchitectureTests`, `ApplicationIT` |
| 1.1 registry | done (32 routes) | `FleetPoliciesTest` (unit: status machine, meter rule, compliance state), `FleetRegistryIT` (14: classes, CRUD + filters, status machine, monotonic meters, concurrency, corrections adjust / replace / reject / no workflow, compliance + expiry query, attachments, linked asset, lookups, tenant isolation, authorization matrix 32 routes) |
| 1.2 drivers & operators | done (37 routes) | `DriverEligibilityPolicyTest`, `AllowanceCalculatorTest` (unit); `FleetDriversIT` (8: profiles, expiring documents, eligibility blocks, assignment history, check-out / check-in with meters + photos + breakdown, concurrent check-outs, tenant isolation, authorization matrix 17 routes); `FleetClaimsIT` (8: allowance rules, draft pricing / validation, approval flow incl. inbox subscriber and no-workflow, pay via expenses with idempotency replay + GL + reversal, expense:post / driver SoD, payroll export → payroll run → reversals, tenant isolation, authorization matrix 20 routes) |
| 1.3–1.9 | planned | — |
