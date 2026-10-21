// POS checkout under concurrent terminals (bar: p95 < 150 ms at 50 terminals on production hardware).
//   k6 run -e API_BASE=http://localhost:3001 -e TERMINALS=50 tests/load/checkout.js
import { api, login, openTill, serviceProduct, uuid } from './lib.js';

const TERMINALS = Number(__ENV.TERMINALS || 50);

export const options = {
  scenarios: {
    tills: { executor: 'constant-vus', vus: TERMINALS, duration: __ENV.DURATION || '2m' },
  },
  thresholds: {
    'http_req_duration{name:checkout}': [`p(95)<${__ENV.P95_BUDGET_MS || 150}`],
    'http_req_failed{name:checkout}': ['rate<0.001'],
  },
};

export function setup() {
  const token = login();
  const productId = serviceProduct(token);
  const tills = [];
  for (let i = 0; i < TERMINALS; i++) tills.push(openTill(token, i));
  return { token, productId, tills };
}

export default function (data) {
  const till = data.tills[(__VU - 1) % data.tills.length];
  const qty = 1 + (__ITER % 3);
  api(data.token, 'POST', '/pos/checkout', {
    cashSessionId: till.sessionId,
    lines: [{ productId: data.productId, description: 'Coffee', quantity: qty, unitPrice: 100 }],
    tenders: [{ method: 'cash', amount: qty * 100 }],
  }, { 'Idempotency-Key': uuid() }, { name: 'checkout' });
}
