import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { notifyApiError } from '@/lib/api-error';
import { idempotentPost } from '@/lib/idempotent-request';
import { notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';

export interface StockTransferItem {
  id: string;
  productId: string;
  productName: string;
  unit: string | null;
  qtyRequested: number;
  qtyTransferred: number;
  qtyDispatched?: number | string;
  qtyReceived?: number | string;
  qtyDamaged?: number | string;
  qtyShort?: number | string;
  qtyRecalled?: number | string;
  batchNumber: string | null;
  distStrategy: string | null;
}

export interface StockTransfer {
  id: string;
  transferCode: string;
  fromLocId: string;
  toLocId: string;
  status: string;
  mode?: 'immediate' | 'transit';
  notes: string | null;
  createdAt: string;
  dispatchedAt?: string | null;
  reversedAt?: string | null;
  reversalReason?: string | null;
  items: StockTransferItem[];
  receipts?: StockTransferReceipt[];
}

export function useTransfers(status?: string) {
  return useQuery<StockTransfer[]>({
    queryKey: ['inventory-transfers', status],
    queryFn: async () => {
      const params = status ? { status } : {};
      const res = await api.get<StockTransfer[]>('/inventory/transfers', { params });
      return res.data;
    },
  });
}

export function useCreateTransfer() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (data: {
      fromLocationId: string;
      toLocationId: string;
      responsibleById: string;
      approvedById: string;
      mode?: 'immediate' | 'transit';
      notes?: string;
      items: { productId: string; qtyRequested: number; distStrategy?: string }[];
    }) => {
      // A stable key per payload: a retry after a lost answer replays instead of creating a second transfer.
      return idempotentPost<StockTransfer>('/inventory/transfers', data);
    },
    onSuccess: () => {
      notify.success(tx('Transfer created'));
      qc.invalidateQueries({ queryKey: ['inventory-transfers'] });
    },
    onError: (e: unknown) => notifyApiError(e, tx('Failed to create transfer')),
  });
}

export function useApproveTransfer() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      return idempotentPost<StockTransfer>(`/inventory/transfers/${id}/approve`);
    },
    onSuccess: (d: StockTransfer) => {
      notify.success(d?.mode === 'transit' ? tx('Transfer approved — dispatch it when the goods leave') : tx('Transfer approved'));
      qc.invalidateQueries({ queryKey: ['inventory-transfers'] });
      qc.invalidateQueries({ queryKey: ['inventory-product-stock-levels'] });
      qc.invalidateQueries({ queryKey: ['inventory-stats'] });
      qc.invalidateQueries({ queryKey: ['inventory-ledger'] });
    },
    onError: (e: unknown) => notifyApiError(e, tx('Failed to approve transfer')),
  });
}

export function useReverseTransfer() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (v: { id: string; reason: string }) =>
      idempotentPost<StockTransfer>(`/inventory/transfers/${v.id}/reverse`, { reason: v.reason }),
    onSuccess: (d) => {
      notify.success(tx('{{transferCode}} reversed — stock moved back', { transferCode: d.transferCode }));
      for (const key of ['inventory-transfers', 'inventory-product-stock-levels', 'inventory-stats', 'inventory-ledger']) {
        qc.invalidateQueries({ queryKey: [key] });
      }
    },
    onError: (e: unknown) => notifyApiError(e, tx('Failed to reverse transfer')),
  });
}

// ---- Transit transfers: dispatch → receive (partial / damaged / short) → recall ----

export interface StockTransferReceipt {
  id: string;
  receiptCode: string;
  kind: 'receipt' | 'recall';
  notes: string | null;
  createdAt: string;
  lines: Array<{ itemId: string; received?: string; damaged?: string; short?: string; recalled?: string }>;
}

export interface ReceiveTransferLine {
  itemId: string;
  received?: number;
  damaged?: number;
  short?: number;
}

function invalidateTransferViews(qc: ReturnType<typeof useQueryClient>) {
  for (const key of ['inventory-transfers', 'inventory-product-stock-levels', 'inventory-stats', 'inventory-ledger']) {
    qc.invalidateQueries({ queryKey: [key] });
  }
}

/**
 * A transfer state change. Every action goes through `idempotentPost`: the key stays the same until the server answers
 * definitively, so a receipt retried after a lost answer is replayed, never booked twice.
 */
function useTransferAction<V>(fn: (v: V) => Promise<StockTransfer>, success: (d: StockTransfer) => string, failure: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: (d) => {
      notify.success(success(d));
      invalidateTransferViews(qc);
    },
    onError: (e: unknown) => notifyApiError(e, failure),
  });
}

export function useDispatchTransfer() {
  return useTransferAction(
    (id: string) => idempotentPost<StockTransfer>(`/inventory/transfers/${id}/dispatch`),
    (d) => `${d.transferCode} dispatched — stock is now in transit`,
    tx('Failed to dispatch transfer'),
  );
}

export function useReceiveTransfer() {
  return useTransferAction(
    (v: { id: string; lines: ReceiveTransferLine[]; notes?: string }) =>
      idempotentPost<StockTransfer>(`/inventory/transfers/${v.id}/receive`, { lines: v.lines, notes: v.notes }),
    (d) => (d.status === 'completed' ? `${d.transferCode} fully received` : `${d.transferCode} partially received`),
    tx('Failed to receive transfer'),
  );
}

export function useRecallTransfer() {
  return useTransferAction(
    (v: { id: string; reason: string }) =>
      idempotentPost<StockTransfer>(`/inventory/transfers/${v.id}/recall`, { reason: v.reason }),
    (d) => `${d.transferCode} recalled — in-transit stock returned to source`,
    tx('Failed to recall transfer'),
  );
}

export function useCancelTransfer() {
  return useTransferAction(
    (id: string) => idempotentPost<StockTransfer>(`/inventory/transfers/${id}/cancel`),
    (d) => `${d.transferCode} cancelled`,
    tx('Failed to cancel transfer'),
  );
}
