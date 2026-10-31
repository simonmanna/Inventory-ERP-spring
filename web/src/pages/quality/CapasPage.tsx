import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Eye, ListTodo, Plus, Trash2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, FilterChips, FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput, useListState,
  type ActiveChip,
} from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  CAPA_STATUS_LABEL, ROOT_CAUSE_LABEL, useCapas, useCreateCapa, useOverdueCapa, type Capa, type CapaStatus, type Ncr,
} from '@/features/quality/api';
import { CapaStatusPill, Field, OptionalSelect, UserPicker } from './quality-ui';
import { toOptions } from './quality-format';

const FILTERS = { status: '', overdue: '' };

/** CAPA register (ADR-S013) with the overdue actions panel used for follow-up. */
export function CapasPage() {
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const list = useListState(FILTERS);
  const { status, overdue } = list.filters;
  const [creating, setCreating] = useState(false);
  const { data, isLoading, isFetching, isError } = useCapas({
    page: list.page, pageSize: list.pageSize, search: list.search || undefined, status: status || undefined,
    overdue: overdue === 'true' ? true : undefined,
  });
  const late = useOverdueCapa();
  const chips: ActiveChip[] = [
    ...(list.search ? [{ key: 'q', label: `“${list.search}”`, onRemove: () => list.setSearchInput('') }] : []),
    ...(status ? [{ key: 'status', label: CAPA_STATUS_LABEL[status as CapaStatus] ?? status, onRemove: () => list.setFilter('status', '') }] : []),
    ...(overdue ? [{ key: 'overdue', label: tx('Overdue'), onRemove: () => list.setFilter('overdue', '') }] : []),
  ];
  const columns: Column<Capa>[] = [
    { key: 'number', header: tx('Number'), render: (c) => <span className="font-medium text-primary">{c.capaNumber}</span> },
    { key: 'title', header: tx('Title'), render: (c) => <span className="line-clamp-1 max-w-[18rem]">{c.title}</span> },
    { key: 'ncr', header: tx('NCR'), render: (c) => c.ncrNumber ?? '—' },
    { key: 'owner', header: tx('Owner'), render: (c) => c.ownerName || '—' },
    { key: 'due', header: tx('Due'), render: (c) => (c.dueDate ? <span className={c.overdue ? 'font-medium text-rose-600' : ''}>{date(c.dueDate)}</span> : '—') },
    { key: 'actions-count', header: tx('Open actions'), className: 'text-right', render: (c) => `${c.openActionCount} / ${c.actionCount}` },
    { key: 'status', header: tx('Status'), render: (c) => <CapaStatusPill status={c.status} /> },
    {
      key: 'actions', header: '', className: 'w-12 text-right',
      render: (c) => <Button variant="ghost" size="icon" className="h-8 w-8" aria-label={tx('View')} onClick={() => navigate(`/quality/capas/${c.id}`)}><Eye className="h-4 w-4" /></Button>,
    },
  ];
  const lateActions = late.data?.actions ?? [];
  return (
    <div className="space-y-4">
      <ListPageHeader icon={ListTodo} title={tx('Corrective & Preventive Actions')}
        description={tx('Root cause, actions with owners and due dates, and the effectiveness check before closing.')}
        actions={has(PERMISSIONS.quality.capa) && <Button onClick={() => setCreating(true)}><Plus className="mr-1.5 h-4 w-4" /> {tx('New CAPA')}</Button>} />
      {lateActions.length > 0 && (
        <Card className="border-rose-200">
          <CardHeader className="pb-2"><CardTitle className="text-base text-rose-700">{tx('{{count}} overdue action(s)', { count: lateActions.length })}</CardTitle></CardHeader>
          <CardContent>
            <ul className="divide-y text-sm">
              {lateActions.slice(0, 8).map((a) => (
                <li key={a.id} className="flex flex-wrap items-center justify-between gap-2 py-1.5">
                  <button type="button" className="text-primary hover:underline" onClick={() => navigate(`/quality/capas/${a.capaId}`)}>{a.capaNumber}</button>
                  <span className="min-w-0 flex-1 truncate">{a.description}</span>
                  <span className="text-muted-foreground">{a.ownerName || '—'}</span>
                  <span className="font-medium text-rose-600">{tx('{{days}} day(s) late', { days: a.daysOverdue })}</span>
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>
      )}
      <ListToolbar chips={<FilterChips chips={chips} onClearAll={list.clearFilters} />}>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search number or title…')} />
        <FilterSelect value={status} onChange={(v) => list.setFilter('status', v)} options={toOptions(CAPA_STATUS_LABEL)} allLabel={tx('All statuses')} />
        <FilterSelect value={overdue} onChange={(v) => list.setFilter('overdue', v)} options={[{ value: 'true', label: tx('Overdue') }]} allLabel={tx('Any due date')} />
      </ListToolbar>
      <ListCard>
        <div className={isFetching && !isLoading ? 'opacity-60 transition-opacity' : 'transition-opacity'}>
          <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} getRowId={(c) => c.id}
            onRowClick={(c) => navigate(`/quality/capas/${c.id}`)} cellClassName="py-2.5 px-4" headerRowClassName="h-10"
            emptyMessage={isError ? tx('Could not load the CAPAs.') : chips.length ? tx('No CAPAs match these filters.') : tx('No CAPA yet.')} />
        </div>
        {data?.meta && (
          <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
            onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="CAPA" />
        )}
      </ListCard>
      {creating && <CapaFormDialog onClose={() => setCreating(false)} onSaved={(c) => navigate(`/quality/capas/${c.id}`)} />}
    </div>
  );
}

interface ActionDraft { kind: 'corrective' | 'preventive'; description: string; ownerUserId: string; dueDate: string }

/** New CAPA (optionally for an NCR) with its first actions. */
export function CapaFormDialog({ ncr, onClose, onSaved }: { ncr?: Ncr; onClose: () => void; onSaved?: (c: Capa) => void }) {
  const [title, setTitle] = useState(ncr ? ncr.title : '');
  const [rootCause, setRootCause] = useState(ncr?.rootCause ?? '');
  const [method, setMethod] = useState('');
  const [ownerUserId, setOwnerUserId] = useState('');
  const [dueDate, setDueDate] = useState('');
  const [actions, setActions] = useState<ActionDraft[]>([{ kind: 'corrective', description: '', ownerUserId: '', dueDate: '' }]);
  const create = useCreateCapa();
  const setAction = (i: number, patch: Partial<ActionDraft>) => setActions((xs) => xs.map((a, j) => (j === i ? { ...a, ...patch } : a)));
  const submit = async () => {
    try {
      const out = await create.mutateAsync({
        title: title.trim(), ncrId: ncr?.id, rootCause: rootCause.trim() || undefined, rootCauseMethod: method || undefined,
        ownerUserId: ownerUserId || undefined, dueDate: dueDate || undefined,
        actions: actions.filter((a) => a.description.trim()).map((a) => ({
          kind: a.kind, description: a.description.trim(), ownerUserId: a.ownerUserId || undefined, dueDate: a.dueDate || undefined,
        })),
      });
      notify.success(tx('CAPA {{number}} created', { number: out.capaNumber }));
      onSaved?.(out);
      onClose();
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not save the CAPA')));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{tx('New CAPA')}</DialogTitle>
          <DialogDescription>{ncr ? tx('For {{number}}.', { number: ncr.ncrNumber }) : tx('Corrective and preventive actions with owners and due dates.')}</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Field label={tx('Title *')} className="sm:col-span-2"><Input value={title} onChange={(e) => setTitle(e.target.value)} /></Field>
          <Field label={tx('Root cause method')}><OptionalSelect value={method} onChange={setMethod} placeholder={tx('Choose…')} noneLabel="—" options={toOptions(ROOT_CAUSE_LABEL)} /></Field>
          <Field label={tx('Owner')}><UserPicker value={ownerUserId} onChange={setOwnerUserId} /></Field>
          <Field label={tx('Due date')}><Input type="date" value={dueDate} onChange={(e) => setDueDate(e.target.value)} /></Field>
          <Field label={tx('Root cause')} className="sm:col-span-2"><Textarea rows={2} value={rootCause} onChange={(e) => setRootCause(e.target.value)} /></Field>
        </div>
        <Card>
          <CardHeader className="py-3"><CardTitle className="text-sm">{tx('Actions')}</CardTitle></CardHeader>
          <CardContent className="space-y-3">
            {actions.map((a, i) => (
              <ActionRow key={i} a={a} onChange={(p) => setAction(i, p)} onRemove={() => setActions((xs) => xs.filter((_, j) => j !== i))} />
            ))}
            <Button size="sm" variant="outline" onClick={() => setActions((xs) => [...xs, { kind: 'corrective', description: '', ownerUserId: '', dueDate: '' }])}><Plus className="mr-1 h-4 w-4" /> {tx('Add action')}</Button>
          </CardContent>
        </Card>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={!title.trim() || create.isPending}>{tx('Save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export function ActionRow({ a, onChange, onRemove }: { a: ActionDraft; onChange: (p: Partial<ActionDraft>) => void; onRemove?: () => void }) {
  return (
    <div className="grid grid-cols-1 gap-2 rounded-md border p-2 sm:grid-cols-[9rem_1fr_10rem_9rem_auto]">
      <OptionalSelect value={a.kind} onChange={(v) => onChange({ kind: (v || 'corrective') as ActionDraft['kind'] })} placeholder={tx('Kind')}
        options={[{ value: 'corrective', label: tx('Corrective') }, { value: 'preventive', label: tx('Preventive') }]} />
      <Input value={a.description} placeholder={tx('Action')} onChange={(e) => onChange({ description: e.target.value })} />
      <UserPicker value={a.ownerUserId} onChange={(v) => onChange({ ownerUserId: v })} />
      <Input type="date" aria-label={tx('Due date')} value={a.dueDate} onChange={(e) => onChange({ dueDate: e.target.value })} />
      {onRemove ? <Button variant="ghost" size="icon" aria-label={tx('Remove')} onClick={onRemove}><Trash2 className="h-4 w-4" /></Button> : <span />}
    </div>
  );
}
