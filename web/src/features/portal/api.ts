import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { PaginatedResult } from '@erp/shared';
import { api } from '@/lib/api';

// ---- Customer portal (PORTAL-1, ADR-S050): the server scopes every call to the signed-in customer ----

export interface PortalHome {
  customer: { id: string; code: string | null; name: string; email: string | null; phone: string | null };
  organization: { name: string; currencyCode: string | null };
  openBalance: string;
  liveDeliveries: number;
  openOrders: number;
}

export interface PortalOrder {
  id: string;
  orderNumber: string;
  status: string;
  openedAt: string;
  closedAt: string | null;
  totalAmount: string;
  invoiceId: string | null;
}

export interface PortalDelivery {
  id: string;
  code: string;
  status: string;
  plannedDate: string;
  loadingStartedAt: string | null;
  weighedAt: string | null;
  dispatchedAt: string | null;
  departedAt: string | null;
  deliveredAt: string | null;
  plannedTonnes: string | null;
  loadedTonnes: string | null;
  deliveredTonnes: string | null;
  deliveryNoteNumber: string | null;
  deliveryOrderCode: string;
  orderNumber: string | null;
  siteName: string | null;
  vehiclePlate: string | null;
  driverFirstName: string | null;
}

export interface PortalOrderDetail extends PortalOrder {
  subtotal: string;
  discountTotal: string;
  taxAmount: string;
  items: Array<{ id: string; description: string; quantity: string; unitPrice: string; discountAmount: string }>;
  deliveries: PortalDelivery[];
}

export interface PortalDeliveryDetail extends PortalDelivery {
  lines: Array<{
    id: string; productName: string; plannedQty: string; loadedQty: string | null; dispatchedQty: string | null;
    deliveredQty: string | null; returnedQty: string; uomCode: string | null;
  }>;
  proofOfDelivery: { receiverName: string; receivedAt: string; notes: string | null } | null;
  position: { recordedAt: string; latitude: string; longitude: string; speedKmh: string | null } | null;
}

export interface PortalInvoice {
  id: string;
  number: string;
  issueDate: string;
  dueDate: string | null;
  totalAmount: string;
  amountPaid: string;
  amountResidual: string;
  status: string;
}

export interface PortalInvoiceDetail extends PortalInvoice {
  subtotal: string | null;
  discountTotal: string | null;
  taxAmount: string | null;
  lines: Array<{ description: string; quantity: string; unitPrice: string; taxAmount: string | null; total: string | null }>;
}

export interface PortalStatement {
  from: string | null;
  to: string | null;
  openingBalance: string;
  totalDebit: string;
  totalCredit: string;
  closingBalance: string;
  lines: Array<{ date: string; entryId: string; reference: string | null; type: string; description: string | null; debit: string; credit: string; balance: string }>;
}

export interface PortalAccount {
  id: string;
  partnerId: string;
  partnerCode: string | null;
  partnerName: string;
  status: 'active' | 'disabled';
  userId: string;
  email: string;
  firstName: string;
  lastName: string | null;
  lastLoginAt?: string | null;
}

const KEY = ['portal'];

export function usePortalHome() {
  return useQuery({
    queryKey: [...KEY, 'home'],
    queryFn: async () => (await api.get<PortalHome>('/portal/home')).data,
  });
}

export function usePortalOrders(params: { page: number; pageSize: number }) {
  return useQuery({
    queryKey: [...KEY, 'orders', params],
    placeholderData: keepPreviousData,
    queryFn: async () => (await api.get<PaginatedResult<PortalOrder>>('/portal/orders', { params })).data,
  });
}

export function usePortalOrder(id: string) {
  return useQuery({
    queryKey: [...KEY, 'orders', id],
    queryFn: async () => (await api.get<PortalOrderDetail>(`/portal/orders/${id}`)).data,
  });
}

export function usePortalDeliveries(params: { scope: string; page: number; pageSize: number }) {
  return useQuery({
    queryKey: [...KEY, 'deliveries', params],
    placeholderData: keepPreviousData,
    refetchInterval: 60_000,
    queryFn: async () => (await api.get<PaginatedResult<PortalDelivery>>('/portal/deliveries', { params })).data,
  });
}

/** Live trip status: refreshed every 30 seconds while the page is open. */
export function usePortalDelivery(id: string) {
  return useQuery({
    queryKey: [...KEY, 'deliveries', id],
    refetchInterval: 30_000,
    queryFn: async () => (await api.get<PortalDeliveryDetail>(`/portal/deliveries/${id}`)).data,
  });
}

export function usePortalInvoices(params: { open?: boolean; page: number; pageSize: number }) {
  return useQuery({
    queryKey: [...KEY, 'invoices', params],
    placeholderData: keepPreviousData,
    queryFn: async () => (await api.get<PaginatedResult<PortalInvoice>>('/portal/invoices', { params })).data,
  });
}

export function usePortalInvoice(id: string) {
  return useQuery({
    queryKey: [...KEY, 'invoices', id],
    queryFn: async () => (await api.get<PortalInvoiceDetail>(`/portal/invoices/${id}`)).data,
  });
}

export function usePortalStatement(params: { from?: string; to?: string }) {
  return useQuery({
    queryKey: [...KEY, 'statement', params],
    queryFn: async () => (await api.get<PortalStatement>('/portal/statement', { params })).data,
  });
}

// ---- Staff ----

export function usePortalAccounts(params: { page: number; pageSize: number; search?: string }) {
  return useQuery({
    queryKey: [...KEY, 'accounts', params],
    placeholderData: keepPreviousData,
    queryFn: async () => (await api.get<PaginatedResult<PortalAccount>>('/portal-accounts', { params })).data,
  });
}

export function usePortalCustomers(search: string, enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, 'customers', search],
    enabled,
    queryFn: async () => (await api.get<Array<{ id: string; code: string | null; name: string; email: string | null }>>(
      '/portal-accounts/customers', { params: { search } },
    )).data,
  });
}

function useAccountMutation<I, O>(fn: (input: I) => Promise<O>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => qc.invalidateQueries({ queryKey: [...KEY, 'accounts'] }) });
}

export const useCreatePortalAccount = () =>
  useAccountMutation(async (body: { partnerId: string; email: string; password: string; firstName: string; lastName?: string }) =>
    (await api.post<PortalAccount>('/portal-accounts', body)).data);

export const useSetPortalAccountActive = () =>
  useAccountMutation(async ({ id, active }: { id: string; active: boolean }) =>
    (await api.post<PortalAccount>(`/portal-accounts/${id}/${active ? 'enable' : 'disable'}`)).data);

export const useResetPortalPassword = () =>
  useAccountMutation(async ({ id, password }: { id: string; password: string }) => {
    await api.post(`/portal-accounts/${id}/password`, { password });
  });
