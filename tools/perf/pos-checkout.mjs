#!/usr/bin/env node
// POS checkout latency against a running API (PR-1 part 2): same sale as PosCheckoutPerfIT (3 service lines, cash).
//
//   API_URL=http://localhost:3001 node tools/perf/pos-checkout.mjs [--runs 200] [--warmup 30] [--budget 150]
//
// Sequential requests from one client, like a single till. Exit code 1 when p95 exceeds --budget (ms).
import { randomUUID } from 'node:crypto';

const API = `${(process.env.API_URL ?? 'http://localhost:3001').replace(/\/$/, '')}/api/v1`;
const arg = (k, d) => (process.argv.includes(k) ? Number(process.argv[process.argv.indexOf(k) + 1]) : d);
const RUNS = arg('--runs', 200);
const WARMUP = arg('--warmup', 30);
const BUDGET = arg('--budget', 150);
let token = '';

async function call(method, path, body, money = false) {
  const res = await fetch(API + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(money ? { 'Idempotency-Key': randomUUID() } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status}: ${text.slice(0, 300)}`);
  return text ? JSON.parse(text) : null;
}

const login = await call('POST', '/auth/login', { organizationCode: process.env.ORG_CODE ?? 'DEMO',
  email: process.env.EMAIL ?? 'admin@demo.test', password: process.env.PASSWORD ?? 'Admin@123' });
token = login.accessToken;
const suffix = randomUUID().slice(0, 6).toUpperCase();
const products = [];
for (let i = 0; i < 3; i++) {
  products.push((await call('POST', '/products', { code: `PERF-${i}-${suffix}`, name: `Perf item ${i} ${suffix}`,
    productType: 'service', salesPrice: 100, costPrice: 0 })).id);
}
const register = await call('POST', '/cash-registers', { code: `PERF-${suffix}`, name: `Perf till ${suffix}` });
const session = await call('POST', '/cash-sessions/open', { cashRegisterId: register.id, openingFloat: 0 }, true);
const sale = () => ({ cashSessionId: session.id,
  lines: products.map((p) => ({ productId: p, description: 'Perf', quantity: 1, unitPrice: 100 })),
  tenders: [{ method: 'cash', amount: 100 * products.length }] });

for (let i = 0; i < WARMUP; i++) await call('POST', '/pos/checkout', sale(), true);
const ms = [];
for (let i = 0; i < RUNS; i++) {
  const t0 = performance.now();
  await call('POST', '/pos/checkout', sale(), true);
  ms.push(performance.now() - t0);
}
ms.sort((a, b) => a - b);
const pct = (p) => ms[Math.ceil(ms.length * p) - 1];
const r = (n) => Math.round(n);
const out = { api: API, runs: RUNS, lines: products.length, p50: r(pct(0.5)), p95: r(pct(0.95)), p99: r(pct(0.99)),
  max: r(ms[ms.length - 1]), budgetP95: BUDGET };
console.log('POS_CHECKOUT_HTTP', JSON.stringify(out));
process.exitCode = out.p95 > BUDGET ? 1 : 0;
