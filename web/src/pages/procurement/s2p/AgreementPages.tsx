import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Handshake, Plus, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { ListPageHeader, StatusPill } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { api } from '@/lib/api';
import { agreementApi, errMsg, useAgreement, useAgreementMutation, useAgreements, useSupplierOptions, type Agreement } from '@/features/procurement/api';
import { notify } from '@/lib/notify';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

const TONE: Record<Agreement['status'], 'neutral' | 'info' | 'success' | 'warning' | 'danger'> = {
  draft: 'neutral', active: 'success', on_hold: 'warning', exhausted: 'info', expired: 'neutral', terminated: 'danger',
};
const LABEL: Record<Agreement['status'], string> = {
  draft: tx('Draft'), active: tx('Active'), on_hold: tx('On hold'), exhausted: tx('Fully released'), expired: tx('Expired'), terminated: tx('Terminated'),
};

/** Purchase agreements: blanket commitments and rate contracts (ADR-PROCUREMENT-001 wave 4). */
export function AgreementsPage() {
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const { data: rows = [] } = useAgreements();
  const { data: suppliers = [] } = useSupplierOptions();
  const create = useAgreementMutation((body: Record<string, unknown>) => agreementApi.create(body));
  const [open, setOpen] = useState(false);
  const today = new Date().toISOString().slice(0, 10);
  const [form, setForm] = useState({ partnerId: '', type: 'blanket', title: '', validFrom: today, validTo: '', committedValue: '' });
  const [lines, setLines] = useState([{ description: '', unitPrice: '', committedQuantity: '' }]);

  const save = async () => {
    try {
      const pa = await create.mutateAsync({
        ...form,
        committedValue: form.committedValue ? Number(form.committedValue) : undefined,
        lines: lines.filter((l) => l.description && l.unitPrice !== '').map((l) => ({ description: l.description, unitPrice: Number(l.unitPrice), committedQuantity: l.committedQuantity ? Number(l.committedQuantity) : undefined })),
      }) as Agreement;
      notify.success(tx('Agreement saved as draft — another person must activate it'));
      setOpen(false);
      navigate(`/procurement/agreements/${pa.id}`);
    } catch (e) { notify.error(tx('Could not save agreement'), errMsg(e)); }
  };

  return (
    <div className="space-y-4 p-6">
      <ListPageHeader title={tx('Purchase agreements')} icon={Handshake}
        description={tx('Negotiate once, order many times: release orders against a blanket quantity or an agreed rate until it is used up or expires.')}
        actions={has(PERMISSIONS.purchaseAgreement.manage) && <Button onClick={() => setOpen(true)}><Plus className="mr-1 h-4 w-4" />{tx('New agreement')}</Button>} />
      <Card>
        <CardContent className="p-0">
          <Table>
            <TableHeader><TableRow>
              <TableHead>{tx('Agreement')}</TableHead><TableHead>{tx('Supplier')}</TableHead><TableHead>{tx('Type')}</TableHead>
              <TableHead>{tx('Valid')}</TableHead><TableHead>{tx('Status')}</TableHead><TableHead className="text-right">{tx('Released')}</TableHead>
            </TableRow></TableHeader>
            <TableBody>
              {!rows.length && <TableRow><TableCell colSpan={6} className="py-8 text-center text-muted-foreground">{tx('No agreements yet.')}</TableCell></TableRow>}
              {rows.map((r) => (
                <TableRow key={r.id} className="cursor-pointer" onClick={() => navigate(`/procurement/agreements/${r.id}`)}>
                  <TableCell><div className="font-mono text-xs">{r.agreementNumber}</div><div>{r.title}</div></TableCell>
                  <TableCell>{r.partnerName}</TableCell>
                  <TableCell>{r.type === 'blanket' ? tx('Blanket') : tx('Rate contract')}</TableCell>
                  <TableCell className="text-xs">{date(r.validFrom)} – {date(r.validTo)}</TableCell>
                  <TableCell><StatusPill tone={TONE[r.status]}>{LABEL[r.status]}</StatusPill></TableCell>
                  <TableCell className="text-right">{money(Number(r.releasedValue), r.currencyCode)}{r.committedValue && <span className="text-xs text-muted-foreground"> / {money(Number(r.committedValue), r.currencyCode)}</span>}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-w-2xl">
          <DialogHeader><DialogTitle>{tx('New agreement')}</DialogTitle></DialogHeader>
          <div className="grid gap-3 md:grid-cols-2">
            <div className="md:col-span-2"><Label>{tx('Supplier')}</Label>
              <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={form.partnerId} onChange={(e) => setForm({ ...form, partnerId: e.target.value })}>
                <option value="">{tx('Select…')}</option>
                {suppliers.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
            </div>
            <div><Label>{tx('Type')}</Label>
              <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value })}>
                <option value="blanket">{tx('Blanket (committed quantity)')}</option>
                <option value="rate">{tx('Rate contract (agreed prices)')}</option>
              </select>
            </div>
            <div><Label>{tx('Title')}</Label><Input value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} /></div>
            <div><Label>{tx('Valid from')}</Label><Input type="date" value={form.validFrom} onChange={(e) => setForm({ ...form, validFrom: e.target.value })} /></div>
            <div><Label>{tx('Valid until')}</Label><Input type="date" value={form.validTo} onChange={(e) => setForm({ ...form, validTo: e.target.value })} /></div>
            <div className="md:col-span-2"><Label>{tx('Value cap (optional)')}</Label><Input type="number" min={0} value={form.committedValue} onChange={(e) => setForm({ ...form, committedValue: e.target.value })} /></div>
          </div>
          <div className="space-y-2">
            <Label>{tx('Lines')}</Label>
            {lines.map((l, i) => (
              <div key={i} className="grid grid-cols-12 gap-2">
                <Input className="col-span-6" placeholder={tx('Description')} value={l.description} onChange={(e) => setLines(lines.map((x, j) => (j === i ? { ...x, description: e.target.value } : x)))} />
                <Input className="col-span-3" type="number" placeholder={tx('Unit price')} value={l.unitPrice} onChange={(e) => setLines(lines.map((x, j) => (j === i ? { ...x, unitPrice: e.target.value } : x)))} />
                <Input className="col-span-2" type="number" placeholder={tx('Qty')} value={l.committedQuantity} onChange={(e) => setLines(lines.map((x, j) => (j === i ? { ...x, committedQuantity: e.target.value } : x)))} />
                <Button className="col-span-1" size="icon" variant="ghost" aria-label={tx('Remove line')} disabled={lines.length === 1} onClick={() => setLines(lines.filter((_, j) => j !== i))}><Trash2 className="h-4 w-4" /></Button>
              </div>
            ))}
            <Button size="sm" variant="outline" onClick={() => setLines([...lines, { description: '', unitPrice: '', committedQuantity: '' }])}><Plus className="mr-1 h-4 w-4" />{tx('Add line')}</Button>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setOpen(false)}>{tx('Cancel')}</Button>
            <Button disabled={!form.partnerId || !form.validTo} onClick={save}>{tx('Save draft')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

/** One agreement: remaining quantities, releases, lifecycle actions and a release (call-off) form. */
export function AgreementDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const { data: pa } = useAgreement(id);
  const run = useAgreementMutation((fn: () => Promise<unknown>) => fn());
  const warehouses = useQuery({ queryKey: ['warehouses'], queryFn: async () => (await api.get<{ data: Array<{ id: string; name: string }> }>('/inventory/locations?type=warehouse')).data.data ?? [] });
  const [release, setRelease] = useState<Record<string, string>>({});
  const [warehouseId, setWarehouseId] = useState('');
  const [reason, setReason] = useState('');
  const [terminateOpen, setTerminateOpen] = useState(false);
  if (!pa) return <div className="p-6 text-sm text-muted-foreground">{tx('Loading…')}</div>;

  const exec = async (fn: () => Promise<unknown>, ok: string) => {
    try { return await run.mutateAsync(fn).then((r) => { notify.success(ok); return r; }); } catch (e) { notify.error(tx('Action failed'), errMsg(e)); return null; }
  };
  const doRelease = async () => {
    const lines = Object.entries(release).filter(([, q]) => Number(q) > 0).map(([agreementLineId, q]) => ({ agreementLineId, quantity: Number(q) }));
    const wh = warehouseId || warehouses.data?.[0]?.id;
    if (!lines.length || !wh) return;
    const po = await exec(() => agreementApi.release(pa.id, { warehouseId: wh, lines }), tx('Release order created')) as any;
    if (po?.id) navigate(`/procurement/purchase-orders/${po.id}`);
  };

  return (
    <div className="space-y-4 p-6">
      <ListPageHeader title={`${pa.agreementNumber} · ${pa.title}`} icon={Handshake}
        description={`${pa.partner?.name ?? ''} · ${date(pa.validFrom)} – ${date(pa.validTo)}`}
        actions={
          <>
            <StatusPill tone={TONE[pa.status]}>{LABEL[pa.status]}</StatusPill>
            {has(PERMISSIONS.purchaseAgreement.approve) && pa.status === 'draft' && <Button size="sm" onClick={() => exec(() => agreementApi.action(pa.id, 'activate'), tx('Agreement activated'))}>{tx('Activate')}</Button>}
            {has(PERMISSIONS.purchaseAgreement.manage) && pa.status === 'active' && <Button size="sm" variant="outline" onClick={() => exec(() => agreementApi.action(pa.id, 'hold'), tx('Agreement on hold'))}>{tx('Put on hold')}</Button>}
            {has(PERMISSIONS.purchaseAgreement.manage) && pa.status === 'on_hold' && <Button size="sm" variant="outline" onClick={() => exec(() => agreementApi.action(pa.id, 'resume'), tx('Agreement resumed'))}>{tx('Resume')}</Button>}
            {has(PERMISSIONS.purchaseAgreement.approve) && ['draft', 'active', 'on_hold'].includes(pa.status) && <Button size="sm" variant="ghost" onClick={() => setTerminateOpen(true)}>{tx('Terminate')}</Button>}
          </>
        } />
      <Card>
        <CardHeader><CardTitle>{tx('Lines')}</CardTitle></CardHeader>
        <CardContent className="p-0">
          <Table>
            <TableHeader><TableRow>
              <TableHead>{tx('Item')}</TableHead><TableHead className="text-right">{tx('Agreed price')}</TableHead>
              <TableHead className="text-right">{tx('Committed')}</TableHead><TableHead className="text-right">{tx('Released')}</TableHead><TableHead className="text-right">{tx('Remaining')}</TableHead>
              {pa.status === 'active' && <TableHead className="w-32">{tx('Release now')}</TableHead>}
            </TableRow></TableHeader>
            <TableBody>
              {(pa.lines ?? []).map((l) => (
                <TableRow key={l.id}>
                  <TableCell>{l.description}</TableCell>
                  <TableCell className="text-right">{money(Number(l.unitPrice), pa.currencyCode)}</TableCell>
                  <TableCell className="text-right">{l.committedQuantity ? Number(l.committedQuantity) : '—'}</TableCell>
                  <TableCell className="text-right">{Number(l.releasedQuantity)}</TableCell>
                  <TableCell className="text-right font-medium">{l.remainingQuantity != null ? Number(l.remainingQuantity) : '∞'}</TableCell>
                  {pa.status === 'active' && <TableCell><Input type="number" min={0} value={release[l.id] ?? ''} onChange={(e) => setRelease({ ...release, [l.id]: e.target.value })} /></TableCell>}
                </TableRow>
              ))}
            </TableBody>
          </Table>
          {pa.status === 'active' && has(PERMISSIONS.procurement.purchaseOrder.create) && (
            <div className="flex items-center justify-end gap-2 p-4">
              <select className="h-9 rounded-md border bg-background px-2 text-sm" value={warehouseId} onChange={(e) => setWarehouseId(e.target.value)}>
                {(warehouses.data ?? []).map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}
              </select>
              <Button onClick={doRelease}>{tx('Create release order')}</Button>
            </div>
          )}
        </CardContent>
      </Card>
      <Card>
        <CardHeader><CardTitle>{tx('Releases')}</CardTitle></CardHeader>
        <CardContent className="space-y-1 text-sm">
          {!(pa.releases ?? []).length && <div className="text-muted-foreground">{tx('No releases yet.')}</div>}
          {(pa.releases ?? []).map((r) => (
            <div key={r.id} className="flex justify-between">
              <Link className="font-mono text-xs hover:underline" to={`/procurement/purchase-orders/${r.id}`}>{r.orderNumber}</Link>
              <span>{date(r.orderDate)} · {r.status} · {money(Number(r.totalAmount), pa.currencyCode)}</span>
            </div>
          ))}
        </CardContent>
      </Card>
      <Dialog open={terminateOpen} onOpenChange={setTerminateOpen}>
        <DialogContent>
          <DialogHeader><DialogTitle>{tx('Terminate agreement')}</DialogTitle></DialogHeader>
          <Label>{tx('Reason')}</Label>
          <Input value={reason} onChange={(e) => setReason(e.target.value)} />
          <DialogFooter>
            <Button variant="outline" onClick={() => setTerminateOpen(false)}>{tx('Cancel')}</Button>
            <Button variant="destructive" disabled={!reason.trim()} onClick={async () => { if (await exec(() => agreementApi.action(pa.id, 'terminate', reason.trim()), tx('Agreement terminated'))) setTerminateOpen(false); }}>{tx('Terminate')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
