# MENA group demo data (construction supplies & production)

API-driven seeder: every record goes through the real services (posting rules, AVCO costing, maker-checker,
idempotency), nothing is inserted into tables directly. Python 3 stdlib only.

| Company (org code) | Role | Branches / locations |
|---|---|---|
| MENA Granite Ltd (`MENA`, group parent) | HQ: slab sawing & polishing, block plant, distribution | NMS Namasuba HQ & Factory (HQ-MAIN, HQ-FACT), NTD Ntinda Showroom (HQ-NTD) |
| MENA Quarries Ltd (`MENA-QRY`) | Nakifuma quarry: blasting, crushing, raw granite blocks | NKF (QRY-YARD stockpiles, QRY-STORE fuel & spares) |
| MENA Granite Showroom Ltd (`MENA-SHOW`) | Kabalagala retail showroom | KBL (SHW-KBL) |

Demo-only sign-ins (local database), password `MenaDemo@2026`:
`admin@menagranite.test` (all three companies — company switcher), `admin@menaquarries.test`,
`admin@menashowroom.test`, and the checker (accountant) per company `accounts@menagranite.test`,
`accounts@menaquarries.test`, `accounts@menashowroom.test`.

## Run

```bash
# 1. empty database + API with the modules on (login throttle off while seeding)
docker exec ierp-db psql -U erp -d erp -c 'CREATE DATABASE ierp_mena'
cd backend && JDBC_DATABASE_URL='jdbc:postgresql://localhost:5434/ierp_mena?stringtype=unspecified' \
  LICENSE_ENFORCE=false THROTTLE_ENABLED=false PORT=3013 ENABLE_MANUFACTURING=true ENABLE_ORDERS=true \
  ENABLE_QUARRY=true ENABLE_FLEET=true ENABLE_ASSETS=true ENABLE_HR=true ./gradlew bootRun
# 2. tenants: POST /organizations/bootstrap for MENA, MENA-QRY, MENA-SHOW (UGX, Africa/Kampala), the twin
#    admin@menagranite.test + accounts@ users, then POST /intercompany/group + /group/members (see report)
# 3. data
cd tools/demo-data/mena && ERP_BASE=http://localhost:3013/api/v1 python run.py
python report.py        # reports/*.json
```

Phases (`python run.py <phase>`): `setup opening production quarry intercompany hq showroom quotations fixups
fixups2 crm variants`. Each step is recorded in `state.json`; a re-run skips finished steps (Idempotency-Key per step).
`fixups*` hold the corrections made after the first verification (kept so the audit trail tells the story).

Files: `data.py` masters and prices, `opening.py` 31 Oct 2026 position, `scenarios.py` the business story,
`flows.py` reusable document flows, `variants.py` product variants demo (ADR-S058, see below), `crm.py` CRM story (reps, teams, tags, leads, pipeline incl. won/lost,
planner activities, cases, quotations linked to opportunities), `report.py` the report pack. Results: `docs/reports/MENA_DEMO_DATA_REPORT.md`.

## Product variants demo (`python run.py variants`)

New products only (codes `VAR-*`), existing products untouched. Shared attributes: Stone colour (colour chips),
Thickness, Edge profile, Block size, Surface finish, Tile size, Vanity width.

| Company | Product | Variants | What to look at |
|---|---|---|---|
| MENA | `VAR-WTOP` Granite kitchen worktop | 16 = 4 colours x 2 thicknesses x 2 edges | price extras (Spotted Green +20k, Galaxy Black +40k, Viscount White +85k, 30 mm +45k, Bullnose +15k); fixed price 340,000 + fixed cost on *Viscount White, 30 mm, Bullnose*; bought on PO/bill `HQ-PO-VAR-STONE-01`, sold on the Canan Developers order |
| MENA | `VAR-BLOCK` Hollow concrete block (by size) | 3 (4/6/8 inch) | +500 / +1000 extras, fixed costs 1,400 / 1,750; opening count 5,000 / 3,000 / 1,500 |
| MENA | `VAR-TILE` Granite floor tile | 24 generated, 15 active | *Flamed* removed afterwards: its 8 variants archived (one still holds 40 m2); *Dragon Fire, Honed, 30x60 cm* archived by hand |
| MENA-SHOW | `VAR-VANITY` Granite vanity top | 9 = 3 colours x 3 widths | fixed price 820,000 on *Viscount White, 120 cm*; counted stock, sold to C-KARIM |

Open documents to edit: quotation + draft sales order for C-OPHIR (MENA) and C-MICH (MENA-SHOW), all lines with
variants. Each variant has a SKU (e.g. `WTOP-VW-30-B`) and a barcode `619…`.
