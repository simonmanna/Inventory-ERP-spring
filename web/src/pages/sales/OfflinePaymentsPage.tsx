/**
 * ADR-S083 O5 — payment reconciliation: what each rep recorded offline per day against what the server applied —
 * received, allocated as asked, allocated in fact, left on account, and payments refused (dead letters).
 */
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { api } from '@/lib/api';
import { tx } from '@/lib/i18n/tx';

interface Row {
  userId: string | null; userName: string | null; day: string; payments: number; voided: number; received: number;
  requestedAllocation: number; allocated: number; onAccount: number; refused: number; refusedOpen: number; refusedAmount: number;
}

const iso = (d: Date) => d.toISOString().slice(0, 10);

export function OfflinePaymentsPage() {
  const [from, setFrom] = useState(iso(new Date(Date.now() - 30 * 86_400_000)));
  const [to, setTo] = useState(iso(new Date()));
  const q = useQuery({
    queryKey: ['sync-payment-reconciliation', from, to],
    queryFn: async (): Promise<Row[]> => (await api.get('/sync/payment-reconciliation', { params: { from, to } })).data,
  });
  const money = (n: number) => n.toLocaleString(undefined, { maximumFractionDigits: 2 });
  const totals = (q.data ?? []).reduce((t, r) => ({ received: t.received + r.received, allocated: t.allocated + r.allocated,
    onAccount: t.onAccount + r.onAccount, refusedAmount: t.refusedAmount + r.refusedAmount }),
  { received: 0, allocated: 0, onAccount: 0, refusedAmount: 0 });
  return (
    <div className="space-y-4 p-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">{tx('Offline payment reconciliation')}</h1>
          <p className="text-muted-foreground">{tx('Payments reps recorded offline, per rep and day, against what the server applied.')}</p>
        </div>
        <div className="flex gap-3 text-sm">
          <Link className="underline" to="/sales/offline-review?kind=payment_allocation">{tx('Payments left on account')}</Link>
          <Link className="underline" to="/settings/devices/rejected">{tx('Refused payments')}</Link>
        </div>
      </div>
      <div className="flex flex-wrap gap-3">
        <div className="grid gap-1"><Label>{tx('From')}</Label><Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></div>
        <div className="grid gap-1"><Label>{tx('To')}</Label><Input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></div>
      </div>
      <Card><CardContent className="p-0 overflow-x-auto">
        {q.isLoading ? <div className="p-4"><Skeleton className="h-24 w-full" /></div> : (
          <table className="w-full text-sm">
            <thead className="bg-muted/50 text-left"><tr>
              <th className="p-2">{tx('Day')}</th><th className="p-2">{tx('Rep')}</th><th className="p-2 text-right">{tx('Payments')}</th>
              <th className="p-2 text-right">{tx('Received')}</th><th className="p-2 text-right">{tx('Allocation asked')}</th>
              <th className="p-2 text-right">{tx('Allocated')}</th><th className="p-2 text-right">{tx('On account')}</th>
              <th className="p-2 text-right">{tx('Refused')}</th>
            </tr></thead>
            <tbody>
              {(q.data ?? []).map((r) => (
                <tr key={`${r.userId}|${r.day}`} className="border-t">
                  <td className="p-2 whitespace-nowrap">{r.day}</td>
                  <td className="p-2">{r.userName ?? '—'}</td>
                  <td className="p-2 text-right">{r.payments}{r.voided ? ` (${tx('{{n}} voided', { n: r.voided })})` : ''}</td>
                  <td className="p-2 text-right">{money(r.received)}</td>
                  <td className="p-2 text-right">{money(r.requestedAllocation)}</td>
                  <td className="p-2 text-right">{money(r.allocated)}</td>
                  <td className={`p-2 text-right ${r.onAccount > 0 ? 'text-amber-700' : ''}`}>{money(r.onAccount)}</td>
                  <td className={`p-2 text-right ${r.refusedOpen > 0 ? 'text-red-700' : ''}`}>
                    {r.refused ? `${r.refused} · ${money(r.refusedAmount)}${r.refusedOpen ? ` (${tx('{{n}} open', { n: r.refusedOpen })})` : ''}` : '—'}
                  </td>
                </tr>
              ))}
              {q.data?.length === 0 && <tr><td colSpan={8} className="p-4 text-muted-foreground">{tx('No offline payments in this period.')}</td></tr>}
            </tbody>
            {(q.data?.length ?? 0) > 0 && (
              <tfoot className="border-t font-semibold"><tr>
                <td className="p-2" colSpan={3}>{tx('Total')}</td><td className="p-2 text-right">{money(totals.received)}</td>
                <td className="p-2" /><td className="p-2 text-right">{money(totals.allocated)}</td>
                <td className="p-2 text-right">{money(totals.onAccount)}</td><td className="p-2 text-right">{money(totals.refusedAmount)}</td>
              </tr></tfoot>
            )}
          </table>
        )}
      </CardContent></Card>
    </div>
  );
}
