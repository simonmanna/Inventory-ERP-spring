import { useQuery } from '@tanstack/react-query';
import {
  PLM_KEY, plmGet, usePlmMutation, type ChangeDetail, type CostPolicy, type CostResult, type LifecycleState, type Num,
  type ProductRef,
} from './api-plm';

/**
 * The PLM application (ADR-S086) on top of the ADR-S063 screens: ECO types and tags, kanban state, BOM rebase,
 * revisions (versions, compare, as-built), production-order BOM updates, the financial impact of a change, first-article
 * inspection, settings (lifecycle policy matrix) and reports. Routes live under `/manufacturing/plm`.
 */

// ---- shapes ----------------------------------------------------------------------------------------------------------

export interface PlmChangeType {
  id: string;
  name: string;
  description: string | null;
  color: string | null;
  sequence: number;
  isDefault: boolean;
  stageCount?: Num;
  openChanges?: Num;
}

export interface PlmTag {
  id: string;
  name: string;
  color: string | null;
  changes?: Num;
}

export interface ChangeFinancial {
  changeId: string;
  number: string;
  type: string;
  costPolicy: CostPolicy;
  status: string;
  applicable: boolean;
  product?: { id: string; code: string; name: string; costingMethod: string };
  currentUnitCost?: Num | null;
  currentSource?: 'standard' | 'cost_price';
  rollup?: {
    currentVersion: Num | null; newVersion: Num | null; deltaToCurrentCost: Num | null; deltaPct: Num | null;
    error: string | null;
  };
  onHand?: { quantity: Num; value: Num | null };
  revaluation?: {
    amount: Num | null; direction: 'increase' | 'decrease' | null; account: { id?: string; code: string; name: string };
  };
  canActivate?: boolean;
  activateBlocker?: string | null;
  result?: CostResult | null;
  standardCostId?: string | null;
  revaluationJournalId?: string | null;
  costVisible?: boolean;
}

export interface RevisionRow {
  id: string;
  itemId: string;
  productId: string;
  revision: string;
  status: 'current' | 'superseded';
  effectiveFrom: string;
  effectiveTo: string | null;
  changeId: string | null;
  changeNumber?: string | null;
  bomId: string | null;
  bomVersion: number | null;
  routingId: string | null;
  unitCost: Num | null;
  costSource: 'standard' | 'cost_price' | null;
  note: string | null;
  createdBy: string | null;
  createdByName?: string | null;
  createdAt: string;
  bom: { id: string; code: string; version: number; lines: number } | null;
  routing: { id: string; name: string; operations: number } | null;
  documents: number;
  recorded: boolean;
  ordersBuilt?: Num;
}

export interface FieldDiff { field: string; from: unknown; to: unknown }

export interface RevisionCompare {
  from: RevisionRow;
  to: RevisionRow;
  recorded: boolean;
  product: FieldDiff[];
  bom: {
    from: { id: string; code: string; version: number } | null;
    to: { id: string; code: string; version: number } | null;
    header: FieldDiff[];
    added: Array<{ componentProductId: string; componentCode?: string; componentName?: string; quantity?: Num }>;
    removed: Array<{ componentProductId: string; componentCode?: string; componentName?: string; quantity?: Num }>;
    changed: Array<{ componentProductId: string; componentCode?: string; componentName?: string; changes: FieldDiff[] }>;
  };
  routing: {
    from: { id: string; name: string } | null;
    to: { id: string; name: string } | null;
    added: Array<{ sequence: number; name: string }>;
    removed: Array<{ sequence: number; name: string }>;
    changed: Array<{ sequence: number; name: string; changes: FieldDiff[] }>;
  };
  documents: {
    added: Array<{ number: string; title: string; version?: Num; revision?: string | null }>;
    removed: Array<{ number: string; title: string; version?: Num; revision?: string | null }>;
    revised: Array<{
      number: string; title: string; from: { version: Num; revision: string | null }; to: { version: Num; revision: string | null };
    }>;
  };
  cost: { from: Num | null; to: Num | null; delta: Num | null };
}

export interface AsBuiltRow {
  id: string;
  orderId: string;
  orderCode: string;
  productId: string;
  revisionId: string | null;
  revision: string | null;
  matchedBy: 'bom' | 'date' | 'none';
  bomId: string | null;
  bomVersion: number | null;
  batchNumber: string | null;
  quantity: Num;
  completedAt: string | null;
}

export interface OrderBomStatus {
  orderId: string;
  orderCode: string;
  status: string;
  bom: { id: string; code: string; version: number; status: string } | null;
  current: { id: string; code: string; version: number } | null;
  superseded: boolean;
  canUpdate: boolean;
  blocker: string | null;
  openChanges: Array<{ id: string; number: string; title: string; status: string }>;
}

export type PolicyActivity = 'purchase' | 'sell' | 'replenish';
export type PolicyValue = 'allow' | 'block';
export type LifecyclePolicy = Record<LifecycleState, Record<PolicyActivity, PolicyValue>>;

export interface PlmSettings {
  lifecyclePolicy: LifecyclePolicy;
  defaults: LifecyclePolicy;
  defaultCostPolicy: CostPolicy;
  firstArticleDefault: boolean;
  states: LifecycleState[];
  activities: PolicyActivity[];
  updatedAt: string | null;
}

export interface SaveSettingsBody {
  lifecyclePolicy?: Partial<Record<LifecycleState, Partial<Record<PolicyActivity, PolicyValue>>>>;
  defaultCostPolicy?: CostPolicy;
  firstArticleDefault?: boolean;
}

export type ReportGroup = 'month' | 'type' | 'stage' | 'owner' | 'priority';

export interface ChangeReport {
  from: string;
  to: string;
  groupBy: ReportGroup;
  changeTypeId: string | null;
  created: { created: Num; open: Num; applied: Num; rejected: Num; blocked: Num };
  applied: {
    applied: Num; avgCycleDays: Num | null; p90CycleDays: Num | null; avgApprovalDays: Num | null; firstPass: Num;
    firstPassRate: Num | null; productDataChanges: Num; standardsCreated: Num; revaluations: Num;
    revaluationAmount: Num | null; rebases: Num;
  };
  firstArticle: Record<'pending' | 'open' | 'no_plan' | 'passed' | 'failed' | 'cancelled', Num>;
  byType: Record<string, Num>;
  rows: Array<{
    key: string | null; label: string | null; created: Num; open: Num; applied: Num; rejected: Num;
    avgCycleDays: Num | null;
  }>;
  costVisible: boolean;
}

export interface ObsoleteStockRow {
  itemId: string;
  productId: string;
  lifecycleState: 'phase_out' | 'obsolete';
  revision: string;
  code: string;
  name: string;
  replacementProductId: string | null;
  replacementCode: string | null;
  replacementName: string | null;
  since: string | null;
  onHand: Num;
  value: Num | null;
  openPurchaseQty: Num;
  openPurchaseOrders: Num;
  openSalesQty: Num;
  usedInActiveBoms: Num;
}

export interface ObsoleteStockReport {
  rows: ObsoleteStockRow[];
  byState: Record<string, { products: Num; onHand: Num; value: Num | null }>;
  totals: {
    products: Num; withStock: Num; onHand: Num; value: Num | null; stillPurchased: Num; usedInActiveBoms: Num;
  };
  costVisible: boolean;
}

export interface StockByRevisionRow {
  itemId: string;
  productId: string;
  code: string;
  name: string;
  lifecycleState: LifecycleState;
  currentRevision: string;
  onHand: Num;
  revisions: Array<{
    revision: string | null; revisionId: string | null; status: 'current' | 'superseded' | null; exact: Num;
    estimated: Num; quantity: Num;
  }>;
  exact: Num;
  estimated: Num;
  unknown: Num;
  batches: Array<{ batchNumber: string; quantity: Num; revision: string | null; orderCode: string | null }>;
  ordersBuilt: Num;
}

// ---- queries ---------------------------------------------------------------------------------------------------------

export function useChangeTypes() {
  return useQuery({ queryKey: [...PLM_KEY, 'change-types'], queryFn: () => plmGet<PlmChangeType[]>('/change-types') });
}

export function usePlmTags() {
  return useQuery({ queryKey: [...PLM_KEY, 'tags'], queryFn: () => plmGet<PlmTag[]>('/tags') });
}

export function useChangeFinancial(id?: string, enabled = true) {
  return useQuery({
    queryKey: [...PLM_KEY, 'financial', id],
    queryFn: () => plmGet<ChangeFinancial>(`/changes/${id}/financial`),
    enabled: !!id && enabled,
  });
}

export function useItemRevisions(itemId?: string) {
  return useQuery({
    queryKey: [...PLM_KEY, 'revisions', itemId],
    queryFn: () => plmGet<RevisionRow[]>(`/items/${itemId}/revisions`),
    enabled: !!itemId,
  });
}

export function useRevisionCompare(from?: string, to?: string) {
  return useQuery({
    queryKey: [...PLM_KEY, 'revision-compare', from, to],
    queryFn: () => plmGet<RevisionCompare>('/revisions/compare', { from, to }),
    enabled: !!from && !!to && from !== to,
  });
}

export function useAsBuilt(itemId?: string) {
  return useQuery({
    queryKey: [...PLM_KEY, 'as-built', itemId],
    queryFn: () => plmGet<AsBuiltRow[]>(`/items/${itemId}/as-built`),
    enabled: !!itemId,
  });
}

export function useOrderBomStatus(orderId?: string, enabled = true) {
  return useQuery({
    queryKey: [...PLM_KEY, 'order-bom', orderId],
    queryFn: () => plmGet<OrderBomStatus>(`/orders/${orderId}/bom-status`),
    enabled: !!orderId && enabled,
    retry: false,
  });
}

export function usePlmSettings() {
  return useQuery({ queryKey: [...PLM_KEY, 'settings'], queryFn: () => plmGet<PlmSettings>('/settings') });
}

export function useChangeReport(params: { from?: string; to?: string; changeTypeId?: string; groupBy?: ReportGroup }) {
  const p = Object.fromEntries(Object.entries(params).map(([k, v]) => [k, v || undefined]));
  return useQuery({
    queryKey: [...PLM_KEY, 'report-changes', p],
    queryFn: () => plmGet<ChangeReport>('/reports/changes', p),
  });
}

export function useObsoleteStock(state?: string) {
  return useQuery({
    queryKey: [...PLM_KEY, 'report-obsolete', state ?? null],
    queryFn: () => plmGet<ObsoleteStockReport>('/reports/obsolete-stock', { state: state || undefined }),
  });
}

export function useStockByRevision(productId?: string) {
  return useQuery({
    queryKey: [...PLM_KEY, 'report-revisions', productId ?? null],
    queryFn: () => plmGet<{ rows: StockByRevisionRow[] }>('/reports/stock-by-revision', {
      productId: productId || undefined,
    }),
  });
}

// ---- mutations -------------------------------------------------------------------------------------------------------

export interface SaveTypeBody { name?: string; description?: string; color?: string; sequence?: number; isDefault?: boolean }
export interface SaveTagBody { name?: string; color?: string }

export const useCreateChangeType = () =>
  usePlmMutation<SaveTypeBody, PlmChangeType>((body) => ({ method: 'post', url: '/change-types', body }));
export const useUpdateChangeType = () =>
  usePlmMutation<{ id: string; body: SaveTypeBody }, PlmChangeType>(({ id, body }) => ({
    method: 'patch', url: `/change-types/${id}`, body,
  }));
export const useDeleteChangeType = () =>
  usePlmMutation<string>((id) => ({ method: 'delete', url: `/change-types/${id}` }));

export const useCreateTag = () => usePlmMutation<SaveTagBody, PlmTag>((body) => ({ method: 'post', url: '/tags', body }));
export const useUpdateTag = () =>
  usePlmMutation<{ id: string; body: SaveTagBody }, PlmTag>(({ id, body }) => ({ method: 'patch', url: `/tags/${id}`, body }));
export const useDeleteTag = () => usePlmMutation<string>((id) => ({ method: 'delete', url: `/tags/${id}` }));

export const useSetKanbanState = () =>
  usePlmMutation<{ id: string; kanbanState: 'normal' | 'blocked' | 'ready'; blockedReason?: string }>(
    ({ id, ...body }) => ({ method: 'post', url: `/changes/${id}/kanban-state`, body }),
  );

export type RebaseResult = ChangeDetail & {
  rebase: { from: { code: string; version: number }; onto: { code: string; version: number }; carriedIn: unknown[]; conflicts: unknown[] };
};
export const useRebaseChange = () =>
  usePlmMutation<string, RebaseResult>((id) => ({ method: 'post', url: `/changes/${id}/rebase` }), { engineering: true });

export const useRecordFirstArticle = () =>
  usePlmMutation<{ id: string; result: 'passed' | 'failed'; note?: string }>(({ id, ...body }) => ({
    method: 'post', url: `/changes/${id}/first-article`, body,
  }));

export const useSavePlmSettings = () =>
  usePlmMutation<SaveSettingsBody, PlmSettings>((body) => ({ method: 'put', url: '/settings', body }));

export const useUpdateOrderBom = () =>
  usePlmMutation<string, { orderId: string; fromCode: string; toCode: string; bom: { code: string; version: number } }>(
    (orderId) => ({ method: 'post', url: `/orders/${orderId}/update-bom` }),
    { extraKeys: [['production-orders'], ['production-order']] },
  );

// ---- vocab -----------------------------------------------------------------------------------------------------------

export const COST_POLICY: Record<CostPolicy, { label: string; hint: string }> = {
  none: { label: 'Leave costs alone', hint: 'Costs follow the normal costing method.' },
  propose: {
    label: 'Propose a standard',
    hint: 'The new version’s roll-up becomes a draft standard cost, approved and activated in Standard costs.',
  },
  activate: {
    label: 'Activate the standard',
    hint: 'The standard is approved and activated on apply: stock on hand is revalued and posted to 5960.',
  },
};

export const FAI_STATUS: Record<string, { label: string; tone: 'neutral' | 'info' | 'warning' | 'success' | 'danger' }> = {
  pending: { label: 'Waiting for the first order', tone: 'info' },
  open: { label: 'Being inspected', tone: 'warning' },
  no_plan: { label: 'Result to record', tone: 'warning' },
  passed: { label: 'Passed', tone: 'success' },
  failed: { label: 'Failed', tone: 'danger' },
  cancelled: { label: 'Cancelled', tone: 'neutral' },
};

export const PRODUCT_FIELDS: Array<{ field: string; label: string; kind: 'text' | 'textarea' | 'number' }> = [
  { field: 'name', label: 'Name', kind: 'text' },
  { field: 'description', label: 'Description', kind: 'textarea' },
  { field: 'barcode', label: 'Barcode', kind: 'text' },
  { field: 'weight', label: 'Weight', kind: 'number' },
  { field: 'volume', label: 'Volume', kind: 'number' },
  { field: 'image', label: 'Image URL', kind: 'text' },
];

export type { ProductRef };
