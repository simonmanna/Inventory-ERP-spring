import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { AlertTriangle, CheckCircle2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { useAuthStore } from '@/stores/auth.store';
import { usePartners } from '@/features/partners/api';
import { useAccounts } from '@/features/accounting/api';
import {
  errMsg,
  useAccrualAction,
  useAccrualMonitor,
  useAccrualRules,
  useDeleteAccrualRule,
  useDuplicateOverrideAction,
  useDuplicateOverrides,
  useLandedCostReconciliation,
  useLandedCostTrace,
  useLineageExceptions,
  useResolveLineageException,
  useSaveAccrualRule,
  type AccrualRuleDraft,
} from '@/features/costing/api';
import { notify } from '@/lib/notify';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

/** CSV download of rows (LC-6 export convention: one header row, values as shown). */
export function downloadCsv(name: string, rows: Record<string, unknown>[]) {
  if (!rows.length) return;
  const keys = Object.keys(rows[0]);
  const esc = (v: unknown) => {
    const s = v == null ? '' : typeof v === 'object' ? JSON.stringify(v) : String(v);
    return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  const csv = [keys.join(','), ...rows.map((r) => keys.map((k) => esc(r[k])).join(','))].join('\n');
  const a = document.createElement('a');
  a.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }));
  a.download = `${name}.csv`;
  a.click();
  URL.revokeObjectURL(a.href);
}

/** Landed cost control (ADR-S053 LC-3 … LC-5): reconciliation, lineage exceptions, accruals, duplicate overrides, trace. */
export function LandedCostControlPage() {
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') ?? 'reconciliation';
  return (
    <div className="space-y-6 p-4 md:p-6">
      <div>
        <h1 className="text-2xl font-semibold">{tx('Landed cost control')}</h1>
        <p className="text-sm text-muted-foreground">{tx('Reconciliation to the general ledger, lineage exceptions, accruals and the trace from invoice to cost of sales.')}</p>
      </div>
      <Tabs value={tab} onValueChange={(t) => setParams({ tab: t })}>
        <TabsList className="flex h-auto w-full flex-wrap justify-start">
          <TabsTrigger value="reconciliation">{tx('Reconciliation')}</TabsTrigger>
          <TabsTrigger value="exceptions">{tx('Exceptions')}</TabsTrigger>
          <TabsTrigger value="accruals">{tx('Accruals')}</TabsTrigger>
          <TabsTrigger value="overrides">{tx('Duplicate invoices')}</TabsTrigger>
          <TabsTrigger value="trace">{tx('Trace')}</TabsTrigger>
        </TabsList>
        <TabsContent value="reconciliation"><ReconciliationTab /></TabsContent>
        <TabsContent value="exceptions"><ExceptionsTab /></TabsContent>
        <TabsContent value="accruals"><AccrualsTab /></TabsContent>
        <TabsContent value="overrides"><OverridesTab /></TabsContent>
        <TabsContent value="trace"><TraceTab initial={params.get('voucherId') ?? ''} /></TabsContent>
      </Tabs>
    </div>
  );
}

function ReconciliationTab() {
  const { data, isLoading, error } = useLandedCostReconciliation();
  if (isLoading) return <p className="p-4 text-sm text-muted-foreground">{tx('Loading…')}</p>;
  if (error) return <p className="p-4 text-sm text-destructive">{errMsg(error)}</p>;
  if (!data) return null;
  return (
    <div className="space-y-4 pt-4">
      <div className="flex items-center gap-2 text-sm">
        {data.clean
          ? <><CheckCircle2 className="h-4 w-4 text-green-600" /> {tx('Every balance ties to the general ledger; no mismatches or open exceptions.')}</>
          : <><AlertTriangle className="h-4 w-4 text-amber-600" /> {tx('Differences, mismatches or open exceptions need attention.')}</>}
        <Button variant="outline" size="sm" className="ml-auto" onClick={() => downloadCsv('landed-cost-reconciliation', data.rows.map((r: any) => ({ key: r.key, label: r.label, subledger: r.subledger, gl: r.gl, difference: r.difference })))}>{tx('Export CSV')}</Button>
      </div>
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
        {data.rows.map((r: any) => (
          <Card key={r.key}>
            <CardHeader className="pb-2"><CardTitle className="text-base">{r.key.toUpperCase()} · {r.label}</CardTitle></CardHeader>
            <CardContent className="space-y-1 text-sm">
              <div className="flex justify-between"><span>{tx('Sub-ledger')}</span><span>{money(Number(r.subledger))}</span></div>
              <div className="flex justify-between"><span>{tx('General ledger')}</span><span>{money(Number(r.gl))}</span></div>
              <div className="flex justify-between font-semibold">
                <span>{tx('Difference')}</span>
                <span className={r.ok ? 'text-green-700' : 'text-destructive'}>{money(Number(r.difference))}</span>
              </div>
              {Array.isArray(r.drillDown) && r.drillDown.length > 0 && (
                <details className="pt-1">
                  <summary className="cursor-pointer text-xs text-muted-foreground">{tx('{{n}} item(s)', { n: r.drillDown.length })}</summary>
                  <div className="max-h-48 overflow-auto text-xs">
                    {r.drillDown.slice(0, 50).map((d: any, i: number) => (
                      <div key={i} className="border-b py-1">
                        {d.code ? <Link className="text-primary hover:underline" to={`/procurement/landed-cost-vouchers/${d.voucherId}`}>{d.code}</Link> : null}
                        {' '}{d.number ?? d.order ?? d.sourceType ?? d.source ?? ''} {d.supplierInvoiceRef ? `#${d.supplierInvoiceRef}` : ''}
                        {d.parked ? ` · ${tx('parked')}` : ''} · {money(Number(d.amount ?? d.ledgerValue ?? 0))}
                      </div>
                    ))}
                  </div>
                </details>
              )}
            </CardContent>
          </Card>
        ))}
      </div>
      <MismatchTable title={tx('Classification mismatches')} rows={data.classificationMismatches} cols={['code', 'accountId', 'expected', 'posted']} />
      <MismatchTable title={tx('Location mismatches')} rows={data.locationMismatches} cols={['code', 'productId', 'locationId', 'expected', 'stockLedger']} />
    </div>
  );
}

function MismatchTable({ title, rows, cols }: { title: string; rows: any[]; cols: string[] }) {
  return (
    <Card>
      <CardHeader><CardTitle className="text-base">{title} {rows.length ? <Badge variant="destructive">{rows.length}</Badge> : <Badge variant="outline">0</Badge>}</CardTitle></CardHeader>
      {rows.length > 0 && (
        <CardContent className="overflow-x-auto">
          <Table>
            <TableHeader><TableRow>{cols.map((c) => <TableHead key={c}>{c}</TableHead>)}</TableRow></TableHeader>
            <TableBody>
              {rows.map((r, i) => (
                <TableRow key={i}>{cols.map((c) => <TableCell key={c} className="font-mono text-xs">{c === 'code' ? <Link className="text-primary hover:underline" to={`/procurement/landed-cost-vouchers/${r.voucherId}`}>{r.code}</Link> : String(r[c] ?? '')}</TableCell>)}</TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      )}
    </Card>
  );
}

function ExceptionsTab() {
  const has = useAuthStore((s) => s.hasPermission);
  const canResolve = has(PERMISSIONS.landedCost.resolveLineage);
  const [status, setStatus] = useState('open');
  const { data: rows = [] } = useLineageExceptions(status);
  const { data: accounts } = useAccounts();
  const resolve = useResolveLineageException();
  const [form, setForm] = useState<Record<string, { dest?: string; account?: string; reason?: string }>>({});
  const set = (id: string, p: { dest?: string; account?: string; reason?: string }) => setForm((f) => ({ ...f, [id]: { ...f[id], ...p } }));
  const act = async (id: string, action: 'assign' | 'write-off') => {
    const f = form[id] ?? {};
    try {
      await resolve.mutateAsync({ id, action, body: action === 'assign'
        ? { destinationLedgerId: f.dest ?? '', reason: f.reason ?? '' }
        : { accountId: f.account ?? '', reason: f.reason ?? '' } });
      notify.success(tx('Exception resolved'));
    } catch (e) {
      notify.error(tx('Could not resolve'), errMsg(e));
    }
  };
  return (
    <Card className="mt-4">
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle className="text-base">{tx('Lineage exceptions')}</CardTitle>
        <select className="rounded-md border p-2 text-sm" value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="open">{tx('Open')}</option>
          <option value="resolved">{tx('Resolved')}</option>
          <option value="written_off">{tx('Written off')}</option>
        </select>
      </CardHeader>
      <CardContent className="space-y-3">
        <p className="text-xs text-muted-foreground">{tx('A landed share that could not follow its goods (transfer not received yet or unpaired, a cycle, too many hops) is parked in 1396. Name the receiving transfer-in movement to release it to the goods, or write it off with a reason.')}</p>
        {rows.length === 0 && <p className="text-sm text-muted-foreground">{tx('Nothing here.')}</p>}
        {rows.map((r: any) => (
          <div key={r.id} className="space-y-2 rounded-md border p-3 text-sm">
            <div className="flex flex-wrap items-center gap-2">
              <Badge variant="outline">{r.kind}</Badge>
              <span>{r.productName ?? r.productId}</span>
              <span className="text-muted-foreground">{r.locationName ?? ''}</span>
              <span className="ml-auto font-semibold">{money(Number(r.parkedOpen ?? r.amount ?? 0))}</span>
            </div>
            <div className="text-xs text-muted-foreground">{r.sourceType} {r.sourceId} · {tx('movement')} <span className="font-mono">{r.ledgerId}</span> · {date(r.createdAt)}</div>
            {status === 'open' && canResolve && (
              <div className="grid gap-2 md:grid-cols-4">
                <Input placeholder={tx('Receiving transfer-in movement id')} value={form[r.id]?.dest ?? ''} onChange={(e) => set(r.id, { dest: e.target.value })} />
                <select className="rounded-md border p-2 text-sm" value={form[r.id]?.account ?? ''} onChange={(e) => set(r.id, { account: e.target.value })}>
                  <option value="">{tx('Write-off account…')}</option>
                  {(accounts?.data ?? []).map((a: any) => <option key={a.id} value={a.id}>{a.code} · {a.name}</option>)}
                </select>
                <Input placeholder={tx('Reason (required)')} value={form[r.id]?.reason ?? ''} onChange={(e) => set(r.id, { reason: e.target.value })} />
                <div className="flex gap-2">
                  <Button size="sm" variant="outline" disabled={!form[r.id]?.dest || !form[r.id]?.reason} onClick={() => act(r.id, 'assign')}>{tx('Assign')}</Button>
                  <Button size="sm" variant="outline" disabled={!form[r.id]?.account || !form[r.id]?.reason} onClick={() => act(r.id, 'write-off')}>{tx('Write off')}</Button>
                </div>
              </div>
            )}
          </div>
        ))}
      </CardContent>
    </Card>
  );
}

const BLANK_RULE: AccrualRuleDraft = { scopeType: 'default', rateType: 'percent', weightBasis: 'net', expectedSettlementDays: 30 };

function AccrualsTab() {
  const has = useAuthStore((s) => s.hasPermission);
  const { data: monitor } = useAccrualMonitor();
  const { data: rules = [] } = useAccrualRules();
  const saveRule = useSaveAccrualRule();
  const deleteRule = useDeleteAccrualRule();
  const action = useAccrualAction();
  const partners = usePartners({ page: 1, pageSize: 500 });
  const suppliers = (partners.data?.data ?? []).filter((p: any) => p.isSupplier !== false);
  const [rule, setRule] = useState<AccrualRuleDraft>(BLANK_RULE);
  const submitRule = async () => {
    try {
      await saveRule.mutateAsync(rule);
      notify.success(tx('Rule saved'));
      setRule(BLANK_RULE);
    } catch (e) {
      notify.error(tx('Could not save rule'), errMsg(e));
    }
  };
  const run = async (id: string, a: 'close' | 'reverse') => {
    try {
      await action.mutateAsync({ id, action: a });
      notify.success(a === 'close' ? tx('Accrual closed') : tx('Accrual reversed'));
    } catch (e) {
      notify.error(tx('Action failed'), errMsg(e));
    }
  };
  return (
    <div className="space-y-4 pt-4">
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
        {Object.entries(monitor?.buckets ?? {}).map(([k, v]) => (
          <Card key={k}><CardContent className="pt-6"><p className="text-xs text-muted-foreground">{tx('Open {{b}} days', { b: k })}</p><p className="text-xl font-semibold">{money(Number(v))}</p></CardContent></Card>
        ))}
        <Card><CardContent className="pt-6"><p className="text-xs text-muted-foreground">{tx('Overdue')}</p><p className="text-xl font-semibold">{monitor?.overdue ?? 0}</p></CardContent></Card>
      </div>
      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle className="text-base">{tx('Open accruals')}</CardTitle>
          <Button variant="outline" size="sm" onClick={() => downloadCsv('landed-cost-accruals', monitor?.accruals ?? [])}>{tx('Export CSV')}</Button>
        </CardHeader>
        <CardContent className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Accrual')}</TableHead><TableHead>{tx('Receipt')}</TableHead><TableHead>{tx('Status')}</TableHead>
                <TableHead className="text-right">{tx('Estimate')}</TableHead><TableHead className="text-right">{tx('Cleared')}</TableHead>
                <TableHead className="text-right">{tx('Remaining')}</TableHead><TableHead className="text-right">{tx('Actual')}</TableHead>
                <TableHead className="text-right">{tx('Estimate variance')}</TableHead><TableHead>{tx('Age')}</TableHead><TableHead />
              </TableRow>
            </TableHeader>
            <TableBody>
              {(monitor?.accruals ?? []).map((a: any) => (
                <TableRow key={a.id}>
                  <TableCell className="font-mono text-xs">{a.number}</TableCell>
                  <TableCell className="font-mono text-xs">{a.receiptNumber}</TableCell>
                  <TableCell><Badge variant="outline">{a.status}</Badge></TableCell>
                  <TableCell className="text-right">{money(Number(a.estimate))}</TableCell>
                  <TableCell className="text-right">{money(Number(a.cleared))}</TableCell>
                  <TableCell className="text-right">{money(Number(a.remaining))}</TableCell>
                  <TableCell className="text-right">{money(Number(a.actual))}</TableCell>
                  <TableCell className="text-right">{money(Number(a.variance))}</TableCell>
                  <TableCell>{a.bucket} {a.overdue ? <Badge variant="destructive">{tx('overdue')}</Badge> : null}</TableCell>
                  <TableCell className="space-x-1 text-right">
                    {has(PERMISSIONS.landedCost.post) && <Button size="sm" variant="outline" onClick={() => run(a.id, 'close')}>{tx('Close')}</Button>}
                    {a.status === 'posted' && has(PERMISSIONS.landedCost.reverse) && <Button size="sm" variant="ghost" onClick={() => run(a.id, 'reverse')}>{tx('Reverse')}</Button>}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <p className="pt-2 text-xs text-muted-foreground">{tx('The estimate variance is the actual minus the accrual it cleared, in base currency at recognition. Exchange differences on paying the supplier are realised FX on the payment and never reach inventory.')}</p>
        </CardContent>
      </Card>
      <Card>
        <CardHeader><CardTitle className="text-base">{tx('Accrual rules')}</CardTitle></CardHeader>
        <CardContent className="space-y-4">
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('Name')}</TableHead><TableHead>{tx('Scope')}</TableHead><TableHead>{tx('Rate')}</TableHead>
                  <TableHead>{tx('Effective')}</TableHead><TableHead>{tx('Settles in')}</TableHead><TableHead />
                </TableRow>
              </TableHeader>
              <TableBody>
                {rules.map((r: any) => (
                  <TableRow key={r.id}>
                    <TableCell>{r.name}</TableCell>
                    <TableCell>{r.scopeType} {r.scopeId ? <span className="font-mono text-xs">{r.scopeId.slice(0, 8)}</span> : null}</TableCell>
                    <TableCell>{r.rateType === 'percent' ? `${Number(r.ratePct)} %` : `${Number(r.rate)} ${r.currencyCode ?? ''} / ${r.rateType === 'per_kg' ? `kg (${r.weightBasis})` : tx('unit')}`}</TableCell>
                    <TableCell className="text-xs">{r.effectiveFrom ? date(r.effectiveFrom) : '…'} – {r.effectiveTo ? date(r.effectiveTo) : '…'}</TableCell>
                    <TableCell>{r.expectedSettlementDays} {tx('days')}</TableCell>
                    <TableCell className="text-right">{has(PERMISSIONS.landedCost.configure) && <Button size="sm" variant="ghost" onClick={() => deleteRule.mutate(r.id)}>{tx('Remove')}</Button>}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          {has(PERMISSIONS.landedCost.configure) && (
            <div className="grid gap-2 md:grid-cols-4">
              <div className="space-y-1"><Label>{tx('Name')}</Label><Input value={rule.name ?? ''} onChange={(e) => setRule({ ...rule, name: e.target.value })} /></div>
              <div className="space-y-1">
                <Label>{tx('Scope')}</Label>
                <select className="w-full rounded-md border p-2 text-sm" value={rule.scopeType} onChange={(e) => setRule({ ...rule, scopeType: e.target.value as AccrualRuleDraft['scopeType'], scopeId: undefined })}>
                  <option value="default">{tx('Default')}</option><option value="supplier">{tx('Supplier')}</option>
                  <option value="category">{tx('Product category')}</option><option value="incoterm">{tx('Incoterm')}</option>
                </select>
              </div>
              <div className="space-y-1">
                <Label>{tx('Applies to')}</Label>
                {rule.scopeType === 'supplier' ? (
                  <select className="w-full rounded-md border p-2 text-sm" value={rule.scopeId ?? ''} onChange={(e) => setRule({ ...rule, scopeId: e.target.value || undefined })}>
                    <option value="">{tx('Supplier…')}</option>
                    {suppliers.map((p: any) => <option key={p.id} value={p.id}>{p.name}</option>)}
                  </select>
                ) : (
                  <Input disabled={rule.scopeType === 'default'} placeholder={rule.scopeType === 'incoterm' ? 'CIF, FOB…' : tx('Category id')} value={rule.scopeId ?? ''} onChange={(e) => setRule({ ...rule, scopeId: e.target.value })} />
                )}
              </div>
              <div className="space-y-1">
                <Label>{tx('Rate type')}</Label>
                <select className="w-full rounded-md border p-2 text-sm" value={rule.rateType} onChange={(e) => setRule({ ...rule, rateType: e.target.value as AccrualRuleDraft['rateType'] })}>
                  <option value="percent">{tx('% of goods value')}</option><option value="per_unit">{tx('Amount per unit')}</option><option value="per_kg">{tx('Amount per kg')}</option>
                </select>
              </div>
              {rule.rateType === 'percent'
                ? <div className="space-y-1"><Label>{tx('Rate %')}</Label><Input type="number" min="0" value={rule.ratePct ?? ''} onChange={(e) => setRule({ ...rule, ratePct: Number(e.target.value) || undefined })} /></div>
                : (
                  <>
                    <div className="space-y-1"><Label>{tx('Rate')}</Label><Input type="number" min="0" value={rule.rate ?? ''} onChange={(e) => setRule({ ...rule, rate: Number(e.target.value) || undefined })} /></div>
                    <div className="space-y-1"><Label>{tx('Currency')}</Label><Input value={rule.currencyCode ?? ''} onChange={(e) => setRule({ ...rule, currencyCode: e.target.value || undefined })} /></div>
                    {rule.rateType === 'per_kg' && (
                      <div className="space-y-1">
                        <Label>{tx('Weight basis')}</Label>
                        <select className="w-full rounded-md border p-2 text-sm" value={rule.weightBasis} onChange={(e) => setRule({ ...rule, weightBasis: e.target.value as 'net' | 'gross' })}>
                          <option value="net">{tx('Net')}</option><option value="gross">{tx('Gross')}</option>
                        </select>
                      </div>
                    )}
                  </>
                )}
              <div className="space-y-1"><Label>{tx('Effective from')}</Label><Input type="date" value={rule.effectiveFrom ?? ''} onChange={(e) => setRule({ ...rule, effectiveFrom: e.target.value || undefined })} /></div>
              <div className="space-y-1"><Label>{tx('Effective to')}</Label><Input type="date" value={rule.effectiveTo ?? ''} onChange={(e) => setRule({ ...rule, effectiveTo: e.target.value || undefined })} /></div>
              <div className="space-y-1"><Label>{tx('Expected settlement (days)')}</Label><Input type="number" min="0" value={rule.expectedSettlementDays ?? 30} onChange={(e) => setRule({ ...rule, expectedSettlementDays: Number(e.target.value) })} /></div>
              <div className="flex items-end"><Button onClick={submitRule} disabled={saveRule.isPending}>{tx('Add rule')}</Button></div>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function OverridesTab() {
  const has = useAuthStore((s) => s.hasPermission);
  const { data: rows = [] } = useDuplicateOverrides();
  const act = useDuplicateOverrideAction();
  const partners = usePartners({ page: 1, pageSize: 500 });
  const suppliers = (partners.data?.data ?? []).filter((p: any) => p.isSupplier !== false);
  const [f, setF] = useState({ supplierId: '', supplierInvoiceRef: '', reason: '' });
  const create = async () => {
    try {
      await act.mutateAsync({ create: f });
      notify.success(tx('Override approved'));
      setF({ supplierId: '', supplierInvoiceRef: '', reason: '' });
    } catch (e) {
      notify.error(tx('Could not approve'), errMsg(e));
    }
  };
  return (
    <Card className="mt-4">
      <CardHeader><CardTitle className="text-base">{tx('Duplicate supplier invoice overrides')}</CardTitle></CardHeader>
      <CardContent className="space-y-4">
        <p className="text-xs text-muted-foreground">{tx('A supplier invoice number can be landed once. When the same number is genuinely used twice, approve one override with a reason; the charge that names it uses it once.')}</p>
        {has(PERMISSIONS.landedCost.overrideDuplicate) && (
          <div className="grid gap-2 md:grid-cols-4">
            <select className="rounded-md border p-2 text-sm" value={f.supplierId} onChange={(e) => setF({ ...f, supplierId: e.target.value })}>
              <option value="">{tx('Supplier…')}</option>
              {suppliers.map((p: any) => <option key={p.id} value={p.id}>{p.name}</option>)}
            </select>
            <Input placeholder={tx('Supplier invoice no.')} value={f.supplierInvoiceRef} onChange={(e) => setF({ ...f, supplierInvoiceRef: e.target.value })} />
            <Input placeholder={tx('Reason (required)')} value={f.reason} onChange={(e) => setF({ ...f, reason: e.target.value })} />
            <Button disabled={!f.supplierId || !f.supplierInvoiceRef || !f.reason} onClick={create}>{tx('Approve override')}</Button>
          </div>
        )}
        <div className="overflow-x-auto">
          <Table>
            <TableHeader><TableRow><TableHead>{tx('Supplier')}</TableHead><TableHead>{tx('Invoice')}</TableHead><TableHead>{tx('Reason')}</TableHead><TableHead>{tx('Status')}</TableHead><TableHead>{tx('Used by')}</TableHead><TableHead /></TableRow></TableHeader>
            <TableBody>
              {rows.map((o: any) => (
                <TableRow key={o.id}>
                  <TableCell>{o.supplierName}</TableCell>
                  <TableCell className="font-mono text-xs">{o.supplierInvoiceRef}</TableCell>
                  <TableCell className="text-sm">{o.reason}</TableCell>
                  <TableCell><Badge variant="outline">{o.status}</Badge></TableCell>
                  <TableCell className="font-mono text-xs">{o.usedByVoucherCode ?? '—'}</TableCell>
                  <TableCell className="text-right">
                    {o.status === 'approved' && !o.usedByChargeId && has(PERMISSIONS.landedCost.overrideDuplicate) && (
                      <Button size="sm" variant="ghost" onClick={() => act.mutate({ revoke: o.id })}>{tx('Revoke')}</Button>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </CardContent>
    </Card>
  );
}

/** The trace chain as a tree: voucher → charges → allocations → adjustments per pool (transfer hops) → journal lines. */
export function TraceTree({ voucherId }: { voucherId: string }) {
  const { data, isLoading, error } = useLandedCostTrace({ voucherId });
  if (isLoading) return <p className="text-sm text-muted-foreground">{tx('Loading…')}</p>;
  if (error) return <p className="text-sm text-destructive">{errMsg(error)}</p>;
  if (!data) return null;
  const adjustments: any[] = data.adjustments ?? [];
  const children = (parent: string | null) => adjustments.filter((a) => (a.parentAdjustmentId ?? null) === parent);
  const node = (a: any) => (
    <li key={a.id} className="space-y-1">
      <div className="text-sm">
        <Badge variant="outline">{a.layerType ?? 'layer'}</Badge>{' '}
        {a.adjustmentType} · {money(Number(a.amount))} · {tx('on hand')} {money(Number(a.onHandAmount))}
        {Number(a.openAmount) ? ` · ${tx('open adjunct')} ${money(Number(a.openAmount))}` : ''}
        {a.reversedAt ? <Badge variant="destructive" className="ml-1">{tx('reversed')}</Badge> : null}
      </div>
      <ul className="ml-4 border-l pl-3 text-xs text-muted-foreground">
        {(a.lines ?? []).filter((l: any) => Number(l.amount) !== 0).map((l: any, i: number) => (
          <li key={i}>{l.target} · {money(Number(l.amount))} {l.moveType ? `· ${l.moveType}` : ''} {l.referenceType ? `${l.referenceType} ${l.referenceId ?? ''}` : ''} {l.targetRef && l.target === 'wip' ? `· ${l.targetRef}` : ''}</li>
        ))}
      </ul>
      {children(a.id).length > 0 && <ul className="ml-4 border-l pl-3">{children(a.id).map(node)}</ul>}
    </li>
  );
  return (
    <div className="space-y-4 text-sm">
      <div><b>{data.voucher.code}</b> · {data.voucher.voucherType} · {data.voucher.status}</div>
      <ul className="space-y-1">
        {(data.charges ?? []).map((c: any) => (
          <li key={c.id}>
            {c.lineNumber}. {c.kind} {c.description ?? ''} {c.supplierInvoiceRef ? `#${c.supplierInvoiceRef}` : ''} · {money(Number(c.baseAmount ?? c.amount))}
            <ul className="ml-4 border-l pl-3 text-xs text-muted-foreground">
              {(c.allocations ?? []).map((a: any) => <li key={a.id}>{a.receiptNumber} · {a.productName} · {money(Number(a.allocatedAmount))}</li>)}
            </ul>
          </li>
        ))}
      </ul>
      <div>
        <p className="font-medium">{tx('Lineage')}</p>
        <ul className="space-y-2">{children(null).map(node)}</ul>
      </div>
      <div className="overflow-x-auto">
        <p className="font-medium">{tx('Journal')}</p>
        <Table>
          <TableHeader><TableRow><TableHead>{tx('Account')}</TableHead><TableHead className="text-right">{tx('Debit')}</TableHead><TableHead className="text-right">{tx('Credit')}</TableHead><TableHead>{tx('Description')}</TableHead></TableRow></TableHeader>
          <TableBody>
            {(data.journal ?? []).map((j: any) => (
              <TableRow key={j.id}>
                <TableCell>{j.accountCode} · {j.accountName}</TableCell>
                <TableCell className="text-right">{Number(j.debit) ? money(Number(j.debit)) : ''}</TableCell>
                <TableCell className="text-right">{Number(j.credit) ? money(Number(j.credit)) : ''}</TableCell>
                <TableCell className="text-xs">{j.description}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}

function TraceTab({ initial }: { initial: string }) {
  const [id, setId] = useState(initial);
  const [shown, setShown] = useState(initial);
  return (
    <Card className="mt-4">
      <CardHeader><CardTitle className="text-base">{tx('Trace a voucher')}</CardTitle></CardHeader>
      <CardContent className="space-y-4">
        <div className="flex flex-wrap gap-2">
          <Input className="max-w-md" placeholder={tx('Voucher id')} value={id} onChange={(e) => setId(e.target.value)} />
          <Button onClick={() => setShown(id.trim())}>{tx('Trace')}</Button>
        </div>
        {shown && <TraceTree voucherId={shown} />}
      </CardContent>
    </Card>
  );
}

export default LandedCostControlPage;
