#!/usr/bin/env node
/**
 * POS checkout load smoke (P5 exit criterion: p95 checkout < 150 ms at 50 concurrent terminals).
 *
 * Node 20+ only, no dependencies (stands in for k6 where it is not installed). Each virtual terminal gets
 * its own cash register + open session (like a real till), then loops POST /pos/checkout with a fresh
 * Idempotency-Key for DURATION_S seconds. Prints count / errors / p50 / p95 / p99 and exits 1 when p95
 * exceeds P95_BUDGET_MS or any request failed.
 *
 *   API_BASE=http://localhost:3001 TERMINALS=50 DURATION_S=30 node tools/perf/checkout-smoke.mjs
 *
 * Run against a seeded throwaway database: it creates registers and sales in the DEMO org and closes the
 * sessions it opened at the end.
 */
import { randomUUID } from 'node:crypto';

const RAW = process.env.API_BASE ?? 'http://localhost:3001';
const BASE = /\/api\/v1$/.test(RAW) ? RAW : `${RAW.replace(/\/$/, '')}/api/v1`;
const TERMINALS = Number(process.env.TERMINALS ?? 50);
const DURATION_S = Number(process.env.DURATION_S ?? 30);
const WARMUP = Number(process.env.WARMUP ?? 5);
const BUDGET = Number(process.env.P95_BUDGET_MS ?? 150);
const ORG = process.env.POS_ORG_CODE ?? 'DEMO';
const EMAIL = process.env.POS_ADMIN_EMAIL ?? 'admin@demo.test';
const PASS = process.env.POS_ADMIN_PASS ?? 'Admin@123';

let token = '';

async function api(method, path, body, key) {
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
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status}: ${text.slice(0, 300)}`);
  return text ? JSON.parse(text) : null;
}

function pct(sorted, p) {
  if (!sorted.length) return NaN;
  return sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)];
}

const login = await api('POST', '/auth/login', { organizationCode: ORG, email: EMAIL, password: PASS });
token = login.accessToken ?? login.token;
const products = await api('GET', '/products?pageSize=200');
const product = (products.data ?? products).find((p) => p.code === 'P-COFFEE-S');
if (!product) throw new Error('seed product P-COFFEE-S not found');
const price = Number(product.salesPrice);

const tag = randomUUID().slice(0, 6).toUpperCase();
const tills = [];
for (let i = 0; i < TERMINALS; i++) {
  const reg = await api('POST', '/cash-registers', { code: `PERF-${tag}-${i}`, name: `Perf till ${i}` });
  const session = await api('POST', '/cash-sessions/open', { cashRegisterId: reg.id, openingFloat: 0 }, randomUUID());
  tills.push({ registerId: reg.id, sessionId: session.id });
}

const body = (sessionId) => ({
  lines: [{ productId: product.id, description: product.name, quantity: 1, unitPrice: price }],
  tenders: [{ method: 'cash', amount: price }],
  cashSessionId: sessionId,
});

// Warm-up (JIT, connection pool) — not measured.
await Promise.all(tills.map(async (t) => {
  for (let i = 0; i < WARMUP; i++) await api('POST', '/pos/checkout', body(t.sessionId), randomUUID());
}));

const latencies = [];
const errors = [];
const until = Date.now() + DURATION_S * 1000;
await Promise.all(tills.map(async (t) => {
  while (Date.now() < until) {
    const start = performance.now();
    try {
      await api('POST', '/pos/checkout', body(t.sessionId), randomUUID());
      latencies.push(performance.now() - start);
    } catch (e) {
      errors.push(String(e.message));
    }
  }
}));

// Close closes "my" open session (any register), so call it once per till. Best effort: a variance gate on a
// throwaway database is irrelevant here.
for (let i = 0; i < tills.length; i++) {
  try {
    await api('POST', '/cash-sessions/close', { closingCounted: 0, notes: 'perf smoke', varianceReason: 'perf smoke' },
      randomUUID());
  } catch { break; }
}

latencies.sort((a, b) => a - b);
const r = (n) => Math.round(n * 10) / 10;
const summary = {
  terminals: TERMINALS,
  durationS: DURATION_S,
  checkouts: latencies.length,
  errors: errors.length,
  throughputPerS: r(latencies.length / DURATION_S),
  p50: r(pct(latencies, 50)),
  p95: r(pct(latencies, 95)),
  p99: r(pct(latencies, 99)),
  max: r(latencies[latencies.length - 1] ?? NaN),
  budgetP95: BUDGET,
};
console.log(JSON.stringify(summary, null, 2));
if (errors.length) console.error('first errors:', errors.slice(0, 3));
process.exit(errors.length || !(summary.p95 < BUDGET) ? 1 : 0);
