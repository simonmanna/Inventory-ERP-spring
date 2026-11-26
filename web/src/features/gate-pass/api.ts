import { useMutation, useQuery, useQueryClient, keepPreviousData } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { tx } from '@/lib/i18n/tx';

/** Documents a gate pass can be issued for (ADR-S085). */
export type GatePassSourceType =
  | 'delivery' | 'sales_order' | 'invoice' | 'cash_sale' | 'stock_transfer' | 'pick_order' | 'rental' | 'repair' | 'supplier_return';

export type GatePassState = 'issued' | 'used' | 'void' | 'expired';
export type BearerType = 'driver' | 'person' | 'courier';
export type GatePassWarning = 'goods_not_issued' | 'unpaid' | 'partially_paid';

export const SOURCE_LABELS: Record<GatePassSourceType, string> = {
  delivery: tx('Delivery'),
  sales_order: tx('Sales order'),
  invoice: tx('Invoice'),
  cash_sale: tx('Cash sale'),
  stock_transfer: tx('Stock transfer'),
  pick_order: tx('Pick order'),
  rental: tx('Rental agreement'),
  repair: tx('Repair order'),
  supplier_return: tx('Supplier return'),
};

export const WARNING_LABELS: Record<string, string> = {
  goods_not_issued: tx('Goods not yet issued from stock — check with stores before release'),
  unpaid: tx('Not paid — check with the cashier before release'),
  partially_paid: tx('Partially paid — check with the cashier before release'),
};

export const PROBLEM_LABELS: Record<string, string> = {
  void: tx('This gate pass is void'),
  already_used: tx('This gate pass was already used'),
  expired: tx('This gate pass has expired'),
  source_cancelled: tx('The source document was cancelled — the goods may not leave'),
  source_missing: tx('The source document no longer exists'),
  goods_not_issued: tx('The goods have not been issued from stock yet — dispatch the delivery first'),
  source_quantity_changed: tx('The source quantities changed after this pass was issued — void it and issue a new one'),
};

export const BEARER_LABELS: Record<BearerType, string> = {
  driver: tx('Driver with vehicle'),
  person: tx('Person on foot'),
  courier: tx('Courier / transporter'),
};

export interface GatePassLine {
  id: string;
  lineNo: number;
  sourceLineId: string | null;
  productId: string | null;
  description: string;
  quantity: number;
  unit: string | null;
  sourceQuantity: number | null;
}

export interface GatePassEvent {
  id: string;
  type: 'issued' | 'printed' | 'gate_out' | 'refused' | 'voided';
  at: string;
  byName: string | null;
  note: string | null;
  data: Record<string, unknown> | null;
}

export interface SourceSummary {
  type: GatePassSourceType;
  id: string;
  label: string;
  number: string | null;
  branchId: string | null;
  partnerName: string | null;
  status: string | null;
  cancelled: boolean;
  goodsIssued: boolean;
  paymentState: 'paid' | 'partial' | 'unpaid' | 'not_applicable';
}

export interface GatePass {
  id: string;
  number: string;
  sourceType: GatePassSourceType;
  sourceId: string;
  sourceNumber: string | null;
  partnerName: string | null;
  branchId: string | null;
  status: 'issued' | 'used' | 'void';
  state: GatePassState;
  expired: boolean;
  bearerType: BearerType;
  bearerName: string;
  bearerIdNumber: string | null;
  bearerPhone: string | null;
  vehiclePlate: string | null;
  vehicleType: string | null;
  transportCompany: string | null;
  destination: string | null;
  purpose: string | null;
  remarks: string | null;
  goodsIssued: boolean;
  paymentState: string;
  warnings: GatePassWarning[];
  validUntil: string;
  printCount: number;
  issuedAt: string;
  gateOutAt: string | null;
  gateOutPlate: string | null;
  gateOutNote: string | null;
  voidedAt: string | null;
  voidReason: string | null;
  lineCount?: number;
}

export interface GatePassDetail extends GatePass {
  lines: GatePassLine[];
  events: GatePassEvent[];
  source: SourceSummary | null;
  problems: string[];
  canLetOut: boolean;
  liveWarnings: GatePassWarning[];
  codeVerified?: boolean;
  sourceUpdate?: string;
}

export interface SourceLine {
  sourceLineId: string;
  productId: string | null;
  description: string;
  unit: string | null;
  sourceQuantity: number;
  onPasses: number;
  remaining: number;
}

export interface SourceView {
  source: SourceSummary;
  warnings: GatePassWarning[];
  prefill: {
    bearerType: BearerType;
    bearerName: string | null;
    bearerPhone: string | null;
    bearerIdNumber: string | null;
    vehiclePlate: string | null;
    vehicleType: string | null;
    transportCompany: string | null;
    destination: string | null;
  };
  lines: SourceLine[];
  passes: GatePass[];
}

export interface IssueBody {
  sourceType: GatePassSourceType;
  sourceId: string;
  bearerType: BearerType;
  bearerName: string;
  bearerIdNumber?: string | null;
  bearerPhone?: string | null;
  vehiclePlate?: string | null;
  vehicleType?: string | null;
  transportCompany?: string | null;
  destination?: string | null;
  purpose?: string | null;
  remarks?: string | null;
  validHours?: number;
  lines?: Array<{ sourceLineId: string; quantity: number }>;
}

export interface GatePassPage {
  data: GatePass[];
  meta: { page: number; pageSize: number; total: number; totalPages: number };
}

const KEY = ['gate-pass'];

export function useGatePassSource(type: GatePassSourceType, id: string | null | undefined, enabled = true) {
  return useQuery({
    queryKey: [...KEY, 'source', type, id],
    queryFn: async () => (await api.get<SourceView>(`/gate-passes/source/${type}/${id}`)).data,
    enabled: !!id && enabled,
  });
}

export function useGatePasses(params: Record<string, string | number | undefined>) {
  return useQuery({
    queryKey: [...KEY, 'list', params],
    queryFn: async () => (await api.get<GatePassPage>('/gate-passes', {
      params: Object.fromEntries(Object.entries(params).filter(([, v]) => v !== undefined && v !== '')),
    })).data,
    placeholderData: keepPreviousData,
  });
}

export function useGatePass(id: string | null | undefined) {
  return useQuery({
    queryKey: [...KEY, 'one', id],
    queryFn: async () => (await api.get<GatePassDetail>(`/gate-passes/${id}`)).data,
    enabled: !!id,
  });
}

export const gatePassApi = {
  verify: async (code: string) => (await api.get<GatePassDetail>('/gate-passes/verify', { params: { code } })).data,
  issue: async (body: IssueBody) => (await api.post<GatePassDetail>('/gate-passes', body)).data,
  gateOut: async ({ id, ...body }: { id: string; verifyCode?: string; plate?: string; note?: string }) =>
    (await api.post<GatePassDetail>(`/gate-passes/${id}/gate-out`, body)).data,
  refuse: async ({ id, reason }: { id: string; reason: string }) =>
    (await api.post<GatePassDetail>(`/gate-passes/${id}/refuse`, { reason })).data,
  void: async ({ id, reason }: { id: string; reason: string }) =>
    (await api.post<GatePassDetail>(`/gate-passes/${id}/void`, { reason })).data,
};

/** A mutation that refreshes every gate pass query afterwards. */
export function useGatePassMutation<TIn, TOut>(fn: (input: TIn) => Promise<TOut>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => qc.invalidateQueries({ queryKey: KEY }) });
}
