import { useState } from 'react';
import { CheckCircle2, Plus, Rocket, XCircle } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useAuthStore } from '@/stores/auth.store';
import { useProducts } from '@/features/products/api';
import { useBoms } from '@/features/manufacturing/api';
import {
  errMsg,
  useCreateStandardCost,
  useProposeBomStandard,
  useStandardCostAction,
  useStandardCosts,
  useStandardVariances,
} from '@/features/costing/api';
import { notify } from '@/lib/notify';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

const STATUS_VARIANT: Record<string, 'default' | 'secondary' | 'outline' | 'destructive'> = {
  draft: 'outline', approved: 'secondary', active: 'default', superseded: 'outline', cancelled: 'destructive',
};

const KIND_LABEL: Record<string, string> = {
  purchase_price: tx('Purchase price'),
  material_price: tx('Material price'),
  material_usage: tx('Material usage'),
  labour: tx('Labour'),
  overhead: tx('Overhead'),
  subcontract: tx('Subcontract'),
  yield: tx('Yield & other'),
};

const today = () => new Date().toISOString().slice(0, 10);
const monthStart = () => new Date(new Date().getFullYear(), new Date().getMonth(), 1).toISOString().slice(0, 10);

/**
 * Standard costing (ADR-S008): versioned product standards (by element), approval and activation with stock
 * revaluation, and the variances they drive — purchase price at receipt, production variances at completion.
 */
export function StandardCostsPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const canManage = has(PERMISSIONS.standardCost.manage);
  const canApprove = has(PERMISSIONS.standardCost.approve);
  const [status, setStatus] = useState('');
  const { data: rows = [] } = useStandardCosts(status ? { status } : undefined);
  const action = useStandardCostAction();
  const [open, setOpen] = useState(false);
  const [bomOpen, setBomOpen] = useState(false);

  const act = async (id: string, a: 'approve' | 'activate' | 'cancel') => {
    try {
      const r = await action.mutateAsync({ id, action: a });
      notify.success(a === 'activate'
        ? tx('Active — stock revalued by {{v}} on {{q}} unit(s)', { v: money(Number(r.revaluationAmount)), q: Number(r.revaluationQuantity) })
        : tx('Standard {{s}}', { s: r.status }));
    } catch (e) {
      notify.error(tx('Action failed'), errMsg(e));
    }
  };

  return (
    <div className="space-y-6 p-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold">{tx('Standard costing')}</h1>
          <p className="text-sm text-muted-foreground">
            {tx('Carry manufactured or bought items at a predetermined cost. Receipts post purchase price variance; production posts material, labour, overhead and yield variances.')}
          </p>
        </div>
        {canManage && (
          <div className="flex gap-2">
            <Button variant="outline" onClick={() => setBomOpen(true)}>{tx('From BOM roll-up')}</Button>
            <Button onClick={() => setOpen(true)}><Plus className="mr-1 h-4 w-4" /> {tx('New standard')}</Button>
          </div>
        )}
      </div>

      <Tabs defaultValue="standards">
        <TabsList>
          <TabsTrigger value="standards">{tx('Standards')}</TabsTrigger>
          <TabsTrigger value="variances">{tx('Variances')}</TabsTrigger>
        </TabsList>
        <TabsContent value="standards">
          <Card>
            <CardContent className="space-y-3 pt-6">
              <select className="rounded-md border p-2 text-sm" value={status} onChange={(e) => setStatus(e.target.value)}>
                <option value="">{tx('All statuses')}</option>
                {Object.keys(STATUS_VARIANT).map((s) => <option key={s} value={s}>{s}</option>)}
              </select>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{tx('Product')}</TableHead>
                    <TableHead>{tx('Version')}</TableHead>
                    <TableHead>{tx('Effective')}</TableHead>
                    <TableHead className="text-right">{tx('Material')}</TableHead>
                    <TableHead className="text-right">{tx('Labour')}</TableHead>
                    <TableHead className="text-right">{tx('Overhead')}</TableHead>
                    <TableHead className="text-right">{tx('Subcontract')}</TableHead>
                    <TableHead className="text-right">{tx('Standard')}</TableHead>
                    <TableHead className="text-right">{tx('Revaluation')}</TableHead>
                    <TableHead>{tx('Status')}</TableHead>
                    <TableHead />
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {rows.map((r) => (
                    <TableRow key={r.id}>
                      <TableCell>{r.productName} <span className="text-xs text-muted-foreground">{r.productCostingMethod}</span></TableCell>
                      <TableCell>v{r.version} {r.source === 'bom_rollup' && <Badge variant="outline">BOM</Badge>}</TableCell>
                      <TableCell>{date(r.effectiveFrom)}</TableCell>
                      <TableCell className="text-right">{money(Number(r.materialCost))}</TableCell>
                      <TableCell className="text-right">{money(Number(r.labourCost))}</TableCell>
                      <TableCell className="text-right">{money(Number(r.overheadCost))}</TableCell>
                      <TableCell className="text-right">{money(Number(r.subcontractCost))}</TableCell>
                      <TableCell className="text-right font-medium">{money(Number(r.totalCost))}</TableCell>
                      <TableCell className="text-right">{r.status === 'active' || r.status === 'superseded' ? money(Number(r.revaluationAmount)) : '—'}</TableCell>
                      <TableCell><Badge variant={STATUS_VARIANT[r.status]}>{r.status}</Badge></TableCell>
                      <TableCell className="space-x-1 whitespace-nowrap text-right">
                        {canApprove && r.status === 'draft' && <Button size="sm" variant="outline" onClick={() => act(r.id, 'approve')}><CheckCircle2 className="mr-1 h-3 w-3" /> {tx('Approve')}</Button>}
                        {canApprove && r.status === 'approved' && <Button size="sm" onClick={() => act(r.id, 'activate')}><Rocket className="mr-1 h-3 w-3" /> {tx('Activate')}</Button>}
                        {canManage && (r.status === 'draft' || r.status === 'approved') && <Button size="sm" variant="ghost" onClick={() => act(r.id, 'cancel')}><XCircle className="h-4 w-4" /></Button>}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              {!rows.length && <p className="text-sm text-muted-foreground">{tx('No standards yet.')}</p>}
            </CardContent>
          </Card>
        </TabsContent>
        <TabsContent value="variances"><VariancesTab /></TabsContent>
      </Tabs>

      <NewStandardDialog open={open} onClose={() => setOpen(false)} />
      <BomStandardDialog open={bomOpen} onClose={() => setBomOpen(false)} />
    </div>
  );
}

function VariancesTab() {
  const [from, setFrom] = useState(monthStart());
  const [to, setTo] = useState(today());
  const [kind, setKind] = useState('');
  const { data } = useStandardVariances(from, to, kind);
  return (
    <Card>
      <CardHeader><CardTitle>{tx('Variances (positive = unfavourable)')}</CardTitle></CardHeader>
      <CardContent className="space-y-3">
        <div className="flex flex-wrap items-end gap-3">
          <div className="space-y-1"><Label>{tx('From')}</Label><Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></div>
          <div className="space-y-1"><Label>{tx('To')}</Label><Input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></div>
          <select className="rounded-md border p-2 text-sm" value={kind} onChange={(e) => setKind(e.target.value)}>
            <option value="">{tx('All kinds')}</option>
            {Object.entries(KIND_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
        </div>
        <div className="flex flex-wrap gap-2">
          {Object.entries(data?.totals ?? {}).map(([k, v]) => (
            <Badge key={k} variant={Number(v) > 0 ? 'destructive' : 'secondary'}>{KIND_LABEL[k] ?? k}: {money(Number(v))}</Badge>
          ))}
          {data && <Badge variant="outline">{tx('Total')}: {money(Number(data.total))}</Badge>}
        </div>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{tx('Date')}</TableHead>
              <TableHead>{tx('Product')}</TableHead>
              <TableHead>{tx('Kind')}</TableHead>
              <TableHead>{tx('Source')}</TableHead>
              <TableHead className="text-right">{tx('Qty')}</TableHead>
              <TableHead className="text-right">{tx('Standard')}</TableHead>
              <TableHead className="text-right">{tx('Actual')}</TableHead>
              <TableHead className="text-right">{tx('Variance')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {(data?.rows ?? []).map((r) => (
              <TableRow key={r.id}>
                <TableCell>{date(r.createdAt)}</TableCell>
                <TableCell>{r.productName}</TableCell>
                <TableCell>{KIND_LABEL[r.kind] ?? r.kind}</TableCell>
                <TableCell className="text-xs text-muted-foreground">{r.sourceType}</TableCell>
                <TableCell className="text-right">{Number(r.quantity).toLocaleString()}</TableCell>
                <TableCell className="text-right">{money(Number(r.standardCost))}</TableCell>
                <TableCell className="text-right">{money(Number(r.actualCost))}</TableCell>
                <TableCell className={`text-right ${Number(r.amount) > 0 ? 'text-destructive' : ''}`}>{money(Number(r.amount))}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  );
}

function NewStandardDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const products = useProducts({ page: 1, pageSize: 500 } as any);
  const create = useCreateStandardCost();
  const [f, setF] = useState({ productId: '', effectiveFrom: today(), materialCost: '', labourCost: '', overheadCost: '', subcontractCost: '', notes: '', convertMethod: false });
  const total = ['materialCost', 'labourCost', 'overheadCost', 'subcontractCost'].reduce((s, k) => s + (Number((f as any)[k]) || 0), 0);
  const list = (products.data as any)?.data ?? [];
  const selected = list.find((p: any) => p.id === f.productId);

  const save = async () => {
    try {
      await create.mutateAsync({
        productId: f.productId, effectiveFrom: f.effectiveFrom, notes: f.notes || undefined, convertMethod: f.convertMethod,
        materialCost: Number(f.materialCost) || 0, labourCost: Number(f.labourCost) || 0,
        overheadCost: Number(f.overheadCost) || 0, subcontractCost: Number(f.subcontractCost) || 0,
      });
      notify.success(tx('Standard saved as draft'));
      onClose();
    } catch (e) {
      notify.error(tx('Could not save standard'), errMsg(e));
    }
  };

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-lg">
        <DialogHeader><DialogTitle>{tx('New standard cost')}</DialogTitle></DialogHeader>
        <div className="space-y-3">
          <div className="space-y-1">
            <Label>{tx('Product')}</Label>
            <select className="w-full rounded-md border p-2 text-sm" value={f.productId} onChange={(e) => setF({ ...f, productId: e.target.value })}>
              <option value="">{tx('Select…')}</option>
              {list.map((p: any) => <option key={p.id} value={p.id}>{p.code} · {p.name} ({p.costingMethod})</option>)}
            </select>
          </div>
          <div className="grid grid-cols-2 gap-3">
            {[['materialCost', tx('Material')], ['labourCost', tx('Labour')], ['overheadCost', tx('Overhead')], ['subcontractCost', tx('Subcontract')]].map(([k, l]) => (
              <div key={k} className="space-y-1"><Label>{l}</Label><Input type="number" min="0" value={(f as any)[k]} onChange={(e) => setF({ ...f, [k]: e.target.value })} /></div>
            ))}
          </div>
          <p className="text-sm">{tx('Standard per unit')}: <b>{money(total)}</b></p>
          <div className="space-y-1"><Label>{tx('Effective from')}</Label><Input type="date" value={f.effectiveFrom} onChange={(e) => setF({ ...f, effectiveFrom: e.target.value })} /></div>
          {selected && selected.costingMethod !== 'STANDARD' && (
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={f.convertMethod} onChange={(e) => setF({ ...f, convertMethod: e.target.checked })} />
              {tx('Switch this product from {{m}} to standard cost on activation (stock is revalued)', { m: selected.costingMethod })}
            </label>
          )}
          <div className="space-y-1"><Label>{tx('Notes')}</Label><Input value={f.notes} onChange={(e) => setF({ ...f, notes: e.target.value })} /></div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={save} disabled={!f.productId || create.isPending}>{tx('Save draft')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function BomStandardDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { data: boms = [] } = useBoms({ status: 'active' });
  const propose = useProposeBomStandard();
  const [bomId, setBomId] = useState('');
  const [effectiveFrom, setEffectiveFrom] = useState(today());
  const [convertMethod, setConvertMethod] = useState(true);

  const save = async () => {
    try {
      const r = await propose.mutateAsync({ bomId, effectiveFrom, convertMethod });
      notify.success(tx('Draft standard {{v}} proposed for {{p}}', { v: money(Number(r.totalCost)), p: r.productName }));
      onClose();
    } catch (e) {
      notify.error(tx('Could not propose standard'), errMsg(e));
    }
  };

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-md">
        <DialogHeader><DialogTitle>{tx('Standard from BOM roll-up')}</DialogTitle></DialogHeader>
        <div className="space-y-3">
          <p className="text-sm text-muted-foreground">{tx('Freezes the BOM cost roll-up (materials, routing labour, subcontract, overhead) and proposes it per output unit as a draft product standard.')}</p>
          <select className="w-full rounded-md border p-2 text-sm" value={bomId} onChange={(e) => setBomId(e.target.value)}>
            <option value="">{tx('Active BOM…')}</option>
            {boms.map((b: any) => <option key={b.id} value={b.id}>{b.code} · {b.name} v{b.version}</option>)}
          </select>
          <div className="space-y-1"><Label>{tx('Effective from')}</Label><Input type="date" value={effectiveFrom} onChange={(e) => setEffectiveFrom(e.target.value)} /></div>
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={convertMethod} onChange={(e) => setConvertMethod(e.target.checked)} />
            {tx('Switch the output product to standard cost on activation')}
          </label>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={save} disabled={!bomId || propose.isPending}>{tx('Propose')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export default StandardCostsPage;
