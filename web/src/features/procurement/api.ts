import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { PaginatedResult } from '@erp/shared';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';

export interface SupplierPerformanceRow {
  partnerId: string;
  partnerName: string | null;
  orders: number;
  spend: string;
  receipts: number;
  onTimeRate: number | null;
  avgDaysLate: number | null;
  fillRate: number | null;
  inspectionLots: number;
  qualityRate: number | null;
  rejectedQty: string;
  priceVariance: string;
  priceVarianceRate: number | null;
  score: number | null;
}

export interface SupplierPerformance {
  from: string;
  to: string;
  rows: SupplierPerformanceRow[];
  totals: { suppliers: number; spend: string };
}

export function useSupplierPerformance(params: { from?: string; to?: string; partnerId?: string }) {
  return useQuery({
    queryKey: ['supplier-performance', params],
    queryFn: async () =>
      (await api.get<SupplierPerformance>('/procurement/reports/supplier-performance', { params })).data,
  });
}

// ---- Requests for quotation (PROC-1) ----

export type RfqStatus = 'draft' | 'sent' | 'awarded' | 'cancelled';

export const RFQ_STATUS_VARIANT: Record<RfqStatus, 'default' | 'secondary' | 'outline' | 'destructive'> = {
  draft: 'secondary',
  sent: 'default',
  awarded: 'outline',
  cancelled: 'destructive',
};

export interface RfqRow {
  id: string;
  rfqNumber: string;
  title: string;
  status: RfqStatus;
  dueDate: string | null;
  currencyCode: string;
  purchaseOrderId: string | null;
  createdAt: string;
  suppliers: number;
  quotations: number;
}

export interface RfqLine {
  id: string;
  productId: string | null;
  description: string;
  quantity: string;
  unitOfMeasureId: string | null;
  lineNumber: number;
}

export interface RfqQuotation {
  id: string;
  partnerId: string;
  partnerName: string | null;
  reference: string | null;
  currencyCode: string;
  exchangeRate: string;
  validUntil: string | null;
  leadTimeDays: number | null;
  paymentTerms: string | null;
  notes: string | null;
  lines: { rfqLineId: string; unitPrice: string; taxRate: string }[];
}

export interface RfqDetail {
  id: string;
  rfqNumber: string;
  title: string;
  status: RfqStatus;
  purchaseRequestId: string | null;
  dueDate: string | null;
  currencyCode: string;
  notes: string | null;
  awardedQuotationId: string | null;
  purchaseOrderId: string | null;
  lines: RfqLine[];
  suppliers: { partnerId: string; partnerName: string | null }[];
  quotations: RfqQuotation[];
}

export interface RfqInput {
  title: string;
  purchaseRequestId?: string;
  dueDate?: string;
  currencyCode: string;
  notes?: string;
  lines: { productId?: string; description: string; quantity: number; unitOfMeasureId?: string }[];
  supplierIds: string[];
}

export interface QuotationInput {
  partnerId: string;
  reference?: string;
  currencyCode?: string;
  exchangeRate?: number;
  validUntil?: string;
  leadTimeDays?: number;
  paymentTerms?: string;
  notes?: string;
  lines: { rfqLineId: string; unitPrice: number; taxRate?: number }[];
}

export interface RfqComparison {
  rfqId: string;
  rfqNumber: string;
  status: RfqStatus;
  lines: {
    rfqLineId: string;
    description: string;
    quantity: string;
    quotes: { quotationId: string; baseUnitPrice: string | null; lineTotal: string | null; cheapest: boolean }[];
  }[];
  quotations: {
    quotationId: string;
    partnerId: string;
    partnerName: string | null;
    currencyCode: string;
    baseTotal: string;
    linesQuoted: number;
    complete: boolean;
    expired: boolean;
    validUntil: string | null;
    leadTimeDays: number | null;
    paymentTerms: string | null;
  }[];
  recommendedQuotationId: string | null;
}

export function useRfqs(params: { page: number; pageSize: number; search?: string; status?: string }) {
  return useQuery({
    queryKey: ['rfqs', params],
    queryFn: async () => (await api.get<PaginatedResult<RfqRow>>('/procurement/rfqs', { params })).data,
  });
}

export function useRfq(id: string | undefined) {
  return useQuery({
    queryKey: ['rfq', id],
    queryFn: async () => (await api.get<RfqDetail>(`/procurement/rfqs/${id}`)).data,
    enabled: !!id,
  });
}

export function useRfqComparison(id: string | undefined, enabled: boolean) {
  return useQuery({
    queryKey: ['rfq', id, 'comparison'],
    queryFn: async () => (await api.get<RfqComparison>(`/procurement/rfqs/${id}/comparison`)).data,
    enabled: !!id && enabled,
  });
}

function useRfqMutation<V, R>(fn: (vars: V) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['rfqs'] });
      qc.invalidateQueries({ queryKey: ['rfq'] });
    },
  });
}

export function useSaveRfq() {
  return useRfqMutation(async ({ id, input }: { id?: string; input: RfqInput }) =>
    id
      ? (await api.patch<RfqDetail>(`/procurement/rfqs/${id}`, input)).data
      : (await api.post<RfqDetail>('/procurement/rfqs', input)).data,
  );
}

export function useRfqAction() {
  return useRfqMutation(async ({ id, action }: { id: string; action: 'send' | 'cancel' }) =>
    (await api.post<RfqDetail>(`/procurement/rfqs/${id}/${action}`)).data,
  );
}

export function useRecordQuotation(id: string) {
  return useRfqMutation(async (input: QuotationInput) =>
    (await api.post<RfqDetail>(`/procurement/rfqs/${id}/quotations`, input)).data,
  );
}

export function useAwardRfq(id: string) {
  return useRfqMutation((input: { quotationId: string; warehouseId: string; paymentType?: string }) =>
    idempotentPost<RfqDetail & { purchaseOrder: { id: string; orderNumber: string; status: string } }>(
      `/procurement/rfqs/${id}/award`,
      input,
    ),
  );
}
