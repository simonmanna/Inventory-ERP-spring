import { useMemo, useState } from 'react';
import { CheckCircle2, Rocket, SlidersHorizontal, XCircle } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { ListPageHeader, StatusPill } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { useProductCategories } from '@/features/products/api';
import { useBranchOptions } from '@/features/finance/api';
import {
  errMsg, useCreatePolicy, usePolicies, usePolicyAction, usePolicyDefinitions, type Policy, type PolicyDefinition, type PolicyScope,
} from '@/features/procurement/api';
import { notify } from '@/lib/notify';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

const GROUP_LABEL: Record<string, string> = {
  supplier: tx('Suppliers'),
  route: tx('Purchasing routes'),
  receiving: tx('Receiving'),
  matching: tx('Invoice matching'),
  budget: tx('Budget control'),
  change: tx('Order changes'),
  landed: tx('Landed cost'),
};

const STATUS_TONE: Record<Policy['status'], 'neutral' | 'info' | 'success' | 'warning' | 'danger'> = {
  draft: 'neutral',
  approved: 'info',
  active: 'success',
  superseded: 'neutral',
  cancelled: 'danger',
};

const SCOPE_LABEL: Record<PolicyScope, string> = {
  org: tx('Whole organisation'),
  branch: tx('Branch'),
  category: tx('Product category'),
  department: tx('Department'),
  cost_center: tx('Cost centre'),
  project: tx('Project'),
};

function show(v: unknown): string {
  if (v === null || v === undefined) return tx('Not set');
  if (Array.isArray(v)) return v.length ? v.join(', ') : tx('None');
  return String(v);
}

/**
 * Purchasing policies (ADR-PROCUREMENT-001): every rule that shapes the purchasing workflow is a configurable,
 * effective-dated value — drafted, approved by a second person, then activated. Organisation-wide or per branch /
 * product category; the most specific active policy wins, otherwise the built-in default (today's behaviour).
 */
export function PurchasingPoliciesPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const canManage = has(PERMISSIONS.procurementPolicy.manage);
  const canApprove = has(PERMISSIONS.procurementPolicy.approve);
  const { data: defs = [] } = usePolicyDefinitions();
  const { data: versions = [] } = usePolicies();
  const { data: categories = [] } = useProductCategories();
  const { data: branches = [] } = useBranchOptions();
  const create = useCreatePolicy();
  const action = usePolicyAction();
  const [editing, setEditing] = useState<PolicyDefinition | null>(null);
  const [form, setForm] = useState({ scopeType: 'org' as PolicyScope, scopeId: '', value: '', effectiveFrom: new Date().toISOString().slice(0, 10), notes: '' });

  const groups = useMemo(() => {
    const out = new Map<string, PolicyDefinition[]>();
    for (const d of defs) out.set(d.group, [...(out.get(d.group) ?? []), d]);
    return [...out.entries()];
  }, [defs]);

  const scopeName = (p: Policy) => {
    if (p.scopeType === 'org') return SCOPE_LABEL.org;
    const name = p.scopeType === 'branch' ? branches.find((b: any) => b.id === p.scopeId)?.name
      : p.scopeType === 'category' ? categories.find((c: any) => c.id === p.scopeId)?.name : p.scopeId;
    return `${SCOPE_LABEL[p.scopeType]}: ${name ?? p.scopeId}`;
  };

  const open = (d: PolicyDefinition) => {
    const current = d.effective.value;
    setForm({
      scopeType: 'org',
      scopeId: '',
      value: Array.isArray(current) ? current.join(', ') : current == null ? '' : String(current),
      effectiveFrom: new Date().toISOString().slice(0, 10),
      notes: '',
    });
    setEditing(d);
  };

  const parsed = (d: PolicyDefinition, raw: string): unknown => {
    switch (d.kind) {
      case 'number': return Number(raw || 0);
      case 'optional_number': return raw.trim() === '' ? null : Number(raw);
      case 'string_list': return raw.split(',').map((s) => s.trim()).filter(Boolean);
      case 'boolean': return raw === 'true';
      default: return raw;
    }
  };

  const save = async () => {
    if (!editing) return;
    try {
      await create.mutateAsync({
        key: editing.key,
        scopeType: form.scopeType,
        scopeId: form.scopeType === 'org' ? undefined : form.scopeId,
        effectiveFrom: form.effectiveFrom,
        value: parsed(editing, form.value),
        notes: form.notes.trim() || undefined,
      });
      notify.success(tx('Saved as draft — a second person must approve it before it takes effect'));
      setEditing(null);
    } catch (e) {
      notify.error(tx('Could not save policy'), errMsg(e));
    }
  };

  const act = async (id: string, a: 'approve' | 'activate' | 'cancel') => {
    try {
      await action.mutateAsync({ id, action: a });
      notify.success(a === 'approve' ? tx('Policy approved') : a === 'activate' ? tx('Policy is now in force') : tx('Policy cancelled'));
    } catch (e) {
      notify.error(tx('Action failed'), errMsg(e));
    }
  };

  return (
    <div className="space-y-6 p-6">
      <ListPageHeader
        title={tx('Purchasing policies')}
        description={tx('Tune the purchasing workflow without code: who you may buy from, when a requisition is needed, receiving and matching tolerances, budget control, duplicate bills. Changes are approved by a second person and take effect on their date.')}
        icon={SlidersHorizontal}
      />

      {groups.map(([group, items]) => (
        <Card key={group}>
          <CardHeader><CardTitle>{GROUP_LABEL[group] ?? group}</CardTitle></CardHeader>
          <CardContent className="divide-y">
            {items.map((d) => {
              const own = versions.filter((v) => v.key === d.key);
              return (
                <div key={d.key} className="space-y-2 py-4 first:pt-0 last:pb-0">
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div className="max-w-2xl">
                      <div className="font-medium">{d.label}</div>
                      <p className="text-sm text-muted-foreground">{d.description}</p>
                    </div>
                    <div className="flex items-center gap-3">
                      <div className="text-right text-sm">
                        <div className="font-semibold">{show(d.effective.value)}</div>
                        <div className="text-xs text-muted-foreground">
                          {d.effective.source === 'default' ? tx('Default') : tx('Organisation policy')}
                        </div>
                      </div>
                      {canManage && <Button size="sm" variant="outline" onClick={() => open(d)}>{tx('Change')}</Button>}
                    </div>
                  </div>
                  {own.length > 0 && (
                    <Table>
                      <TableHeader><TableRow>
                        <TableHead>{tx('Scope')}</TableHead><TableHead>{tx('Value')}</TableHead><TableHead>{tx('Effective')}</TableHead>
                        <TableHead>{tx('Status')}</TableHead><TableHead className="text-right">{tx('Actions')}</TableHead>
                      </TableRow></TableHeader>
                      <TableBody>
                        {own.map((p) => (
                          <TableRow key={p.id}>
                            <TableCell>{scopeName(p)} <span className="text-xs text-muted-foreground">v{p.version}</span></TableCell>
                            <TableCell>{show(p.value)}</TableCell>
                            <TableCell>{date(p.effectiveFrom)}</TableCell>
                            <TableCell><StatusPill tone={STATUS_TONE[p.status]}>{p.status}</StatusPill></TableCell>
                            <TableCell className="space-x-1 text-right">
                              {canApprove && p.status === 'draft' && (
                                <Button size="sm" variant="outline" onClick={() => act(p.id, 'approve')}><CheckCircle2 className="mr-1 h-4 w-4" />{tx('Approve')}</Button>
                              )}
                              {canManage && p.status === 'approved' && (
                                <Button size="sm" onClick={() => act(p.id, 'activate')}><Rocket className="mr-1 h-4 w-4" />{tx('Activate')}</Button>
                              )}
                              {canManage && (p.status === 'draft' || p.status === 'approved') && (
                                <Button size="sm" variant="ghost" onClick={() => act(p.id, 'cancel')}><XCircle className="mr-1 h-4 w-4" />{tx('Cancel')}</Button>
                              )}
                            </TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  )}
                </div>
              );
            })}
          </CardContent>
        </Card>
      ))}

      <Dialog open={!!editing} onOpenChange={(o) => !o && setEditing(null)}>
        <DialogContent>
          <DialogHeader><DialogTitle>{editing?.label}</DialogTitle></DialogHeader>
          {editing && (
            <div className="grid gap-3">
              <p className="text-sm text-muted-foreground">{editing.description}</p>
              <div>
                <Label>{tx('Applies to')}</Label>
                <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={form.scopeType}
                  onChange={(e) => setForm({ ...form, scopeType: e.target.value as PolicyScope, scopeId: '' })}>
                  <option value="org">{SCOPE_LABEL.org}</option>
                  <option value="branch">{SCOPE_LABEL.branch}</option>
                  <option value="category">{SCOPE_LABEL.category}</option>
                </select>
              </div>
              {form.scopeType !== 'org' && (
                <div>
                  <Label>{SCOPE_LABEL[form.scopeType]}</Label>
                  <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={form.scopeId}
                    onChange={(e) => setForm({ ...form, scopeId: e.target.value })}>
                    <option value="">{tx('Select…')}</option>
                    {(form.scopeType === 'branch' ? branches : categories).map((o: any) => <option key={o.id} value={o.id}>{o.name}</option>)}
                  </select>
                </div>
              )}
              <div>
                <Label>{tx('Value')}</Label>
                {editing.kind === 'enum' ? (
                  <select className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={form.value}
                    onChange={(e) => setForm({ ...form, value: e.target.value })}>
                    {(editing.allowed ?? []).map((o) => <option key={o} value={o}>{o}</option>)}
                  </select>
                ) : (
                  <Input value={form.value} type={editing.kind.includes('number') ? 'number' : 'text'}
                    placeholder={editing.kind === 'string_list' ? tx('Comma separated') : editing.kind === 'optional_number' ? tx('Empty = not set') : ''}
                    onChange={(e) => setForm({ ...form, value: e.target.value })} />
                )}
              </div>
              <div><Label>{tx('Effective from')}</Label><Input type="date" value={form.effectiveFrom} onChange={(e) => setForm({ ...form, effectiveFrom: e.target.value })} /></div>
              <div><Label>{tx('Notes')}</Label><Input value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} /></div>
            </div>
          )}
          <DialogFooter>
            <Button variant="outline" onClick={() => setEditing(null)}>{tx('Cancel')}</Button>
            <Button onClick={save} disabled={form.scopeType !== 'org' && !form.scopeId}>{tx('Save draft')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
