import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';
import { notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';

/** Cost Management CM-1 … CM-3 (ADR-S031 … ADR-S033). */
export interface CostingSettings {
  valuation: { defaultCostingMethod: string; allowNegativeStock: boolean; costPolicies: { id: string; code: string; method: string; scopeType: string; effectiveFrom: string; status: string }[]; note: string };
  landedCosts: { enabled: boolean; defaultAllocationMethod: string; components: { id: string; code: string; name: string; kind: string; treatment: string; defaultAllocationMethod: string; eligibility: string | null }[] };
  accounts: { key: string; label: string; accountId: string | null; code: string | null; name: string | null }[];
  policies: PolicyElection[];
  eligibility: { classifications: { subjectType: string; subjectId: string; eligibility: string }[]; unclassifiedAccounts: { id: string; code: string; name: string; category: string }[] };
  dimensions: { enforcement: 'off' | 'warn' | 'enforce' };
}

export interface PolicyElection {
  key: string;
  label: string;
  standard: string;
  scopeTypes: string[];
  values: string[] | null;
  min: string | null;
  max: string | null;
  default: string | null;
  note: string;
  current: string | null;
}

export interface PolicyChange {
  id: string;
  election: string;
  scopeType: string;
  scopeId: string;
  value: string;
  effectiveFrom: string;
  status: 'proposed' | 'approved' | 'rejected';
  justification: string;
  proposedBy: string | null;
  approvedBy: string | null;
  inForce: boolean;
}

export interface Revaluation {
  id: string;
  number: string;
  kind: 'revaluation' | 'nrv';
  status: 'draft' | 'posted' | 'reversed';
  revaluationDate: string;
  reason: string | null;
  totalAmount: string;
}

export interface AllocationCycle {
  id: string;
  code: string;
  name: string;
  postingMode: 'financial' | 'statistical';
  method: 'sequential' | 'reciprocal';
  isActive: boolean;
  segments?: AllocationSegment[];
}

export interface AllocationSegment {
  id?: string;
  seq: number;
  name: string;
  senderCostCenterId: string;
  senderAccountIds: string[];
  receiverType: 'cost_center';
  receivers: { receiverId: string; manualValue?: number }[];
  basis: string;
  statisticalKeyId?: string | null;
  sharePercent: number;
}

export interface AllocationRun {
  id: string;
  number: string;
  cycleId: string;
  periodFrom: string;
  periodTo: string;
  postingMode: string;
  status: 'posted' | 'reversed';
  total: string;
  journalEntryId: string | null;
  lines?: { segmentSeq: number; senderCostCenterId: string; receiverId: string; basisValue: string; share: string; amount: string }[];
}

const KEY = ['cost-management'];

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

export function useCostingSettings() {
  return useQuery({ queryKey: [...KEY, 'settings'], queryFn: async () => (await api.get<CostingSettings>('/costing/settings')).data });
}

export function useSaveCostingSettings() {
  return useMut(async (body: Record<string, unknown>) => (await api.put<CostingSettings>('/costing/settings', body)).data, tx('Settings saved'));
}

export function useEnsureCostingAccounts() {
  return useMut(async () => (await api.post('/costing/settings/accounts/ensure')).data, tx('Accounts ready'));
}

export function usePolicyChanges() {
  return useQuery({ queryKey: [...KEY, 'policies'], queryFn: async () => (await api.get<PolicyChange[]>('/costing/policies')).data });
}

export function useProposePolicy() {
  return useMut(async (body: Record<string, unknown>) => (await api.post<PolicyChange>('/costing/policies', body)).data, tx('Proposal recorded'));
}

export function usePolicyDecision() {
  return useMut(async ({ id, action }: { id: string; action: 'approve' | 'reject' }) => (await api.post(`/costing/policies/${id}/${action}`, action === 'reject' ? {} : undefined)).data, tx('Done'));
}

export function useRevaluations() {
  return useQuery({ queryKey: [...KEY, 'revaluations'], queryFn: async () => (await api.get<Revaluation[]>('/inventory/revaluations')).data });
}

export function useCreateRevaluation() {
  return useMut(async (body: Record<string, unknown>) => (await api.post<Revaluation>('/inventory/revaluations', body)).data, tx('Draft created'));
}

export function useRevaluationAction() {
  return useMut(async ({ id, action }: { id: string; action: 'post' | 'reverse' | 'delete' }) => {
    if (action === 'delete') return (await api.delete(`/inventory/revaluations/${id}`)).data;
    return (await idempotentPost(`/inventory/revaluations/${id}/${action}`)).data;
  }, tx('Done'));
}

export function useOpenAdjuncts() {
  return useQuery({ queryKey: [...KEY, 'adjuncts'], queryFn: async () => (await api.get<{ productId: string; productName: string; locationId: string; valuationComponent: string; openAmount: string }[]>('/inventory/price-differences')).data });
}

export function useReleaseAdjuncts() {
  return useMut(async () => (await idempotentPost('/inventory/price-differences/release')).data, tx('Released'));
}

export function useAllocationCycles() {
  return useQuery({ queryKey: [...KEY, 'cycles'], queryFn: async () => (await api.get<AllocationCycle[]>('/costing/allocation-cycles')).data });
}

export function useCreateAllocationCycle() {
  return useMut(async (body: Record<string, unknown>) => (await api.post<AllocationCycle>('/costing/allocation-cycles', body)).data, tx('Cycle saved'));
}

export function useAllocationRuns() {
  return useQuery({ queryKey: [...KEY, 'runs'], queryFn: async () => (await api.get<AllocationRun[]>('/costing/allocation-runs')).data });
}

export function useAllocationPreview() {
  return useMutation({
    mutationFn: async ({ id, from, to }: { id: string; from: string; to: string }) =>
      (await api.post<AllocationRun>(`/costing/allocation-cycles/${id}/preview`, { from, to })).data,
  });
}

export function useRunAllocation() {
  return useMut(async ({ id, from, to }: { id: string; from: string; to: string }) => (await idempotentPost(`/costing/allocation-cycles/${id}/runs`, { from, to })).data, tx('Run posted'));
}

export function useReverseAllocationRun() {
  return useMut(async (id: string) => (await idempotentPost(`/costing/allocation-runs/${id}/reverse`)).data, tx('Run reversed'));
}
