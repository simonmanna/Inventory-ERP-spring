# Construction materials: administrator guide

> **The system built here has not yet been compiled or run.** Every setting below was written from the code, never
> tried. Check each one against `docs/VERIFY_CHECKLIST.md` before relying on it.

This guide covers set-up, in the order to do it. The daily work of each role is in the
[user guide](construction-materials.md).

**[Needs hardware]** and **[Needs vendor]** mark what only works in full with a real device or supplier account.

## 1. Switch the modules on

A module must be on in three places: the server flag, the licence, and the organization.

| Module | Server flag | Licence feature |
|---|---|---|
| Fleet, dispatch, weighbridge, fuel, maintenance | `ENABLE_FLEET` | `fleet` |
| Quarry, drilling and blasting | `ENABLE_QUARRY` | `quarry` |
| Manufacturing, crushing, precast, shop floor | `ENABLE_MANUFACTURING` | `manufacturing` |
| HR, payroll, overtime | `ENABLE_HR` | `hr` |
| Sales orders | `ENABLE_ORDERS` | none |
| Alerts | `ENABLE_ALERTS` | none |
| Customer portal | `ENABLE_PORTAL` | none |
| WhatsApp and Telegram messages | `ENABLE_COMMUNICATION`, then `_WHATSAPP` or `_TELEGRAM` | `communication` |

- Quality, analytics and accounting have no server flag. `accounting` and `expense` are licence features.
- A trial or an unlicensed install grants every feature for its trial days, then becomes read-only.
- Paid licences set caps on users, devices and organizations. Check them at `/settings/license`.
- A licensed module can be turned off for an organization at `/settings/developer` or `/modules`. An unlicensed one
  cannot be turned on.
- Web menu flags are set at build time. `VITE_ENABLE_HR` shows HR and `VITE_ENABLE_ALERTS` shows alerts.

### Alerts

Alerts need both flags. `ENABLE_ALERTS=true` starts the scans and opens the routes, and `VITE_ENABLE_ALERTS=true` shows
**Alerts & Exceptions** in the menu. Rebuild the web app after changing the second one. `ALERTS_HOURLY_CRON` and
`ALERTS_DAILY_CRON` set the scan times.

## 2. Company, accounts and branches

1. Set the company at `/settings/company`, and review the chart at `/accounts`.
2. Check `/accounts/mappings`. Production needs `wip`, `stock_valuation`, `cogs`, `grni_accrued` and `overhead_absorbed`
   mapped. The chart template maps these, and a hand-edited chart may not. Fleet creates its own accounts on first use:
   fuel 6410, maintenance 6420, tyres 6430, transport revenue 4250, and others.
3. Create the year's fiscal periods at `/fiscal-periods`.
4. Link each tax to its account before using it. The demo organization charges no tax.

### Branch scopes

**Assign every location to a branch before restricting anyone.** A restricted user cannot see rows with no branch, so an
unassigned yard disappears for them.

1. Assign each yard and stockpile a branch at `/inventory/locations`.
2. Give vehicles, weighbridge stations and quarry sites their branch.
3. Restrict a user at `/staff`, under **Data access**: tick the branches and locations they may see.

A user's default branch is not a restriction. No ticks means no restriction. Single-location reports then ask a restricted
user to pick one location.

## 3. Roles and permissions

Build roles at `/staff/roles`. The demo organization's roles are a starting point, listed in
`backend/src/main/java/com/inventoryerp/kernel/seed/ConstructionDemoRoles.java`. Review them for the client.

| Role | Main permissions |
|---|---|
| Weighbridge operator | `weighbridge:read`, `weighbridge:weigh`, `fleet_trip:read` |
| Weighbridge supervisor (not in the demo) | adds `weighbridge:manual_entry`, `weighbridge:void`, `weighbridge:approve_exception` |
| Dispatcher | `fleet_dispatch:*` except `approve_discrepancy`, and `fleet_trip:*` |
| Driver | `fleet_trip:read`, `fleet_trip:deliver`, `fleet_fuel:create`, `fleet_maintenance:inspect` |
| Plant supervisor | `quarry_extraction:*`, `crushing_run:*`, `production_order:*` |
| Quality officer | `quality:*` except `configure`, and `production_order:qc` |
| Storekeeper | `inventory:*`, `inventory_count:*`, `goods_receipt:*` |
| Accountant | `invoice:*`, `payment:*`, `journal_entry:*`, `report:accounting`, `fleet_freight:bill` |
| Manager | `analytics:*`, `alerts:read`, `approvals:decide`, and the approve permissions for exceptions |

Keep approve permissions away from the people who make the document.

`/fleet/trips` on the API is not limited to a driver's own trips. Anyone with `fleet_trip:read` can read all of them. To
link a driver to the driver app, set the user on their HR employee record.

## 4. Approvals

- **Workflows.** Set steps and approvers at `/approval-workflows`. Entity types include `goods_receipt`,
  `purchase_request`, `supplier_payment`, `expense`, `credit_note`, `invoice_cancel`, `inventory_count_submit`,
  `quarry_explosive_issue`, `fleet_delivery_discrepancy`, `fleet_driver_claim`, `fleet_meter_correction` and
  `fleet_tank_reconciliation`. With no workflow, most gates approve at once, so set up the ones the client needs.
- **Limits.** Set amounts by user or permission at `/approval-controls`, tab **Limits**. A user's own limit wins over a
  limit by permission. The highest limit applies, and no limit means no cap.
- **Held changes.** A `config_change` workflow holds changes to workflows, limits, separation switches and numbering.
  Give it no minimum amount. Held changes carry amount 0, so a minimum above 0 never applies.

### Separation of duties

**All pairs but one are off until switched on.** The owner must switch them on for the client at `/approval-controls`,
tab **Separation of duties**, or the control does nothing.

| Pair | Maker | Checker | Default |
|---|---|---|---|
| Weighbridge exception | Weighs the ticket | Approves the exception | Always enforced |
| Stock adjustment | Creates the adjustment | Approves and posts it | Off |
| Production loss write-off | Runs the production order | Writes off the loss | Off |
| Credit override | Raises the sale | Overrides the credit hold | Off |
| Supplier bill approval | Enters the bill | Approves it | Off |

There is no exemption. Once a pair is on, a one-person site needs a second approver through a workflow. Each check appears
in the audit log as `SodCheck`.

## 5. Weighbridge

Set up at `/fleet/weighbridge/settings`.

1. Add a station per gate, with its branch, yard, weight range and tare validity.
2. Add an indicator to each station. **[Needs hardware]** Choose `ascii-tcp` or `ascii-serial` and give the connection and
   frame format. The real indicator model is not yet known, so these adapters are generic and untested on a device. The
   `simulator` adapter stands in until then.
3. Set the tolerance. A ticket is out of tolerance when the difference exceeds the larger of the percentage and the
   absolute kg. Station defaults are 0.5 % and 20 kg. Product settings override them per field, and hold kg per unit
   for goods sold by the piece.
4. Choose **Weighbridge required for dispatch**, for the organization or a branch: off, all products, bulk products, or
   listed categories.

Exceptions wait for a person even with no active workflow. `FLEET_WEIGHBRIDGE_MAX_READ_TIMEOUT_MS` caps any read.

## 6. Products, plant and recipes

- **Units and density.** Set the stock unit on the product, then density, kg per piece, kg per bag and tonnes per
  truckload in the product's unit profile. Load factors at `/fleet/settings/dispatch` take priority in load planning.
- **Quarry.** Sites, materials and grades at `/quarry/sites` and `/quarry/materials`. Royalties, cost allocation and who may approve and
  issue explosives at `/quarry/settings`. Magazines at `/quarry/magazines`.
- **Crusher.** Plants at `/manufacturing/crushing/plants`. A grading profile must be activated before a run can use it.
- **Block plant.** Curing areas and product specs at `/manufacturing/precast/setup`, mix designs at
  `/manufacturing/precast/mix-designs`. Rates per work centre at `/manufacturing/costing`.
- **Quality.** Specifications at `/quality/specifications`, inspection plans at `/quality/plans`.

## 7. Fleet settings

| Setting | Where | Default |
|---|---|---|
| Delivery charges, transporter rates, load factors | `/fleet/settings/dispatch` | none |
| Fuel bands per vehicle class, dip variance needing approval | `/fleet/settings/fuel` | tipper 25 to 60 L/100 km, wheel loader 6 to 30 L/h, overfill 2 % |
| Vehicle classes | `/fleet/settings/classes` | 11 classes on first use |
| Allowance rules | `/fleet/settings/allowances` | none |
| Service templates | `/fleet/maintenance/templates` | none |
| Document reminder lead days | setting `fleet.complianceReminderDays` | 30 |
| Stored tare reminder days | setting `fleet.tareReminderDays` | 3 |
| Freight match tolerance | settings `fleet.freightMatchTolerancePct`, `fleet.freightMatchToleranceAmount` | 1 %, amount 0 (the larger applies) |
| Workshop labour posted to the ledger | setting `fleet.maintenanceLabourAbsorption` | off, payroll already expenses wages |

**[Needs vendor]** Telematics needs `FLEET_TELEMATICS_PROVIDER=rest` with a URL and key, and the vendor is not yet chosen.
The default `mock` is for demos.

## 8. Alert thresholds

At `/alerts`, on the settings tab, each alert type has a threshold, channels and recipients.

| Alert | Threshold | Default |
|---|---|---|
| Weighbridge exception | hours waiting | 0 |
| Curing release due | hours ahead | 24 |
| Yield below threshold | minimum % over 7 days | 90, a placeholder: set it per plant |
| Expiring document | lead days | 30 |
| Overdue maintenance | days late | 0 |
| Credit limit breach | % of limit | 100 |
| Budget overrun | % of budget | 100 |
| Stock below reorder, overdue CAPA | none | |

Credit limits and holds are at `/credit-control`.

## 9. Overtime

Set rules at `/hr/payroll/settings`, under Overtime.

- Each rule has weekday, weekend and holiday multipliers, and its own weekend days. A holiday beats a weekend.
- A rule for the employee's department, position or employment type applies first, by code. Next comes the general rule.
- With no active rule, overtime is 1.5 times the hourly rate.

## 10. Integrations

| Integration | State | Needs |
|---|---|---|
| Weighbridge indicators | Generic ASCII adapters, simulator | **[Needs hardware]** the indicator model and its frame format |
| Telematics | REST adapter, mock | **[Needs vendor]** a provider, URL and key |
| Machine data, belt scales | Push route, REST adapter, CSV, simulator | **[Needs hardware]** a PLC or counter gateway |
| Laboratory | REST adapter, CSV, simulator | **[Needs vendor]** a laboratory system |
| Mobile money | Mock only, no money moves | **[Needs vendor]** a provider adapter to be written |
| Label printing | PDF and ZPL files, no direct printing | **[Needs hardware]** a printer |

Details and keys are in `docs/integrations.md`.

## 11. Demo data

| Flag | Fills | Use |
|---|---|---|
| `SEED_CONSTRUCTION_DEMO` | New organization `CMDEMO`, three months | Client demonstrations |
| `SEED_FLEET_DEMO` | `DEMO` organization, one week | Fleet only |
| `SEED_MANUFACTURING_DEMO` | `DEMO` organization, one month | Plant only |

All three are off by default, need the `seed` profile and run only on an empty database. They add several minutes to the
first start, and a failure part way needs a fresh database. Never set them in production. With two organizations, a paid
licence must allow both.
