# Granite demo (tenant DEMO)

Granite and marble products with photos, sold by size and by variant, processing services, sample sales documents,
and a staged-processing flow that is sold after every stage. Everything goes through the running API (no SQL), so
stock, lots, cost and GL postings are real.

```bash
# back up the database first
cd tools/demo-data/granite
GRANITE_PASSWORD='<admin password>' python3 run.py --confirm DEMO              # all steps
GRANITE_PASSWORD='<admin password>' python3 run.py --confirm DEMO processing   # one step
```

| Variable | Default |
|---|---|
| `ERP_BASE` | `http://127.0.0.1:3001/api/v1` (only localhost is accepted; run it on the server) |
| `GRANITE_ORG` | `DEMO` |
| `GRANITE_EMAIL` | `admin@demo.test` |
| `GRANITE_PASSWORD` | `Admin@123` (the local seed default) |

Re-running is safe. Products, services, processing steps and the route are skipped when their code exists. Documents,
jobs and sales are recorded in `state.<ORG>.json` and not repeated; delete that file only together with the data.

The live server's DEMO tenant was seeded on 2026-11-19 before this seeder existed, with the same data (processing lot
`SG-2611-01`) and no state file. Running `documents` or `processing` there would add a second set; `catalogue` and
`services` only skip.

## Steps

**`catalogue`**: 14 products from `images/`, in Granite Slabs, Floor Tiles, Countertops, Stairs & Skirting and
Aggregates. They are sold per m², per running metre or per m³ (pieces × length × width × height), with variants by
thickness, finish, tile size, edge, step length, colour or grade, each with its own price extra and default size.
Aggregates are also sold per tonne and per truck, and steps and skirting per piece. It also turns on
**Product images on quotations**.

**`services`**: 15 service products in Stone Processing Services, for example cutting (per cut, m² or slab), edge
profiling (per metre), polishing and sealing (per m²), CNC and repair (per hour), drilling (per hole), engraving (per
letter), sink cut-outs, waterjet, chamfering and countertop fabrication. Variants by material, thickness, profile,
finish and so on carry price extras.

**`documents`**: these documents go to Acme Retail (or the first customer):
- a granite quotation with sections, sizes and images
- a kitchen worktop quotation with stone plus fabrication services
- a sales order for re-polishing and engraving
- a draft invoice for cutting and finishing
- a draft branch cash sale

**`processing`**: Steel Grey granite, from raw slab to sealed countertop (Manufacturing → Processing, ADR-S060).

| Stage | Job: in → out, added cost | Cost per m² | Sold | Price per m² | Margin |
|---|---|---|---|---|---|
| 0 Raw slab | bought 45 m² at 90,000 | 90,000 | 4.5 m² | 150,000 | 40.0% |
| 1 Cut to size | 40 → 34 m² + 4 m² offcuts, +720,000 | 122,353 | 8 m² | 190,000 | 35.6% |
| 2 Edged | 22 → 21.56 m², +550,000 | 150,360 | 5 m² | 240,000 | 37.4% |
| 3 Polished | 14 → 13.86 m², +392,000 | 180,162 | 4 m² | 300,000 | 39.9% |
| 4 Sealed | 8 → 8 m², +104,000 | 193,162 | 3.6 m² | 360,000 | 46.3% |

Cost per m² after a step is (m² in × cost per m² + step cost − offcut value) ÷ good m² out. Loss up to the expected
yield raises the cost of the good output. Offcuts (2 m² sold at 40,000) carry their resale value.

The processing invoices are **posted**, so they reach receivables, cost of sales and the GL. Dates are 17 to 6 days
before the run. The customer handout of this flow is `products-images/granite-stage-costing.pdf`.

Where to look: **Manufacturing → Processing** (steps, jobs, route, stock by stage, lot trace, dashboard, stage
margin), **Sales → Quotations / Orders / Cash sales**, **Invoices**, and **Sales → Settings → Quotation layout**.
