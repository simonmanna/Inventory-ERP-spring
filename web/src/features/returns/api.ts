import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { PaginatedResult } from '@erp/shared';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';
import { tx } from '@/lib/i18n/tx';

/** Café/restaurant = sale has menu-item lines; retail = product lines only. */
export type ReturnChannel = 'retail' | 'restaurant';
export type StockDisposition = 'restock' | 'waste' | 'no_return';

type Money = string | number;
interface PartnerRef { id: string; name: string }

export interface SalesReturn {
  id: string;
  returnNumber: string;
  invoiceId: string;
  invoiceNumber: string;
  invoiceDate: string;
  amount: Money;
  reason: string;
  stockDisposition: StockDisposition;
  channel: ReturnChannel;
  itemCount: number;
  partner: PartnerRef | null;
  cashSessionId: string | null;
  journalEntryId: string | null;
  createdByName: string | null;
  approvedByName: string | null;
  createdAt: string;
}

export interface SalesReturnLine {
  lineId: string;
  description: string | null;
  variantName: string | null;
  productId: string | null;
  menuItemId: string | null;
  unitPrice: Money | null;
  quantity: Money;
  subtotal: Money;
  taxAmount: Money;
  total: Money;
}

export interface SalesReturnPayment {
  paymentId: string;
  paymentNumber: string | null;
  paymentMethod: string | null;
  amount: Money;
  originalPaymentNumber: string | null;
}

export interface SalesReturnDetail extends SalesReturn {
  invoiceTotal: Money;
  invoiceRefunded: Money;
  invoiceStatus: string;
  cashRegisterName: string | null;
  journalEntryNumber: string | null;
  lines: SalesReturnLine[];
  payments: SalesReturnPayment[];
}

export interface ReturnsListParams {
  page?: number;
  pageSize?: number;
  search?: string;
  stockDisposition?: string;
  channel?: string;
  dateFrom?: string;
  dateTo?: string;
}

export type ReturnsPage = PaginatedResult<SalesReturn> & { summary: { count: number; amount: Money } };

export interface ReturnableInvoiceSummary {
  id: string;
  invoiceNumber: string;
  issueDate: string;
  totalAmount: Money;
  amountRefunded: Money;
  paymentMode: string | null;
  channel: ReturnChannel;
  partner: PartnerRef | null;
}

export interface ReturnableLine {
  id: string;
  description: string;
  variantName: string | null;
  productId: string | null;
  menuItemId: string | null;
  quantity: Money;
  refundedQty: Money;
  remaining: Money;
  unitPrice: Money;
  unitRefund: Money;
  total: Money;
}

export interface ReturnableInvoice {
  id: string;
  invoiceNumber: string;
  issueDate: string;
  status: string;
  settlementStatus: string;
  totalAmount: Money;
  amountPaid: Money;
  amountResidual: Money;
  amountRefunded: Money;
  paymentMode: string | null;
  postingReady: boolean;
  channel: ReturnChannel;
  partner: PartnerRef | null;
  lines: ReturnableLine[];
  tenders: Array<{ paymentMethod: string; amount: Money; refundedAmount: Money }>;
  returns: Array<{ id: string; returnNumber: string; amount: Money; reason: string; stockDisposition: StockDisposition; createdAt: string }>;
}

export interface OpenRegister {
  id: string;
  cashRegisterId: string;
  cashRegisterName: string;
  userId: string;
  userName: string | null;
  openedAt: string;
}

export interface CreateReturnBody {
  invoiceId: string;
  reason: string;
  stockDisposition: StockDisposition;
  overrideById: string;
  overridePin?: string;
  cashSessionId?: string;
  lines: Array<{ lineId: string; quantity: number }>;
}

export interface CreateReturnResult {
  invoiceId: string;
  refundId: string;
  status: 'refunded' | 'partially_refunded';
  amount: Money;
  amountReturned: Money;
}

export const CHANNEL_LABEL: Record<ReturnChannel, string> = { retail: tx('Retail'), restaurant: tx('Café / Restaurant') };
export const DISPOSITION_LABEL: Record<StockDisposition, string> = {
  restock: tx('Restocked'),
  waste: tx('Wasted / consumed'),
  no_return: tx('Not returned'),
};

export function useReturns(params: ReturnsListParams) {
  return useQuery({
    queryKey: ['returns', params],
    queryFn: async () => (await api.get<ReturnsPage>('/returns', { params })).data,
  });
}

export function useReturn(id: string | undefined) {
  return useQuery({
    queryKey: ['return', id],
    queryFn: async () => (await api.get<SalesReturnDetail>(`/returns/${id}`)).data,
    enabled: !!id,
  });
}

export function useReturnableInvoices(search: string) {
  return useQuery({
    queryKey: ['returns', 'invoices', search],
    queryFn: async () => (await api.get<ReturnableInvoiceSummary[]>('/returns/invoices', { params: { search: search || undefined } })).data,
  });
}

export function useReturnableInvoice(invoiceId: string | undefined) {
  return useQuery({
    queryKey: ['returns', 'invoice', invoiceId],
    queryFn: async () => (await api.get<ReturnableInvoice>(`/returns/invoices/${invoiceId}`)).data,
    enabled: !!invoiceId,
  });
}

export function useOpenRegisters() {
  return useQuery({
    queryKey: ['returns', 'registers'],
    queryFn: async () => (await api.get<OpenRegister[]>('/returns/registers')).data,
  });
}

/** Records the return through the POS refund engine (GL reversal, payout over original tenders, stock). */
export function useCreateReturn() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ invoiceId, ...body }: CreateReturnBody) =>
      idempotentPost<CreateReturnResult>(`/pos/invoices/${invoiceId}/refund`, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['returns'] });
      qc.invalidateQueries({ queryKey: ['receipts'] });
      qc.invalidateQueries({ queryKey: ['pos-reports'] });
      qc.invalidateQueries({ queryKey: ['cash-session'] });
    },
  });
}
