import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Lock, Plus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useAuthStore } from '@/stores/auth.store';
import { api } from '@/lib/api';
import { useAccounts } from '@/features/accounting/api';
import { useProducts } from '@/features/products/api';
import {
  errMsg,
  flattenTree,
  useCostCenterTree,
  useCostProcess,
  useCostProcesses,
  useOpenProcessPeriod,
  useProcessPeriod,
  useProcessPeriodAction,
  useProcessPreview,
  useSaveCostProcess,
} from '@/features/costing/api';
import { notify } from '@/lib/notify';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

const UNIT_FIELDS: Array<[string, string]> = [
  ['openingUnits', tx('Opening WIP units')],
  ['openingMaterialPct', tx('Opening % material')],
  ['openingConversionPct', tx('Opening % conversion')],
  ['unitsStarted', tx('Units started / received')],
  ['unitsCompleted', tx('Units completed')],
  ['closingUnits', tx('Closing WIP units')],
  ['closingMaterialPct', tx('Closing % material')],
  ['closingConversionPct', tx('Closing % conversion')],
  ['normalLossUnits', tx('Normal loss units')],
  ['abnormalLossUnits', tx('Abnormal loss units')],
  ['abnormalMaterialPct', tx('Abnormal % material')],
  ['abnormalConversionPct', tx('Abnormal % conversion')],
];

/**
 * Process costing (ADR-S008): continuous production costed per process and period with equivalent units (weighted
 * average or FIFO), transfers between processes, normal / abnormal loss and closing WIP carried forward.
 */
export function ProcessCostingPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const canManage = has(PERMISSIONS.processCosting.manage);
  const { data: processes = [] } = useCostProcesses();
  const [selected, setSelected] = useState<string>();
  const [periodId, setPeriodId] = useState<string>();
  const [editing, setEditing] = useState<any>(null);

  return (
    <div className="space-y-6 p-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold">{tx('Process costing')}</h1>
          <p className="text-sm text-muted-foreground">
            {tx('For continuous production (cement, blocks, beverages): costs accumulate per process and period, are spread over equivalent units, and flow to the next process or into finished stock.')}
          </p>
        </div>
        {canManage && <Button onClick={() => setEditing({ method: 'weighted_average', sequence: processes.length + 1 })}><Plus className="mr-1 h-4 w-4" /> {tx('New process')}</Button>}
      </div>

      <Card>
        <CardHeader><CardTitle>{tx('Processes')}</CardTitle></CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>#</TableHead>
                <TableHead>{tx('Process')}</TableHead>
                <TableHead>{tx('Method')}</TableHead>
                <TableHead>{tx('Feeds')}</TableHead>
                <TableHead>{tx('Open period')}</TableHead>
                <TableHead />
              </TableRow>
            </TableHeader>
            <TableBody>
              {processes.map((p) => (
                <TableRow key={p.id} className="cursor-pointer" onClick={() => { setSelected(p.id); setPeriodId(p.openPeriodId ?? undefined); }}>
                  <TableCell>{p.sequence}</TableCell>
                  <TableCell><span className="font-mono text-xs">{p.code}</span> {p.name}</TableCell>
                  <TableCell><Badge variant="outline">{p.method === 'fifo' ? 'FIFO' : tx('Weighted average')}</Badge></TableCell>
                  <TableCell>{p.nextProcessCode ?? (p.outputProductName ? `→ ${p.outputProductName}` : '—')}</TableCell>
                  <TableCell>{p.openPeriodId ? <Badge>{tx('open')}</Badge> : '—'}</TableCell>
                  <TableCell className="text-right">{canManage && <Button size="sm" variant="ghost" onClick={(e) => { e.stopPropagation(); setEditing(p); }}>{tx('Edit')}</Button>}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          {!processes.length && <p className="text-sm text-muted-foreground">{tx('Define your processes in order, e.g. Mixing → Grinding → Packing.')}</p>}
        </CardContent>
      </Card>

      {selected && <ProcessDetail processId={selected} periodId={periodId} setPeriodId={setPeriodId} canManage={canManage} />}
      {editing && <ProcessDialog value={editing} processes={processes} onClose={() => setEditing(null)} />}
    </div>
  );
}

function ProcessDetail({ processId, periodId, setPeriodId, canManage }: { processId: string; periodId?: string; setPeriodId: (id?: string) => void; canManage: boolean }) {
  const { data: proc } = useCostProcess(processId);
  const open = useOpenProcessPeriod();
  const [from, setFrom] = useState(new Date(new Date().getFullYear(), new Date().getMonth(), 1).toISOString().slice(0, 10));
  const [to, setTo] = useState(new Date().toISOString().slice(0, 10));
  if (!proc) return null;
  const openPeriod = proc.periods?.find((p: any) => p.status === 'open');
  return (
    <div className="space-y-4">
      <Card>
        <CardHeader><CardTitle>{proc.code} · {proc.name} — {tx('periods')}</CardTitle></CardHeader>
        <CardContent className="space-y-3 text-sm">
          {!openPeriod && canManage && (
            <div className="flex flex-wrap items-end gap-2">
              <div className="space-y-1"><Label>{tx('From')}</Label><Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></div>
              <div className="space-y-1"><Label>{tx('To')}</Label><Input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></div>
              <Button onClick={() => open.mutate({ processId, periodFrom: from, periodTo: to }, {
                onSuccess: (p: any) => setPeriodId(p.id), onError: (e) => notify.error(tx('Could not open period'), errMsg(e)),
              })}>{tx('Open first period')}</Button>
            </div>
          )}
          <div className="flex flex-wrap gap-2">
            {(proc.periods ?? []).map((p: any) => (
              <Button key={p.id} size="sm" variant={p.id === periodId ? 'default' : 'outline'} onClick={() => setPeriodId(p.id)}>
                {date(p.periodFrom)} – {date(p.periodTo)} {p.status === 'closed' && <Lock className="ml-1 h-3 w-3" />}
              </Button>
            ))}
          </div>
        </CardContent>
      </Card>
      {periodId && <PeriodPanel periodId={periodId} canManage={canManage} setPeriodId={setPeriodId} />}
    </div>
  );
}

function PeriodPanel({ periodId, canManage, setPeriodId }: { periodId: string; canManage: boolean; setPeriodId: (id?: string) => void }) {
  const has = useAuthStore((s) => s.hasPermission);
  const { data: p } = useProcessPeriod(periodId);
  const isOpen = p?.status === 'open';
  const { data: preview, error } = useProcessPreview(periodId, isOpen);
  const action = useProcessPeriodAction();
  const { data: accounts } = useAccounts();
  const products = useProducts({ page: 1, pageSize: 500 } as any);
  const locations = useQuery({
    queryKey: ['inventory-locations', 'process'],
    queryFn: async () => (await api.get<any>('/inventory/locations')).data?.data ?? [],
  });
  const [units, setUnits] = useState<Record<string, string>>({});
  const [mat, setMat] = useState({ productId: '', locationId: '', quantity: '' });
  const [cost, setCost] = useState({ element: 'labour', amount: '', creditAccountId: '', description: '' });

  const result = isOpen ? preview?.result : p?.result;
  const run = async (kind: 'units' | 'materials' | 'costs' | 'close', body?: unknown) => {
    try {
      const r = await action.mutateAsync({ id: periodId, kind, body });
      if (kind === 'close') {
        notify.success(tx('Closed — {{c}} completed, {{w}} in closing WIP', { c: money(Number(r.completedCost)), w: money(Number(r.closingWipCost)) }));
        setPeriodId(r.nextPeriodId);
      }
    } catch (e) {
      notify.error(tx('Failed'), errMsg(e));
    }
  };

  const unitValues = useMemo(() => Object.fromEntries(UNIT_FIELDS.map(([k]) => [k, units[k] ?? (p ? String(Number(p[k])) : '')])), [units, p]);
  if (!p) return null;

  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <Card>
        <CardHeader><CardTitle>{tx('Units')} <Badge variant={isOpen ? 'default' : 'outline'}>{p.status}</Badge></CardTitle></CardHeader>
        <CardContent className="space-y-3">
          <div className="grid grid-cols-2 gap-2">
            {UNIT_FIELDS.map(([k, label]) => (
              <div key={k} className="space-y-1">
                <Label className="text-xs">{label}</Label>
                <Input type="number" min="0" disabled={!isOpen || !canManage} value={unitValues[k]} onChange={(e) => setUnits({ ...units, [k]: e.target.value })} />
              </div>
            ))}
          </div>
          {isOpen && canManage && (
            <Button size="sm" onClick={() => run('units', Object.fromEntries(Object.entries(units).filter(([, v]) => v !== '').map(([k, v]) => [k, Number(v)])))}>
              {tx('Save units')}
            </Button>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle>{tx('Costs this period')}</CardTitle></CardHeader>
        <CardContent className="space-y-3 text-sm">
          <div className="flex flex-wrap gap-2">
            <Badge variant="outline">{tx('Opening WIP')}: {money(Number(p.openingMaterialCost) + Number(p.openingConversionCost) + Number(p.openingTransferredInCost))}</Badge>
            {Object.entries(p.costsAdded ?? {}).map(([k, v]: any) => <Badge key={k} variant="secondary">{k}: {money(Number(v))}</Badge>)}
          </div>
          <Table>
            <TableBody>
              {(p.entries ?? []).map((e: any) => (
                <TableRow key={e.id}>
                  <TableCell>{e.element}</TableCell>
                  <TableCell>{e.productName ?? e.description ?? e.sourceType}</TableCell>
                  <TableCell className="text-right">{e.quantity ? Number(e.quantity).toLocaleString() : ''}</TableCell>
                  <TableCell className="text-right">{money(Number(e.amount))}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          {isOpen && canManage && (
            <>
              <div className="grid grid-cols-4 gap-2">
                <select className="col-span-2 rounded-md border p-2" value={mat.productId} onChange={(e) => setMat({ ...mat, productId: e.target.value })}>
                  <option value="">{tx('Material…')}</option>
                  {((products.data as any)?.data ?? []).map((x: any) => <option key={x.id} value={x.id}>{x.name}</option>)}
                </select>
                <select className="rounded-md border p-2" value={mat.locationId} onChange={(e) => setMat({ ...mat, locationId: e.target.value })}>
                  <option value="">{tx('From…')}</option>
                  {(locations.data ?? []).map((l: any) => <option key={l.id} value={l.id}>{l.name}</option>)}
                </select>
                <Input type="number" min="0" placeholder={tx('Qty')} value={mat.quantity} onChange={(e) => setMat({ ...mat, quantity: e.target.value })} />
                <Button className="col-span-4" size="sm" variant="outline" disabled={!mat.productId || !mat.locationId || !Number(mat.quantity)}
                  onClick={() => run('materials', { ...mat, quantity: Number(mat.quantity) })}>{tx('Issue material into process')}</Button>
              </div>
              <div className="grid grid-cols-4 gap-2">
                <select className="rounded-md border p-2" value={cost.element} onChange={(e) => setCost({ ...cost, element: e.target.value })}>
                  <option value="labour">{tx('Labour')}</option>
                  <option value="overhead">{tx('Overhead')}</option>
                  <option value="material">{tx('Other material')}</option>
                </select>
                <Input type="number" min="0" placeholder={tx('Amount')} value={cost.amount} onChange={(e) => setCost({ ...cost, amount: e.target.value })} />
                <select className="col-span-2 rounded-md border p-2" value={cost.creditAccountId} onChange={(e) => setCost({ ...cost, creditAccountId: e.target.value })}>
                  <option value="">{tx('Already in WIP (record only)')}</option>
                  {(accounts?.data ?? []).map((a: any) => <option key={a.id} value={a.id}>{tx('Credit')} {a.code} · {a.name}</option>)}
                </select>
                <Button className="col-span-4" size="sm" variant="outline" disabled={!Number(cost.amount)}
                  onClick={() => run('costs', { element: cost.element, amount: Number(cost.amount), creditAccountId: cost.creditAccountId || undefined, description: cost.description || undefined })}>
                  {tx('Apply cost')}
                </Button>
              </div>
            </>
          )}
        </CardContent>
      </Card>

      <Card className="lg:col-span-2">
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle>{isOpen ? tx('Cost of production (preview)') : tx('Cost of production')}</CardTitle>
          {isOpen && has(PERMISSIONS.processCosting.close) && <Button onClick={() => run('close')} disabled={!!error || action.isPending}><Lock className="mr-1 h-4 w-4" /> {tx('Close period')}</Button>}
        </CardHeader>
        <CardContent className="space-y-3 text-sm">
          {isOpen && error && <p className="text-destructive">{errMsg(error)}</p>}
          {result && (
            <>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{tx('Element')}</TableHead>
                    <TableHead className="text-right">{tx('Equivalent units')}</TableHead>
                    <TableHead className="text-right">{tx('Cost per EU')}</TableHead>
                    <TableHead className="text-right">{tx('In closing WIP')}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {['transferredIn', 'material', 'conversion'].map((k) => (
                    <TableRow key={k}>
                      <TableCell>{k === 'transferredIn' ? tx('Transferred in') : k === 'material' ? tx('Material') : tx('Conversion')}</TableCell>
                      <TableCell className="text-right">{Number(result.equivalentUnits?.[k] ?? 0).toLocaleString()}</TableCell>
                      <TableCell className="text-right">{money(Number(result.costPerEquivalentUnit?.[k] ?? 0))}</TableCell>
                      <TableCell className="text-right">{money(Number(result.closingWipByElement?.[k] ?? 0))}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              <div className="flex flex-wrap gap-2">
                <Badge>{tx('Completed')}: {money(Number(result.completedCost))}</Badge>
                <Badge variant="secondary">{tx('Unit cost')}: {money(Number(result.unitCost))}</Badge>
                <Badge variant="outline">{tx('Closing WIP')}: {money(Number(result.closingWipCost))}</Badge>
                {Number(result.abnormalLossCost) > 0 && <Badge variant="destructive">{tx('Abnormal loss')}: {money(Number(result.abnormalLossCost))}</Badge>}
              </div>
            </>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function ProcessDialog({ value, processes, onClose }: { value: any; processes: any[]; onClose: () => void }) {
  const save = useSaveCostProcess();
  const products = useProducts({ page: 1, pageSize: 500 } as any);
  const { data: tree = [] } = useCostCenterTree();
  const centers = useMemo(() => flattenTree(tree), [tree]);
  const locations = useQuery({
    queryKey: ['inventory-locations', 'process'],
    queryFn: async () => (await api.get<any>('/inventory/locations')).data?.data ?? [],
  });
  const [f, setF] = useState<any>(value);

  const submit = async () => {
    try {
      await save.mutateAsync({
        id: f.id, code: f.id ? undefined : f.code, name: f.name, sequence: Number(f.sequence) || 1, method: f.method,
        nextProcessId: f.nextProcessId || undefined, outputProductId: f.outputProductId || undefined,
        outputLocationId: f.outputLocationId || undefined, costCenterId: f.costCenterId || undefined, notes: f.notes || undefined,
      });
      onClose();
    } catch (e) {
      notify.error(tx('Could not save process'), errMsg(e));
    }
  };

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-lg">
        <DialogHeader><DialogTitle>{f.id ? tx('Edit process') : tx('New process')}</DialogTitle></DialogHeader>
        <div className="grid grid-cols-2 gap-3">
          <div className="space-y-1"><Label>{tx('Code')}</Label><Input disabled={!!f.id} value={f.code ?? ''} onChange={(e) => setF({ ...f, code: e.target.value })} /></div>
          <div className="space-y-1"><Label>{tx('Name')}</Label><Input value={f.name ?? ''} onChange={(e) => setF({ ...f, name: e.target.value })} /></div>
          <div className="space-y-1"><Label>{tx('Sequence')}</Label><Input type="number" value={f.sequence ?? 1} onChange={(e) => setF({ ...f, sequence: e.target.value })} /></div>
          <div className="space-y-1">
            <Label>{tx('Method')}</Label>
            <select className="w-full rounded-md border p-2 text-sm" value={f.method} onChange={(e) => setF({ ...f, method: e.target.value })}>
              <option value="weighted_average">{tx('Weighted average')}</option>
              <option value="fifo">FIFO</option>
            </select>
          </div>
          <div className="col-span-2 space-y-1">
            <Label>{tx('Next process (empty = last process, output to stock)')}</Label>
            <select className="w-full rounded-md border p-2 text-sm" value={f.nextProcessId ?? ''} onChange={(e) => setF({ ...f, nextProcessId: e.target.value })}>
              <option value="">{tx('None')}</option>
              {processes.filter((p) => p.id !== f.id).map((p) => <option key={p.id} value={p.id}>{p.code} · {p.name}</option>)}
            </select>
          </div>
          <div className="space-y-1">
            <Label>{tx('Output product')}</Label>
            <select className="w-full rounded-md border p-2 text-sm" value={f.outputProductId ?? ''} onChange={(e) => setF({ ...f, outputProductId: e.target.value })}>
              <option value="">{tx('None')}</option>
              {((products.data as any)?.data ?? []).map((x: any) => <option key={x.id} value={x.id}>{x.name}</option>)}
            </select>
          </div>
          <div className="space-y-1">
            <Label>{tx('Output location')}</Label>
            <select className="w-full rounded-md border p-2 text-sm" value={f.outputLocationId ?? ''} onChange={(e) => setF({ ...f, outputLocationId: e.target.value })}>
              <option value="">{tx('None')}</option>
              {(locations.data ?? []).map((l: any) => <option key={l.id} value={l.id}>{l.name}</option>)}
            </select>
          </div>
          <div className="col-span-2 space-y-1">
            <Label>{tx('Cost centre')}</Label>
            <select className="w-full rounded-md border p-2 text-sm" value={f.costCenterId ?? ''} onChange={(e) => setF({ ...f, costCenterId: e.target.value })}>
              <option value="">{tx('None')}</option>
              {centers.map((c) => <option key={c.id} value={c.id}>{'  '.repeat(c.depth)}{c.code} · {c.name}</option>)}
            </select>
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={save.isPending || !f.name || (!f.id && !f.code)}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export default ProcessCostingPage;
