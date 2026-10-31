import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { AlertTriangle, BarChart3, Info, Link2, PackagePlus, Plus, RefreshCw, Trash2 } from 'lucide-react';
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
import { useQuery } from '@tanstack/react-query';
import {
  errMsg,
  portalApi,
  replenishmentApi,
  useProcurementAlerts,
  useReorderRules,
  useReplenishment,
  useScorecards,
  useSpend,
  useSupplierOptions,
} from '@/features/procurement/api';
import { notify } from '@/lib/notify';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

/** Single-series bar colour (reference palette slot 1; its dark-mode step on dark surfaces). */
const BAR = 'text-[#2a78d6] dark:text-[#3987e5]';
const GRADE_TONE = { A: 'success', B: 'info', C: 'warning', D: 'danger' } as const;

/**
 * Purchasing dashboard (ADR-PROCUREMENT-001 wave 7): risk alerts, spend by supplier and by month, and supplier
 * scorecards. Statistics only — no external service.
 */
export function ProcurementDashboardPage() {
  const { data: alerts = [] } = useProcurementAlerts();
  const { data: bySupplier } = useSpend('supplier');
  const { data: byMonth } = useSpend('month');
  const { data: cards = [] } = useScorecards();
  const top = (bySupplier?.rows ?? []).slice(0, 8).map((r) => ({ label: r.label, value: Number(r.value), share: r.sharePct }));
  const months = (byMonth?.rows ?? []).map((r) => ({ label: r.label, value: Number(r.value) }));
  const orders = (bySupplier?.rows ?? []).reduce((s, r) => s + Number(r.orders), 0);
  const avgScore = cards.length ? cards.reduce((s, c) => s + c.score, 0) / cards.length : null;

  return (
    <div className="space-y-4 p-6">
      <ListPageHeader title={tx('Purchasing dashboard')} icon={BarChart3}
        description={tx('Where the money goes over the last 12 months, how suppliers perform, and what needs attention.')} />

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label={tx('Spend, last 12 months')} value={money(bySupplier?.total ?? 0)} />
        <Stat label={tx('Orders')} value={String(orders)} />
        <Stat label={tx('Active suppliers')} value={String(cards.length)} />
        <Stat label={tx('Average supplier score')} value={avgScore == null ? '—' : avgScore.toFixed(0)} />
      </div>

      {alerts.length > 0 && (
        <Card>
          <CardHeader><CardTitle>{tx('Needs attention')}</CardTitle></CardHeader>
          <CardContent className="space-y-2">
            {alerts.map((a, i) => (
              <Link key={i} to={a.link} className="flex items-center gap-2 rounded-md border px-3 py-2 text-sm hover:bg-muted">
                {a.severity === 'info' ? <Info className="h-4 w-4 text-muted-foreground" /> : <AlertTriangle className={`h-4 w-4 ${a.severity === 'danger' ? 'text-red-600' : 'text-amber-600'}`} />}
                <span className="sr-only">{a.severity === 'danger' ? tx('Critical') : a.severity === 'warning' ? tx('Warning') : tx('Note')}</span>
                {a.message}
              </Link>
            ))}
          </CardContent>
        </Card>
      )}

      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader><CardTitle>{tx('Spend by supplier')}</CardTitle></CardHeader>
          <CardContent className={BAR}>
            {top.length ? (
              <ResponsiveContainer width="100%" height={Math.max(160, top.length * 34)}>
                <BarChart data={top} layout="vertical" margin={{ left: 8, right: 24 }}>
                  <CartesianGrid horizontal={false} stroke="hsl(var(--border))" strokeDasharray="0" />
                  <XAxis type="number" tickFormatter={(v) => compact(v)} tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} axisLine={false} tickLine={false} />
                  <YAxis type="category" dataKey="label" width={140} tick={{ fontSize: 12, fill: 'hsl(var(--foreground))' }} axisLine={false} tickLine={false} />
                  <Tooltip cursor={{ fill: 'hsl(var(--muted))' }} formatter={(v: number) => [money(v), tx('Spend')]} />
                  <Bar dataKey="value" fill="currentColor" barSize={16} radius={[0, 4, 4, 0]} />
                </BarChart>
              </ResponsiveContainer>
            ) : <Empty />}
          </CardContent>
        </Card>
        <Card>
          <CardHeader><CardTitle>{tx('Spend by month')}</CardTitle></CardHeader>
          <CardContent className={BAR}>
            {months.length ? (
              <ResponsiveContainer width="100%" height={240}>
                <BarChart data={months} margin={{ left: 0, right: 8 }}>
                  <CartesianGrid vertical={false} stroke="hsl(var(--border))" />
                  <XAxis dataKey="label" tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} axisLine={false} tickLine={false} />
                  <YAxis tickFormatter={(v) => compact(v)} tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} axisLine={false} tickLine={false} width={48} />
                  <Tooltip cursor={{ fill: 'hsl(var(--muted))' }} formatter={(v: number) => [money(v), tx('Spend')]} />
                  <Bar dataKey="value" fill="currentColor" barSize={18} radius={[4, 4, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            ) : <Empty />}
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader><CardTitle>{tx('Supplier scorecards')}</CardTitle></CardHeader>
        <CardContent className="p-0">
          <Table>
            <TableHeader><TableRow>
              <TableHead>{tx('Supplier')}</TableHead><TableHead className="text-right">{tx('Orders')}</TableHead>
              <TableHead className="text-right">{tx('Spend')}</TableHead><TableHead className="text-right">{tx('On time')}</TableHead>
              <TableHead className="text-right">{tx('In full')}</TableHead><TableHead className="text-right">{tx('Rejected')}</TableHead>
              <TableHead className="text-right">{tx('Claims')}</TableHead><TableHead className="text-right">{tx('Lead time (days)')}</TableHead>
              <TableHead className="text-right">{tx('Score')}</TableHead>
            </TableRow></TableHeader>
            <TableBody>
              {!cards.length && <TableRow><TableCell colSpan={9} className="py-8 text-center text-muted-foreground">{tx('No orders in the last 12 months.')}</TableCell></TableRow>}
              {cards.map((c) => (
                <TableRow key={c.partnerId}>
                  <TableCell><Link className="hover:underline" to={`/procurement/suppliers/${c.partnerId}`}>{c.partnerName}</Link></TableCell>
                  <TableCell className="text-right">{c.orders}</TableCell>
                  <TableCell className="text-right">{money(c.spend)}</TableCell>
                  <TableCell className="text-right">{pct(c.onTimePct)}</TableCell>
                  <TableCell className="text-right">{pct(c.inFullPct)}</TableCell>
                  <TableCell className="text-right">{pct(c.rejectPct)}</TableCell>
                  <TableCell className="text-right">{c.claims}</TableCell>
                  <TableCell className="text-right">{c.averageLeadDays ?? '—'}</TableCell>
                  <TableCell className="text-right"><StatusPill tone={GRADE_TONE[c.grade]}>{c.grade} · {c.score.toFixed(0)}</StatusPill></TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return <Card><CardContent className="pt-6"><div className="text-xs text-muted-foreground">{label}</div><div className="text-2xl font-semibold">{value}</div></CardContent></Card>;
}

function Empty() {
  return <p className="py-10 text-center text-sm text-muted-foreground">{tx('No orders in the last 12 months.')}</p>;
}

const pct = (v: string | null) => (v == null ? '—' : `${v}%`);
const compact = (v: number) => new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 }).format(v);

/**
 * Replenishment (ADR-PROCUREMENT-001 wave 7): reorder rules per product and store, and what to buy now. Suggestions
 * count stock on hand, open orders and open requests; one click raises a draft purchase request.
 */
export function ReplenishmentPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const canManage = has(PERMISSIONS.replenishment.manage);
  const qc = useQueryClient();
  const [all, setAll] = useState(false);
  const { data: rows = [], isFetching, refetch } = useReplenishment(all);
  const { data: rules = [] } = useReorderRules();
  const [picked, setPicked] = useState<Record<string, boolean>>({});
  const [open, setOpen] = useState(false);
  const selected = Object.keys(picked).filter((k) => picked[k]);

  const raise = async () => {
    try {
      const pr = await replenishmentApi.raise(selected);
      if (pr.raised === false) notify.info(pr.message ?? tx('Nothing needs reordering'));
      else notify.success(tx('Purchase request {{n}} created — review and submit it', { n: pr.requestNumber ?? '' }));
      setPicked({});
      qc.invalidateQueries({ queryKey: ['replenishment'] });
    } catch (e) {
      notify.error(tx('Could not raise the request'), errMsg(e));
    }
  };

  const remove = async (id: string) => {
    try {
      await replenishmentApi.remove(id);
      qc.invalidateQueries({ queryKey: ['replenishment'] });
    } catch (e) {
      notify.error(tx('Action failed'), errMsg(e));
    }
  };

  return (
    <div className="space-y-4 p-6">
      <ListPageHeader title={tx('Replenishment')} icon={PackagePlus}
        description={tx('Never run out of cement, fuel or spares: set a minimum and maximum per store and raise purchase requests for what is low.')}
        actions={canManage && <Button onClick={() => setOpen(true)}><Plus className="mr-1 h-4 w-4" />{tx('Reorder rule')}</Button>} />
      <div className="flex flex-wrap items-center gap-3">
        <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={all} onChange={(e) => setAll(e.target.checked)} /> {tx('Show all rules')}</label>
        <Button size="sm" variant="ghost" onClick={() => refetch()} disabled={isFetching}><RefreshCw className="mr-1 h-4 w-4" />{tx('Refresh')}</Button>
        {canManage && <Button size="sm" onClick={raise} disabled={!rows.some((r) => r.needed)}>{selected.length ? tx('Raise request for {{n}} item(s)', { n: selected.length }) : tx('Raise request for everything low')}</Button>}
      </div>
      <Card>
        <CardContent className="p-0">
          <Table>
            <TableHeader><TableRow>
              <TableHead />
              <TableHead>{tx('Item')}</TableHead><TableHead>{tx('Store')}</TableHead>
              <TableHead className="text-right">{tx('On hand')}</TableHead><TableHead className="text-right">{tx('On order')}</TableHead>
              <TableHead className="text-right">{tx('Requested')}</TableHead><TableHead className="text-right">{tx('Daily use')}</TableHead>
              <TableHead className="text-right">{tx('Days of cover')}</TableHead><TableHead className="text-right">{tx('Reorder at')}</TableHead>
              <TableHead className="text-right">{tx('Buy')}</TableHead><TableHead>{tx('Supplier')}</TableHead><TableHead />
            </TableRow></TableHeader>
            <TableBody>
              {!rows.length && <TableRow><TableCell colSpan={12} className="py-8 text-center text-muted-foreground">{tx('Everything is above its minimum.')}</TableCell></TableRow>}
              {rows.map((r) => (
                <TableRow key={r.ruleId}>
                  <TableCell>{r.needed && canManage && <input type="checkbox" checked={!!picked[r.ruleId]} onChange={(e) => setPicked((p) => ({ ...p, [r.ruleId]: e.target.checked }))} />}</TableCell>
                  <TableCell>{r.productName}</TableCell>
                  <TableCell>{r.locationName}</TableCell>
                  <TableCell className="text-right">{Number(r.onHand)}</TableCell>
                  <TableCell className="text-right">{Number(r.onOrder)}</TableCell>
                  <TableCell className="text-right">{Number(r.openRequests)}</TableCell>
                  <TableCell className="text-right">{Number(r.averageDailyUse)}</TableCell>
                  <TableCell className="text-right">{r.daysOfCover ?? '—'}</TableCell>
                  <TableCell className="text-right">{Number(r.reorderPoint)}</TableCell>
                  <TableCell className="text-right font-semibold">{r.needed ? Number(r.suggestedQuantity) : '—'}</TableCell>
                  <TableCell>{r.preferredSupplierName ?? '—'}</TableCell>
                  <TableCell>{canManage && <Button size="icon" variant="ghost" aria-label={tx('Delete rule')} onClick={() => remove(r.ruleId)}><Trash2 className="h-4 w-4" /></Button>}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
      <p className="text-xs text-muted-foreground">{tx('{{n}} reorder rule(s). Rules marked "automatic" raise a draft request every night.', { n: rules.length })}</p>
      {open && <RuleDialog onClose={() => setOpen(false)} />}
    </div>
  );
}

function RuleDialog({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient();
  const { data: suppliers = [] } = useSupplierOptions();
  const products = useQuery({
    queryKey: ['products', 'options'],
    queryFn: async () => (await api.get<{ data: Array<{ id: string; name: string }> }>('/products?pageSize=200')).data.data ?? [],
  });
  const stores = useQuery({
    queryKey: ['warehouses'],
    queryFn: async () => (await api.get<{ data: Array<{ id: string; name: string }> }>('/inventory/locations?type=warehouse')).data.data ?? [],
  });
  const [f, setF] = useState({ productId: '', locationId: '', method: 'min_max', minQuantity: '', maxQuantity: '', safetyStock: '', leadTimeDays: '7', orderMultiple: '', preferredSupplierId: '', autoRaise: false });
  const set = (k: string, v: unknown) => setF((x) => ({ ...x, [k]: v }));
  const num = (v: string) => (v === '' ? undefined : Number(v));
  const save = async () => {
    try {
      await replenishmentApi.upsert({
        productId: f.productId, locationId: f.locationId, method: f.method, minQuantity: num(f.minQuantity), maxQuantity: num(f.maxQuantity),
        safetyStock: num(f.safetyStock), leadTimeDays: num(f.leadTimeDays), orderMultiple: num(f.orderMultiple),
        preferredSupplierId: f.preferredSupplierId || undefined, autoRaise: f.autoRaise,
      });
      notify.success(tx('Reorder rule saved'));
      qc.invalidateQueries({ queryKey: ['replenishment'] });
      onClose();
    } catch (e) {
      notify.error(tx('Could not save the rule'), errMsg(e));
    }
  };
  const sel = 'h-9 w-full rounded-md border bg-background px-2 text-sm';
  return (
    <Dialog open onOpenChange={(v) => !v && onClose()}>
      <DialogContent>
        <DialogHeader><DialogTitle>{tx('Reorder rule')}</DialogTitle></DialogHeader>
        <div className="grid gap-3 sm:grid-cols-2">
          <div className="sm:col-span-2"><Label>{tx('Item')}</Label>
            <select className={sel} value={f.productId} onChange={(e) => set('productId', e.target.value)}>
              <option value="">{tx('Select…')}</option>{(products.data ?? []).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            </select></div>
          <div><Label>{tx('Store')}</Label>
            <select className={sel} value={f.locationId} onChange={(e) => set('locationId', e.target.value)}>
              <option value="">{tx('Select…')}</option>{(stores.data ?? []).map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select></div>
          <div><Label>{tx('Method')}</Label>
            <select className={sel} value={f.method} onChange={(e) => set('method', e.target.value)}>
              <option value="min_max">{tx('Minimum / maximum')}</option><option value="reorder_point">{tx('Reorder point (from usage)')}</option>
            </select></div>
          <div><Label>{tx('Minimum')}</Label><Input type="number" min={0} value={f.minQuantity} onChange={(e) => set('minQuantity', e.target.value)} /></div>
          <div><Label>{tx('Maximum')}</Label><Input type="number" min={0} value={f.maxQuantity} onChange={(e) => set('maxQuantity', e.target.value)} /></div>
          <div><Label>{tx('Safety stock')}</Label><Input type="number" min={0} value={f.safetyStock} onChange={(e) => set('safetyStock', e.target.value)} /></div>
          <div><Label>{tx('Lead time (days)')}</Label><Input type="number" min={0} value={f.leadTimeDays} onChange={(e) => set('leadTimeDays', e.target.value)} /></div>
          <div><Label>{tx('Order in multiples of')}</Label><Input type="number" min={0} value={f.orderMultiple} onChange={(e) => set('orderMultiple', e.target.value)} /></div>
          <div><Label>{tx('Preferred supplier')}</Label>
            <select className={sel} value={f.preferredSupplierId} onChange={(e) => set('preferredSupplierId', e.target.value)}>
              <option value="">{tx('Any')}</option>{suppliers.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select></div>
          <label className="flex items-center gap-2 text-sm sm:col-span-2"><input type="checkbox" checked={f.autoRaise} onChange={(e) => set('autoRaise', e.target.checked)} /> {tx('Automatic: raise a draft request every night when low')}</label>
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={save} disabled={!f.productId || !f.locationId}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** "Supplier link" button: issues a secure portal link for an RFQ invitation or an order and copies it. */
export function PortalLinkButton({ documentType, documentId, partnerId, label }: { documentType: 'rfq' | 'purchase_order'; documentId: string; partnerId?: string; label?: string }) {
  const has = useAuthStore((s) => s.hasPermission);
  if (!has(PERMISSIONS.supplierPortal.manage)) return null;
  const issue = async () => {
    try {
      const link = await portalApi.issue({ documentType, documentId, partnerId });
      const url = `${window.location.origin}${link.path}`;
      try { await navigator.clipboard.writeText(url); } catch { /* clipboard may be blocked; the prompt below shows it */ }
      window.prompt(tx('Send this link to the supplier (copied). It expires on {{d}}.', { d: link.expiresAt.slice(0, 10) }), url);
    } catch (e) {
      notify.error(tx('Could not create the link'), errMsg(e));
    }
  };
  return <Button size="sm" variant="outline" onClick={issue}><Link2 className="mr-1 h-4 w-4" />{label ?? tx('Supplier link')}</Button>;
}
