import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import type { Num } from './api';

/**
 * Job costing (CM-8a, ADR-S038) client for one project: sites, WBS, budgets, cost-to-complete forecast, the project
 * cost sub-ledger (capture + reversal) and the budget / committed / actual / EAC report. Every response is a plain
 * object or array. Cost capture and reversal post journal entries, so they go through {@link idempotentPost}: a retry
 * after a timeout reuses the same Idempotency-Key and the server replays the first result.
 */

export const COST_ELEMENTS = ['material', 'labour', 'equipment', 'subcontract', 'other', 'overhead'] as const;
export type CostElement = (typeof COST_ELEMENTS)[number];
export const FULFILMENT_CLASSES = ['incremental', 'directly_related', 'excluded'] as const;
export type FulfilmentClass = (typeof FULFILMENT_CLASSES)[number];

export interface ProjectSite {
  id: string;
  projectId: string;
  code: string;
  name: string;
  address?: string | null;
  deliveryLocationId?: string | null;
  createdAt?: string;
}

export interface WbsElement {
  id: string;
  projectId: string;
  parentId?: string | null;
  code: string;
  name: string;
  isBillable: boolean;
  isCostable: boolean;
  createdAt?: string;
}

/** GET /projects/:id — the job-costing project row with its sites and WBS elements. */
export interface JobProject {
  id: string;
  code: string;
  name: string;
  status: string;
  contractValue?: Num | null;
  customerId?: string | null;
  branchId?: string | null;
  costCenterId?: string | null;
  sites: ProjectSite[];
  wbs: WbsElement[];
}

export interface BudgetLine {
  id: string;
  budgetId: string;
  wbsId: string;
  costElement: CostElement;
  amount: Num;
  revenue: Num;
}

export interface Budget {
  id: string;
  projectId: string;
  version: number;
  kind: 'original' | 'revised' | string;
  status: 'draft' | 'approved' | 'superseded' | string;
  total: Num;
  notes?: string | null;
  createdBy?: string | null;
  approvedBy?: string | null;
  approvedAt?: string | null;
  createdAt: string;
  lines?: BudgetLine[];
}

export interface ForecastLine {
  id: string;
  wbsId: string;
  costElement: CostElement;
  costToComplete: Num;
  fulfilmentClass: FulfilmentClass;
  asOf: string;
  createdAt: string;
}

export interface CostLine {
  id: string;
  projectId: string;
  wbsId: string;
  wbsCode?: string | null;
  siteId?: string | null;
  costElement: CostElement;
  sourceType: string;
  sourceId: string;
  sourceLineId?: string | null;
  date: string;
  quantity?: Num | null;
  rate?: Num | null;
  amount: Num;
  excludeFromProgress: boolean;
  journalEntryId?: string | null;
  reversalEntryId?: string | null;
  status: 'posted' | 'reversed' | string;
  description?: string | null;
  createdAt: string;
}

export interface CostReportRow {
  wbsId: string;
  wbsCode?: string | null;
  costElement: CostElement;
  budget: Num;
  actual: Num;
  costToComplete: Num;
  eac: Num;
  variance: Num;
}

export interface CostReport {
  projectId: string;
  code: string;
  budgetId?: string | null;
  rows: CostReportRow[];
  totals: { budget: Num; committed: Num; actual: Num; costToComplete: Num; eac: Num; variance: Num };
  wipTieOut: { glWip: Num; subledger: Num; difference: Num };
}

export interface TimesheetCapture { batchId: string; journalEntryId?: string | null; entries: number; total: Num }

/** GET /projects/:id/costs/reclassable — a posted expense line that can still be (partly) reclassed. */
export interface ReclassableLine {
  journalLineId: string;
  journalEntryId: string;
  entryNumber: string;
  postingDate: string;
  accountId: string;
  accountCode: string;
  accountName: string;
  description?: string | null;
  partnerName?: string | null;
  amount: Num;
  reclassed: Num;
  open: Num;
}

export interface LocationOption { id: string; name: string; code?: string | null }

// Under the PM key so every PM write (and every costing write) refreshes the overview's job-costing card too.
const KEY = ['pm', 'costing'] as const;

export function useJobProject(id: string | undefined, enabled = true) {
  return useQuery({
    queryKey: [...KEY, id, 'project'],
    enabled: !!id && enabled,
    queryFn: async () => (await api.get<JobProject>(`/projects/${id}`)).data,
  });
}

export function useBudgets(id: string | undefined, enabled = true) {
  return useQuery({
    queryKey: [...KEY, id, 'budgets'],
    enabled: !!id && enabled,
    queryFn: async () => (await api.get<Budget[]>(`/projects/${id}/budgets`)).data,
  });
}

export function useBudget(budgetId: string | null | undefined) {
  return useQuery({
    queryKey: [...KEY, 'budget', budgetId],
    enabled: !!budgetId,
    queryFn: async () => (await api.get<Budget>(`/projects/budgets/${budgetId}`)).data,
  });
}

export function useForecast(id: string | undefined, enabled = true) {
  return useQuery({
    queryKey: [...KEY, id, 'forecast'],
    enabled: !!id && enabled,
    queryFn: async () => (await api.get<ForecastLine[]>(`/projects/${id}/forecast`)).data,
  });
}

export function useCostLines(id: string | undefined, enabled = true) {
  return useQuery({
    queryKey: [...KEY, id, 'costs'],
    enabled: !!id && enabled,
    queryFn: async () => (await api.get<CostLine[]>(`/projects/${id}/costs`)).data,
  });
}

export function useCostReport(id: string | undefined, enabled = true) {
  return useQuery({
    queryKey: [...KEY, id, 'report'],
    enabled: !!id && enabled,
    queryFn: async () => (await api.get<CostReport>(`/projects/${id}/report`)).data,
  });
}

/** Reclass candidates for the reclass dialog (needs `project:post`); newest first, filtered server-side. */
export function useReclassable(id: string | undefined, filter: { search?: string; from?: string; to?: string }, enabled = true) {
  return useQuery({
    queryKey: [...KEY, id, 'reclassable', filter],
    enabled: !!id && enabled,
    queryFn: async () =>
      (await api.get<ReclassableLine[]>(`/projects/${id}/costs/reclassable`, {
        params: { search: filter.search || undefined, from: filter.from || undefined, to: filter.to || undefined, limit: 100 },
      })).data,
  });
}

/** Stock locations for the issue dialog and a site's delivery location. */
export function useLocationOptions(enabled = true) {
  return useQuery({
    queryKey: ['inventory', 'locations', 'options'],
    enabled,
    queryFn: async () =>
      (await api.get<{ data: LocationOption[] }>('/inventory/locations', { params: { pageSize: 500 } })).data.data ?? [],
  });
}

type Method = 'post' | 'put' | 'patch' | 'delete';

/**
 * One mutation hook for every costing write. `money: true` sends it with a stable Idempotency-Key (cost capture and
 * reversal post journal entries). Errors toast the server's verbatim message; success refreshes all PM queries.
 */
export function useCostingMutation<V = void, R = unknown>(
  build: (v: V) => { method: Method; url: string; body?: unknown; money?: boolean },
  opts: { success?: string } = {},
) {
  const qc = useQueryClient();
  return useMutation<R, unknown, V>({
    mutationFn: async (v: V) => {
      const { method, url, body, money } = build(v);
      if (money && method === 'post') return idempotentPost<R>(url, body ?? {});
      if (method === 'delete') return (await api.delete<R>(url)).data;
      const res = await api[method]<R>(url, body ?? {});
      return res.data;
    },
    onSuccess: () => {
      if (opts.success) notify.success(opts.success);
      void qc.invalidateQueries({ queryKey: ['pm'] });
    },
    onError: (err) => notify.error(apiMessage(err, tx('Something went wrong'))),
  });
}

/** Turn a form string into a number for the JSON body; blank stays undefined (the server applies its default). */
export function decimalOrUndefined(v: string): string | undefined {
  const t = v.trim();
  return t === '' ? undefined : t;
}
