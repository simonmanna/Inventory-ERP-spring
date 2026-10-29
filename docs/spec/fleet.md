# Fleet & logistics — `fleet`

| | |
|---|---|
| Phase | Track C Phase 1 (construction materials), roadmap Tier 4 |
| Nest source | none — new module (no parity constraints; house rules apply) |
| Design | [ADR-S009](../adr/ADR-S009-fleet-and-logistics.md) |
| Routes | 32 (1.1) — table below; 1.2–1.9 outlined |
| Feature flag | `ENABLE_FLEET` → `app.modules.fleet` (controllers + module manifest only when `true`), licence feature `fleet`, module name `fleet`, web feature key `fleet` |
| Java package | `com.inventoryerp.fleet` (`domain`, `application` are named interfaces) |
| Migrations | `V15__fleet_vehicle_registry.sql` |

## Purpose

Vehicles and mobile / fixed equipment of a quarrying, crushing, block-making and construction-materials distribution
business: the unit register with meters and compliance (1.1), drivers and operators (1.2), delivery planning, dispatch
and trips with proof of delivery (1.3), the weighbridge (1.4), fuel (1.5), maintenance (1.6), tyres / incidents / fines
(1.7), telematics (1.8) and fleet costing, delivery profitability and reports (1.9).

## Functional areas

| # | Area | Status | Scope |
|---|---|---|---|
| 1.1 | Vehicle & equipment registry | **done** | configurable classes, vehicle master, status machine + history, odometer / hour-meter readings with the monotonic rule, approval-gated corrections, compliance documents + expiry query, photos / documents, linked fixed asset (read-only), lookups, overview |
| 1.2 | Drivers & operators | planned | driver profile → hr employee, licences / classes / medical expiry, qualified classes, assignments, check-out / check-in, allowances → expenses / payroll |
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
| 1.2 | `/fleet/drivers`, `/fleet/drivers/{id}/licences`, `/fleet/assignments`, `/fleet/vehicles/{id}/check-out`, `/check-in`, `/fleet/allowance-rules`, `/fleet/allowances` | `fleet_driver:*` |
| 1.3 | `/fleet/sites`, `/fleet/delivery-orders`, `/fleet/dispatch/board`, `/fleet/loads` (trips) + lifecycle actions, `/fleet/loads/{id}/delivery-note`, `/pod`, `/fleet/discrepancies`, `/fleet/transporter-rates` | `fleet_dispatch:*`, `fleet_trip:*` |
| 1.4 | `/fleet/weighbridge/stations`, `/devices`, `/tares`, `/tickets` (+ `/first-weigh`, `/second-weigh`, `/print`, `/void`), `/exceptions` | `weighbridge:*` |
| 1.5 | `/fleet/fuel-cards`, `/fleet/fuel-logs` (+ `/post`, `/reverse` — idempotent), `/fleet/fuel-tanks`, `/dips`, `/reconciliation` | `fleet_fuel:*` |
| 1.6 | `/fleet/service-templates`, `/fleet/service-schedules`, `/fleet/work-orders` (+ lines, start, complete — idempotent), `/fleet/downtime`, `/fleet/inspection-templates`, `/fleet/inspections`, `/fleet/defects` | `fleet_maintenance:*` |
| 1.7 | `/fleet/tyres`, `/fleet/incidents`, `/fleet/claims`, `/fleet/fines` (+ `/charge`) | `fleet_maintenance:tyre`, `fleet_incident:*` |
| 1.8 | `/fleet/telematics/devices`, `/fleet/telematics/positions`, provider webhook (signed) | `fleet:settings`, `fleet_trip:read` |
| 1.9 | `/fleet/reports/*` (cost, TCO, utilisation, deliveries, tonnes, weighbridge discrepancies, fuel, renewals), `/fleet/freight-bills` | `fleet:report`, `fleet_dispatch:*` |

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

## State machines

- **Vehicle status**: `active ⇄ in_workshop ⇄ breakdown ⇄ idle` (any to any), any → `disposed` (terminal). Reason
  required for `breakdown` and `disposed`. Only `active` is operational.
- **Meter correction**: `pending → applied | rejected | failed` (failed = the approved correction no longer fits the
  meter's neighbours when applied).
- **Compliance document**: `active → renewed`; derived state `valid / expiring / expired / no_expiry`.

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

## Events

| Event | When | Payload |
|---|---|---|
| `fleet.vehicle.created` | vehicle registered | `organizationId, vehicleId, code, categoryId` |
| `fleet.vehicle.status_changed` | any status change | `organizationId, vehicleId, fromStatus, toStatus, reason, source, sourceId` |
| `fleet.meter.corrected` | correction applied | `organizationId, vehicleId, correctionId, meterType, kind, previousValue, correctedValue` |

Consumed: `approval.decided` (entity type `fleet_meter_correction`).

## Scheduled jobs

None in 1.1. 1.9 adds `fleet-reminders` (ShedLock, daily) over `FleetComplianceService.dueForOrganization`.

## GL postings

None in 1.1 (the registry has no money effect). Posting rules of 1.2–1.9: ADR-S009 §6.

## Public API for later sub-modules

- `FleetVehicleService.require(id, lock)`, `changeStatus(id, to, reason, source, sourceId)`, `get(id)`.
- `VehicleStatusPolicy.isOperational(status)` — dispatch / assignment / check-out guard.
- `FleetMeterService.recordAutomated(vehicleId, meterType, value, readingAt, source, sourceId, notes)` — idempotent.
- `FleetComplianceService.dueForOrganization(organizationId, asOf)` — reminder job.
- `FleetCategoryService.requireActive(id)`; `FleetAttachmentService.upload(...)` (check-in photos, POD, receipts).

## Web

Sidebar **Fleet**: Fleet Overview `/fleet`, Vehicles & Equipment `/fleet/vehicles` (+ `/new`, `/:id`, `/:id/edit`),
Compliance & Renewals `/fleet/compliance`, Vehicle Classes `/fleet/settings/classes`. Detail tabs: overview, meters
(record reading, corrections), compliance, status history, fixed asset, photos & documents. Routes in
`web/src/pages/fleet/routes.tsx`, hooks in `web/src/features/fleet/api.ts`.

## Deviations

None from any parity contract (new module). Autonomous decisions are listed in `docs/DECISIONS_FOR_OWNER.md`
(1.1 rows).

## Status

| Sub-module | Status | Tests |
|---|---|---|
| foundation (module, flag, licence, permissions, ADR, spec, menu) | done | `ModularityTests`, `ArchitectureTests`, `ApplicationIT` |
| 1.1 registry | done (32 routes) | `FleetPoliciesTest` (unit: status machine, meter rule, compliance state), `FleetRegistryIT` (14: classes, CRUD + filters, status machine, monotonic meters, concurrency, corrections adjust / replace / reject / no workflow, compliance + expiry query, attachments, linked asset, lookups, tenant isolation, authorization matrix 32 routes) |
| 1.2–1.9 | planned | — |

## 1.7 Tyres, incidents and fines

Status: **done** (Track C, worktree `wt/c-tyres`). Migration `V921__fleet_tyres_incidents.sql` (placeholder number).
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
| 21 | GET | `/fleet/claims` | `fleet_incident:read` | 200 | `{data, meta}`; `status, incidentId, search` |
| 22 | POST | `/fleet/incidents/{id}/claims` | `fleet_incident:update` | 201 | draft; insurer (partner or name) required |
| 23 | PATCH | `/fleet/claims/{id}` | `fleet_incident:update` | 200 | while draft / submitted / approved (claimed amount not after approval) |
| 24 | POST | `/fleet/claims/{id}/status` | `fleet_incident:update` | 201 | `{status: submitted / approved / rejected / settled / withdrawn, amountApproved?, amountReceived?, date?, reason?}` |
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

Status: **done** (Track C, worktree `wt/c-tyres`). Migration `V922__fleet_telematics.sql` (placeholder number).
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
