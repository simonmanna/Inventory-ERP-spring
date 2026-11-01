import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';
import { money } from '@/lib/format';
import { notify } from '@/lib/notify';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';

const SELECT = 'h-9 rounded-md border border-input bg-background px-3 text-sm';
const today = new Date().toISOString().slice(0, 10);
const monthStart = `${today.slice(0, 8)}01`;

type Row = Record<string, unknown>;
interface Named { id: string; code: string; name: string }

const s = (v: unknown) => (v == null ? '' : String(v));
const day = (v: unknown) => s(v).slice(0, 10);

function useList<T = Row>(key: string[], url: string, enabled = true) {
  return useQuery({ queryKey: ['cost-ops', ...key], enabled, queryFn: async () => (await api.get<T>(url)).data });
}

function useAction() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ url, body }: { url: string; body?: unknown }) => (await idempotentPost(url, body)).data as Row,
    onSuccess: () => {
      notify.success(tx('Saved'));
      void qc.invalidateQueries({ queryKey: ['cost-ops'] });
    },
  });
}

/** Cost Management CM-4 … CM-7 (ADR-S034 … ADR-S037): settlement, quarry provisions, landed accruals, distribution. */
export function CostOperationsPage() {
  const has = useAuthStore((st) => st.hasPermission);
  const [tab, setTab] = useState<'absorption' | 'quarry' | 'landed' | 'distribution'>('absorption');
  const [period, setPeriod] = useState({ from: monthStart, to: today });
  const tabs = [
    ['absorption', tx('Absorption settlement')],
    ['quarry', tx('Quarry')],
    ['landed', tx('Landed cost accruals')],
    ['distribution', tx('Distribution')],
  ] as const;
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">{tx('Cost operations')}</h1>
        <p className="text-sm text-muted-foreground">
          {tx('Period costing: overhead settlement by cause, quarry stripping and rehabilitation, landed-cost accruals and delivery cost.')}
        </p>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        {tabs.map(([k, label]) => (
          <Button key={k} size="sm" variant={tab === k ? 'default' : 'outline'} onClick={() => setTab(k)}>{label}</Button>
        ))}
        <div className="ml-auto flex gap-2">
          <Input type="date" value={period.from} onChange={(e) => setPeriod({ ...period, from: e.target.value })} />
          <Input type="date" value={period.to} onChange={(e) => setPeriod({ ...period, to: e.target.value })} />
        </div>
      </div>
      {tab === 'absorption' && <Absorption period={period} canPost={has(PERMISSIONS.advancedCosting.post)} canManage={has(PERMISSIONS.advancedCosting.manage)} />}
      {tab === 'quarry' && <Quarry period={period} />}
      {tab === 'landed' && <Landed canPost={has(PERMISSIONS.landedCost.post)} />}
      {tab === 'distribution' && <Distribution period={period} canPost={has(PERMISSIONS.advancedCosting.post)} />}
    </div>
  );
}

function Absorption({ period, canPost, canManage }: { period: { from: string; to: string }; canPost: boolean; canManage: boolean }) {
  const { data: centres = [] } = useList<Named[]>(['work-centers'], '/manufacturing/work-centers');
  const { data: capacities = [] } = useList<Row[]>(['capacities'], '/costing/normal-capacities');
  const { data: settlements = [] } = useList<Row[]>(['settlements'], '/costing/absorption-settlements');
  const action = useAction();
  const [wc, setWc] = useState('');
  const [classification, setClassification] = useState('');
  const [justification, setJustification] = useState('');
  const [shown, setShown] = useState<Row | null>(null);
  const preview = useMutation({
    mutationFn: async () => (await api.post<Row>('/costing/absorption-settlements/preview', { workCenterId: wc, ...period, classification: classification || undefined, justification: justification || undefined })).data,
    onSuccess: setShown,
  });
  const list = Array.isArray(centres) ? centres : [];
  const blockers = (shown?.blockers as string[] | undefined) ?? [];
  return (
    <>
      <Card>
        <CardHeader><CardTitle>{tx('Absorption settlement')}</CardTitle></CardHeader>
        <CardContent className="space-y-3">
          <div className="grid gap-3 md:grid-cols-4">
            <select className={SELECT} value={wc} onChange={(e) => setWc(e.target.value)}>
              <option value="">{tx('Work centre')}</option>
              {list.map((c) => <option key={c.id} value={c.id}>{c.code} · {c.name}</option>)}
            </select>
            <select className={SELECT} value={classification} onChange={(e) => setClassification(e.target.value)}>
              <option value="">{tx('Classification')}</option>
              <option value="normal">{tx('Normal')}</option>
              <option value="abnormal">{tx('Abnormal')}</option>
            </select>
            <Input placeholder={tx('Justification')} value={justification} onChange={(e) => setJustification(e.target.value)} />
            <div className="flex gap-2">
              {canManage && <Button variant="outline" disabled={!wc} onClick={() => preview.mutate()}>{tx('Preview')}</Button>}
              {canPost && <Button disabled={!wc || !shown || blockers.length > 0} onClick={() => action.mutate({ url: '/costing/absorption-settlements', body: { workCenterId: wc, ...period, classification: classification || undefined, justification: justification || undefined } })}>{tx('Post')}</Button>}
            </div>
          </div>
          {shown && (
            <div className="space-y-2 text-sm">
              <div className="grid gap-2 md:grid-cols-4">
                {['actual', 'absorbed', 'volume', 'spending', 'rateError', 'total', 'normalActivity', 'actualActivity'].map((k) => (
                  <div key={k} className="rounded border p-2"><div className="text-muted-foreground">{k}</div><div className="font-medium">{s(shown[k])}</div></div>
                ))}
              </div>
              {blockers.map((b) => <div key={b} className="text-destructive">• {b}</div>)}
              <Table>
                <TableHeader><TableRow><TableHead>{tx('Cause')}</TableHead><TableHead>{tx('Treatment')}</TableHead><TableHead>{tx('Target')}</TableHead><TableHead className="text-right">{tx('Amount')}</TableHead></TableRow></TableHeader>
                <TableBody>
                  {((shown.lines as Row[] | undefined) ?? []).map((l, i) => (
                    <TableRow key={i}><TableCell>{s(l.cause)}</TableCell><TableCell>{s(l.treatment)}</TableCell><TableCell>{s(l.target)}</TableCell><TableCell className="text-right">{money(s(l.amount))}</TableCell></TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </CardContent>
      </Card>
      <Card>
        <CardHeader><CardTitle>{tx('Normal capacity')}</CardTitle></CardHeader>
        <CardContent>
          <Table>
            <TableHeader><TableRow><TableHead>{tx('Work centre')}</TableHead><TableHead>{tx('Normal capacity')}</TableHead><TableHead>{tx('Budget')}</TableHead><TableHead>{tx('Effective from')}</TableHead><TableHead>{tx('Status')}</TableHead><TableHead /></TableRow></TableHeader>
            <TableBody>
              {capacities.map((c) => (
                <TableRow key={s(c.id)}>
                  <TableCell>{s(c.workCenterCode)}</TableCell>
                  <TableCell>{s(c.normalCapacity)} {s(c.basis)} / {s(c.periodDays)}d</TableCell>
                  <TableCell>{money(s(c.budgetFixedOverhead))}</TableCell>
                  <TableCell>{day(c.effectiveFrom)}</TableCell>
                  <TableCell><Badge variant="secondary">{s(c.status)}</Badge></TableCell>
                  <TableCell className="text-right">
                    {c.status === 'proposed' && <Button size="sm" variant="outline" onClick={() => action.mutate({ url: `/costing/normal-capacities/${s(c.id)}/approve` })}>{tx('Approve')}</Button>}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
      <Card>
        <CardHeader><CardTitle>{tx('Settlements')}</CardTitle></CardHeader>
        <CardContent>
          <Table>
            <TableHeader><TableRow><TableHead>{tx('Number')}</TableHead><TableHead>{tx('Work centre')}</TableHead><TableHead>{tx('Period')}</TableHead><TableHead>{tx('Volume')}</TableHead><TableHead>{tx('Spending')}</TableHead><TableHead>{tx('Status')}</TableHead><TableHead /></TableRow></TableHeader>
            <TableBody>
              {settlements.map((r) => (
                <TableRow key={s(r.id)}>
                  <TableCell>{s(r.number)}</TableCell>
                  <TableCell>{s(r.workCenterCode)}</TableCell>
                  <TableCell>{day(r.periodFrom)} – {day(r.periodTo)}</TableCell>
                  <TableCell>{money(s(r.volume))}</TableCell>
                  <TableCell>{money(s(r.spending))}</TableCell>
                  <TableCell><Badge variant="secondary">{s(r.status)}</Badge></TableCell>
                  <TableCell className="text-right">
                    {canPost && r.status === 'posted' && <Button size="sm" variant="outline" onClick={() => action.mutate({ url: `/costing/absorption-settlements/${s(r.id)}/reverse` })}>{tx('Reverse')}</Button>}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </>
  );
}

function Quarry({ period }: { period: { from: string; to: string } }) {
  const { data: rollForward } = useList<{ rows: Row[] }>(['rehab', period.from, period.to], `/quarry/rehab-roll-forward?from=${period.from}&to=${period.to}`);
  const { data: assets = [] } = useList<Row[]>(['stripping'], '/quarry/stripping-assets');
  const { data: kpis } = useList<{ rows: Row[]; costPerTonne: string | null }>(['kpis', period.from, period.to], `/quarry/cost-kpis?from=${period.from}&to=${period.to}`);
  return (
    <>
      <Card>
        <CardHeader><CardTitle>{tx('Rehabilitation')}</CardTitle></CardHeader>
        <CardContent>
          <Table>
            <TableHeader><TableRow><TableHead>{tx('Code')}</TableHead><TableHead>{tx('Opening')}</TableHead><TableHead>{tx('Initial')}</TableHead><TableHead>{tx('Production')}</TableHead><TableHead>{tx('Unwind')}</TableHead><TableHead>{tx('Remeasurement')}</TableHead><TableHead>{tx('Utilisation')}</TableHead><TableHead>{tx('Closing')}</TableHead><TableHead>{tx('Difference')}</TableHead></TableRow></TableHeader>
            <TableBody>
              {(rollForward?.rows ?? []).map((r) => (
                <TableRow key={s(r.obligationId)}>
                  <TableCell>{s(r.code)} · {s(r.zoneCode)}</TableCell>
                  {['opening', 'initial', 'production', 'unwind', 'remeasurement', 'utilisation', 'closing', 'difference'].map((k) => <TableCell key={k}>{money(s(r[k]))}</TableCell>)}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
      <Card>
        <CardHeader><CardTitle>{tx('Stripping assets')}</CardTitle></CardHeader>
        <CardContent>
          <Table>
            <TableHeader><TableRow><TableHead>{tx('Code')}</TableHead><TableHead>{tx('Status')}</TableHead><TableHead>{tx('Carrying amount')}</TableHead></TableRow></TableHeader>
            <TableBody>
              {assets.map((a) => (
                <TableRow key={s(a.id)}><TableCell>{s(a.code)} · {s(a.zoneCode)}</TableCell><TableCell><Badge variant="secondary">{s(a.status)}</Badge></TableCell><TableCell>{money(s(a.carrying))}</TableCell></TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
      <Card>
        <CardHeader><CardTitle>{tx('Cost per tonne')} · {s(kpis?.costPerTonne)}</CardTitle></CardHeader>
        <CardContent>
          <Table>
            <TableHeader><TableRow><TableHead>{tx('Code')}</TableHead><TableHead>{tx('Tonnes')}</TableHead><TableHead>{tx('Total')}</TableHead><TableHead>{tx('Cost per tonne')}</TableHead></TableRow></TableHeader>
            <TableBody>
              {(kpis?.rows ?? []).map((r, i) => (
                <TableRow key={i}><TableCell>{s(r.zoneCode)}</TableCell><TableCell>{s(r.tonnes)}</TableCell><TableCell>{money(s(r.cost))}</TableCell><TableCell>{s(r.costPerTonne)}</TableCell></TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </>
  );
}

function Landed({ canPost }: { canPost: boolean }) {
  const { data: accruals = [] } = useList<Row[]>(['accruals'], '/procurement/landed-cost-accruals');
  const { data: recon } = useList<Row>(['accrual-recon'], '/procurement/landed-cost-accruals/reconciliation');
  const action = useAction();
  const [grn, setGrn] = useState('');
  const [voucher, setVoucher] = useState('');
  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between gap-2">
        <CardTitle>{tx('Landed cost accruals')}</CardTitle>
        <span className="text-sm text-muted-foreground">{tx('Difference')}: {money(s(recon?.difference))}</span>
      </CardHeader>
      <CardContent className="space-y-3">
        {canPost && (
          <div className="flex flex-wrap gap-2">
            <Input className="max-w-xs" placeholder={tx('Goods receipt')} value={grn} onChange={(e) => setGrn(e.target.value)} />
            <Button disabled={!grn} onClick={() => action.mutate({ url: '/procurement/landed-cost-accruals', body: { goodsReceiptId: grn } })}>{tx('Accrue')}</Button>
            <Input className="max-w-xs" placeholder={tx('Voucher')} value={voucher} onChange={(e) => setVoucher(e.target.value)} />
          </div>
        )}
        <Table>
          <TableHeader><TableRow><TableHead>{tx('Number')}</TableHead><TableHead>{tx('Goods receipt')}</TableHead><TableHead>{tx('Amount')}</TableHead><TableHead>{tx('Actual')}</TableHead><TableHead>{tx('Status')}</TableHead><TableHead /></TableRow></TableHeader>
          <TableBody>
            {accruals.map((a) => (
              <TableRow key={s(a.id)}>
                <TableCell>{s(a.number)}</TableCell>
                <TableCell>{s(a.receiptNumber)}</TableCell>
                <TableCell>{money(s(a.amount))}</TableCell>
                <TableCell>{a.actualAmount == null ? '—' : money(s(a.actualAmount))}</TableCell>
                <TableCell><Badge variant="secondary">{s(a.status)}</Badge></TableCell>
                <TableCell className="space-x-2 text-right">
                  {canPost && a.status === 'posted' && (
                    <>
                      <Button size="sm" disabled={!voucher} onClick={() => action.mutate({ url: `/procurement/landed-cost-accruals/${s(a.id)}/clear`, body: { voucherId: voucher } })}>{tx('Clear')}</Button>
                      <Button size="sm" variant="outline" onClick={() => action.mutate({ url: `/procurement/landed-cost-accruals/${s(a.id)}/reverse` })}>{tx('Reverse')}</Button>
                    </>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  );
}

function Distribution({ period, canPost }: { period: { from: string; to: string }; canPost: boolean }) {
  const { data: runs = [] } = useList<Row[]>(['delivery-runs'], '/costing/delivery-cost-runs');
  const { data: clearing } = useList<{ rows: Row[]; total: string }>(['clearing'], '/costing/branch-transfers/clearing');
  const action = useAction();
  const [shown, setShown] = useState<Row | null>(null);
  const preview = useMutation({
    mutationFn: async () => (await api.post<Row>('/costing/delivery-cost-runs/preview', period)).data,
    onSuccess: setShown,
  });
  return (
    <>
      <Card>
        <CardHeader className="flex flex-row items-center justify-between gap-2">
          <CardTitle>{tx('Delivery cost')}</CardTitle>
          <div className="flex gap-2">
            <Button variant="outline" onClick={() => preview.mutate()}>{tx('Preview')}</Button>
            {canPost && <Button disabled={!shown} onClick={() => action.mutate({ url: '/costing/delivery-cost-runs', body: period })}>{tx('Post')}</Button>}
          </div>
        </CardHeader>
        <CardContent>
          {shown && (
            <Table>
              <TableHeader><TableRow><TableHead>{tx('Trip')}</TableHead><TableHead>{tx('Loaded km')}</TableHead><TableHead>{tx('Empty km')}</TableHead><TableHead>{tx('Tonnes')}</TableHead><TableHead>{tx('Amount')}</TableHead><TableHead>{tx('Cost per tonne-km')}</TableHead></TableRow></TableHeader>
              <TableBody>
                {((shown.lines as Row[] | undefined) ?? []).map((l) => (
                  <TableRow key={s(l.loadId)}><TableCell>{s(l.loadCode)}</TableCell><TableCell>{s(l.loadedKm)}</TableCell><TableCell>{s(l.emptyKm)}</TableCell><TableCell>{s(l.tonnes)}</TableCell><TableCell>{money(s(l.amount))}</TableCell><TableCell>{s(l.perTonneKm)}</TableCell></TableRow>
                ))}
              </TableBody>
            </Table>
          )}
          <Table>
            <TableHeader><TableRow><TableHead>{tx('Number')}</TableHead><TableHead>{tx('Period')}</TableHead><TableHead>{tx('Total')}</TableHead><TableHead>{tx('Status')}</TableHead><TableHead /></TableRow></TableHeader>
            <TableBody>
              {runs.map((r) => (
                <TableRow key={s(r.id)}>
                  <TableCell>{s(r.number)}</TableCell>
                  <TableCell>{day(r.periodFrom)} – {day(r.periodTo)}</TableCell>
                  <TableCell>{money(s(r.total))}</TableCell>
                  <TableCell><Badge variant="secondary">{s(r.status)}</Badge></TableCell>
                  <TableCell className="text-right">
                    {canPost && r.status === 'posted' && <Button size="sm" variant="outline" onClick={() => action.mutate({ url: `/costing/delivery-cost-runs/${s(r.id)}/reverse` })}>{tx('Reverse')}</Button>}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
      <Card>
        <CardHeader><CardTitle>{tx('Inter-branch clearing')} · {money(s(clearing?.total))}</CardTitle></CardHeader>
        <CardContent>
          <Table>
            <TableHeader><TableRow><TableHead>{tx('Branch')}</TableHead><TableHead className="text-right">{tx('Balance')}</TableHead></TableRow></TableHeader>
            <TableBody>
              {(clearing?.rows ?? []).map((r, i) => (
                <TableRow key={i}><TableCell>{s(r.branchCode) || '—'}</TableCell><TableCell className="text-right">{money(s(r.balance))}</TableCell></TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </>
  );
}

