# Loss-prevention pilot demo (tenant DEMO)

A replayed 14-day pilot at **Kampala Road Cafe** (branch `KRD`): normal trade plus planted incidents, so that every
loss-prevention rule except the retail ones (V-R1, V-R2 ship off) and V-S1 shows at least one case, and the Insights
screens have figures.

**The data is synthetic.** It shows how the module reports and how a case is worked. It says nothing about detection
accuracy: no camera, model or meter was involved. Cameras and meters are a simulated edge agent that sends the
observations a real one would.

```bash
# back up the database first
cd tools/demo-data/loss-prevention
LP_DEMO_PASSWORD='<admin password>' python3 run.py --confirm DEMO          # all steps
LP_DEMO_PASSWORD='<admin password>' python3 run.py --confirm DEMO report   # one step
```

| Variable | Default |
|---|---|
| `ERP_BASE` | `http://127.0.0.1:3001/api/v1` (only localhost is accepted) |
| `LP_DEMO_ORG` / `LP_DEMO_EMAIL` | `DEMO` / `admin@demo.test` |
| `LP_DEMO_PASSWORD` | `Admin@123` (local seed default) |
| `LP_DEMO_DB` | `erp` (database for the `backdate` step) |
| `LP_DEMO_STATE` | `state.<ORG>.json` (ignored by git; holds the simulated agent's client secret, mode 600) |

## Steps

| Step | What it does |
|---|---|
| `setup` | Tills `KRD-T1` (counter, watched) and `KRD-T2` (bar); cashiers Grace Nakato, Peter Okello, Aisha Namutebi (plus Sarah Cashier); products `LPD-*` (three house spirits in ml, three bar drinks); C03 = 50,000 and C08 = 10,000 UGX; edge agent "KRD edge box (demo)", enrolled; 6 cameras, 12 zones; 3 pour spouts |
| `backdate` | Sets the demo agent, cameras, zones and meters' `createdAt` to the day before day 1. A zone or meter only judges time after it was created, so a replayed pilot needs this. The only step that is SQL (psql as postgres), limited to the ids in the state file |
| `events` | ~6,000 POS/kitchen/stock events (source `demo-pilot`, sent by the admin) and ~13,500 camera/meter events (source `krd-edge`, sent by the agent) for the 14 days before the first run |
| `reconcile` | Agent heartbeat (watermark = now), replay of the six engines over the 14 days, insights recompute |
| `report` | Cases per rule, and the incidents planted |

Re-running is safe: objects are skipped by state, and event ids derive from the source key, so a second `events`
run is all duplicates. The 14 days are fixed at the first run (`start` in the state file).

## The story (days counted from day 0)

- **Peter Okello**: cash short in 6 counter shifts (C09), 18,000 short once (C08), six refunds in one shift (C04),
  one with no customer at the till (V-C2), the drawer seen open with nothing rung up twice (V-C1), seven "No sale"
  openings in one shift (C11).
- **Grace Nakato**: a refund of 86,000 (C03); seven overrides in a shift, all approved by Mike Manager (C05, C06).
- **Aisha Namutebi**: day 11, discounts on more than half her sales (C07).
- **Bar**: day 3 heavy pours on every spout (B05, B04, B01); day 9 nine gin pours after closing, nothing sold, three
  seen by the camera (V-B1, B04) and the gin count 270 ml short (B01); day 6 two bottles tipped with no meter reading
  (V-B2); vodka "spillage" three counts running (B02).
- **Kitchen and service**: voids after firing (C01), cancellations after cooking (C02), unpaid food (K01), plates sent
  back (K02, C10), a ticket served with no plate seen (V-K2), surplus plates out of the pass (V-K1), plates to empty
  table 4 (V-K3), extra takeaway hand-overs (V-F1), coffees prepared but not handed over (V-F2), a customer served at
  the till with no sale (V-C3).
- A 40-minute front-door camera gap on day 7.

## Modelling choices forced by the engines (worth fixing in the module)

- Service vision loads the previous day's kitchen tickets but not that day's sales or cancellations, so a table used
  the day before looks occupied all day and V-K3 is silent for it. The demo keeps table 4 free on days 10 and 11.
- V-F1 also runs on a `cafe_counter` linked to a station and compares it with all branch sales; dine-in guests get
  their coffee before they pay, so it fires on normal trade, while V-F2 on the same zone needs those hand-overs. The
  demo sends dine-in drinks from a separate "Table drinks" station that does not cross the pick-up line.
- V-B1 counts any sale with recipe data as a drink sale (coffee included), so in a cafe it can only fire when nothing
  at all is being sold. The planted V-B1 pours are after closing.
