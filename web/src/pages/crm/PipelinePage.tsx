import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { toast } from 'sonner';
import {
  DndContext, PointerSensor, closestCorners, useDraggable, useDroppable, useSensor, useSensors, type DragEndEvent,
} from '@dnd-kit/core';
import { ChevronDown, ChevronRight, KanbanSquare, List, Plus, Settings2 } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { DataTablePagination } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { apiErrorMessage } from '@/lib/api-error';
import { money, date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import {
  crmApi, useCrmLookups, useCrmMutation, useGrouped, useKanban, useOpportunities, type ActivityState, type GroupRow,
  type KanbanColumn, type Opportunity,
} from '@/features/crm/suite-api';
import { ActivityBadge, ActivityBar, Avatar, DealStatusPill, Stars, TagChips, colorOf, compact } from './crm-ui';
import { OpportunityDialog, ScheduleDialog } from './crm-dialogs';
import { CrmFilterBar, useCrmFilters, type FilterState } from './filter-bar';

const GROUPS = ['stage', 'owner', 'team', 'source', 'tag', 'partner', 'city', 'country', 'status', 'priority', 'lostReason', 'createdMonth', 'closeMonth', 'wonMonth'];

/** The opportunity kanban (Odoo-style) with list and grouped-table views, filters and saved searches (ADR-S010). */
export function PipelinePage() {
  const state = useCrmFilters({ mine: 'true' });
  const { filters, view, groupBy } = state;
  const canWrite = useAuthStore((s) => s.hasPermission(PERMISSIONS.crm.dealWrite));
  const canConfigure = useAuthStore((s) => s.hasPermission(PERMISSIONS.crm.configure));
  const [creating, setCreating] = useState<{ stageId?: string } | null>(null);
  const navigate = useNavigate();

  return (
    <div className="flex h-full min-h-0 flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        {canWrite && <Button onClick={() => setCreating({})}><Plus className="mr-1 h-4 w-4" />{tx('New')}</Button>}
        <div className="flex items-center gap-1.5">
          <h1 className="text-xl font-bold tracking-tight">{tx('Pipeline')}</h1>
          {canConfigure && (
            <Link to="/crm/settings" title={tx('Configure stages')} className="rounded p-1 text-muted-foreground hover:bg-muted hover:text-foreground"><Settings2 className="h-4 w-4" /></Link>
          )}
        </div>
        <div className="order-last flex w-full min-w-0 sm:order-none sm:w-auto sm:flex-1 sm:justify-center">
          <div className="w-full max-w-2xl"><CrmFilterBar scope="pipeline" state={state} groupByOptions={GROUPS} /></div>
        </div>
        <div className="ml-auto flex rounded-md border bg-background p-0.5">
          <button title={tx('Kanban')} onClick={() => state.set({ view: 'kanban', groupBy: null })} className={cn('rounded p-1.5', view === 'kanban' ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-muted')}><KanbanSquare className="h-4 w-4" /></button>
          <button title={tx('List')} onClick={() => state.set({ view: 'list' })} className={cn('rounded p-1.5', view === 'list' ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-muted')}><List className="h-4 w-4" /></button>
        </div>
      </div>
      {view === 'kanban'
        ? <Kanban filters={filters} canWrite={canWrite} onQuickCreate={(stageId) => setCreating({ stageId })} onActivityFilter={(a) => state.set({ activityState: filters.activityState === a ? null : a })} />
        : groupBy
          ? <GroupedTable groupBy={groupBy} filters={filters} />
          : <ListTable filters={filters} page={state.page} setPage={state.setPage} />}
      <OpportunityDialog open={!!creating} onOpenChange={(o) => !o && setCreating(null)} kind="opportunity" defaults={creating ?? undefined}
        onSaved={(o) => navigate(`/crm/opportunities/${o.id}`)} />
    </div>
  );
}

// ------------------------------------------------------------------ kanban

function Kanban({ filters, canWrite, onQuickCreate, onActivityFilter }: {
  filters: FilterState; canWrite: boolean; onQuickCreate: (stageId: string) => void; onActivityFilter: (a: ActivityState) => void;
}) {
  const { data, isLoading, isError } = useKanban(filters as Record<string, string>);
  const move = useCrmMutation(crmApi.move);
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 6 } }));
  const [scheduleFor, setScheduleFor] = useState<string | null>(null);

  const onDragEnd = (e: DragEndEvent) => {
    const id = String(e.active.id);
    const over = e.over?.id ? String(e.over.id) : null;
    if (!over) return;
    const stageId = over.startsWith('col:') ? over.slice(4) : data?.columns.find((c) => c.items.some((i) => i.id === over))?.stage.id;
    if (!stageId) return;
    const col = data?.columns.find((c) => c.stage.id === stageId);
    const index = over.startsWith('col:') ? col?.items.length : col?.items.findIndex((i) => i.id === over);
    const card = data?.columns.flatMap((c) => c.items).find((i) => i.id === id);
    if (card?.stageId === stageId && over.startsWith('col:')) return;
    move.mutate({ id, stageId, index: index ?? undefined }, {
      onSuccess: (o) => { if (o.status === 'won' && card?.status !== 'won') toast.success(tx('Won! 🎉')); },
      onError: (err) => toast.error(apiErrorMessage(err, tx('Could not move'))),
    });
  };

  if (isLoading) return <div className="flex gap-3">{[0, 1, 2, 3].map((i) => <Skeleton key={i} className="h-96 w-72 shrink-0" />)}</div>;
  if (isError || !data) return <p className="text-sm text-muted-foreground">{tx('Could not load the pipeline.')}</p>;

  return (
    <DndContext sensors={sensors} collisionDetection={closestCorners} onDragEnd={onDragEnd}>
      <div className="flex min-h-0 flex-1 gap-3 overflow-x-auto pb-3">
        {data.columns.map((col) => (
          <Column key={col.stage.id} col={col} canWrite={canWrite} onQuickCreate={onQuickCreate} onActivityFilter={onActivityFilter} onSchedule={setScheduleFor} />
        ))}
        {data.columns.length === 0 && <p className="text-sm text-muted-foreground">{tx('No stages configured.')}</p>}
      </div>
      <ScheduleDialog open={!!scheduleFor} onOpenChange={(o) => !o && setScheduleFor(null)} dealId={scheduleFor ?? undefined} />
    </DndContext>
  );
}

function Column({ col, canWrite, onQuickCreate, onActivityFilter, onSchedule }: {
  col: KanbanColumn; canWrite: boolean; onQuickCreate: (stageId: string) => void; onActivityFilter: (a: ActivityState) => void; onSchedule: (id: string) => void;
}) {
  const { setNodeRef, isOver } = useDroppable({ id: `col:${col.stage.id}` });
  const [folded, setFolded] = useState(col.stage.fold);
  const currency = col.items[0]?.currencyCode;
  if (folded) {
    return (
      <div ref={setNodeRef} onClick={() => setFolded(false)}
        className={cn('flex w-10 shrink-0 cursor-pointer flex-col items-center gap-2 rounded-xl border bg-muted/30 py-3', isOver && 'ring-2 ring-primary/40')}>
        <ChevronRight className="h-4 w-4 text-muted-foreground" />
        <span className="text-xs font-semibold [writing-mode:vertical-rl]">{col.stage.name} ({col.count})</span>
      </div>
    );
  }
  return (
    <div ref={setNodeRef} className={cn('flex w-[290px] shrink-0 flex-col rounded-xl bg-muted/30 transition-shadow', isOver && 'ring-2 ring-primary/40')}>
      <div className="space-y-1.5 px-2.5 pb-2 pt-2.5">
        <div className="flex items-center justify-between gap-2">
          <button className="flex min-w-0 items-center gap-1.5" onClick={() => setFolded(true)} title={col.stage.requirements ?? tx('Fold')}>
            <span className={cn('h-2 w-2 shrink-0 rounded-full', colorOf(col.stage.color).dot)} />
            <span className="truncate text-sm font-semibold">{col.stage.name}</span>
            <span className="text-xs text-muted-foreground">({col.count})</span>
          </button>
          {canWrite && (
            <button className="rounded p-1 text-muted-foreground hover:bg-background hover:text-foreground" title={tx('Quick create')} onClick={() => onQuickCreate(col.stage.id)}>
              <Plus className="h-4 w-4" />
            </button>
          )}
        </div>
        <div className="flex items-center gap-2">
          <div className="flex-1 cursor-pointer" onClick={(e) => {
            const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
            const x = (e.clientX - rect.left) / rect.width;
            const order: ActivityState[] = ['planned', 'today', 'overdue', 'none'];
            let acc = 0;
            for (const a of order) { acc += (col.activity[a] ?? 0) / Math.max(1, col.count); if (x <= acc) { onActivityFilter(a); break; } }
          }}>
            <ActivityBar counts={col.activity} total={col.count} />
          </div>
          <span className="text-sm font-bold tabular-nums">{compact(col.amount)}{currency ? '' : ''}</span>
        </div>
      </div>
      <div className="flex min-h-[120px] flex-1 flex-col gap-2 overflow-y-auto px-2 pb-2">
        {col.items.map((o) => <Card key={o.id} o={o} onSchedule={onSchedule} canWrite={canWrite} />)}
        {col.count > col.items.length && <p className="py-1 text-center text-xs text-muted-foreground">+{col.count - col.items.length} {tx('more — use the list view')}</p>}
      </div>
    </div>
  );
}

function Card({ o, onSchedule, canWrite }: { o: Opportunity; onSchedule: (id: string) => void; canWrite: boolean }) {
  const navigate = useNavigate();
  const { data: lk } = useCrmLookups();
  const { attributes, listeners, setNodeRef, transform, isDragging } = useDraggable({ id: o.id, disabled: !canWrite });
  const { setNodeRef: dropRef } = useDroppable({ id: o.id });
  const prio = useCrmMutation(crmApi.update);
  const style = transform ? { transform: `translate3d(${transform.x}px, ${transform.y}px, 0)`, zIndex: 30 } : undefined;
  return (
    <div ref={(n) => { setNodeRef(n); dropRef(n); }} style={style} {...listeners} {...attributes}
      onClick={() => navigate(`/crm/opportunities/${o.id}`)}
      className={cn('group cursor-pointer rounded-lg border bg-card p-3 shadow-sm transition-shadow hover:shadow-md',
        isDragging && 'opacity-70 shadow-lg ring-2 ring-primary/40', o.status === 'won' && 'border-l-4 border-l-emerald-500')}>
      <p className="text-sm font-semibold leading-snug">{o.name}</p>
      <p className="mt-0.5 text-sm tabular-nums text-foreground/80">{money(o.amount, o.currencyCode)}</p>
      {(o.partnerName || o.companyName || o.contactName) && (
        <p className="truncate text-xs text-muted-foreground">{o.partnerName ?? o.companyName ?? o.contactName}</p>
      )}
      <div className="mt-1.5"><TagChips ids={o.tagIds} tags={lk?.tags ?? []} /></div>
      <div className="mt-2 flex items-center justify-between">
        <div className="flex items-center gap-1.5">
          <Stars value={o.priority} onChange={canWrite ? (p) => prio.mutate({ id: o.id, priority: p }) : undefined} />
          <button type="button" onPointerDown={(e) => e.stopPropagation()} onClick={(e) => { e.stopPropagation(); onSchedule(o.id); }}
            title={o.nextActivity ? `${o.nextActivity.title} — ${date(o.nextActivity.dueAt)}` : tx('Schedule an activity')}>
            <ActivityBadge state={o.activityState} type={o.nextActivity?.type} title={o.nextActivity?.title} />
          </button>
        </div>
        <div className="flex items-center gap-1.5">
          {o.rotting && <span title={tx('No stage change for') + ` ${o.daysInStage} ` + tx('days')} className="rounded-full bg-rose-100 px-1.5 text-[11px] font-semibold text-rose-700 dark:bg-rose-500/20 dark:text-rose-300">{o.daysInStage}d</span>}
          <Avatar name={o.ownerName} />
        </div>
      </div>
    </div>
  );
}

// ------------------------------------------------------------------ list / grouped

const COLS = 'grid-cols-[minmax(150px,2fr)_minmax(120px,1.4fr)_minmax(110px,1fr)_minmax(100px,1fr)_100px_110px_80px]';

function Row({ o }: { o: Opportunity }) {
  const navigate = useNavigate();
  return (
    <button onClick={() => navigate(`/crm/opportunities/${o.id}`)} className={cn('grid w-full items-center gap-3 border-b px-3 py-2 text-left text-sm hover:bg-muted/40', COLS)}>
      <span className="truncate font-medium">{o.name}<span className="block text-xs font-normal text-muted-foreground">{date(o.createdAt)}</span></span>
      <span className="truncate">{o.partnerName ?? o.companyName ?? '—'}<span className="block truncate text-xs text-muted-foreground">{o.contactName ?? o.email ?? ''}</span></span>
      <span className="truncate">{o.ownerName ?? tx('Unassigned')}</span>
      <span className="truncate">{o.stageName ?? '—'}</span>
      <span><DealStatusPill status={o.status} /></span>
      <span className="text-right tabular-nums">{money(o.amount, o.currencyCode)}</span>
      <span className="text-right tabular-nums text-muted-foreground">{Math.round(o.probability)}%</span>
    </button>
  );
}

function Header() {
  return (
    <div className={cn('grid gap-3 border-b bg-muted/40 px-3 py-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground', COLS)}>
      <span>{tx('Opportunity')}</span><span>{tx('Customer')}</span><span>{tx('Salesperson')}</span><span>{tx('Stage')}</span>
      <span>{tx('Status')}</span><span className="text-right">{tx('Expected revenue')}</span><span className="text-right">{tx('Prob.')}</span>
    </div>
  );
}

function ListTable({ filters, page, setPage }: { filters: FilterState; page: number; setPage: (p: number) => void }) {
  const { data, isLoading } = useOpportunities({ ...filters, page, pageSize: 50 } as Record<string, string | number>);
  return (
    <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
      <div className="min-w-[860px]">
        <Header />
        {isLoading && <div className="p-3"><Skeleton className="h-40 w-full" /></div>}
        {data?.data.map((o) => <Row key={o.id} o={o} />)}
        {data && data.data.length === 0 && <p className="p-6 text-center text-sm text-muted-foreground">{tx('No opportunities match these filters.')}</p>}
        {data && (
          <div className="flex items-center justify-between border-t px-3 py-2 text-sm">
            <span className="text-muted-foreground">{data.meta.total} {tx('records')}</span>
            <span className="font-semibold tabular-nums">{money(data.totals?.amount ?? 0)}</span>
          </div>
        )}
      </div>
      {data && data.meta.totalPages > 1 && (
        <DataTablePagination page={data.meta.page} pageSize={data.meta.pageSize} total={data.meta.total} totalPages={data.meta.totalPages} onPageChange={setPage} />
      )}
    </div>
  );
}

/** Filter that selects one group's rows. */
export function groupFilter(groupBy: string, key: string | null): Record<string, string> | null {
  const k = key ?? '';
  const month = (prefix: 'created' | 'close') => {
    if (!k) return null;
    const [y, m] = k.split('-').map(Number);
    const last = new Date(y, m, 0).getDate();
    return { [`${prefix}From`]: `${k}-01`, [`${prefix}To`]: `${k}-${String(last).padStart(2, '0')}` };
  };
  switch (groupBy) {
    case 'stage': return k ? { stageId: k } : null;
    case 'owner': return k ? { ownerId: k } : { unassigned: 'true' };
    case 'team': return k ? { teamId: k } : null;
    case 'source': return k ? { sourceId: k } : null;
    case 'tag': return k ? { tagId: k } : null;
    case 'lostReason': return k ? { lostReasonId: k, status: 'lost' } : null;
    case 'partner': return k ? { partnerId: k } : null;
    case 'city': return k ? { city: k } : null;
    case 'country': return k ? { country: k } : null;
    case 'status': return k ? { status: k } : null;
    case 'createdMonth': return month('created');
    case 'closeMonth': return month('close');
    default: return null;
  }
}

function GroupedTable({ groupBy, filters }: { groupBy: string; filters: FilterState }) {
  const { data, isLoading } = useGrouped(groupBy, filters as Record<string, string>);
  const total = useMemo(() => (data ?? []).reduce((a, g) => a + Number(g.amount), 0), [data]);
  return (
    <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
      <div className="min-w-[860px]">
        <Header />
        {isLoading && <div className="p-3"><Skeleton className="h-40 w-full" /></div>}
        {(data ?? []).map((g) => <Group key={String(g.key)} g={g} groupBy={groupBy} filters={filters} />)}
        {data && data.length === 0 && <p className="p-6 text-center text-sm text-muted-foreground">{tx('No opportunities match these filters.')}</p>}
        <div className="flex justify-end border-t px-3 py-2 text-sm font-bold tabular-nums">{money(total)}</div>
      </div>
    </div>
  );
}

function Group({ g, groupBy, filters }: { g: GroupRow; groupBy: string; filters: FilterState }) {
  const [open, setOpen] = useState(false);
  const sub = groupFilter(groupBy, g.key);
  const { data } = useOpportunities({ ...filters, ...(sub ?? {}), pageSize: 100 } as Record<string, string | number>, '/crm/opportunities', open && !!sub);
  const label = groupBy === 'priority' ? '★'.repeat(Number(g.label ?? 0)) || tx('No priority') : g.label ?? tx('None');
  return (
    <>
      <button onClick={() => setOpen((x) => !x)} className="flex w-full items-center justify-between border-b bg-muted/20 px-3 py-2 text-left text-sm font-semibold hover:bg-muted/50">
        <span className="flex items-center gap-1.5">{open ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}{label} ({g.count})</span>
        <span className="flex gap-6 tabular-nums"><span className="text-xs font-normal text-muted-foreground">{tx('Weighted')} {money(g.weighted)}</span>{money(g.amount)}</span>
      </button>
      {open && !sub && <p className="border-b px-8 py-2 text-xs text-muted-foreground">{tx('Filter by this value to see the records.')}</p>}
      {open && data?.data.map((o) => <Row key={o.id} o={o} />)}
    </>
  );
}
