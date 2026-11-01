import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';
import { notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';

/** Advanced costing remainder (roadmap 3.1, ADR-S030). */
export interface MaterialLedgerRun {
  id: string;
  number: string;
  periodFrom: string;
  periodTo: string;
  status: 'draft' | 'posted' | 'reversed';
  productCount: number;
  poolTotal: string;
  toInventory: string;
  toConsumption: string;
  deltaTotal: string;
  lines?: {
    productId: string;
    productName: string;
    standardCost: string;
    openingQty: string;
    carriedVariance: string;
    receiptQty: string;
    periodVariance: string;
    consumedQty: string;
    endingQty: string;
    toInventory: string;
    toConsumption: string;
    periodicUnitCost: string;
    delta: string;
  }[];
}

export interface AbcActivity {
  id: string;
  code: string;
  name: string;
  costObject: 'customer' | 'product';
  driver: string;
  accountIds: string[];
  poolPercent: string;
  isActive: boolean;
}

export interface AbcRun {
  id: string;
  costObject: string;
  periodFrom: string;
  periodTo: string;
  poolTotal: string;
  assignedTotal: string;
  unassignedTotal: string;
  objectCount: number;
  activities?: { code: string; name: string; driver: string; pool: string; driverTotal: string; rate: string }[];
  results?: { objectId: string; objectName: string | null; revenue: string; costOfGoods: string; activityCost: string; margin: string }[];
}

const KEY = ['advanced-costing'];

function useMut<T>(fn: (input: T) => Promise<unknown>, success: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: KEY });
      notify.success(success);
    },
  });
}

export function useMlRuns() {
  return useQuery({ queryKey: [...KEY, 'ml'], queryFn: async () => (await api.get<MaterialLedgerRun[]>('/costing/material-ledger/runs')).data });
}

export function useMlRun(id?: string) {
  return useQuery({
    queryKey: [...KEY, 'ml', id],
    queryFn: async () => (await api.get<MaterialLedgerRun>(`/costing/material-ledger/runs/${id}`)).data,
    enabled: !!id,
  });
}

export function useCreateMlRun() {
  return useMut(async (input: { from: string; to: string }) => (await api.post<MaterialLedgerRun>('/costing/material-ledger/runs', input)).data, tx('Run computed'));
}

export function useMlAction() {
  return useMut(async ({ id, action }: { id: string; action: 'post' | 'reverse' | 'recompute' | 'delete' }) => {
    if (action === 'delete') return (await api.delete(`/costing/material-ledger/runs/${id}`)).data;
    if (action === 'recompute') return (await api.post(`/costing/material-ledger/runs/${id}/recompute`)).data;
    return (await idempotentPost(`/costing/material-ledger/runs/${id}/${action}`)).data;
  }, tx('Done'));
}

export function useVarianceAnalysis(from: string, to: string) {
  return useQuery({
    queryKey: [...KEY, 'variances', from, to],
    queryFn: async () => (await api.get<Record<string, unknown>>('/costing/variance-analysis', { params: { from, to } })).data,
  });
}

export function useAbcDrivers() {
  return useQuery({
    queryKey: [...KEY, 'drivers'],
    queryFn: async () => (await api.get<Record<string, { driver: string; label: string }[]>>('/costing/abc/drivers')).data,
    staleTime: 60 * 60_000,
  });
}

export function useAbcActivities() {
  return useQuery({ queryKey: [...KEY, 'activities'], queryFn: async () => (await api.get<AbcActivity[]>('/costing/abc/activities')).data });
}

export function useCreateAbcActivity() {
  return useMut(async (input: Omit<Partial<AbcActivity>, 'poolPercent'> & { accountIds: string[]; poolPercent?: number }) =>
    (await api.post('/costing/abc/activities', input)).data, tx('Activity saved'));
}

export function useDeleteAbcActivity() {
  return useMut(async (id: string) => (await api.delete(`/costing/abc/activities/${id}`)).data, tx('Activity deleted'));
}

export function useAbcRuns() {
  return useQuery({ queryKey: [...KEY, 'abc-runs'], queryFn: async () => (await api.get<AbcRun[]>('/costing/abc/runs')).data });
}

export function useAbcRun(id?: string) {
  return useQuery({
    queryKey: [...KEY, 'abc-runs', id],
    queryFn: async () => (await api.get<AbcRun>(`/costing/abc/runs/${id}`)).data,
    enabled: !!id,
  });
}

export function useComputeAbc() {
  return useMut(async (input: { costObject: string; from: string; to: string }) => (await api.post<AbcRun>('/costing/abc/runs', input)).data, tx('Run computed'));
}
