import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { PaginatedResult } from '@erp/shared';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';
import { tx } from '@/lib/i18n/tx';

/**
 * Shop-floor execution & data capture (ADR-S015, Phase 2.7): operator terminal, live board, shift reports with
 * supervisor approval, OEE, machine data (drafts) and set-up. Output posting and draft confirmation carry an
 * Idempotency-Key (idempotentPost).
 */

const SF = '/manufacturing/shop-floor';

export type Num = string | number;
export type FloorStatus = 'running' | 'paused' | 'down' | 'idle';
export type ShiftReportStatus = 'open' | 'submitted' | 'approved';
export type DraftStatus = 'draft' | 'posted' | 'rejected';
export type DowntimeStatus = 'draft' | 'confirmed' | 'rejected';
export type ReasonCategory = 'breakdown' | 'setup' | 'material' | 'quality' | 'operator' | 'utility' | 'minor_stop' | 'planned' | 'other';

export interface Oee {
  plannedMinutes: number;
  downtimeMinutes: number;
  runMinutes: number;
  goodQty: Num;
  scrapQty: Num;
  totalQty: Num;
  idealMinutes: Num | null;
  availability: Num | null;
  performance: Num | null;
  quality: Num | null;
  oee: Num | null;
  performanceCapped: boolean;
}

export interface Shift {
  id: string;
  code: string;
  name: string;
  startMinute: number;
  endMinute: number;
  breakMinutes: number;
  isActive: boolean;
  sortOrder: number;
  lengthMinutes: number;
}

export interface DowntimeReason {
  id: string;
  code: string;
  name: string;
  category: ReasonCategory;
  isPlanned: boolean;
  isActive: boolean;
  sortOrder: number;
}

export interface WorkCenterProfile {
  workCenterId: string;
  workCenterCode: string;
  workCenterName: string;
  id: string | null;
  idealRatePerHour: Num | null;
  rateUom: string | null;
  machineCode: string | null;
  meterCodes: string[];
}

export interface Downtime {
  id: string;
  workCenterId: string;
  workOrderId: string | null;
  workOrderName: string | null;
  shiftReportId: string | null;
  reasonCode: string | null;
  reasonName: string | null;
  reasonCategory: string | null;
  isPlanned: boolean;
  startedAt: string;
  endedAt: string | null;
  minutes: number | null;
  source: 'operator' | 'machine';
  status: DowntimeStatus;
  notes: string | null;
  rejectedReason: string | null;
}

export interface ProductionReport {
  id: string;
  workCenterId: string;
  workOrderId: string | null;
  workOrderName: string | null;
  productionOrderId: string | null;
  orderCode: string | null;
  productName: string | null;
  stageId: string | null;
  stageSequence: number | null;
  stageName: string | null;
  shiftReportId: string | null;
  qtyGood: Num;
  qtyScrap: Num;
  scrapReasonCode: string | null;
  completesStage: boolean;
  batchNumber: string | null;
  periodStart: string | null;
  periodEnd: string | null;
  reportedAt: string;
  source: 'operator' | 'machine';
  status: DraftStatus;
  stagePostingId: string | null;
  notes: string | null;
  rejectedReason: string | null;
}

export interface ClockEntry {
  id: string;
  userId: string;
  userName: string | null;
  workCenterId: string;
  workOrderId: string | null;
  workOrderName: string | null;
  crewCode: string | null;
  role: string | null;
  clockOnAt: string;
  clockOffAt: string | null;
  minutes: number | null;
}

export interface MeterReading {
  id: string;
  workCenterId: string;
  meterCode: string;
  reading: Num;
  delta: Num | null;
  isReset: boolean;
  readAt: string;
  notes: string | null;
}

export interface QueueItem {
  id: string;
  name: string;
  status: 'pending' | 'in_progress' | 'paused';
  operationSequence: number;
  plannedStartAt: string | null;
  plannedDurationMins: number | null;
  startedAt: string | null;
  qtyGood: Num;
  qtyScrap: Num;
  productionOrderId: string;
  orderCode: string;
  orderStatus: string;
  plannedQty: Num;
  productName: string | null;
  productCode: string | null;
  stageId: string | null;
  stageSequence: number | null;
  stageStatus: string | null;
  qcStatus: string | null;
  qcGate: boolean | null;
  issueMode: 'backflush' | 'manual' | null;
  plannedOutputQty: Num | null;
  stageQtyGood: Num | null;
  isLast: boolean | null;
  multiStage: boolean;
}

export interface ShiftReport {
  id: string;
  code: string;
  workCenterId: string;
  workCenterCode: string;
  workCenterName: string;
  shiftId: string;
  shiftCode: string;
  shiftName: string;
  shiftDate: string;
  startsAt: string;
  endsAt: string;
  status: ShiftReportStatus;
  notes: string | null;
  submittedAt: string | null;
  approvedAt: string | null;
  approvalNotes: string | null;
  rejectedAt: string | null;
  rejectionReason: string | null;
  oee: Oee;
  pendingDrafts: number;
}

export interface ShiftReportDetail extends ShiftReport {
  liveOee: Oee;
  productionReports: ProductionReport[];
  downtime: Downtime[];
  clockEntries: ClockEntry[];
  meterReadings: MeterReading[];
  submittedByUser: { id: string; name: string } | null;
  approvedByUser: { id: string; name: string } | null;
}

export interface Queue {
  workCenter: { id: string; code: string; name: string };
  profile: WorkCenterProfile | null;
  currentShiftReport: { id: string; code: string; status: ShiftReportStatus } | null;
  workOrders: QueueItem[];
  stagesWithoutWorkOrders: Array<{ stageId: string; stageSequence: number; name: string; productionOrderId: string; orderCode: string; productName: string | null }>;
  openDowntime: Downtime[];
  operators: ClockEntry[];
  productionDrafts: ProductionReport[];
  downtimeDrafts: Downtime[];
}

export interface BoardCard {
  workCenterId: string;
  code: string;
  name: string;
  status: FloorStatus;
  workOrders: Array<{ id: string; name: string; status: string; startedAt: string | null; orderCode: string; productName: string | null }>;
  openDowntime: Downtime | null;
  machineStopUnconfirmed: boolean;
  operators: ClockEntry[];
  pendingDrafts: number;
  lastMachineEventAt: string | null;
  shift: { shiftReportId: string | null; code: string | null; status: ShiftReportStatus | null; startsAt: string; endsAt: string; oee: Oee | null } | null;
}

export interface Board {
  asOf: string;
  currentShift: { shiftId: string; shiftDate: string; startsAt: string; endsAt: string } | null;
  totals: Record<FloorStatus, number>;
  workCenters: BoardCard[];
}

export interface OeeRow extends Oee {
  shiftReportId: string;
  code: string;
  workCenterId: string;
  workCenterCode: string;
  workCenterName: string;
  shiftCode: string;
  shiftDate: string;
  status: ShiftReportStatus;
}

export interface OeeReport {
  from: string;
  to: string;
  workCenters: Array<Oee & { workCenterId: string; workCenterCode: string; workCenterName: string; shifts: number }>;
  shifts: OeeRow[];
  total: Oee & { shifts: number };
}

export interface MachineEventRow {
  id: string;
  adapter: string;
  externalId: string;
  machineCode: string;
  workCenterId: string | null;
  workCenterCode: string | null;
  eventType: string;
  occurredAt: string;
  endedAt: string | null;
  goodQty: Num | null;
  scrapQty: Num | null;
  reasonCode: string | null;
  status: 'drafted' | 'ignored' | 'error';
  draftType: string | null;
  error: string | null;
}

export interface IngestResult {
  adapter: string;
  received: number;
  created: number;
  duplicates: number;
  drafted: number;
  ignored: number;
  failed: number;
  errors: Array<{ row: number; message: string }>;
}

// ---- labels ----

export const FLOOR_STATUS_LABEL: Record<FloorStatus, string> = {
  running: tx('Running'), paused: tx('Paused'), down: tx('Down'), idle: tx('Idle'),
};
export const SHIFT_REPORT_STATUS_LABEL: Record<ShiftReportStatus, string> = {
  open: tx('Open'), submitted: tx('Submitted'), approved: tx('Approved'),
};
export const DRAFT_STATUS_LABEL: Record<DraftStatus, string> = {
  draft: tx('Draft'), posted: tx('Posted'), rejected: tx('Rejected'),
};
export const DOWNTIME_STATUS_LABEL: Record<DowntimeStatus, string> = {
  draft: tx('Draft'), confirmed: tx('Confirmed'), rejected: tx('Rejected'),
};
export const REASON_CATEGORY_LABEL: Record<ReasonCategory, string> = {
  breakdown: tx('Breakdown'), setup: tx('Set-up'), material: tx('Material'), quality: tx('Quality'),
  operator: tx('Operator'), utility: tx('Utility'), minor_stop: tx('Minor stop'), planned: tx('Planned stop'),
  other: tx('Other'),
};

// ---- hooks ----

const KEY = ['shop-floor'] as const;
type Body = Record<string, unknown>;

function useFloorMutation<TVars, TResult = unknown>(fn: (vars: TVars) => Promise<TResult>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: KEY });
      qc.invalidateQueries({ queryKey: ['order-stages'] });
      qc.invalidateQueries({ queryKey: ['production-orders'] });
    },
  });
}

function get<T>(path: string, params?: object) {
  return async () => (await api.get<T>(path, { params })).data;
}

export const useBoard = () =>
  useQuery({ queryKey: [...KEY, 'board'], queryFn: get<Board>(`${SF}/dashboard`), refetchInterval: 30_000 });
export const useQueue = (workCenterId?: string) =>
  useQuery({
    queryKey: [...KEY, 'queue', workCenterId], enabled: !!workCenterId, refetchInterval: 20_000,
    queryFn: get<Queue>(`${SF}/work-centers/${workCenterId}/queue`),
  });
export const useShifts = () => useQuery({ queryKey: [...KEY, 'shifts'], queryFn: get<Shift[]>(`${SF}/shifts`) });
export const useReasons = (includeInactive = false) =>
  useQuery({ queryKey: [...KEY, 'reasons', includeInactive], queryFn: get<DowntimeReason[]>(`${SF}/downtime-reasons`, { includeInactive }) });
export const useProfiles = () =>
  useQuery({ queryKey: [...KEY, 'profiles'], queryFn: get<WorkCenterProfile[]>(`${SF}/work-center-profiles`) });
export const useMachineAdapters = () =>
  useQuery({ queryKey: [...KEY, 'adapters'], queryFn: get<Array<{ code: string; configured: boolean; pull: boolean }>>(`${SF}/machine-adapters`) });

function useList<T>(path: string, params: object, enabled = true) {
  return useQuery({
    queryKey: [...KEY, path, params], enabled, placeholderData: (prev) => prev,
    queryFn: get<PaginatedResult<T>>(path, params),
  });
}

export interface PageParams { page: number; pageSize: number }
export const useShiftReports = (p: PageParams & { workCenterId?: string; status?: string; from?: string; to?: string }) =>
  useList<ShiftReport>(`${SF}/shift-reports`, p);
export const useShiftReport = (id?: string) =>
  useQuery({ queryKey: [...KEY, 'shift-report', id], enabled: !!id, queryFn: get<ShiftReportDetail>(`${SF}/shift-reports/${id}`) });
export const useProductionReports = (p: PageParams & { workCenterId?: string; status?: string; source?: string; shiftReportId?: string }) =>
  useList<ProductionReport>(`${SF}/production-reports`, p);
export const useDowntime = (p: PageParams & { workCenterId?: string; status?: string; source?: string; open?: boolean }) =>
  useList<Downtime>(`${SF}/downtime`, p);
export const useMeterReadings = (p: PageParams & { workCenterId?: string; meterCode?: string }, enabled = true) =>
  useList<MeterReading>(`${SF}/meter-readings`, p, enabled);
export const useMachineEvents = (p: PageParams & { workCenterId?: string; status?: string; adapter?: string }) =>
  useList<MachineEventRow>(`${SF}/machine-events`, p);
export const useOee = (p: { from?: string; to?: string; workCenterId?: string }) =>
  useQuery({ queryKey: [...KEY, 'oee', p], placeholderData: (prev) => prev, queryFn: get<OeeReport>(`${SF}/oee`, p) });

const post = async <T = unknown>(path: string, body?: unknown) => (await api.post<T>(path, body ?? {})).data;

// terminal
export const useStartWorkOrder = () => useFloorMutation((id: string) => post(`${SF}/work-orders/${id}/start`));
export const usePauseWorkOrder = () =>
  useFloorMutation(({ id, body }: { id: string; body: Body }) => post(`${SF}/work-orders/${id}/pause`, body));
export const useCompleteWorkOrder = () =>
  useFloorMutation(({ id, body }: { id: string; body: Body }) => idempotentPost(`${SF}/work-orders/${id}/complete`, body));
export const useGenerateWorkOrders = () =>
  useFloorMutation((orderId: string) => post(`/manufacturing/production-orders/${orderId}/work-orders/generate`));
export const useClockOn = () => useFloorMutation((body: Body) => post<ClockEntry>(`${SF}/clock-entries`, body));
export const useClockOff = () => useFloorMutation((id: string) => post(`${SF}/clock-entries/${id}/clock-off`));
export const useReportProduction = () =>
  useFloorMutation((body: Body) => idempotentPost<ProductionReport>(`${SF}/production-reports`, body));
export const useConfirmProduction = () =>
  useFloorMutation(({ id, body }: { id: string; body: Body }) => idempotentPost(`${SF}/production-reports/${id}/confirm`, body));
export const useRejectProduction = () =>
  useFloorMutation(({ id, reason }: { id: string; reason: string }) => post(`${SF}/production-reports/${id}/reject`, { reason }));
export const useLogDowntime = () => useFloorMutation((body: Body) => post(`${SF}/downtime`, body));
export const useEndDowntime = () => useFloorMutation((id: string) => post(`${SF}/downtime/${id}/end`));
export const useConfirmDowntime = () =>
  useFloorMutation(({ id, body }: { id: string; body: Body }) => post(`${SF}/downtime/${id}/confirm`, body));
export const useRejectDowntime = () =>
  useFloorMutation(({ id, reason }: { id: string; reason: string }) => post(`${SF}/downtime/${id}/reject`, { reason }));
export const useReadMeter = () => useFloorMutation((body: Body) => post(`${SF}/meter-readings`, body));
export const useStageIssueFromFloor = () =>
  useFloorMutation(({ stageId, body }: { stageId: string; body: Body }) =>
    idempotentPost(`/manufacturing/production-stages/${stageId}/issue`, body));

// shift reports
export const useSubmitShiftReport = () =>
  useFloorMutation(({ id, notes }: { id: string; notes?: string }) => post(`${SF}/shift-reports/${id}/submit`, { notes }));
export const useApproveShiftReport = () =>
  useFloorMutation(({ id, notes }: { id: string; notes?: string }) => post(`${SF}/shift-reports/${id}/approve`, { notes }));
export const useRejectShiftReport = () =>
  useFloorMutation(({ id, reason }: { id: string; reason: string }) => post(`${SF}/shift-reports/${id}/reject`, { reason }));
export const useOpenShiftReport = () =>
  useFloorMutation((body: Body) => post<ShiftReportDetail>(`${SF}/shift-reports`, body));

// set-up
export const useSaveShift = () =>
  useFloorMutation(({ id, body }: { id?: string; body: Body }) =>
    id ? api.patch(`${SF}/shifts/${id}`, body).then((r) => r.data) : post(`${SF}/shifts`, body));
export const useDefaultShifts = () => useFloorMutation(() => post(`${SF}/shifts/defaults`));
export const useSaveReason = () =>
  useFloorMutation(({ id, body }: { id?: string; body: Body }) =>
    id ? api.patch(`${SF}/downtime-reasons/${id}`, body).then((r) => r.data) : post(`${SF}/downtime-reasons`, body));
export const useDefaultReasons = () => useFloorMutation(() => post(`${SF}/downtime-reasons/defaults`));
export const useSaveProfile = () =>
  useFloorMutation(({ workCenterId, body }: { workCenterId: string; body: Body }) =>
    api.put(`${SF}/work-center-profiles/${workCenterId}`, body).then((r) => r.data));

// machine data
export const usePollMachines = () => useFloorMutation((body: Body) => post<IngestResult>(`${SF}/machine-data/poll`, body));
export const useImportMachineCsv = () =>
  useFloorMutation((body: { content: string; filename?: string }) => post<IngestResult>(`${SF}/machine-data/csv`, body));

// ---- formatting ----

/** 0.8571 → "85.7 %"; null → "—". */
export function pct(v: Num | null | undefined): string {
  if (v === null || v === undefined || v === '') return '—';
  const n = Number(v);
  return Number.isFinite(n) ? `${(n * 100).toFixed(1)} %` : '—';
}

export function qty(v: Num | null | undefined): string {
  if (v === null || v === undefined || v === '') return '—';
  const n = Number(v);
  return Number.isFinite(n) ? n.toLocaleString(undefined, { maximumFractionDigits: 3 }) : String(v);
}

export const hm = (m: number) => `${String(Math.floor(m / 60) % 24).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;
export const fromHm = (s: string) => {
  const [h, m] = s.split(':').map(Number);
  return (h || 0) * 60 + (m || 0);
};
export const minutesLabel = (m: number | null | undefined) =>
  m === null || m === undefined ? '—' : m >= 60 ? `${Math.floor(m / 60)} h ${m % 60} min` : `${m} min`;
