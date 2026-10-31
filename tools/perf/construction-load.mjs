#!/usr/bin/env node
/**
 * Construction-materials load test (Phase 6: p95 < 300 ms on list and detail routes with a 1M-row seed).
 *
 * Node 20+ only, no dependencies. VUS virtual users each loop over the scenarios of one group for DURATION_S seconds,
 * after WARMUP requests per scenario that are not counted. Prints count / errors / p50 / p95 / p99 per scenario and exits
 * 1 when a p95 is over P95_BUDGET_MS or a request failed.
 *
 *   API_BASE=http://localhost:3001 node tools/perf/construction-load.mjs            # every read group
 *   GROUPS=dispatch,stock VUS=10 DURATION_S=60 node tools/perf/construction-load.mjs
 *   WRITES=1 PERF_STATION_ID=... PERF_PLANT_ID=... node tools/perf/construction-load.mjs writes
 *
 * Start the API with THROTTLE_ENABLED=false: one login loops far past the 500 requests a minute per route of a user, and
 * a 429 is not a latency result.
 *
 * Groups: dispatch, weighbridge, production, stock, reports (read only); writes (needs WRITES=1, creates weighbridge
 * tickets and draft crushing runs: run it on a throwaway database). A 403 means the module or the login lacks the
 * permission, a 404 a disabled module: fix the setup rather than the number.
 */
import { randomUUID } from 'node:crypto';

const RAW = process.env.API_BASE ?? 'http://localhost:3001';
const BASE = /\/api\/v1$/.test(RAW) ? RAW : `${RAW.replace(/\/$/, '')}/api/v1`;
const VUS = Number(process.env.VUS ?? 20);
const DURATION_S = Number(process.env.DURATION_S ?? 30);
const WARMUP = Number(process.env.WARMUP ?? 3);
const BUDGET = Number(process.env.P95_BUDGET_MS ?? 300);
const ORG = process.env.POS_ORG_CODE ?? 'DEMO';
const EMAIL = process.env.POS_ADMIN_EMAIL ?? 'admin@demo.test';
const PASS = process.env.POS_ADMIN_PASS ?? 'Admin@123';
const WRITES = process.env.WRITES === '1';
const STATION = process.env.PERF_STATION_ID;
const PLANT = process.env.PERF_PLANT_ID;

const day = (offset) => new Date(Date.now() + offset * 86_400_000).toISOString().slice(0, 10);
const FROM = process.env.PERF_FROM ?? day(-90);
const TO = process.env.PERF_TO ?? day(0);
const RANGE = `from=${FROM}&to=${TO}`;

let token = '';

async function call(method, path, body, key) {
  const started = performance.now();
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(key ? { 'Idempotency-Key': key } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  return { ok: res.ok, status: res.status, ms: performance.now() - started, body: text };
}

const get = (name, path) => ({ name, run: () => call('GET', path) });

const READS = {
  dispatch: [
    get('dispatch board', `/fleet/dispatch-board?from=${day(-1)}&to=${day(7)}`),
    get('delivery orders', '/fleet/delivery-orders?pageSize=25'),
    get('dispatch sources', '/fleet/dispatch/sources'),
  ],
  weighbridge: [
    get('ticket queue', '/fleet/weighbridge/tickets?queue=live&pageSize=25'),
    get('ticket history', `/fleet/weighbridge/tickets?queue=all&${RANGE}&pageSize=25`),
    get('ticket queue counts', '/fleet/weighbridge/tickets/summary'),
  ],
  production: [
    get('crushing runs', '/manufacturing/crushing/runs?pageSize=25'),
    get('crushing yield', `/manufacturing/crushing/reports/yield?${RANGE}`),
    get('crushing cost per tonne', `/manufacturing/crushing/reports/cost-per-tonne?${RANGE}`),
    get('planning gantt', `/manufacturing/planning/gantt?from=${day(-1)}&to=${day(14)}`),
    get('costing summary', `/manufacturing/costing/reports/summary?${RANGE}`),
  ],
  stock: [
    get('stock items', '/inventory/items?pageSize=25'),
    get('stock levels', '/inventory/product-stock-levels?pageSize=25'),
    get('stock ledger', '/inventory/ledger?pageSize=25'),
    get('stock valuation', '/inventory/reports/valuation'),
    get('replenishment alerts', '/inventory/replenishment/alerts?pageSize=25'),
  ],
  reports: [
    get('kpi sales', `/analytics/sales?${RANGE}`),
    get('kpi inventory', `/analytics/inventory?${RANGE}`),
    get('kpi fleet', `/analytics/fleet?${RANGE}`),
    get('kpi finance', `/analytics/finance?${RANGE}`),
    get('kpi branches', `/analytics/branches?${RANGE}`),
    get('exception report', `/alerts/exception-report?${RANGE}`),
  ],
};

function writes() {
  if (!WRITES) throw new Error('the writes group changes data: set WRITES=1 and use a throwaway database');
  if (!STATION || !PLANT) throw new Error('the writes group needs PERF_STATION_ID and PERF_PLANT_ID');
  return [
    {
      name: 'weighbridge ticket + first weigh',
      run: async () => {
        const plate = `PERF ${randomUUID().slice(0, 6).toUpperCase()}`;
        const created = await call('POST', '/fleet/weighbridge/tickets', {
          stationId: STATION, subjectType: 'other', registrationPlate: plate, notes: 'load test',
        });
        if (!created.ok) return created;
        const { id } = JSON.parse(created.body);
        const weighed = await call('POST', `/fleet/weighbridge/tickets/${id}/first-weigh`, {
          manualKg: 12000, manualReason: 'load test',
        });
        return { ...weighed, ms: created.ms + weighed.ms };
      },
    },
    {
      name: 'crushing run draft',
      run: () => call('POST', '/manufacturing/crushing/runs', {
        plantId: PLANT, runDate: day(0), feedTonnes: 100, shift: 'day', outputs: [], meters: [], costs: [],
      }, randomUUID()),
    },
  ];
}

function pct(sorted, p) {
  if (!sorted.length) return NaN;
  return sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)];
}

async function runGroup(group, scenarios) {
  const stats = new Map(scenarios.map((s) => [s.name, { ms: [], errors: 0, last: '' }]));
  for (const s of scenarios) {
    for (let i = 0; i < WARMUP; i++) await s.run().catch(() => null);
  }
  const deadline = Date.now() + DURATION_S * 1000;
  await Promise.all(Array.from({ length: VUS }, async (_, vu) => {
    let i = vu;
    while (Date.now() < deadline) {
      const s = scenarios[i++ % scenarios.length];
      const stat = stats.get(s.name);
      try {
        const r = await s.run();
        stat.ms.push(r.ms);
        if (!r.ok) {
          stat.errors++;
          stat.last = `${r.status}: ${r.body.slice(0, 120)}`;
        }
      } catch (e) {
        stat.errors++;
        stat.last = String(e);
      }
    }
  }));
  let failed = false;
  console.log(`\n${group} (${VUS} users, ${DURATION_S} s, budget ${BUDGET} ms)`);
  for (const [name, stat] of stats) {
    const sorted = stat.ms.sort((a, b) => a - b);
    const p95 = pct(sorted, 95);
    const over = !(p95 <= BUDGET) || stat.errors > 0;
    failed = failed || over;
    console.log(`  ${over ? 'FAIL' : 'ok  '} ${name.padEnd(34)} n=${String(sorted.length).padStart(6)} `
      + `err=${String(stat.errors).padStart(4)} p50=${pct(sorted, 50).toFixed(0).padStart(5)} `
      + `p95=${p95.toFixed(0).padStart(5)} p99=${pct(sorted, 99).toFixed(0).padStart(5)} ms`);
    if (stat.last) console.log(`       last error ${stat.last}`);
  }
  return failed;
}

const login = await call('POST', '/auth/login', { organizationCode: ORG, email: EMAIL, password: PASS });
if (!login.ok) throw new Error(`login failed: ${login.status} ${login.body.slice(0, 200)}`);
const session = JSON.parse(login.body);
token = session.accessToken ?? session.token;

const asked = [...process.argv.slice(2), ...(process.env.GROUPS ?? '').split(',')].map((g) => g.trim()).filter(Boolean);
const names = asked.length ? asked : Object.keys(READS);
let failed = false;
for (const name of names) {
  const scenarios = name === 'writes' ? writes() : READS[name];
  if (!scenarios) throw new Error(`unknown group ${name}; use ${[...Object.keys(READS), 'writes'].join(', ')}`);
  failed = (await runGroup(name, scenarios)) || failed;
}
process.exit(failed ? 1 : 0);
