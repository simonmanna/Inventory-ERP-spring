import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { PaginatedResult } from '@erp/shared';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';
import { tx } from '@/lib/i18n/tx';

// ---- Types (fleet 1.2 — drivers & operators, assignments, check-out / check-in, allowances, ADR-S009) ----

export type DriverStatus = 'active' | 'suspended' | 'inactive';
export type DriverType = 'driver' | 'operator' | 'driver_operator';
export type DocState = 'expired' | 'expiring' | 'valid' | 'no_expiry';
export type Condition = 'good' | 'fair' | 'poor';
export type AllowanceBasis = 'per_trip' | 'per_day' | 'per_night';
export type ExpenseType = 'fuel' | 'toll' | 'parking' | 'meals' | 'lodging' | 'repairs' | 'loading' | 'other';
export type ClaimStatus = 'draft' | 'submitted' | 'approved' | 'rejected' | 'paid' | 'exported' | 'cancelled';

export interface DriverDocument {
  expiresOn: string | null;
  state: DocState;
  daysToExpiry: number | null;
}

export interface Driver {
  id: string;
  code: string;
  employeeId: string;
  driverType: DriverType;
  licenceNumber: string | null;
  licenceClasses: string[];
  licenceIssuer: string | null;
  licenceIssuedOn: string | null;
  licenceExpiresOn: string | null;
  medicalExpiresOn: string | null;
  permitNumber: string | null;
  permitExpiresOn: string | null;
  reminderLeadDays: number;
  status: DriverStatus;
  statusReason: string | null;
  notes: string | null;
  createdAt: string;
  name: string | null;
  employee: {
    id: string; employeeCode: string | null; firstName: string | null; lastName: string | null;
    phone: string | null; email: string | null; isActive: boolean; userId: string | null;
  } | null;
  documents: { licence: DriverDocument | null; medical: DriverDocument | null; permit: DriverDocument | null };
  documentState: DocState;
  qualifications: Array<{ id: string; code: string; name: string; kind: string }>;
  openCheckout: { id: string; code: string; vehicleId: string; vehicleCode: string; vehicleRegistrationPlate: string | null; checkedOutAt: string } | null;
  assignments?: Assignment[];
}

export interface Assignment {
  id: string;
  vehicleId: string;
  driverId: string;
  kind: 'primary' | 'temporary';
  startsAt: string;
  endsAt: string | null;
  status: 'active' | 'ended';
  notes: string | null;
  endReason: string | null;
  vehicleCode: string;
  vehicleRegistrationPlate: string | null;
  vehicleName: string | null;
  driverCode: string;
  driverName: string | null;
}

export interface Checkout {
  id: string;
  code: string;
  vehicleId: string;
  driverId: string;
  assignmentId: string | null;
  status: 'open' | 'closed';
  purpose: string | null;
  tripId: string | null;
  checkedOutAt: string;
  outOdometer: string | null;
  outHours: string | null;
  outFuelLevelPct: string | null;
  outCondition: Condition;
  outDamageFlags: string[];
  outNotes: string | null;
  checkedInAt: string | null;
  inOdometer: string | null;
  inHours: string | null;
  inFuelLevelPct: string | null;
  inCondition: Condition | null;
  inDamageFlags: string[];
  inNotes: string | null;
  distanceKm: string | null;
  hoursUsed: string | null;
  vehicleCode: string;
  vehicleRegistrationPlate: string | null;
  vehicleName: string | null;
  vehicleMeterType: string;
  driverCode: string;
  driverName: string | null;
  photos?: Array<{ id: string; phase: 'out' | 'in'; filename: string; url: string }>;
}

export interface DueDocument {
  driverId: string;
  driverCode: string;
  driverName: string | null;
  employeeCode: string | null;
  document: 'licence' | 'medical' | 'permit';
  number: string | null;
  expiresOn: string;
  state: DocState;
  daysToExpiry: number;
}

export interface AllowanceRule {
  id: string;
  code: string;
  name: string;
  basis: AllowanceBasis;
  amount: string;
  currencyCode: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  categoryId: string | null;
  categoryCode: string | null;
  categoryName: string | null;
  routeZone: string | null;
  isActive: boolean;
  notes: string | null;
  usage: number;
}

export interface ClaimLine {
  id: string;
  lineType: 'allowance' | 'expense';
  basis: AllowanceBasis | null;
  ruleId: string | null;
  ruleCode: string | null;
  ruleName: string | null;
  expenseType: ExpenseType | null;
  description: string | null;
  quantity: string;
  rate: string;
  amount: string;
}

export interface Claim {
  id: string;
  code: string;
  driverId: string;
  driverCode: string;
  driverName: string | null;
  vehicleId: string | null;
  vehicleCode: string | null;
  vehicleRegistrationPlate: string | null;
  tripId: string | null;
  claimDate: string;
  routeZone: string | null;
  description: string | null;
  status: ClaimStatus;
  totalAmount: string;
  currencyCode: string;
  costCenterId: string | null;
  approvalRequestId: string | null;
  submittedAt: string | null;
  decidedAt: string | null;
  decisionNotes: string | null;
  settlement: 'expenses' | 'payroll' | null;
  expenseId: string | null;
  payrollInputId: string | null;
  journalEntryId: string | null;
  settledAt: string | null;
  reversedAt: string | null;
  reversalReason: string | null;
  notes: string | null;
  createdAt: string;
  lines?: ClaimLine[];
  receipts?: Array<{ id: string; lineId: string | null; title: string | null; filename: string; url: string }>;
  costCenter?: { id: string; code: string | null; name: string } | null;
  approvalRequest?: { id: string; status: string; currentStep: number } | null;
  expense?: { id: string; expenseCode: string; status: string; paymentStatus: string; amount: string } | null;
  payrollInput?: { id: string; status: string; runId: string | null; runNumber: string | null } | null;
}

// ---- Labels ----
/** "Jane Doe" or the code. */
export const driverLabel = (d: { name?: string | null; driverName?: string | null; code?: string; driverCode?: string }) =>
  (d.name ?? d.driverName ?? '').trim() || d.code || d.driverCode || '—';


export const DRIVER_STATUS_LABEL: Record<DriverStatus, string> = {
  active: tx('Active'),
  suspended: tx('Suspended'),
  inactive: tx('Inactive'),
};

export const DRIVER_TYPE_LABEL: Record<DriverType, string> = {
  driver: tx('Driver'),
  operator: tx('Plant operator'),
  driver_operator: tx('Driver & operator'),
};

export const DOC_STATE_LABEL: Record<DocState, string> = {
  expired: tx('Expired'),
  expiring: tx('Expiring soon'),
  valid: tx('Valid'),
  no_expiry: tx('No expiry'),
};

export const DRIVER_DOC_LABEL: Record<DueDocument['document'], string> = {
  licence: tx('Driving licence'),
  medical: tx('Medical certificate'),
  permit: tx('Operator permit'),
};

export const CONDITION_LABEL: Record<Condition, string> = {
  good: tx('Good'),
  fair: tx('Fair'),
  poor: tx('Poor'),
};

export const DAMAGE_LABEL: Record<string, string> = {
  body: tx('Body'),
  glass: tx('Glass'),
  tyres: tx('Tyres'),
  lights: tx('Lights'),
  mirrors: tx('Mirrors'),
  interior: tx('Interior'),
  mechanical: tx('Mechanical'),
  leak: tx('Leak'),
  load_body: tx('Load body'),
  other: tx('Other'),
};

export const BASIS_LABEL: Record<AllowanceBasis, string> = {
  per_trip: tx('Per trip'),
  per_day: tx('Per day'),
  per_night: tx('Per night out'),
};

export const EXPENSE_TYPE_LABEL: Record<ExpenseType, string> = {
  fuel: tx('Fuel'),
  toll: tx('Toll'),
  parking: tx('Parking'),
  meals: tx('Meals'),
  lodging: tx('Lodging'),
  repairs: tx('Repairs'),
  loading: tx('Loading / offloading'),
  other: tx('Other'),
};

export const CLAIM_STATUS_LABEL: Record<ClaimStatus, string> = {
  draft: tx('Draft'),
  submitted: tx('Waiting for approval'),
  approved: tx('Approved'),
  rejected: tx('Rejected'),
  paid: tx('Paid'),
  exported: tx('Exported to payroll'),
  cancelled: tx('Cancelled'),
};

export const PAYMENT_METHOD_LABEL: Record<string, string> = {
  CASH: tx('Cash'),
  BANK_TRANSFER: tx('Bank transfer'),
  MTN_MOBILE_MONEY: tx('MTN Mobile Money'),
  AIRTEL_MONEY: tx('Airtel Money'),
  CHEQUE: tx('Cheque'),
};

// ---- Queries ----

const KEY = ['fleet'] as const;

export interface DriverListParams {
  page: number;
  pageSize: number;
  search?: string;
  status?: string;
  driverType?: string;
  categoryId?: string;
  documents?: string;
  sortBy?: string;
  sortOrder?: 'asc' | 'desc';
}

export function useDrivers(params: DriverListParams) {
  return useQuery({
    queryKey: [...KEY, 'drivers', params],
    placeholderData: (prev) => prev,
    queryFn: async () => (await api.get<PaginatedResult<Driver>>('/fleet/drivers', { params })).data,
  });
}

/** All active drivers (up to 200) for pickers. */
export function useDriverOptions(enabled = true) {
  return useQuery({
    queryKey: [...KEY, 'drivers', 'options'],
    enabled,
    staleTime: 30_000,
    queryFn: async () =>
      (await api.get<PaginatedResult<Driver>>('/fleet/drivers', { params: { status: 'active', pageSize: 200 } })).data.data,
  });
}

export function useDriver(id: string | undefined) {
  return useQuery({
    queryKey: [...KEY, 'driver', id],
    enabled: !!id,
    queryFn: async () => (await api.get<Driver>(`/fleet/drivers/${id}`)).data,
  });
}

export function useEmployeePicker(search: string) {
  return useQuery({
    queryKey: [...KEY, 'employee-picker', search],
    queryFn: async () =>
      (await api.get<Array<{ id: string; employeeCode: string | null; firstName: string | null; lastName: string | null; isActive: boolean; driverCode: string | null }>>(
        '/fleet/lookups/employees', { params: { search: search || undefined } })).data,
  });
}

export function useDriverDocuments(params: { due: string; page: number; pageSize: number }) {
  return useQuery({
    queryKey: [...KEY, 'driver-documents', params],
    placeholderData: (prev) => prev,
    queryFn: async () => (await api.get<PaginatedResult<DueDocument>>('/fleet/driver-documents', { params })).data,
  });
}

export function useEligibility(driverId: string, vehicleId: string, action: 'assigned' | 'checked out') {
  return useQuery({
    queryKey: [...KEY, 'eligibility', driverId, vehicleId, action],
    enabled: !!driverId && !!vehicleId,
    queryFn: async () =>
      (await api.get<{ eligible: boolean; reasons: string[] }>(`/fleet/drivers/${driverId}/eligibility`, {
        params: { vehicleId, action },
      })).data,
  });
}

export function useAssignments(params: { vehicleId?: string; driverId?: string; status?: string; page: number; pageSize?: number }) {
  return useQuery({
    queryKey: [...KEY, 'assignments', params],
    placeholderData: (prev) => prev,
    queryFn: async () => (await api.get<PaginatedResult<Assignment>>('/fleet/assignments', { params: { pageSize: 20, ...params } })).data,
  });
}

export function useCheckouts(params: { vehicleId?: string; driverId?: string; status?: string; page: number; pageSize?: number }) {
  return useQuery({
    queryKey: [...KEY, 'checkouts', params],
    placeholderData: (prev) => prev,
    queryFn: async () => (await api.get<PaginatedResult<Checkout>>('/fleet/checkouts', { params: { pageSize: 20, ...params } })).data,
  });
}

export function useCheckout(id: string | undefined) {
  return useQuery({
    queryKey: [...KEY, 'checkout', id],
    enabled: !!id,
    queryFn: async () => (await api.get<Checkout>(`/fleet/checkouts/${id}`)).data,
  });
}

export function useOpenCheckout(vehicleId: string | undefined) {
  return useQuery({
    queryKey: [...KEY, 'open-checkout', vehicleId],
    enabled: !!vehicleId,
    queryFn: async () => (await api.get<{ checkout: Checkout | null }>(`/fleet/vehicles/${vehicleId}/open-checkout`)).data.checkout,
  });
}

export function useAllowanceRules(includeInactive: boolean) {
  return useQuery({
    queryKey: [...KEY, 'allowance-rules', includeInactive],
    queryFn: async () => (await api.get<AllowanceRule[]>('/fleet/allowance-rules', { params: { includeInactive } })).data,
  });
}

export interface ClaimListParams {
  page: number;
  pageSize: number;
  search?: string;
  status?: string;
  driverId?: string;
  vehicleId?: string;
  settlement?: string;
  from?: string;
  to?: string;
}

export function useClaims(params: ClaimListParams) {
  return useQuery({
    queryKey: [...KEY, 'claims', params],
    placeholderData: (prev) => prev,
    queryFn: async () => (await api.get<PaginatedResult<Claim>>('/fleet/claims', { params })).data,
  });
}

export function useClaim(id: string | undefined) {
  return useQuery({
    queryKey: [...KEY, 'claim', id],
    enabled: !!id,
    queryFn: async () => (await api.get<Claim>(`/fleet/claims/${id}`)).data,
  });
}

export function usePaymentAccounts(enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, 'payment-accounts'],
    enabled,
    queryFn: async () =>
      (await api.get<Array<{ id: string; name: string; currency: string; currentBalance: number }>>('/fleet/lookups/payment-accounts')).data,
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

export const useCreateDriver = () =>
  useFleetMutation(async (body: Body) => (await api.post<Driver>('/fleet/drivers', body)).data);

export const useUpdateDriver = () =>
  useFleetMutation(async ({ id, body }: { id: string; body: Body }) => (await api.patch<Driver>(`/fleet/drivers/${id}`, body)).data);

export const useDeleteDriver = () => useFleetMutation(async (id: string) => api.delete(`/fleet/drivers/${id}`));

export const useAssign = () =>
  useFleetMutation(async (body: Body) => (await api.post<Assignment>('/fleet/assignments', body)).data);

export const useEndAssignment = () =>
  useFleetMutation(async ({ id, reason }: { id: string; reason?: string }) =>
    (await api.post<Assignment>(`/fleet/assignments/${id}/end`, { reason: reason || undefined })).data);

export const useCheckOut = () =>
  useFleetMutation(async ({ vehicleId, body }: { vehicleId: string; body: Body }) =>
    (await api.post<Checkout>(`/fleet/vehicles/${vehicleId}/check-out`, body)).data);

export const useCheckIn = () =>
  useFleetMutation(async ({ id, body }: { id: string; body: Body }) =>
    (await api.post<Checkout>(`/fleet/checkouts/${id}/check-in`, body)).data);

export const useCheckoutPhoto = () =>
  useFleetMutation(async ({ id, file, phase }: { id: string; file: File; phase: 'out' | 'in' }) => {
    const fd = new FormData();
    fd.append('file', file);
    fd.append('phase', phase);
    return (await api.post(`/fleet/checkouts/${id}/photos`, fd, { headers: { 'Content-Type': 'multipart/form-data' } })).data;
  });

export const useCreateRule = () =>
  useFleetMutation(async (body: Body) => (await api.post<AllowanceRule>('/fleet/allowance-rules', body)).data);

export const useUpdateRule = () =>
  useFleetMutation(async ({ id, body }: { id: string; body: Body }) =>
    (await api.patch<AllowanceRule>(`/fleet/allowance-rules/${id}`, body)).data);

export const useDeleteRule = () =>
  useFleetMutation(async (id: string) => (await api.delete<{ deleted: boolean }>(`/fleet/allowance-rules/${id}`)).data);

export const useCreateClaim = () =>
  useFleetMutation(async (body: Body) => (await api.post<Claim>('/fleet/claims', body)).data);

export const useUpdateClaim = () =>
  useFleetMutation(async ({ id, body }: { id: string; body: Body }) => (await api.patch<Claim>(`/fleet/claims/${id}`, body)).data);

export const useAddClaimLine = () =>
  useFleetMutation(async ({ id, body }: { id: string; body: Body }) => (await api.post<Claim>(`/fleet/claims/${id}/lines`, body)).data);

export const useRemoveClaimLine = () =>
  useFleetMutation(async ({ id, lineId }: { id: string; lineId: string }) =>
    (await api.delete<Claim>(`/fleet/claims/${id}/lines/${lineId}`)).data);

export const useClaimReceipt = () =>
  useFleetMutation(async ({ id, file, title }: { id: string; file: File; title?: string }) => {
    const fd = new FormData();
    fd.append('file', file);
    if (title) fd.append('title', title);
    return (await api.post(`/fleet/claims/${id}/receipts`, fd, { headers: { 'Content-Type': 'multipart/form-data' } })).data;
  });

/** submit / cancel / approve / reject / sync. */
export const useClaimAction = () =>
  useFleetMutation(async ({ id, action, notes }: { id: string; action: 'submit' | 'cancel' | 'approve' | 'reject' | 'sync'; notes?: string }) =>
    (await api.post<Claim>(`/fleet/claims/${id}/${action}`, action === 'approve' || action === 'reject' ? { notes: notes || undefined } : undefined)).data);

/** Money routes: a stable Idempotency-Key per attempt so a retry never pays twice. */
export const usePayClaim = () =>
  useFleetMutation(async ({ id, body }: { id: string; body: Body }) => idempotentPost<Claim>(`/fleet/claims/${id}/pay`, body));

export const useExportClaim = () =>
  useFleetMutation(async ({ id, earnedOn }: { id: string; earnedOn?: string }) =>
    idempotentPost<Claim>(`/fleet/claims/${id}/export-payroll`, { earnedOn: earnedOn || undefined }));

export const useReverseClaim = () =>
  useFleetMutation(async ({ id, reason }: { id: string; reason: string }) => idempotentPost<Claim>(`/fleet/claims/${id}/reverse`, { reason }));
