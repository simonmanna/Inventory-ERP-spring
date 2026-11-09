import { useMutation, useQuery, useQueryClient, type QueryKey } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';

/**
 * Product lifecycle management (ADR-S063) — lifecycle items, change requests (ECR), staged change orders (ECO),
 * engineering documents and the ECO stage configuration. All routes live under `/manufacturing/plm`.
 *
 * Every mutation toasts the server's verbatim error and refreshes all PLM queries (plus BOM / routing lists for
 * the writes that create or activate them). Pages pass their own `onSuccess` to `mutate` for navigation / toasts.
 */

const BASE = '/manufacturing/plm';

// ---- vocabularies ----------------------------------------------------------------------------------------------------

export type Num = number | string;
export type LifecycleState = 'concept' | 'design' | 'prototype' | 'released' | 'phase_out' | 'obsolete';
export type ChangeType = 'bom' | 'routing' | 'product' | 'document';
export type ChangeStatus = 'draft' | 'approved' | 'applied' | 'rejected';
export type Priority = 'low' | 'normal' | 'high' | 'urgent';
export type RequestStatus = 'new' | 'accepted' | 'rejected' | 'converted';
export type ReasonCode =
  | 'cost_reduction' | 'quality' | 'customer' | 'supplier' | 'regulatory' | 'safety' | 'manufacturability' | 'other';
export type DocStatus = 'draft' | 'released' | 'superseded' | 'obsolete';
export type DocType =
  | 'drawing' | 'specification' | 'cad' | 'work_instruction' | 'test_procedure' | 'datasheet' | 'certificate' | 'other';
export type Effectivity = 'asap' | 'date';
export type StockDisposition = 'use_up' | 'scrap' | 'rework' | 'return_to_supplier' | 'none';
export type WipDisposition = 'continue' | 'update' | 'rework';
export type ApproverKind = 'required' | 'optional';

/** Counts may serialize as numbers or numeric strings. */
export const num = (v: Num | null | undefined): number => (v == null || v === '' ? 0 : Number(v));

/** Message of an API error (`message` may be a string or a list of validation messages). */
export function plmError(e: unknown): string {
  const err = e as { response?: { data?: { message?: unknown } }; message?: string };
  const m = err?.response?.data?.message ?? err?.message;
  if (Array.isArray(m)) return m.filter(Boolean).map(String).join(', ');
  return typeof m === 'string' && m ? m : tx('Something went wrong');
}

// ---- shapes ----------------------------------------------------------------------------------------------------------

export interface ProductRef {
  id: string;
  code: string;
  name: string;
  manufacturingRole?: string | null;
}

export interface PlmItem {
  id: string;
  productId: string;
  revision: string;
  lifecycleState: LifecycleState;
  ownerId: string | null;
  ownerName: string | null;
  description: string | null;
  notes: string | null;
  replacementProductId: string | null;
  releasedAt: string | null;
  obsoleteAt: string | null;
  createdAt?: string;
  productCode?: string;
  productName?: string;
  manufacturingRole?: string | null;
  activeBom?: string | null;
  openChanges?: Num;
  releasedDocuments?: Num;
}

export interface LifecycleEvent {
  id: string;
  kind: 'registered' | 'transition' | 'revision';
  fromState: LifecycleState | null;
  toState: LifecycleState | null;
  fromRevision: string | null;
  toRevision: string | null;
  changeId: string | null;
  note: string | null;
  userName: string | null;
  createdAt: string;
}

export interface WhereUsedRow {
  level: number;
  bomId: string;
  code: string;
  name: string;
  version: number;
  outputProductId: string;
  outputName: string | null;
  quantity: Num;
  uomId: string | null;
  componentProductId: string;
}

export interface PlmItemDetail extends PlmItem {
  product: ProductRef;
  replacement: ProductRef | null;
  allowedTransitions: LifecycleState[];
  history: LifecycleEvent[];
  boms: Array<{
    id: string; code: string; name: string; version: number; status: string; isDefault: boolean;
    routingId: string | null; effectiveFrom: string | null; effectiveTo: string | null; updatedAt: string;
  }>;
  routings: Array<{ id: string; name: string; isActive: boolean; operations: Num }>;
  changes: Array<{
    id: string; number: string; title: string; type: ChangeType; status: ChangeStatus; priority: Priority;
    stageId: string | null; stageName: string | null; newRevision: string | null; appliedAt: string | null; createdAt: string;
  }>;
  requests: Array<{
    id: string; number: string; title: string; status: RequestStatus; priority: Priority; reasonCode: ReasonCode;
    changeId: string | null; createdAt: string;
  }>;
  documents: Array<{
    id: string; number: string; version: number; revision: string; title: string; docType: DocType; status: DocStatus;
    fileId: string | null; url: string | null; fileName: string | null; releasedAt: string | null;
  }>;
  openOrders: Array<{ id: string; orderCode: string; status: string; plannedQty: Num; bomVersion: number | null }>;
  whereUsed: WhereUsedRow[];
}

export interface StageApprover {
  id: string;
  stageId: string;
  userId: string;
  userName: string | null;
  kind: ApproverKind;
}

export interface PlmStage {
  id: string;
  name: string;
  sequence: number;
  requiresApproval: boolean;
  description: string | null;
  isFinal?: boolean;
  openChanges?: Num;
  approvers?: StageApprover[];
}

export interface ChangeRequest {
  id: string;
  number: string;
  title: string;
  description: string | null;
  productId: string | null;
  product: ProductRef | null;
  bomId: string | null;
  reasonCode: ReasonCode;
  priority: Priority;
  status: RequestStatus;
  dueDate: string | null;
  requestedById: string | null;
  requestedByName: string | null;
  reviewedByName: string | null;
  reviewedAt: string | null;
  decisionNote: string | null;
  changeId: string | null;
  changeNumber?: string | null;
  changeStatus?: ChangeStatus | null;
  overdue?: boolean | null;
  createdAt: string;
}

export interface ChangeRow {
  id: string;
  number: string;
  title: string;
  reason: string | null;
  description: string | null;
  type: ChangeType;
  status: ChangeStatus;
  priority: Priority;
  stageId: string | null;
  stageName?: string | null;
  stageSequence?: number | null;
  productId: string | null;
  product?: ProductRef | null;
  ownerId: string | null;
  ownerName?: string | null;
  requestId: string | null;
  requestNumber?: string | null;
  bomId: string | null;
  newBomId: string | null;
  routingId: string | null;
  newRoutingId: string | null;
  targetLifecycleState: LifecycleState | null;
  newRevision: string | null;
  effectivity: Effectivity;
  effectiveDate: string | null;
  stockDisposition: StockDisposition;
  wipDisposition: WipDisposition;
  rejectedReason?: string | null;
  approvedAt: string | null;
  appliedAt: string | null;
  createdAt: string;
  /** Apply was requested but waits (effective date, use-up of stock); the hourly job switches it over. */
  applyQueued?: boolean;
  /** Why an approved change cannot switch over yet (detail / apply responses only). */
  pending?: string | null;
  /** What apply did with stock and open orders (BOM changes). */
  dispositionResult?: DispositionResult | null;
}

export interface DispositionDocument {
  kind: 'stock_adjustment' | 'stock_transfer' | 'debit_note';
  id: string;
  code: string;
  locationId: string;
  partnerId?: string;
  lines: Num;
}

export interface DispositionResult {
  stockDisposition: StockDisposition;
  wipDisposition: WipDisposition;
  documents: DispositionDocument[];
  skipped: Array<{ productId: string; product: ProductRef | null; reason: string }>;
  replacedOrders: Array<{ fromId: string; fromCode: string; toId: string; toCode: string; status: string }>;
  flaggedOrders: Array<{ id: string; orderCode: string; status: string }>;
  keptOrders: Array<{ id: string; orderCode: string; status: string; reason: string }>;
}

export interface DispositionPlan {
  stockDisposition: StockDisposition;
  wipDisposition: WipDisposition;
  stock: Array<{
    productId: string; product: ProductRef | null; locationId: string; locationName: string; quantity: Num;
    unitCost: Num; action: string; supplier?: { id: string; name: string } | null;
  }>;
  keptComponents: Array<{ productId: string; product: ProductRef | null; reason: string }>;
  orders: Array<{ id: string; orderCode: string; status: string; plannedQty: Num; action: string }>;
  useUpPending: Array<{ productId: string; label: string; onHand: Num }>;
}

export interface BomLineDiff {
  id: string;
  componentProductId: string;
  componentVariantId?: string | null;
  quantity: Num;
  uomId?: string | null;
  scrapPct?: Num | null;
  isOptional?: boolean;
}

export interface BomComparison {
  from: { id: string; code: string; version: number };
  to: { id: string; code: string; version: number };
  header: Array<{ field: string; from: unknown; to: unknown }>;
  added: BomLineDiff[];
  removed: BomLineDiff[];
  changed: Array<{ componentProductId: string; changes: Record<string, { from: unknown; to: unknown }> }>;
}

export interface RoutingOperation {
  id?: string;
  sequence: number;
  name: string;
  workCenterId: string | null;
  durationMins: number | null;
  setupMins: number | null;
  instructions: string | null;
  isSubcontracted: boolean | null;
  subcontractorId?: string | null;
  subcontractCostPerUnit: Num | null;
}

export interface RoutingComparison {
  added: RoutingOperation[];
  removed: RoutingOperation[];
  changed: Array<{ sequence: number; name: string; changes: Record<string, { from: unknown; to: unknown }> }>;
}

export interface ApprovalState {
  stageId: string;
  requiresApproval: boolean;
  approvers: Array<{
    userId: string; userName: string | null; kind: ApproverKind | null; decision: 'approved' | 'rejected' | null;
    comment: string | null; decidedAt: string | null;
  }>;
  satisfied: boolean;
  blocker: string | null;
  canDecide: boolean;
  myDecision: 'approved' | 'rejected' | null;
}

export interface PlmDocument {
  id: string;
  number: string;
  version: number;
  revision: string;
  title: string;
  docType: DocType;
  status: DocStatus;
  productId: string | null;
  product?: ProductRef | null;
  bomId: string | null;
  changeId: string | null;
  changeNumber?: string | null;
  fileId: string | null;
  url: string | null;
  fileName: string | null;
  description: string | null;
  releasedAt: string | null;
  createdAt: string;
}

export interface PlmDocumentDetail extends PlmDocument {
  versions: PlmDocument[];
}

export interface ChangeDetail extends ChangeRow {
  stage: PlmStage;
  stages: PlmStage[];
  product: ProductRef | null;
  comparison: BomComparison | null;
  routingComparison: RoutingComparison | null;
  newRouting: { id: string; name: string; isActive: boolean; operations: RoutingOperation[] } | null;
  documents: PlmDocument[];
  request: ChangeRequest | null;
  plmItem: PlmItem | null;
  approval: ApprovalState;
  approvals: Array<{
    id: string; userName: string | null; stageName: string | null; decision: 'approved' | 'rejected';
    comment: string | null; voided: boolean; createdAt: string;
  }>;
  activity: Array<{ id: string; kind: string; message: string; userName: string | null; createdAt: string }>;
  ownerName: string | null;
  approvedByName: string | null;
  createdByName: string | null;
}

export interface ChangeImpact {
  changeId: string;
  type: ChangeType;
  stockDisposition: StockDisposition;
  wipDisposition: WipDisposition;
  openOrders: Array<{
    id: string; orderCode: string; status: string; plannedQty: Num; bomId: string | null; bomVersion: number | null;
    scheduledFor: string | null;
  }>;
  removedComponents: Array<{
    productId: string; product: ProductRef | null; quantity: Num; onHand: Num; usedElsewhere: Num; obsolescenceRisk: boolean;
  }>;
  addedComponents: Array<{
    productId: string; product: ProductRef | null; quantity: Num; onHand: Num; lifecycleState: LifecycleState | null;
  }>;
  changedComponents: Array<{
    productId: string; product: ProductRef | null; changes: Record<string, { from: unknown; to: unknown }>; onHand: Num;
  }>;
  cost: { currentUnitCost: Num | null; newUnitCost: Num | null; delta: Num | null; deltaPct: Num | null } | { error: string } | null;
  routing: { bomsRepointed: Array<{ id: string; code: string; version: number; status: string }> } | null;
  onHand?: Num;
  whereUsed?: WhereUsedRow[];
  lifecycleState?: LifecycleState | null;
  dispositionPlan: DispositionPlan;
  pending: string | null;
  warnings: string[];
}

export interface PlmOverview {
  lifecycle: Record<LifecycleState, Num>;
  items: Num;
  changes: {
    byStatus: Record<ChangeStatus, Num>;
    byStage: Array<{ id: string; name: string; sequence: number; count: Num }>;
    appliedLast30Days: Num;
    avgCycleDays: Num | null;
  };
  requests: { byStatus: Record<RequestStatus, Num>; overdue: Num };
  documents: Record<DocStatus, Num>;
  awaitingMyApproval: Array<{ id: string; number: string; title: string; priority: Priority; stageName: string; createdAt: string }>;
  recentActivity: Array<{
    id: string; kind: string; message: string; userName: string | null; createdAt: string; changeId: string;
    number: string; title: string;
  }>;
}

// ---- request bodies --------------------------------------------------------------------------------------------------

export interface RegisterItemBody {
  productId: string;
  revision?: string;
  lifecycleState?: 'concept' | 'design' | 'prototype' | 'released';
  ownerId?: string;
  description?: string;
  notes?: string;
}
export interface UpdateItemBody {
  ownerId?: string;
  description?: string;
  notes?: string;
  replacementProductId?: string;
}
export interface TransitionBody {
  toState: LifecycleState;
  note?: string;
  replacementProductId?: string;
}
export interface SaveStageBody {
  name?: string;
  sequence?: number;
  requiresApproval?: boolean;
  description?: string;
}
export interface SaveRequestBody {
  title?: string;
  description?: string;
  productId?: string;
  bomId?: string;
  reasonCode?: ReasonCode;
  priority?: Priority;
  dueDate?: string;
}
export interface ConvertRequestBody {
  type: ChangeType;
  title?: string;
  bomId?: string;
  routingId?: string;
  targetLifecycleState?: LifecycleState;
  documentIds?: string[];
}
export interface CreateChangeBody {
  type: ChangeType;
  title?: string;
  description?: string;
  reason?: string;
  priority?: Priority;
  productId?: string;
  bomId?: string;
  routingId?: string;
  targetLifecycleState?: LifecycleState;
  documentIds?: string[];
  effectivity?: Effectivity;
  effectiveDate?: string;
  stockDisposition?: StockDisposition;
  wipDisposition?: WipDisposition;
  newRevision?: string;
  ownerId?: string;
  requestId?: string;
}
export interface UpdateChangeBody {
  title?: string;
  description?: string;
  reason?: string;
  priority?: Priority;
  targetLifecycleState?: LifecycleState;
  effectivity?: Effectivity;
  effectiveDate?: string;
  stockDisposition?: StockDisposition;
  wipDisposition?: WipDisposition;
  newRevision?: string;
  ownerId?: string;
}
export interface RoutingOperationInput {
  sequence: number;
  name: string;
  workCenterId?: string;
  durationMins?: number;
  setupMins?: number;
  instructions?: string;
  isSubcontracted?: boolean;
  subcontractorId?: string;
  subcontractCostPerUnit?: number;
}
export interface SaveDocumentBody {
  title?: string;
  docType?: DocType;
  revision?: string;
  productId?: string;
  bomId?: string;
  changeId?: string;
  fileId?: string;
  url?: string;
  fileName?: string;
  description?: string;
}

/** Drop `undefined` fields: the API rejects unknown JSON fields, and absent ≠ null for PATCH routes. */
export function clean<T extends object>(body: T): T {
  return Object.fromEntries(Object.entries(body).filter(([, v]) => v !== undefined)) as T;
}

// ---- queries ---------------------------------------------------------------------------------------------------------

const KEY = ['plm'] as const;
const get = async <T,>(url: string, params?: Record<string, unknown>) =>
  (await api.get<T>(`${BASE}${url}`, { params: params ? clean(params) : undefined })).data;
const blankToUndef = (p: Record<string, string | undefined>) =>
  Object.fromEntries(Object.entries(p).map(([k, v]) => [k, v ? v : undefined]));

export function usePlmOverview() {
  return useQuery({ queryKey: [...KEY, 'overview'], queryFn: () => get<PlmOverview>('/overview') });
}

export function usePlmItems(params: { state?: string; productId?: string; q?: string } = {}, enabled = true) {
  const p = blankToUndef(params);
  return useQuery({ queryKey: [...KEY, 'items', p], queryFn: () => get<PlmItem[]>('/items', p), enabled });
}

export function usePlmItem(id?: string) {
  return useQuery({ queryKey: [...KEY, 'item', id], queryFn: () => get<PlmItemDetail>(`/items/${id}`), enabled: !!id });
}

export function usePlmStages() {
  return useQuery({ queryKey: [...KEY, 'stages'], queryFn: () => get<PlmStage[]>('/stages') });
}

export function usePlmRequests(params: { status?: string; productId?: string; q?: string } = {}) {
  const p = blankToUndef(params);
  return useQuery({ queryKey: [...KEY, 'requests', p], queryFn: () => get<ChangeRequest[]>('/requests', p) });
}

export function usePlmRequest(id?: string) {
  return useQuery({ queryKey: [...KEY, 'request', id], queryFn: () => get<ChangeRequest>(`/requests/${id}`), enabled: !!id });
}

export function usePlmChanges(
  params: { status?: string; stageId?: string; type?: string; productId?: string; q?: string } = {},
  enabled = true,
) {
  const p = blankToUndef(params);
  return useQuery({ queryKey: [...KEY, 'changes', p], queryFn: () => get<ChangeRow[]>('/changes', p), enabled });
}

export function usePlmChange(id?: string) {
  return useQuery({ queryKey: [...KEY, 'change', id], queryFn: () => get<ChangeDetail>(`/changes/${id}`), enabled: !!id });
}

export function useChangeImpact(id?: string, enabled = true) {
  return useQuery({
    queryKey: [...KEY, 'impact', id],
    queryFn: () => get<ChangeImpact>(`/changes/${id}/impact`),
    enabled: !!id && enabled,
  });
}

export function usePlmDocuments(
  params: { productId?: string; changeId?: string; status?: string; docType?: string; q?: string } = {},
  enabled = true,
) {
  const p = blankToUndef(params);
  return useQuery({ queryKey: [...KEY, 'documents', p], queryFn: () => get<PlmDocument[]>('/documents', p), enabled });
}

export function usePlmDocument(id?: string | null) {
  return useQuery({
    queryKey: [...KEY, 'document', id],
    queryFn: () => get<PlmDocumentDetail>(`/documents/${id}`),
    enabled: !!id,
  });
}

/** BOM versions of a product (for the BOM-change pickers). */
export interface BomOption {
  id: string;
  code: string;
  name: string;
  version: number;
  status: string;
  isDefault: boolean;
  routingId?: string | null;
}
export function useProductBoms(productId?: string) {
  return useQuery({
    queryKey: ['boms', { outputProductId: productId }],
    queryFn: async () => (await api.get<BomOption[]>('/manufacturing/boms', { params: { outputProductId: productId } })).data,
    enabled: !!productId,
  });
}

/** Users of the organization (id + display name) for owner / approver pickers. */
export interface OrgUserOption {
  id: string;
  name: string;
  email: string;
  isActive: boolean;
}
export function useOrgUsers() {
  return useQuery({
    queryKey: ['organization-users'],
    staleTime: 5 * 60_000,
    queryFn: async () => (await api.get<Array<{ id: string; email: string; firstName: string | null; lastName: string | null; isActive: boolean }>>('/organizations/users')).data,
    select: (rows): OrgUserOption[] => rows
      .map((u) => ({
        id: u.id,
        email: u.email,
        isActive: u.isActive !== false,
        name: [u.firstName, u.lastName].filter(Boolean).join(' ').trim() || u.email,
      }))
      .sort((a, b) => a.name.localeCompare(b.name)),
  });
}

// ---- mutations -------------------------------------------------------------------------------------------------------

type Method = 'post' | 'patch' | 'put' | 'delete';
/** Extra query keys refreshed by writes that create / activate BOMs and routings. */
const ENGINEERING_KEYS: QueryKey[] = [['boms'], ['bom'], ['routings'], ['ecos']];

function usePlmMutation<V, R = unknown>(
  build: (v: V) => { method: Method; url: string; body?: unknown },
  opts: { engineering?: boolean } = {},
) {
  const qc = useQueryClient();
  return useMutation<R, unknown, V>({
    mutationFn: async (v: V) => {
      const { method, url, body } = build(v);
      const res = method === 'delete'
        ? await api.delete(`${BASE}${url}`)
        : await api[method](`${BASE}${url}`, body === undefined ? {} : clean(body as object));
      return res.data as R;
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: KEY });
      if (opts.engineering) ENGINEERING_KEYS.forEach((k) => void qc.invalidateQueries({ queryKey: k }));
    },
    onError: (e) => notify.error(tx('Action failed'), plmError(e)),
  });
}

// lifecycle items
export const useRegisterItem = () =>
  usePlmMutation<RegisterItemBody, PlmItem>((body) => ({ method: 'post', url: '/items', body }));
export const useRegisterActive = () =>
  usePlmMutation<void, { registered: Num }>(() => ({ method: 'post', url: '/items/register-active' }));
export const useUpdateItem = () =>
  usePlmMutation<{ id: string; body: UpdateItemBody }, PlmItem>(({ id, body }) => ({ method: 'patch', url: `/items/${id}`, body }));
export const useTransitionItem = () =>
  usePlmMutation<{ id: string; body: TransitionBody }, PlmItem>(({ id, body }) => ({ method: 'post', url: `/items/${id}/transition`, body }));

// stages
export const useCreateStage = () =>
  usePlmMutation<SaveStageBody, PlmStage>((body) => ({ method: 'post', url: '/stages', body }));
export const useUpdateStage = () =>
  usePlmMutation<{ id: string; body: SaveStageBody }, PlmStage>(({ id, body }) => ({ method: 'patch', url: `/stages/${id}`, body }));
export const useDeleteStage = () =>
  usePlmMutation<string>((id) => ({ method: 'delete', url: `/stages/${id}` }));
export const useSetStageApprovers = () =>
  usePlmMutation<{ id: string; approvers: Array<{ userId: string; kind: ApproverKind }> }, StageApprover[]>(
    ({ id, approvers }) => ({ method: 'put', url: `/stages/${id}/approvers`, body: { approvers } }),
  );

// change requests
export const useCreateRequest = () =>
  usePlmMutation<SaveRequestBody, ChangeRequest>((body) => ({ method: 'post', url: '/requests', body }));
export const useUpdateRequest = () =>
  usePlmMutation<{ id: string; body: SaveRequestBody }, ChangeRequest>(({ id, body }) => ({ method: 'patch', url: `/requests/${id}`, body }));
export const useReviewRequest = () =>
  usePlmMutation<{ id: string; decision: 'accept' | 'reject'; note?: string }, ChangeRequest>(({ id, decision, note }) => ({
    method: 'post', url: `/requests/${id}/review`, body: { decision, note },
  }));
export const useConvertRequest = () =>
  usePlmMutation<{ id: string; body: ConvertRequestBody }, ChangeDetail>(
    ({ id, body }) => ({ method: 'post', url: `/requests/${id}/convert`, body }),
    { engineering: true },
  );

// change orders
export const useCreateChange = () =>
  usePlmMutation<CreateChangeBody, ChangeDetail>((body) => ({ method: 'post', url: '/changes', body }), { engineering: true });
export const useUpdateChange = () =>
  usePlmMutation<{ id: string; body: UpdateChangeBody }, ChangeDetail>(({ id, body }) => ({ method: 'patch', url: `/changes/${id}`, body }));
export const useMoveChangeStage = () =>
  usePlmMutation<{ id: string; stageId: string }, ChangeDetail>(({ id, stageId }) => ({
    method: 'post', url: `/changes/${id}/stage`, body: { stageId },
  }));
export const useDecideChange = () =>
  usePlmMutation<{ id: string; decision: 'approved' | 'rejected'; comment?: string }, ChangeDetail>(({ id, decision, comment }) => ({
    method: 'post', url: `/changes/${id}/decisions`, body: { decision, comment },
  }));
export const useCommentChange = () =>
  usePlmMutation<{ id: string; message: string }, ChangeDetail>(({ id, message }) => ({
    method: 'post', url: `/changes/${id}/comments`, body: { message },
  }));
export const useLinkChangeDocuments = () =>
  usePlmMutation<{ id: string; documentIds: string[] }, ChangeDetail>(({ id, documentIds }) => ({
    method: 'post', url: `/changes/${id}/documents`, body: { documentIds },
  }));
export const useSaveRoutingOperations = () =>
  usePlmMutation<{ id: string; operations: RoutingOperationInput[] }, ChangeDetail>(
    ({ id, operations }) => ({ method: 'put', url: `/changes/${id}/routing-operations`, body: { operations: operations.map((o) => clean(o)) } }),
    { engineering: true },
  );
/** Apply (or queue, when it must wait); {@code force} switches over despite stock still to be used up. */
export const useApplyChange = () =>
  usePlmMutation<string | { id: string; force?: boolean }, ChangeRow>((v) => {
    const { id, force } = typeof v === 'string' ? { id: v, force: false } : v;
    return { method: 'post', url: `/changes/${id}/apply`, body: force ? { force: true } : {} };
  }, { engineering: true });
export const useCancelChange = () =>
  usePlmMutation<{ id: string; reason?: string }, ChangeRow>(
    ({ id, reason }) => ({ method: 'post', url: `/changes/${id}/cancel`, body: { reason } }),
    { engineering: true },
  );

// documents
export const useCreateDocument = () =>
  usePlmMutation<SaveDocumentBody, PlmDocument>((body) => ({ method: 'post', url: '/documents', body }));
export const useUpdateDocument = () =>
  usePlmMutation<{ id: string; body: SaveDocumentBody }, PlmDocument>(({ id, body }) => ({ method: 'patch', url: `/documents/${id}`, body }));
export const useDocumentAction = () =>
  usePlmMutation<{ id: string; action: 'release' | 'revise' | 'obsolete' }, PlmDocument>(({ id, action }) => ({
    method: 'post', url: `/documents/${id}/${action}`,
  }));
export const useDeleteDocument = () =>
  usePlmMutation<string>((id) => ({ method: 'delete', url: `/documents/${id}` }));

// ---- files -----------------------------------------------------------------------------------------------------------

/** Upload a document file to `/files` (owner type `plm_document`); returns the stored file id and name. */
export async function uploadPlmFile(file: File): Promise<{ id: string; filename: string }> {
  const fd = new FormData();
  fd.append('file', file);
  fd.append('ownerType', 'plm_document');
  const { data } = await api.post<{ id: string; filename?: string }>('/files/upload', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  return { id: data.id, filename: data.filename ?? file.name };
}

/** Open a stored file through a short-lived signed URL. */
export async function openPlmFile(fileId: string): Promise<void> {
  try {
    const { data } = await api.post<{ url: string }>(`/files/${encodeURIComponent(fileId)}/signed-url`);
    window.open(data.url, '_blank', 'noopener');
  } catch (e) {
    notify.error(tx('Could not open the file'), plmError(e));
  }
}
