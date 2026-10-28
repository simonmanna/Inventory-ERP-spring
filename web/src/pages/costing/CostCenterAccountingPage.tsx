import { useMemo, useState } from 'react';
import { Play, Plus, Trash2, Undo2 } from 'lucide-react';
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
import { useAccounts } from '@/features/accounting/api';
import {
  errMsg,
  flattenTree,
  useAccountDimensionRules,
  useAllocationPreview,
  useAllocationRules,
  useAllocationRuns,
  useCostCenterDefaults,
  useCostCenterMatrix,
  useCostCenterPnl,
  useCostCenterTree,
  useDeleteAllocationRule,
  useDeleteCostCenterDefault,
  useReverseAllocationRun,
  useRunAllocation,
  useSaveAllocationRule,
  useSaveCostCenterDefault,
  useSetAccountDimensionRule,
  type AllocationRule,
  type CostCenterNode,
} from '@/features/costing/api';
import { notify } from '@/lib/notify';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

const firstOfMonth = () => new Date(new Date().getFullYear(), new Date().getMonth(), 1).toISOString().slice(0, 10);
const today = () => new Date().toISOString().slice(0, 10);

function CenterSelect({ value, onChange, centers, allowEmpty }: { value: string; onChange: (v: string) => void; centers: Array<CostCenterNode & { depth: number }>; allowEmpty?: string }) {
  return (
    <select className="w-full rounded-md border p-2 text-sm" value={value} onChange={(e) => onChange(e.target.value)}>
      <option value="">{allowEmpty ?? tx('Select…')}</option>
      {centers.map((c) => <option key={c.id} value={c.id}>{'  '.repeat(c.depth)}{c.code} · {c.name}</option>)}
    </select>
  );
}

/**
 * Cost-centre accounting (ADR-S007): hierarchy, split and periodic allocation rules, allocation runs, default
 * centres, per-account rules, and the cost-centre P&L / matrix. A cost centre is an attribute of a GL line, never a
 * second expense.
 */
export function CostCenterAccountingPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const canAllocate = has(PERMISSIONS.costCenter.allocate);
  const canUpdate = has(PERMISSIONS.costCenter.update);
  const { data: tree = [] } = useCostCenterTree();
  const centers = useMemo(() => flattenTree(tree), [tree]);

  return (
    <div className="space-y-6 p-6">
      <div>
        <h1 className="text-2xl font-semibold">{tx('Cost centre accounting')}</h1>
        <p className="text-sm text-muted-foreground">
          {tx('Track where costs are incurred — by branch, department, project or warehouse — with allocation rules, defaults and management reports.')}
        </p>
      </div>
      <Tabs defaultValue="pnl">
        <TabsList>
          <TabsTrigger value="pnl">{tx('Profit & loss')}</TabsTrigger>
          <TabsTrigger value="matrix">{tx('Centre × account')}</TabsTrigger>
          <TabsTrigger value="hierarchy">{tx('Hierarchy')}</TabsTrigger>
          <TabsTrigger value="rules">{tx('Allocation rules')}</TabsTrigger>
          <TabsTrigger value="runs">{tx('Allocation runs')}</TabsTrigger>
          <TabsTrigger value="defaults">{tx('Defaults & account rules')}</TabsTrigger>
        </TabsList>
        <TabsContent value="pnl"><PnlTab centers={centers} /></TabsContent>
        <TabsContent value="matrix"><MatrixTab /></TabsContent>
        <TabsContent value="hierarchy"><HierarchyTab tree={tree} /></TabsContent>
        <TabsContent value="rules"><RulesTab centers={centers} canAllocate={canAllocate} /></TabsContent>
        <TabsContent value="runs"><RunsTab canAllocate={canAllocate} /></TabsContent>
        <TabsContent value="defaults"><DefaultsTab centers={centers} canUpdate={canUpdate} /></TabsContent>
      </Tabs>
    </div>
  );
}

function Period({ from, to, setFrom, setTo }: { from: string; to: string; setFrom: (v: string) => void; setTo: (v: string) => void }) {
  return (
    <div className="flex flex-wrap items-end gap-3">
      <div className="space-y-1"><Label>{tx('From')}</Label><Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></div>
      <div className="space-y-1"><Label>{tx('To')}</Label><Input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></div>
    </div>
  );
}

function PnlTab({ centers }: { centers: Array<CostCenterNode & { depth: number }> }) {
  const [from, setFrom] = useState(firstOfMonth());
  const [to, setTo] = useState(today());
  const [center, setCenter] = useState('');
  const { data, isLoading } = useCostCenterPnl(from, to, center || undefined);
  const depth = useMemo(() => Object.fromEntries(centers.map((c) => [c.id, c.depth])), [centers]);
  return (
    <Card>
      <CardHeader><CardTitle>{tx('Cost centre profit & loss')}</CardTitle></CardHeader>
      <CardContent className="space-y-4">
        <div className="flex flex-wrap items-end gap-3">
          <Period from={from} to={to} setFrom={setFrom} setTo={setTo} />
          <div className="min-w-[240px] space-y-1"><Label>{tx('Centre (with children)')}</Label><CenterSelect value={center} onChange={setCenter} centers={centers} allowEmpty={tx('All centres')} /></div>
        </div>
        {isLoading ? <p className="text-sm text-muted-foreground">{tx('Loading…')}</p> : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Centre')}</TableHead>
                <TableHead className="text-right">{tx('Income')}</TableHead>
                <TableHead className="text-right">{tx('Expense')}</TableHead>
                <TableHead className="text-right">{tx('Net')}</TableHead>
                <TableHead className="text-right">{tx('Net incl. children')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(data?.costCenters ?? []).map((r: any) => (
                <TableRow key={r.costCenterId}>
                  <TableCell style={{ paddingLeft: `${(depth[r.costCenterId] ?? 0) * 16 + 8}px` }}>
                    <span className="font-mono text-xs">{r.code}</span> {r.name}
                  </TableCell>
                  <TableCell className="text-right">{money(Number(r.income))}</TableCell>
                  <TableCell className="text-right">{money(Number(r.expense))}</TableCell>
                  <TableCell className={`text-right ${Number(r.net) < 0 ? 'text-destructive' : ''}`}>{money(Number(r.net))}</TableCell>
                  <TableCell className={`text-right font-medium ${Number(r.rollupNet) < 0 ? 'text-destructive' : ''}`}>{money(Number(r.rollupNet))}</TableCell>
                </TableRow>
              ))}
              {data?.unassigned && (
                <TableRow>
                  <TableCell className="italic text-muted-foreground">{tx('No cost centre')}</TableCell>
                  <TableCell className="text-right">{money(Number(data.unassigned.income))}</TableCell>
                  <TableCell className="text-right">{money(Number(data.unassigned.expense))}</TableCell>
                  <TableCell className="text-right">{money(Number(data.unassigned.net))}</TableCell>
                  <TableCell />
                </TableRow>
              )}
              {data?.company && (
                <TableRow className="font-semibold">
                  <TableCell>{tx('Company total')}</TableCell>
                  <TableCell className="text-right">{money(Number(data.company.income))}</TableCell>
                  <TableCell className="text-right">{money(Number(data.company.expense))}</TableCell>
                  <TableCell className="text-right">{money(Number(data.company.net))}</TableCell>
                  <TableCell />
                </TableRow>
              )}
            </TableBody>
          </Table>
        )}
      </CardContent>
    </Card>
  );
}

function MatrixTab() {
  const [from, setFrom] = useState(firstOfMonth());
  const [to, setTo] = useState(today());
  const { data } = useCostCenterMatrix(from, to);
  const cols: string[] = useMemo(() => Object.keys(data?.columnTotals ?? {}), [data]);
  const name = (id: string) => (id === 'unassigned' ? tx('None') : data?.costCenters?.find((c: any) => c.id === id)?.code ?? id);
  return (
    <Card>
      <CardHeader><CardTitle>{tx('Cost centre × account')}</CardTitle></CardHeader>
      <CardContent className="space-y-4">
        <Period from={from} to={to} setFrom={setFrom} setTo={setTo} />
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Account')}</TableHead>
                {cols.map((c) => <TableHead key={c} className="text-right">{name(c)}</TableHead>)}
                <TableHead className="text-right">{tx('Total')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(data?.accounts ?? []).map((a: any) => (
                <TableRow key={a.accountId}>
                  <TableCell><span className="font-mono text-xs">{a.code}</span> {a.name}</TableCell>
                  {cols.map((c) => <TableCell key={c} className="text-right">{a.byCostCenter[c] ? money(Number(a.byCostCenter[c])) : '—'}</TableCell>)}
                  <TableCell className="text-right font-medium">{money(Number(a.total))}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </CardContent>
    </Card>
  );
}

function HierarchyTab({ tree }: { tree: CostCenterNode[] }) {
  const render = (nodes: CostCenterNode[], depth: number): JSX.Element[] =>
    nodes.flatMap((n) => [
      <div key={n.id} className="flex items-center gap-2 py-1" style={{ paddingLeft: depth * 20 }}>
        <span className="font-mono text-xs">{n.code}</span>
        <span>{n.name}</span>
        <Badge variant="outline">{n.category}</Badge>
        <Badge variant={n.type === 'profit' ? 'default' : 'secondary'}>{n.type}</Badge>
        {!n.isActive && <Badge variant="destructive">{tx('Inactive')}</Badge>}
        {(n.validFrom || n.validTo) && <span className="text-xs text-muted-foreground">{date(n.validFrom)} – {date(n.validTo)}</span>}
      </div>,
      ...render(n.children, depth + 1),
    ]);
  return (
    <Card>
      <CardHeader><CardTitle>{tx('Hierarchy')}</CardTitle></CardHeader>
      <CardContent className="text-sm">
        {tree.length ? render(tree, 0) : <p className="text-muted-foreground">{tx('No cost centres yet. Create them under Cost Centers.')}</p>}
        <p className="mt-3 text-xs text-muted-foreground">{tx('Set the parent, category and validity on the Cost Centers page.')}</p>
      </CardContent>
    </Card>
  );
}

type RuleDraft = Omit<Partial<AllocationRule>, 'targets'> & { targets: Array<{ costCenterId: string; weight: string }> };

function RulesTab({ centers, canAllocate }: { centers: Array<CostCenterNode & { depth: number }>; canAllocate: boolean }) {
  const { data: rules = [] } = useAllocationRules();
  const save = useSaveAllocationRule();
  const remove = useDeleteAllocationRule();
  const run = useRunAllocation();
  const [editing, setEditing] = useState<RuleDraft | null>(null);
  const [runFor, setRunFor] = useState<AllocationRule | null>(null);
  const [from, setFrom] = useState(firstOfMonth());
  const [to, setTo] = useState(today());
  const { data: preview } = useAllocationPreview(runFor?.id, from, to);

  const submit = async () => {
    if (!editing) return;
    try {
      await save.mutateAsync({
        id: editing.id,
        code: editing.code,
        name: editing.name,
        kind: editing.id ? undefined : editing.kind,
        sourceCostCenterId: editing.kind === 'periodic' ? editing.sourceCostCenterId : undefined,
        notes: editing.notes || undefined,
        targets: editing.targets.filter((t) => t.costCenterId && Number(t.weight) > 0).map((t) => ({ costCenterId: t.costCenterId, weight: Number(t.weight) })),
      });
      notify.success(tx('Rule saved'));
      setEditing(null);
    } catch (e) {
      notify.error(tx('Could not save rule'), errMsg(e));
    }
  };

  const doRun = async () => {
    if (!runFor) return;
    try {
      const r = await run.mutateAsync({ id: runFor.id, periodFrom: from, periodTo: to });
      notify.success(tx('Allocated {{v}} — entry {{e}}', { v: money(Number(r.totalAmount)), e: r.entryNumber ?? '' }));
      setRunFor(null);
    } catch (e) {
      notify.error(tx('Allocation failed'), errMsg(e));
    }
  };

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle>{tx('Allocation rules')}</CardTitle>
        {canAllocate && (
          <Button size="sm" onClick={() => setEditing({ kind: 'split', code: '', name: '', targets: [{ costCenterId: '', weight: '60' }, { costCenterId: '', weight: '40' }] })}>
            <Plus className="mr-1 h-4 w-4" /> {tx('New rule')}
          </Button>
        )}
      </CardHeader>
      <CardContent className="space-y-3">
        <p className="text-xs text-muted-foreground">
          {tx('Split rules divide one posting line across centres (tag the line with the rule). Periodic rules move a source centre’s period cost to the targets with one allocation entry.')}
        </p>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{tx('Code')}</TableHead>
              <TableHead>{tx('Kind')}</TableHead>
              <TableHead>{tx('Source')}</TableHead>
              <TableHead>{tx('Targets')}</TableHead>
              <TableHead className="text-right">{tx('Actions')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {rules.map((r) => (
              <TableRow key={r.id}>
                <TableCell><span className="font-mono text-xs">{r.code}</span> {r.name}</TableCell>
                <TableCell><Badge variant="outline">{r.kind}</Badge></TableCell>
                <TableCell>{r.sourceCostCenterCode ?? '—'}</TableCell>
                <TableCell className="text-sm">{r.targets.map((t) => `${t.code} ${Number(t.sharePct).toFixed(1)}%`).join(' · ')}</TableCell>
                <TableCell className="space-x-1 text-right">
                  {canAllocate && r.kind === 'periodic' && <Button size="sm" variant="outline" onClick={() => setRunFor(r)}><Play className="mr-1 h-3 w-3" /> {tx('Run')}</Button>}
                  {canAllocate && (
                    <Button size="sm" variant="ghost" onClick={() => setEditing({ ...r, targets: r.targets.map((t) => ({ costCenterId: t.costCenterId, weight: t.weight })) })}>{tx('Edit')}</Button>
                  )}
                  {canAllocate && <Button size="sm" variant="ghost" onClick={() => remove.mutate(r.id)}><Trash2 className="h-4 w-4" /></Button>}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>

      <Dialog open={!!editing} onOpenChange={(o) => !o && setEditing(null)}>
        <DialogContent className="max-w-lg">
          <DialogHeader><DialogTitle>{editing?.id ? tx('Edit rule') : tx('New allocation rule')}</DialogTitle></DialogHeader>
          {editing && (
            <div className="space-y-3">
              <div className="grid grid-cols-2 gap-3">
                <div className="space-y-1"><Label>{tx('Code')}</Label><Input value={editing.code ?? ''} onChange={(e) => setEditing({ ...editing, code: e.target.value })} /></div>
                <div className="space-y-1"><Label>{tx('Name')}</Label><Input value={editing.name ?? ''} onChange={(e) => setEditing({ ...editing, name: e.target.value })} /></div>
              </div>
              {!editing.id && (
                <div className="space-y-1">
                  <Label>{tx('Kind')}</Label>
                  <select className="w-full rounded-md border p-2 text-sm" value={editing.kind} onChange={(e) => setEditing({ ...editing, kind: e.target.value as any })}>
                    <option value="split">{tx('Split a posting line')}</option>
                    <option value="periodic">{tx('Periodic allocation of a source centre')}</option>
                  </select>
                </div>
              )}
              {editing.kind === 'periodic' && (
                <div className="space-y-1"><Label>{tx('Source centre')}</Label><CenterSelect value={editing.sourceCostCenterId ?? ''} onChange={(v) => setEditing({ ...editing, sourceCostCenterId: v })} centers={centers} /></div>
              )}
              <Label>{tx('Targets and weights (percent, floor area, headcount…)')}</Label>
              {editing.targets.map((t, i) => (
                <div key={i} className="flex gap-2">
                  <div className="flex-1"><CenterSelect value={t.costCenterId} centers={centers} onChange={(v) => setEditing({ ...editing, targets: editing.targets.map((x, j) => (j === i ? { ...x, costCenterId: v } : x)) })} /></div>
                  <Input className="w-28" type="number" min="0" value={t.weight} onChange={(e) => setEditing({ ...editing, targets: editing.targets.map((x, j) => (j === i ? { ...x, weight: e.target.value } : x)) })} />
                  <Button variant="ghost" size="icon" onClick={() => setEditing({ ...editing, targets: editing.targets.filter((_, j) => j !== i) })}><Trash2 className="h-4 w-4" /></Button>
                </div>
              ))}
              <Button variant="outline" size="sm" onClick={() => setEditing({ ...editing, targets: [...editing.targets, { costCenterId: '', weight: '1' }] })}><Plus className="mr-1 h-3 w-3" /> {tx('Add target')}</Button>
            </div>
          )}
          <DialogFooter>
            <Button variant="outline" onClick={() => setEditing(null)}>{tx('Cancel')}</Button>
            <Button onClick={submit} disabled={save.isPending}>{tx('Save')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={!!runFor} onOpenChange={(o) => !o && setRunFor(null)}>
        <DialogContent className="max-w-xl">
          <DialogHeader><DialogTitle>{tx('Run {{r}}', { r: runFor?.code ?? '' })}</DialogTitle></DialogHeader>
          <Period from={from} to={to} setFrom={setFrom} setTo={setTo} />
          {preview && (
            <div className="space-y-2 text-sm">
              <p className="font-medium">{tx('Moves {{v}} out of {{s}}', { v: money(Number(preview.totalAmount)), s: runFor?.sourceCostCenterCode ?? '' })}</p>
              {preview.accounts.map((a: any) => (
                <div key={a.accountId}>
                  <span className="font-mono text-xs">{a.accountCode}</span> {a.accountName}: {money(Number(a.balance))} →{' '}
                  {a.targets.map((t: any) => `${t.costCenterCode} ${money(Number(t.amount))}`).join(', ')}
                </div>
              ))}
            </div>
          )}
          <DialogFooter>
            <Button variant="outline" onClick={() => setRunFor(null)}>{tx('Cancel')}</Button>
            <Button onClick={doRun} disabled={run.isPending || !preview || Number(preview.totalAmount) === 0}>{tx('Post allocation')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </Card>
  );
}

function RunsTab({ canAllocate }: { canAllocate: boolean }) {
  const { data: runs = [] } = useAllocationRuns();
  const reverse = useReverseAllocationRun();
  return (
    <Card>
      <CardHeader><CardTitle>{tx('Allocation runs')}</CardTitle></CardHeader>
      <CardContent>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{tx('Rule')}</TableHead>
              <TableHead>{tx('Period')}</TableHead>
              <TableHead className="text-right">{tx('Amount')}</TableHead>
              <TableHead>{tx('Entry')}</TableHead>
              <TableHead>{tx('Status')}</TableHead>
              <TableHead />
            </TableRow>
          </TableHeader>
          <TableBody>
            {runs.map((r) => (
              <TableRow key={r.id}>
                <TableCell><span className="font-mono text-xs">{r.ruleCode}</span> {r.ruleName}</TableCell>
                <TableCell>{date(r.periodFrom)} – {date(r.periodTo)}</TableCell>
                <TableCell className="text-right">{money(Number(r.totalAmount))}</TableCell>
                <TableCell className="font-mono text-xs">{r.entryNumber}</TableCell>
                <TableCell><Badge variant={r.status === 'posted' ? 'default' : 'outline'}>{r.status}</Badge></TableCell>
                <TableCell className="text-right">
                  {canAllocate && r.status === 'posted' && (
                    <Button size="sm" variant="ghost" onClick={() => reverse.mutate(r.id, { onError: (e) => notify.error(tx('Reverse failed'), errMsg(e)) })}>
                      <Undo2 className="mr-1 h-3 w-3" /> {tx('Reverse')}
                    </Button>
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

function DefaultsTab({ centers, canUpdate }: { centers: Array<CostCenterNode & { depth: number }>; canUpdate: boolean }) {
  const { data: defaults = [] } = useCostCenterDefaults();
  const { data: rules = [] } = useAccountDimensionRules();
  const { data: accounts } = useAccounts();
  const saveDefault = useSaveCostCenterDefault();
  const removeDefault = useDeleteCostCenterDefault();
  const setRule = useSetAccountDimensionRule();
  const [d, setD] = useState({ scopeType: 'branch', scopeId: '', costCenterId: '' });
  const [r, setR] = useState({ accountId: '', rule: 'required' as 'required' | 'optional' | 'forbidden' });
  const accountList = accounts?.data ?? [];

  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <Card>
        <CardHeader><CardTitle>{tx('Default cost centres')}</CardTitle></CardHeader>
        <CardContent className="space-y-3 text-sm">
          <p className="text-xs text-muted-foreground">{tx('Revenue and expense lines without a centre inherit one: partner first, then branch, then the posting user.')}</p>
          {canUpdate && (
            <div className="grid grid-cols-3 gap-2">
              <select className="rounded-md border p-2" value={d.scopeType} onChange={(e) => setD({ ...d, scopeType: e.target.value })}>
                {['branch', 'partner', 'user', 'location', 'employee'].map((s) => <option key={s} value={s}>{s}</option>)}
              </select>
              <Input placeholder={tx('Branch / partner / user id')} value={d.scopeId} onChange={(e) => setD({ ...d, scopeId: e.target.value })} />
              <CenterSelect value={d.costCenterId} onChange={(v) => setD({ ...d, costCenterId: v })} centers={centers} />
              <Button className="col-span-3" size="sm" disabled={!d.scopeId || !d.costCenterId}
                onClick={() => saveDefault.mutate(d, { onError: (e) => notify.error(tx('Could not save'), errMsg(e)), onSuccess: () => setD({ ...d, scopeId: '' }) })}>
                {tx('Save default')}
              </Button>
            </div>
          )}
          <Table>
            <TableBody>
              {defaults.map((x) => (
                <TableRow key={x.id}>
                  <TableCell>{x.scopeType}</TableCell>
                  <TableCell className="font-mono text-xs">{x.scopeId}</TableCell>
                  <TableCell>{x.costCenterCode} · {x.costCenterName}</TableCell>
                  <TableCell className="text-right">{canUpdate && <Button size="icon" variant="ghost" onClick={() => removeDefault.mutate(x.id)}><Trash2 className="h-4 w-4" /></Button>}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
      <Card>
        <CardHeader><CardTitle>{tx('Account rules')}</CardTitle></CardHeader>
        <CardContent className="space-y-3 text-sm">
          <p className="text-xs text-muted-foreground">{tx('Required: every line on the account needs a centre. Forbidden: balance-sheet style accounts never carry one.')}</p>
          {canUpdate && (
            <div className="grid grid-cols-3 gap-2">
              <select className="col-span-2 rounded-md border p-2" value={r.accountId} onChange={(e) => setR({ ...r, accountId: e.target.value })}>
                <option value="">{tx('Account…')}</option>
                {accountList.map((a: any) => <option key={a.id} value={a.id}>{a.code} · {a.name}</option>)}
              </select>
              <select className="rounded-md border p-2" value={r.rule} onChange={(e) => setR({ ...r, rule: e.target.value as any })}>
                <option value="required">{tx('Required')}</option>
                <option value="optional">{tx('Optional')}</option>
                <option value="forbidden">{tx('Forbidden')}</option>
              </select>
              <Button className="col-span-3" size="sm" disabled={!r.accountId} onClick={() => setRule.mutate(r, { onError: (e) => notify.error(tx('Could not save'), errMsg(e)) })}>{tx('Save rule')}</Button>
            </div>
          )}
          <Table>
            <TableBody>
              {rules.map((x) => (
                <TableRow key={x.id}>
                  <TableCell><span className="font-mono text-xs">{x.accountCode}</span> {x.accountName}</TableCell>
                  <TableCell><Badge variant={x.rule === 'required' ? 'default' : 'destructive'}>{x.rule}</Badge></TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  );
}

export default CostCenterAccountingPage;
