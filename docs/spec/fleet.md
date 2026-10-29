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
