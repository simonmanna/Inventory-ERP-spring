// Shared helpers for the k6 load suite. Run against a seeded throwaway database, never production.
import http from 'k6/http';
import { check, fail } from 'k6';

export const BASE = (__ENV.API_BASE || 'http://localhost:3001').replace(/\/$/, '').replace(/\/api\/v1$/, '') + '/api/v1';
const ORG = __ENV.POS_ORG_CODE || 'DEMO';
const EMAIL = __ENV.POS_ADMIN_EMAIL || 'admin@demo.test';
const PASS = __ENV.POS_ADMIN_PASS || 'Admin@123';

export function uuid() {
  // RFC 4122 v4 from Math.random — good enough for idempotency keys / client ids in a load test.
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

export function login() {
  const res = http.post(`${BASE}/auth/login`, JSON.stringify({ organizationCode: ORG, email: EMAIL, password: PASS }), {
    headers: { 'Content-Type': 'application/json' },
  });
  if (res.status !== 200) fail(`login failed: ${res.status} ${res.body}`);
  return res.json('accessToken');
}

export function headers(token, extra = {}) {
  return { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, ...extra } };
}

export function api(token, method, path, body, extra = {}, tags = {}) {
  const params = headers(token, extra);
  params.tags = tags;
  const res = http.request(method, `${BASE}${path}`, body === undefined ? null : JSON.stringify(body), params);
  check(res, { [`${method} ${tags.name || path} 2xx`]: (r) => r.status >= 200 && r.status < 300 });
  return res;
}

/** One register + open session per virtual terminal (like a real till). */
export function openTill(token, n) {
  const code = `K6-${Date.now().toString(36)}-${n}`;
  const reg = api(token, 'POST', '/cash-registers', { code, name: `k6 till ${n}` }, {}, { name: 'setup' }).json();
  const session = api(token, 'POST', '/cash-sessions/open', { cashRegisterId: reg.id, openingFloat: 0 },
    { 'Idempotency-Key': uuid() }, { name: 'setup' }).json();
  return { registerId: reg.id, sessionId: session.id };
}

export function serviceProduct(token) {
  const code = `K6-SVC-${Date.now().toString(36)}`;
  return api(token, 'POST', '/products', {
    code, name: `k6 coffee ${code}`, productType: 'service', salesPrice: 100, costPrice: 0,
  }, {}, { name: 'setup' }).json('id');
}
