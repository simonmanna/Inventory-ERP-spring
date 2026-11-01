import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import {
  useAllocationCycles,
  useAllocationPreview,
  useAllocationRuns,
  useCreateAllocationCycle,
  useReverseAllocationRun,
  useRunAllocation,
  type AllocationRun,
} from '@/features/costing/cost-management-api';
import { api } from '@/lib/api';
import { money } from '@/lib/format';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';

const SELECT = 'h-9 rounded-md border border-input bg-background px-3 text-sm';
const today = new Date().toISOString().slice(0, 10);
const monthStart = `${today.slice(0, 8)}01`;
const BASES = ['equal', 'posted_cost', 'revenue', 'tonnage', 'distance_km', 'tonne_km', 'statistical_key', 'labour_hours', 'machine_hours', 'operating_hours', 'headcount', 'floor_area'];

interface CostCentre { id: string; code: string; name: string }
interface StatKey { id: string; code: string; name: string }

/** Cost-centre allocation cycles: financial (posted) or statistical (reports only) — ADR-S033. */
export function CostAllocationPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const canManage = has(PERMISSIONS.costAllocation.manage);
  const canPost = has(PERMISSIONS.costAllocation.post);
  const { data: cycles = [] } = useAllocationCycles();
  const { data: runs = [] } = useAllocationRuns();
  const { data: centres = [] } = useQuery({ queryKey: ['cost-centres-flat'], queryFn: async () => (await api.get<CostCentre[] | { data: CostCentre[] }>('/cost-centers', { params: { pageSize: 200 } })).data });
  const { data: keys = [] } = useQuery({ queryKey: ['cost-management', 'keys'], queryFn: async () => (await api.get<StatKey[]>('/costing/statistical-keys')).data });
  const centreList: CostCentre[] = Array.isArray(centres) ? centres : centres.data ?? [];
  const create = useCreateAllocationCycle();
  const preview = useAllocationPreview();
  const runIt = useRunAllocation();
  const reverse = useReverseAllocationRun();
  const [period, setPeriod] = useState({ from: monthStart, to: today });
  const [form, setForm] = useState({ code: '', name: '', postingMode: 'financial', sender: '', receivers: [] as string[], basis: 'equal', keyId: '' });
  const [shown, setShown] = useState<AllocationRun | null>(null);
  const label = (id: string) => centreList.find((c) => c.id === id)?.code ?? id.slice(0, 8);

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">{tx('Cost allocation')}</h1>
        <p className="text-sm text-muted-foreground">
          {tx('Allocations move cost-centre costs to receivers by measured drivers — financial runs post to the GL, statistical runs only inform reports.')}
        </p>
      </div>
      {canManage && (
        <Card>
          <CardHeader><CardTitle>{tx('New cycle')}</CardTitle></CardHeader>
          <CardContent className="grid gap-3 md:grid-cols-3">
            <Input placeholder={tx('Code')} value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value })} />
            <Input placeholder={tx('Name')} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
            <select className={SELECT} value={form.postingMode} onChange={(e) => setForm({ ...form, postingMode: e.target.value })}>
              <option value="financial">{tx('Financial (posts to the GL)')}</option>
              <option value="statistical">{tx('Statistical (no GL movement)')}</option>
            </select>
            <div className="space-y-1">
              <Label>{tx('Sender cost centre')}</Label>
              <select className={`${SELECT} w-full`} value={form.sender} onChange={(e) => setForm({ ...form, sender: e.target.value })}>
                <option value="">—</option>
                {centreList.map((c) => <option key={c.id} value={c.id}>{c.code} · {c.name}</option>)}
              </select>
            </div>
            <div className="space-y-1">
              <Label>{tx('Receivers')}</Label>
              <select multiple className={`${SELECT} h-24 w-full`} value={form.receivers}
                onChange={(e) => setForm({ ...form, receivers: Array.from(e.target.selectedOptions).map((o) => o.value) })}>
                {centreList.filter((c) => c.id !== form.sender).map((c) => <option key={c.id} value={c.id}>{c.code} · {c.name}</option>)}
              </select>
            </div>
            <div className="space-y-1">
              <Label>{tx('Basis')}</Label>
              <select className={`${SELECT} w-full`} value={form.basis} onChange={(e) => setForm({ ...form, basis: e.target.value })}>
                {BASES.map((b) => <option key={b} value={b}>{b}</option>)}
              </select>
              {['statistical_key', 'labour_hours', 'machine_hours', 'operating_hours', 'headcount', 'floor_area'].includes(form.basis) && (
                <select className={`${SELECT} w-full`} value={form.keyId} onChange={(e) => setForm({ ...form, keyId: e.target.value })}>
                  <option value="">{tx('Statistical keys')}</option>
                  {keys.map((k) => <option key={k.id} value={k.id}>{k.code} · {k.name}</option>)}
                </select>
              )}
            </div>
            <Button className="md:col-span-3" disabled={!form.code || !form.name || !form.sender || form.receivers.length === 0}
              onClick={() => create.mutate({ code: form.code, name: form.name, postingMode: form.postingMode, segments: [{
                senderCostCenterId: form.sender, receiverIds: form.receivers, basis: form.basis,
                ...(form.keyId ? { statisticalKeyId: form.keyId } : {}),
              }] })}>{tx('Save')}</Button>
          </CardContent>
        </Card>
      )}
      <Card>
        <CardHeader className="flex flex-row items-center justify-between gap-2">
          <CardTitle>{tx('Allocation cycles')}</CardTitle>
          <div className="flex gap-2">
            <Input type="date" value={period.from} onChange={(e) => setPeriod({ ...period, from: e.target.value })} />
            <Input type="date" value={period.to} onChange={(e) => setPeriod({ ...period, to: e.target.value })} />
          </div>
        </CardHeader>
        <CardContent>
          <Table>
            <TableHeader><TableRow><TableHead>{tx('Code')}</TableHead><TableHead>{tx('Mode')}</TableHead><TableHead>{tx('Segments')}</TableHead><TableHead /></TableRow></TableHeader>
            <TableBody>
              {cycles.map((c) => (
                <TableRow key={c.id}>
                  <TableCell>{c.code} · {c.name}</TableCell>
                  <TableCell><Badge variant="secondary">{c.postingMode}</Badge> {c.method}</TableCell>
                  <TableCell>{c.segments?.length ?? 0}</TableCell>
                  <TableCell className="space-x-2 text-right">
                    {canManage && <Button size="sm" variant="outline" onClick={() => preview.mutate({ id: c.id, ...period }, { onSuccess: setShown })}>{tx('Preview')}</Button>}
                    {canPost && <Button size="sm" onClick={() => runIt.mutate({ id: c.id, ...period })}>{tx('Post run')}</Button>}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
      {shown?.lines && (
        <Card>
          <CardHeader><CardTitle>{tx('Preview')} · {money(shown.total)}</CardTitle></CardHeader>
          <CardContent>
            <Table>
              <TableHeader><TableRow><TableHead>#</TableHead><TableHead>{tx('Sender cost centre')}</TableHead><TableHead>{tx('Receiver')}</TableHead><TableHead>{tx('Driver value')}</TableHead><TableHead className="text-right">{tx('Amount')}</TableHead></TableRow></TableHeader>
              <TableBody>
                {shown.lines.map((l, i) => (
                  <TableRow key={i}>
                    <TableCell>{l.segmentSeq}</TableCell>
                    <TableCell>{label(l.senderCostCenterId)}</TableCell>
                    <TableCell>{label(l.receiverId)}</TableCell>
                    <TableCell>{l.basisValue}</TableCell>
                    <TableCell className="text-right">{money(l.amount)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}
      <Card>
        <CardHeader><CardTitle>{tx('Allocation runs')}</CardTitle></CardHeader>
        <CardContent>
          <Table>
            <TableHeader><TableRow><TableHead>{tx('Number')}</TableHead><TableHead>{tx('Period')}</TableHead><TableHead>{tx('Mode')}</TableHead><TableHead>{tx('Amount')}</TableHead><TableHead>{tx('Status')}</TableHead><TableHead /></TableRow></TableHeader>
            <TableBody>
              {runs.map((r) => (
                <TableRow key={r.id}>
                  <TableCell>{r.number}</TableCell>
                  <TableCell>{String(r.periodFrom).slice(0, 10)} – {String(r.periodTo).slice(0, 10)}</TableCell>
                  <TableCell>{r.postingMode}</TableCell>
                  <TableCell>{money(r.total)}</TableCell>
                  <TableCell><Badge variant="secondary">{r.status}</Badge></TableCell>
                  <TableCell className="text-right">
                    {canPost && r.status === 'posted' && <Button size="sm" variant="outline" onClick={() => reverse.mutate(r.id)}>{tx('Reverse')}</Button>}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  );
}
