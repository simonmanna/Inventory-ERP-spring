#!/usr/bin/env node
/**
 * Disaster-recovery drill (release gate, docs/OPERATIONS.md §7a). Node 20+, no dependencies; works on Linux and on
 * Windows single-site installs.
 *
 *   1. encrypted full backup through the API (same path as the nightly job)
 *   2. restore it into a NEW scratch database through the API (never the live one)
 *   3. boot a second API container on the restored database
 *   4. the trial balance of the live and the restored system must be identical
 *   5. drop the scratch database, remove the drill container (also on failure)
 *
 *   ADMIN_EMAIL=... ADMIN_PASSWORD=... [ORG_CODE=DEMO] [API=http://127.0.0.1:3001/api/v1] node scripts/dr-drill.mjs
 *   COMPOSE="docker compose -f docker-compose.yml -f docker-compose.prod.yml" to target another compose setup.
 * Exit code 0 = drill passed.
 */
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const COMPOSE = (process.env.COMPOSE ?? 'docker compose').split(/\s+/).filter(Boolean);
const API = (process.env.API ?? 'http://127.0.0.1:3001/api/v1').replace(/\/$/, '');
const DRILL_PORT = Number(process.env.DRILL_PORT ?? 3901);
const ORG = process.env.ORG_CODE ?? 'DEMO';
const EMAIL = process.env.ADMIN_EMAIL;
const PASSWORD = process.env.ADMIN_PASSWORD;
if (!EMAIL || !PASSWORD) {
  console.error('set ADMIN_EMAIL and ADMIN_PASSWORD');
  process.exit(2);
}
const stamp = new Date().toISOString().replace(/\D/g, '').slice(0, 14);
const SCRATCH = `erp_drill_${stamp}`;
const CONTAINER = `ierp-dr-drill-${stamp}`;

const log = (m) => console.log(`[dr-drill ${new Date().toISOString().slice(11, 19)}] ${m}`);
const compose = (...args) =>
  execFileSync(COMPOSE[0], [...COMPOSE.slice(1), ...args], { cwd: root, encoding: 'utf8' }).trim();
const docker = (...args) => execFileSync('docker', args, { encoding: 'utf8' }).trim();

async function call(base, method, p, token, body) {
  const res = await fetch(`${base}${p}`, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json;
  try { json = JSON.parse(text); } catch { json = text; }
  if (!res.ok) throw new Error(`${method} ${p} -> ${res.status}: ${text.slice(0, 300)}`);
  return json;
}
const login = async (base) =>
  (await call(base, 'POST', '/auth/login', null, { organizationCode: ORG, email: EMAIL, password: PASSWORD })).accessToken;

/** Trial balance without volatile fields, keys sorted, for an exact comparison. */
function canonical(v) {
  if (Array.isArray(v)) return v.map(canonical);
  if (v && typeof v === 'object') {
    return Object.fromEntries(Object.keys(v).filter((k) => !['generatedAt', 'timestamp', 'durationMs'].includes(k))
      .sort().map((k) => [k, canonical(v[k])]));
  }
  return v;
}

function cleanup() {
  try { docker('rm', '-f', CONTAINER); } catch { /* not started */ }
  try { compose('exec', '-T', 'db', 'sh', '-c', `dropdb -U "$POSTGRES_USER" --if-exists ${SCRATCH}`); } catch { /* ignore */ }
}

let ok = false;
try {
  const token = await login(API);

  log('1/5 Full backup');
  const backup = await call(API, 'POST', '/admin/backups/full', token);
  if (backup.status !== 'success') throw new Error(`backup failed: ${backup.error}`);
  log(`    ${backup.target} (${backup.sizeBytes} bytes, encrypted=${backup.encrypted})`);

  log(`2/5 Restore into new database ${SCRATCH}`);
  const restore = await call(API, 'POST', '/admin/backups/restore', token,
    { scope: 'database', backupFile: path.posix.basename(backup.target), targetDatabase: SCRATCH });
  if (!restore.success) throw new Error(`restore failed: ${restore.message}`);
  log(`    checks ${JSON.stringify(restore.checks)}`);

  log('3/5 Boot an API on the restored database');
  const image = compose('images', '-q', 'api').split('\n')[0];
  const dbId = compose('ps', '-q', 'db');
  const network = docker('inspect', '-f', '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}', dbId).split(' ')[0];
  const user = compose('exec', '-T', 'db', 'printenv', 'POSTGRES_USER');
  const pass = compose('exec', '-T', 'db', 'printenv', 'POSTGRES_PASSWORD');
  docker('run', '-d', '--name', CONTAINER, '--network', network, '-p', `127.0.0.1:${DRILL_PORT}:3000`,
    '-e', `JDBC_DATABASE_URL=jdbc:postgresql://db:5432/${SCRATCH}?stringtype=unspecified`,
    '-e', `DB_USER=${user}`, '-e', `DB_PASSWORD=${pass}`, '-e', 'SPRING_PROFILES_ACTIVE=dev',
    '-e', 'BACKUP_CRON_ENABLED=false', '-e', 'APP_JOBS_ENABLED=false', image);
  const drill = `http://127.0.0.1:${DRILL_PORT}/api/v1`;
  let ready = false;
  for (let i = 0; i < 90 && !ready; i++) {
    try { await call(drill, 'GET', '/health/live'); ready = true; } catch { await new Promise((r) => setTimeout(r, 2000)); }
  }
  if (!ready) throw new Error(`restored API never became ready:\n${docker('logs', '--tail', '80', CONTAINER)}`);

  log('4/5 Compare trial balances');
  const drillToken = await login(drill);
  const live = canonical(await call(API, 'GET', '/reports/accounting/trial-balance', token));
  const restored = canonical(await call(drill, 'GET', '/reports/accounting/trial-balance', drillToken));
  if (JSON.stringify(live) !== JSON.stringify(restored)) {
    throw new Error('trial balance differs between the live and the restored system (writes during the drill also show here)');
  }
  const rows = Array.isArray(live) ? live.length : (live.rows ?? live.accounts ?? []).length;
  log(`    trial balance identical (${rows} rows)`);
  ok = true;
} catch (e) {
  log(`FAILED: ${e.message}`);
} finally {
  log('5/5 Cleanup');
  cleanup();
}
log(ok ? 'DR drill PASSED' : 'DR drill FAILED');
process.exit(ok ? 0 : 1);
