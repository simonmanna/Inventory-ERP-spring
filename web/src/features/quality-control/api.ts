import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { PaginatedResult } from '@erp/shared';
import { api } from '@/lib/api';
import { tx } from '@/lib/i18n/tx';

// ---- Types (Odoo-style quality control, ADR-S087) ----

export type QcOperation = 'receipt' | 'delivery' | 'manufacturing' | 'work_order';
export type QcCheckType = 'instructions' | 'pass_fail' | 'measure' | 'picture' | 'worksheet';
export type QcCheckStatus = 'pending' | 'passed' | 'failed' | 'waived' | 'cancelled';
export type QcOrigin = 'generated' | 'manual' | 'correction';
export type QcSourceType = 'production_order' | 'work_order' | 'goods_receipt' | 'sales_delivery' | 'manual';
export type QcControlPer = 'operation' | 'product' | 'quantity';
export type QcFrequency = 'all' | 'random' | 'periodic';
export type QcDisposition = 'accepted_as_is' | 'quantity_excluded' | 'reworked' | 'other';
export type WorksheetFieldType = 'text' | 'number' | 'checkbox' | 'select' | 'date' | 'photo';
type Num = string | number | null;

export interface QcTeam {
  id: string;
  name: string;
  description: string | null;
  isActive: boolean;
  members?: Array<{ userId: string; name: string; email?: string }>;
}

export interface QcFailureClass { id: string; code: string; name: string; sortOrder: number; isActive: boolean }

export interface WorksheetField {
  key: string;
  label: string;
  type: WorksheetFieldType;
  required?: boolean;
  min?: number | null;
  max?: number | null;
  decimals?: number | null;
  options?: string[];
  passValues?: string[];
  expected?: boolean | null;
  maxLength?: number | null;
  showIf?: { field: string; equals: unknown } | null;
}

export interface WorksheetTemplate {
  id: string;
  templateKey: string;
  version: number;
  name: string;
  description: string | null;
  fields: WorksheetField[];
  isLatest: boolean;
  isActive: boolean;
  checkCount?: number;
  versionCount?: number;
  createdAt: string;
  versions?: Array<{ id: string; version: number; name: string; isLatest: boolean; isActive: boolean; createdAt: string; checkCount: number }>;
}

export interface ControlPoint {
  id: string;
  reference: string;
  title: string;
  operation: QcOperation;
  productIds: string[];
  categoryIds: string[];
  supplierPartnerId: string | null;
  supplierName?: string | null;
  routingOperationId: string | null;
  routingOperationName?: string | null;
  teamId: string | null;
  teamName?: string | null;
  responsibleUserId: string | null;
  responsibleName?: string | null;
  checkType: QcCheckType;
  instructions: string | null;
  failureMessage: string | null;
  failureClassRequired: boolean;
  norm: Num;
  toleranceMin: Num;
  toleranceMax: Num;
  unit: string | null;
  decimals: number | null;
  worksheetTemplateKey: string | null;
  worksheetTemplateId?: string | null;
  worksheetTemplateName?: string | null;
  controlPer: QcControlPer;
  testQtyPct: Num;
  minSampleQty: Num;
  frequency: QcFrequency;
  randomPct: Num;
  periodValue: number | null;
  periodUnit: 'day' | 'week' | 'month' | null;
  blocking: boolean;
  blockOnFailure: boolean;
  autoAlert: boolean;
  receiptMode: 'pre_receipt' | 'quarantine_on_receipt' | null;
  failureLocationId: string | null;
  failureLocationName?: string | null;
  spcSubgroup: string;
  spcSubgroupSize: number | null;
  spcWindowHours: number | null;
  spcSplitByWorkCenter: boolean;
  sequence: number;
  isActive: boolean;
  version: number;
  checkCount?: number;
  pendingCount?: number;
  note?: string;
  createdAt: string;
  updatedAt: string;
  versions?: Array<{ id: string; version: number; action: string; reason: string | null; config: Record<string, unknown>; changedBy: string | null; changedByName?: string | null; changedAt: string }>;
}

export interface QcSample {
  id: string;
  sampleNo: number;
  sampleRef: string | null;
  batchNumber: string | null;
  sampleQty: Num;
  measureValue: Num;
  result: 'passed' | 'failed' | null;
  notes: string | null;
}

export interface QcExecution {
  id: string;
  revision: number;
  result: 'passed' | 'failed';
  measureValue: Num;
  worksheetAnswers: Record<string, unknown> | null;
  evaluation: QcEvaluation | null;
  failureClassId: string | null;
  failureClassName?: string | null;
  failedQty: Num;
  pictureDocumentId: string | null;
  notes: string | null;
  executedBy: string | null;
  executedByName?: string | null;
  executedAt: string;
  superseded: boolean;
  samples: QcSample[];
}

export interface QcEvaluation {
  checkType?: QcCheckType;
  result?: string;
  passed?: boolean;
  values?: Array<{ sampleNo?: number; value: Num; passed: boolean; reason: string | null }>;
  toleranceMin?: Num;
  toleranceMax?: Num;
  norm?: Num;
  unit?: string | null;
  fields?: Array<{ key: string; label: string; visible: boolean; passed: boolean | null; reason: string | null; value?: unknown }>;
}

export type QcQuarantineStatus = 'not_required' | 'pending' | 'completed' | 'failed';

export interface QcEvent {
  id: string;
  action: string;
  fromStatus: string | null;
  toStatus: string | null;
  revision: number | null;
  reason: string | null;
  payload: Record<string, unknown> | null;
  userName?: string | null;
  createdAt: string;
}

export interface QcCheck {
  id: string;
  checkNumber: string;
  title: string;
  controlPointId: string | null;
  controlPointReference?: string | null;
  controlPointTitle?: string | null;
  controlPointVersionId: string | null;
  origin: QcOrigin;
  phase: 'pre_completion' | 'post_receipt';
  operation: QcOperation | null;
  checkType: QcCheckType;
  sourceType: QcSourceType;
  sourceId: string | null;
  sourceRef: string | null;
  productId: string | null;
  productName?: string | null;
  productCode?: string | null;
  batchNumber: string | null;
  teamId: string | null;
  teamName?: string | null;
  responsibleUserId: string | null;
  responsibleName?: string | null;
  coveredQty: Num;
  testQty: Num;
  snapshot: {
    controlPointReference?: string; controlPointVersion?: number; instructions?: string | null; failureMessage?: string | null;
    norm?: Num; toleranceMin?: Num; toleranceMax?: Num; unit?: string | null; decimals?: number | null;
    worksheet?: { templateId: string; templateKey: string; version: number; name: string; fields: WorksheetField[] };
  };
  worksheetTemplateId: string | null;
  blocking: boolean;
  blockOnFailure: boolean;
  blocksSource: boolean;
  canBlock: boolean;
  failureClassRequired: boolean;
  status: QcCheckStatus;
  currentRevision: number;
  failureClassId: string | null;
  failureClassCode?: string | null;
  failureClassName?: string | null;
  failedQty: Num;
  quarantineStatus: QcQuarantineStatus | null;
  quarantinedQty?: Num;
  quarantineError?: string | null;
  quarantineLocationId?: string | null;
  workOrderId?: string | null;
  generationKey?: string | null;
  sourceClosed: boolean;
  correctsCheckId: string | null;
  correctsCheckNumber?: string | null;
  includeInSpc: boolean;
  waiveReason: string | null;
  waiveDisposition: QcDisposition | null;
  excludedQty: Num;
  cancelReason: string | null;
  notes: string | null;
  doneAt: string | null;
  createdAt: string;
  executions?: QcExecution[];
  events?: QcEvent[];
  corrections?: Array<{ id: string; checkNumber: string; status: QcCheckStatus; createdAt: string }>;
}

export interface QcOverview {
  byStatus: Record<QcCheckStatus, number>;
  blocking: number;
  failedLast30Days: number;
  activeControlPoints: number;
  quarantineExceptions: number;
  recentFailures: QcCheck[];
  pending: QcCheck[];
}

// ---- Labels ----

export const OPERATION_LABEL: Record<QcOperation, string> = {
  receipt: tx('Receipt'), delivery: tx('Delivery'), manufacturing: tx('Manufacturing'), work_order: tx('Work order'),
};
export const CHECK_TYPE_LABEL: Record<QcCheckType, string> = {
  instructions: tx('Instructions'), pass_fail: tx('Pass / fail'), measure: tx('Measure'), picture: tx('Take a picture'),
  worksheet: tx('Worksheet'),
};
export const CHECK_STATUS_LABEL: Record<QcCheckStatus, string> = {
  pending: tx('To do'), passed: tx('Passed'), failed: tx('Failed'), waived: tx('Waived'), cancelled: tx('Cancelled'),
};
export const ORIGIN_LABEL: Record<QcOrigin, string> = {
  generated: tx('Generated'), manual: tx('Manual'), correction: tx('Correction'),
};
export const SOURCE_LABEL: Record<QcSourceType, string> = {
  production_order: tx('Production order'), work_order: tx('Work order'), goods_receipt: tx('Goods receipt'),
  sales_delivery: tx('Delivery'), manual: tx('None'),
};
export const CONTROL_PER_LABEL: Record<QcControlPer, string> = {
  operation: tx('Operation'), product: tx('Product'), quantity: tx('Quantity'),
};
export const FREQUENCY_LABEL: Record<QcFrequency, string> = {
  all: tx('All operations'), random: tx('Randomly'), periodic: tx('Periodically'),
};
export const DISPOSITION_LABEL: Record<QcDisposition, string> = {
  accepted_as_is: tx('Accepted as is'), quantity_excluded: tx('Quantity excluded'), reworked: tx('Reworked'), other: tx('Other'),
};
export const RECEIPT_MODE_LABEL = {
  pre_receipt: tx('Check before posting the receipt'), quarantine_on_receipt: tx('Receive into quarantine, then check'),
} as const;
export const SPC_SUBGROUP_LABEL: Record<string, string> = {
  individual: tx('Individual values'), consecutive: tx('Consecutive checks'), batch: tx('Batch'), work_order: tx('Work order'),
  time_window: tx('Time window'),
};
export const FIELD_TYPE_LABEL: Record<WorksheetFieldType, string> = {
  text: tx('Text'), number: tx('Number'), checkbox: tx('Checkbox'), select: tx('Choice'), date: tx('Date'), photo: tx('Photo reference'),
};

// ---- Queries ----

const KEY = ['quality-control'] as const;
type Body = Record<string, unknown>;

function useQcMutation<TVars, TResult = unknown>(fn: (vars: TVars) => Promise<TResult>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => qc.invalidateQueries({ queryKey: KEY }) });
}

export interface PageParams { page: number; pageSize: number; search?: string }

export const useQcOverview = () =>
  useQuery({ queryKey: [...KEY, 'overview'], queryFn: async () => (await api.get<QcOverview>('/quality-control/overview')).data });

export const useQcTeams = () =>
  useQuery({ queryKey: [...KEY, 'teams'], queryFn: async () => (await api.get<QcTeam[]>('/quality-control/teams')).data });

export const useFailureClasses = (active?: boolean) =>
  useQuery({
    queryKey: [...KEY, 'failure-classes', active],
    queryFn: async () => (await api.get<QcFailureClass[]>('/quality-control/failure-classes', { params: { active } })).data,
  });

export const useWorksheetTemplates = (active?: boolean) =>
  useQuery({
    queryKey: [...KEY, 'templates', active],
    queryFn: async () => (await api.get<WorksheetTemplate[]>('/quality-control/worksheet-templates', { params: { active } })).data,
  });
export const useWorksheetTemplate = (id?: string) =>
  useQuery({
    queryKey: [...KEY, 'template', id],
    enabled: !!id,
    queryFn: async () => (await api.get<WorksheetTemplate>(`/quality-control/worksheet-templates/${id}`)).data,
  });

export const useControlPoints = (p: PageParams & { operation?: string; checkType?: string; active?: boolean }) =>
  useQuery({
    queryKey: [...KEY, 'points', p],
    placeholderData: (prev) => prev,
    queryFn: async () => (await api.get<PaginatedResult<ControlPoint>>('/quality-control/control-points', { params: p })).data,
  });
export const useControlPoint = (id?: string) =>
  useQuery({
    queryKey: [...KEY, 'point', id],
    enabled: !!id,
    queryFn: async () => (await api.get<ControlPoint>(`/quality-control/control-points/${id}`)).data,
  });

export const useQcChecks = (p: PageParams & { status?: string; origin?: string; checkType?: string; sourceType?: string; sourceId?: string; controlPointId?: string; teamId?: string; quarantineStatus?: string }) =>
  useQuery({
    queryKey: [...KEY, 'checks', p],
    placeholderData: (prev) => prev,
    queryFn: async () => (await api.get<PaginatedResult<QcCheck>>('/quality-control/checks', { params: p })).data,
  });
export const useQcCheck = (id?: string) =>
  useQuery({
    queryKey: [...KEY, 'check', id],
    enabled: !!id,
    queryFn: async () => (await api.get<QcCheck>(`/quality-control/checks/${id}`)).data,
  });

// ---- Mutations ----

export const useSaveTeam = () =>
  useQcMutation(async ({ id, body }: { id?: string; body: Body }) =>
    (id ? await api.patch<QcTeam>(`/quality-control/teams/${id}`, body) : await api.post<QcTeam>('/quality-control/teams', body)).data);
export const useSaveFailureClass = () =>
  useQcMutation(async ({ id, body }: { id?: string; body: Body }) =>
    (id ? await api.patch<QcFailureClass>(`/quality-control/failure-classes/${id}`, body)
      : await api.post<QcFailureClass>('/quality-control/failure-classes', body)).data);
export const useSaveTemplate = () =>
  useQcMutation(async ({ id, body }: { id?: string; body: Body }) =>
    (id ? await api.patch<WorksheetTemplate>(`/quality-control/worksheet-templates/${id}`, body)
      : await api.post<WorksheetTemplate>('/quality-control/worksheet-templates', body)).data);
export const useSaveControlPoint = () =>
  useQcMutation(async ({ id, body }: { id?: string; body: Body }) =>
    (id ? await api.put<ControlPoint>(`/quality-control/control-points/${id}`, body)
      : await api.post<ControlPoint>('/quality-control/control-points', body)).data);
export const useSetPointActive = () =>
  useQcMutation(async ({ id, active, reason }: { id: string; active: boolean; reason?: string }) =>
    (await api.post<ControlPoint>(`/quality-control/control-points/${id}/${active ? 'activate' : 'deactivate'}`, { reason: reason || undefined })).data);

export const useCreateCheck = () =>
  useQcMutation(async (body: Body) => (await api.post<QcCheck>('/quality-control/checks', body)).data);
/** Dry run: the evaluation a submission would get; nothing is saved (no invalidation). */
export const useEvaluateCheck = () =>
  useMutation({
    mutationFn: async ({ id, body }: { id: string; body: Body }) =>
      (await api.post<{ result: 'passed' | 'failed'; evaluation: QcEvaluation; failureMessage: string | null }>(`/quality-control/checks/${id}/evaluate`, body)).data,
  });
export type CheckAction = 'execute' | 'waive' | 'reset' | 'corrections' | 'cancel';
export const useCheckAction = () =>
  useQcMutation(async ({ id, action, body }: { id: string; action: CheckAction; body: Body }) =>
    (await api.post<QcCheck>(`/quality-control/checks/${id}/${action}`, body)).data);
/** Retries a quarantine move that failed when its source completed (manage). */
export const useRetryQuarantine = () =>
  useQcMutation(async (id: string) => (await api.post<QcCheck>(`/quality-control/checks/${id}/quarantine/retry`)).data);

export const QUARANTINE_STATUS_LABEL: Record<QcQuarantineStatus, string> = {
  not_required: 'Not required', pending: 'Held in quarantine', completed: 'Done', failed: 'Move failed',
};

/** The (non-cancelled) checks of one source document, e.g. a work order on the shop-floor terminal. */
export const useSourceChecks = (sourceType: QcSourceType, sourceId?: string, enabled = true) =>
  useQuery({
    queryKey: [...KEY, 'checks', 'source', sourceType, sourceId],
    enabled: enabled && !!sourceId,
    queryFn: async () => ((await api.get<PaginatedResult<QcCheck>>('/quality-control/checks', {
      params: { sourceType, sourceId, page: 1, pageSize: 100 },
    })).data.data ?? []).filter((c) => c.status !== 'cancelled'),
  });
