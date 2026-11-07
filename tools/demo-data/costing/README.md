# Costing demo (tenant MENA)

Shows how one product's cost is built, and how the same product costs differently depending on the supplier. It is all
real documents through the API: POs, receipts, landed cost vouchers, production orders, invoices, GL. No SQL.

Sign in at http://localhost:8080, organization `MENA`, `admin@menagranite.test` / `MenaDemo@2026` (local demo only).
All demo records are named "costing demo" or start with `CD-`, and new suppliers end in `(DEMO)`.

## What a block is made of

`CD-BLK6` Hollow block 6 inch, BOM per 1,000 blocks, two levels:

| Level | Item | Qty | Notes |
|---|---|---|---|
| Block | `CD-MIX` Concrete mix C15 | 8 m3 | Semi-finished, has its own BOM |
| Block | `CD-WRAP` Pallet wrap | 4 rolls | |
| Block | Pressing, curing, palletising | 150 / 60 / 45 min | Work centre rates: labour, machine, overhead |
| Block | Fixed overhead | 60,000 | Per run |
| Mix, per 10 m3 | `CD-CEM` Cement | 30 bags | |
| Mix, per 10 m3 | `CD-SAND` Sand | 9 t | |
| Mix, per 10 m3 | `CD-DUST` Stone dust | 7 t | |
| Mix, per 10 m3 | `CD-ADMIX` Admixture | 5 L | |
| Mix, per 10 m3 | `CD-WATER` Water | 1,800 L | |
| Mix, per 10 m3 | Batching and mixing | 60 min | |

Current roll-up: **UGX 1,933.96 per block** (material 1,616.46, conversion 257.50, overhead 60.00).

## The same material from different suppliers (UGX)

| Material | Supplier | Invoice | Charges paid by buyer | Landed unit cost |
|---|---|---|---|---|
| Cement, bag | Hima Cement Distributors | 36,500 | none, delivered | **36,500.00** |
| Cement, bag | Kasese Cement Traders | 33,500 | haulage 3,200, offloading 400 | **37,100.00** |
| Cement, bag | Mombasa Bulk Cement Imports | USD 7.80 x 3,700 = 28,860 | freight, duty, insurance 6,855.31 | **35,715.31** |
| Sand, t | Lake Victoria Sand Dealers | 42,000 | none, delivered | **42,000.00** |
| Sand, t | Masaka Sand Pits | 26,000 | haulage 14,000 | **40,000.00** |
| Stone dust, t | Namanve Stone Crushers | 34,000 | none, delivered | **34,000.00** |
| Stone dust, t | Mukono Stone Crushers | 31,000 | haulage 5,000 | **36,000.00** |

The cheapest invoice is not the cheapest supplier: Kasese is 3,000 a bag below Hima on invoice and 600 above it landed.

## The same block from each sourcing round

| Run | Materials from | Good blocks | Material | Conversion and overhead | Unit cost |
|---|---|---|---|---|---|
| MO-00092 | Hima, Lake Victoria, Namanve | 12,500 | 20,168,000 | 3,968,583 | **1,930.93** |
| MO-00094 | Kasese, Masaka, Mukono | 12,500 | 20,308,000 | 3,968,583 | **1,942.13** |
| MO-00096 | Mombasa import, Masaka, Namanve | 12,500 | 19,863,000 | 3,968,583 | **1,906.53** |
| MO-00098 | Same as MO-00096, poor press run | 11,800 (700 scrap) | 19,863,000 | 4,317,333 | **2,049.18** |

Material includes the mix order's mixing cost. Selling price is 2,400. 16,000 blocks were sold, and FIFO took 12,500 at
1,930.93 and 3,500 at 1,942.13, so COGS is 30,934,027.

## Click path for the client

| # | Show | Screen |
|---|---|---|
| 1 | What a block costs and why, line by line | Manufacturing > BOMs > `Hollow block 6in per 1,000`, then the mix BOM |
| 2 | Invoice price to landed price, per supplier | Purchases > Landed cost reports, tab By shipment |
| 3 | One charge split across products, by weight and value | Purchases > Shipments, then voucher LC-00005 |
| 4 | Each charge is its own supplier bill | Voucher LC-00002 |
| 5 | Actual cost of a run by labour, machine, overhead | `/manufacturing/costing`, orders MO-00092 and MO-00098 |
| 6 | Each run stays its own layer | Inventory > `CD-BLK6` lots |
| 7 | Late bill after stock was used | Voucher LC-00007: 300,000 to stock, 600,000 to WIP, then run CAR/00001 |
| 8 | Two suppliers blended on AVCO | Inventory > `CD-CEM`: 300 at 36,715.31 + 300 at 37,000 = 36,857.66 |
| 9 | Everything ties out | Landed cost reports, tab Clearing account; `/procurement/landed-cost-control` |

## Avoid on screen (defects found, not fixed)

| Where | Problem | Cause |
|---|---|---|
| Order variances: Labour efficiency, Overhead, Total | Overstated: "56.87 actual, 4.25 standard" hours. Round A shows 2.3M variance with no real difference. Material price and Yield are right | Standard hours come from one BOM run and are not scaled to the order quantity |
| BOM roll-up on FIFO materials | Material shows at cost 0, so roll-up and frozen standard are far too low. Materials here are AVCO to avoid it | `CostingService.averageCost` reads `runningAverageCost`, which stays 0 on FIFO stock |
| Landed charge in foreign currency with a generated bill | Bill posts face value: USD 1,050 reached AP as 1,050, not 3,906,000, leaving 2155 open. Freight here is entered in UGX | Generated bill is not converted at the charge's rate |
| Cost adjustment run | Late cost on mix went straight to COGS (600,000), though blocks from those runs are unsold | The run does not cascade through a second production level |
| Margin report by product | Blocks show cost 31,294,145 against GL COGS 30,934,027 | Report uses average production cost, not issued FIFO layers |
| PO currency | A PO without a currency is stored as USD at rate 1, so older MENA POs and their price history show USD. The demo POs name UGX | Column default `USD` |
| Supplier price history for cement | Mombasa shows 7.80 USD beside UGX prices, invoice only. Use the landed table instead | History holds the invoice price |
| Goods bill for the USD PO | Document shows USD 7,020 at rate 1. GL is right: AP was raised at receipt at 25,974,000 | Bills take no rate |
| Landed cost control, stock row | Shows a 0.03 difference across the org, so it is marked not OK. Not traced | |

## Run

```bash
cd tools/demo-data/costing
python run.py --dry-run
python run.py --confirm MENA              # masters, scenario, verify; a re-run skips finished steps
python run.py --confirm MENA cd_verify    # checks 20 numbers quoted above, exit 2 on a failure
```

Progress is in `state.MENA.json`. Refuses non-local hosts. The API's PO and receipt routes do not replay on the
Idempotency-Key, so each sub-step is recorded before the next. Dates are fixed in `base.py` (`TODAY`).

The run depends on a clean start: each round consumes its whole purchase, so seeding twice over the same stock mixes
rounds. To redo it, restore `C:\Dev\db-backups\ierp_mena_2026-11-06_pre-costing-demo.dump` first. Modules are prefixed
`cd_` because the GreenRock folder, which holds the shared client, has its own `masters.py`.
