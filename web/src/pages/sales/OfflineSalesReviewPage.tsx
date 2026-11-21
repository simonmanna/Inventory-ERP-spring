/**
 * ADR-S083 O5 — the offline sales review queue: orders and quotes the server priced beyond the tolerance of what the
 * rep saw, orders it could not confirm, the rep's offline order cap exceeded, and payments whose allocations could
 * not all be applied. Open the document to act on it (confirm, release, re-price, allocate), then resolve the item.
 */
import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { PERMISSIONS } from '@erp/shared';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { tx } from '@/lib/i18n/tx';
import { useAuthStore } from '@/stores/auth.store';

interface ReviewItem {
  id: string; kind: string; status: string; documentType: string; documentId: string | null; documentNumber: string | null;
  partnerName: string | null; actorName: string | null; deviceName: string | null; occurredAt: string | null;
  createdAt: string; expectedAmount: number | null; actualAmount: number | null; variancePercent: number | null;
  reason: string; resolutionNote: string | null; resolvedAt: string | null;
}

const KINDS: Record<string, string> = {
  price_variance: 'Price differs',
  confirm_refused: 'Not confirmed',
  offline_cap: 'Offline cap exceeded',
  payment_allocation: 'Payment left on account',
};

function documentLink(r: ReviewItem): string | null {
  if (!r.documentId) return null;
  if (r.documentType === 'sales_order') return `/sales/orders/${r.documentId}`;
  if (r.documentType === 'sales_quotation') return `/sales/quotations/${r.documentId}`;
  if (r.documentType === 'payment') return `/payments/${r.documentId}`;
  return null;
}

export function OfflineSalesReviewPage() {
  const qc = useQueryClient();
  const [params] = useSearchParams();
  const [kind, setKind] = useState(params.get('kind') ?? '');
  const [status, setStatus] = useState('open');
  const [notes, setNotes] = useState<Record<string, string>>({});
  const canResolve = useAuthStore((s) => s.permissions.includes(PERMISSIONS.sales.orderHoldRelease));
  const q = useQuery({
    queryKey: ['sync-sales-reviews', kind, status],
    queryFn: async (): Promise<ReviewItem[]> => (await api.get('/sync/sales-reviews', { params: { kind: kind || undefined,
      status: status || undefined } })).data,
    refetchInterval: 30_000,
  });
  const resolve = useMutation({
    mutationFn: async ({ id, note }: { id: string; note: string }) => (await api.post(`/sync/sales-reviews/${id}/resolve`, { note })).data,
    onSuccess: () => {
      toast.success(tx('Resolved'));
      void qc.invalidateQueries({ queryKey: ['sync-sales-reviews'] });
    },
    onError: (e) => toast.error(apiErrorMessage(e, tx('Failed'))),
  });
  const select = 'h-10 rounded-md border bg-background px-3';
  const money = (n: number | null) => (n == null ? '—' : n.toLocaleString(undefined, { maximumFractionDigits: 2 }));
  return (
    <div className="space-y-4 p-6">
      <div>
        <h1 className="text-2xl font-semibold">{tx('Offline sales review')}</h1>
        <p className="text-muted-foreground">{tx('Orders, quotes and payments recorded offline that need a person to look at them.')}</p>
      </div>
      <div className="flex flex-wrap gap-3">
        <div className="grid gap-1"><Label>{tx('Kind')}</Label>
          <select className={select} value={kind} onChange={(e) => setKind(e.target.value)}>
            <option value="">{tx('All')}</option>
            {Object.entries(KINDS).map(([k, v]) => <option key={k} value={k}>{tx(v)}</option>)}
          </select></div>
        <div className="grid gap-1"><Label>{tx('Status')}</Label>
          <select className={select} value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="open">{tx('Open')}</option><option value="resolved">{tx('Resolved')}</option><option value="">{tx('All')}</option>
          </select></div>
      </div>
      <Card><CardContent className="p-0 overflow-x-auto">
        {q.isLoading ? <div className="p-4"><Skeleton className="h-24 w-full" /></div> : (
          <table className="w-full text-sm">
            <thead className="bg-muted/50 text-left"><tr>
              <th className="p-2">{tx('Recorded')}</th><th className="p-2">{tx('Kind')}</th><th className="p-2">{tx('Document')}</th>
              <th className="p-2">{tx('Customer')}</th><th className="p-2">{tx('Rep / device')}</th>
              <th className="p-2 text-right">{tx('Rep saw')}</th><th className="p-2 text-right">{tx('Server')}</th>
              <th className="p-2">{tx('Why')}</th><th className="p-2">{tx('Resolution')}</th>
            </tr></thead>
            <tbody>
              {(q.data ?? []).map((r) => {
                const link = documentLink(r);
                return (
                  <tr key={r.id} className="border-t align-top">
                    <td className="p-2 whitespace-nowrap">{new Date(r.occurredAt ?? r.createdAt).toLocaleString()}</td>
                    <td className="p-2">{tx(KINDS[r.kind] ?? r.kind)}</td>
                    <td className="p-2">{link ? <Link className="underline" to={link}>{r.documentNumber ?? r.documentId}</Link> : r.documentNumber ?? '—'}</td>
                    <td className="p-2">{r.partnerName ?? '—'}</td>
                    <td className="p-2">{r.actorName ?? '—'}{r.deviceName ? ` · ${r.deviceName}` : ''}</td>
                    <td className="p-2 text-right">{money(r.expectedAmount)}</td>
                    <td className="p-2 text-right">{money(r.actualAmount)}</td>
                    <td className="p-2 max-w-sm">{r.reason}</td>
                    <td className="p-2 min-w-56">
                      {r.status === 'resolved' ? <span className="text-muted-foreground">{r.resolutionNote}</span> : canResolve ? (
                        <div className="flex gap-2">
                          <Input className="h-9" placeholder={tx('What was done')} value={notes[r.id] ?? ''}
                            onChange={(e) => setNotes((n) => ({ ...n, [r.id]: e.target.value }))} />
                          <Button type="button" size="sm" disabled={!notes[r.id]?.trim() || resolve.isPending}
                            onClick={() => resolve.mutate({ id: r.id, note: notes[r.id] })}>{tx('Resolve')}</Button>
                        </div>
                      ) : tx('Open')}
                    </td>
                  </tr>
                );
              })}
              {q.data?.length === 0 && <tr><td colSpan={9} className="p-4 text-muted-foreground">{tx('Nothing to review.')}</td></tr>}
            </tbody>
          </table>
        )}
      </CardContent></Card>
    </div>
  );
}
