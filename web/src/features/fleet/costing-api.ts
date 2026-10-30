import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { PaginatedResult } from '@erp/shared';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';
import { tx } from '@/lib/i18n/tx';

// ---- Types (fleet 1.9 — costing, profitability, reports, freight bills, reminders; ADR-S009 §6 / §9) ----

type Dec = string | null;

export interface ReportEnvelope<R> {
  from: string;
  to: string;
  groupBy: string;
  rows: R[];
  totals: R;
  allocationBasis?: string;
}

export interface CostRow {
  key: string;
  label: string;
  vehicleId?: string;
  vehicleCode?: string;
  registrationPlate?: string | null;
  categoryName?: string;
  branchName?: string | null;
  vehicles: number;
  depreciation: string;
  fuel: string;
  maintenance: string;
  tyres: string;
  insurance: string;
  compliance: string;
  incidents: string;
  allowances: string;
  fines: string;
  total: string;
  running: string;
  km: Dec;
  hours: Dec;
  tonnes: Dec;
  trips: number;
  perKm: Dec;
  perHour: Dec;
  perTonne: Dec;
  perTrip: Dec;
}

export interface ProfitRow {
  key: string;
  label: string;
  trips: number;
  tonnes: Dec;
  revenue: string;
  transportCharge: string;
  productCost: string;
  tripCost: string;
  margin: string;
  marginPct: Dec;
  tripId?: string;
  day?: string;
  carrier?: 'own' | 'transporter';
  vehicle?: string | null;
  transporter?: string | null;
  deliveryOrderId?: string;
  deliveryOrder?: string;
  customer?: string;
  site?: string | null;
  costBasis?: string;
  productCostEstimated?: boolean;
}

export interface DeliveryRow {
  key: string;
  label: string;
  trips: number;
  orders: number;
  onTime: number;
  late: number;
  onTimePct: Dec;
  tonnes: Dec;
  km: Dec;
  returnedTrips?: number;
}

export interface TonnesRow {
  key: string;
  label: string;
  trips: number;
  tonnes: Dec;
  quantity?: Dec;
  tonnesPerTrip?: Dec;
}

export interface TransportCostRow {
  key: string;
  label: string;
  trips: number;
  tonnes: Dec;
  km: Dec;
  transportCost: string;
  costPerTonne: Dec;
  costPerKm: Dec;
  transportCharge: string;
  recoveryPct: Dec;
}

export interface UtilisationRow {
  key: string;
  label: string;
  vehicleId?: string;
  categoryName?: string;
  status?: string;
  units: number;
  periodDays: number;
  activeDays: number;
  utilisationPct: Dec;
  availabilityPct: Dec;
  periodHours: string;
  downtimeHours: string;
  trips: number;
  km: Dec;
  hours: Dec;
  tonnes: Dec;
}

export interface UtilisationReport extends ReportEnvelope<UtilisationRow> {
  downtimeByReason: Array<{ reason: string; events: number; hours: string }>;
}

export interface FuelRow {
  key: string;
  label: string;
  logs: number;
  litres: Dec;
  stationLitres: Dec;
  tankLitres: Dec;
  cost: string;
  km: Dec;
  hours: Dec;
  lPer100Km: Dec;
  lPerHour: Dec;
  costPerKm: Dec;
  costPerLitre: Dec;
  openAnomalies: number;
}

export type RenewalKind = 'compliance' | 'driver' | 'tare' | 'service';

export interface RenewalRow {
  key: string;
  kind: RenewalKind;
  item: string;
  label: string;
  vehicleId: string | null;
  driverId: string | null;
  reference: string | null;
  dueOn: string | null;
  daysLeft: number | null;
  state: 'expired' | 'expiring' | 'overdue' | 'due';
  trigger?: string;
  link: string;
}

export interface RenewalReport {
  asOf: string;
  days: number;
  kind: string;
  rows: RenewalRow[];
  counts: Record<'expired' | 'overdue' | 'expiring' | 'due', number>;
}

export interface FleetKpis {
  from: string;
  to: string;
  deliveries: number;
  onTimePct: Dec;
  tonnes: Dec;
  transportCost: string;
  transportCostPerTonne: Dec;
  deliveryMargin: string;
  deliveryMarginPct: Dec;
  availabilityPct: Dec;
  utilisationPct: Dec;
  fuelCost: string;
  fuelLitres: Dec;
  totalCostOfOwnership: string;
  costPerKm: Dec;
  renewalsExpired: number;
  renewalsExpiring: number;
  servicesOverdue: number;
  servicesDue: number;
  freightBillsToMatch: number;
  unbilledTransporterTrips: number;
}

export type FreightStatus = 'draft' | 'disputed' | 'billed' | 'cancelled';

export interface FreightBill {
  id: string;
  code: string;
  direction: 'outbound' | 'inbound';
  transporterPartnerId: string;
  periodFrom: string | null;
  periodTo: string | null;
  status: FreightStatus;
  tripCount: number;
  totalAmount: string;
  currencyCode: string | null;
  supplierReference: string | null;
  supplierInvoiceDate: string | null;
  supplierAmount: Dec;
  varianceAmount: Dec;
  variancePct: Dec;
  allowedVariance: Dec;
  varianceAccepted: boolean;
  vendorBillId: string | null;
  vendorBillNumber: string | null;
  journalEntryId: string | null;
  reversalEntryId: string | null;
  goodsReceiptId: string | null;
  landedCostId: string | null;
  matchedAt: string | null;
  cancelledAt: string | null;
  cancelReason: string | null;
  notes: string | null;
  createdAt: string;
  transporter: { id: string; name: string; code?: string } | null;
}

export interface FreightBillLine {
  id: string;
  tripId: string | null;
  tripCode: string | null;
  deliveryOrderId: string | null;
  deliveryOrderCode: string | null;
  customerName: string | null;
  description: string;
  freightBasis: string | null;
  freightRate: Dec;
  tonnes: Dec;
  distanceKm: Dec;
  amount: string;
  status: 'active' | 'cancelled';
  closedAt: string | null;
}

export interface FreightBillDetail extends FreightBill {
  lines: FreightBillLine[];
  vendorBill: { id: string; documentNumber: string; status: string; totalAmount: string; paymentStatus: string; reference: string | null } | null;
  journalEntries: Array<{ id: string; entryNumber: string; postingDate: string; description: string; status: string }>;
  landedCost: { id: string; code: string; status: string } | null;
  tolerance: { pct: string; amount: string };
}

export interface FreightCandidates {
  from: string;
  to: string;
  transporters: Array<{
    transporterPartnerId: string;
    transporterName: string;
    total: string;
    trips: Array<{ id: string; code: string; deliveryOrderCode: string; freightAmount: string; freightBasis: string | null; tonnes: Dec; distanceKm: Dec; closedAt: string }>;
  }>;
}

export interface FreightLookups {
  transporters: Array<{ id: string; code: string | null; name: string }>;
  goodsReceipts: Array<{ id: string; receiptNumber: string; receivedAt: string | null; partnerName: string | null }>;
  tolerance: { pct: string; amount: string };
}

export interface ReminderLog {
  id: string;
  itemType: 'compliance_document' | 'driver_document' | 'vehicle_tare' | 'service_due';
  itemId: string;
  reminderDate: string;
  state: string;
  title: string;
  body: string | null;
  dueOn: string | null;
  vehicleId: string | null;
  vehicleCode: string | null;
  driverId: string | null;
  driverCode: string | null;
  createdAt: string;
}

// ---- Labels ----

export const FREIGHT_STATUS_LABEL: Record<FreightStatus, string> = {
  draft: tx('Draft'),
  disputed: tx('Disputed'),
  billed: tx('Billed'),
  cancelled: tx('Cancelled'),
};

export const REMINDER_TYPE_LABEL: Record<ReminderLog['itemType'], string> = {
  compliance_document: tx('Vehicle document'),
  driver_document: tx('Driver document'),
  vehicle_tare: tx('Stored tare'),
  service_due: tx('Service due'),
};

export const RENEWAL_KIND_LABEL: Record<RenewalKind, string> = {
  compliance: tx('Vehicle document'),
  driver: tx('Driver document'),
  tare: tx('Stored tare'),
  service: tx('Service'),
};

export const RENEWAL_STATE_LABEL: Record<RenewalRow['state'], string> = {
  expired: tx('Expired'),
  expiring: tx('Expiring'),
  overdue: tx('Overdue'),
  due: tx('Due'),
};

export const COST_BASIS_LABEL: Record<string, string> = {
  km: tx('per km'),
  tonne: tx('per tonne'),
  trip: tx('per trip'),
  none: tx('no cost data'),
  freight: tx('trip freight'),
  freight_bill: tx('freight bill'),
};

// ---- Queries ----

const KEY = ['fleet', 'costing'] as const;
type Params = Record<string, string | number | undefined>;
const clean = (p: Params) => Object.fromEntries(Object.entries(p).filter(([, v]) => v !== undefined && v !== ''));

function useReport<T>(path: string, params: Params, enabled = true) {
  return useQuery({
    queryKey: [...KEY, path, params],
    queryFn: async () => (await api.get<T>(`/fleet/reports/${path}`, { params: clean(params) })).data,
    enabled,
  });
}

export const useFleetKpis = (enabled = true) => useReport<FleetKpis>('kpis', {}, enabled);
export const useVehicleCosts = (p: Params) => useReport<ReportEnvelope<CostRow>>('vehicle-costs', p);
export const useProfitability = (p: Params) => useReport<ReportEnvelope<ProfitRow>>('delivery-profitability', p);
export const useDeliveriesReport = (p: Params) => useReport<ReportEnvelope<DeliveryRow>>('deliveries', p);
export const useTonnesReport = (p: Params) => useReport<ReportEnvelope<TonnesRow>>('tonnes', p);
export const useTransportCost = (p: Params) => useReport<ReportEnvelope<TransportCostRow>>('transport-cost', p);
export const useUtilisation = (p: Params) => useReport<UtilisationReport>('utilisation', p);
export const useFuelReport = (p: Params) => useReport<ReportEnvelope<FuelRow>>('fuel', p);
export const useRenewals = (p: Params) => useReport<RenewalReport>('renewals', p);

export function useVehicleCostSummary(vehicleId: string | undefined, p: Params) {
  return useQuery({
    queryKey: [...KEY, 'vehicle', vehicleId, p],
    queryFn: async () => (await api.get<ReportEnvelope<CostRow>>(`/fleet/vehicles/${vehicleId}/cost-summary`, { params: clean(p) })).data,
    enabled: !!vehicleId,
  });
}

/** Download a report as CSV / XLSX / PDF (server-rendered with the same filters). */
export async function downloadReport(report: string, format: 'csv' | 'xlsx' | 'pdf', params: Params) {
  const res = await api.get<Blob>(`/fleet/reports/${report}/export`, { params: { ...clean(params), format }, responseType: 'blob' });
  const disposition = String(res.headers?.['content-disposition'] ?? '');
  const match = /filename="([^"]+)"/.exec(disposition);
  const url = URL.createObjectURL(res.data);
  const a = document.createElement('a');
  a.href = url;
  a.download = match?.[1] ?? `fleet-${report}.${format}`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export function useFreightBills(p: Params) {
  return useQuery({
    queryKey: [...KEY, 'freight-bills', p],
    queryFn: async () => (await api.get<PaginatedResult<FreightBill>>('/fleet/freight-bills', { params: clean(p) })).data,
  });
}

export function useFreightBill(id: string | undefined) {
  return useQuery({
    queryKey: [...KEY, 'freight-bill', id],
    queryFn: async () => (await api.get<FreightBillDetail>(`/fleet/freight-bills/${id}`)).data,
    enabled: !!id,
  });
}

export function useFreightCandidates(p: Params, enabled = true) {
  return useQuery({
    queryKey: [...KEY, 'freight-candidates', p],
    queryFn: async () => (await api.get<FreightCandidates>('/fleet/freight-bills/candidates', { params: clean(p) })).data,
    enabled,
  });
}

export function useFreightLookups(search: string, enabled = true) {
  return useQuery({
    queryKey: [...KEY, 'freight-lookups', search],
    queryFn: async () => (await api.get<FreightLookups>('/fleet/freight-bills/lookups', { params: clean({ search }) })).data,
    enabled,
  });
}

export function useReminderLog(p: Params) {
  return useQuery({
    queryKey: [...KEY, 'reminders', p],
    queryFn: async () => (await api.get<PaginatedResult<ReminderLog>>('/fleet/reminders', { params: clean(p) })).data,
  });
}

// ---- Mutations ----

function useCostingMutation<TVars, TResult = unknown>(fn: (vars: TVars) => Promise<TResult>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => qc.invalidateQueries({ queryKey: KEY }),
  });
}

type Body = Record<string, unknown>;

export const useGenerateFreightBills = () =>
  useCostingMutation(async (body: Body) => (await api.post<{ created: FreightBill[]; trips: number }>('/fleet/freight-bills/generate', body)).data);

/** Match the supplier invoice: AP bill + accrual — Idempotency-Key. */
export const useMatchFreightBill = () =>
  useCostingMutation(async ({ id, body }: { id: string; body: Body }) => idempotentPost<FreightBillDetail>(`/fleet/freight-bills/${id}/match`, body));

/** Cancel (reverses a billed accrual) — Idempotency-Key. */
export const useCancelFreightBill = () =>
  useCostingMutation(async ({ id, reason }: { id: string; reason: string }) => idempotentPost<FreightBillDetail>(`/fleet/freight-bills/${id}/cancel`, { reason }));

export const useRemoveFreightLine = () =>
  useCostingMutation(async ({ id, lineId }: { id: string; lineId: string }) => (await api.delete<FreightBillDetail>(`/fleet/freight-bills/${id}/lines/${lineId}`)).data);

/** Inbound freight on a posted goods receipt → landed cost — Idempotency-Key. */
export const useInboundFreight = () =>
  useCostingMutation(async (body: Body) => idempotentPost<FreightBillDetail>('/fleet/freight-bills/inbound', body));

export const useRunReminders = () =>
  useCostingMutation(async () => (await api.post<{ sent: Record<string, number>; skipped: number; items: number }>('/fleet/reminders/run', {})).data);
