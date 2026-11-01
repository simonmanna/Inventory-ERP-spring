# MENA group demo data: what was loaded, how it flows, what broke

Date: 2026-11-02. Database `ierp_mena` (container `ierp-db`, port 5434), API on port 3013.
Seeder and how to re-run it: `tools/demo-data/mena/README.md`. Raw report output: `tools/demo-data/mena/reports/`.

Everything went through the real API, so the services did the postings, AVCO costing, maker-checker and idempotency
themselves. No rows were written to tables directly. The one exception is the `UGX` currency row: bug 2 below stops a
fresh database from creating the first UGX company without it.

## 1. Structure: three sister companies in one group

| Company | Org code | Does | Branches → stock locations |
|---|---|---|---|
| MENA Granite Ltd (group parent) | `MENA` | Head office, slab sawing and polishing, block plant, distribution | Namasuba HQ & Factory → `HQ-MAIN`, `HQ-FACT`; **Ntinda Showroom** (sales branch) → `HQ-NTD` |
| MENA Quarries Ltd | `MENA-QRY` | Nakifuma quarry: blasting, crushing, raw granite blocks | Nakifuma → `QRY-YARD` (stockpiles), `QRY-STORE` (fuel, explosives, spares) |
| MENA Granite Showroom Ltd | `MENA-SHOW` | Kabalagala retail granite and blocks | Kabalagala → `SHW-KBL` |

- Company group "MENA Group": HQ is the parent, the two others are 100 % subsidiaries. `admin@menagranite.test` can
  switch between all three companies.
- Each company has the others set up as intercompany partners (`IC-HQ`, `IC-QRY`, `IC-SHOW`).
- How the companies trade with each other:
  - The quarry sells aggregate and raw granite blocks to HQ.
  - HQ sells slabs, skirting and blocks to the showroom company.
  - HQ charges both subsidiaries a monthly management fee through intercompany journals, which post in both sets of
    books at once.
- Each company has 2 users: an admin who enters documents and an accountant who approves them (maker-checker).
- Cost centres:
  - HQ (7): Administration, Finance, Granite Processing, Block Manufacturing, Transport, Sales Namasuba, Sales Ntinda.
  - Quarry (4): Quarry Operations, Crushing, Transport, Administration.
  - Showroom (2).
- Chart of accounts: the standard chart plus 26 accounts per company:
  - Revenue by line: aggregate, blocks, granite, transport, intercompany sales, management fee.
  - Expense accounts: fuel, power, rent, repairs, transport hire, security, blasting, group fees, admin, marketing,
    interest.
  - Fixed-asset classes, a bank loan, share capital, and due to / due from sister companies.
- Units: tonne, bag, litre, kg and piece already existed. Added m² (`SQM`), running metre (`RM`) and truck trip (`TRIP`).

## 2. Prices: sample sheets compared with the demo

Sale prices are mostly 10–30 % above the sheets. Costs are illustrative.

| Item | Sample (Oct 2026) | Demo price | Demo cost |
|---|---:|---:|---:|
| Stone dust 0-6 mm / t | 31,000 – 33,000 | 38,000 | 19,500 (opening) |
| Aggregate 10-14 mm (½") / t | 31,000 – 56,500 delivered | 42,000 | 23,000 |
| Aggregate 14-25 mm (1") / t | 31,000 | 38,000 | 21,000 |
| Stone base CRR / t | 33,000 | 40,000 | 20,000 |
| Hardcore / t | 30,000 | 36,000 | 16,000 |
| Transport / trip | 560,000 – 968,000 | 850,000 (quarry) | transporter rate |
| Hollow block 4" / 6" / 8" | 1,500 / 1,800–2,000 / 2,200–2,500 | 1,800 / 2,300 / 2,800 (showroom 2,500 / 3,000) | from the bill of materials, about 1,080 / 1,420 / 1,720 |
| Royal Red slab / m² | 75,000 – 125,000 | 110,000 (showroom 125,000) | 50,200 when produced |
| Spotted Green / m² | 110,000 – 216,666 | 150,000 (170,000) | 50,500 when produced |
| Green spotted slash black / m² | 130,000 – 216,666 | 165,000 (185,000) | 98,000 |
| Viscount White, imported / m² | 162,000 – 333,333 | 265,000 (295,000) | 128,000 purchase price → **198,674 after landed costs** |
| Molding / running metre | 7,000 – 10,000 | 12,000 – 13,000 | 4,000 |

The customers come from the sheets: ACME, Spear On Road, Texa Solution, Metazone, Eng. Kasule, Malik Ssenabulya,
Sarajan, Zerigaber, Fidel, Canan Developers, Shay, Ophir, Mr Paul, Mawa, Karim, Loyality Rolex, Micheal Andom, Siimba,
Mapuk, Wara and Luzige. There is also one walk-in cash customer per company. Suppliers (cement, sand, fuel,
explosives, spares, imports, clearing, transport, Umeme, landlords) are fictional.

## 3. Timeline

Go-live is 1 Nov 2026. The opening position is at 31 Oct.

Stock receipts, production, truck trips and transfers can only be posted at the server's current date. There is no
way to backdate them, so October cannot be replayed. October is therefore loaded as the opening position:

- Stock: the slab counts come from `INVENTORY 04.10.2026`.
- Receivables: the October customers.
- Payables, bank and cash balances.
- Fixed assets (in the ledger only), loans and share capital.
- October payroll and depreciation.

The live transactions are dated 1–2 Nov 2026.

| Company | Opening stock | Opening receivables | Opening payables | Bank + cash + mobile money |
|---|---:|---:|---:|---:|
| HQ | 178.5 M | 75.3 M (including 28.7 M from the showroom company) | 71.6 M (including 46.5 M to the quarry) | 323.0 M |
| Quarry | 335.5 M | 155.8 M (including 46.5 M from HQ) | 40.8 M | 238.8 M |
| Showroom | 30.2 M | 10.8 M | 28.7 M (to HQ) | 45.7 M |

## 4. What was loaded

| | HQ | Quarry | Showroom |
|---|---:|---:|---:|
| Products / partners | 25 / 19 | 17 / 14 | 10 / 12 |
| Bills of materials / production orders | 6 / 6 | 5 / 5 | – |
| Purchase orders (receipt, bill, payment) | 5 (1 intercompany, 1 import with landed cost) | 3 | 1 (intercompany) |
| Sales orders → delivery orders → truck trips | 5 → 11 trips | 4 → 25 trips (+1 duplicate cancelled) | – |
| Sales invoices (cash, credit, intercompany) | 18 | 18 | 11 |
| Credit notes (sales returns) | 1 (50 damaged 8" blocks, Shay) | – | – |
| Customer receipts and supplier payments | 19 | 18 | 12 |
| Expenses (approved and paid) | 4 | 3 | 2 |
| Quotations | – | 1 open (Sarajan, 19.24 M, sent) | – |
| Journal entries | 173 | 142 | 65 |

### Business scenarios covered

**Quarry company**

- Purchasing:
  - Diesel, 20,000 L, part-paid.
  - Explosives and detonators.
  - Crusher jaw plates and belt, paid.
- Production:
  - Blast and haul 4,000 t of run-of-mine rock: explosives, detonators and diesel, plus overhead.
  - Crush it into 10-14 mm (780 t with 20 t scrap), 14-25 mm (1,200 t) and stone dust (1,000 t).
  - Cut 60 t of Royal Red raw blocks.
- Sales:
  - ACME: 560 t ordered, 520 t delivered over 13 transporter trips. The 40 t balance was closed short.
  - Spear On Road: CRR plus 5 transport trips.
  - Texa: hardcore plus transport.
  - Malik: cash order matching the sample order form.
  - 6 walk-in cash and mobile-money sales at the gate.
- Receipts: ACME, Eng. Kasule and HQ.
- Supplier payments: Petro (fuel) and the explosives supplier.
- Expenses: Umeme (power), excavator repair and security, each under its own expense category.
- Loan interest.

**HQ**

- Purchasing:
  - Cement, 1,200 ordered and 1,195 received. The other 5 are a short delivery: no quality-inspection rejection was
    loaded.
  - Sand.
  - Abrasives and resin.
- Import: Viscount White and Wave Gray slabs, with freight, duty and clearing (15 M in total) added to the stock cost
  as landed costs.
- Production:
  - Hollow blocks: 10,000 6" (150 scrap), 8,000 8" and 5,000 4".
  - Sawing and polishing: 270 m² Royal Red (8 m² scrap) and 180 m² Spotted Green.
  - Skirting from offcuts.
- Transfer of slabs and skirting to the **Ntinda Showroom** branch.
- Sales:
  - 5 delivered orders: Zerigaber, Fidel, Canan, the Mr Paul granite job and Shay.
  - 6 cash, mobile-money and bank counter sales.
- Return and credit note: Shay, 50 damaged 8" blocks.
- Expenses: rent, power, marketing and tipper hire.
- Management fees charged to both sister companies.

**Showroom company**

- 7 counter sales taken from the daily sales sheet.
- 2 credit sales: Siimba (Viscount White) and Wara, part-paid.
- Mapuk collection.
- Payment to HQ.
- Expenses: rent and power.

## 5. Report results

The figures are from the reports for 1 Oct – 30 Nov 2026. Two corrections were applied before these figures were taken
(both are kept as `fixups` phases, so the audit trail shows them):

- **Correction A:** all 9 expenses had posted to account 5200, because none had an expense category with a ledger
  account. They were voided and entered again under the right categories.
- **Correction B:** October payroll and depreciation had been booked on 1 Nov. They were reversed and carried in the
  opening position instead.

| Company | Revenue | Cost of goods sold | Gross profit (margin) | Operating profit | Net profit |
|---|---:|---:|---:|---:|---:|
| HQ | 64.18 M | 38.82 M | 25.36 M (39.5 %) | 18.80 M | 24.30 M (includes 15.5 M management fees) |
| Quarry | 86.75 M | 43.00 M | 43.75 M (50.4 %) | 48.78 M ⚠ | 33.78 M |
| Showroom | 17.19 M | 13.48 M | 3.71 M (21.6 %) | −4.24 M (month's rent against 2 days of sales) | −4.24 M |
| Group, consolidated | 116.16 M | | | | **31.84 M** after the fix (was 1.87 M); 22.0 M unrealised profit held in buyers' stock |

**Ledger checks**

- ✅ The trial balance balances in all three companies.
- ✅ The goods-received-not-invoiced account and the stock adjustment income account are back to zero.
- ✅ No negative stock is left. Diesel had gone negative at the crusher yard; 3,000 L was moved there from the fuel
  store.
- ✅ The quarry's stock ledger matches the general ledger to within 73 k.
- ✅ The intercompany reconciliation matches in both pairs:
  - HQ ↔ Quarry: 64.5 M.
  - HQ ↔ Showroom: 47.2 M.
- ✅ The eliminations remove intercompany revenue (51.97 M) and the management fees (15.5 M) from the group figures.
- ✅ In the quarry and showroom companies, the receivables aging matches the general ledger.

**Report problems**

- ✅ (fixed) The balance sheet balances in all three companies: total assets HQ 2,420.2 M, quarry 4,212.3 M, showroom 214.1 M. Before the fix it said `balanced: false` (bug 1).
- ✅ (fixed) The payables aging now includes payables raised by purchase-order receipts:

  | Company | Aging before | Aging after | General ledger | Remaining gap |
  |---|---:|---:|---:|---|
  | HQ | 19.7 M | 104.6 M | 119.6 M | 15.0 M landed-cost charges credited straight to AP (no document) |
  | Quarry | 16.2 M | 87.1 M | 99.1 M | 12.0 M management fee (intercompany journal) |
  | Showroom | 13.7 M | 29.9 M | 33.4 M | 3.5 M management fee (intercompany journal) |

  The remaining gaps are journal-based payables; see issue 14.
- ✅ (fixed) The stock-to-ledger tie-out shows 0 variance in HQ and the showroom (it showed 21.9 M / −24.7 M before the timezone fix).
- ⚠ The quarry's operating profit is inflated by 37.7 M of overhead absorbed into production with no actual overhead
  booked for November. There is no month-end process that compares absorbed with actual overhead.

**Stock values**

- HQ 284.9 M, quarry 473.7 M, showroom 32.9 M.
- Royal Red slab: produced at 50,207/m², against 62,000 average opening cost.
- Viscount White: landed cost lifts the purchase price from 128,000 to 198,674/m². The shipment was small, so freight,
  duty and clearing added 58 %.

## 6. Problems found, most serious first

**Fixed on 2026-11-02:** bugs 1, 2, 3 (supplier bill date and stock tie-out), 4 and 6. Each fix has a regression
test; the affected test classes (invoicing, procurement, accounting, fixed assets, intercompany, core; 256 tests)
pass. The one failure in that run is `ReportingIT` FX revaluation on the *reused* test database, a known artefact
of re-running against it; it passes on a fresh container. The full `./gradlew build` (JaCoCo gate included) is
green; the fixes are in commit `8887beb`.

- **1:** `AccountClassification.balanceSheetBalance` is used for the live and the snapshot balance sheet.
- **2:** `OrganizationsService` seeds currencies before the organization row; an unknown currency now gets a 400.
- **3:** the supplier-bill "future" check uses the company's calendar day, and the stock tie-out's default "now"
  includes today's dated documents. Fleet postings still use the real instant: a full business-date model is open.
- **4:** `ApAgingService` and the AP aging snapshot add open receipt-voucher payables per PO (vouchered less PO
  payments).
- **6:** consolidation eliminates the cost of intercompany goods and the unrealised profit in the buyer's stock
  (ADR-S028 §6(c)).

| # | Kind | Problem | Where / evidence | Suggested fix |
|---|---|---|---|---|
| 1 | **Bug** | The balance sheet adds accumulated depreciation to total assets. The gap is exactly 2 × accumulated depreciation in every company (557.8 M, 840 M, 36 M), so it reports `balanced: false`. | `FinancialReportsService.Totals.accumulate` adds the display balance of credit-normal asset accounts | Subtract contra-asset accounts on the asset side, and add a test with accumulated depreciation |
| 4 | **Bug / design** | A purchase order receipt creates the payable itself ("receipt voucher"). The supplier bill then has zero balance, so the payables aging and the supplier-payment balance check miss that money. Such suppliers can only be paid through the purchase order's **Pay** button. | `PaymentService` checks only open vendor bills; the aging reads only documents | Include receipt-voucher payables in the aging and in supplier payments, or let the bill carry the balance |
| 6 | **Bug** | Group consolidation removes intercompany revenue but not the matching cost, nor the profit still sitting in the buying company's stock. Group profit comes out at 1.87 M; the true figure is roughly 30 M or more. | `/intercompany/consolidation` | Eliminate intercompany cost of sales and the unrealised profit in inventory |
| 3 | **Bug** | The system mixes UTC with Kampala time. Between 00:00 and 03:00 Kampala time: (a) supplier bills dated "today" are refused as future-dated, which made the automatic intercompany bill fail; (b) truck trips post cost of sales on the previous day while their invoices carry today; (c) the stock tie-out "as of now" leaves out today's postings. | `VendorBillService.validateDates`, fleet dispatch posting date, `gl-tieout` | Use the organization's business date (`BusinessZone`) everywhere |
| 5 | Design conflict | An intercompany purchase received through a purchase order **plus** the automatic mirrored bill records the payable twice. | ADR-S028 §4 against receipt vouchers (P1-7) | Have the mirror skip purchase-order-matched receipts, or receive with an ad-hoc receipt |
| 2 | **Bug** | `POST /organizations/bootstrap` fails on an empty database for any currency except the seeded ones. The organization is inserted before the currency rows exist. | `OrganizationsService.bootstrap` | Insert the currencies before the organization |
| 7 | **Bug** | After a business error (400) on a route that requires an Idempotency-Key, retrying with the same key returns `409 OPERATION_PENDING` forever, although the message says to retry with the same key. | Sales order invoice: "Nothing to invoice" | Release the key when the request fails with a 4xx |
| 8 | Bug / UX | A sales order invoiced right after its last trip closed billed only 160 of 200 t (Spear) and left transport lines unbilled. The billable quantity is updated by an event after the trip commits. | `SalesFulfilmentSubscriber` | Recompute the billable quantity inside the invoice call, or wait for pending trip events |
| 9 | Gap | There is no opening-stock import: direct stock-in cannot be backdated and credits **Stock Adjustment Income** (4200). The demo needed a reclass journal. | `/inventory/direct-stock/in` | Add a dated "opening balance" stock movement posting to opening equity |
| 10 | Gap | The purchase order's Pay button has no bank account field. It credits the `default_bank` mapping, which pointed at the **1200 Bank** header account. | `/procurement/purchase-orders/{id}/pay` | Add an account field; map `default_bank` to a real bank account by default |
| 12 | Gap | A direct invoice always takes stock from the company's oldest warehouse, so the Ntinda branch cannot sell its own stock by direct invoice. | `InvoicingWorkflowsInitializer.warehouse()` | Use the branch's warehouse or a location chosen on the invoice |
| 13 | Gap | A receipt taken "on account" cannot be applied to invoices afterwards. The demo had to void and re-enter 12 receipts and payments. | `/payments` has no allocate route | Add `POST /payments/{id}/allocate` |
| 11 | Check | The quarry's stock ledger is 73 k above the general ledger after diesel went negative and was then topped up. | Inventory tie-out | Check how the revaluation of negative stock is posted |
| 14 | Report | The receivables aging leaves out receivables created by intercompany journals (15.5 M management fees) and the credit note not yet applied (135 k). | `/reports/ar/aging` | Include partner-tagged journal lines and credit notes |
| 15 | Documentation | OpenAPI shows wrong request bodies for many routes: branches, fiscal periods, taxes, expense categories, fleet plan-loads, every sales route, and goods receipts. Record names collide, and several routes take untyped JSON. | `/v3/api-docs` | Give the records unique schema names and use typed DTOs |
| 16 | UX | Several settings trip up a new user: a purchase order is "cash" unless set otherwise, and receiving a cash order needs an open cash register; an expense's `accountId` is the account it is paid from (its cost account comes from the category, and with no category it goes to 5200); approving an expense that is already posted gives an error. | | Default supplier orders to credit; show the category's account on the expense form |
| 17 | Info | The login limit (10 per 5 minutes per IP) blocks scripted loading. Seed with `THROTTLE_ENABLED=false`. | | — |

### Not loaded yet (the next steps for a complete finance demo)

- Fixed-asset register and depreciation runs. Fixed assets exist only as ledger balances so far.
- Bank reconciliation: import a statement and match it.
- Purchase requisition, RFQ and comparison of supplier quotes.
- Quality-inspection rejection at goods receipt.
- Customer price lists and credit limits.
- Budgets.
- The HR payroll module. Payroll is posted as journals.
- Quarry module: extraction logs, the weighbridge and blasting records.
- Precast curing.
- A month-end comparison of absorbed and actual overhead, and closing the period.
- A longer history: Nov–Dec activity to fill out the dashboards.

## 7. Environment notes

- Two earlier API processes are still listening on ports 3011 and 3012 (PIDs 5560 and 15632). Windows refused to stop
  them. End them in Task Manager.
- The seeding API runs on port 3013.
- To view the demo in the web app, point the Vite proxy at port 3013, or restart the API on port 3001 against
  `ierp_mena`. Sign in to organization `MENA` as `admin@menagranite.test`.
