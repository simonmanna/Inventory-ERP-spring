# GreenRock quarry demo data

Fictional tenant for demos: GreenRock Construction Materials Ltd (UGX, Central Uganda). The seeder only calls the running
API, so stock, GL postings, approvals and segregation of duties are the real ones. Nothing is inserted with SQL.

Sign-in at http://localhost:8080, organization `GRC`, password `GreenRockDemo@2026` (local demo only):
`admin@greenrock.test` (maker), `accounts@greenrock.test` (second approver), `viewer@greenrock.test` (no permissions).

## Seeded companies

| Tenant | Sign-in | Notes |
|---|---|---|
| `MENA` | `admin@menagranite.test` / `MenaDemo@2026` | The demo company in use: everything below, including `extras`, on top of its existing data |
| `GRC` | see above | Separate copy, base phases only |

To seed MENA: `GREENROCK_ORG=MENA python run.py --confirm MENA`. State is in `state.MENA.json`. My Trips and the Driver App
need a driver login: `driver@menagranite.test` / `MenaDemo@2026`.

`extras` fills the remaining Manufacturing & Operations screens with illustrative data. A refused step is reported and
skipped, and a re-run retries it. `fix_stock` tops up demo stockpiles that those deliveries took below zero. `verify`
checks written for a clean tenant fail on MENA: tenant isolation (it looks for MENA's own pits) and sales margin (older
products have no cost).

## Run

```bash
cd tools/demo-data/greenrock
python run.py --dry-run             # target and phases, writes nothing
python run.py --confirm GRC         # seed, then verify; a re-run skips finished steps
python run.py --confirm GRC verify  # verification only
```

Safeguards: refuses any host but localhost, needs `--confirm <tenant>`, and every write has an Idempotency-Key. Progress
is in `state.<ORG>.json`. For a second clean copy use a new tenant: `GREENROCK_ORG=GRC2 python run.py --confirm GRC2`.
`sales.py` and `quarry.py` hold fixed dates (`TODAY`), so update them before seeding on a later day.

Needs modules on: quarry, fleet, manufacturing, orders, HR. API on `127.0.0.1:3001`. On Windows do not use `localhost` in
`ERP_BASE` from Python: each call waits about 2 s for the IPv6 attempt.

## Phases

| Phase | What |
|---|---|
| masters | Tenant, 3 branches, warehouses, products, customers, employees, fleet, drivers |
| quarry | 3 pits (one quarry site each, the reports compare sites), targets, 81 posted logs, haulage |
| crushing | 2 lines, grading profiles (market-value allocation), posted runs, product cost prices |
| quality | Specifications, inspection plans, lots: passed, held, rejected + NCR |
| pricing | Customer sites with distance, 5 freight bands, list + volume + contract prices, weighbridge simulator |
| sales | 18 orders to invoice: delivery orders, trips, tickets, dispatch, POD, goods and freight invoices |
| fleetops | Fuel logs, downtime, work orders |
| verify | Reads the reports back and checks the scenarios; writes `verify-result.<ORG>.json`; exit 2 on a failure |

## Where to show each scenario

| Scenario | Screen |
|---|---|
| 1 Pit yields (A 22 %, B 15 %, C 30 % waste) | Quarry > Reports, cost per tonne, group by site, from 7 Oct 2026 |
| 2 Planned 500, actual 380 | Crushing run CR-00002 (note and downtime), Crushing > Reports > plant hours |
| 3 Rejected batch | Quality > Lots (failed lot), Quality > NCRs |
| 4 100 / 96 / 90 | Sales order SO-2026-00001, order lines: Delivered 96, Invoiced 90 |
| 5 Same order, near and far | Fleet > Reports > delivery profitability by delivery order: DO-000002, DO-000003 |
| 6 5-10mm vs 20-40mm | Same report: DO-000004, DO-000005. Crushing > Reports > cost per tonne |
| 7 Utilisation and downtime | Crushing > Reports > plant hours, Fleet > Maintenance > Downtime |
| Weighbridge exceptions | Fleet > Weighbridge > Exceptions (1 pending, 1 approved), Void queue, Stored tares |
| Delivery shortage | Fleet > Delivery discrepancies (DSC-000001 pending: decide it live, then invoice the freight) |

## Known limits

- Dispatch and stock issue have no backdating, so all trips, tickets and invoices are dated the seed day. Extraction and
  crushing do cover 30 days. Sales and dispatch trends over time are therefore flat, and fleet utilisation looks low.
- Screens default to month to date. Pick 7 Oct 2026 as the start to see the 30 days.
- Defect: creating a ticket with `tripId` for a sales-module order fails with "Sales order not found". The trip pre-fill
  copies the SalesOrder id into `salesOrderId`, which is checked against the legacy `Order` table
  (`WeighbridgeTicketService`, `WeighbridgeTripFeed.applyTripDefaults`). The seeder creates the ticket, then links the
  trip with a header edit. A user creating a linked ticket this way from the screen will probably hit the same error.
- Defect: editing the quantity on a draft sales-order invoice does not update `SalesInvoiceLink`, so the order's Invoiced
  figure then disagrees with the document. Scenario 4 avoids this by invoicing before the last trip.
- Not implemented, so not seeded as if it were: a dispatched-versus-invoiced exception report, duplicate-ticket detection
  (only a second live ticket per truck or trip is refused), automatic tare-discrepancy exceptions, a planned quantity on a
  crushing run (it is in the run note), reports by pit after crushing (the pit is named in the run note), pricing by
  distance inside the price list (freight is a delivery charge rule, invoiced separately), a delivered-price dashboard.
- Weights come from the simulator adapter. No physical indicator is connected.
- Quality values are illustrative, not certified results. Licence numbers and authorities are fictional.
