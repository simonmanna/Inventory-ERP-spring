# Decisions for owner review

Decisions taken autonomously during the construction-materials build (Track C,
[`AUTONOMOUS_BUILD_CONSTRUCTION.md`](AUTONOMOUS_BUILD_CONSTRUCTION.md)). Each row can be reversed later; the last column
says how. Significant ones also have an ADR.

| Date | Phase.item | Question | Decision taken | Alternatives | How to change later |
|---|---|---|---|---|---|
| 2026-10-28 | 1.1 | Reuse `repair` for fleet maintenance work orders (1.6)? | No — fleet-owned work orders (ADR-S009 §5); `repair` stays the customer-repair engine | Extend `repair` (parity-locked, POS billing spine, no vehicle FK) | New ADR; a bridge can create a repair order from a fleet work order |
| 2026-10-28 | 1.1 | Permission granularity | 53 granular keys for all of Phase 1 (`fleet:report/settings`, `fleet_vehicle:*`, `fleet_driver:*`, `fleet_dispatch:*`, `fleet_trip:*`, `fleet_fuel:*`, `fleet_maintenance:*`, `fleet_incident:*`, `weighbridge:*`); Administrator gets all, no other role changed | Coarse `fleet:read/manage` (draft ADR-S020) | Roles & Permissions screen; keys in `packages/shared/src/permissions.ts` |
| 2026-10-28 | 1.1 | Who approves meter rollbacks when no approval workflow exists? | Fleet creates a default workflow "Fleet meter correction" (1 step, approver permission `fleet_vehicle:approve_meter`, distinct approvers) on the first correction; requester cannot approve | Built-in fleet approval route; no approval | Settings → Approval Workflows (edit steps; deactivating it makes corrections apply immediately) |
| 2026-10-28 | 1.1 | Meter replacement / rollover | Supported as a correction kind `replace` (approval-gated); lifetime value = raw + offset stays monotonic | Delete / overwrite readings | — |
| 2026-10-28 | 1.1 | Future-dated readings | Refused beyond a 5-minute clock-skew tolerance; back-dated readings allowed between neighbours | Allow any time | `MeterReadingPolicy.FUTURE_TOLERANCE` |
| 2026-10-28 | 1.1 | Default compliance reminder lead time | 30 days (org setting `fleet.complianceReminderDays`), overridable per document (0–365) | 14 / 60 days | `PUT /settings/fleet.complianceReminderDays` |
| 2026-10-28 | 1.1 | Plate / owner rules | Plate required for classes of kind road vehicle and trailer; owner partner required for hired / third-party units; VIN not format-checked (heavy plant chassis numbers vary) | ISO-3779 VIN check; plate always optional | `FleetVehicleService` |
| 2026-10-28 | 1.1 | Default vehicle classes | 11 seeded per organization on first use (tipper, trailer, prime mover, water bowser, excavator, wheel loader, dozer, forklift, generator, light vehicle, other); generators as mobile plant | Fewer / industry-neutral | Fleet → Vehicle Classes |
| 2026-10-28 | 1.1 | Vehicle photos / documents storage | Kernel file store (owner type `fleet_vehicle`) with a fleet link table; DMS documents reserved for printable delivery notes / tickets | Attach to DMS documents | — |
| 2026-10-28 | 1.1 | Deleting vehicles | Soft delete only without operating history; otherwise dispose (status `disposed`, terminal) | Always allow soft delete | `FleetVehicleService.delete` |
| 2026-10-28 | 1.1 | Default fleet GL accounts (used from 1.2) | Mapping keys + proposed codes: fuel 6410, maintenance 6420, tyres 6430, driver allowances 6440, fines 6450, outbound freight 6460, transport revenue 4250, fuel-card payable 2165, fines recoverable 1360 (created on first use) | Seed in the COA | Accounting → account mappings |
| 2026-10-28 | 1.1 | Licence plans carrying `fleet` | enterprise-yearly and perpetual-onprem (like rental / manufacturing); an install in trial mode grants every feature anyway | All plans | Licence server admin → plans |
