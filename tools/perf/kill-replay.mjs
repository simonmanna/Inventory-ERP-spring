#!/usr/bin/env node
/**
 * Offline-queue replay check (P5 exit criterion): fire a checkout, hard-kill the API while it is in flight,
 * restart it, replay the same request with the same Idempotency-Key, and assert exactly one sale exists for
 * that key. Run against a throwaway database.
 *
 *   API_BASE=http://localhost:3101 KILL_CMD="taskkill //F //PID 1234" START_CMD="java -jar app.jar" \
 *     node tools/perf/kill-replay.mjs
 *
 * KILL_CMD / START_CMD are shell commands (START_CMD runs detached). DB_CHECK_CMD (optional) is a shell command
 * that prints the number of Order rows whose clientOperationKey equals $KEY.
 */
import { randomUUID } from 'node:crypto';
import { execSync, spawn } from 'node:child_process';

const RAW = process.env.API_BASE ?? 'http://localhost:3101';
const BASE = /\/api\/v1$/.test(RAW) ? RAW : `${RAW.replace(/\/$/, '')}/api/v1`;
const KILL_DELAY_MS = Number(process.env.KILL_DELAY_MS ?? 60);
// Shell for the *_CMD hooks (e.g. SCRIPT_SHELL=bash under Git Bash on Windows); default: the platform shell.
const SHELL = process.env.SCRIPT_SHELL || true;

async function api(method, path, body, token, key) {
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
  return { status: res.status, body: text ? JSON.parse(text) : null };
}

async function waitUp(ms) {
  const until = Date.now() + ms;
  while (Date.now() < until) {
    try {
      const r = await fetch(`${BASE}/health`);
      if (r.status < 500) return;
    } catch { /* not up yet */ }
    await new Promise((r) => setTimeout(r, 1000));
  }
  throw new Error('API did not come back');
}

const login = await api('POST', '/auth/login', { organizationCode: 'DEMO', email: 'admin@demo.test', password: 'Admin@123' });
const token = login.body.accessToken;
const products = await api('GET', '/products?pageSize=200', undefined, token);
const product = (products.body.data ?? products.body).find((p) => p.code === 'P-COFFEE-S');
const reg = await api('POST', '/cash-registers', { code: `KR-${randomUUID().slice(0, 6)}`, name: 'Kill replay till' }, token);
const session = await api('POST', '/cash-sessions/open', { cashRegisterId: reg.body.id, openingFloat: 0 }, token, randomUUID());
const key = randomUUID();
const sale = {
  lines: [{ productId: product.id, description: product.name, quantity: 1, unitPrice: Number(product.salesPrice) }],
  tenders: [{ method: 'cash', amount: Number(product.salesPrice) }],
  cashSessionId: session.body.id,
};

// 1) In-flight sale, then hard kill.
const inflight = api('POST', '/pos/checkout', sale, token, key).then(
  (r) => `answered ${r.status}`,
  (e) => `connection lost (${e.cause?.code ?? e.message})`,
);
await new Promise((r) => setTimeout(r, KILL_DELAY_MS));
execSync(process.env.KILL_CMD, { stdio: 'inherit', shell: SHELL });
console.log('first attempt:', await inflight);

// 2) Restart and replay the queued request with the same key (twice, like an eager offline queue).
spawn(process.env.START_CMD, { shell: SHELL, detached: true, stdio: 'ignore' }).unref();
await waitUp(180_000);
const relog = await api('POST', '/auth/login', { organizationCode: 'DEMO', email: 'admin@demo.test', password: 'Admin@123' });
const replay1 = await api('POST', '/pos/checkout', sale, relog.body.accessToken, key);
const replay2 = await api('POST', '/pos/checkout', sale, relog.body.accessToken, key);
console.log('replay 1:', replay1.status, replay1.body?.invoiceNumber ?? replay1.body?.message);
console.log('replay 2:', replay2.status, replay2.body?.invoiceNumber ?? replay2.body?.message);

let count = null;
if (process.env.DB_CHECK_CMD) {
  const out = execSync(process.env.DB_CHECK_CMD, { env: { ...process.env, KEY: key }, shell: SHELL });
  count = Number(out.toString().trim());
  console.log('orders for key:', count);
}
const same = replay1.body?.invoiceId && replay1.body.invoiceId === replay2.body?.invoiceId;
// 200 for a normal replay; 201 when the sale committed but the response was lost (the record is
// business_completed, statusCode 201 — same as Nest's business-outcome.ts).
const ok2xx = (s) => s === 200 || s === 201;
const ok = ok2xx(replay1.status) && replay2.status === replay1.status && same && (count === null || count === 1);
console.log(ok ? 'PASS: exactly one sale' : 'FAIL');
process.exit(ok ? 0 : 1);
