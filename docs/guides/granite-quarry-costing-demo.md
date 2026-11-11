# Granite quarry costing demo: the accountant's worksheet

Organization `GQDEMO` holds one month of a quarry and construction-materials business: purchases, extraction,
crushing, block making, imported material with landed cost, sales, delivery, receipts and payments. All of it is
posted through the normal engines (quarry, crushing, production stages, purchasing, landed cost, sales delivery,
invoicing, payments). Stock valuation, the general ledger and the profitability reports therefore agree, and every
figure below can be recomputed by hand from the documents.

`GraniteQuarryDemoSeedIT` checks the figures in this worksheet on every build.

## Switch it on

`SEED_COSTING_DEMO=true` with the `seed` profile. Unlike the other demo seeds it also runs on a database that already has
organizations. It is skipped when `GQDEMO` exists, and it never touches `DEMO` or `CMDEMO`. To see the quarry, crusher and
block screens, enable `ENABLE_QUARRY=true` and `ENABLE_MANUFACTURING=true` on the API and `VITE_ENABLE_MANUFACTURING=true`
on the web build.

Sign in to organization `GQDEMO`. To try everything (every permission: create, edit, delete, approve, every report),
use `demo@granite.test` with password `Demo@123`. The administrator is `admin@granite.test` with `SEED_ADMIN_PASSWORD` (default
`Admin@123`). The role users have password `Demo@123`: `accounts@granite.test` (accountant), `gm@granite.test` (general
manager), `plant@granite.test`, `stores@granite.test`, and the others listed in the construction guide with the
`@granite.test` domain.

**The month:** every document is dated in the calendar month before the day the seed ran (day 1 to day 28), so the
whole period is closed and can be reported as one month. Currency UGX. The rate is USD 1 = UGX 3,700. All prices
exclude VAT.

## 1. Master data

| Code | Product | Unit | Standard cost | Selling price |
|---|---|---|---:|---:|
| GQ-ROM | Raw granite (run-of-mine) | t | 19,000 | |
| GQ-AGG-20 | Aggregate 20mm | t | 28,421.05 | 45,000 |
| GQ-AGG-10 | Aggregate 10mm | t | 28,421.05 | 40,000 |
| GQ-DUST | Stone dust | t | 28,421.05 | 20,000 |
| GQ-CEMENT | Cement 50 kg bag | bag | 35,000 | |
| GQ-SAND | Sharp sand | t | 35,000 | |
| GQ-ADMIX | Water & block admixture | L | 500 | |
| GQ-BLOCK-6 | Concrete block 6 inch | pcs | 1,818.68 | 2,500 |
| GQ-BASALT | Basalt chippings 14mm (imported) | t | 81,400 | 95,000 |

The customers are Kampala Builders Ltd (GQ-C01) and Nile Towers Construction (GQ-C02). The twelve suppliers are
GQ-S01 to GQ-S12.

Branches: QRY (quarry and plant) and KLA (Kampala depot). Stock is held in the "Quarry stockyard" (`MAIN`, branch
QRY) and on the "ROM pad" stockpile.

Expense accounts added for the demo: 6310 Drilling & Extraction Contractors, 6320 Plant & Equipment Hire, 6330 Internal
Haulage, 6340 Electricity, 6410 Fuel & Diesel, 6420 Repairs & Maintenance, 6460 Outbound Freight & Transport, and 3010
Owner's Capital.

## 2. How costs flow (absorption costing)

The actual costs are booked from bills and journals to expense accounts (6310 to 6420, 5710, 5600). As production
happens, the quarry and the plants move the same amounts into stock against "absorbed" contra accounts:

- 5361 Quarry Extraction Cost Absorbed, from extraction logs.
- 5350 Manufacturing Overhead Absorbed, from crushing and the block press.

Expense minus absorbed is **zero** in the P&L. The cost reaches the P&L as **cost of sales (5100)** only when the goods
are delivered, and unsold goods stay in **Inventory (1400)**.

| | UGX |
|---|---:|
| Actual production costs (6310–6420, 5710, 5600) | 27,430,000 |
| Absorbed into stock (5361 19,000,000 + 5350 8,430,000) | (27,430,000) |
| Net effect on profit | 0 |

## 3. Demo one: extraction and crushing (batch GQ-B01)

**Day 2, extraction log** at Mukono granite quarry, bench B1: 1,000 t raw granite to the ROM pad.

| Cost | Bill | UGX |
|---|---|---:|
| Drilling and extraction | Rocktech Drilling, RTD-0457 → 6310 | 12,000,000 |
| Loading equipment | Equator Plant Hire, EPH-1180 → 6320 | 4,000,000 |
| Internal transportation | Lakeside Haulage, LH-3301 → 6330 | 3,000,000 |
| **Extraction cost** | | **19,000,000** |

Cost per tonne: 19,000,000 ÷ 1,000 = **19,000**. GL: Dr 1400 Inventory 19,000,000 / Cr 5361 19,000,000.

**Day 5, crushing run** on Crusher line 1 (jaw crusher, then a triple-deck screen), grading profile "Granite split
60/25/10", joint costs shared by quantity:

| Processing cost | Basis | UGX |
|---|---|---:|
| Diesel and fuel | 800 L × 5,000 (jaw crusher meter) | 4,000,000 |
| Electricity | 1,000 kWh × 1,000 (screen meter) | 1,000,000 |
| Labour | 100 crew hours × 10,000 | 1,000,000 |
| Equipment maintenance | manual cost line | 1,000,000 |
| Depreciation allocation | manual cost line | 1,000,000 |
| **Processing cost** | | **8,000,000** |

| Output | Tonnes | Value (UGX) |
|---|---:|---:|
| Aggregate 20mm | 600 | 17,052,631.58 |
| Aggregate 10mm | 250 | 7,105,263.16 |
| Stone dust | 100 | 2,842,105.26 |
| Waste (normal loss, 5%) | 50 | 0 |
| **Total** | **1,000** | **27,000,000.00** |

Production cost 19,000,000 + 8,000,000 = 27,000,000 over 950 saleable tonnes = **28,421.05 per tonne**. The waste carries
no value, so the good stone absorbs it (expected recovery 95%).

GL: Dr 1420 WIP / Cr 1400 (feed 19,000,000); Dr 1420 WIP / Cr 5350 (processing 8,000,000); Dr 1400 / Cr 1420
(outputs 27,000,000).

The actual processing costs are booked at month end:

| Day | Document | Account | UGX |
|---|---|---|---:|
| 25 | Equator Fuels EQF-88213 | 6410 | 4,000,000 |
| 25 | Kampala Power KPD-ACC-4471 (crusher line) | 6340 | 1,000,000 |
| 25 | Crusher Spares CSS-0719 (crusher) | 6420 | 1,000,000 |
| 28 | Payroll journal (crusher crew) | 5710 | 1,000,000 |
| 28 | Depreciation journal (crusher plant) | 5600 | 1,000,000 |

## 4. Demo two: product profitability of the batch

If the whole batch were sold at list price:

| Product | Tonnes | Revenue | Production cost | Gross profit | Margin |
|---|---:|---:|---:|---:|---:|
| 20mm | 600 | 27,000,000 | 17,052,631.58 | 9,947,368.42 | 36.8% |
| 10mm | 250 | 10,000,000 | 7,105,263.16 | 2,894,736.84 | 28.9% |
| Stone dust | 100 | 2,000,000 | 2,842,105.26 | **(842,105.26)** | **−42.1%** |
| **Batch** | **950** | **39,000,000** | **27,000,000.00** | **12,000,000.00** | **30.77%** |

Stone dust sells **below** its allocated cost. Management can raise its price, cut its processing cost, bundle it, sell
it to another segment, or treat it as a by-product: set the grading line to `nrv` or `zero` valuation and the 20mm and
10mm then carry more of the cost.

The batch as actually used: 4 t of the 20mm goes into the block plant (section 5) and the other 946 t are sold (section
7).

## 5. Demo three: 1,000 concrete blocks (day 7, lot BLK-001)

Materials are bought on day 1 and received on day 4. Lake Cement supplies 60 bags of cement at 35,000 and 200 L of
admixture at 500 (bill LC-INV-2207). Nile Sand Dealers supplies 10 t of sand at 35,000 (bill NSD-0981). The bill of
materials "Concrete block 6 inch, 1,000 pcs" is backflushed when the lot is put away:

| Material | Quantity | Unit cost | UGX |
|---|---:|---:|---:|
| Cement | 30 bags | 35,000 | 1,050,000.00 |
| Sand | 5 t | 35,000 | 175,000.00 |
| Aggregate 20mm (own) | 4 t | 28,421.05 | 113,684.21 |
| Water & admixture | 100 L | 500 | 50,000.00 |
| **Materials** | | | **1,388,684.21** |

| Conversion cost | UGX | Actual cost booked |
|---|---:|---|
| Direct labour | 200,000 | Payroll journal day 28 (5710) |
| Electricity | 100,000 | Kampala Power KPD-ACC-4471 (6340) |
| Machine depreciation | 80,000 | Depreciation journal day 28 (5600) |
| Maintenance | 50,000 | Crusher Spares CSS-0719 (6420) |
| **Conversion** | **430,000** | |

Total 1,388,684.21 + 430,000 = **1,818,684.21**, which is **1,818.68 per block**. Sold at 2,500, the gross profit is
681,315.79 (27.25%).

## 6. Demo four: landed cost of imported basalt

| Day | Document | USD | UGX at 3,700 |
|---|---|---:|---:|
| 3 | Purchase order, Mombasa Basalt Exporters, 500 t × $16 | 8,000 | 29,600,000 |
| 8 | Received into the stockyard; bill MBE-INV-5530 | | |
| 9 | Landed cost voucher, allocated by quantity: | | |
| | Customs and clearing, Swift Clearing SCF-2291 | 1,000 | 3,700,000 |
| | Transport Mombasa–Kampala, Northern Corridor Freight NCF-7781 | 1,500 | 5,550,000 |
| | Loading and unloading, Kasese Loading KLS-0412 | 300 | 1,110,000 |
| | Port and documentation charges, Swift Clearing SCF-2292 | 200 | 740,000 |
| | **Total landed cost** | **11,000** | **40,700,000** |

40,700,000 ÷ 500 t = **81,400 per tonne**, against 59,200 for the supplier price alone. The voucher raises the four
charge bills (in USD) and moves the basalt's average cost to 81,400.

## 7. Demo five: sales orders, delivery and profitability

| Day | Order | Customer, branch | Lines | Revenue |
|---|---|---|---|---:|
| 12 | SO-1 (ref KB-PO-1101) | Kampala Builders, KLA | 100 t 20mm × 45,000; 50 t 10mm × 40,000 | 6,500,000 |
| 15 | SO-2 (ref NTC-LPO-77) | Nile Towers, QRY | 496 t 20mm × 45,000; 200 t 10mm × 40,000; 100 t dust × 20,000 | 32,320,000 |
| 18 | SO-3 (ref KB-PO-1124) | Kampala Builders, KLA | 1,000 blocks × 2,500 | 2,500,000 |
| 20 | SO-4 (ref NTC-LPO-81) | Nile Towers, QRY | 300 t basalt × 95,000 | 28,500,000 |
| | | | **Total** | **69,820,000** |

Each order is confirmed, delivered (stock out at average cost: Dr 5100 / Cr 1400), signed for, invoiced and posted
(Dr 1300 / Cr 4100). SO-1 is delivered by Lakeside Haulage for **500,000** (bill LH-3342, day 13, account 6460, tagged
to the customer and the order).

**SO-1 contribution (demo five):**

| | UGX |
|---|---:|
| Sales revenue | 6,500,000.00 |
| Production cost (150 t × 28,421.05) | 4,263,157.89 |
| Gross profit | 2,236,842.11 |
| Delivery cost | 500,000.00 |
| **Contribution profit** | **1,736,842.11 (26.72%)** |

**Product profitability (what was sold):**

| Product | Qty | Revenue | Cost of sales | Gross profit | Margin |
|---|---:|---:|---:|---:|---:|
| Aggregate 20mm | 596 t | 26,820,000 | 16,938,947.37 | 9,881,052.63 | 36.8% |
| Aggregate 10mm | 250 t | 10,000,000 | 7,105,263.16 | 2,894,736.84 | 28.9% |
| Stone dust | 100 t | 2,000,000 | 2,842,105.26 | (842,105.26) | −42.1% |
| Concrete block 6 inch | 1,000 | 2,500,000 | 1,818,684.21 | 681,315.79 | 27.3% |
| Basalt chippings | 300 t | 28,500,000 | 24,420,000.00 | 4,080,000.00 | 14.3% |
| **Total** | | **69,820,000** | **53,125,000.00** | **16,695,000.00** | **23.9%** |

Without landed costing the basalt would show a cost of 300 × 59,200 = 17,760,000 and a profit of 10,740,000, overstating
it by 6,660,000.

**Customer profitability:**

| Customer | Revenue | Cost of sales | Gross profit | Freight | Contribution |
|---|---:|---:|---:|---:|---:|
| Nile Towers Construction | 60,820,000 | 47,043,157.90 | 13,776,842.10 | 0 | 13,776,842.10 |
| Kampala Builders Ltd | 9,000,000 | 6,081,842.10 | 2,918,157.90 | 500,000 | 2,418,157.90 |

Per-order cost of sales is the tonnes at the running average (28,421.052632/t), so a single order can differ by
UGX 0.01 from a hand calculation at 28,421.05. The totals are exact.

**Branch:** KLA 9,000,000 revenue (SO-1, SO-3), QRY 60,820,000 (SO-2, SO-4).

## 8. Cash

| Day | Receipt / payment | UGX |
|---|---|---:|
| 1 | Owner's capital into the bank | 150,000,000 |
| 26 | Kampala Builders pays SO-1 and SO-3 | 9,000,000 |
| 27 | Nile Towers part-pays SO-2 | 30,000,000 |
| 27 | Rocktech Drilling, Equator Plant Hire, Equator Fuels | (20,000,000) |
| 27 | Lake Cement | (2,200,000) |
| 27 | Mombasa Basalt, $8,000 at 3,700 | (29,600,000) |
| 28 | Net pay | (1,200,000) |
| | **Bank at month end** | **136,000,000** |

Nile Towers still owes **30,820,000** (2,320,000 on SO-2 and 28,500,000 on SO-4). Open supplier bills: Nile Sand
350,000, Lakeside Haulage 3,500,000, Kampala Power 1,100,000, Crusher Spares 1,050,000, and in USD Swift Clearing
$1,200, Northern Corridor $1,500 and Kasese Loading $300.

## 9. The month's statements

**Profit and loss**

| | UGX |
|---|---:|
| Sales revenue (4100) | 69,820,000.00 |
| Cost of sales (5100) | (53,125,000.00) |
| **Gross profit** | **16,695,000.00** |
| Outbound freight (6460) | (500,000.00) |
| Production costs incurred (6310–6420, 5710, 5600) | (27,430,000.00) |
| Production costs absorbed into stock (5361, 5350) | 27,430,000.00 |
| **Net profit** | **16,195,000.00** |

**Closing stock (1400): 17,555,000**

| Item | Qty | UGX |
|---|---:|---:|
| Basalt chippings | 200 t × 81,400 | 16,280,000 |
| Cement | 30 bags × 35,000 | 1,050,000 |
| Sharp sand | 5 t × 35,000 | 175,000 |
| Water & admixture | 100 L × 500 | 50,000 |
| Aggregates, dust, blocks, ROM, WIP | 0 | 0 |

Proof: purchases 43,250,000 + production costs 27,430,000 = 70,680,000 = cost of sales 53,125,000 + closing stock
17,555,000.

**Balance sheet:**

| Assets | UGX |
|---|---:|
| Bank | 136,000,000 |
| Receivables | 30,820,000 |
| Stock | 17,555,000 |
| Plant at cost, less depreciation 1,080,000 | 298,920,000 |

| Funding | UGX |
|---|---:|
| Owner's capital | 450,000,000 |
| Payables | 17,100,000 |
| Profit for the month | 16,195,000 |

Assets and funding both total 483,295,000. Payables are UGX 6,000,000 plus USD 3,000 at 3,700 (11,100,000), so
17,100,000.

## 10. Where to look in the app

- **Quarry, extraction logs:** the day 2 log with 19,000/t.
- **Manufacturing, crushing runs:** the day 5 run with feed cost, conversion cost per stage, outputs and 28,421.05/t.
- **Manufacturing, production orders:** lot BLK-001 with its materials and four conversion lines.
- **Procurement, landed cost:** the day 9 voucher with the allocation per receipt line.
- **Sales, reports:** gross profit by product, customer, order and branch. **Sales analytics, profitability:** customer
  contribution after freight.
- **Cost management, profitability:** the month's run, already built.
- **Accounting:** trial balance, P&L and balance sheet for the month; the journal of every document above.

## 11. Questions for the general manager

- **Diesel up 15%:** +600,000 on the batch. That is 27,600,000 ÷ 950 = 29,052.63/t, so the batch margin falls from
  30.77% to 29.23%.
- **Minimum price for 20mm:** at least its cost of 28,421.05/t. Add delivery cost when it is delivered.
- **The most expensive stage:** extraction (19,000,000, 70% of the batch). Crushing takes 8,000,000, of which diesel is
  4,000,000.
