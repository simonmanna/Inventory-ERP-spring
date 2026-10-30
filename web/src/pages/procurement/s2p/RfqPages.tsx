import { useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Award, FileQuestion, Plus, Send, Trophy, XCircle } from 'lucide-react';
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
import {
  errMsg, rfqApi, useRfq, useRfqComparison, useRfqMutation, useRfqs, useSupplierOptions, type RfqStatus,
} from '@/features/procurement/api';
import { notify } from '@/lib/notify';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { PortalLinkButton } from './PlanningPages';

const STATUS_TONE: Record<RfqStatus, 'neutral' | 'info' | 'success' | 'warning' | 'danger'> = {
  draft: 'neutral', sent: 'info', closed: 'warning', awarded: 'success', cancelled: 'danger',
};
const STATUS_LABEL: Record<RfqStatus, string> = {
  draft: tx('Draft'), sent: tx('Open for quotes'), closed: tx('Closed'), awarded: tx('Awarded'), cancelled: tx('Cancelled'),
};
const CRITERIA: Array<[string, string]> = [
  ['price', tx('Price')], ['quality', tx('Quality')], ['delivery', tx('Delivery')], ['terms', tx('Payment terms')], ['performance', tx('Past performance')],
];

/** Requests for quotation (ADR-PROCUREMENT-001 wave 4). */
export function RfqListPage() {
  const navigate = useNavigate();
  const { data: rows = [], isLoading } = useRfqs();
  return (
    <div className="space-y-4 p-6">
      <ListPageHeader title={tx('Requests for quotation')} icon={FileQuestion}
        description={tx('Ask several suppliers to quote, compare total delivered cost and score, and award line by line. Start from the sourcing workbench.')}
        actions={<Button asChild variant="outline"><Link to="/procurement/sourcing">{tx('Sourcing workbench')}</Link></Button>} />
      <Card>
        <CardContent className="p-0">
          <Table>
            <TableHeader><TableRow>
              <TableHead>{tx('RFQ')}</TableHead><TableHead>{tx('Title')}</TableHead><TableHead>{tx('Status')}</TableHead>
              <TableHead>{tx('Deadline')}</TableHead><TableHead className="text-right">{tx('Lines')}</TableHead><TableHead className="text-right">{tx('Responses')}</TableHead>
            </TableRow></TableHeader>
            <TableBody>
              {isLoading && <TableRow><TableCell colSpan={6}>{tx('Loading…')}</TableCell></TableRow>}
              {!isLoading && !rows.length && <TableRow><TableCell colSpan={6} className="py-8 text-center text-muted-foreground">{tx('No RFQs yet — select request lines on the sourcing workbench and choose "Request quotations".')}</TableCell></TableRow>}
              {rows.map((r) => (
                <TableRow key={r.id} className="cursor-pointer" onClick={() => navigate(`/procurement/rfqs/${r.id}`)}>
                  <TableCell className="font-mono text-xs">{r.rfqNumber}</TableCell>
                  <TableCell>{r.title}</TableCell>
                  <TableCell><StatusPill tone={STATUS_TONE[r.status]}>{STATUS_LABEL[r.status]}</StatusPill></TableCell>
                  <TableCell>{r.deadline ? date(r.deadline) : '—'}</TableCell>
                  <TableCell className="text-right">{r.lines}</TableCell>
                  <TableCell className="text-right">{r.responded}/{r.invited}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  );
}

/** One RFQ: suppliers, quotation entry, comparison matrix, per-line award and order creation. */
export function RfqDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const canManage = has(PERMISSIONS.rfq.manage);
  const canAward = has(PERMISSIONS.rfq.award);
  const { data: rfq } = useRfq(id);
  const { data: cmp } = useRfqComparison(id, !!rfq && rfq.status !== 'draft');
  const { data: suppliers = [] } = useSupplierOptions();
  const warehouses = useQuery({
    queryKey: ['warehouses'],
    queryFn: async () => (await api.get<{ data: Array<{ id: string; name: string }> }>('/inventory/locations?type=warehouse')).data.data ?? [],
  });
  const run = useRfqMutation((fn: () => Promise<unknown>) => fn());
  const [invite, setInvite] = useState('');
  const [quoteFor, setQuoteFor] = useState<string | null>(null);
  const [quote, setQuote] = useState<Record<string, string>>({});
  const [quoteHead, setQuoteHead] = useState({ leadTimeDays: '', paymentTermsDays: '', freightAmount: '', validUntil: '', supplierReference: '', qualityScore: '' });
  const [choice, setChoice] = useState<Record<string, { partnerId: string; justification: string }>>({});
  const [warehouseId, setWarehouseId] = useState('');

  const pick = useMemo(() => {
    const out: Record<string, { partnerId: string; justification: string }> = {};
    for (const l of cmp?.lines ?? []) out[l.id] = choice[l.id] ?? { partnerId: l.bestPartnerId ?? '', justification: '' };
    return out;
  }, [cmp, choice]);

  if (!rfq) return <div className="p-6 text-sm text-muted-foreground">{tx('Loading…')}</div>;

  const exec = async (fn: () => Promise<unknown>, ok: string) => {
    try { await run.mutateAsync(fn); notify.success(ok); return true; } catch (e) { notify.error(tx('Action failed'), errMsg(e)); return false; }
  };

  const submitQuote = async () => {
    if (!quoteFor) return;
    const lines = rfq.lines.filter((l) => quote[l.id] !== undefined && quote[l.id] !== '').map((l) => ({ rfqLineId: l.id, unitPrice: Number(quote[l.id]) }));
    const head = Object.fromEntries(Object.entries(quoteHead).filter(([, v]) => v !== '').map(([k, v]) => [k, ['supplierReference', 'validUntil'].includes(k) ? v : Number(v)]));
    if (await exec(() => rfqApi.quote(rfq.id, { partnerId: quoteFor, lines, ...head }), tx('Quotation recorded'))) {
      setQuoteFor(null); setQuote({}); setQuoteHead({ leadTimeDays: '', paymentTermsDays: '', freightAmount: '', validUntil: '', supplierReference: '', qualityScore: '' });
    }
  };

  const award = async () => {
    const lines = Object.entries(pick).filter(([, v]) => v.partnerId).map(([rfqLineId, v]) => ({ rfqLineId, partnerId: v.partnerId, justification: v.justification || undefined }));
    try {
      const res = await run.mutateAsync(() => rfqApi.award(rfq.id, lines)) as any;
      notify.success(res?.approvalRequired ? tx('Award saved — waiting for approval') : tx('Award saved'));
    } catch (e) { notify.error(tx('Could not award'), errMsg(e)); }
  };

  const createOrders = async () => {
    const wh = warehouseId || warehouses.data?.[0]?.id;
    if (!wh) return;
    try {
      const res = await run.mutateAsync(() => rfqApi.orders(rfq.id, { warehouseId: wh })) as any;
      notify.success(tx('{{n}} purchase order(s) created', { n: res.orders.length }));
      if (res.orders.length === 1) navigate(`/procurement/purchase-orders/${res.orders[0].id}`);
    } catch (e) { notify.error(tx('Could not create orders'), errMsg(e)); }
  };

  const unordered = rfq.awards.filter((a) => !a.purchaseOrderId).length;

  return (
    <div className="space-y-4 p-6">
      <ListPageHeader title={`${rfq.rfqNumber} · ${rfq.title}`} icon={FileQuestion}
        description={rfq.deadline ? tx('Quotes due {{d}}', { d: date(rfq.deadline) }) : undefined}
        actions={
          <>
            <StatusPill tone={STATUS_TONE[rfq.status]}>{STATUS_LABEL[rfq.status]}</StatusPill>
            {canManage && rfq.status === 'draft' && <Button size="sm" onClick={() => exec(() => rfqApi.action(rfq.id, 'send'), tx('RFQ sent'))}><Send className="mr-1 h-4 w-4" />{tx('Send to suppliers')}</Button>}
            {canManage && rfq.status === 'sent' && <Button size="sm" variant="outline" onClick={() => exec(() => rfqApi.action(rfq.id, 'close'), tx('Quotations closed'))}>{tx('Close quotations')}</Button>}
            {canManage && ['draft', 'sent', 'closed'].includes(rfq.status) && <Button size="sm" variant="ghost" onClick={() => exec(() => rfqApi.action(rfq.id, 'cancel'), tx('RFQ cancelled'))}><XCircle className="mr-1 h-4 w-4" />{tx('Cancel')}</Button>}
          </>
        } />

      <div className="grid gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader><CardTitle>{tx('Lines')}</CardTitle></CardHeader>
          <CardContent className="p-0">
            <Table>
              <TableHeader><TableRow><TableHead>#</TableHead><TableHead>{tx('Item')}</TableHead><TableHead className="text-right">{tx('Qty')}</TableHead><TableHead>{tx('Required date')}</TableHead></TableRow></TableHeader>
              <TableBody>
                {rfq.lines.map((l) => (
                  <TableRow key={l.id}><TableCell>{l.lineNumber}</TableCell><TableCell>{l.description}</TableCell><TableCell className="text-right">{Number(l.quantity)}</TableCell><TableCell>{l.requiredDate ? date(l.requiredDate) : '—'}</TableCell></TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
        <Card>
          <CardHeader><CardTitle>{tx('Suppliers')}</CardTitle></CardHeader>
          <CardContent className="space-y-2 text-sm">
            {rfq.suppliers.map((s) => (
              <div key={s.partnerId} className="flex items-center justify-between gap-2">
                <div>
                  <div className="font-medium">{s.partnerName}</div>
                  <div className="text-xs text-muted-foreground">
                    {s.status === 'responded' ? tx('Quoted (rev. {{r}})', { r: s.lastRevision ?? 1 }) : s.status === 'declined' ? tx('Declined') : tx('Invited')}
                  </div>
                </div>
                {canManage && rfq.status === 'sent' && s.status !== 'declined' && (
                  <Button size="sm" variant="outline" onClick={() => setQuoteFor(s.partnerId)}>{s.status === 'responded' ? tx('Revise quote') : tx('Enter quote')}</Button>
                )}
                {['draft', 'sent'].includes(rfq.status) && s.status !== 'declined' && (
                  <PortalLinkButton documentType="rfq" documentId={rfq.id} partnerId={s.partnerId} label={tx('Link')} />
                )}
                {canManage && rfq.status === 'draft' && (
                  <Button size="sm" variant="ghost" onClick={() => exec(() => rfqApi.removeSupplier(rfq.id, s.partnerId), tx('Supplier removed'))}>{tx('Remove')}</Button>
                )}
              </div>
            ))}
            {canManage && ['draft', 'sent'].includes(rfq.status) && (
              <div className="flex gap-2 pt-2">
                <select className="h-9 flex-1 rounded-md border bg-background px-2 text-sm" value={invite} onChange={(e) => setInvite(e.target.value)}>
                  <option value="">{tx('Invite a supplier…')}</option>
                  {suppliers.filter((s) => !rfq.suppliers.some((x) => x.partnerId === s.id)).map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
                </select>
                <Button size="sm" disabled={!invite} onClick={async () => { if (await exec(() => rfqApi.addSupplier(rfq.id, invite), tx('Supplier invited'))) setInvite(''); }}><Plus className="h-4 w-4" /></Button>
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      {cmp && cmp.suppliers.length > 0 && (
        <Card>
          <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2">
            <CardTitle className="flex items-center gap-2"><Trophy className="h-4 w-4" />{tx('Comparison')}</CardTitle>
            <div className="text-xs text-muted-foreground">
              {tx('Weights')}: {CRITERIA.map(([k, label]) => `${label} ${cmp.weights[k] ?? 0}%`).join(' · ')}
            </div>
          </CardHeader>
          <CardContent className="space-y-4">
            <Table>
              <TableHeader><TableRow>
                <TableHead>{tx('Supplier')}</TableHead><TableHead className="text-right">{tx('Delivered total')}</TableHead>
                <TableHead className="text-right">{tx('Lead time')}</TableHead><TableHead className="text-right">{tx('Coverage')}</TableHead>
                {CRITERIA.map(([k, label]) => <TableHead key={k} className="text-right">{label}</TableHead>)}
                <TableHead className="text-right">{tx('Score')}</TableHead>
              </TableRow></TableHeader>
              <TableBody>
                {cmp.suppliers.map((s) => (
                  <TableRow key={s.quotationId} className={s.partnerId === cmp.recommendedPartnerId ? 'bg-emerald-50 dark:bg-emerald-950/30' : ''}>
                    <TableCell className="font-medium">{s.partnerName}{s.partnerId === cmp.recommendedPartnerId && <span className="ml-2 text-xs text-emerald-700">★ {tx('best score')}</span>}</TableCell>
                    <TableCell className="text-right">{money(Number(s.deliveredTotal), cmp.functionalCurrency)}</TableCell>
                    <TableCell className="text-right">{tx('{{n}} days', { n: s.leadTimeDays })}</TableCell>
                    <TableCell className="text-right">{s.coveragePct}%</TableCell>
                    {CRITERIA.map(([k]) => <TableCell key={k} className="text-right text-xs">{s.scores[k]}</TableCell>)}
                    <TableCell className="text-right font-semibold">{s.weightedScore}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>

            <Table>
              <TableHeader><TableRow>
                <TableHead>{tx('Line')}</TableHead>
                {cmp.suppliers.map((s) => <TableHead key={s.partnerId} className="text-right">{s.partnerName}</TableHead>)}
                {canAward && ['sent', 'closed'].includes(rfq.status) && <TableHead>{tx('Award to')}</TableHead>}
              </TableRow></TableHeader>
              <TableBody>
                {cmp.lines.map((l) => {
                  const chosen = pick[l.id];
                  const notLowest = chosen?.partnerId && chosen.partnerId !== l.bestPartnerId;
                  return (
                    <TableRow key={l.id}>
                      <TableCell>{l.description}<div className="text-xs text-muted-foreground">{Number(l.quantity)}</div></TableCell>
                      {cmp.suppliers.map((s) => {
                        const o = l.offers.find((x) => x.partnerId === s.partnerId);
                        return (
                          <TableCell key={s.partnerId} className={`text-right ${o?.lowest ? 'font-semibold text-emerald-700' : ''}`}>
                            {o ? <><div>{money(Number(o.deliveredUnitCost), cmp.functionalCurrency)}</div><div className="text-xs text-muted-foreground">{tx('quoted {{p}}', { p: money(Number(o.unitPrice)) })}</div></> : '—'}
                          </TableCell>
                        );
                      })}
                      {canAward && ['sent', 'closed'].includes(rfq.status) && (
                        <TableCell className="min-w-[220px] space-y-1">
                          <select className="h-8 w-full rounded-md border bg-background px-2 text-sm" value={chosen?.partnerId ?? ''}
                            onChange={(e) => setChoice({ ...choice, [l.id]: { partnerId: e.target.value, justification: chosen?.justification ?? '' } })}>
                            <option value="">{tx('Do not award')}</option>
                            {l.offers.map((o) => <option key={o.partnerId} value={o.partnerId}>{cmp.suppliers.find((s) => s.partnerId === o.partnerId)?.partnerName}</option>)}
                          </select>
                          {notLowest && (
                            <Input className="h-8" placeholder={tx('Why not the lowest offer?')} value={chosen.justification}
                              onChange={(e) => setChoice({ ...choice, [l.id]: { partnerId: chosen.partnerId, justification: e.target.value } })} />
                          )}
                        </TableCell>
                      )}
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
            {canAward && ['sent', 'closed'].includes(rfq.status) && (
              <div className="flex justify-end"><Button onClick={award}><Award className="mr-1 h-4 w-4" />{tx('Award')}</Button></div>
            )}
          </CardContent>
        </Card>
      )}

      {rfq.awards.length > 0 && (
        <Card>
          <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2">
            <CardTitle>{tx('Awards')}</CardTitle>
            {unordered > 0 && rfq.status === 'awarded' && (
              <div className="flex items-center gap-2">
                <select className="h-9 rounded-md border bg-background px-2 text-sm" value={warehouseId} onChange={(e) => setWarehouseId(e.target.value)}>
                  {(warehouses.data ?? []).map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}
                </select>
                <Button onClick={createOrders}>{tx('Create purchase orders')}</Button>
              </div>
            )}
          </CardHeader>
          <CardContent className="p-0">
            <Table>
              <TableHeader><TableRow><TableHead>{tx('Line')}</TableHead><TableHead>{tx('Supplier')}</TableHead><TableHead className="text-right">{tx('Qty')}</TableHead><TableHead className="text-right">{tx('Unit price')}</TableHead><TableHead>{tx('Justification')}</TableHead><TableHead>{tx('Order')}</TableHead></TableRow></TableHeader>
              <TableBody>
                {rfq.awards.map((a) => (
                  <TableRow key={a.id}>
                    <TableCell>{rfq.lines.find((l) => l.id === a.rfqLineId)?.description}</TableCell>
                    <TableCell>{a.partnerName}</TableCell>
                    <TableCell className="text-right">{Number(a.quantity)}</TableCell>
                    <TableCell className="text-right">{money(Number(a.unitPrice))}</TableCell>
                    <TableCell className="text-xs">{a.lowestPrice ? tx('Lowest offer') : a.justification}</TableCell>
                    <TableCell>{a.purchaseOrderId ? <Link className="font-mono text-xs hover:underline" to={`/procurement/purchase-orders/${a.purchaseOrderId}`}>{a.orderNumber}</Link> : '—'}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}

      <Dialog open={!!quoteFor} onOpenChange={(o) => !o && setQuoteFor(null)}>
        <DialogContent className="max-w-2xl">
          <DialogHeader><DialogTitle>{tx('Quotation from {{s}}', { s: rfq.suppliers.find((s) => s.partnerId === quoteFor)?.partnerName ?? '' })}</DialogTitle></DialogHeader>
          <div className="grid gap-3">
            {rfq.lines.map((l) => (
              <div key={l.id} className="grid grid-cols-3 items-center gap-2">
                <div className="col-span-2 text-sm">{l.description} <span className="text-muted-foreground">× {Number(l.quantity)}</span></div>
                <Input type="number" min={0} placeholder={tx('Unit price')} value={quote[l.id] ?? ''} onChange={(e) => setQuote({ ...quote, [l.id]: e.target.value })} />
              </div>
            ))}
            <div className="grid grid-cols-3 gap-2">
              <div><Label>{tx('Lead time (days)')}</Label><Input type="number" min={0} value={quoteHead.leadTimeDays} onChange={(e) => setQuoteHead({ ...quoteHead, leadTimeDays: e.target.value })} /></div>
              <div><Label>{tx('Payment terms (days)')}</Label><Input type="number" min={0} value={quoteHead.paymentTermsDays} onChange={(e) => setQuoteHead({ ...quoteHead, paymentTermsDays: e.target.value })} /></div>
              <div><Label>{tx('Freight / delivery charge')}</Label><Input type="number" min={0} value={quoteHead.freightAmount} onChange={(e) => setQuoteHead({ ...quoteHead, freightAmount: e.target.value })} /></div>
              <div><Label>{tx('Valid until')}</Label><Input type="date" value={quoteHead.validUntil} onChange={(e) => setQuoteHead({ ...quoteHead, validUntil: e.target.value })} /></div>
              <div><Label>{tx('Supplier ref.')}</Label><Input value={quoteHead.supplierReference} onChange={(e) => setQuoteHead({ ...quoteHead, supplierReference: e.target.value })} /></div>
              <div><Label>{tx('Quality score (0–100)')}</Label><Input type="number" min={0} max={100} value={quoteHead.qualityScore} onChange={(e) => setQuoteHead({ ...quoteHead, qualityScore: e.target.value })} /></div>
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setQuoteFor(null)}>{tx('Cancel')}</Button>
            <Button onClick={submitQuote}>{tx('Save quotation')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
