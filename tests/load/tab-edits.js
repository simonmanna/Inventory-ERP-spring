// Open-tab editing: create an order, then keep replacing its item set (auto-save) with the version guard.
//   k6 run -e API_BASE=http://localhost:3001 tests/load/tab-edits.js
import { api, login, serviceProduct, uuid } from './lib.js';

export const options = {
  scenarios: { waiters: { executor: 'constant-vus', vus: Number(__ENV.WAITERS || 20), duration: __ENV.DURATION || '2m' } },
  thresholds: {
    'http_req_duration{name:save-items}': ['p(95)<250'],
    'http_req_failed{name:save-items}': ['rate<0.01'],
  },
};

export function setup() {
  const token = login();
  return { token, productId: serviceProduct(token) };
}

export default function (data) {
  const order = api(data.token, 'POST', '/pos/orders', { orderType: 'takeaway', lines: [] },
    { 'Idempotency-Key': uuid() }, { name: 'create-order' }).json();
  let version = order.version;
  for (let i = 1; i <= 5; i++) {
    const res = api(data.token, 'PUT', `/pos/orders/${order.id}/items`, {
      expectedVersion: version,
      lines: [{ productId: data.productId, description: 'Coffee', quantity: i, unitPrice: 100 }],
    }, {}, { name: 'save-items' });
    if (res.status === 200) version = res.json('version');
  }
  api(data.token, 'POST', `/pos/orders/${order.id}/cancel`, { reason: 'k6' }, { 'Idempotency-Key': uuid() },
    { name: 'cancel' });
}
