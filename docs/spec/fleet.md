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

## 1.5 Fuel & energy

Status: **done** (worktree `wt/c-fuel`, migration placeholder `V901__fleet_fuel.sql`, renumbered at merge). 34 routes,
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
