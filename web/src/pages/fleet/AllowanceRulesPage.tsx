import { useState } from 'react';
import { Edit, HandCoins, Plus, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import { ListCard, ListPageHeader, StatusPill } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useFleetLookups } from '@/features/fleet/api';
import {
  BASIS_LABEL, useAllowanceRules, useCreateRule, useDeleteRule, useUpdateRule, type AllowanceBasis, type AllowanceRule,
} from '@/features/fleet/drivers-api';
import { Field, OptionalSelect } from './fleet-ui';
import { toOptions } from './fleet-format';

/** Fleet settings: driver allowance rules — per trip / day / night out, effective dates, class / zone scope (1.2). */
export function AllowanceRulesPage() {
  const canManage = useAuthStore((s) => s.hasPermission(PERMISSIONS.fleet.settings));
  const [showInactive, setShowInactive] = useState(false);
  const { data, isLoading, isError } = useAllowanceRules(showInactive);
  const [editing, setEditing] = useState<AllowanceRule | 'new' | null>(null);
  const remove = useDeleteRule();
  const update = useUpdateRule();

  const del = async (r: AllowanceRule) => {
    if (!window.confirm(tx('Delete allowance rule {{code}}?', { code: r.code }))) return;
    try {
      const res = await remove.mutateAsync(r.id);
      notify.success(res.deleted ? tx('Rule deleted') : tx('The rule is used by claims, so it was deactivated'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not delete the rule')));
    }
  };

  const toggle = async (r: AllowanceRule) => {
    try {
      await update.mutateAsync({ id: r.id, body: { isActive: !r.isActive } });
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not update the rule')));
    }
  };

  const columns: Column<AllowanceRule>[] = [
    { key: 'code', header: tx('Code'), render: (r) => <span className="font-mono text-xs">{r.code}</span> },
    { key: 'name', header: tx('Name'), render: (r) => <span className="font-medium">{r.name}</span> },
    { key: 'basis', header: tx('Basis'), render: (r) => BASIS_LABEL[r.basis] },
    { key: 'amount', header: tx('Amount'), className: 'text-right', render: (r) => <span className="tabular-nums">{money(r.amount, r.currencyCode)}</span> },
    { key: 'period', header: tx('Effective'), render: (r) => `${date(r.effectiveFrom)} – ${r.effectiveTo ? date(r.effectiveTo) : tx('open')}` },
    { key: 'scope', header: tx('Applies to'), render: (r) => [r.categoryName ?? tx('All classes'), r.routeZone ?? tx('All zones')].join(' · ') },
    { key: 'status', header: tx('Status'), render: (r) => <StatusPill tone={r.isActive ? 'success' : 'neutral'}>{r.isActive ? tx('Active') : tx('Inactive')}</StatusPill> },
    {
      key: 'actions', header: '', className: 'text-right',
      render: (r) => canManage && (
        <div className="flex justify-end gap-1">
          <Button size="sm" variant="ghost" onClick={() => toggle(r)}>{r.isActive ? tx('Deactivate') : tx('Activate')}</Button>
          <Button size="icon" variant="ghost" className="h-8 w-8" aria-label={tx('Edit')} onClick={() => setEditing(r)}><Edit className="h-4 w-4" /></Button>
          <Button size="icon" variant="ghost" className="h-8 w-8" aria-label={tx('Delete')} onClick={() => del(r)}><Trash2 className="h-4 w-4" /></Button>
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader
        icon={HandCoins}
        title={tx('Driver Allowance Rules')}
        description={tx('What a driver earns per trip, per day or per night out. The most specific rule for the vehicle class and route zone applies.')}
        actions={canManage && <Button onClick={() => setEditing('new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('Add rule')}</Button>}
      />
      <label className="flex items-center gap-2 text-sm">
        <input type="checkbox" className="h-4 w-4" checked={showInactive} onChange={(e) => setShowInactive(e.target.checked)} />
        {tx('Show inactive rules')}
      </label>
      <ListCard>
        <DataTable columns={columns} data={data ?? []} loading={isLoading} getRowId={(r) => r.id} cellClassName="py-2.5 px-4"
          emptyMessage={isError ? tx('Could not load the rules.') : tx('No allowance rules yet. Add one so claims can be priced.')} />
      </ListCard>
      {editing && <RuleDialog rule={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

function RuleDialog({ rule, onClose }: { rule: AllowanceRule | null; onClose: () => void }) {
  const lookups = useFleetLookups();
  const create = useCreateRule();
  const update = useUpdateRule();
  const today = new Date().toISOString().slice(0, 10);
  const [code, setCode] = useState(rule?.code ?? '');
  const [name, setName] = useState(rule?.name ?? '');
  const [basis, setBasis] = useState<AllowanceBasis>(rule?.basis ?? 'per_day');
  const [amount, setAmount] = useState(rule?.amount ?? '');
  const [from, setFrom] = useState(rule?.effectiveFrom ?? today);
  const [to, setTo] = useState(rule?.effectiveTo ?? '');
  const [categoryId, setCategoryId] = useState(rule?.categoryId ?? '');
  const [zone, setZone] = useState(rule?.routeZone ?? '');
  const [notes, setNotes] = useState(rule?.notes ?? '');
  const amountError = amount !== '' && !(Number(amount) > 0) ? tx('Enter an amount greater than zero') : undefined;
  const periodError = to && from && to < from ? tx('The rule must end on or after its start date') : undefined;
  const valid = (rule || (code.trim() && name.trim())) && Number(amount) > 0 && from && !periodError;

  const save = async () => {
    try {
      if (rule) {
        const clear: string[] = [];
        if (!to && rule.effectiveTo) clear.push('effectiveTo');
        if (!categoryId && rule.categoryId) clear.push('categoryId');
        if (!zone.trim() && rule.routeZone) clear.push('routeZone');
        if (!notes.trim() && rule.notes) clear.push('notes');
        await update.mutateAsync({ id: rule.id, body: {
          name: name.trim() || undefined, amount: Number(amount), effectiveFrom: from, effectiveTo: to || undefined,
          categoryId: categoryId || undefined, routeZone: zone.trim() || undefined, notes: notes.trim() || undefined,
          clear: clear.length ? clear : undefined,
        } });
      } else {
        await create.mutateAsync({ code: code.trim(), name: name.trim(), basis, amount: Number(amount), effectiveFrom: from,
          effectiveTo: to || undefined, categoryId: categoryId || undefined, routeZone: zone.trim() || undefined,
          notes: notes.trim() || undefined });
      }
      notify.success(tx('Rule saved'));
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the rule')));
    }
  };

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{rule ? tx('Edit rule {{code}}', { code: rule.code }) : tx('Add allowance rule')}</DialogTitle>
          <DialogDescription>{tx('Amounts are in the organization currency. Rules with the same basis and scope must not overlap in time.')}</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label={tx('Code *')}><Input value={code} onChange={(e) => setCode(e.target.value)} disabled={!!rule} className="uppercase" /></Field>
          <Field label={tx('Name *')}><Input value={name} onChange={(e) => setName(e.target.value)} /></Field>
          <Field label={tx('Basis')}>
            <OptionalSelect value={basis} onChange={(v) => setBasis((v || 'per_day') as AllowanceBasis)} placeholder={tx('Basis')} options={toOptions(BASIS_LABEL)} disabled={!!rule} />
          </Field>
          <Field label={tx('Amount *')} error={amountError}><Input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} /></Field>
          <Field label={tx('Effective from *')}><Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></Field>
          <Field label={tx('Effective to')} error={periodError}><Input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></Field>
          <Field label={tx('Vehicle class')}>
            <OptionalSelect value={categoryId} onChange={setCategoryId} placeholder={tx('All classes')} noneLabel={tx('All classes')}
              options={(lookups.data?.categories ?? []).map((c) => ({ value: c.id, label: c.name }))} />
          </Field>
          <Field label={tx('Route zone')}><Input value={zone} onChange={(e) => setZone(e.target.value)} placeholder={tx('All zones')} /></Field>
          <Field label={tx('Notes')} className="sm:col-span-2"><Input value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={save} disabled={!valid || create.isPending || update.isPending}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
