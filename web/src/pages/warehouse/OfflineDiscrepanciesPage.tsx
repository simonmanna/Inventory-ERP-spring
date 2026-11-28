/**
 * ADR-S083 O4 / S4 — the inventory discrepancy report: every movement recorded offline that took stock below zero,
 * was held for a supervisor, or waits for its dispatch. Filter by branch, device, location, kind and status;
 * resolve in the Posting Monitor (an open one in a period blocks its close).
 */
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Card, CardContent } from '@/components/ui/card';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { api } from '@/lib/api';
import { tx } from '@/lib/i18n/tx';

interface Discrepancy {
  id: string; kind: string; status: string; description: string; reason: string | null; quantity: number | null;
  locationName: string | null; productName: string | null; createdAt: string; resolvedAt: string | null;
  resolutionNote: string | null; opType: string | null; deviceId: string | null; branchId: string | null;
  occurredAt: string | null; documentType: string | null; documentCode: string | null; onHandAfter: number | null;
}
interface Device { id: string; name: string }

const KINDS: Record<string, string> = {
  offline_negative_stock: 'Below zero',
  offline_held_posting: 'Held for a supervisor',
  offline_awaiting_dispatch: 'Waiting for dispatch',
};

export function OfflineDiscrepanciesPage() {
  const [kind, setKind] = useState('');
  const [status, setStatus] = useState('open');
  const [deviceId, setDeviceId] = useState('');
  const devices = useQuery({ queryKey: ['pos-devices'], queryFn: async (): Promise<Device[]> => (await api.get('/sync/devices')).data });
  const q = useQuery({
    queryKey: ['sync-stock-discrepancies', kind, status, deviceId],
    queryFn: async (): Promise<Discrepancy[]> => (await api.get('/sync/stock-discrepancies', { params: {
      kind: kind || undefined, status: status || undefined, deviceId: deviceId || undefined } })).data,
    refetchInterval: 30_000,
  });
  const deviceName = (id: string | null) => devices.data?.find((d) => d.id === id)?.name ?? (id ? id.slice(0, 8) : '—');
  const select = 'h-10 rounded-md border bg-background px-3';
  return (
    <div className="space-y-4 p-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">{tx('Offline stock discrepancies')}</h1>
          <p className="text-muted-foreground">{tx('Stock work recorded offline that went below zero, was held, or waits for its dispatch.')}</p>
        </div>
        <Link className="text-sm underline" to="/inventory/posting-monitor">{tx('Resolve in the Posting Monitor')}</Link>
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
        <div className="grid gap-1"><Label>{tx('Device')}</Label>
          <select className={select} value={deviceId} onChange={(e) => setDeviceId(e.target.value)}>
            <option value="">{tx('All')}</option>
            {devices.data?.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
          </select></div>
      </div>
      <Card><CardContent className="p-0 overflow-x-auto">
        {q.isLoading ? <div className="p-4"><Skeleton className="h-24 w-full" /></div> : (
          <table className="w-full text-sm">
            <thead className="bg-muted/50 text-left"><tr>
              <th className="p-2">{tx('When')}</th><th className="p-2">{tx('Kind')}</th><th className="p-2">{tx('Document')}</th>
              <th className="p-2">{tx('Location / item')}</th><th className="p-2">{tx('Why')}</th><th className="p-2">{tx('Device')}</th>
              <th className="p-2">{tx('Status')}</th>
            </tr></thead>
            <tbody>
              {(q.data ?? []).map((d) => (
                <tr key={d.id} className="border-t align-top">
                  <td className="p-2 whitespace-nowrap">{new Date(d.occurredAt ?? d.createdAt).toLocaleString()}</td>
                  <td className="p-2">{tx(KINDS[d.kind] ?? d.kind)}</td>
                  <td className="p-2">{d.documentCode ?? d.documentType ?? '—'}</td>
                  <td className="p-2">{d.locationName ?? '—'}{d.productName ? ` · ${d.productName}` : ''}
                    {d.onHandAfter != null ? ` (${tx('on hand {{n}}', { n: d.onHandAfter })})` : ''}</td>
                  <td className="p-2 max-w-sm">{d.reason ?? d.description}</td>
                  <td className="p-2">{deviceName(d.deviceId)}</td>
                  <td className="p-2">{d.status}{d.resolutionNote ? ` — ${d.resolutionNote}` : ''}</td>
                </tr>
              ))}
              {q.data?.length === 0 && <tr><td colSpan={7} className="p-4 text-muted-foreground">{tx('Nothing to show.')}</td></tr>}
            </tbody>
          </table>
        )}
      </CardContent></Card>
    </div>
  );
}
