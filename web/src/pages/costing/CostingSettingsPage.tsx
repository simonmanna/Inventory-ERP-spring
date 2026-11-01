import { useState } from 'react';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { useAccounts } from '@/features/accounting/api';
import { useLocationOptions, useProductOptions } from '@/features/manufacturing/options';
import {
  useCostingSettings,
  useCreateRevaluation,
  useEnsureCostingAccounts,
  useOpenAdjuncts,
  usePolicyChanges,
  usePolicyDecision,
  useProposePolicy,
  useReleaseAdjuncts,
  useRevaluationAction,
  useRevaluations,
  useSaveCostingSettings,
} from '@/features/costing/cost-management-api';
import { money } from '@/lib/format';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';

const SELECT = 'h-9 rounded-md border border-input bg-background px-3 text-sm';
const today = new Date().toISOString().slice(0, 10);

/** Costing & Valuation settings, accounting policy elections and inventory revaluation / NRV (ADR-S031, ADR-S032). */
export function CostingSettingsPage() {
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">{tx('Costing & valuation')}</h1>
        <p className="text-sm text-muted-foreground">
          {tx('One place for valuation, landed costs, costing accounts and governed accounting policies.')}
        </p>
      </div>
      <Tabs defaultValue="settings">
        <TabsList>
          <TabsTrigger value="settings">{tx('Settings')}</TabsTrigger>
          <TabsTrigger value="policies">{tx('Accounting policies')}</TabsTrigger>
          <TabsTrigger value="revaluation">{tx('Revaluation & NRV')}</TabsTrigger>
        </TabsList>
        <TabsContent value="settings"><SettingsTab /></TabsContent>
        <TabsContent value="policies"><PoliciesTab /></TabsContent>
        <TabsContent value="revaluation"><RevaluationTab /></TabsContent>
      </Tabs>
    </div>
  );
}

function SettingsTab() {
  const has = useAuthStore((s) => s.hasPermission);
  const canManage = has(PERMISSIONS.costingSettings.manage);
  const { data } = useCostingSettings();
  const { data: accountPage } = useAccounts();
  const save = useSaveCostingSettings();
  const ensure = useEnsureCostingAccounts();
  if (!data) return <p className="text-sm text-muted-foreground">{tx('Loading…')}</p>;
  const accounts = accountPage?.data ?? [];
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <Card>
        <CardHeader><CardTitle>{tx('Valuation')}</CardTitle></CardHeader>
        <CardContent className="space-y-3 text-sm">
          <div className="flex items-center justify-between gap-2">
            <Label>{tx('Default costing method (new products)')}</Label>
            <select className={SELECT} disabled={!canManage} value={data.valuation.defaultCostingMethod}
              onChange={(e) => save.mutate({ defaultCostingMethod: e.target.value })}>
              {['AVCO', 'FIFO', 'STANDARD', 'SPECIFIC'].map((m) => <option key={m} value={m}>{m}</option>)}
            </select>
          </div>
          <label className="flex items-center gap-2">
            <input type="checkbox" disabled={!canManage} checked={data.valuation.allowNegativeStock}
              onChange={(e) => save.mutate({ allowNegativeStock: e.target.checked })} />
            {tx('Allow negative stock')}
          </label>
          <p className="text-muted-foreground">{data.valuation.note}</p>
          <p>{tx('Costing policies in force')}: {data.valuation.costPolicies.length === 0 ? '—' : data.valuation.costPolicies.map((p) => `${p.code} ${p.method}`).join(', ')}</p>
        </CardContent>
      </Card>
      <Card>
        <CardHeader><CardTitle>{tx('Landed costs')}</CardTitle></CardHeader>
        <CardContent className="space-y-3 text-sm">
          <label className="flex items-center gap-2">
            <input type="checkbox" disabled={!canManage} checked={data.landedCosts.enabled}
              onChange={(e) => save.mutate({ landedCostEnabled: e.target.checked })} />
            {tx('Add additional costs (transport, customs, …) to the value of the product')}
          </label>
          <div className="flex items-center justify-between gap-2">
            <Label>{tx('Default allocation')}</Label>
            <select className={SELECT} disabled={!canManage} value={data.landedCosts.defaultAllocationMethod}
              onChange={(e) => save.mutate({ defaultAllocationMethod: e.target.value })}>
              {['value', 'quantity', 'equal', 'weight', 'volume', 'manual'].map((m) => <option key={m} value={m}>{m}</option>)}
            </select>
          </div>
          <Table>
            <TableHeader><TableRow><TableHead>{tx('Component')}</TableHead><TableHead>{tx('Treatment')}</TableHead><TableHead>{tx('Eligibility')}</TableHead></TableRow></TableHeader>
            <TableBody>
              {data.landedCosts.components.map((c) => (
                <TableRow key={c.id}>
                  <TableCell>{c.code} · {c.name}</TableCell>
                  <TableCell>{c.treatment}</TableCell>
                  <TableCell>{c.eligibility ? <Badge variant="secondary">{c.eligibility}</Badge> : '—'}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
      <Card className="lg:col-span-2">
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle>{tx('Costing accounts')}</CardTitle>
          {canManage && <Button size="sm" variant="outline" onClick={() => ensure.mutate(undefined)}>{tx('Create missing accounts')}</Button>}
        </CardHeader>
        <CardContent>
          <Table>
            <TableBody>
              {data.accounts.map((a) => (
                <TableRow key={a.key}>
                  <TableCell>{a.label}</TableCell>
                  <TableCell>
                    <select className={SELECT} disabled={!canManage} value={a.accountId ?? ''}
                      onChange={(e) => e.target.value && save.mutate({ accounts: { [a.key]: e.target.value } })}>
                      <option value="">—</option>
                      {accounts.map((acc) => <option key={acc.id} value={acc.id}>{acc.code} · {acc.name}</option>)}
                    </select>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
      <Card className="lg:col-span-2">
        <CardHeader><CardTitle>{tx('Branch dimension on costing postings')}</CardTitle></CardHeader>
        <CardContent className="flex items-center gap-3 text-sm">
          <select className={SELECT} disabled={!canManage} value={data.dimensions.enforcement}
            onChange={(e) => save.mutate({ dimensionEnforcement: e.target.value })}>
            <option value="off">{tx('Off')}</option>
            <option value="warn">{tx('Warn (compliance report)')}</option>
            <option value="enforce">{tx('Enforce')}</option>
          </select>
          <span className="text-muted-foreground">{tx('Unclassified expense accounts are never capitalised into inventory.')} ({data.eligibility.unclassifiedAccounts.length})</span>
        </CardContent>
      </Card>
    </div>
  );
}

function PoliciesTab() {
  const has = useAuthStore((s) => s.hasPermission);
  const canPropose = has(PERMISSIONS.accountingPolicy.manage);
  const canApprove = has(PERMISSIONS.accountingPolicy.approve);
  const { data: settings } = useCostingSettings();
  const { data: changes = [] } = usePolicyChanges();
  const propose = useProposePolicy();
  const decide = usePolicyDecision();
  const [form, setForm] = useState({ election: 'variance_posting', value: '', effectiveFrom: today, justification: '' });
  const elections = (settings?.policies ?? []).filter((e) => e.key !== 'cost_eligibility');
  const selected = elections.find((e) => e.key === form.election);
  return (
    <div className="space-y-4">
      <Card>
        <CardHeader><CardTitle>{tx('Elections in force')}</CardTitle></CardHeader>
        <CardContent>
          <Table>
            <TableHeader><TableRow><TableHead>{tx('Election')}</TableHead><TableHead>{tx('Standard')}</TableHead><TableHead>{tx('In force')}</TableHead></TableRow></TableHeader>
            <TableBody>
              {elections.map((e) => (
                <TableRow key={e.key}>
                  <TableCell><div>{e.label}</div><div className="text-xs text-muted-foreground">{e.note}</div></TableCell>
                  <TableCell>{e.standard}</TableCell>
                  <TableCell><Badge variant="secondary">{e.current}</Badge></TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
      {canPropose && (
        <Card>
          <CardHeader><CardTitle>{tx('Propose a change')}</CardTitle></CardHeader>
          <CardContent className="grid gap-3 md:grid-cols-4">
            <select className={SELECT} value={form.election} onChange={(e) => setForm({ ...form, election: e.target.value, value: '' })}>
              {elections.map((e) => <option key={e.key} value={e.key}>{e.label}</option>)}
            </select>
            {selected?.values ? (
              <select className={SELECT} value={form.value} onChange={(e) => setForm({ ...form, value: e.target.value })}>
                <option value="">—</option>
                {selected.values.map((v) => <option key={v} value={v}>{v}</option>)}
              </select>
            ) : (
              <Input type="number" value={form.value} onChange={(e) => setForm({ ...form, value: e.target.value })} />
            )}
            <Input type="date" value={form.effectiveFrom} onChange={(e) => setForm({ ...form, effectiveFrom: e.target.value })} />
            <Button disabled={!form.value || form.justification.length < 10} onClick={() => propose.mutate(form)}>{tx('Propose')}</Button>
            <Input className="md:col-span-4" placeholder={tx('Justification (IAS 8: why the change gives more relevant information)')}
              value={form.justification} onChange={(e) => setForm({ ...form, justification: e.target.value })} />
          </CardContent>
        </Card>
      )}
      <Card>
        <CardHeader><CardTitle>{tx('Proposals and history')}</CardTitle></CardHeader>
        <CardContent>
          <Table>
            <TableHeader><TableRow><TableHead>{tx('Election')}</TableHead><TableHead>{tx('Value')}</TableHead><TableHead>{tx('Effective')}</TableHead><TableHead>{tx('Status')}</TableHead><TableHead /></TableRow></TableHeader>
            <TableBody>
              {changes.map((c) => (
                <TableRow key={c.id}>
                  <TableCell>{c.election}{c.scopeId ? ` · ${c.scopeType}` : ''}</TableCell>
                  <TableCell>{c.value}</TableCell>
                  <TableCell>{String(c.effectiveFrom).slice(0, 10)}</TableCell>
                  <TableCell><Badge variant={c.inForce ? 'default' : 'secondary'}>{c.inForce ? tx('In force') : c.status}</Badge></TableCell>
                  <TableCell className="space-x-2 text-right">
                    {canApprove && c.status === 'proposed' && (
                      <>
                        <Button size="sm" onClick={() => decide.mutate({ id: c.id, action: 'approve' })}>{tx('Approve')}</Button>
                        <Button size="sm" variant="outline" onClick={() => decide.mutate({ id: c.id, action: 'reject' })}>{tx('Reject')}</Button>
                      </>
                    )}
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

function RevaluationTab() {
  const has = useAuthStore((s) => s.hasPermission);
  const canManage = has(PERMISSIONS.inventoryValuation.manage);
  const canPost = has(PERMISSIONS.inventoryValuation.post);
  const { data: docs = [] } = useRevaluations();
  const { data: adjuncts = [] } = useOpenAdjuncts();
  const { data: productList = [] } = useProductOptions();
  const { data: locations = [] } = useLocationOptions();
  const create = useCreateRevaluation();
  const act = useRevaluationAction();
  const release = useReleaseAdjuncts();
  const [form, setForm] = useState({ kind: 'nrv', productId: '', locationId: '', value: '', reason: '' });
  return (
    <div className="space-y-4">
      {canManage && (
        <Card>
          <CardHeader><CardTitle>{tx('New revaluation or NRV review')}</CardTitle></CardHeader>
          <CardContent className="grid gap-3 md:grid-cols-5">
            <select className={SELECT} value={form.kind} onChange={(e) => setForm({ ...form, kind: e.target.value })}>
              <option value="nrv">{tx('NRV review (IAS 2)')}</option>
              <option value="revaluation">{tx('Revaluation')}</option>
            </select>
            <select className={SELECT} value={form.productId} onChange={(e) => setForm({ ...form, productId: e.target.value })}>
              <option value="">{tx('Product')}</option>
              {productList.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            </select>
            <select className={SELECT} value={form.locationId} onChange={(e) => setForm({ ...form, locationId: e.target.value })}>
              <option value="">{tx('Location')}</option>
              {locations.map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}
            </select>
            <Input type="number" placeholder={form.kind === 'nrv' ? tx('Net realisable value per unit') : tx('New unit cost')}
              value={form.value} onChange={(e) => setForm({ ...form, value: e.target.value })} />
            <Button disabled={!form.productId || !form.locationId || form.value === ''}
              onClick={() => create.mutate({ kind: form.kind, date: today, reason: form.reason || undefined, lines: [{
                productId: form.productId, locationId: form.locationId,
                ...(form.kind === 'nrv' ? { netRealisableValue: Number(form.value) } : { unitCostAfter: Number(form.value) }),
              }] })}>{tx('Create draft')}</Button>
          </CardContent>
        </Card>
      )}
      <Card>
        <CardHeader><CardTitle>{tx('Documents')}</CardTitle></CardHeader>
        <CardContent>
          <Table>
            <TableHeader><TableRow><TableHead>{tx('Number')}</TableHead><TableHead>{tx('Kind')}</TableHead><TableHead>{tx('Date')}</TableHead><TableHead>{tx('Amount')}</TableHead><TableHead>{tx('Status')}</TableHead><TableHead /></TableRow></TableHeader>
            <TableBody>
              {docs.map((d) => (
                <TableRow key={d.id}>
                  <TableCell>{d.number}</TableCell>
                  <TableCell>{d.kind}</TableCell>
                  <TableCell>{String(d.revaluationDate).slice(0, 10)}</TableCell>
                  <TableCell>{money(d.totalAmount)}</TableCell>
                  <TableCell><Badge variant="secondary">{d.status}</Badge></TableCell>
                  <TableCell className="space-x-2 text-right">
                    {canPost && d.status === 'draft' && <Button size="sm" onClick={() => act.mutate({ id: d.id, action: 'post' })}>{tx('Post')}</Button>}
                    {canManage && d.status === 'draft' && <Button size="sm" variant="outline" onClick={() => act.mutate({ id: d.id, action: 'delete' })}>{tx('Delete')}</Button>}
                    {canPost && d.status === 'posted' && <Button size="sm" variant="outline" onClick={() => act.mutate({ id: d.id, action: 'reverse' })}>{tx('Reverse')}</Button>}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle>{tx('Open price differences and NRV allowances')}</CardTitle>
          {canPost && <Button size="sm" variant="outline" onClick={() => release.mutate(undefined)}>{tx('Release consumed share')}</Button>}
        </CardHeader>
        <CardContent>
          <Table>
            <TableBody>
              {adjuncts.map((a) => (
                <TableRow key={`${a.productId}-${a.locationId}-${a.valuationComponent}`}>
                  <TableCell>{a.productName}</TableCell>
                  <TableCell>{a.valuationComponent}</TableCell>
                  <TableCell className="text-right">{money(a.openAmount)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  );
}
