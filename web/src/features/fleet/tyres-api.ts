import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { PaginatedResult } from '@erp/shared';
import { api } from '@/lib/api';
import { tx } from '@/lib/i18n/tx';

// ---- Types (fleet 1.7 — tyres, incidents & claims, traffic fines; ADR-S009) ----

export type TyreStatus = 'in_stock' | 'fitted' | 'removed' | 'retreaded' | 'scrapped';
export type TyreType = 'steer' | 'drive' | 'trailer' | 'all_position' | 'offroad' | 'other';

export interface TyreStats {
  totalCost: string;
  km: string;
  hours: string;
  currentStintKm: string;
  costPerKm: string | null;
  costPerHour: string | null;
  treadUsedMm: string | null;
  wearPer1000Km: string | null;
  projectedLifeKm: string | null;
  remainingTreadPct: string | null;
  belowMinimum: boolean;
}

export interface Tyre {
  id: string;
  serialNumber: string;
  brand: string | null;
  model: string | null;
  size: string | null;
  tyreType: TyreType;
  purchaseCost: string | null;
  extraCost: string;
  supplierPartnerId: string | null;
  purchasedOn: string | null;
  invoiceReference: string | null;
  productId: string | null;
  stockLocationId: string | null;
  stockIssuedAt: string | null;
  stockLedgerCode: string | null;
  stockIssueValue: string | null;
  status: TyreStatus;
  vehicleId: string | null;
  position: string | null;
  fittedAt: string | null;
  kmRun: string;
  newTreadDepthMm: string | null;
  treadDepthMm: string | null;
  treadMeasuredAt: string | null;
  minTreadDepthMm: string;
  retreadCount: number;
  scrapReason: string | null;
  notes: string | null;
  vehicle: { id: string; code: string; registrationPlate: string | null } | null;
  stats: TyreStats;
  supplier?: { id: string; code: string | null; name: string } | null;
  product?: { id: string; sku: string | null; name: string } | null;
  stockLocation?: { id: string; code: string | null; name: string } | null;
  events?: TyreEvent[];
  readings?: TyreReading[];
}

export interface TyreEvent {
  id: string;
  eventType: 'registered' | 'fitted' | 'rotated' | 'removed' | 'retreaded' | 'scrapped';
  vehicleId: string | null;
  vehicleCode: string | null;
  fromPosition: string | null;
  toPosition: string | null;
  eventAt: string;
  odometer: string | null;
  odometerLifetime: string | null;
  kmRun: string | null;
  treadDepthMm: string | null;
  cost: string | null;
  reason: string | null;
  notes: string | null;
}

export interface TyreReading {
  id: string;
  readingAt: string;
  treadDepthMm: string;
  pressureKpa: string | null;
  position: string | null;
  notes: string | null;
}

export interface WheelPosition {
  code: string;
  axle: number;
  side: 'left' | 'right' | 'spare';
  slot: 'single' | 'inner' | 'outer' | 'spare';
  spare: boolean;
  tyre: Tyre | null;
}

export interface VehicleTyres {
  vehicle: { id: string; code: string; registrationPlate: string | null; axleConfig: string | null; status: string };
  axleConfig: string | null;
  axles: Array<{ number: number; steer: boolean; dual: boolean; positions: WheelPosition[] }>;
  spares: WheelPosition[];
  unplaced: Tyre[];
  fittedCount: number;
  positionCount: number;
}

export type IncidentStatus = 'reported' | 'investigating' | 'repairing' | 'closed' | 'cancelled';
export type ClaimStatus = 'draft' | 'submitted' | 'approved' | 'rejected' | 'settled' | 'withdrawn';
export type FineStatus = 'open' | 'disputed' | 'charged' | 'settled' | 'cancelled';
export type DeductionStatus = 'pending' | 'exported' | 'settled' | 'cancelled';

export interface EmployeeRef {
  id: string;
  employeeCode: string;
  name: string;
}

export interface ThirdParty {
  name: string;
  phone?: string | null;
  vehiclePlate?: string | null;
  insurer?: string | null;
  policyNumber?: string | null;
  notes?: string | null;
}

export interface InsuranceClaim {
  id: string;
  incidentId: string;
  claimNumber: string | null;
  insurerPartnerId: string | null;
  insurerName: string | null;
  policyNumber: string | null;
  submittedOn: string | null;
  amountClaimed: string;
  amountApproved: string | null;
  amountReceived: string;
  excessAmount: string | null;
  status: ClaimStatus;
  statusReason: string | null;
  decidedOn: string | null;
  settledOn: string | null;
  notes: string | null;
  insurer?: string | null;
  incident?: { id: string; incidentNumber: string; occurredAt: string };
  vehicle?: { id: string; code: string; registrationPlate: string | null };
}

export interface Incident {
  id: string;
  incidentNumber: string;
  vehicleId: string;
  driverEmployeeId: string | null;
  occurredAt: string;
  location: string | null;
  latitude: string | null;
  longitude: string | null;
  incidentType: string;
  severity: 'minor' | 'major' | 'critical';
  description: string;
  damageDescription: string | null;
  estimatedCost: string | null;
  atFault: 'own' | 'third_party' | 'shared' | 'unknown';
  injuries: string | null;
  thirdParties: ThirdParty[];
  policeReportNumber: string | null;
  policeStation: string | null;
  status: IncidentStatus;
  statusReason: string | null;
  workOrderId: string | null;
  repairReference: string | null;
  repairCost: string | null;
  closedAt: string | null;
  notes: string | null;
  vehicle: { id: string; code: string; registrationPlate: string | null; status?: string };
  driver: EmployeeRef | null;
  claimCount?: number;
  claims?: InsuranceClaim[];
  attachments?: Array<{ id: string; fileId: string; kind: 'photo' | 'document'; title: string | null; filename: string; contentType: string; url: string; createdAt: string }>;
  fines?: Array<{ id: string; fineNumber: string; offence: string; amount: string; status: FineStatus; chargedTo: string | null }>;
}

export interface FineDeduction {
  id: string;
  fineId: string;
  employeeId: string;
  amount: string;
  status: DeductionStatus;
  requestedAt: string;
  exportedAt: string | null;
  exportReference: string | null;
  settledAt: string | null;
  settledReference: string | null;
  cancelledAt: string | null;
  cancelReason: string | null;
  fine?: { id: string; fineNumber: string; offence: string; offenceAt: string };
  vehicle?: { code: string; registrationPlate: string | null };
  employee?: EmployeeRef;
}

export interface Fine {
  id: string;
  fineNumber: string;
  vehicleId: string;
  driverEmployeeId: string | null;
  incidentId: string | null;
  offenceAt: string;
  location: string | null;
  offence: string;
  offenceCode: string | null;
  authority: string | null;
  noticeNumber: string | null;
  amount: string;
  dueDate: string | null;
  status: FineStatus;
  statusReason: string | null;
  chargedTo: 'company' | 'driver' | null;
  chargedAt: string | null;
  chargeCount: number;
  expenseId: string | null;
  deductionId: string | null;
  settledAt: string | null;
  notes: string | null;
  vehicle: { id: string; code: string; registrationPlate: string | null };
  driver: EmployeeRef | null;
  expense: { id: string; expenseCode: string; status: string; paymentStatus: string } | null;
  deductionStatus: DeductionStatus | null;
  overdue: boolean;
  incident?: { id: string; incidentNumber: string; occurredAt: string; status: string } | null;
  deductions?: FineDeduction[];
}

// ---- Labels ----

export const TYRE_STATUS_LABEL: Record<TyreStatus, string> = {
  in_stock: tx('In stock'),
  fitted: tx('Fitted'),
  removed: tx('Removed'),
  retreaded: tx('Retreaded'),
  scrapped: tx('Scrapped'),
};

export const TYRE_TYPE_LABEL: Record<TyreType, string> = {
  steer: tx('Steer'),
  drive: tx('Drive'),
  trailer: tx('Trailer'),
  all_position: tx('All position'),
  offroad: tx('Off-road'),
  other: tx('Other'),
};

export const TYRE_EVENT_LABEL: Record<TyreEvent['eventType'], string> = {
  registered: tx('Registered'),
  fitted: tx('Fitted'),
  rotated: tx('Rotated'),
  removed: tx('Removed'),
  retreaded: tx('Retreaded'),
  scrapped: tx('Scrapped'),
};

export const INCIDENT_STATUS_LABEL: Record<IncidentStatus, string> = {
  reported: tx('Reported'),
  investigating: tx('Investigating'),
  repairing: tx('Repairing'),
  closed: tx('Closed'),
  cancelled: tx('Cancelled'),
};

export const INCIDENT_TYPE_LABEL: Record<string, string> = {
  collision: tx('Collision'),
  rollover: tx('Rollover'),
  theft: tx('Theft'),
  vandalism: tx('Vandalism'),
  fire: tx('Fire'),
  injury: tx('Injury'),
  property_damage: tx('Property damage'),
  load_spill: tx('Load spill'),
  other: tx('Other'),
};

export const SEVERITY_LABEL: Record<Incident['severity'], string> = {
  minor: tx('Minor'),
  major: tx('Major'),
  critical: tx('Critical'),
};

export const AT_FAULT_LABEL: Record<Incident['atFault'], string> = {
  own: tx('Our driver'),
  third_party: tx('Third party'),
  shared: tx('Shared'),
  unknown: tx('Unknown'),
};

export const CLAIM_STATUS_LABEL: Record<ClaimStatus, string> = {
  draft: tx('Draft'),
  submitted: tx('Submitted'),
  approved: tx('Approved'),
  rejected: tx('Rejected'),
  settled: tx('Settled'),
  withdrawn: tx('Withdrawn'),
};

export const FINE_STATUS_LABEL: Record<FineStatus, string> = {
  open: tx('Open'),
  disputed: tx('Disputed'),
  charged: tx('Charged'),
  settled: tx('Settled'),
  cancelled: tx('Cancelled'),
};

export const DEDUCTION_STATUS_LABEL: Record<DeductionStatus, string> = {
  pending: tx('Pending'),
  exported: tx('Exported to payroll'),
  settled: tx('Settled'),
  cancelled: tx('Cancelled'),
};

/** "A2LO" → "Axle 2 · left outer", "SP1" → "Spare 1". */
export function positionLabel(code: string | null | undefined): string {
  if (!code) return '—';
  const spare = /^SP(\d+)$/.exec(code);
  if (spare) return tx('Spare {{n}}', { n: spare[1] });
  const m = /^A(\d+)(L|R)(O|I)?$/.exec(code);
  if (!m) return code;
  const side = m[2] === 'L' ? tx('left') : tx('right');
  const slot = m[3] === 'O' ? ` ${tx('outer')}` : m[3] === 'I' ? ` ${tx('inner')}` : '';
  return `${tx('Axle {{n}}', { n: m[1] })} · ${side}${slot}`;
}

// ---- Queries ----

const KEY = ['fleet'] as const;

export interface TyreListParams {
  page: number;
  pageSize: number;
  search?: string;
  status?: string;
  vehicleId?: string;
  tyreType?: string;
  sortBy?: string;
  sortOrder?: 'asc' | 'desc';
}

export function useTyres(params: TyreListParams, enabled = true) {
  return useQuery({
    queryKey: [...KEY, 'tyres', params],
    enabled,
    placeholderData: (prev) => prev,
    queryFn: async () => (await api.get<PaginatedResult<Tyre>>('/fleet/tyres', { params })).data,
  });
}

export function useTyre(id: string | undefined) {
  return useQuery({
    queryKey: [...KEY, 'tyre', id],
    enabled: !!id,
    queryFn: async () => (await api.get<Tyre>(`/fleet/tyres/${id}`)).data,
  });
}

export function useVehicleTyres(vehicleId: string, enabled = true) {
  return useQuery({
    queryKey: [...KEY, 'vehicle', vehicleId, 'tyres'],
    enabled,
    queryFn: async () => (await api.get<VehicleTyres>(`/fleet/vehicles/${vehicleId}/tyres`)).data,
  });
}

export function useTyreProducts(search: string, enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, 'tyre-products', search],
    enabled,
    queryFn: async () =>
      (await api.get<Array<{ id: string; sku: string | null; name: string }>>('/fleet/tyre-lookups/products', {
        params: { search },
      })).data,
  });
}

export function useEmployeePicker(search: string, enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, 'employee-picker', search],
    enabled,
    queryFn: async () =>
      (await api.get<Array<EmployeeRef & { status: string }>>('/fleet/incident-lookups/employees', {
        params: { search },
      })).data,
  });
}

export interface IncidentListParams {
  page: number;
  pageSize: number;
  search?: string;
  status?: string;
  vehicleId?: string;
  incidentType?: string;
  from?: string;
  to?: string;
}

export function useIncidents(params: IncidentListParams) {
  return useQuery({
    queryKey: [...KEY, 'incidents', params],
    placeholderData: (prev) => prev,
    queryFn: async () => (await api.get<PaginatedResult<Incident>>('/fleet/incidents', { params })).data,
  });
}

export function useIncident(id: string | undefined) {
  return useQuery({
    queryKey: [...KEY, 'incident', id],
    enabled: !!id,
    queryFn: async () => (await api.get<Incident>(`/fleet/incidents/${id}`)).data,
  });
}

export function useClaims(params: { page: number; pageSize: number; status?: string; search?: string }) {
  return useQuery({
    queryKey: [...KEY, 'claims', params],
    placeholderData: (prev) => prev,
    queryFn: async () => (await api.get<PaginatedResult<InsuranceClaim>>('/fleet/insurance-claims', { params })).data,
  });
}

export interface FineListParams {
  page: number;
  pageSize: number;
  search?: string;
  status?: string;
  vehicleId?: string;
  chargedTo?: string;
  overdue?: boolean;
}

export function useFines(params: FineListParams) {
  return useQuery({
    queryKey: [...KEY, 'fines', params],
    placeholderData: (prev) => prev,
    queryFn: async () => (await api.get<PaginatedResult<Fine>>('/fleet/fines', { params })).data,
  });
}

export function useFine(id: string | undefined) {
  return useQuery({
    queryKey: [...KEY, 'fine', id],
    enabled: !!id,
    queryFn: async () => (await api.get<Fine>(`/fleet/fines/${id}`)).data,
  });
}

export function useDeductions(params: { page: number; pageSize: number; status?: string }) {
  return useQuery({
    queryKey: [...KEY, 'fine-deductions', params],
    placeholderData: (prev) => prev,
    queryFn: async () => (await api.get<PaginatedResult<FineDeduction>>('/fleet/fine-deductions', { params })).data,
  });
}

// ---- Mutations ----

function useFleetMutation<TVars, TResult = unknown>(fn: (vars: TVars) => Promise<TResult>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => qc.invalidateQueries({ queryKey: KEY }),
  });
}

type Body = Record<string, unknown>;

export const useCreateTyre = () => useFleetMutation(async (body: Body) => (await api.post<Tyre>('/fleet/tyres', body)).data);
export const useUpdateTyre = () =>
  useFleetMutation(async ({ id, body }: { id: string; body: Body }) => (await api.patch<Tyre>(`/fleet/tyres/${id}`, body)).data);
export const useDeleteTyre = () => useFleetMutation(async (id: string) => api.delete(`/fleet/tyres/${id}`));

/** Lifecycle action of a tyre: fit (money route, idempotency key added by the api client), rotate, remove, ... */
export const useTyreAction = () =>
  useFleetMutation(async ({ id, action, body }: { id: string; action: 'fit' | 'rotate' | 'remove' | 'retread' | 'scrap' | 'readings'; body: Body }) =>
    (await api.post<Tyre>(`/fleet/tyres/${id}/${action}`, body)).data);

export const useCreateIncident = () =>
  useFleetMutation(async (body: Body) => (await api.post<Incident>('/fleet/incidents', body)).data);
export const useUpdateIncident = () =>
  useFleetMutation(async ({ id, body }: { id: string; body: Body }) => (await api.patch<Incident>(`/fleet/incidents/${id}`, body)).data);
export const useIncidentStatus = () =>
  useFleetMutation(async ({ id, status, reason }: { id: string; status: IncidentStatus; reason?: string }) =>
    (await api.post<Incident>(`/fleet/incidents/${id}/status`, { status, reason: reason || undefined })).data);
export const useUploadIncidentFile = () =>
  useFleetMutation(async ({ id, file, kind }: { id: string; file: File; kind: 'photo' | 'document' }) => {
    const fd = new FormData();
    fd.append('file', file);
    fd.append('kind', kind);
    return (await api.post(`/fleet/incidents/${id}/attachments`, fd, { headers: { 'Content-Type': 'multipart/form-data' } })).data;
  });
export const useDeleteIncidentFile = () =>
  useFleetMutation(async ({ id, attachmentId }: { id: string; attachmentId: string }) =>
    api.delete(`/fleet/incidents/${id}/attachments/${attachmentId}`));
export const useCreateClaim = () =>
  useFleetMutation(async ({ incidentId, body }: { incidentId: string; body: Body }) =>
    (await api.post<InsuranceClaim>(`/fleet/incidents/${incidentId}/claims`, body)).data);
export const useClaimStatus = () =>
  useFleetMutation(async ({ id, body }: { id: string; body: Body }) =>
    (await api.post<InsuranceClaim>(`/fleet/insurance-claims/${id}/status`, body)).data);

export const useCreateFine = () => useFleetMutation(async (body: Body) => (await api.post<Fine>('/fleet/fines', body)).data);
export const useUpdateFine = () =>
  useFleetMutation(async ({ id, body }: { id: string; body: Body }) => (await api.patch<Fine>(`/fleet/fines/${id}`, body)).data);
export const useFineStatus = () =>
  useFleetMutation(async ({ id, status, reason }: { id: string; status: 'open' | 'disputed' | 'cancelled'; reason?: string }) =>
    (await api.post<Fine>(`/fleet/fines/${id}/status`, { status, reason: reason || undefined })).data);
export const useChargeFine = () =>
  useFleetMutation(async ({ id, body }: { id: string; body: Body }) => (await api.post<Fine>(`/fleet/fines/${id}/charge`, body)).data);
export const useReverseFineCharge = () =>
  useFleetMutation(async ({ id, reason }: { id: string; reason: string }) =>
    (await api.post<Fine>(`/fleet/fines/${id}/reverse-charge`, { reason })).data);
export const useDeductionStatus = () =>
  useFleetMutation(async ({ id, status, reference }: { id: string; status: 'exported' | 'settled'; reference?: string }) =>
    (await api.post<FineDeduction>(`/fleet/fine-deductions/${id}/status`, { status, reference: reference || undefined })).data);

/** Cash / bank accounts an expense can be paid from (expenses module). */
export function usePaymentAccounts(enabled: boolean) {
  return useQuery({
    queryKey: ['expenses', 'meta', 'accounts'],
    enabled,
    staleTime: 60_000,
    queryFn: async () => (await api.get<Array<{ id: string; name: string; currentBalance: number }>>('/expenses/meta/accounts')).data,
  });
}
