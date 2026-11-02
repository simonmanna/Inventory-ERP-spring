// Expenses ERP extensions (ADR-S052, EX-1..EX-6): claims, advances, petty cash, schedules, limits, settings, reports.
import { idempotentPost } from '@/lib/idempotent-request';
import { api } from '@/lib/api';

export type Row = Record<string, any>;

const get = <T = any>(url: string, params?: Record<string, unknown>): Promise<T> =>
  api.get(url, { params }).then((r) => r.data);
const post = <T = any>(url: string, body?: unknown): Promise<T> => api.post(url, body ?? {}).then((r) => r.data);
const patch = <T = any>(url: string, body: unknown): Promise<T> => api.patch(url, body).then((r) => r.data);

export interface MoneyBody {
  paymentMethod: string;
  accountId: string;
  reference?: string;
  amount?: number;
}

export const expenseFlowApi = {
  returnForCorrection: (id: string, reason: string) => post(`/expenses/${id}/return`, { reason }),
  submit: (id: string) => idempotentPost(`/expenses/${id}/submit`, {}),
};

export const claimsApi = {
  list: (params?: { status?: string; mine?: boolean }) => get<Row[]>('/expense-claims', params),
  get: (id: string) => get<Row>(`/expense-claims/${id}`),
  create: (body: Row) => idempotentPost('/expense-claims', body) as Promise<Row>,
  update: (id: string, body: Row) => patch<Row>(`/expense-claims/${id}`, body),
  submit: (id: string) => idempotentPost(`/expense-claims/${id}/submit`, {}) as Promise<Row>,
  approve: (id: string, notes?: string) => idempotentPost(`/expense-claims/${id}/approve`, { notes }) as Promise<Row>,
  reject: (id: string, reason: string) => post<Row>(`/expense-claims/${id}/reject`, { reason }),
  returnForCorrection: (id: string, reason: string) => post<Row>(`/expense-claims/${id}/return`, { reason }),
  cancel: (id: string) => post<Row>(`/expense-claims/${id}/cancel`),
  pay: (id: string, body: MoneyBody) => idempotentPost(`/expense-claims/${id}/pay`, body) as Promise<Row>,
};

export const advancesApi = {
  list: (params?: { status?: string }) => get<Row[]>('/expense-advances', params),
  get: (id: string) => get<Row>(`/expense-advances/${id}`),
  create: (body: Row) => idempotentPost('/expense-advances', body) as Promise<Row>,
  approve: (id: string, notes?: string) => post<Row>(`/expense-advances/${id}/approve`, { notes }),
  reject: (id: string, reason: string) => post<Row>(`/expense-advances/${id}/reject`, { reason }),
  cancel: (id: string) => post<Row>(`/expense-advances/${id}/cancel`),
  disburse: (id: string, body: MoneyBody) => idempotentPost(`/expense-advances/${id}/disburse`, body) as Promise<Row>,
  refund: (id: string, body: MoneyBody) => idempotentPost(`/expense-advances/${id}/refund`, body) as Promise<Row>,
};

export const pettyCashApi = {
  list: () => get<Row[]>('/petty-cash-funds'),
  get: (id: string) => get<Row>(`/petty-cash-funds/${id}`),
  create: (body: Row) => post<Row>('/petty-cash-funds', body),
  update: (id: string, body: Row) => patch<Row>(`/petty-cash-funds/${id}`, body),
  replenish: (id: string, body: { sourceAccountId: string; amount?: number; paymentMethod?: string; reference?: string }) =>
    idempotentPost(`/petty-cash-funds/${id}/replenish`, body) as Promise<Row>,
  count: (id: string, body: { countedAmount: number; notes?: string }) =>
    idempotentPost(`/petty-cash-funds/${id}/counts`, body) as Promise<Row>,
  statement: (id: string, from?: string, to?: string) => get<Row>(`/petty-cash-funds/${id}/statement`, { from, to }),
};

export const schedulesApi = {
  recurrences: () => get<Row[]>('/expense-recurrences'),
  createRecurrence: (body: Row) => post<Row>('/expense-recurrences', body),
  setRecurrence: (id: string, action: 'pause' | 'resume' | 'end') => post<Row>(`/expense-recurrences/${id}/${action}`),
  runRecurrence: (id: string) => idempotentPost(`/expense-recurrences/${id}/run`, {}) as Promise<Row>,
  prepayments: () => get<Row[]>('/expense-prepayments'),
  prepayment: (id: string) => get<Row>(`/expense-prepayments/${id}`),
  accruals: () => get<Row[]>('/expense-accruals'),
  createAccrual: (body: Row) => idempotentPost('/expense-accruals', body) as Promise<Row>,
  reverseAccrual: (id: string) => idempotentPost(`/expense-accruals/${id}/reverse`, {}) as Promise<Row>,
  runDue: (asOf?: string) => post<Row>('/expense-schedules/run', asOf ? { asOf } : {}),
};

export const controlsApi = {
  limits: () => get<Row[]>('/expense-limits'),
  createLimit: (body: Row) => post<Row>('/expense-limits', body),
  updateLimit: (id: string, body: Row) => patch<Row>(`/expense-limits/${id}`, body),
  deleteLimit: (id: string) => api.delete(`/expense-limits/${id}`),
  settings: () => get<Row>('/expense-settings'),
  saveSettings: (body: Row) => api.put('/expense-settings', body).then((r) => r.data as Row),
};

export const expenseReportsApi = {
  summary: (groupBy: string, dateFrom?: string, dateTo?: string) =>
    get<Row>('/expense-reports/summary', { groupBy, dateFrom, dateTo }),
  trend: (months = 12) => get<Row>('/expense-reports/trend', { months }),
  pipeline: (dateFrom?: string, dateTo?: string) => get<Row>('/expense-reports/pipeline', { dateFrom, dateTo }),
  reimbursements: () => get<Row[]>('/expense-reports/reimbursements'),
  advances: () => get<Row>('/expense-reports/advances'),
  pettyCash: () => get<Row[]>('/expense-reports/petty-cash'),
  schedules: () => get<Row>('/expense-reports/schedules'),
  capitalPurchases: (dateFrom?: string, dateTo?: string) =>
    get<Row[]>('/expense-reports/capital-purchases', { dateFrom, dateTo }),
};

/** Lookups for the forms (branches, cost centres, taxes, cash accounts, users). Each fails soft to []. */
export const lookupsApi = {
  branches: () => get<any>('/branches').then(list).catch(() => []),
  costCenters: () => get<any>('/cost-centers').then(list).catch(() => []),
  taxes: () => get<any>('/taxes').then(list).catch(() => []),
  cashAccounts: () => get<any>('/expenses/meta/accounts').then(list).catch(() => []),
  users: () => get<any>('/users', { pageSize: 500 }).then(list).catch(() => []),
};

function list(d: any): Row[] {
  return Array.isArray(d) ? d : (d?.data ?? d?.items ?? []);
}

export const fmt = (n: unknown) =>
  Number(n ?? 0).toLocaleString(undefined, { minimumFractionDigits: 0, maximumFractionDigits: 2 });

export const errMsg = (e: any, fallback = 'Request failed') => {
  const m = e?.response?.data?.message;
  return Array.isArray(m) ? m.join(', ') : m || e?.message || fallback;
};

export const userName = (u?: Row | null) =>
  u ? `${u.firstName ?? ''} ${u.lastName ?? ''}`.trim() || u.email || '' : '';
