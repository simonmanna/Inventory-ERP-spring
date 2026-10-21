// Read-heavy back office: Z-report and the trial balance while tills keep selling.
//   k6 run -e API_BASE=http://localhost:3001 -e CASH_SESSION_ID=<closed session> tests/load/reports.js
import { api, login } from './lib.js';

export const options = {
  scenarios: {
    managers: {
      executor: 'constant-arrival-rate', rate: Number(__ENV.RATE || 10), timeUnit: '1s',
      duration: __ENV.DURATION || '2m', preAllocatedVUs: 20,
    },
  },
  thresholds: {
    'http_req_duration{name:z-report}': ['p(95)<800'],
    'http_req_duration{name:trial-balance}': ['p(95)<1500'],
    http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  return { token: login() };
}

export default function (data) {
  const zPath = __ENV.CASH_SESSION_ID ? `/pos/reports/z-report/${__ENV.CASH_SESSION_ID}` : '/pos/reports/z-report';
  api(data.token, 'GET', zPath, undefined, {}, { name: 'z-report' });
  api(data.token, 'GET', '/reports/accounting/trial-balance', undefined, {}, { name: 'trial-balance' });
}
