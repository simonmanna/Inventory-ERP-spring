# ADR-S009: Fleet & logistics module (`fleet`) — vehicles, drivers, dispatch, weighbridge, fuel, maintenance

- Status: Accepted (autonomous, 2026-10-28)
- Brief: [`AUTONOMOUS_BUILD_CONSTRUCTION.md`](../AUTONOMOUS_BUILD_CONSTRUCTION.md) Phase 1 (1.1–1.9); requirements
  [§5, §5.1, §7](../requirements/construction-materials-erp.md); roadmap Tier 4 (4.1–4.10)
- Spec: [`docs/spec/fleet.md`](../spec/fleet.md) — route table, data model, permission matrix, status per sub-module
- Migrations: `V15__fleet_vehicle_registry.sql` (1.1 + all Phase 1 permissions); each later sub-module adds its own
  `V{n}__fleet_<area>.sql`
- Supersedes: the never-pushed session-B draft "ADR-S020 fleet management" (smaller Tier 4 scope, `fleet:*` coarse
  permissions); its ideas (odometer offset, fuel efficiency calculator, work-order lines) are kept where they fit

## Context

The client quarries stone, crushes aggregates, makes concrete blocks and trades construction materials. It runs tipper
trucks, trailers, prime movers, water bowsers, excavators, wheel loaders, dozers, forklifts, generators and light
vehicles; delivers by the tonne, m³, piece, bag or truckload to construction sites with its own trucks and third-party
transporters; and operates a weighbridge at the quarry / yard. Transport is one of its largest operating costs, and the
weighbridge is where bulk-material fraud happens. The ERP has no fleet capability: `rental` has a "fleet" report for
hire-out units (unrelated), `repair` is a customer-repair engine, `fixedasset` holds depreciation only.

Odoo (Fleet + Inventory delivery + Maintenance), Business Central (Fixed Assets + Transport/Warehouse + Service) and SAP
(PM + TM + weighbridge add-ons) all model the same core: a unit register with meters and compliance, drivers, delivery
orders split into loads, weighing tickets that drive the shipped quantity, fuel and maintenance costs tagged to the unit
and its cost centre, and cost / profitability reports.

## Decision

### 1. Module shape and boundaries

- New Spring Modulith module **`com.inventoryerp.fleet`** (`web → application → domain`), displayName "Fleet".
  `domain` (hand-written JPA entities: column catalogue, tenancy filters, schema validation) and `application` are
  `@NamedInterface`s, so later modules (manufacturing quarry haulage 2.2, CMMS 4.x, BI 5) read fleet entities and call
  fleet services; **no existing module depends on fleet** (it reacts to their events or is called by its own screens).
- Services are JdbcClient SQL like the rest of the code base (`FleetDb` helper, `SqlRows` rendering, explicit
  `"organizationId" = ?` on every query, `"deletedAt" IS NULL` for soft-deleting tables); every fleet table has the
  `tenant_isolation` RLS policy. Contended rows (vehicle meters, ticket numbers, stock moves) are locked `FOR UPDATE`.
- Cross-module references are plain text ids validated in the fleet service against the owning table (org-scoped,
  read-only). Fleet never writes another module's tables: writes go through that module's `application` API.
- Feature flag **`app.modules.fleet`** (env `ENABLE_FLEET`, default `false`); every controller is
  `@ConditionalOnProperty` + `@RequiresModule("fleet")`; `AccessGuardInterceptor` maps `fleet → app.modules.fleet`;
  licence feature key **`fleet`** (`LicenseFeatures.FLEET`; licence server `Entitlements.KNOWN_FEATURES` and plans
  enterprise-yearly / perpetual-onprem via license-server `V3__fleet_feature.sql`); web `MODULE_FEATURES` key `fleet`
  gates the sidebar section "Fleet"; `FleetModule` registers the `fleet` manifest so the Modules screen lists it.
- Base path **`/fleet`** for every fleet route (weighbridge too: `/fleet/weighbridge/...`).

### 2. Reuse of existing modules (how, not just what)

| Module | Fleet uses | How |
|---|---|---|
| kernel | approvals, audit, files, sequences, settings, notifications, events/outbox, ShedLock jobs | `ApprovalsService.checkOrRequestApproval` + `approval.decided` subscriber; `AuditService.recordInTx`; `FilesService`; `SequenceService.next`; `SettingRegistry` keys `fleet.*`; `EventBus.publish` |
| core | branches, partners (lessors, transporters, customers), products, UoM | read `Branch`, `Partner`, `Product` rows; UoM / density conversion through the core / inventory API (Phase 4 extends ADR-S003) |
| fixedasset | asset register + depreciation of owned vehicles | optional `FleetVehicle.assetId`; fleet **reads** `Asset` / `AssetDepreciation` (domain named interface / SQL) and shows book value read-only; disposal stays in fixedasset |
| hr | employees (drivers / operators), payroll earnings & deductions | driver profile → `HrEmployee` id (1.2). Allowances exported as payroll earnings and fines as deductions **through an hr application API**: `hr.application` exposes no named interface today, so 1.2 / 1.7 add one (additive) or publish an event hr subscribes to |
| inventory | yard / site locations, stock issue on dispatch, returns, spare parts, site fuel tanks | `StockService.issue / receiveReturn / transfer` (exactly once per load: posting key per load), `InventoryLocation` for home sites and tanks |
| procurement | inbound weighing → GRN quantity, external workshop / transporter / fuel bills | `GoodsReceiptsService` (1.4 inbound), `PurchaseOrdersService`; landed cost for inbound freight (ADR-S007 `LandedCostVoucherService`) |
| orders / invoicing | confirmed sales orders → delivery orders; transport charge lines; credit-note requests for approved shortages; supplier freight bills | `OrdersService` / `InvoiceService` / `CreditNoteService` / `VendorBillService` application APIs; POS sale tables and routes are **never** touched |
| expenses | driver allowances / trip expenses paid in cash, external fuel purchases | `ExpensesService` (create + approve + post through its own approval gate) |
| accounting | GL | `PostingService.post / reverse` with effective-dated mappings via `AccountResolverService.ensureByCode`; cost centre on every line + `dimensions.vehicleId` |
| documents (DMS) | printable delivery notes (1.3) and weighbridge tickets (1.4) | `DocumentPrintService` / templates; vehicle photos and scans use the same kernel file store (`FilesService`, owner type `fleet_vehicle`) |
| communication | expiry / service-due / exception alerts (1.9) | kernel `NotificationsService` (in-app) and the communication module's dispatch for email / WhatsApp |
| repair | — | **not reused** for fleet work orders (§5) |
| rental | — | unrelated (its "fleet" report is hire-out units); no coupling |

### 3. Sub-areas and their main tables

| # | Area | Main tables (planned names) | Key flows |
|---|---|---|---|
| 1.1 | Vehicle & equipment registry (**built**) | `FleetVehicleCategory`, `FleetVehicle`, `FleetVehicleStatusHistory`, `FleetMeterReading`, `FleetMeterCorrection`, `FleetComplianceDocument`, `FleetVehicleAttachment` | register, status machine, monotonic meters + approved corrections, compliance expiry query |
| 1.2 | Drivers & operators | `FleetDriver` (→ hr employee), `FleetDriverLicence` (classes, expiry), `FleetDriverQualification` (vehicle classes), `FleetAssignment`, `FleetCheckout`, `FleetAllowanceRule`, `FleetDriverAllowance` | assignment blocked for invalid licence / class; check-out / check-in writes meter readings (`source = check_out / check_in`); allowances → expenses or payroll |
| 1.3 | Trips, delivery planning & dispatch | `FleetCustomerSite`, `FleetDeliveryOrder` + `…Line`, `FleetLoad` (trip) + `…Line`, `FleetTransporterRate`, `FleetProofOfDelivery`, `FleetDeliveryDiscrepancy`, `FleetTransportCharge` | sales order → delivery order → loads within payload (t) / volume (m³) → truck + driver (+ trailer) or transporter → trip lifecycle → stock issue once → POD → discrepancy approval → credit-note request; transport charge line on the invoice |
| 1.4 | Weighbridge | `WeighbridgeStation`, `WeighbridgeDevice`, `FleetVehicleTare`, `WeighbridgeTicket`, `WeighbridgeException` | register → first weigh → second weigh → net → tolerance → exception (SoD approval) → immutable printed ticket (void + reissue) → drives load / GRN quantity |
| 1.5 | Fuel & energy | `FleetFuelCard`, `FleetFuelLog`, `FleetFuelTank`, `FleetTankDip`, `FleetFuelSettings` | fuel logs with efficiency + anomaly flags; tank receipts via GRN, issues as stock issues; GL posting |
| 1.6 | Maintenance | `FleetServiceTemplate`, `FleetServiceSchedule`, `FleetWorkOrder` + `…Line`, `FleetDowntime`, `FleetInspectionTemplate`, `FleetInspection`, `FleetDefect` | PM by km / hours / calendar (whichever first); work orders with parts (inventory), labour (hr), external bills (invoicing); downtime, MTBF / MTTR; DVIR defects block dispatch |
| 1.7 | Tyres, incidents, fines | `FleetTyre`, `FleetTyreEvent`, `FleetTyreReading`, `FleetIncident`, `FleetInsuranceClaim`, `FleetFine` | tyre lifecycle + cost / km; incident → claim; fine charged to driver (payroll deduction) or company |
| 1.8 | Telematics SPI | `FleetTelematicsDevice`, `FleetPosition` (partitioned by month later), `FleetTelematicsEvent` | provider poll / push → positions, odometer / hours sync (`source = telematics`, idempotent per external id), alerts; trip distance and POD location |
| 1.9 | Costing, profitability & reports | read models over the tables above + GL lines tagged `vehicleId`; `FleetCostSnapshot` (monthly, optional) | cost per vehicle / km / hour / tonne / trip, TCO, delivery profitability, freight bills, utilisation, renewals / services due; ShedLock reminder jobs |

### 4. Registry rules (1.1)

- **Classes** are configurable per organization (`FleetVehicleCategory`, seeded on first use with tipper, trailer,
  prime mover, water bowser, excavator, wheel loader, dozer, forklift, generator, light vehicle, other). A class has a
  `kind` (`road_vehicle, trailer, mobile_plant, fixed_plant, other`) and a default meter type. Road vehicles and
  trailers need a registration plate. `fixed_plant` lets Phase 4 CMMS register crushers / conveyors / block machines
  as units of the same register, so trucks and plant share one maintenance engine.
- **Vehicle**: code (auto `VEH-0001` from the sequence service when blank), plate (normalised upper case, unique per
  organization), VIN / chassis (normalised, unique), engine no., make / model / year, colour, class, ownership
  (`owned, leased, hired, third_party`; hired / third-party need the owner partner = transporter), payload (t), body
  volume (m³), axle configuration, fuel type, tank (L), meter type (`odometer, hours, both, none`), home branch, home
  site (inventory location), cost centre, optional fixed asset (one vehicle per asset), notes; soft delete only
  without operating history (otherwise dispose).
- **Status machine** (`VehicleStatusPolicy`): `active, in_workshop, breakdown, idle` move freely; `disposed` is terminal;
  breakdown / disposal need a reason; only `active` is operational (dispatch, assignment, check-out check it). Every
  change writes `FleetVehicleStatusHistory` (from, to, reason, source, source id, meters, user) and publishes
  `fleet.vehicle.status_changed`. Later sub-modules call `FleetVehicleService.changeStatus(id, to, reason, source,
  sourceId)` — e.g. a work order opening moves the vehicle to `in_workshop` with `source = maintenance`.
- **Meters** (`MeterReadingPolicy`): each reading stores the raw value and the **lifetime** value (raw + replacement
  offset). The lifetime value never decreases over time: a reading must be ≥ the highest earlier reading and ≤ the
  lowest later reading (back-dated readings are allowed between neighbours); not in the future (5 min skew); not before
  the last meter replacement; only for meters the vehicle tracks. The vehicle row is locked `FOR UPDATE` per reading,
  so concurrent conflicting readings cannot both pass. Other processes record readings through
  `FleetMeterService.recordAutomated(…, source, sourceId)` — idempotent per source document.
- **Corrections** (rollbacks): `adjust` (replace a wrong reading's value; the original is kept as `superseded`) or
  `replace` (meter replaced / rolled over: new baseline value, the offset grows so the lifetime total continues). A
  correction is validated at request time and again when applied, one pending correction per meter. It is gated by the
  **kernel approval engine**, entity type `fleet_meter_correction`: if the organization has no workflow for it, fleet
  creates the default one (step "Fleet manager", approver permission `fleet_vehicle:approve_meter`, distinct approvers);
  the engine refuses self-approval. `FleetApprovalSubscriber` applies the decision from `approval.decided`
  (`POST /fleet/meter-corrections/{id}/sync` does the same on demand). An administrator who deactivates the workflow
  accepts the engine's rule "no workflow = auto-approve": corrections then apply immediately. Request, apply, reject and
  failure are audited; an applied correction publishes `fleet.meter.corrected`.
- **Compliance documents**: type (`registration, insurance, road_licence, inspection, operator_permit, other`),
  number, issuer, issue / expiry dates, reminder lead days (default org setting `fleet.complianceReminderDays` = 30),
  notes, attachments; renewal creates the successor and marks the old one `renewed`. State on a business day:
  `expired` (expiry < today), `expiring` (expiry ≤ today + lead), `valid`, `no_expiry`. The expiry query
  (`GET /fleet/compliance-documents?due=…`, `FleetComplianceService.dueForOrganization(org, asOf)`) only looks at
  active documents of live, non-disposed vehicles — the 1.9 reminder job uses it.
- **Photos / documents** go to the kernel file store (`FilesService`, owner type `fleet_vehicle`, signed download
  URLs) with a fleet link row (kind photo / document, title, optional compliance document).

### 5. Maintenance work orders (1.6) are fleet-owned, not `repair`

`repair` was read (`docs/spec/repair.md`, `RepairJobService`, `RepairPartsService`, `RepairPostingService`): it is a
customer-repair engine (received → diagnosis → quotation → approval → repairing → testing → ready / delivered), bills
through the POS Order → Invoice spine, takes untyped Nest-parity bodies, has no vehicle / asset foreign key (free-text
plate / VIN), prices labour as a sale (not as a cost), posts part issues to COGS rather than to an expense cost centre,
and its `application` package is not a named interface. Its 64 routes are parity-locked. Bending it to internal fleet
maintenance would change parity behaviour or bolt a second model onto it.

**Decision**: 1.6 builds `FleetWorkOrder` / `FleetWorkOrderLine` in fleet: preventive / corrective / inspection-defect /
breakdown, lifecycle `open → in_progress → completed / cancelled`, linked to the unit, its meters and downtime; part
lines issue spare parts through `StockService.issue` posted Dr fleet maintenance expense (vehicle cost centre) / Cr the
part's stock account; labour lines record hr employee + hours + rate (memo cost by default — payroll already expenses
wages; optional absorption posting Dr maintenance / Cr labour absorption); external workshop lines become vendor bills
through `VendorBillService` (AP posts them through its own approval / three-way match). Opening a corrective work
order moves the unit to `in_workshop`; completing it restores the previous status. Phase 4 CMMS for fixed plant reuses
this engine on `fixed_plant` units. `repair` stays the customer-service module.

### 6. GL posting rules (all through `PostingService`, effective-dated mappings, cost centre + `vehicleId` dimension)

Accounts are resolved with `AccountResolverService.ensureByCode(code, name, category, mappingKey)` on first use, so a
fresh organization works without manual set-up and an accountant can remap the key later. Every posting has a
`postingKey` (`fleet:<area>:<id>`) so a retry never double-posts; money routes are `@Idempotent(required = true)`;
corrections are reversals (`PostingService.reverse`), never edits. Default codes are proposals (owner review):

| Event | Debit | Credit | Mapping key / default code |
|---|---|---|---|
| Fuel bought outside (station, card) — 1.5 | Fleet fuel & energy (vehicle cost centre) | Cash / bank, fuel-card payable, or AP via expenses / vendor bill | `fleet_fuel_expense` 6410; `fleet_fuel_card_payable` 2165 |
| Fuel issued from a site tank — 1.5 | Fleet fuel & energy (vehicle cost centre) | Fuel stock (tank location's stock account, AVCO / FIFO cost) | stock issue `skipGl` + fleet posting, `fleet_fuel_expense` |
| Fuel bought into a site tank — 1.5 | Fuel stock (via GRN) | GRNI / AP (procurement) | procurement's normal receipt posting |
| Maintenance parts — 1.6 | Fleet repairs & maintenance (cost centre) | Part's stock account | `fleet_maintenance_expense` 6420 |
| External workshop / tyres bought — 1.6 / 1.7 | Fleet repairs & maintenance / Tyres | AP (vendor bill) | `fleet_maintenance_expense`, `fleet_tyre_expense` 6430 |
| Driver allowance / trip expense paid — 1.2 | Driver allowances & trip expenses (cost centre) | Cash (expenses) or Payroll payable (hr earning) | `fleet_driver_allowance_expense` 6440 |
| Traffic fine paid by company — 1.7 | Traffic fines (cost centre) | Cash / AP | `fleet_fines_expense` 6450 |
| Fine charged to driver — 1.7 | Fines recoverable from employees | Traffic fines (or cash when paid) — recovered by payroll deduction | `fleet_fines_recoverable` 1360 |
| Third-party freight, outbound — 1.3 / 1.9 | Outbound freight (delivery cost centre) | AP (transporter vendor bill) | `fleet_freight_expense` 6460 |
| Third-party freight, inbound — 1.9 | Inventory (landed cost, ADR-S007) | Landed cost clearing / AP | existing `landed_cost_clearing` 2155 |
| Transport charge billed to customer — 1.3 | AR (invoice) | Transport / delivery revenue | `fleet_transport_revenue` 4250 |
| Dispatched goods — 1.3 | COGS | Inventory | `StockService.issue` (inventory's normal COGS posting, once per load) |
| Own-fleet trip cost — 1.9 | — (analytic) | — | no GL entry: trip cost = allocated vehicle cost; optional ADR-S007 allocation run Dr delivery / Cr fleet cost centre |

Depreciation of owned vehicles stays in `fixedasset` (its own postings); 1.9 reads it for TCO. Insurance / licence
fees are expenses / vendor bills tagged with the vehicle cost centre.

### 7. Device and provider SPIs (interfaces; implementations in 1.4 / 1.8)

```java
/** Weighbridge indicator (1.4). Implementations: generic continuous-output ASCII over TCP / serial, simulator. */
public interface WeighbridgeAdapter {
    String type();                                   // "ascii-tcp", "ascii-serial", "simulator"
    StableWeight readStable(WeighbridgeDevice device, Duration timeout); // blocks until stable or throws
    DeviceHealth health(WeighbridgeDevice device);
    record StableWeight(BigDecimal kilograms, Instant at, String rawFrame, boolean stable) {}
    record DeviceHealth(boolean reachable, String detail) {}
}

/** Telematics provider (1.8). Implementations: mock (tests / demo), vendor adapters later. */
public interface TelematicsProvider {
    String type();
    List<Position> positions(String externalUnitId, Instant from, Instant to);
    Optional<MeterSnapshot> meters(String externalUnitId);          // odometer km / engine hours
    List<TelematicsEvent> events(Instant since);                    // ignition on/off, harsh driving, geofence
    record Position(String externalUnitId, Instant at, double lat, double lon, BigDecimal speedKmh, String externalId) {}
    record MeterSnapshot(BigDecimal odometerKm, BigDecimal engineHours, Instant at) {}
    record TelematicsEvent(String externalUnitId, String type, Instant at, Map<String, Object> data, String externalId) {}
}
```

Adapters are Spring beans selected per station / organization by `type`; configuration (host, port, frame regex, unit,
provider URL, API key) lives in env placeholders (`.env.example`) and per-station rows, never in logs. Manual weight
entry needs `weighbridge:manual_entry` and is always flagged on the ticket; device frames are stored for audit.

### 8. Permissions (all of Phase 1 declared at once in `packages/shared/src/permissions.ts`)

Granular resources, action verbs like the rest of the catalogue (`read / create / update / delete` + domain verbs).
Administrators get them through `V15` and `CorePermissionSync`; no existing role changes (POS manager untouched).

| Resource | Actions |
|---|---|
| `fleet` | `report` (dashboards, reports, exports), `settings` (classes, thresholds, telematics, allowance rules) |
| `fleet_vehicle` | `read, create, update, delete, status, meter, approve_meter, compliance` |
| `fleet_driver` | `read, create, update, delete, assign, checkout, allowance, approve_allowance` |
| `fleet_dispatch` | `read, create, update, plan, cancel, approve_discrepancy` |
| `fleet_trip` | `read, create, update, dispatch, deliver, close, cancel` |
| `fleet_fuel` | `read, create, update, post, tank` |
| `fleet_maintenance` | `read, create, update, schedule, complete, inspect, tyre` |
| `fleet_incident` | `read, create, update, charge` |
| `weighbridge` | `read, weigh, manual_entry, approve_exception, void, configure` |

Separation of duties is enforced in the service, not only by permission: the meter-correction requester cannot approve
(engine), a weighbridge operator cannot approve the exception of their own ticket, a discrepancy recorder cannot approve
it, an allowance claimant cannot approve it.

### 9. Events and jobs

Published after commit through `EventBus` (`packages/shared/src/events.ts`): `fleet.vehicle.created`,
`fleet.vehicle.status_changed`, `fleet.meter.corrected` (1.1); later `fleet.trip.*`, `fleet.delivery.delivered`,
`weighbridge.ticket.issued`, `weighbridge.exception.raised`, `fleet.fuel.posted`, `fleet.workorder.*`. Consumed:
`approval.decided` (1.1), sales-order events from `orders` (1.3). Scheduled jobs are ShedLock-guarded, gated by
`app.jobs.enabled` and `app.modules.fleet`, iterate organizations explicitly: compliance / licence / tare reminders and
service-due generation (1.6 / 1.9).

### 10. Web

Sidebar section **Fleet** (`MODULE_FEATURES` key `fleet`, permission-aware items); route skeleton in
`web/src/pages/fleet/routes.tsx` that each sub-module extends; API hooks in `web/src/features/fleet/api.ts`. Yard,
driver and weighbridge screens are phone / tablet first (large inputs, camera capture for photos).

## Consequences

- One unit register serves trucks, mobile plant and (Phase 4) fixed plant; every later fleet table keys on
  `FleetVehicle.id`.
- Meter integrity is strong (lifetime monotonic rule + approvals) at the price of a correction workflow for every
  mistake; the default workflow is created automatically and can be edited in Approval Workflows.
- Fleet does not reuse `repair`; the two modules may both show "work orders" in the menu (Repair & Maintenance vs
  Fleet → Maintenance). Customer repairs stay in `repair`.
- `hr` needs an exposed application API (or an event) before 1.2 / 1.7 can write payroll lines — an additive change.
- Default account codes and thresholds are recorded in `docs/DECISIONS_FOR_OWNER.md` for review.

## Alternatives considered

1. **Extend `fixedasset` as the vehicle register** — rejected: assets are financial records (depreciation, disposal),
   hired / third-party trucks are not assets, and fleet needs meters, compliance and dispatch fields on every row.
   Linking (optional `assetId`) keeps both clean.
2. **Reuse `repair` for maintenance** — rejected (§5).
3. **Coarse `fleet:read / manage / …` permissions (draft ADR-S020)** — rejected: dispatchers, weighbridge operators,
   drivers and workshop staff need disjoint rights, and SoD pairs need distinct approve permissions.
4. **Separate `weighbridge` module** — rejected for now: tickets are inseparable from loads / GRNs; the SPI and tables
   are isolated inside fleet and can be split out later without schema changes.
