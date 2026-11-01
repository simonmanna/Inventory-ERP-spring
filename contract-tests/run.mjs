#!/usr/bin/env node
// Parity harness: replays scenario steps against the NestJS API and the Spring API and diffs the
// normalized responses. Usage:
//   NEST_URL=http://localhost:3000 SPRING_URL=http://localhost:3001 node contract-tests/run.mjs --module kernel
// Both APIs must run on databases migrated + seeded identically (see README.md).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const moduleArg = args[args.indexOf('--module') + 1];
const targets = { nest: process.env.NEST_URL ?? 'http://localhost:3000', spring: process.env.SPRING_URL ?? 'http://localhost:3001' };
const allowed = fs.existsSync(path.join(here, 'allowed-diffs.json')) ? JSON.parse(fs.readFileSync(path.join(here, 'allowed-diffs.json'), 'utf8')) : [];

const files = fs.readdirSync(path.join(here, 'scenarios')).filter((f) => f.endsWith('.json') && (!moduleArg || f.startsWith(moduleArg)));

// Values that legitimately differ between two runs are replaced by stable placeholders.
const VOLATILE_KEYS = new Set(['id', 'requestId', 'createdAt', 'updatedAt', 'timestamp', 'accessToken', 'refreshToken', 'posToken', 'mfaToken', 'durationMs', 'latencyMs']);
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const UUID_IN = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi;
const ISO =/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$/;
function normalize(v, key) {
  if (Array.isArray(v)) return v.map((x) => normalize(x));
  if (v && typeof v === 'object') {
    // Maps keyed by row id (meta.categoryCounts): ids differ per database, so compare the entries as a sorted list.
    const plain = Object.keys(v).filter((k) => !UUID.test(k)).sort().map((k) => [k, normalize(v[k], k)]);
    const byId = Object.keys(v).filter((k) => UUID.test(k)).map((k) => normalize(v[k], 'value'))
      .sort((a, b) => JSON.stringify(a).localeCompare(JSON.stringify(b))).map((x, i) => [`<uuid-key-${i}>`, x]);
    return Object.fromEntries([...plain, ...byId]);
  }
  if (typeof v === 'string') {
    if (VOLATILE_KEYS.has(key) || UUID.test(v)) return `<${key ?? 'value'}>`;
    if (ISO.test(v)) return '<timestamp>';
    if (/^ey[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(v)) return '<jwt>';
    // Ids embedded in larger strings (DRAFT-<uuid>, postingKey "cash-flow:deposit:<uuid>", "/journal-entries/<uuid>").
    return v.replace(UUID_IN, '<uuid>');
  }
  return v;
}

function diff(a, b, at = '$', out = []) {
  if (JSON.stringify(a) === JSON.stringify(b)) return out;
  if (a && b && typeof a === 'object' && typeof b === 'object' && Array.isArray(a) === Array.isArray(b)) {
    for (const k of new Set([...Object.keys(a), ...Object.keys(b)])) diff(a[k], b[k], `${at}.${k}`, out);
  } else out.push({ path: at, nest: a, spring: b });
  return out;
}

// Allowed-diff paths are prefixes and `scenario` is a file-name prefix; a `*` segment matches any one key or array index ($.body.*.reasonCode).
const escapeRe = (t) => t.replace(/[.+?^${}()|[\]\\]/g, '\\$&');
const pathMatches = (allowedPath, p) =>
  new RegExp('^' + allowedPath.split('*').map(escapeRe).join('[^.]+') + '(\\.|$)').test(p);
const isAllowed = (d, file, stepName) => allowed.some((a) =>
  (!a.step || a.step === stepName) && (!a.scenario || file.startsWith(a.scenario)) && pathMatches(a.path, d.path));

const pick = (obj, p) => p.replace(/^\$\.?/, '').split('.').filter(Boolean).reduce((o, k) => o?.[k], obj);
const subst = (v, vars) => JSON.parse(JSON.stringify(v ?? null).replace(/\{\{(\w+)\}\}/g, (_, k) => vars[k] ?? ''));

let failures = 0;
for (const file of files) {
  const scenario = JSON.parse(fs.readFileSync(path.join(here, 'scenarios', file), 'utf8'));
  const vars = { nest: { ...(scenario.vars ?? {}) }, spring: { ...(scenario.vars ?? {}) } };
  console.log(`\n# ${file}: ${scenario.name ?? ''}`);
  for (const step of scenario.steps) {
    const results = {};
    for (const [side, base] of Object.entries(targets)) {
      const headers = { 'content-type': 'application/json', ...subst(step.headers ?? {}, vars[side]) };
      if (vars[side].token) headers.authorization ??= `Bearer ${vars[side].token}`;
      const res = await fetch(base + subst(step.path, vars[side]), {
        method: step.method ?? 'GET',
        headers,
        body: step.body ? JSON.stringify(subst(step.body, vars[side])) : undefined,
      });
      const text = await res.text();
      let body;
      try { body = text ? JSON.parse(text) : null; } catch { body = text; }
      for (const [name, jsonPath] of Object.entries(step.capture ?? {})) vars[side][name] = pick(body, jsonPath);
      results[side] = { status: res.status, totalCount: res.headers.get('x-total-count'), body: normalize(body) };
    }
    const diffs = diff(results.nest, results.spring).filter((d) => !isAllowed(d, file, step.name));
    // A capture that resolves to nothing (failed login, 429, error body) silently turns every later step into an
    // unauthenticated or `//` request on both sides, which then "match". Treat it as a failure of this step.
    for (const name of Object.keys(step.capture ?? {})) {
      const state = (side) => (vars[side][name] == null ? `missing (HTTP ${results[side].status})` : 'ok');
      if (vars.nest[name] == null || vars.spring[name] == null) diffs.push({ path: `capture:${name}`, nest: state('nest'), spring: state('spring') });
    }
    if (diffs.length) {
      failures++;
      console.log(`  ✗ ${step.name}`);
      for (const d of diffs.slice(0, 20)) console.log(`      ${d.path}: nest=${JSON.stringify(d.nest)} spring=${JSON.stringify(d.spring)}`);
    } else console.log(`  ✓ ${step.name}`);
  }
}
console.log(failures ? `\n${failures} step(s) differ` : '\nall steps match');
process.exit(failures ? 1 : 0);
