// Floor-plan SSE fan-out: N tablets hold /pos/tables/stream open while waiters read tables.
// Needs the xk6-sse extension:  xk6 build --with github.com/phymbert/xk6-sse  →  ./k6 run tests/load/tables-sse.js
import sse from 'k6/x/sse';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { BASE, api, login } from './lib.js';

const events = new Counter('sse_events');
const connectTime = new Trend('sse_connect_ms', true);

export const options = {
  scenarios: {
    tablets: { executor: 'constant-vus', vus: Number(__ENV.TABLETS || 50), duration: __ENV.DURATION || '1m', exec: 'listen' },
    waiters: {
      executor: 'constant-arrival-rate', rate: 5, timeUnit: '1s', duration: __ENV.DURATION || '1m',
      preAllocatedVUs: 5, exec: 'touch',
    },
  },
  thresholds: { sse_connect_ms: ['p(95)<500'], checks: ['rate>0.99'] },
};

export function setup() {
  const token = login();
  const tables = api(token, 'GET', '/pos/tables', undefined, {}, { name: 'setup' }).json();
  return { token, tableIds: (Array.isArray(tables) ? tables : tables.rows || []).map((t) => t.id) };
}

export function listen(data) {
  const started = Date.now();
  const res = sse.open(`${BASE}/pos/tables/stream`, { headers: { Authorization: `Bearer ${data.token}` } }, (client) => {
    client.on('open', () => connectTime.add(Date.now() - started));
    client.on('event', () => events.add(1));
    client.on('error', () => client.close());
    setTimeout(() => client.close(), 20000);
  });
  check(res, { 'stream opened': (r) => r && r.status === 200 });
}

export function touch(data) {
  if (!data.tableIds.length) return;
  const id = data.tableIds[Math.floor(Math.random() * data.tableIds.length)];
  api(data.token, 'GET', `/pos/tables/${id}`, undefined, {}, { name: 'table' });
}
