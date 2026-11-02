import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';

/**
 * ADR-S007 — costing & cost centres: configurable AVCO / FIFO costing policy, cost-centre accounting (hierarchy,
 * defaults, account rules, split + periodic allocation, reports) and landed cost (cost components, shipments,
 * multi-receipt vouchers, reports).
 */

// ------------------------------------------------------------------ costing policy

export type CostMethod = 'AVCO' | 'FIFO' | 'SPECIFIC' | 'STANDARD';

export interface CostPolicy {
  id: string;
  code: string;
  scopeType: 'organization' | 'category';
  categoryId: string | null;
  categoryName: string | null;
  method: CostMethod;
  effectiveFrom: string;
  status: 'draft' | 'approved' | 'active' | 'superseded' | 'cancelled';
  version: number;
  notes: string | null;
  approvedAt: string | null;
  activatedAt: string | null;
  migrationSummary: { products: number; quants: number; valueBefore: string; valueAfter: string; difference: string } | null;
  migrationLines?: Array<{ id: string; productName: string; sku: string | null; methodFrom: string; methodTo: string; quantity: string; valueBefore: string; valueAfter: string }>;
}

export function useCostPolicies() {
  return useQuery({ queryKey: ['cost-policies'], queryFn: async () => (await api.get<CostPolicy[]>('/inventory/cost-policies')).data });
}

export function useCurrentCostPolicy() {
  return useQuery({
    queryKey: ['cost-policies', 'current'],
    queryFn: async () => (await api.get<{ active: CostPolicy[]; defaultMethod: CostMethod; governed: boolean }>('/inventory/cost-policies/current')).data,
  });
}

export function useCostPolicy(id?: string) {
  return useQuery({ queryKey: ['cost-policies', id], enabled: !!id, queryFn: async () => (await api.get<CostPolicy>(`/inventory/cost-policies/${id}`)).data });
}

export function useCostPolicyPreview(id?: string) {
  return useQuery({
    queryKey: ['cost-policies', id, 'preview'],
    enabled: !!id,
    queryFn: async () => (await api.get<{ products: any[]; totalValue: string }>(`/inventory/cost-policies/${id}/preview`)).data,
  });
}

export function useCreateCostPolicy() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: { scopeType: string; categoryId?: string; method: CostMethod; effectiveFrom: string; notes?: string }) =>
      (await api.post<CostPolicy>('/inventory/cost-policies', body)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['cost-policies'] }),
  });
}

export function useCostPolicyAction() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, action }: { id: string; action: 'approve' | 'activate' | 'cancel' }) =>
      action === 'activate'
        ? idempotentPost<CostPolicy>(`/inventory/cost-policies/${id}/activate`)
        : (await api.post<CostPolicy>(`/inventory/cost-policies/${id}/${action}`)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['cost-policies'] }),
  });
}

// ------------------------------------------------------------------ cost centres

export interface CostCenterNode {
  id: string;
  code: string;
  name: string;
  type: string;
  category: string;
  parentId: string | null;
  branchId: string | null;
  isActive: boolean;
  validFrom: string | null;
  validTo: string | null;
  children: CostCenterNode[];
}

export function useCostCenterTree() {
  return useQuery({ queryKey: ['cost-centers', 'tree'], queryFn: async () => (await api.get<CostCenterNode[]>('/cost-centers/tree')).data });
}

/** Flatten the tree depth-first with a `depth` for indented pickers. */
export function flattenTree(nodes: CostCenterNode[] = [], depth = 0): Array<CostCenterNode & { depth: number }> {
  return nodes.flatMap((n) => [{ ...n, depth }, ...flattenTree(n.children, depth + 1)]);
}

export interface AllocationRule {
  id: string;
  code: string;
  name: string;
  kind: 'split' | 'periodic';
  driver: string;
  sourceCostCenterId: string | null;
  sourceCostCenterCode: string | null;
  accountIds: string[] | null;
  isActive: boolean;
  notes: string | null;
  targets: Array<{ costCenterId: string; code: string; name: string; weight: string; sharePct: string }>;
}

export function useAllocationRules() {
  return useQuery({ queryKey: ['cost-alloc-rules'], queryFn: async () => (await api.get<AllocationRule[]>('/cost-centers/allocation-rules')).data });
}

export function useAllocationRuns() {
  return useQuery({ queryKey: ['cost-alloc-runs'], queryFn: async () => (await api.get<any[]>('/cost-centers/allocation-runs')).data });
}

export function useSaveAllocationRule() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, ...body }: any) =>
      id ? (await api.patch(`/cost-centers/allocation-rules/${id}`, body)).data : (await api.post('/cost-centers/allocation-rules', body)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['cost-alloc-rules'] }),
  });
}

export function useDeleteAllocationRule() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => api.delete(`/cost-centers/allocation-rules/${id}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['cost-alloc-rules'] }),
  });
}

export function useAllocationPreview(id?: string, periodFrom?: string, periodTo?: string) {
  return useQuery({
    queryKey: ['cost-alloc-preview', id, periodFrom, periodTo],
    enabled: !!id && !!periodFrom && !!periodTo,
    queryFn: async () => (await api.get<any>(`/cost-centers/allocation-rules/${id}/preview`, { params: { periodFrom, periodTo } })).data,
  });
}

export function useRunAllocation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, periodFrom, periodTo }: { id: string; periodFrom: string; periodTo: string }) =>
      idempotentPost<any>(`/cost-centers/allocation-rules/${id}/run`, { periodFrom, periodTo }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['cost-alloc-runs'] }),
  });
}

export function useReverseAllocationRun() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => idempotentPost<any>(`/cost-centers/allocation-runs/${id}/reverse`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['cost-alloc-runs'] }),
  });
}

export function useCostCenterDefaults() {
  return useQuery({ queryKey: ['cost-center-defaults'], queryFn: async () => (await api.get<any[]>('/cost-centers/defaults')).data });
}

export function useSaveCostCenterDefault() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: { scopeType: string; scopeId: string; costCenterId: string }) => (await api.post('/cost-centers/defaults', body)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['cost-center-defaults'] }),
  });
}

export function useDeleteCostCenterDefault() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => api.delete(`/cost-centers/defaults/${id}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['cost-center-defaults'] }),
  });
}

export function useAccountDimensionRules() {
  return useQuery({ queryKey: ['account-dimension-rules'], queryFn: async () => (await api.get<any[]>('/cost-centers/account-rules')).data });
}

export function useSetAccountDimensionRule() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ accountId, rule }: { accountId: string; rule: 'required' | 'optional' | 'forbidden' }) =>
      (await api.put(`/cost-centers/account-rules/${accountId}`, { rule })).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['account-dimension-rules'] }),
  });
}

export function useCostCenterPnl(from: string, to: string, costCenterId?: string) {
  return useQuery({
    queryKey: ['cost-center-pnl', from, to, costCenterId],
    enabled: !!from && !!to,
    queryFn: async () => (await api.get<any>('/cost-centers/reports/profit-and-loss', { params: { from, to, costCenterId: costCenterId || undefined } })).data,
  });
}

export function useCostCenterMatrix(from: string, to: string) {
  return useQuery({
    queryKey: ['cost-center-matrix', from, to],
    enabled: !!from && !!to,
    queryFn: async () => (await api.get<any>('/cost-centers/reports/matrix', { params: { from, to } })).data,
  });
}

// ------------------------------------------------------------------ landed cost

export type AllocationMethod = 'value' | 'quantity' | 'equal' | 'weight' | 'volume' | 'manual';
export type Treatment = 'capitalize' | 'expense' | 'recoverable_tax';

export interface CostComponent {
  id: string;
  code: string;
  name: string;
  kind: string;
  treatment: Treatment;
  defaultAllocationMethod: AllocationMethod;
  expenseAccountId: string | null;
  expenseAccountCode: string | null;
  description: string | null;
  isActive: boolean;
}

export function useCostComponents() {
  return useQuery({ queryKey: ['cost-components'], queryFn: async () => (await api.get<CostComponent[]>('/procurement/cost-components')).data });
}

export function useSaveCostComponent() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, ...body }: any) =>
      id ? (await api.patch(`/procurement/cost-components/${id}`, body)).data : (await api.post('/procurement/cost-components', body)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['cost-components'] }),
  });
}

export function useShipments() {
  return useQuery({ queryKey: ['shipments'], queryFn: async () => (await api.get<any[]>('/procurement/shipments')).data });
}

export function useShipment(id?: string) {
  return useQuery({ queryKey: ['shipments', id], enabled: !!id, queryFn: async () => (await api.get<any>(`/procurement/shipments/${id}`)).data });
}

export function useSaveShipment() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, ...body }: any) =>
      id ? (await api.patch(`/procurement/shipments/${id}`, body)).data : (await api.post('/procurement/shipments', body)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['shipments'] }),
  });
}

export function useShipmentReceipt() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, goodsReceiptId, remove }: { id: string; goodsReceiptId: string; remove?: boolean }) =>
      remove
        ? (await api.delete(`/procurement/shipments/${id}/receipts/${goodsReceiptId}`)).data
        : (await api.post(`/procurement/shipments/${id}/receipts`, { goodsReceiptId })).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['shipments'] }),
  });
}

export interface ChargeDraft {
  costComponentId?: string;
  kind?: string;
  description?: string;
  supplierId?: string;
  supplierInvoiceRef?: string;
  invoiceDate?: string;
  currencyCode?: string;
  exchangeRate?: number;
  amount: number;
  taxAmount?: number;
  /** ADR-S053: true = recoverable input tax, false = part of the charge; omitted = the tax / organisation default. */
  taxRecoverable?: boolean;
  treatment?: Treatment;
  allocationMethod?: AllocationMethod;
  expenseAccountId?: string;
  creditAccountId?: string;
  costCenterId?: string;
  appliesToLineIds?: string[];
  manualAllocations?: Record<string, number>;
  /** generate = posting raises the supplier's own bill; link = an existing bill; none = clearing only. */
  billMode?: BillMode;
  vendorBillId?: string;
  dueDate?: string;
}

export type BillMode = 'generate' | 'link' | 'none';

export interface VoucherDraft {
  shipmentId?: string;
  goodsReceiptIds?: string[];
  date?: string;
  notes?: string;
  costCenterId?: string;
  charges: ChargeDraft[];
}

export function useLandedCostVouchers(params?: { status?: string; shipmentId?: string }) {
  return useQuery({
    queryKey: ['landed-cost-vouchers', params],
    queryFn: async () => (await api.get<any[]>('/procurement/landed-cost-vouchers', { params })).data,
  });
}

export function useLandedCostVoucher(id?: string) {
  return useQuery({
    queryKey: ['landed-cost-vouchers', id],
    enabled: !!id,
    queryFn: async () => (await api.get<any>(`/procurement/landed-cost-vouchers/${id}`)).data,
  });
}

export function useLandedCostPreview(id?: string, enabled = true) {
  return useQuery({
    queryKey: ['landed-cost-vouchers', id, 'preview'],
    enabled: !!id && enabled,
    queryFn: async () => (await api.get<any>(`/procurement/landed-cost-vouchers/${id}/preview`)).data,
  });
}

export function useSaveLandedCostVoucher() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, body }: { id?: string; body: VoucherDraft }) =>
      id ? (await api.put(`/procurement/landed-cost-vouchers/${id}`, body)).data : idempotentPost<any>('/procurement/landed-cost-vouchers', body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['landed-cost-vouchers'] }),
  });
}

export function useLandedCostAction() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, action }: { id: string; action: 'submit' | 'approve' | 'post' | 'reverse' | 'cancel' }) =>
      action === 'post' || action === 'reverse'
        ? idempotentPost<any>(`/procurement/landed-cost-vouchers/${id}/${action}`)
        : (await api.post(`/procurement/landed-cost-vouchers/${id}/${action}`)).data,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['landed-cost-vouchers'] });
      qc.invalidateQueries({ queryKey: ['inventory-product-stock-levels'] });
    },
  });
}

export function useLandedCostReport<T = any>(kind: 'by-shipment' | 'by-item' | 'by-supplier' | 'product-profitability' | 'clearing', from?: string, to?: string) {
  return useQuery({
    queryKey: ['landed-cost-report', kind, from, to],
    enabled: kind === 'clearing' || (!!from && !!to),
    queryFn: async () => (await api.get<T>(`/procurement/landed-cost-reports/${kind}`, { params: kind === 'clearing' ? {} : { from, to } })).data,
  });
}

export const errMsg = (e: any) => {
  const m = e?.response?.data?.message ?? e?.message;
  return Array.isArray(m) ? m.join(', ') : m;
};

// ------------------------------------------------------------------ standard costing (ADR-S008)

export interface StandardCost {
  id: string;
  productId: string;
  productName: string;
  sku: string | null;
  productCostingMethod: string;
  version: number;
  status: 'draft' | 'approved' | 'active' | 'superseded' | 'cancelled';
  effectiveFrom: string;
  materialCost: string;
  labourCost: string;
  overheadCost: string;
  subcontractCost: string;
  totalCost: string;
  source: 'manual' | 'bom_rollup';
  convertMethod: boolean;
  previousCost: string | null;
  revaluationAmount: string;
  revaluationQuantity: string;
  notes: string | null;
}

export function useStandardCosts(params?: { productId?: string; status?: string }) {
  return useQuery({
    queryKey: ['standard-costs', params],
    queryFn: async () => (await api.get<StandardCost[]>('/inventory/standard-costs', { params })).data,
  });
}

export function useStandardVariances(from: string, to: string, kind?: string) {
  return useQuery({
    queryKey: ['standard-variances', from, to, kind],
    enabled: !!from && !!to,
    queryFn: async () =>
      (await api.get<{ rows: any[]; totals: Record<string, string>; total: string }>('/inventory/standard-costs/variances', {
        params: { from, to, kind: kind || undefined },
      })).data,
  });
}

export function useCreateStandardCost() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: Record<string, unknown>) => (await api.post<StandardCost>('/inventory/standard-costs', body)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['standard-costs'] }),
  });
}

export function useProposeBomStandard() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ bomId, ...body }: { bomId: string; effectiveFrom?: string; convertMethod?: boolean }) =>
      (await api.post<StandardCost>(`/manufacturing/boms/${bomId}/product-standard`, body)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['standard-costs'] }),
  });
}

export function useStandardCostAction() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, action }: { id: string; action: 'approve' | 'activate' | 'cancel' }) =>
      action === 'activate'
        ? idempotentPost<StandardCost>(`/inventory/standard-costs/${id}/activate`)
        : (await api.post<StandardCost>(`/inventory/standard-costs/${id}/${action}`)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['standard-costs'] }),
  });
}

// ------------------------------------------------------------------ process costing (ADR-S008)

export function useCostProcesses() {
  return useQuery({ queryKey: ['cost-processes'], queryFn: async () => (await api.get<any[]>('/manufacturing/process-costing/processes')).data });
}

export function useCostProcess(id?: string) {
  return useQuery({
    queryKey: ['cost-processes', id],
    enabled: !!id,
    queryFn: async () => (await api.get<any>(`/manufacturing/process-costing/processes/${id}`)).data,
  });
}

export function useSaveCostProcess() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, ...body }: any) =>
      id
        ? (await api.patch(`/manufacturing/process-costing/processes/${id}`, body)).data
        : (await api.post('/manufacturing/process-costing/processes', body)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['cost-processes'] }),
  });
}

export function useOpenProcessPeriod() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ processId, periodFrom, periodTo }: { processId: string; periodFrom: string; periodTo: string }) =>
      (await api.post(`/manufacturing/process-costing/processes/${processId}/periods`, { periodFrom, periodTo })).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['cost-processes'] }),
  });
}

export function useProcessPeriod(id?: string) {
  return useQuery({
    queryKey: ['process-period', id],
    enabled: !!id,
    queryFn: async () => (await api.get<any>(`/manufacturing/process-costing/periods/${id}`)).data,
  });
}

export function useProcessPreview(id?: string, enabled = true) {
  return useQuery({
    queryKey: ['process-period', id, 'preview'],
    enabled: !!id && enabled,
    retry: false,
    queryFn: async () => (await api.get<any>(`/manufacturing/process-costing/periods/${id}/preview`)).data,
  });
}

export function useProcessPeriodAction() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, kind, body }: { id: string; kind: 'units' | 'materials' | 'costs' | 'close'; body?: unknown }) =>
      kind === 'units'
        ? (await api.patch(`/manufacturing/process-costing/periods/${id}/units`, body)).data
        : idempotentPost<any>(`/manufacturing/process-costing/periods/${id}/${kind}`, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['process-period'] });
      qc.invalidateQueries({ queryKey: ['cost-processes'] });
    },
  });
}
