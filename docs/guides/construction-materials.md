# Construction materials: user guide

> **The system built here has not yet been compiled or run.** Every screen, button and result below was written from the
> code, never tried. Expect differences until the owner has worked through `docs/VERIFY_CHECKLIST.md`.

This guide follows the work of each role: quarry to crusher, block plant to stockyard, order to weighbridge, delivery to
invoice. Configuration is in the [admin guide](construction-admin.md).

**[Needs hardware]** and **[Needs vendor]** mark steps that only work in full with a real device or supplier account.
Until then a simulator, a mock or a manual route stands in. `docs/integrations.md` has the list.

## Try it on the demo organization

Start on an empty database with the `seed` profile and `SEED_CONSTRUCTION_DEMO=true`. Sign in to organization `CMDEMO`.
Role users have password `Demo@123`. The administrator, `admin@construction.test`, has `SEED_ADMIN_PASSWORD`, default
`Admin@123`. Use these on a demo database only.

| Role | Sign-in | Default branch |
|---|---|---|
| Weighbridge operator | `weighbridge@construction.test` | QRY |
| Dispatcher | `dispatcher@construction.test` | KLA |
| Driver | `driver@construction.test` | QRY |
| Plant supervisor | `plant@construction.test` | QRY |
| Quality officer | `quality@construction.test` | QRY |
| Storekeeper | `stores@construction.test` | KLA |
| Accountant | `accounts@construction.test` | KLA |
| Manager | `gm@construction.test` | KLA |

The demo holds two branches (QRY quarry and plant, KLA Kampala depot), 13 fleet units and 20 customers with sites, and
about 90 days of work ending yesterday. One open delivery is waiting today on truck `CM-T01`. Its weighbridges use
simulator indicators.

## Weighbridge operator

Screens: Weighbridge (`/fleet/weighbridge`), New ticket (`/fleet/weighbridge/tickets/new`), Weighbridge Exceptions
(`/fleet/weighbridge/exceptions`), stored tares (`/fleet/weighbridge/tares`).

1. Open the queue at `/fleet/weighbridge`. Tabs show open tickets, trucks awaiting a second weight, exceptions and
   completed tickets.
2. For a delivery, open the trip and press **Weigh**, or start a new ticket and pick the trip. The ticket fills in the
   truck, driver, customer, product and expected quantity.
3. With the empty truck on the platform, capture the first weight. Or use the truck's stored tare if it has a valid one.
   **[Needs hardware]** A device reading needs an indicator on the network or a serial line. Each make needs its frame
   format set by the administrator.
4. After loading, capture the second weight. The ticket works out gross, tare and net, and compares net with expected.
5. If the difference is within tolerance, the ticket completes and sets the trip's loaded quantity.
   If not, the ticket goes to exceptions and someone else must decide it. You cannot approve your own ticket.
6. Print the ticket from its screen. A completed ticket cannot be edited. To correct one, void it, with a reason, and
   reissue it. Voiding needs `weighbridge:void`, and it is refused once the trip has left.

Keying in a weight needs `weighbridge:manual_entry` and a reason, and the ticket is marked manual. The demo role does
not have this permission, so manual entry stays a supervisor's decision.

Purchase receipts use the same screens with subject **purchase receipt**. A completed ticket can raise the goods receipt.

## Dispatcher

Screens: Delivery Orders (`/fleet/delivery-orders`), Dispatch Board (`/fleet/dispatch`), Trips (`/fleet/trips`), Customer
Sites (`/fleet/customer-sites`), Delivery Discrepancies (`/fleet/delivery-discrepancies`).

1. Create the order at `/fleet/delivery-orders/new`, or make it from a confirmed sales order. Choose the customer, site,
   yard, date and lines. A blocked customer is refused by credit control.
2. On the order, use **Plan loads**. The system splits it by truck payload and body volume, using each product's load
   factor. Quantities and tonnage appear before anything is saved.
3. On `/fleet/dispatch`, assign each load: an own truck and driver, or a transporter with its rate. Trucks with expired
   documents or under repair, and drivers not eligible, are refused.
4. **Start loading** when the truck reaches the loader, then send it to the weighbridge.
5. **Dispatch** the trip. This issues the stock at the weighed quantity and prints the delivery note number. If the
   weighbridge is required for that product, dispatch waits for a completed ticket.
6. After delivery, **Close trip**. An order weighed short of its quantity stays in progress: close the order with a
   reason, and it can then be invoiced.
7. Review differences at `/fleet/delivery-discrepancies`. Every shortage, excess and return is logged with a reason for
   someone with `fleet_dispatch:approve_discrepancy` to decide.

**[Needs vendor]** The Live Fleet map (`/fleet/live`) needs a telematics provider. It uses a mock until one is set.

## Driver

Screens, on a phone: Driver App (`/driver`), trips (`/driver/trips`), pre-trip check (`/driver/pre-trip`), fuel
(`/driver/fuel`). The driver's user must be linked to their employee record.

1. Do the pre-trip check before starting. A failed item raises a defect for the workshop.
2. Open the trip at `/driver/trips` and see the site, contact and load.
3. At the site, record proof of delivery: the receiver's name, the delivered quantity, and a signature. If the quantity
   differs, or anything comes back, choose a reason. The difference goes to the dispatcher.
4. Enter fuel at `/driver/fuel`. It stays a draft until someone in the office posts it.

**[Needs hardware]** Position on proof of delivery comes from the phone or a telematics device. Without either, none is
stored.

## Plant supervisor

Screens by area:

| Area | Screens |
|---|---|
| Quarry | Extraction Logs (`/quarry/extraction`), Haulage (`/quarry/haulage`), ROM Stockpiles (`/quarry/stockpiles`), Drilling & Blasting (`/quarry/blasts`) |
| Crusher | Crushing Runs (`/manufacturing/crushing`), New run (`/manufacturing/crushing/runs/new`), Aggregate Stockpiles (`/manufacturing/crushing/stockpiles`) |
| Block plant | Precast orders (`/manufacturing/precast/orders`), Curing board (`/manufacturing/precast/curing`), Mix designs (`/manufacturing/precast/mix-designs`) |
| Shift | Operator Terminal (`/manufacturing/terminal`), Shop-floor Live (`/manufacturing/floor-board`), Shift Reports (`/manufacturing/shift-reports`), Plan Performance (`/manufacturing/plan-performance`) |

**Quarry.** Log each shift's extraction by bench, with tonnes by survey, loads or weighbridge, and post it. Posting puts
run-of-mine stone into the ROM stockpile at its cost per tonne. Explosives are a separate, approval-gated flow: request,
approve, then issue from the magazine.

**Crusher.** Create a run for the plant and grading profile. Enter feed tonnes, hours, downtime and the actual tonnes of
each fraction, then post. Posting takes the feed out of the stockpile and shares its cost across the fractions.
**[Needs hardware]** Belt-scale and machine counts arrive through a gateway. Until then they are typed in or imported.

**Block plant.** Create an order from a mix design and record the pressing run. Blocks go to curing. After the minimum
curing time the batch is cubed and waits for its strength result. Only a passed batch reaches saleable stock.

**End of shift.** Submit the shift report at `/manufacturing/shift-reports`. Someone else must approve it.

## Quality officer

Screens: Quality Overview (`/quality`), Inspection Lots (`/quality/lots`), Specifications (`/quality/specifications`),
Inspection Plans (`/quality/plans`), Nonconformance Reports (`/quality/ncrs`), Corrective & Preventive Actions
(`/quality/capas`), Laboratory Results (`/quality/lab`), Batch Traceability (`/quality/traceability`), Quality Reports
(`/quality/reports`).

1. Open a lot at `/quality/lots` and enter the results against its specification.
2. Hold or quarantine a lot that fails, and raise a nonconformance report. Each lot then needs a decision: release,
   reject or downgrade.
3. For blocks, record the cube results on the curing batch at `/manufacturing/precast/curing`. The strength gate decides
   whether the batch is released.
4. Print the lot's certificate from its screen.
5. Track a batch from delivery back to its production and raw material at `/quality/traceability`.

**[Needs vendor]** Automatic results need a laboratory system. Until then, enter them by hand or import a CSV at
`/quality/lab`.

## Storekeeper

Screens: Inventory (`/inventory`), Stock Transfers (`/inventory/transfers`), Inventory Count (`/inventory/count`), Stock
Adjustments (`/inventory/adjustments`), Goods Receipts (`/procurement/goods-receipts`), Stock Ledger
(`/inventory/ledger`), Reservations (`/inventory/reservations`), Replenishment (`/inventory/replenishment`), Fuel Tanks
(`/fleet/fuel/tanks`).

1. Receive purchases at `/procurement/goods-receipts`, against the purchase order. Bulk loads can arrive through a
   weighbridge ticket.
2. Move stock between yards at `/inventory/transfers`, for example quarry stockpile to depot.
3. Issue spares to a work order from the work order's own screen, so the cost lands on the vehicle.
4. Count at `/inventory/count`. Differences are posted when the count is submitted and approved.
5. Stock adjustments go through `/inventory/adjustments`. If the separation pair is on, someone else must approve them.
6. Check reorder alerts at `/inventory/replenishment`.

## Accountant

Screens: Invoices (`/invoices`), Payments (`/payments`), Credit Control (`/credit-control`), Credit Notes
(`/credit-notes`), Freight Bills (`/fleet/freight-bills`), Journal Entries (`/journal-entries`), Trial Balance
(`/trial-balance`), Profit and loss (`/profit-and-loss`), Balance sheet (`/balance-sheet`), Inventory to GL tie-out
(`/inventory-gl-tieout`), Fiscal periods (`/fiscal-periods`).

1. Invoice delivered orders from the delivery order with **Invoice**. Goods are billed at the trips' billable quantity,
   plus any transport charge. The invoice does not issue stock again, because dispatch already did.
2. Post the invoice at `/invoices`, then record receipts at `/payments` against it.
3. Match each transporter's invoice to its draft freight bill at `/fleet/freight-bills`. Within tolerance it raises the
   supplier bill. Outside tolerance it is disputed until someone with `fleet_freight:approve_variance` accepts it.
4. Follow production cost at `/manufacturing/costing` and work in progress at `/manufacturing/stage-wip`.
5. Before closing a month, tie stock to the ledger at `/inventory-gl-tieout`, then run the checklist from
   `/fiscal-periods`.

**[Needs vendor]** Mobile money collection (`/payments/mobile-money`) runs on a mock: no money moves. A bank feed is a
file import at `/accounts/bank-import`, not a live connection.

## Manager

Screens: Dashboards Overview (`/analytics`) and its pages, Alerts & Exceptions (`/alerts`), Approvals (`/approvals`),
Fleet Reports (`/fleet/reports`), Branch Performance (`/reports/branches`), BI Exports (`/reports/bi-exports`).

1. Start at `/analytics`. Open the page for an area: `/analytics/quarry`, `/analytics/aggregates`, `/analytics/blocks`,
   `/analytics/inventory`, `/analytics/procurement`, `/analytics/sales`, `/analytics/fleet`, `/analytics/maintenance`,
   `/analytics/finance`, `/analytics/branches`. Each page has filters and opens the records behind a figure.
2. Decide what is waiting for you at `/approvals`: exceptions, discrepancies, explosive issues, large documents.
3. Review `/alerts` for stock, documents, maintenance, credit, yield and overdue actions. It appears only when the
   administrator has switched alerts on.
4. Export data for a BI tool at `/reports/bi-exports`.

A user limited to some branches sees only those branches. Cash and OEE are left out of such a view, because they have no
reliable branch.
