# Fleet & logistics — `fleet`

| | |
|---|---|
| Phase | Track C Phase 1 (construction materials), roadmap Tier 4 |
| Nest source | none — new module (no parity constraints; house rules apply) |
| Design | [ADR-S009](../adr/ADR-S009-fleet-and-logistics.md) |
| Routes | 32 (1.1) + 37 (1.2) + 55 (1.3) + 36 (1.4a) + 6 (1.4b) — tables below; 1.9 outlined |
| Feature flag | `ENABLE_FLEET` → `app.modules.fleet` (controllers + module manifest only when `true`), licence feature `fleet`, module name `fleet`, web feature key `fleet` |
| Java package | `com.inventoryerp.fleet` (`domain`, `application` are named interfaces) |
| Migrations | `V15__fleet_vehicle_registry.sql`, `V16__fleet_drivers_operators.sql` (1.2 + hr `HrPayrollInput`), `V22__fleet_dispatch.sql` (1.3), `V23__fleet_weighbridge.sql` (1.4a), `V951__fleet_weighbridge_dispatch.sql` (1.4b, placeholder number) |

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
| 1.3 | Trips, delivery planning & dispatch | **done** | customer sites, delivery orders from sales orders / invoices / manual, dispatch board, loads within payload / volume, own fleet or transporter, trip lifecycle, delivery note, POD, stock issue once per load, discrepancies → approval → credit-note request, transport charges — see "1.3 Trips, delivery planning & dispatch" |
| 1.4 | Weighbridge | **1.4a done; 1.4b outbound wiring pending** | stations, devices (`WeighbridgeAdapter` SPI: ASCII TCP / serial + simulator), stored tare with expiry, ticket workflow, tolerance exceptions with SoD approval, immutable tickets (void + reissue), inbound weighing → GRN |
| 1.5 | Fuel & energy | done | fuel logs, cards, efficiency + anomaly flags, site tanks / bowsers (GRN in, stock issue out, dips, reconciliation), GL |
| 1.6 | Maintenance | done | service templates / schedules (km / hours / calendar), fleet-owned work orders (parts, labour, external bills), downtime, availability, MTBF / MTTR, inspections (DVIR) → defects → work orders, dispatch block |
| 1.7 | Tyres, incidents, fines | done | tyre positions / fitment / rotation / tread, cost per km; incidents, claims; fines charged to driver (payroll deduction) or company |
| 1.8 | Telematics SPI | done | `TelematicsProvider` (positions, meters, events) + mock; positions feed trip distance and POD location |
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
| 1.3 | built — see "1.3 Trips, delivery planning & dispatch" (`/fleet/customer-sites`, `/fleet/delivery-orders`, `/fleet/dispatch-board`, `/fleet/trips`, `/fleet/delivery-discrepancies`, `/fleet/transporter-rates`, `/fleet/delivery-charge-rules`, `/fleet/product-load-factors`) | `fleet_dispatch:*`, `fleet_trip:*`, `fleet:settings` |
| 1.4 | built (1.4a) — see "1.4 Weighbridge" | `weighbridge:*` |
| 1.5 | `/fleet/fuel-cards`, `/fleet/fuel-logs` (+ `/post`, `/reverse` — idempotent), `/fleet/fuel-tanks`, `/dips`, `/reconciliation` | `fleet_fuel:*` |
| 1.6 | `/fleet/service-templates`, `/fleet/service-schedules`, `/fleet/work-orders` (+ lines, start, complete — idempotent), `/fleet/downtime`, `/fleet/inspection-templates`, `/fleet/inspections`, `/fleet/defects` | `fleet_maintenance:*` |
| 1.7 | `/fleet/tyres`, `/fleet/incidents`, `/fleet/insurance-claims`, `/fleet/fines` (+ `/charge`) | `fleet_maintenance:tyre`, `fleet_incident:*` |
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
| 1.5 fuel, 1.6 maintenance | done | see their sections below |
| 1.7 tyres, incidents, fines; 1.8 telematics | done | see their sections below |
| 1.3 trips, delivery planning & dispatch | done (55 routes) | `DispatchPoliciesTest` (unit: load splitting by payload / volume / whole units, freight, delivery-charge rule choice + amounts, trip state machine); `FleetDispatchIT` (9: sales order → DO → loads → eligibility / capacity blocks → weighed → dispatch once with replay → delivery note → POD shortage → SoD approval → close → invoice + transport charge without a second stock issue; posted-invoice source → credit-note request + transporter freight + draft-invoice hand-over; returns; concurrent dispatch; weighbridge API; settings / validation / trip FK; tenant isolation; authorization matrix 55 routes) |
| 1.4a weighbridge core | done (36 routes) | `WeighbridgePoliciesTest`, `FleetWeighbridgeIT` — see "1.4 Weighbridge" |
| 1.4b weighbridge ↔ dispatch | done (6 routes) | `WeighbridgeDispatchRulesTest` (unit), `FleetWeighbridgeDispatchIT` (7), `FleetPhase1EndToEndIT` (3, Phase 1 exit) — see "1.4b" |
| 1.9 | planned | — |

## 1.6 Maintenance — fleet, mobile plant and (Phase 4) fixed plant

Status: **done** (Track C, 2026-10-29). Design: ADR-S009 §5 (fleet-owned work orders, `repair` not reused) and §6
(GL). Migration `V18__fleet_maintenance.sql`. Permissions: the existing
`fleet_maintenance:*` keys (none added). Feature flag / module: `fleet`.

### Maintainable unit (reuse for fixed plant)

Every maintenance table keys on the unit id `vehicleId` → `FleetVehicle`. Trucks, trailers and mobile plant are units
today; Phase 4 CMMS registers crushers, conveyors, screens, block machines and generators as units of a category with
`kind = fixed_plant` (ADR-S009 §4), so schedules, work orders, downtime, inspections and the reliability report work
for them without schema change. The services only use the unit view of `MaintainableUnits` (identity, class, cost
centre, branch, lifetime meters, status hold / release); a separate plant register would implement that view.

### Routes (51, all under `/api/v1`, `@RequiresModule("fleet")`)

| Group | Routes | Permission |
|---|---|---|
| Service templates | `GET /fleet/service-templates` (`categoryId, includeInactive`), `POST`, `PATCH /{id}`, `DELETE /{id}` (400 when used → deactivate), `POST /{id}/apply` (`vehicleIds` or `categoryId`; skips units already planned) | read / `schedule` |
| Schedules + due list | `GET /fleet/service-schedules` (due list: `state` = attention (default: due + overdue), due, overdue, ok, all; `vehicleId, categoryId, branchId, asOf, page, pageSize`), `GET` / `POST /fleet/vehicles/{id}/service-schedules`, `PATCH` / `DELETE /fleet/service-schedules/{id}`, `POST /fleet/service-schedules/{id}/work-order` (409 when a live one exists; `create`), `POST /fleet/service-schedules/generate` (`schedule`) | read / `schedule` / `create` |
| Work orders | `GET /fleet/work-orders` (`search, vehicleId, status` (+ `live`), `type, priority, from, to, page, pageSize, sortBy, sortOrder`), `GET /{id}` (tasks, lines, cost summary, defects, downtime, `allowed` transitions), `POST` (201), `PATCH /{id}`, `POST /{id}/open`, `/start` (`update`), `/complete`, `/close` (`complete`), `/cancel` (`{reason}`, `update`) | read / `create` / `update` / `complete` |
| Tasks | `POST /fleet/work-orders/{id}/tasks`, `PATCH` / `DELETE /fleet/work-orders/{id}/tasks/{taskId}` | `update` |
| Cost lines (money: `Idempotency-Key` required, 200, replay) | `POST /fleet/work-orders/{id}/parts`, `/labour`, `/bills`, `POST /fleet/work-orders/{id}/lines/{lineId}/return`, `/reverse` | `complete` |
| Pickers | `GET /fleet/maintenance/lookups/parts` (`search, locationId` → on-hand), `GET /fleet/maintenance/lookups/employees` | read |
| Downtime | `GET /fleet/downtime` (`vehicleId, kind, reasonCode, open, from, to`), `GET /{id}`, `POST` (`createWorkOrder`), `POST /{id}/end`, `PATCH /{id}` | read / `create` / `update` |
| Reliability | `GET /fleet/maintenance/reliability` (`from, to` (default last 30 days), `groupBy` vehicle / class, `vehicleId, categoryId, branchId`) | read |
| Checklists | `GET /fleet/inspection-templates` (seeds the default "Daily pre-trip check" for an org without any), `POST`, `PATCH /{id}`, `DELETE /{id}` | read / `schedule` |
| Inspections | `GET /fleet/inspections`, `GET /{id}` (items, defects, photos with signed URLs), `POST` (`inspect`), `POST /{id}/photos` (multipart `file`, `itemCode`) | read / `inspect` |
| Defects | `GET /fleet/defects` (`status` = open (open + in repair, default), in_repair, resolved, dismissed, all; `severity`), `GET /{id}`, `POST` (driver report, `inspect`), `POST /{id}/work-order` (`create`; or `{workOrderId}` to attach), `POST /{id}/close` (`{outcome: resolved / dismissed, resolution}`, `inspect`) | read / `inspect` / `create` |
| Dispatch eligibility | `GET /fleet/vehicles/{id}/dispatch-eligibility` → `{eligible, status, reasons[], blockingDefects[], warnings[]}` | `fleet_vehicle:read` |

### Data model (V911)

| Table | Purpose | Notable columns / rules |
|---|---|---|
| `FleetServiceTemplate` | plan per class | `code` unique per org, `categoryId` (null = any class), `intervalKm / intervalHours / intervalDays` (≥ 1 set), `leadKm / leadHours / leadDays` (due-soon window), `tasks` JSON array |
| `FleetServiceSchedule` | plan per unit | intervals / leads / tasks (copied from the template, overridable), baseline `lastDoneOn / lastDoneKm / lastDoneHours` (lifetime meters; default today + current meters), `status` active / paused, `openWorkOrderId`, `lastWorkOrderId`; one schedule per unit + template |
| `FleetWorkOrder` | maintenance job | `code` WO-00001, `type` preventive / corrective / breakdown / inspection_defect, `priority`, `status`, `source` manual / schedule / schedule_job / defect / downtime, `scheduleId` (one **live** work order per schedule: partial unique index), timestamps per state, completion meters, `previousVehicleStatus`, snapshot `costCenterId / branchId`, running `partsCost / labourCost / externalCost / totalCost` |
| `FleetWorkOrderTask` | checklist of the job | `status` pending / done / skipped; completing needs no pending task |
| `FleetWorkOrderLine` | cost line | `lineType` part / part_return / labour / external, `status` posted / reversed, product / location / quantity / `returnedQuantity` / `stockLedgerCode`, `employeeId / hours / workDate`, `partnerId / supplierReference / billDate / vendorBillId`, `unitCost`, signed `amount` (returns negative), `journalEntryId`, unique `postingKey`, reversal fields |
| `FleetDowntime` | out-of-service period | `kind` unplanned (failure) / planned, `reasonCode` (engine, transmission, hydraulic, electrical, brakes, tyres, suspension, body, accident, preventive_service, inspection_defect, waiting_parts, operator_error, other), `startedAt / endedAt`, `workOrderId`, `source`; **one open downtime per unit** |
| `FleetInspectionTemplate` | DVIR checklist | `kind` pre_trip / post_trip / periodic, `categoryId`, `items` JSON `{code, label, section, severity}` |
| `FleetInspection` | recorded check | `code` INS-00001, inspector (hr employee) + recording user, meters, `result` pass / fail, `items` JSON with per-item result / notes, `photos` JSON (vehicle attachment ids), `defectCount` |
| `FleetDefect` | fault | `code` DEF-00001, `severity` minor / major / critical, `status` open → in_repair → resolved / dismissed, `inspectionId`, `itemCode`, `workOrderId` |

All tables: `organizationId` + RLS `tenant_isolation`, quoted camelCase, text UUIDs.

### Rules

1. **Next due** (`ServiceDuePolicy`): per dimension next due = baseline + interval (km and hours on lifetime meters,
   days on the business date); remaining < 0 → `overdue`, 0 ≤ remaining ≤ lead → `due`, else `ok`; unknown meters never
   trigger; a missing meter baseline counts from 0. Overall = worst dimension (**whichever first**), `dueTrigger` = the
   dimension closest to its due point relative to the interval.
2. **Generation**: the screen action, `generate` and the daily ShedLock job `fleet-maintenance-due` (cron
   `FLEET_MAINTENANCE_DUE_CRON`, default 05:30; `app.jobs.enabled` and `app.modules.fleet`) raise one preventive work
   order (status open, schedule tasks, priority high when overdue) per due / overdue active schedule without a live
   work order; the schedule row is locked and the partial unique index makes a double booking impossible.
3. **Lifecycle** (`MaintenancePolicies`): `draft → open → in_progress → completed → closed`; `draft / open /
   in_progress → cancelled` only without posted cost lines (reverse / return first). **Start** holds the unit
   `in_workshop` (source maintenance, remembers the previous status) and opens downtime (planned for preventive work,
   unplanned otherwise) or links the unit's open downtime. **Complete** needs every task done / skipped; optional
   completion meters are recorded (source `work_order`, monotonic rule); it ends the downtime, resolves linked
   defects, re-bases the schedule (`lastDoneOn / Km / Hours` = completion) and releases the unit (back to its previous
   active / idle status once no other work is in progress and no downtime is open). Parts are issued while open / in
   progress; labour, bills, returns and reversals also after completion (late bills); nothing once closed.
4. **Downtime**: recording an unplanned downtime moves the unit to `breakdown`, planned to `in_workshop`;
   `createWorkOrder` raises the linked breakdown / corrective work order; ending it releases the unit.
5. **Reliability** (`AvailabilityCalculator`): period = [from, min(to, now)) clipped at disposal; downtime = union of
   intervals (overlaps once); availability % = (period − downtime) / period; failures = unplanned intervals starting in
   the period; MTBF = uptime / failures; MTTR = unplanned downtime / failures; class / fleet totals recomputed from
   summed seconds.
6. **Inspections**: every checklist item answered (pass / fail / na); a class-specific checklist only for its class;
   result fail when any item fails; each failed item raises a defect (answer severity, else the item's) unless the same
   item already has an open defect on the unit (repeat failure). Defects → corrective work order (type
   inspection_defect, priority urgent / high / normal by severity) or attached to a live one (`in_repair`);
   completing the work order resolves them, cancelling re-opens them.
7. **Dispatch block** (`MaintenancePolicies.blocksDispatch`): a **critical** defect that is open or in repair blocks
   the unit; status must be operational (active). Major defects and due / overdue services are warnings only.

### GL postings (PostingService; posting key per line; debits = credits; cost centre = the unit's)

| Line | Entry | Accounts (mapping key / default code) |
|---|---|---|
| Part issue | `StockService.issue` (`internal_use`, quantity only, never below on-hand) + Dr maintenance / Cr the part's stock account at the issue cost, journal INV | `fleet_maintenance_expense` 6420; stock = `StockPostingService.stockAccount` |
| Part return | bare receipt (`return_in`) at the issue unit cost + Dr stock / Cr maintenance | same |
| Labour | memo cost (hours × rate, default the employee's hourly rate); with org setting `fleet.maintenanceLabourAbsorption` = true: Dr maintenance / Cr workshop labour absorbed, journal GEN | `fleet_maintenance_labour_absorbed` 6429 |
| External bill | `VendorBillService.create` → **draft vendor bill** in AP (line on the clearing account; AP approves / posts: Dr clearing / Cr supplier) + accrual Dr maintenance / Cr clearing, journal GEN | `fleet_maintenance_accrual` 2168 "Fleet Workshop Bills to Receive" |
| Reverse | `PostingService.reverse` of the line's entry; a part goes back to stock (`reversal_in`), a return is re-issued; refused for a part with returns (reverse them first) and for a bill already posted in AP (cancel it there first) | — |

Every entry carries the dimensions `vehicleId` and `workOrderId`; the expense line carries the unit's cost centre.

### Events

`fleet.workorder.created` (`organizationId, workOrderId, code, vehicleId, type, source`), `fleet.workorder.completed`
(`… totalCost`), `fleet.defect.reported` (`organizationId, defectId, code, vehicleId, severity, blocksDispatch`); the
vehicle status changes publish `fleet.vehicle.status_changed` with `source = maintenance`.

### Public API for later sub-modules

- **`FleetDispatchEligibilityService.check(vehicleId)` / `assertEligible(vehicleId)`** — 1.3 dispatch, assignment and
  check-out call it (`assertEligible` throws 400 "Vehicle X cannot be dispatched: …").
- `FleetServiceScheduleService.dueForOrganization(organizationId, asOf)` — due / overdue services for the 1.9 alert
  job; `attentionForVehicle(vehicleId)`.
- `FleetWorkOrderService.createFor(WorkOrderInput, source)` (1.7 incidents / tyres can raise work orders);
  `FleetWorkOrderCostService.maintenanceExpenseAccount()`.
- `MaintainableUnits` (unit view, `hold` / `release` of the unit status around maintenance).

### Web

Sidebar **Fleet**: Services Due `/fleet/maintenance`, Maintenance Work Orders `/fleet/maintenance/work-orders`
(+ `/:id` detail: state actions, tasks, parts / labour / bills dialogs, return / reverse, cost tiles, defects,
downtime), Inspections `/fleet/inspections` (+ `/new` phone entry with big pass / fail / n.a. buttons and camera
photos, `/:id`), Defects `/fleet/defects`, Downtime `/fleet/maintenance/downtime`, Availability & Reliability
`/fleet/maintenance/reliability`, Service Templates `/fleet/maintenance/templates`, Inspection Checklists
`/fleet/maintenance/checklists`. Vehicle detail tabs **Maintenance** (schedules + due, work orders, downtime) and
**Inspections** (dispatch eligibility banner, open defects, inspections). Hooks in
`web/src/features/fleet/maintenance-api.ts`, pages in `web/src/pages/fleet/maintenance/`.

### Tests

`FleetMaintenancePoliciesTest` (unit: next due km / hours / days whichever first, lead windows, unknown meters;
availability / MTBF / MTTR incl. overlaps, clipping, open intervals, aggregation; defect block rule, defect / work-order
state machines, inspection result) and `FleetMaintenanceIT` (8: template → schedule → due list → job / screen
generation exactly once → lifecycle with unit status, downtime and schedule re-base; part issue with stock + balanced
GL on the cost centre, idempotent replay, returns, reversals; concurrent issue of the last units; labour memo /
absorbed, external bill → AP draft + accrual, reversal rules; inspection → critical defect → dispatch blocked →
work order → resolved → eligible; downtime + reliability; tenant isolation; authorization matrix of all 51 routes).

### Known gaps / owner decisions

- Account codes 6420 / 6429 / 2168 are proposals (remap via the mapping keys).
- A reversed external line whose AP bill is still a draft leaves that draft in AP (AP has no draft delete): discard it
  there; the accrual is already reversed.
- External bills are net of tax (AP may add tax on the draft bill; the accrual stays on the net amount).
- Parts are issued in the product's base unit; serial-tracked parts need the serial-number flow (not exposed here).
- Reason codes are a fixed list (translated in the web); configurable codes can follow with 4.x CMMS.

## 1.5 Fuel & energy

Status: **done** (worktree `wt/c-fuel`, migration placeholder `V19__fleet_fuel.sql`, renumbered at merge). 34 routes,
module-gated like 1.1. Controllers live in `com.inventoryerp.fleet.web.fuel`; services `FleetFuel*Service` in
`fleet.application`; policies `FuelEfficiencyPolicy`, `FuelCardNumbers`.

### Routes (1.5)

| # | Method | Path | Permission | Notes |
|---|---|---|---|---|
| 1 | GET | `/fleet/fuel/overview` | `fleet_fuel:read` | month-to-date litres / cost, drafts, open anomalies, tanks below reorder, pending reconciliations |
| 2 | GET | `/fleet/fuel/lookups` | `fleet_fuel:read` | vehicles (meters, tank size), active tanks (level), active cards (masked), cash / bank accounts and suppliers (expenses API), locations, cost centres |
| 3 | GET | `/fleet/fuel/lookups/employees` | `fleet_fuel:read` | `search`; active hr employees (≤ 30) |
| 4 | GET | `/fleet/fuel/lookups/products` | `fleet_fuel:read` | `search`; stocked products (tank fuel product) |
| 5 | GET | `/fleet/fuel-logs` | `fleet_fuel:read` | `{data, meta, summary}`; `vehicleId, source, status, anomaly (any/open/none/type), tankId, fuelCardId, dateFrom, dateTo, search, page, pageSize, sortBy, sortOrder` |
| 6 | GET | `/fleet/fuel-logs/{id}` | `fleet_fuel:read` | + vehicle, tank, card (masked), supplier, employee, cost centre, anomalies, GL entries (+ reversals), expense, receipt (signed URL) |
| 7 | POST | `/fleet/fuel-logs` | `fleet_fuel:create` | **Idempotency-Key required**; draft, or `post: true` (also needs `fleet_fuel:post`, else 403) |
| 8 | PATCH | `/fleet/fuel-logs/{id}` | `fleet_fuel:update` | drafts only; `clear: [...]` |
| 9 | DELETE | `/fleet/fuel-logs/{id}` | `fleet_fuel:update` | drafts only (soft delete) |
| 10 | POST | `/fleet/fuel-logs/{id}/post` | `fleet_fuel:post` | **Idempotency-Key required**; exactly once (row + vehicle locked) |
| 11 | POST | `/fleet/fuel-logs/{id}/reverse` | `fleet_fuel:post` | **Idempotency-Key required**; `{reason}` |
| 12 | POST | `/fleet/fuel-logs/{id}/receipt` | `fleet_fuel:create` | multipart `file` (camera photo / PDF) → vehicle attachment, linked to the log |
| 13 | GET | `/fleet/fuel-anomalies` | `fleet_fuel:read` | `status` (open default / acknowledged / resolved / all), `type`, `vehicleId`, paging |
| 14 | POST | `/fleet/fuel-anomalies/{id}/acknowledge` | `fleet_fuel:update` | `{note}` (required), audited; open only |
| 15 | GET | `/fleet/vehicles/{id}/fuel` | `fleet_fuel:read` | totals, average L/100 km and L/h, class band, efficiency trend (last 60 measured logs), 10 recent logs |
| 16–20 | GET / GET / POST / PATCH / DELETE | `/fleet/fuel-cards`, `/fleet/fuel-cards/{id}` | `read` / `read` / `create` / `update` / `update` | list `search` (last 4 / label / vehicle), `status`, `vehicleId`; 409 duplicate number; delete only unused (else cancel) |
| 21 | GET | `/fleet/fuel-settings` | `fleet_fuel:read` | thresholds + band per active class (system classes seeded with default bands) |
| 22 | PUT | `/fleet/fuel-settings` | `fleet:settings` | `overfillTolerancePct, dipApprovalLitres, dipApprovalPct` |
| 23 | PUT | `/fleet/fuel-settings/bands/{categoryId}` | `fleet:settings` | `minLPer100Km, maxLPer100Km, minLPerHour, maxLPerHour` (replace; null = not checked) |
| 24–28 | GET / GET / POST / PATCH / DELETE | `/fleet/fuel-tanks`, `/fleet/fuel-tanks/{id}` | `read` / `read` / `tank` / `tank` / `tank` | level, fill %, value, last dip, draft issues; one tank per inventory location; delete only unused |
| 29 | GET | `/fleet/fuel-tanks/{id}/movements` | `fleet_fuel:read` | inventory ledger of the tank (GRN receipts, issues with fuel log, adjustments, reversals) |
| 30 | GET | `/fleet/fuel-tanks/{id}/dips` | `fleet_fuel:read` | newest first (`limit`) |
| 31 | POST | `/fleet/fuel-tanks/{id}/dips` | `fleet_fuel:tank` | `{dipLitres, dippedAt?, notes?}` → book at the dip time, variance |
| 32 | GET | `/fleet/fuel-dips/{id}` | `fleet_fuel:read` | |
| 33 | POST | `/fleet/fuel-dips/{id}/reconcile` | `fleet_fuel:tank` | **Idempotency-Key required**; `{reason}` (required when variance ≠ 0) |
| 34 | POST | `/fleet/fuel-dips/{id}/sync` | `fleet_fuel:tank` | **Idempotency-Key required**; apply the decided approval now |

### Data model (1.5)

| Table | Purpose | Notable columns / constraints |
|---|---|---|
| `FleetFuelSettings` | one row per org | `overfillTolerancePct` (2), `dipApprovalLitres` (50), `dipApprovalPct` (0.5 % of capacity), `expenseCategoryId` (the "Fleet fuel" expense category, created on first use) |
| `FleetFuelBand` | consumption band per vehicle class | min / max L/100 km and L/h; unique per class |
| `FleetFuelCard` | fuel cards | `maskedNumber` (`**** 1234`), `last4`, `numberHash` (SHA-256 of org + number, unique among live cards) — the full number is never stored; provider partner, vehicle, employee, status (active / blocked / expired / cancelled), expiry, `limitAmount` + `limitPeriod` (daily / weekly / monthly) |
| `FleetFuelTank` | site tank / bowser | `locationId` (inventory location, unique per org), `productId` (fuel, litres = base unit), `capacityL`, `reorderLevelL`, `kind` tank / bowser, `bowserVehicleId`, active, soft delete |
| `FleetFuelLog` | fuel log | `code` FL-000001, vehicle, `fuelledAt`, `source` station / tank, litres, unit price, amount, full tank, odometer / hours, supplier, station, `paymentMethod` cash / fuel_card / credit, payment account, card, tank, receipt no. + attachment, employee (hr), cost centre, status draft / posted / reversed; efficiency (`previousFullLogId, segmentLitres, distanceKm, hoursRun, lPer100Km, lPerHour`), `anomalyCount`, meter reading ids + `meterNote`; `expenseId`, `journalEntryId`, `stockLedgerCode`, `stockUnitCost`, `stockValue`; post / reversal stamps. Checks: tank source ⇔ tank id; station needs a payment method |
| `FleetFuelAnomaly` | review queue | type (overfill, meter_not_increasing, high_consumption, low_consumption, card_vehicle_mismatch, card_limit_exceeded), detail, observed / expected bounds, status open / acknowledged / resolved, note, acknowledged by / at; unique per log + type |
| `FleetTankDip` | dips + reconciliation | `dipLitres`, `bookLitres` (inventory ledger as of the dip time), `varianceLitres`; status open → accepted / adjusted / pending_approval → adjusted / rejected / failed; reason, approval request, adjustment ledger code / litres / value; one pending reconciliation per tank |

### Rules (1.5)

1. **Fuel log validation**: vehicle not disposed; not more than 5 min in the future; the vehicle's meter must be given
   (odometer and / or hours as it tracks; a meter it does not track is refused); station purchases need a payment
   method, the amount or the price (the other is derived; both must agree within max(1, 0.5 %)), a cash / bank account
   (cash), an active unexpired card (fuel card) or a supplier (credit); tank issues need an active tank and carry no
   price (costed from stock at posting).
2. **Posting** (exactly once: log row and vehicle row locked; `Idempotency-Key`): meter readings through
   `FleetMeterService.recordAutomated(source = fuel, sourceId = log)`; a reading the monotonic meter rule refuses
   (`FleetMeterService.readingRejection`) is not recorded — the log posts and is flagged `meter_not_increasing` with the
   rule's message (corrections go through the 1.1 approval workflow). Then the GL / stock effect (below), then the
   efficiency chain and anomalies are recomputed for the vehicle.
3. **Efficiency** (full-to-full): a full-tank log is measured against the previous posted full-tank log of the
   vehicle; the litres are its own plus every partial fill in between; L/100 km over the odometer distance, L/h over
   the hour-meter run. Back-dated logs and reversals recompute the whole chain of the vehicle.
4. **Anomalies**: overfill (litres > tank size × (1 + tolerance)), meter not above the previous log's meter (or refused
   by the meter rule), consumption above / below the class band; at posting: card assigned to another vehicle, card
   spend in its limit period above the limit (logged, not refused — the provider enforces the limit at the pump). Chain
   anomalies that no longer apply are `resolved`; reversal resolves all of a log's anomalies. Acknowledge needs a note.
5. **Tanks**: fuel comes in through the **normal procurement goods receipt** (PO receipt or ad-hoc GRN) into the tank's
   inventory location — no fleet purchase flow; the tank's book level is that location's stock of its product. Issues
   use `StockService.issue` (move type `internal_use`, `requireAvailable` — a tank never goes negative; concurrent issues
   serialise on the stock row), costed by the product's method (AVCO / FIFO, ADR-S007).
6. **Dips / reconciliation**: book = inventory ledger up to the dip time (post draft issues first); variance =
   dip − book. Reconcile: no variance → accepted; |variance| ≤ both thresholds → `StockService.adjust` at once
   (inventory's adjustment gain / loss posting); above → kernel approval engine, entity type
   `fleet_tank_reconciliation` (default workflow "Fuel tank reconciliation", approver permission `fleet_fuel:post`,
   requester cannot approve), applied on `approval.decided` (`FleetFuelApprovalSubscriber`) or `/sync`, as
   current book + variance; it fails (status `failed`) instead of taking the book below zero or posting into a locked
   period.

### GL postings (1.5) — all balanced, posting key `fleet:fuel:<logId>`

| Case | Debit | Credit | How |
|---|---|---|---|
| Tank issue | `fleet_fuel_expense` (6410), vehicle cost centre, dimensions `vehicleId`, `fuelLogId` | product stock account (`StockPostingService.stockAccount`) at issue cost | `StockService.issue(skipGl)` + `PostingService.post` (journal INV, source `fleet_fuel_log`) |
| Station, fuel card | `fleet_fuel_expense`, cost centre, dimensions | `fleet_fuel_card_payable` (2165), partner = card provider | `PostingService.post` (GEN) — settled later from the provider's statement |
| Station, cash | `fleet_fuel_expense`, cost centre, dimensions | the cash / bank account | `ExpensesService.create(CASH, tags)` → expense posted + paid (category "Fleet fuel") |
| Station, supplier credit | same (when paid) | cash / bank on payment | `ExpensesService.create(CREDIT)` → approved / pending expense; its payment in Expenses is tagged through `FleetFuelExpenseTagger` |
| Reversal | mirror | mirror | `PostingService.reverse` (keeps line cost centres); tank stock back through `StockReversalService.reverseLedgerSource` at the original cost; expenses voided (`ExpensesService.voidExpense`) |
| Dip variance | adjustment loss / stock (or stock / gain) | | `StockService.adjust` (inventory's ADJUSTMENT_LOSS / GAIN rules) |

Expenses module change (additive, no behaviour change for other callers): `ExpensesService.create(CreateInput,
PostingTags)` overload and the `ExpensePostingTagger` SPI — the expense line of a payment posting can carry cost centre,
branch, dimensions and the expense account; expenses no tagger claims post exactly as before.

### Web (1.5)

Menu Fleet → Fuel Logs `/fleet/fuel`, Fuel Tanks `/fleet/fuel/tanks` (+ `/:id`), Fuel Cards `/fleet/fuel/cards`, Fuel
Anomalies `/fleet/fuel/anomalies`, Fuel Settings `/fleet/settings/fuel`; quick entry `/fleet/fuel/new` (phone first:
large inputs, segment buttons, camera capture, sticky save bar; `?vehicleId=` / `?tankId=` prefill), detail
`/fleet/fuel/logs/:id` (+ `/edit` for drafts); vehicle detail tab **Fuel** (totals, efficiency trend chart with the class
band, recent logs). Pages in `web/src/pages/fleet/fuel/`, hooks in `web/src/features/fleet/fuel-api.ts`.

### Tests (1.5)

`FuelPoliciesTest` (unit: full-to-full efficiency, L/h, zero distance, band high / low, overfill tolerance, meter not
increasing / refused, card masking + hash, limit periods); `FleetFuelIT` (9: fuel-card purchase GL + masking + limit /
mismatch flags + blocked card; cash and credit purchases through expenses with the vehicle cost centre + reversal;
tank issue stock + GL + idempotent replay + exactly once + insufficient stock + reversal; 5 concurrent issues on a
contended tank; dip reconciliation immediate / approval with SoD / rejected; efficiency chain, back-dated recompute,
anomalies, acknowledge, reversal recompute, vehicle fuel tab, bands / settings; drafts, validation, receipt upload,
quick entry without `fleet_fuel:post`; tenant isolation; authorization matrix 34 routes).

### Known gaps / next

- No domain events yet (`fleet.fuel.posted` is planned in ADR-S009 §9; add with 1.9 reporting if needed).
- Fuel-card provider statements are settled outside fleet (vendor bill / payment against `fleet_fuel_card_payable`).
- The fuel log stores the person who fuelled as an hr employee; 1.2 driver profiles link to the same employee.

## 1.7 Tyres, incidents and fines

Status: **done** (Track C, worktree `wt/c-tyres`). Migration `V20__fleet_tyres_incidents.sql` (placeholder number).
Code: `FleetTyreService`, `TyrePolicy`, `TyrePositionLayout`, `FleetIncidentService`, `FleetFineService`,
`FleetExpenseSubscriber`, `FleetEmployeeLookup`; controllers `FleetTyreController`, `FleetIncidentController`,
`FleetFineController`.

### Routes (1.7) — 34

| # | Method | Path | Permission | Status | Notes |
|---|---|---|---|---|---|
| 1 | GET | `/fleet/tyres` | `fleet_maintenance:read` | 200 | `{data, meta}`; `search, status, vehicleId, tyreType, page, pageSize, sortBy (serialNumber, brand, status, createdAt, treadDepthMm), sortOrder`; each with `vehicle` and live `stats` |
| 2 | GET | `/fleet/tyres/{id}` | `fleet_maintenance:read` | 200 | + supplier, product, stock location, `events` (newest first), `readings`, `stats` |
| 3 | POST | `/fleet/tyres` | `fleet_maintenance:tyre` | 201 | serial normalised upper case, unique per org (409) |
| 4 | PATCH | `/fleet/tyres/{id}` | `fleet_maintenance:tyre` | 200 | `clear: [...]`; refused when scrapped; product fixed once issued from stock |
| 5 | DELETE | `/fleet/tyres/{id}` | `fleet_maintenance:tyre` | 200 | soft delete only when never fitted (else scrap) |
| 6 | POST | `/fleet/tyres/{id}/fit` | `fleet_maintenance:tyre` | 200 | **`Idempotency-Key` required** (stock issue + GL); `{vehicleId, position, at?, odometer?, hours?, treadDepthMm?, notes?}`; 409 position taken |
| 7 | POST | `/fleet/tyres/{id}/rotate` | `fleet_maintenance:tyre` | 201 | `{position, at?, odometer?, hours?}`; swaps with the tyre at the target position |
| 8 | POST | `/fleet/tyres/{id}/remove` | `fleet_maintenance:tyre` | 201 | `{reason, at?, odometer?, hours?, treadDepthMm?, scrap?}` |
| 9 | POST | `/fleet/tyres/{id}/retread` | `fleet_maintenance:tyre` | 201 | `{cost, newTreadDepthMm, partnerId?, at?}` from `removed` |
| 10 | POST | `/fleet/tyres/{id}/scrap` | `fleet_maintenance:tyre` | 201 | `{reason}`; a fitted tyre is removed with `scrap: true` instead |
| 11 | POST | `/fleet/tyres/{id}/readings` | `fleet_maintenance:tyre` | 201 | tread depth (+ pressure); `belowMinimum` flag |
| 12 | GET | `/fleet/vehicles/{id}/tyres` | `fleet_maintenance:read` | 200 | axle layout with the tyre at each position, spares, `unplaced` |
| 13 | GET | `/fleet/tyre-lookups/products` | `fleet_maintenance:read` | 200 | stock products picker (`search`) |
| 14 | GET | `/fleet/incidents` | `fleet_incident:read` | 200 | `{data, meta}`; `search, status, vehicleId, incidentType, from, to` (business days), with vehicle, driver, `claimCount` |
| 15 | GET | `/fleet/incidents/{id}` | `fleet_incident:read` | 200 | + vehicle, driver, `claims`, `attachments` (signed `url`), `fines` |
| 16 | POST | `/fleet/incidents` | `fleet_incident:create` | 201 | numbered `INC-00001`; `markVehicleBreakdown` moves the vehicle to `breakdown` (source `incident`) |
| 17 | PATCH | `/fleet/incidents/{id}` | `fleet_incident:update` | 200 | `clear: [...]`; `thirdParties` replaces the list; refused when cancelled |
| 18 | POST | `/fleet/incidents/{id}/status` | `fleet_incident:update` | 201 | `{status, reason}`; close needs every claim decided |
| 19 | POST | `/fleet/incidents/{id}/attachments` | `fleet_incident:update` | 201 | multipart `file`, `kind` photo / document, `title`; stored as a vehicle attachment + link |
| 20 | DELETE | `/fleet/incidents/{id}/attachments/{attachmentId}` | `fleet_incident:update` | 200 | removes the stored file |
| 21 | GET | `/fleet/insurance-claims` | `fleet_incident:read` | 200 | `{data, meta}`; `status, incidentId, search` |
| 22 | POST | `/fleet/incidents/{id}/claims` | `fleet_incident:update` | 201 | draft; insurer (partner or name) required |
| 23 | PATCH | `/fleet/insurance-claims/{id}` | `fleet_incident:update` | 200 | while draft / submitted / approved (claimed amount not after approval) |
| 24 | POST | `/fleet/insurance-claims/{id}/status` | `fleet_incident:update` | 201 | `{status: submitted / approved / rejected / settled / withdrawn, amountApproved?, amountReceived?, date?, reason?}` |
| 25 | GET | `/fleet/incident-lookups/employees` | `fleet_incident:read` | 200 | hr employees picker (drivers / operators) |
| 26 | GET | `/fleet/fines` | `fleet_incident:read` | 200 | `{data, meta}`; `search, status, vehicleId, driverEmployeeId, chargedTo, overdue` |
| 27 | GET | `/fleet/fines/{id}` | `fleet_incident:read` | 200 | + vehicle, driver, incident, `expense` (code, status, payment status), `deductions` |
| 28 | POST | `/fleet/fines` | `fleet_incident:create` | 201 | numbered `FIN-00001`; notice number unique per org (409) |
| 29 | PATCH | `/fleet/fines/{id}` | `fleet_incident:update` | 200 | open / disputed only |
| 30 | POST | `/fleet/fines/{id}/status` | `fleet_incident:update` | 201 | `open ⇄ disputed`, `→ cancelled` (reason) |
| 31 | POST | `/fleet/fines/{id}/charge` | `fleet_incident:charge` | 200 | **`Idempotency-Key` required**; `{chargeTo: company / driver, employeeId?, paymentType: CREDIT (default) / CASH, paymentMethod?, accountId?, paymentReference?, notes?}` |
| 32 | POST | `/fleet/fines/{id}/reverse-charge` | `fleet_incident:charge` | 200 | **`Idempotency-Key` required**; `{reason}` |
| 33 | GET | `/fleet/fine-deductions` | `fleet_incident:read` | 200 | payroll list; `status, employeeId` |
| 34 | POST | `/fleet/fine-deductions/{id}/status` | `fleet_incident:charge` | 201 | `{status: exported / settled, reference?}` (manual until hr payroll consumes the event) |

### Data model (1.7)

| Table | Purpose | Notable columns / constraints |
|---|---|---|
| `FleetTyre` | tyre by serial | `serialNumber` unique per org (live), brand / model / size / `tyreType`, `purchaseCost`, `extraCost` (retreads), supplier, optional stock `productId` + `stockLocationId` with `stockIssuedAt` / `stockLedgerCode` / `stockIssueValue` / `issueJournalEntryId`, `status` (in_stock, fitted, removed, retreaded, scrapped), current `vehicleId` + `position` (unique per vehicle while fitted; CHECK fitted ⇔ both set), `fitOdometer` / `fitHours` (lifetime meters at stint start), `kmRun` / `hoursRun` (closed stints), `treadKmBase`, new / current / minimum tread (mm), `retreadCount`, soft delete |
| `FleetTyreEvent` | lifecycle | registered, fitted, rotated, removed, retreaded, scrapped; positions, raw + lifetime meters, closing stint km / hours, tread, cost, reason, `meterReadingId` |
| `FleetTyreReading` | tread readings | tread mm, pressure kPa, vehicle / position / lifetime odometer at the time |
| `FleetIncident` | incident / accident | `incidentNumber`, vehicle, `driverEmployeeId` (hr), time, place, GPS (9,6), type, severity, description, damage, estimate, at fault, injuries, `thirdParties` JSONB, police report / station, `status`, `workOrderId` (1.6, **no FK** — built in parallel), repair reference / cost, soft delete |
| `FleetIncidentAttachment` | photos / documents | link to `FleetVehicleAttachment` (kernel file store, cascade) |
| `FleetInsuranceClaim` | claim | claim no., insurer partner / name, policy, submitted on, claimed / approved / received / excess, `status`, decided / settled on |
| `FleetFine` | traffic fine | `fineNumber`, vehicle, driver, incident, offence time / place / text / code, authority, `noticeNumber` (unique), amount, due date, `status` (open, disputed, charged, settled, cancelled), `chargedTo`, `chargeCount`, `expenseId`, `deductionId`, `settledAt` |
| `FleetFineDeduction` | salary-deduction request | fine, employee, amount, `status` (pending, exported, settled, cancelled; one non-cancelled per fine), export / settle references |
| `ExpenseSourceLink` (expenses) | expense raised by another module | `expenseId` (unique), `sourceType` / `sourceId`, `debitAccountId`, `costCenterId`, `dimensions` — read by `ExpensesService.recordPayment` |

`FleetMeterReading.source` gains `tyre` (fitment / rotation / removal readings, idempotent per value + minute).

### Rules (1.7)

1. Tyre status machine `TyrePolicy` (in_stock → fitted; fitted → fitted / removed / scrapped; removed → fitted /
   retreaded / scrapped; retreaded → fitted / scrapped; scrapped terminal). Positions must exist in the vehicle's layout
   (`TyrePositionLayout`, from the axle configuration and class kind); one tyre per position (vehicle row locked, the
   partial unique index backs it). Events cannot be dated in the future or before the tyre's fitment.
2. Meters of an event: the given raw odometer / hours are recorded through `FleetMeterService.recordAutomated`
   (source `tyre`, monotonic rule); blank = the vehicle's current lifetime meters. A rotation / removal closes the
   running stint (km = lifetime odometer now − at stint start).
3. Cost per km = (purchase cost + retread costs) / km run (closed stints + the running stint), 4 decimals HALF_UP;
   per engine hour likewise. Wear per 1,000 km, projected life and remaining tread % extrapolate from the tread used
   since the current tread started (`treadKmBase`, reset by a retread).
4. Stocked tyre: the first fitment issues one unit (`StockService.issue`, move type internal use, quantity only,
   serial passed for serial-tracked products) from the tyre's stock location or the vehicle's home site, then posts
   Dr `fleet_tyre_expense` (6430, vehicle cost centre, dimensions vehicleId / tyreId) / Cr the product's stock account
   (stock-in rule debit, else `stock_valuation`), key `fleet:tyre:issue:<tyreId>`. The locked tyre row records the
   issue, so replays and re-fitments never issue again. Without a stock purchase cost the issue value becomes it.
5. Incident workflow `IncidentPolicy`: reported → investigating / repairing / closed / cancelled; closing needs every
   claim settled, rejected or withdrawn; reopening a closed incident and cancelling need a reason; cancelled is
   terminal and read-only. Claims: draft → submitted → approved (≤ claimed) → settled (received ≤ approved), rejected /
   withdrawn need a reason.
6. Fines: charge only `open` fines. **Company** → `ExpensesService.createFromSource` (source `fleet_fine`,
   `<fineId>#<chargeNo>`, idempotent while the expense is live): CASH posts Dr 6450 (vehicle cost centre, dims) /
   Cr the paying cash / bank account at once (fine `settled`), CREDIT waits for approval / payment in Expenses —
   `expense.paid` settles the fine, `expense.voided` reopens it. **Driver** → `FleetFineDeduction` (pending) +
   `fleet.fine.deduction_requested`; settled by payroll (or `/fine-deductions/{id}/status`). Reverse charge: voids the
   expense (payments reversed by expenses) or cancels the deduction (`fleet.fine.deduction_cancelled`) — refused once
   payroll settled it.

### Events (1.7)

| Event | When | Payload |
|---|---|---|
| `fleet.incident.reported` | incident created | `organizationId, incidentId, incidentNumber, vehicleId, incidentType, severity, occurredAt` |
| `fleet.fine.charged` | fine charged | `organizationId, fineId, fineNumber, vehicleId, chargedTo, amount, expenseId, deductionId` |
| `fleet.fine.deduction_requested` | fine charged to a driver | `organizationId, deductionId, fineId, fineNumber, employeeId, amount ("150000.00"), vehicleId, offence, offenceAt` |
| `fleet.fine.deduction_cancelled` | driver charge reversed | the same + `reason` |

Consumed: `expense.paid`, `expense.voided` (`FleetExpenseSubscriber`). **hr wiring at merge**: an hr subscriber of
`fleet.fine.deduction_requested` / `_cancelled` creates / cancels the payroll deduction and calls
`/fleet/fine-deductions/{id}/status` (or `FleetFineService.deductionStatus`) when exported / settled.

### GL (1.7)

| Event | Debit | Credit | Key |
|---|---|---|---|
| Stocked tyre first fitted | `fleet_tyre_expense` 6430 (vehicle cost centre; vehicleId, tyreId) | product stock account | `fleet:tyre:issue:<tyreId>` |
| Fine charged to company, paid | `fleet_fines_expense` 6450 (vehicle cost centre; vehicleId, fineId) | cash / bank | expenses' `expense_payment:<paymentId>` |
| Charge reversed | reversal of the payment entry (`PostingService.reverse` via `ExpensesService.voidExpense`) | | |

### Web (1.7)

Fleet → Tyres `/fleet/tyres` (+ `/:id`), Incidents & Claims `/fleet/incidents` (+ `/new`, `/:id`, `/:id/edit`),
Traffic Fines `/fleet/fines` (+ `/:id`, salary-deductions tab); vehicle detail tab **Tyres** (axle diagram; tap a
position to fit / rotate / remove / measure). Hooks in `web/src/features/fleet/tyres-api.ts`.

### Tests (1.7)

`FleetSafetyPoliciesTest` (unit: tyre status machine, cost per km / wear, wheel layouts, incident / claim / fine /
deduction state machines), `FleetTyresIT` (lifecycle with swap and cost/km, stock issue once + replay + GL on the cost
centre, concurrent fitment on one position, tenant isolation, 13-route matrix), `FleetIncidentsIT` (incident + photos
+ claims + close guard, fine → company CASH: GL balanced / replay / reversal, CREDIT paid in Expenses → settled,
driver deduction + events + settle / reversal rules, tenant isolation, 21-route matrix).

## 1.8 Telematics

Status: **done** (Track C, worktree `wt/c-tyres`). Migration `V21__fleet_telematics.sql` (placeholder number).
Code: SPI `TelematicsProvider` (ADR-S009 §7), `MockTelematicsProvider`, `TelematicsProviders`,
`FleetTelematicsService`, `FleetTelematicsSyncJob`, `GeoDistance`; controller `FleetTelematicsController`.

### Configuration

`app.fleet.telematics.provider` (env `FLEET_TELEMATICS_PROVIDER`, default `mock`), `base-url` / `api-key`
(`FLEET_TELEMATICS_URL` / `FLEET_TELEMATICS_API_KEY`, only "configured yes / no" is ever exposed), `sync-cron`
(`FLEET_TELEMATICS_SYNC_CRON`, default every 5 min), `retention-days` (`FLEET_TELEMATICS_RETENTION_DAYS`, default 365,
minimum 7). Read with `@Value` defaults, so `application.yml` needs no entry.

### Routes (1.8) — 12

| # | Method | Path | Permission | Status | Notes |
|---|---|---|---|---|---|
| 1 | GET | `/fleet/telematics/settings` | `fleet:settings` | 200 | provider, available providers, URL / key configured, retention |
| 2 | GET | `/fleet/telematics/devices` | `fleet_vehicle:read` | 200 | `includeInactive, vehicleId`; with vehicle, sync status, last fix |
| 3 | POST | `/fleet/telematics/devices` | `fleet:settings` | 201 | `{vehicleId?, provider?, externalUnitId, name?, imei?, simNumber?, syncOdometer?, syncHours?}`; one active device per unit and per vehicle (409) |
| 4 | PATCH | `/fleet/telematics/devices/{id}` | `fleet:settings` | 200 | re-map (resets the cursors), `clear: ["vehicleId"]` unmaps, `isActive` |
| 5 | DELETE | `/fleet/telematics/devices/{id}` | `fleet:settings` | 200 | deletes a device without data, otherwise deactivates it |
| 6 | POST | `/fleet/telematics/sync` | `fleet:settings` | 201 | sync the caller's organization now → `{devices, positions, events, alerts, meterReadings, errors, purged}` |
| 7 | GET | `/fleet/telematics/positions/latest` | `fleet_trip:read` | 200 | last fix per tracked vehicle + open alerts |
| 8 | GET | `/fleet/vehicles/{id}/positions` | `fleet_trip:read` | 200 | `{data, meta}` newest first; `from, to` (default last 24 h), `page, pageSize ≤ 500` |
| 9 | GET | `/fleet/vehicles/{id}/distance` | `fleet_trip:read` | 200 | `from, to` → `{distanceKm, points, first, last}` |
| 10 | GET | `/fleet/vehicles/{id}/last-position` | `fleet_trip:read` | 200 | `{vehicleId, position}` (null when never reported) |
| 11 | GET | `/fleet/telematics/events` | `fleet_trip:read` | 200 | `{data, meta}`; `vehicleId, eventType, alertsOnly, open, from, to` |
| 12 | POST | `/fleet/telematics/events/{id}/acknowledge` | `fleet_trip:update` | 201 | alerts only, once; `{note?}` |

### Data model (1.8)

| Table | Purpose | Notable columns / constraints |
|---|---|---|
| `FleetTelematicsDevice` | unit ↔ vehicle | provider, `externalUnitId` (unique per org + provider while active), vehicle (one active device per vehicle), `syncOdometer` / `syncHours`, cursors `positionsSyncedTo` / `eventsSyncedTo`, `lastSyncAt` / `lastSyncStatus` (ok, partial, error) / `lastError`, cached last fix and meters |
| `FleetPosition` | GPS fix | vehicle, device, `recordedAt`, lat / lon NUMERIC(9,6), speed, `externalId` (unique per device → re-pulls never duplicate); index (org, vehicle, time); retention purge by (org, time) |
| `FleetTelematicsEvent` | ignition / alert | type (ignition_on / off, speeding, idling, geofence_enter / exit, harsh_braking / acceleration, other), time, position, speed, `data` JSONB, `externalId` (unique per device), `isAlert`, `severity` (info, warning, critical — speeding ≥ 30 km/h over the limit is critical), acknowledgement |

Volume: positions are append-only; every query goes by (organization, vehicle, time) and is paged (≤ 500 per page,
public API ≤ 10,000 points); the sync job purges positions older than the retention in batches of 5,000. Monthly
partitioning can be added later without an API change.

### Sync (job `fleet-telematics-sync`, ShedLock, `app.jobs.enabled` + fleet module)

Per organization with an active device, per mapped device: positions from the cursor (first run: last hour, never
more than 24 h back) to now; the meter snapshot through `FleetMeterService.recordAutomated` (source `telematics`,
source id `tm:<device>:odo|hrs:<snapshot ms>` → idempotent; only meters the vehicle tracks; monotonic rule applies —
a lower provider value fails that meter only); then the provider's events since the oldest device cursor (alerts
publish `fleet.telematics.alert_raised`); each step in its own transaction, the outcome on the device.

### Public API for 1.3 dispatch (`FleetTelematicsService`)

- `positionsBetween(vehicleId, from, to)` → `List<PositionPoint(at, latitude, longitude, speedKmh, ignition)>`, oldest
  first.
- `distanceKm(vehicleId, from, to)` → `BigDecimal` km (3 decimals): haversine over consecutive fixes, segments
  implying > 250 km/h skipped as GPS glitches (`GeoDistance.pathKm`).
- `lastKnownPosition(vehicleId)` → `Optional<PositionPoint>`.

### Web (1.8)

Fleet → Live Fleet `/fleet/live` (last positions, alerts with acknowledge), Telematics `/fleet/telematics` (provider,
devices ↔ vehicles, sync now); vehicle detail tab **Telematics** (unit, last position, distance 24 h / 7 days, recent
positions, alerts). Hooks in `web/src/features/fleet/telematics-api.ts`.

### Tests (1.8)

`FleetSafetyPoliciesTest` (distance with glitch filter, mock provider determinism, alert severity), `FleetTelematicsIT`
(device mapping rules, sync → positions / one reading per snapshot / events / alert + outbox, distance ≈ 30 km for an
hour of mock fixes, positions paging, last position, acknowledge, failure isolation, tenant isolation, 12-route
matrix).

### Known gaps (1.7 / 1.8)

- hr payroll does not consume `fleet.fine.deduction_requested` yet (wired at merge with 1.2's hr integration).
- `FleetIncident.workOrderId` is a free id until 1.6 work orders are merged (then validate / link).
- No provider push webhook (pull only); no vendor adapter beyond the mock; no geofence editor (geofence events come
  from the provider).

## 1.3 Trips, delivery planning & dispatch

Status: **done** (Track C, 2026-10-29). Design: ADR-S009 §3 (tables), §6 (GL). Migration `V22__fleet_dispatch.sql`
(placeholder number, renumbered at merge). Permissions: the existing `fleet_dispatch:*`, `fleet_trip:*` and
`fleet:settings` keys (none added); invoicing a delivery order also needs `invoice:create`. Events (new in
`packages/shared/src/events.ts`): `fleet.trip.dispatched`, `fleet.trip.delivered`, `fleet.trip.closed`,
`fleet.trip.returned`, `fleet.delivery.discrepancy_decided`.

### Routes (1.3) — 55, all under `/api/v1`, `@RequiresModule("fleet")`, package `fleet.web.dispatch`

Idempotent (Idempotency-Key required, answer 200): trip dispatch, proof of delivery, return, discrepancy approve,
delivery-order invoice — they move stock or raise accounting documents. Other POSTs answer 201.

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET / POST | `/fleet/customer-sites` | `fleet_dispatch:read` / `create` | `search, partnerId, zone, includeInactive, page, pageSize`; code `SITE-0001` when blank; lat + lon together |
| GET / PATCH / DELETE | `/fleet/customer-sites/{id}` | `read` / `update` / `update` | delete = soft delete, or deactivate when used by a delivery order |
| GET | `/fleet/delivery-orders` | `fleet_dispatch:read` | `search, status (… / active), partnerId, siteId, sourceType, dateFrom, dateTo, page, pageSize`; rows with ordered / unplanned / delivered totals and trip counts |
| GET | `/fleet/delivery-orders/{id}` | `fleet_dispatch:read` | + site, lines (remaining, t / m³ per unit and factor source), trips, discrepancies, charges |
| POST | `/fleet/delivery-orders` | `fleet_dispatch:create` | manual: `partnerId, siteId?, locationId?, requestedDate, windowFrom/To (HH:MM), priority, contact, costCenterId, lines[{productId, quantity, uomId?, unitPrice?, taxId?, batchNumber?}]` |
| POST | `/fleet/delivery-orders/from-sales-order` | `fleet_dispatch:create` | `{sourceId = orders Order id, siteId?, locationId?, requestedDate?, …}` via `OrdersService.getOrder`; confirmed / in progress / completed only; POS sales (table / cash session) refused; 409 when a live DO exists |
| POST | `/fleet/delivery-orders/from-invoice` | `fleet_dispatch:create` | sales invoice via `InvoiceService.findOne`: draft → stock mode `dispatch` (invoice marked), posted / paid → `invoiced` |
| PATCH | `/fleet/delivery-orders/{id}` | `fleet_dispatch:update` | header; `clear: [...]` |
| POST | `/fleet/delivery-orders/{id}/cancel` | `fleet_dispatch:cancel` | `{reason}`; refused once goods left the yard; open trips cancelled; marked draft invoice freed |
| POST | `/fleet/delivery-orders/{id}/close` | `fleet_dispatch:update` | short-close; no open trips |
| GET | `/fleet/delivery-orders/{id}/load-plan` | `fleet_dispatch:read` | preview split: `vehicleId, trailerId, payloadT, volumeM3, maxLoads` |
| POST | `/fleet/delivery-orders/{id}/plan-loads` | `fleet_dispatch:plan` | creates the loads; with `vehicleId` (+ `trailerId`, `driverId`) they are assigned (eligibility + capacity) |
| POST | `/fleet/delivery-orders/{id}/trips` | `fleet_dispatch:plan` | one load with explicit `lines[{deliveryOrderLineId, quantity, batchNumber?}]` (≤ remaining) |
| GET | `/fleet/delivery-orders/{id}/delivery-charge` | `fleet_dispatch:read` | charge preview `{facts, rule, charge}` |
| POST | `/fleet/delivery-orders/{id}/invoice` | `fleet_dispatch:update` (+ `invoice:create`) | draft customer invoice: billable goods (unless invoiced already) + transport charge; Idempotency-Key |
| GET | `/fleet/dispatch-board` | `fleet_dispatch:read` | `from, to (≤ 62 days), partnerId, siteId` → `{days[{date, trips}], unplanned[delivery orders]}` |
| GET | `/fleet/dispatch/sources` | `fleet_dispatch:read` | `type = sales_order / invoice, search` — documents without a live DO |
| GET | `/fleet/dispatch/lookups` (+ `/customers`, `/products`) | `fleet_dispatch:read` | vehicles with payload / volume, drivers, transporters + rates, locations, cost centres, taxes; product search with load factors |
| GET | `/fleet/trips` | `fleet_trip:read` | `search, status (… / open / on_road / active), dateFrom, dateTo, vehicleId, driverId, deliveryOrderId, partnerId, mine` (driver phone: trips of the caller's driver profile) |
| GET / PATCH | `/fleet/trips/{id}` | `fleet_trip:read` / `update` | detail + lines, delivery order + site, POD (signed signature / photo URLs), discrepancies; PATCH date, location, cost centre, purpose, notes while open |
| POST | `/fleet/trips/{id}/assign` | `fleet_dispatch:plan` | `{carrier: own, vehicleId, driverId?, trailerId?}` or `{carrier: transporter, transporterPartnerId, transporterRateId \| freightBasis + freightRate, transporterPlate?, transporterDriverName?}` |
| GET | `/fleet/trips/{id}/assignment-check` | `fleet_trip:read` | `vehicleId, trailerId, driverId` → `{eligible, reasons, warnings, capacityT, capacityM3, …}` |
| POST | `/fleet/trips/{id}/start-loading` | `fleet_trip:dispatch` | `{odometer?}` |
| POST | `/fleet/trips/{id}/loaded-quantity` | `fleet_trip:dispatch` | manual loaded quantity `lines[{loadLineId, quantity, batchNumber?}]` → `weighed` |
| POST | `/fleet/trips/{id}/dispatch` | `fleet_trip:dispatch` | `{odometer?}`; stock issue once; delivery-note number `DN-000001`; Idempotency-Key |
| POST | `/fleet/trips/{id}/depart` | `fleet_trip:dispatch` | → `in_transit` |
| POST | `/fleet/trips/{id}/proof-of-delivery` | `fleet_trip:deliver` | `{receiverName, receiverPhone?, receivedAt?, latitude?, longitude?, signature (PNG / JPEG data URL)?, endOdometer?, lines[{loadLineId, deliveredQty, returnedQty?, reason?, notes?}]}`; Idempotency-Key |
| POST | `/fleet/trips/{id}/pod-photo` | `fleet_trip:deliver` | multipart `file` (image), after the POD |
| POST | `/fleet/trips/{id}/close` | `fleet_trip:close` | `{endOdometer?, distanceKm?}` → distance + freight |
| POST | `/fleet/trips/{id}/cancel` | `fleet_trip:cancel` | `{reason}`, before dispatch |
| POST | `/fleet/trips/{id}/return` | `fleet_trip:close` | `{reason, reasonCode?}` whole load back; Idempotency-Key |
| GET | `/fleet/trips/{id}/delivery-note` | `fleet_trip:read` | PDF (OpenPDF); reprints say COPY n |
| GET | `/fleet/delivery-discrepancies` (+ `/{id}`) | `fleet_dispatch:read` | `status, kind, deliveryOrderId, partnerId, page, pageSize` |
| POST | `/fleet/delivery-discrepancies/{id}/approve` | `fleet_dispatch:approve_discrepancy` | `{notes?}`; Idempotency-Key |
| POST | `/fleet/delivery-discrepancies/{id}/reject` | `fleet_dispatch:approve_discrepancy` | `{notes}` required |
| POST | `/fleet/delivery-discrepancies/{id}/sync` | `fleet_dispatch:approve_discrepancy` | apply an inbox decision now |
| GET / POST | `/fleet/transporter-rates` | `fleet_dispatch:read` / `fleet:settings` | `{partnerId (supplier), name, basis per_trip / per_tonne / per_km, rate, zone?, effectiveFrom/To?}` |
| PATCH / DELETE | `/fleet/transporter-rates/{id}` | `fleet:settings` | delete when unused, otherwise deactivate |
| GET / POST | `/fleet/delivery-charge-rules` | `fleet_dispatch:read` / `fleet:settings` | `{name, partnerId?, zone?, minDistanceKm?, maxDistanceKm?, basis flat / per_trip / per_tonne / per_m3 / per_km, rate, minAmount?, taxId?}` |
| PATCH / DELETE | `/fleet/delivery-charge-rules/{id}` | `fleet:settings` | delete when unused, otherwise deactivate |
| GET | `/fleet/product-load-factors` (+ `/{productId}` resolved) | `fleet_dispatch:read` | |
| PUT / DELETE | `/fleet/product-load-factors/{productId}` | `fleet:settings` | `{tonnesPerUnit?, cubicMetresPerUnit?}` per product base unit |

Approvals are also decided in the approvals inbox (`POST /approvals/{requestId}/decide`, entity type
`fleet_delivery_discrepancy`; `FleetDiscrepancyApprovalSubscriber` applies `approval.decided`).

### Data model (V931)

| Table | Purpose | Notable columns |
|---|---|---|
| `FleetCustomerSite` | construction site / delivery address | `code` (SITE-0001), `partnerId`, address, city, `latitude` / `longitude`, contact, `accessNotes`, `zone`, `distanceKm` (from the yard), soft delete |
| `FleetProductLoadFactor` | planning factor per product | `tonnesPerUnit`, `cubicMetresPerUnit` per base unit (one per product) |
| `FleetTransporterRate` | agreed freight of a transporter | `partnerId` (supplier), `basis`, `rate`, `zone`, effective dates |
| `FleetDeliveryChargeRule` | transport charge to the customer | `partnerId?`, `zone?`, distance band, `basis`, `rate`, `minAmount`, `taxId` |
| `FleetDeliveryOrder` | delivery order | `code` (DO-000001), customer, site, `sourceType` manual / sales_order / invoice + `sourceId` (one live DO per source), `stockMode` dispatch / invoiced, `goodsInvoiceId/Number/Kind` (credit-note target), `chargesInvoiceId`, yard `locationId`, cost centre, `requestedDate`, window, priority, status |
| `FleetDeliveryOrderLine` | product line | `uomId` + snapshot `uomFactor`, `orderedQty`, `plannedQty`, `dispatchedQty`, `deliveredQty`, `returnedQty`, price / discount / tax (for invoicing and credit notes), batch |
| `FleetLoad` | trip | `code` (TRP-000001), sequence, `plannedDate`, status, `carrier` own / transporter, vehicle / trailer / driver or transporter (+ rate, plate, driver name), freight basis / rate / amount, capacity, planned / loaded / delivered tonnes and m³, yard location, cost centre, start / end odometer + reading ids, distance + source, weigh source (`weighSourceType` / `weighSourceId`), `stockIssuedAt`, delivery note number + print count, lifecycle timestamps |
| `FleetLoadLine` | product on a trip | planned / loaded / dispatched / delivered / returned / **billable** quantity, t / m³ per unit, batch, stock ledger code, issue unit cost and value, return ledger code |
| `FleetProofOfDelivery` | POD (one per trip) | receiver, time, GPS + source (device / telematics / none), signature and photo file ids (kernel file store, owner type `fleet_trip`) |
| `FleetDeliveryDiscrepancy` | discrepancy | `code` (DSC-000001), kind shortage / excess / returned, reason code, dispatched / delivered / difference qty, status, approval request, credit note id / number / amount |
| `FleetDeliveryCharge` | charge put on an invoice | rule, basis, quantity, rate, amount, tax, invoice |

`FleetCheckout."tripId"` and `FleetDriverClaim."tripId"` now reference `FleetLoad` (FK `NOT VALID`); the check-out and
claim services answer 400 `Trip not found` for an unknown trip.

### Rules (1.3)

1. **Sources.** A delivery order comes from a confirmed back-office sales order (orders module `Order`, statuses
   confirmed / in progress / completed, never a POS sale — orders with a table or cash session are refused), a sales
   invoice, or manual entry. Lines keep the source line's unit (sales orders: the product's base unit), price, discount
   and tax. **Stock mode** `dispatch`: fleet issues the goods per load (manual, un-billed sales order, draft invoice —
   the draft invoice is marked through the additive `InvoiceService.setStockIssuedByDelivery(id, ref)`, so posting it
   skips its automatic stock issue; cancelling the DO clears the mark). `invoiced`: the source already issued the goods
   (posted invoice, or an order billed through the POS spine): dispatch moves no stock.
2. **Load factors** (t / m³ per product base unit, `FleetLoadFactors`): fleet factor → `Product.weight` (kg) /
   `Product.volume` (m³) → the base unit (t / kg / m³). Times the line's unit factor. Phase 4 replaces this with UoM
   densities.
3. **Planning** (`LoadPlanner`): greedy fill per load within payload and body volume (vehicle + trailer, or explicit),
   rounded down to the unit step (whole units when the unit's rounding precision ≥ 1); a line without factors is not
   limited (warning); one unit above capacity → 400; `maxLoads` guard. Planning locks the delivery order.
4. **Assignment.** Own fleet: the vehicle (not a trailer) passes `FleetDispatchEligibilityService.assertEligible`
   (status + blocking defects), the trailer must be of a trailer class and eligible, the driver passes
   `DriverEligibilityPolicy` on the planned date (`… cannot be dispatched`); planned / weighed tonnage and m³ must fit
   the combined capacity (`Trip TRP-… carries 20 t but VEH-… takes 10 t; split the load`). Transporter: a supplier
   partner and its active rate card or an explicit basis + rate. Vehicle and driver are re-checked (today) at loading
   and at dispatch. Lock order: delivery order → trip → vehicle → driver.
5. **Lifecycle** (`TripStatusPolicy`): planned → loading → weighed → dispatched → in transit → delivered → closed;
   loading / weighing / in transit are optional steps; cancelled before dispatch; returned after it. Odometer readings
   go through `FleetMeterService.recordAutomated` (source `trip`, source ids `<tripId>:start` / `:end`).
6. **Weighing.** Manual loaded quantity per line, or `recordWeighedQuantity(tripId, netQty, uom, sourceType, sourceId)`
   (1.4b ticket): `t` / `kg` spread over the lines by planned tonnage, another unit only for single-line trips;
   idempotent per source document, re-weigh allowed until dispatch.
7. **Dispatch, exactly once.** Delivery order and trip locked `FOR UPDATE`, status must be open and `stockIssuedAt`
   empty; each tracked line is issued from the trip's location through `StockService.issue` (product, batch — manual
   pick when given — line unit + factor, move type `issue`, source `fleet_trip` / trip id) — the inventory engine posts
   Dr COGS / Cr stock (balanced). The loaded quantity (default: planned) becomes dispatched and billable; any
   difference to the plan is plannable again. Concurrent or replayed dispatches cannot issue twice (row lock + status +
   Idempotency-Key).
8. **POD.** Delivered and returned quantity per line; a difference (dispatched − delivered − returned) or a return
   needs a reason. Returned goods are received back at once at the issue cost (`StockService.receiveReturn`, Dr stock /
   Cr COGS, source `fleet_trip_return`) and become plannable again. GPS: the device's, else the vehicle's telematics fix
   within 6 h (`FleetTelematicsService.lastKnownPosition`). Signature: PNG / JPEG data URL stored in the kernel file
   store; photo via `/pod-photo`. A full **return** puts every line back the same way.
9. **Discrepancies** (`FleetDiscrepancyService`): one per difference (shortage / excess) and per return, routed through
   the approval engine (default workflow "Fleet delivery discrepancy", step "Dispatch manager", permission
   `fleet_dispatch:approve_discrepancy`; an inactive workflow auto-approves); the recorder and the driver cannot decide
   (403). Approved: the load line's billable quantity drops by the difference (rises for an excess); when the goods were
   already invoiced a **draft credit note** is created through `CreditNoteService.create` (reversing the goods invoice
   when it is a sales-invoice document) with product-less revenue lines, so posting it moves no stock — AR posts it.
   Rejected: the customer stays billed for the dispatched quantity.
10. **Close.** Distance: manual → odometer difference → telematics path distance (`distanceKm`) → site distance.
    Transporter freight = `FreightCalculator` (per trip / per tonne delivered (else loaded / planned) / per km).
11. **Invoicing & transport charges.** Once all trips are finished and no discrepancy is pending, `/invoice` creates
    one draft sales invoice through `InvoiceService.create`: goods at their billable quantities (only when not invoiced
    yet; the invoice is marked so posting does not issue them again) + the transport charge of the most specific
    `FleetDeliveryChargeRule` (customer + zone > customer > zone > general; distance band; per basis; minimum) on
    account 4250 Transport & Delivery Revenue (`fleet_transport_revenue`). Posting it (AR) gives Dr AR / Cr revenue +
    transport revenue + tax.

### GL (1.3)

| Event | Debit | Credit | Posted by |
|---|---|---|---|
| Dispatch (stock mode dispatch) | COGS (inventory posting rules) | Stock | `StockService.issue`, once per load line |
| Returned goods (POD return / full return) | Stock | COGS | `StockService.receiveReturn` |
| Customer invoice of the delivery (posted by AR) | AR | Revenue, 4250 transport revenue, output tax | invoicing |
| Approved shortage on invoiced goods (posted by AR) | Revenue (product category / default), tax | AR | invoicing credit note |
| Transporter freight | — | — | computed on the trip (`freightAmount`); vendor bill / accrual in 1.9 freight bills |

### Public API for later sub-modules

- **1.4b weighbridge**: `FleetDispatchService.recordWeighedQuantity(tripId, netQty, uom, sourceType, sourceId)` (e.g.
  `uom = "kg"`, `sourceType = "weighbridge_ticket"`, `sourceId` = ticket id); `FleetDispatchService.get(tripId)` /
  `require(tripId, lock)` to show / validate the trip; trips of a delivery order via
  `FleetDeliveryOrderService.get(id).trips`.
- **1.9 costing / reports**: `FleetLoad` (`freightAmount`, `distanceKm`, `loadedTonnes`, `deliveredTonnes`,
  `carrier`, vehicle / driver / transporter, cost centre), `FleetLoadLine` (`stockValue`, billable quantity),
  `FleetDeliveryCharge`, `FleetDeliveryDiscrepancy`; `FreightCalculator.amount(basis, rate, tonnes, km)`;
  `FleetDeliveryInvoiceService.transportRevenueAccount()`; events `fleet.trip.closed` (distance, freight) and
  `fleet.delivery.discrepancy_decided`.
- Additive in **invoicing**: `InvoiceService.setStockIssuedByDelivery(invoiceId, deliveryRef)` + the skip in
  `InvoicingWorkflowsInitializer.postInvoice` (only for marked invoices; POS and other invoices unchanged).

### Web (1.3)

Dispatch Board `/fleet/dispatch` (day columns + unplanned orders, assign dialog with live eligibility), Delivery
Orders `/fleet/delivery-orders` (+ `/new`, `/:id` with plan-loads, trips, charges, invoice), Trips `/fleet/trips`
(+ `/:id` with state actions, delivery note print, POD), My Trips `/fleet/my-trips` (+ `/:id`: phone POD with signature
pad, photo, GPS), Customer Sites `/fleet/customer-sites`, Delivery Discrepancies `/fleet/delivery-discrepancies`,
Dispatch Settings `/fleet/settings/dispatch` (charges, transporter rates, load factors). Hooks in
`web/src/features/fleet/dispatch-api.ts`, routes in `web/src/pages/fleet/dispatch/dispatch-routes.tsx`.

### Tests (1.3)

`DispatchPoliciesTest` (unit) and `FleetDispatchIT` (see Status). `FleetClaimsIT` now refers to a real trip.

### Known gaps / owner decisions (1.3)

- Billing a sales order through the orders module's own `generateInvoice` (POS billing spine, frozen) still deducts
  stock: invoice delivered sales orders from the delivery order (`/invoice`) instead; documented for the owner.
- An approved **excess** on invoiced goods adjusts the billable quantity only (no automatic supplementary invoice).
- Editing a draft source invoice after its DO exists does not re-sync the DO lines.
- Transporter freight is computed on the trip; posting it (vendor bill / accrual, Dr 6460 outbound freight) is 1.9.
- Load factors are a stop-gap until Phase 4 UoM / density work.
## 1.4 Weighbridge — done (1.4a core + 1.4b dispatch wiring)

Requirements §5.1, ADR-S009 §3 / §7. 1.4a = stations, devices + adapter SPI, stored tares, the ticket workflow with
tolerance exceptions, print / void / reissue, inbound weighing → procurement GRN, the public API and the event.
**1.4b** (done, see "1.4b Weighbridge ↔ dispatch wiring" below) wires outbound tickets to delivery orders / trips: the
foreign keys on `WeighbridgeTicket."deliveryOrderId"` / `"tripId"`, the completed ticket drives the trip's loaded
quantity and therefore its stock issue, void handling, and the "weighbridge required for dispatch" rules.

Migration `V23__fleet_weighbridge.sql` (placeholder number, renumbered at merge). Code: `fleet.application.Weighbridge*`
(+ `AsciiFrameParser`, `StableWeightReader`, `*WeighbridgeAdapter`), `fleet.web.weighbridge`.

### Routes (36, all under `/api/v1/fleet/weighbridge`, `@RequiresModule("fleet")`)

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/stations` · `/stations/{id}` | `weighbridge:read` | `includeInactive`; detail with devices |
| POST / PATCH | `/stations` · `/stations/{id}` | `weighbridge:configure` | code (unique), name, branch, receiving store (`locationId`), `tolerancePct` (0.5), `toleranceKg` (20), `tareValidityDays` (30), `minWeightKg` (200) / `maxWeightKg` (100 000), `isActive`; `clear` |
| GET | `/devices` | `weighbridge:read` | `stationId, includeInactive`; last read / status / error |
| POST / PATCH | `/devices` · `/devices/{id}` | `weighbridge:configure` | `adapterType` (simulator, ascii-tcp, ascii-serial), `config` (validated by the adapter), `readTimeoutMs` (500–60 000, capped by `FLEET_WEIGHBRIDGE_MAX_READ_TIMEOUT_MS`) |
| POST | `/devices/{id}/test-read` | `weighbridge:configure` | reachability + one stable read; 201 `{ok, reachable, detail, weightKg, rawFrame | error}` |
| POST | `/devices/{id}/read` | `weighbridge:weigh` | live stable weight for the big display (nothing stored); 503 unreachable / timeout / format, 409 motion / overload (`reason`) |
| POST | `/devices/{id}/simulator-weight` | `weighbridge:configure` | `{weightKg, mode?, motionFrames?}` — simulator only (demo / tests) |
| GET / PUT | `/product-settings` · `/product-settings/{productId}` | read / `configure` | `kgPerUnit` (kg per product base unit), `tolerancePct`, `toleranceKg`; all null deletes |
| GET | `/lookups` · `/lookups/products` · `/lookups/partners` · `/lookups/purchase-orders` | `weighbridge:read` | stations, devices, adapters, subject types, units, stores; searches (`role` customer / supplier); POs active / partially received with lines |
| GET | `/tickets` | `weighbridge:read` | `{data, meta}`; `queue` (live, open, awaiting_second, exceptions, completed, void, cancelled, all), `stationId, subjectType, partnerId, productId, vehicleId, search, manual, outOfTolerance, from, to, page, pageSize ≤ 200` |
| GET | `/tickets/summary` · `/tickets/{id}` | `weighbridge:read` | queue counts; detail with exceptions, reissue chain, goods receipt, operators, device codes |
| POST / PATCH | `/tickets` · `/tickets/{id}` | `weighbridge:weigh` | register / edit header (open or awaiting second weighing only) |
| POST | `/tickets/{id}/first-weigh` · `/second-weigh` | `weighbridge:weigh` | `{deviceId}` or `{manualKg, manualReason}` (+ `weighbridge:manual_entry`, else 403); `saveTare` on the second; a repeated capture answers 409 |
| POST | `/tickets/{id}/stored-tare` | `weighbridge:weigh` | `{tareId?}` — the plate's valid stored tare as one weighing |
| POST | `/tickets/{id}/cancel` | `weighbridge:weigh` | `{reason}`; open / awaiting second only |
| POST | `/tickets/{id}/print` | `weighbridge:weigh` | `application/pdf` (A5); completed or void; counts copies (`printCount`, original / copy n) |
| POST | `/tickets/{id}/void` | `weighbridge:void` | `{reason, reissue?, copyWeights? (default true), corrections?}` → `{voided, reissued}` |
| POST | `/tickets/{id}/goods-receipt` | `weighbridge:weigh` | inbound: create the draft GRN if missing (`locationId?`); idempotent (`created: false`) |
| GET / POST | `/tares` | read / `weigh` | `vehicleId, plate, state` (current, valid, expired, all); weigh an empty truck (device or manual) |
| POST | `/tares/{id}/revoke` | `weighbridge:configure` | `{reason}` |
| GET | `/exceptions` | `weighbridge:read` | `status, stationId`; each row has `ownTicket` |
| POST | `/exceptions/{id}/approve` · `/reject` · `/sync` | `weighbridge:approve_exception` | reject needs `notes`; sync applies an inbox decision |
| GET | `/reports/discrepancies` | `weighbridge:read` | `groupBy` product / partner / station / vehicle / subject / day / month, `stationId, subjectType, partnerId, productId, from, to` |

### Data model (V941)

| Table | Notable columns |
|---|---|
| `WeighbridgeStation` | code (unique), branch, receiving store, tolerance % / kg, tare validity days, min / max weight |
| `WeighbridgeDevice` | station, code (unique), `adapterType`, `config` JSONB (no secrets), `readTimeoutMs`, last read / status / error |
| `WeighbridgeProductSetting` | product (unique), `kgPerUnit`, tolerance overrides |
| `FleetVehicleTare` | vehicle (optional) + normalised plate, `tareKg`, `weighedAt`, `expiresAt`, station / device, source device / manual (+ raw frame / reason), `ticketId`, status active / superseded / revoked; one active per plate |
| `WeighbridgeTicket` | number `WBT-<year>-000001` (sequence), station, `subjectType` (sale_delivery, purchase_receipt, internal_haulage, other) → `direction`, status, truck (vehicle or ad-hoc plate; a plate of a fleet vehicle links to it), trailer, driver (fleet driver or name), transporter, partner, `salesOrderId`, `deliveryOrderId` / `tripId` (**text, no FK — 1.4b**), PO + line, reference, product, ordered qty + unit, `expectedKg`, store; per weighing (first / second): kg, time, source device / manual / stored_tare, device, raw frame, manual reason, user, tare id; gross / tare / net kg, `netQty` + unit, variance kg / %, allowed kg, tolerance used, `withinTolerance`, `isManual`, `usedStoredTare`, completion, print counters, cancel / void fields, `reissuedFromId` / `replacedById`, `goodsReceiptId` / `goodsReceiptLineId` (unique); one live ticket per plate (partial unique index) |
| `WeighbridgeException` | ticket, expected / net / variance / allowed, status pending / approved / rejected, `approvalRequestId`, raised / decided by; one pending per ticket |

### Rules

1. **Device reads** (`WeighbridgeAdapter.readStable(device, timeout)`): frames are parsed by `AsciiFrameParser`
   (default `ST,GS,+0032150kg` style: status ST / US / MO / OL, gross / net marker, sign, weight, unit kg / t / g / lb;
   configurable `frameRegex` with named groups, status tokens, default unit, implied decimals) and
   `StableWeightReader` returns once `stableFrames` (default 3) consecutive stable frames show the same weight; motion
   resets the run, overload fails at once; everything is bounded by the device timeout (capped by
   `FLEET_WEIGHBRIDGE_MAX_READ_TIMEOUT_MS`, default 15 s). The read runs **outside** the database transaction; the
   ticket row is then locked `FOR UPDATE` and its state re-checked (two captures cannot both land). Device captures
   store the raw frame and the device id. `ascii-serial` reads the frames from the serial device file (line settings
   configured on the host with `stty`); a serial-to-Ethernet converter with `ascii-tcp` is the recommended set-up.
2. **Plausibility**: a captured weight below the station minimum (empty platform) or above the maximum is refused.
3. **Net** = heavier − lighter weighing, so outbound (tare first) and inbound (gross first) tickets work the same;
   equal weighings are refused; a stored tare that is heavier than the weighed truck is refused.
4. **Expected weight / units** (`WeighbridgeUnits`, no full UoM engine — Phase 4): kg per 1 document unit from (a) a
   weight unit (KG category: t = 1 000 kg), (b) a product conversion (`ProductUomConversion`, the ADR-S003 density
   bridge) to a weight unit, (c) the document unit in the product's base unit when that is a weight unit, (d) the
   product's weighbridge setting `kgPerUnit` (kg per base unit — e.g. 1 600 kg per m³ of sand). A ticket with an
   ordered quantity in a unit without any conversion is refused at registration. Net quantity is stored in the
   document unit (else the product's base unit when convertible).
5. **Tolerance**: allowed = max(absolute kg, expected × % / 100); product settings override the station per field;
   |net − expected| > allowed → exception. No ordered quantity → no comparison (completes).
6. **Exceptions**: approval engine entity `weighbridge_exception` (default workflow "Weighbridge exception", step
   "Weighbridge supervisor", approver permission `weighbridge:approve_exception`, created on first use; snapshot
   `amount` = |variance kg| so workflows can set kg thresholds). **Separation of duties**: whoever registered the
   ticket or captured a weighing (and the requester) cannot approve or reject it (403); a decision made by such a user
   in the approvals inbox is not applied (the exception stays pending for another approver). Approval completes the
   ticket; rejection (reason required) discards the second weighing and the truck is weighed again. Unlike other
   gates, an inactive / missing workflow does **not** auto-approve: the exception still waits for a decision.
7. **Stored tare**: weighed on its own (`POST /tares`) or saved from a ticket (`saveTare`); expires after the station's
   `tareValidityDays`; a new tare supersedes the plate's previous one; revocable; a ticket can use it as one weighing
   only while valid (`… expired on …: weigh the empty truck again (re-tare)`).
8. **Manual entry** only with `weighbridge:manual_entry` and a reason; flagged per weighing and on the ticket
   (`isManual`), shown on the print, filterable in the queue and counted in the report.
9. **Immutability**: completed / void tickets refuse edits and weighings (409 `… is completed and cannot be changed:
   void and reissue it to correct it`); printing counts copies; correction = `void` (reason, `weighbridge:void`,
   audited) + optional **reissue** with corrected header, keeping the weighings (re-evaluated → completed or exception)
   or reopened for a new weighing; the chain is kept (`reissuedFromId` / `replacedById`).
10. **Inbound → GRN** (procurement): a completed `purchase_receipt` ticket (PO required, line auto-picked when the PO
    has one line) pre-fills **one DRAFT goods receipt with one line** through `GoodsReceiptsService.createDraft`
    (PO line, net quantity in the PO line's unit, PO price, receiving store, branch of the station); the store posts it
    with procurement's own approval / GL / over-receipt rules. Idempotent: the ticket keeps the GRN + line ids (unique
    index), `POST /goods-receipt` returns the existing one. Voiding: a posted receipt blocks the void (409, reverse it
    in procurement first); a draft is cancelled through the new additive `GoodsReceiptsService.cancelDraft` (no route).

### Events

| Event | When | Payload |
|---|---|---|
| `weighbridge.ticket.completed` | ticket completed (within tolerance, approved exception, reissue) | `organizationId, ticketId, ticketNumber, subjectType, direction, stationId, vehicleId, registrationPlate, partnerId, productId, salesOrderId, deliveryOrderId, tripId, purchaseOrderId, grossKg, tareKg, netKg, netQty, uomId, withinTolerance, manual, reissuedFromId, goodsReceiptId` |
| `weighbridge.exception.raised` | out of tolerance | `organizationId, exceptionId, ticketId, ticketNumber, varianceKg, variancePct, allowedVarianceKg` |
| `weighbridge.ticket.voided` | ticket voided | `organizationId, ticketId, ticketNumber, reason, replacedById, deliveryOrderId, tripId` |

Consumed: `approval.decided` (entity type `weighbridge_exception`, `WeighbridgeApprovalSubscriber`).

### Public API (1.4b / 1.9)

- `WeighbridgeTicketService.completedTicket(id)` → `CompletedTicket` (net kg, `netQty` + `uomId` in the document unit,
  gross / tare, ordered qty, variance, within tolerance, manual, stored tare, references, GRN id); 400 when not
  completed. `completedTicketsForTrip(tripId)`, `completedTicketsForDeliveryOrder(deliveryOrderId)`.
- `WeighbridgeStationService.readDevice(deviceId, stationId)` — a stable reading (for other weighing points).
- `WeighbridgeAdapter` SPI (+ `WeighbridgeAdapters` registry), `AsciiFrameParser`, `WeighbridgeCalculator`,
  `WeighbridgeUnits.kgPerUnit(productId, uomId)`.
- Additive in procurement: `GoodsReceiptsService.cancelDraft(id, reason)`.

### GL

None in 1.4a: the weighbridge has no money effect. Stock and GL come from procurement's GRN post (inbound) and from
the 1.3 dispatch stock issue driven by the ticket (1.4b).

### Web

Sidebar (Fleet): **Weighbridge** `/fleet/weighbridge` (queue: in progress / open / awaiting second / exceptions /
completed / void, counts, search, station / subject / date filters, phone cards), **Weighbridge Exceptions**
`/fleet/weighbridge/exceptions` (approve / reject; own tickets marked), **Weighbridge Discrepancies**
`/fleet/weighbridge/reports`, **Weighbridge Settings** `/fleet/weighbridge/settings` (stations, indicators with test
read and simulator weight, product settings). Ticket entry `/fleet/weighbridge/tickets/new` (remembers the station),
weighing screen `/fleet/weighbridge/tickets/:id` (big weight display, read scale, capture first / second weight, use
stored tare, manual entry, save tare, cancel, print / copy, void & reissue, create GRN), stored tares
`/fleet/weighbridge/tares`. Tablet first: large inputs and buttons. Hooks: `web/src/features/fleet/weighbridge-api.ts`.

### Tests

`WeighbridgePoliciesTest` (unit: frame parser default / custom formats, units, implied decimals, validation; stable
reader settling, overload, closed source, never-settling, garbage, silent with bounded time; CR / LF / STX / ETX
splitter; net order-independence and equal weighings; tolerance max rule, edge, product override; tare expiry / states;
simulator modes; `ascii-tcp` against an in-test socket server with a poll command, unreachable port).
`FleetWeighbridgeIT` (outbound with the simulator → net 20 050 kg = 20.05 t, within tolerance, event, public API,
queue; registration rules incl. one live ticket per truck, missing weight conversion, kg-per-unit setting + product
tolerance, plausibility; exception SoD — operator 403, supervisor approves, request approved, subscriber idempotent;
rejection → reweigh; approvals-inbox decision → subscriber; manual entry permission / reason / flag / report;
immutability, PDF print counting, void + reissue audited and evented, reissue without weights; stored tare single
weighing, expiry, supersede via `saveTare`, revoke, manual-tare permission; inbound PO → exactly one draft GRN line,
replay, procurement post, posted-GRN void guard, draft cancelled on void + reissued GRN; `ascii-tcp` device read
against an in-test indicator, motion-only 409, unreachable 503, test-read; concurrent first-weigh captures (one lands);
discrepancy report by product; tenant isolation; 36-route authorization matrix).

### Known gaps / next

- No direct serial-port library (serial = device file read with host line settings); no vendor-specific binary
  protocols (Toledo / Cardinal) — add as adapters. No camera / ANPR plate capture. Report period uses the server
  time zone for day / month grouping.

### 1.4b Weighbridge ↔ dispatch wiring

Status: **done** (Track C, 2026-10-29). Migration `V951__fleet_weighbridge_dispatch.sql` (placeholder number,
renumbered at merge). No new permission or event keys. Code: `fleet.application.WeighbridgeTripFeed` (link / pre-fill /
feed / void), `FleetWeighbridgeRuleService` (requirement rules + dispatch gate, depends only on `FleetDb` so dispatch can
use it without a cycle), `fleet.web.weighbridge.WeighbridgeDispatchController`.

**Data (V951).** `WeighbridgeTicket."deliveryOrderId"` → `FleetDeliveryOrder`, `"tripId"` → `FleetLoad`: added `NOT VALID`
and validated in the same migration only when no orphan text value exists (1.4a stored free text); FK-side indexes;
partial unique index *one live ticket per trip* (`open` / `first_weighed` / `exception`); new ticket columns
`tripFeedStatus` (`fed` / `skipped` / `failed` / `cleared`), `tripFeedMessage`, `tripFedAt`. New table
`FleetWeighbridgeRequirement` (organization rule = `branchId` NULL, or one per branch; `mode` off / all / bulk /
categories + `categoryIds`; unique per scope).

**Routes (6, `/api/v1/fleet/weighbridge`, `@RequiresModule("fleet")`).**

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/lookups/trips` | `weighbridge:read` | `search, vehicleId` — open trips (planned / loading / weighed) with products, truck, live ticket (50 max) |
| GET | `/lookups/trips/{tripId}` | `weighbridge:read` | ticket header pre-filled from the trip (+ names, `open`, `liveTicket`, `weighbridge.required/reason`) |
| POST | `/tickets/{id}/feed-trip` | `weighbridge:weigh` | feed a completed ticket's weight to its trip (again); 400 with the reason when the trip cannot take it; idempotent |
| GET | `/dispatch-requirements` | `fleet_dispatch:read` | `{rules, branches, categories}` |
| PUT | `/dispatch-requirements` | `fleet:settings` | `{branchId?, mode, categoryIds?, notes?}` — upsert of the organization (no branch) or branch rule |
| DELETE | `/dispatch-requirements/{id}` | `fleet:settings` | the branch falls back to the organization rule (none = off) |

Additive response fields: trip detail `weighbridgeTickets[]` + `weighbridge {required, reason, ruleId, mode, branchId,
liveTicket, fedByTicket}`; delivery-order detail `weighbridgeTickets[]`; ticket `trip {id, code, status}`,
`deliveryOrder {id, code}`, `tripFeed*`; event `weighbridge.ticket.completed` gains `tripFeedStatus`.

**Rules.**

1. **Linking.** A `sale_delivery` ticket may name a trip (other subjects: 400 `Only sale deliveries reference a sales
   order or delivery order`). Registration with only `{stationId, subjectType, tripId}` fills the blanks from the trip:
   delivery order, fleet vehicle (or the transporter's plate), trailer plate, driver (or transporter driver name),
   transporter, customer, sales order (DO source), reference `DO-… / TRP-…`, yard store, and the product + expected
   quantity — one product in a unit the weighbridge converts to kg → its planned quantity in that unit, otherwise the
   planned tonnage in TON (KG × 1 000 when no TON unit). Checks (also on edit / reissue): delivery order and trip of the
   organization (400 `Delivery order not found` / `Trip not found`), the trip belongs to the given delivery order, the
   trip is open (`… is dispatched: only planned, loading or weighed trips can be weighed`), and it is on no other live
   ticket (409 `Trip … is already on live weighbridge ticket WBT-…`, also a partial unique index).
2. **Completion feeds the trip** — in the ticket's transaction (within tolerance, approved exception, reissue with copied
   weights) through `FleetDispatchService.recordWeighedQuantity(trip, …, "weighbridge_ticket", ticketId)` (idempotent
   per ticket): single-product trip whose line unit is the ticket's net unit → the ticket's net quantity; single product
   with a weighbridge conversion of the line unit → net kg ÷ kg per unit; otherwise net kg spread over the lines by
   planned tonnage (load factors). The trip becomes `weighed`, so **dispatch issues the weighed quantity** (1.3 rule 7,
   exactly once). The weighbridge is never blocked by the trip: a trip that already left (`skipped`) or a product
   without any weight conversion (`failed`) is recorded on the ticket with the reason, and `POST /feed-trip` retries
   (e.g. after a kg-per-unit setting). **Out-of-tolerance** tickets feed nothing while the exception is pending (and a
   rejection re-opens the ticket for a new weighing).
3. **Void.** Before dispatch, voiding the ticket that gave the trip its quantity clears the lines' loaded quantity,
   `loadedTonnes` and the weigh source, and puts the trip back to **`loading`** (`FleetDispatchService.clearWeighedQuantity`,
   audited; ticket `tripFeedStatus = cleared`); a reissued ticket (copied weights) feeds the trip again in the same
   transaction. **After dispatch the void is refused** (409 `Ticket … gave the loaded quantity of trip …, which is
   dispatched: its stock is issued, so the ticket cannot be voided. Correct the quantity through the delivery (proof
   of delivery, discrepancy or return) instead`) — the stock and COGS are posted; the 1.3 POD / discrepancy / return
   flow is the correction path. Voiding a ticket that is not (or no longer) the trip's weigh source, or of a cancelled
   trip, does not touch the trip. This is done synchronously inside the void (not by a `weighbridge.ticket.voided`
   listener) because a listener runs after commit and could not refuse the void; the event is still published.
4. **Weighbridge required for dispatch** (`FleetWeighbridgeRuleService`): the rule of the delivery order's branch, else
   the organization rule, else off. `all` = every trip; `bulk` = a line whose unit (or the product's base unit) is a
   weight / volume / bulk unit (`UnitOfMeasure.category` weight / volume / bulk, or a Weight / Volume UoM category), or a
   product with weighbridge product settings; `categories` = a line whose product is in a listed category or any of its
   sub-categories. When covered, dispatch needs the trip's loaded quantity from a **completed ticket of this trip**
   (`Trip … needs a completed weighbridge ticket before dispatch (<product> is a bulk product and must be weighed at
   branch …): weigh the truck on the weighbridge`) and the manual `loaded-quantity` is refused (`… must be weighed on the
   weighbridge (…): its loaded quantity cannot be entered by hand`). Independently of the rule, a trip on a **live**
   ticket cannot be dispatched (`Trip … is on weighbridge ticket WBT-… (waiting for the second weighing): complete or
   cancel the ticket before dispatch`), so an exception cannot be bypassed by dispatching at the planned quantity.
5. **Locks**: ticket → delivery order → trip (completion, approval, void); dispatch locks delivery order → trip and only
   reads tickets, so a completion racing a dispatch either lands first (trip weighed) or the dispatch sees the live
   ticket and refuses.

**GL.** None new: the stock issue (Dr COGS / Cr stock) is the 1.3 dispatch, now at the weighed quantity; invoicing via
`/fleet/delivery-orders/{id}/invoice` (Dr AR / Cr revenue + 4250 transport revenue + tax when posted).

**Web.** Ticket entry `/fleet/weighbridge/tickets/new` has a trip picker (search open trips) and accepts
`?tripId=` (the trip screen's **Weigh** / *Continue weighing WBT-…* button) — truck, driver, customer, product, planned
quantity and reference are pre-filled; the delivery order is shown. The weighing screen links the trip and delivery
order and shows how the ticket fed the trip with *Feed the trip again*. Trip and delivery-order screens list the linked
tickets (number → weighing screen, status, net kg / quantity, variance, manual / out-of-tolerance flags, feed result) and
the trip says whether the weighbridge is required; the manual *Loaded quantity* button is hidden when it is. Weighbridge
Settings → **Dispatch rule** tab: organization / branch rules (all / bulk / categories).

**Tests.** `WeighbridgeDispatchRulesTest` (unit: bulk test, live-ticket wording, weighable states); `FleetWeighbridgeDispatchIT` (picker + pre-fill, link validation, one live ticket per trip, multi-product kg
spread, feed failure → retry, rules: validation / branch over organization / categories with sub-categories / all / off
/ delete fallback / audit, live ticket blocks an optional dispatch, five concurrent dispatches of a ticket-fed trip issue
once, public API by trip / delivery order, tenant isolation, authorization matrix 6 routes) and **`FleetPhase1EndToEndIT`**
(Phase 1 exit: sales order → DO → plan → eligibility → ticket via the simulator → completion feeds the trip → dispatch
once (replay) with the ticket's product / quantity in the inventory ledger → POD → close with odometer distance →
invoice with the per-tonne transport charge → every journal of the flow balanced and in total, COGS = weighed qty ×
cost, stock down by the net; out-of-tolerance → second-user approval → feeds → dispatch; void before dispatch → loading →
new ticket → void + reissue feeds → dispatch → void refused). `FleetWeighbridgeIT` no longer uses fake delivery-order /
trip ids (real FKs now).

**Known gaps.** A ticket covers one trip (a truck weighed once for two trips needs two tickets). Trip lines snapshot the
load factor at planning, so after adding a load factor only the weighbridge kg-per-unit conversion helps a re-feed.
After dispatch, weight corrections go through POD / discrepancies, not the ticket.
