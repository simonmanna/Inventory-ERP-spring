# Load tests (k6)

Run against a **seeded throwaway database** (`docker compose up -d`, DEMO seed). The scripts create registers,
sessions, products and sales in the DEMO organization.

| Script | What | Bar |
|---|---|---|
| `checkout.js` | 50 tills × `POST /pos/checkout` | p95 < 150 ms, < 0.1 % errors |
| `tab-edits.js` | 20 waiters × create order → 5 auto-saves (`PUT /pos/orders/:id/items`) → cancel | p95 < 250 ms |
| `reports.js` | Z-report + trial balance at 10 req/s | p95 < 800 ms / 1.5 s |
| `tables-sse.js` | 50 tablets on `/pos/tables/stream` (needs the xk6-sse extension) | connect p95 < 500 ms |

```bash
k6 run -e API_BASE=http://localhost:3001 -e TERMINALS=50 tests/load/checkout.js
```

No k6 on the box? `node tools/perf/checkout-smoke.mjs` measures the same checkout path with plain Node.

Measured results and the current gap to the bar are recorded in `docs/OPERATIONS.md` (Performance).
