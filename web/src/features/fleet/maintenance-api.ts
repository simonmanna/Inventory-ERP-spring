import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { PaginatedResult } from '@erp/shared';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';
import { tx } from '@/lib/i18n/tx';
import type { VehicleStatus } from './api';

// ---- Types (fleet 1.6 — maintenance, inspections, downtime; ADR-S009 §5) ----

export type DueState = 'ok' | 'due' | 'overdue';
export type WorkOrderStatus = 'draft' | 'open' | 'in_progress' | 'completed' | 'closed' | 'cancelled';
export type WorkOrderType = 'preventive' | 'corrective' | 'breakdown' | 'inspection_defect';
export type Priority = 'low' | 'normal' | 'high' | 'urgent';
export type Severity = 'minor' | 'major' | 'critical';
export type DefectStatus = 'open' | 'in_repair' | 'resolved' | 'dismissed';
export type InspectionKind = 'pre_trip' | 'post_trip' | 'periodic';
export type LineType = 'part' | 'part_return' | 'labour' | 'external';

export interface UnitRef {
  id: string;
  code: string;
  name?: string | null;
  registrationPlate?: string | null;
  status?: VehicleStatus;
  categoryName?: string | null;
}

export interface ServiceTemplate {
  id: string;
  code: string;
  name: string;
  description: string | null;
  categoryId: string | null;
  intervalKm: string | null;
  intervalHours: string | null;
  intervalDays: number | null;
  leadKm: string;
  leadHours: string;
  leadDays: number;
  tasks: string[];
  isActive: boolean;
  category: { id: string; code: string; name: string } | null;
  scheduleCount: number;
}

export interface DueDimension {
  dimension: 'km' | 'hours' | 'days';
  interval: string;
  nextDue: string | null;
  nextDueOn: string | null;
  remaining: string | null;
  state: DueState;
}

export interface ServiceSchedule {
  id: string;
  vehicleId: string;
  templateId: string | null;
  name: string;
  intervalKm: string | null;
  intervalHours: string | null;
  intervalDays: number | null;
  leadKm: string;
  leadHours: string;
  leadDays: number;
  tasks: string[];
  lastDoneOn: string;
  lastDoneKm: string | null;
  lastDoneHours: string | null;
  status: 'active' | 'paused';
  lastWorkOrderId: string | null;
  notes: string | null;
  due: DueDimension[];
  dueState: DueState;
  dueTrigger: string | null;
  vehicle?: UnitRef;
  liveWorkOrder?: { code: string; status: WorkOrderStatus } | null;
}

export interface WorkOrderTask {
  id: string;
  sortOrder: number;
  description: string;
  status: 'pending' | 'done' | 'skipped';
  notes: string | null;
  doneAt: string | null;
}

export interface WorkOrderLine {
  id: string;
  lineType: LineType;
  status: 'posted' | 'reversed';
  description: string | null;
  productId: string | null;
  productCode: string | null;
  productName: string | null;
  locationName: string | null;
  quantity: string | null;
  returnedQuantity: string;
  returnOfLineId: string | null;
  employeeName: string | null;
  hours: string | null;
  workDate: string | null;
  partnerName: string | null;
  supplierReference: string | null;
  billDate: string | null;
  vendorBill: { id: string; documentNumber: string; status: string } | null;
  unitCost: string | null;
  amount: string;
  journalEntryId: string | null;
  reverseReason: string | null;
  createdAt: string;
}

export interface Defect {
  id: string;
  code: string;
  vehicleId: string;
  inspectionId: string | null;
  itemCode: string | null;
  title: string;
  description: string | null;
  severity: Severity;
  status: DefectStatus;
  workOrderId: string | null;
  reportedAt: string;
  resolvedAt: string | null;
  resolution: string | null;
  vehicle?: UnitRef;
  workOrder?: { id: string; code: string; status: WorkOrderStatus } | null;
  inspectionCode?: string | null;
  blocksDispatch?: boolean;
}

export interface Downtime {
  id: string;
  vehicleId: string;
  workOrderId: string | null;
  kind: 'planned' | 'unplanned';
  reasonCode: string;
  description: string | null;
  startedAt: string;
  endedAt: string | null;
  source: string;
  vehicle?: UnitRef;
  workOrder?: { id: string; code: string; status: WorkOrderStatus } | null;
  durationHours?: string;
}

export interface WorkOrder {
  id: string;
  code: string;
  vehicleId: string;
  type: WorkOrderType;
  priority: Priority;
  status: WorkOrderStatus;
  title: string;
  description: string | null;
  scheduleId: string | null;
  source: string;
  dueOn: string | null;
  reportedAt: string;
  startedAt: string | null;
  completedAt: string | null;
  closedAt: string | null;
  cancelledAt: string | null;
  cancelReason: string | null;
  resolution: string | null;
  completionOdometer: string | null;
  completionHours: string | null;
  assignedEmployeeId: string | null;
  partsCost: string;
  labourCost: string;
  externalCost: string;
  totalCost: string;
  vehicle: UnitRef & { meterType?: string; costCenterName?: string | null; costCenterCode?: string | null };
  schedule?: { id: string; name: string } | null;
  assignedEmployee?: Employee | null;
  tasks?: WorkOrderTask[];
  lines?: WorkOrderLine[];
  defects?: Defect[];
  downtime?: Downtime[];
  allowed?: WorkOrderStatus[];
}

export interface ChecklistItem {
  code: string;
  label: string;
  section: string | null;
  severity: Severity;
}

export interface InspectionTemplate {
  id: string;
  code: string;
  name: string;
  kind: InspectionKind;
  categoryId: string | null;
  items: ChecklistItem[];
  isActive: boolean;
  category: { id: string; name: string } | null;
}

export interface Inspection {
  id: string;
  code: string;
  vehicleId: string;
  templateId: string | null;
  kind: InspectionKind;
  inspectedAt: string;
  odometer: string | null;
  hours: string | null;
  result: 'pass' | 'fail';
  items?: Array<ChecklistItem & { result: 'pass' | 'fail' | 'na'; notes: string | null }>;
  defectCount: number;
  notes: string | null;
  vehicle?: UnitRef;
  templateName?: string | null;
  inspectorName?: string | null;
  defects?: Defect[];
  photos?: Array<{ attachmentId: string; itemCode: string | null; url: string; filename: string }>;
}

export interface Employee {
  id: string;
  employeeCode: string;
  firstName: string;
  lastName: string | null;
  hourlyRate: string | null;
}

export interface PartOption {
  id: string;
  code: string;
  sku: string | null;
  name: string;
  onHand: string;
  runningAverageCost: string | null;
}

export interface Reliability {
  periodHours: string;
  downtimeHours: string;
  unplannedHours: string;
  plannedHours: string;
  uptimeHours: string;
  failures: number;
  availabilityPct: string | null;
  mtbfHours: string | null;
  mttrHours: string | null;
}

export interface ReliabilityReport {
  from: string;
  to: string;
  groupBy: 'vehicle' | 'class';
  rows: Array<Reliability & { vehicle?: UnitRef; category?: { id: string; name: string }; units?: number }>;
  total: Reliability & { units: number };
}

export interface Eligibility {
  vehicleId: string;
  code: string;
  status: VehicleStatus;
  eligible: boolean;
  reasons: string[];
  blockingDefects: Array<{ id: string; code: string; title: string }>;
  warnings: string[];
}

// ---- Labels ----

export const DUE_STATE_LABEL: Record<DueState, string> = {
  ok: tx('OK'),
  due: tx('Due'),
  overdue: tx('Overdue'),
};

export const WO_STATUS_LABEL: Record<WorkOrderStatus, string> = {
  draft: tx('Draft'),
  open: tx('Open'),
  in_progress: tx('In progress'),
  completed: tx('Completed'),
  closed: tx('Closed'),
  cancelled: tx('Cancelled'),
};

export const WO_TYPE_LABEL: Record<WorkOrderType, string> = {
  preventive: tx('Preventive'),
  corrective: tx('Corrective'),
  breakdown: tx('Breakdown'),
  inspection_defect: tx('Inspection defect'),
};

export const PRIORITY_LABEL: Record<Priority, string> = {
  low: tx('Low'),
  normal: tx('Normal'),
  high: tx('High'),
  urgent: tx('Urgent'),
};

export const SEVERITY_LABEL: Record<Severity, string> = {
  minor: tx('Minor'),
  major: tx('Major'),
  critical: tx('Critical'),
};

export const DEFECT_STATUS_LABEL: Record<DefectStatus, string> = {
  open: tx('Open'),
  in_repair: tx('In repair'),
  resolved: tx('Resolved'),
  dismissed: tx('Dismissed'),
};

export const INSPECTION_KIND_LABEL: Record<InspectionKind, string> = {
  pre_trip: tx('Pre-trip'),
  post_trip: tx('Post-trip'),
  periodic: tx('Periodic'),
};

export const LINE_TYPE_LABEL: Record<LineType, string> = {
  part: tx('Part'),
  part_return: tx('Part returned'),
  labour: tx('Labour'),
  external: tx('External bill'),
};

export const REASON_LABEL: Record<string, string> = {
  engine: tx('Engine'),
  transmission: tx('Transmission'),
  hydraulic: tx('Hydraulics'),
  electrical: tx('Electrical'),
  brakes: tx('Brakes'),
  tyres: tx('Tyres'),
  suspension: tx('Suspension'),
  body: tx('Body'),
  accident: tx('Accident'),
  preventive_service: tx('Preventive service'),
  inspection_defect: tx('Inspection defect'),
  waiting_parts: tx('Waiting for parts'),
  operator_error: tx('Operator error'),
  other: tx('Other'),
};

export const DIMENSION_UNIT: Record<DueDimension['dimension'], string> = { km: 'km', hours: 'h', days: tx('days') };

// ---- Queries ----

const KEY = ['fleet'] as const;

export function useServiceTemplates(includeInactive = false) {
  return useQuery({
    queryKey: [...KEY, 'service-templates', includeInactive],
    queryFn: async () => (await api.get<ServiceTemplate[]>('/fleet/service-templates', { params: { includeInactive } })).data,
  });
}

export interface DueParams { page: number; pageSize: number; state?: string; vehicleId?: string; categoryId?: string; branchId?: string }

export function useDueSchedules(params: DueParams) {
  return useQuery({
    queryKey: [...KEY, 'service-due', params],
    placeholderData: (prev) => prev,
    queryFn: async () => (await api.get<PaginatedResult<ServiceSchedule>>('/fleet/service-schedules', { params })).data,
  });
}

export function useVehicleSchedules(vehicleId: string) {
  return useQuery({
    queryKey: [...KEY, 'vehicle', vehicleId, 'service-schedules'],
    queryFn: async () => (await api.get<ServiceSchedule[]>(`/fleet/vehicles/${vehicleId}/service-schedules`)).data,
  });
}

export interface WorkOrderParams {
  page: number; pageSize: number; search?: string; vehicleId?: string; status?: string; type?: string; priority?: string;
  sortBy?: string; sortOrder?: 'asc' | 'desc';
}

export function useWorkOrders(params: WorkOrderParams) {
  return useQuery({
    queryKey: [...KEY, 'work-orders', params],
    placeholderData: (prev) => prev,
    queryFn: async () => (await api.get<PaginatedResult<WorkOrder>>('/fleet/work-orders', { params })).data,
  });
}

export function useWorkOrder(id: string | undefined) {
  return useQuery({
    queryKey: [...KEY, 'work-order', id],
    enabled: !!id,
    queryFn: async () => (await api.get<WorkOrder>(`/fleet/work-orders/${id}`)).data,
  });
}

export interface DowntimeParams { page: number; pageSize: number; vehicleId?: string; kind?: string; reasonCode?: string; open?: boolean }

export function useDowntime(params: DowntimeParams) {
  return useQuery({
    queryKey: [...KEY, 'downtime', params],
    placeholderData: (prev) => prev,
    queryFn: async () => (await api.get<PaginatedResult<Downtime>>('/fleet/downtime', { params })).data,
  });
}

export function useReliability(params: { from?: string; to?: string; groupBy?: string; categoryId?: string; vehicleId?: string }) {
  return useQuery({
    queryKey: [...KEY, 'reliability', params],
    queryFn: async () => (await api.get<ReliabilityReport>('/fleet/maintenance/reliability', { params })).data,
  });
}

export function useInspectionTemplates(params: { categoryId?: string; includeInactive?: boolean } = {}) {
  return useQuery({
    queryKey: [...KEY, 'inspection-templates', params],
    queryFn: async () => (await api.get<InspectionTemplate[]>('/fleet/inspection-templates', { params })).data,
  });
}

export function useInspections(params: { page: number; pageSize: number; vehicleId?: string; result?: string }) {
  return useQuery({
    queryKey: [...KEY, 'inspections', params],
    placeholderData: (prev) => prev,
    queryFn: async () => (await api.get<PaginatedResult<Inspection>>('/fleet/inspections', { params })).data,
  });
}

export function useInspection(id: string | undefined) {
  return useQuery({
    queryKey: [...KEY, 'inspection', id],
    enabled: !!id,
    queryFn: async () => (await api.get<Inspection>(`/fleet/inspections/${id}`)).data,
  });
}

export function useDefects(params: { page: number; pageSize: number; vehicleId?: string; status?: string; severity?: string }) {
  return useQuery({
    queryKey: [...KEY, 'defects', params],
    placeholderData: (prev) => prev,
    queryFn: async () => (await api.get<PaginatedResult<Defect>>('/fleet/defects', { params })).data,
  });
}

export function useEligibility(vehicleId: string) {
  return useQuery({
    queryKey: [...KEY, 'vehicle', vehicleId, 'eligibility'],
    queryFn: async () => (await api.get<Eligibility>(`/fleet/vehicles/${vehicleId}/dispatch-eligibility`)).data,
  });
}

export function usePartPicker(search: string, locationId: string) {
  return useQuery({
    queryKey: [...KEY, 'part-picker', search, locationId],
    enabled: !!locationId,
    queryFn: async () => (await api.get<PartOption[]>('/fleet/maintenance/lookups/parts', { params: { search, locationId } })).data,
  });
}

export function useEmployeePicker(search: string) {
  return useQuery({
    queryKey: [...KEY, 'employee-picker', search],
    queryFn: async () => (await api.get<Employee[]>('/fleet/maintenance/lookups/employees', { params: { search } })).data,
  });
}

// ---- Mutations ----

function useMaintMutation<TVars, TResult = unknown>(fn: (vars: TVars) => Promise<TResult>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => qc.invalidateQueries({ queryKey: KEY }) });
}

type Body = Record<string, unknown>;

export const useSaveServiceTemplate = () =>
  useMaintMutation(async ({ id, body }: { id?: string; body: Body }) =>
    (id ? await api.patch<ServiceTemplate>(`/fleet/service-templates/${id}`, body) : await api.post<ServiceTemplate>('/fleet/service-templates', body)).data);
export const useDeleteServiceTemplate = () => useMaintMutation(async (id: string) => api.delete(`/fleet/service-templates/${id}`));
export const useApplyServiceTemplate = () =>
  useMaintMutation(async ({ id, body }: { id: string; body: Body }) =>
    (await api.post<{ created: number; skipped: number }>(`/fleet/service-templates/${id}/apply`, body)).data);

export const useCreateSchedule = () =>
  useMaintMutation(async ({ vehicleId, body }: { vehicleId: string; body: Body }) =>
    (await api.post<ServiceSchedule>(`/fleet/vehicles/${vehicleId}/service-schedules`, body)).data);
export const useUpdateSchedule = () =>
  useMaintMutation(async ({ id, body }: { id: string; body: Body }) => (await api.patch<ServiceSchedule>(`/fleet/service-schedules/${id}`, body)).data);
export const useDeleteSchedule = () => useMaintMutation(async (id: string) => api.delete(`/fleet/service-schedules/${id}`));
export const useGenerateOne = () =>
  useMaintMutation(async (scheduleId: string) => (await api.post<WorkOrder>(`/fleet/service-schedules/${scheduleId}/work-order`)).data);
export const useGenerateDue = () =>
  useMaintMutation(async () => (await api.post<{ created: number; skipped: number; workOrders: string[] }>('/fleet/service-schedules/generate')).data);

export const useCreateWorkOrder = () => useMaintMutation(async (body: Body) => (await api.post<WorkOrder>('/fleet/work-orders', body)).data);
export const useUpdateWorkOrder = () =>
  useMaintMutation(async ({ id, body }: { id: string; body: Body }) => (await api.patch<WorkOrder>(`/fleet/work-orders/${id}`, body)).data);
export const useWorkOrderAction = () =>
  useMaintMutation(async ({ id, action, body }: { id: string; action: 'open' | 'start' | 'complete' | 'close' | 'cancel'; body?: Body }) =>
    (await api.post<WorkOrder>(`/fleet/work-orders/${id}/${action}`, body)).data);
export const useAddTask = () =>
  useMaintMutation(async ({ id, description }: { id: string; description: string }) =>
    (await api.post<WorkOrder>(`/fleet/work-orders/${id}/tasks`, { description })).data);
export const useUpdateTask = () =>
  useMaintMutation(async ({ id, taskId, body }: { id: string; taskId: string; body: Body }) =>
    (await api.patch<WorkOrder>(`/fleet/work-orders/${id}/tasks/${taskId}`, body)).data);
export const useDeleteTask = () =>
  useMaintMutation(async ({ id, taskId }: { id: string; taskId: string }) => (await api.delete<WorkOrder>(`/fleet/work-orders/${id}/tasks/${taskId}`)).data);

/** Cost lines move money: Idempotency-Key kept until the server answers definitively. */
export const useCostLine = () =>
  useMaintMutation(async ({ id, kind, body }: { id: string; kind: 'parts' | 'labour' | 'bills'; body: Body }) =>
    idempotentPost<WorkOrder>(`/fleet/work-orders/${id}/${kind}`, body));
export const useLineAction = () =>
  useMaintMutation(async ({ id, lineId, action, body }: { id: string; lineId: string; action: 'return' | 'reverse'; body: Body }) =>
    idempotentPost<WorkOrder>(`/fleet/work-orders/${id}/lines/${lineId}/${action}`, body));

export const useStartDowntime = () => useMaintMutation(async (body: Body) => (await api.post<Downtime>('/fleet/downtime', body)).data);
export const useEndDowntime = () =>
  useMaintMutation(async ({ id, body }: { id: string; body?: Body }) => (await api.post<Downtime>(`/fleet/downtime/${id}/end`, body ?? {})).data);

export const useSaveInspectionTemplate = () =>
  useMaintMutation(async ({ id, body }: { id?: string; body: Body }) =>
    (id ? await api.patch<InspectionTemplate>(`/fleet/inspection-templates/${id}`, body) : await api.post<InspectionTemplate>('/fleet/inspection-templates', body)).data);
export const useDeleteInspectionTemplate = () => useMaintMutation(async (id: string) => api.delete(`/fleet/inspection-templates/${id}`));
export const useRecordInspection = () => useMaintMutation(async (body: Body) => (await api.post<Inspection>('/fleet/inspections', body)).data);
export const useInspectionPhoto = () =>
  useMaintMutation(async ({ id, file, itemCode }: { id: string; file: File; itemCode?: string }) => {
    const fd = new FormData();
    fd.append('file', file);
    if (itemCode) fd.append('itemCode', itemCode);
    return (await api.post<Inspection>(`/fleet/inspections/${id}/photos`, fd, { headers: { 'Content-Type': 'multipart/form-data' } })).data;
  });
export const useReportDefect = () => useMaintMutation(async (body: Body) => (await api.post<Defect>('/fleet/defects', body)).data);
export const useDefectWorkOrder = () =>
  useMaintMutation(async ({ id, body }: { id: string; body?: Body }) => (await api.post<Defect>(`/fleet/defects/${id}/work-order`, body ?? {})).data);
export const useCloseDefect = () =>
  useMaintMutation(async ({ id, body }: { id: string; body: Body }) => (await api.post<Defect>(`/fleet/defects/${id}/close`, body)).data);
