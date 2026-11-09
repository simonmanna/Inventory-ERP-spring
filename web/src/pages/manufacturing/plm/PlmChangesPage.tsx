import { useMemo, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import {
  DndContext, DragOverlay, PointerSensor, closestCorners, useDraggable, useDroppable, useSensor, useSensors,
  type DragEndEvent, type DragStartEvent,
} from '@dnd-kit/core';
import { CalendarClock, GitPullRequest, KanbanSquare, List, MoreHorizontal, Plus, ShieldCheck } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { ListCard, ListPageHeader } from '@/components/list/list-page';
import { FilterSelect, ListToolbar, SearchInput } from '@/components/list/list-toolbar';
import { useAuthStore } from '@/stores/auth.store';
import {
  useMoveChangeStage, usePlmChanges, usePlmStages, type ChangeRow, type ChangeStatus, type PlmStage,
} from '@/features/manufacturing/api-plm';
import { useDebouncedValue } from '@/lib/use-debounced-value';
import { notify } from '@/lib/notify';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import {
  Avatar, ChangeStatusPill, ChangeTypeBadge, EmptyState, PlmSubNav, PriorityPill, ProductLabel, RevisionBadge,
} from './plm-ui';
import { ChangeDialog } from './change-dialogs';
import { CHANGE_STATUS, CHANGE_TYPE, CHANGE_TYPES, day } from './vocab';

/** The stage an ECO sits in (legacy ECOs without a stage: first stage while draft, last once approved). */
function stageOf(c: ChangeRow, stages: PlmStage[]): string | undefined {
  if (c.stageId && stages.some((s) => s.id === c.stageId)) return c.stageId;
  return c.status === 'draft' ? stages[0]?.id : stages[stages.length - 1]?.id;
}

/** Engineering change orders: kanban by approval stage, or a filterable table. */
export function PlmChangesPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const view = params.get('view') === 'table' ? 'table' : 'board';
  const setView = (v: 'board' | 'table') => {
    const next = new URLSearchParams(params);
    if (v === 'table') next.set('view', 'table'); else next.delete('view');
    setParams(next, { replace: true });
  };
  const canChange = useAuthStore((s) => s.hasPermission)(PERMISSIONS.plm.change);
  const [search, setSearch] = useState('');
  const q = useDebouncedValue(search, 250);
  const [type, setType] = useState('');
  const [status, setStatus] = useState('open');
  const { data: stages = [], isLoading: stagesLoading } = usePlmStages();
  const { data = [], isLoading } = usePlmChanges({ status: view === 'board' ? 'open' : status, type, q });
  const [creating, setCreating] = useState(false);

  return (
    <div className="space-y-4">
      <ListPageHeader
        title={tx('Change orders')}
        description={tx('Engineering changes move through the approval stages; the last stage means approved and ready to apply.')}
        icon={GitPullRequest}
        actions={canChange && <Button onClick={() => setCreating(true)}><Plus className="mr-1 h-4 w-4" />{tx('New change')}</Button>}
      />
      <PlmSubNav />
      <ListToolbar>
        <div className="flex rounded-lg border bg-muted/40 p-0.5">
          {([['board', KanbanSquare, tx('Board')], ['table', List, tx('Table')]] as const).map(([v, Icon, label]) => (
            <button key={v} type="button" onClick={() => setView(v)}
              className={cn('flex items-center gap-1.5 rounded-md px-3 py-1 text-sm', view === v ? 'bg-background font-medium shadow-sm' : 'text-muted-foreground hover:text-foreground')}>
              <Icon className="h-4 w-4" />{label}
            </button>
          ))}
        </div>
        <SearchInput value={search} onChange={setSearch} placeholder={tx('Search number or title…')} />
        <FilterSelect value={type} onChange={setType} allLabel={tx('Any type')}
          options={CHANGE_TYPES.map((t) => ({ value: t, label: CHANGE_TYPE[t].label }))} />
        {view === 'table' && (
          <FilterSelect value={status} onChange={setStatus} allLabel={tx('Any status')}
            options={[{ value: 'open', label: tx('Open') }, ...(Object.keys(CHANGE_STATUS) as ChangeStatus[]).map((s) => ({ value: s, label: CHANGE_STATUS[s].label }))]} />
        )}
        <span className="ml-auto text-xs text-muted-foreground">
          {view === 'board' ? tx('{{n}} open changes', { n: data.length }) : tx('{{n}} changes', { n: data.length })}
        </span>
      </ListToolbar>

      {isLoading || stagesLoading ? (
        <div className="flex gap-3">{Array.from({ length: 3 }).map((_, i) => <Skeleton key={i} className="h-80 w-72 rounded-xl" />)}</div>
      ) : view === 'board' ? (
        <>
          <Board stages={stages} changes={data} editable={canChange} onOpen={(id) => navigate(`/manufacturing/plm/changes/${id}`)} />
          <p className="text-xs text-muted-foreground">{tx('Applied and cancelled changes are in the table view.')}</p>
        </>
      ) : data.length === 0 ? (
        <EmptyState icon={GitPullRequest} title={tx('No change orders match')}
          action={canChange && <Button onClick={() => setCreating(true)}><Plus className="mr-1 h-4 w-4" />{tx('New change')}</Button>} />
      ) : (
        <ListCard>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tx('ECO')}</TableHead>
                <TableHead>{tx('Title')}</TableHead>
                <TableHead>{tx('Type')}</TableHead>
                <TableHead>{tx('Product')}</TableHead>
                <TableHead>{tx('Stage')}</TableHead>
                <TableHead>{tx('Status')}</TableHead>
                <TableHead>{tx('Priority')}</TableHead>
                <TableHead>{tx('Owner')}</TableHead>
                <TableHead>{tx('Created')}</TableHead>
                <TableHead>{tx('Applied')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.map((c) => (
                <TableRow key={c.id} className="cursor-pointer" onClick={() => navigate(`/manufacturing/plm/changes/${c.id}`)}>
                  <TableCell className="whitespace-nowrap font-mono text-xs">{c.number}</TableCell>
                  <TableCell className="max-w-[260px] font-medium">{c.title}</TableCell>
                  <TableCell><ChangeTypeBadge type={c.type} /></TableCell>
                  <TableCell className="max-w-[200px] text-sm"><ProductLabel product={c.product} /></TableCell>
                  <TableCell className="text-sm">{c.stageName ?? stages.find((s) => s.id === stageOf(c, stages))?.name ?? '—'}</TableCell>
                  <TableCell><ChangeStatusPill status={c.status} /> {c.status === 'approved' && c.applyQueued && <span className="ml-1 rounded-full bg-amber-100 px-1.5 py-0.5 text-[10px] font-medium text-amber-900 dark:bg-amber-500/15 dark:text-amber-200">{tx('Queued')}</span>}</TableCell>
                  <TableCell><PriorityPill priority={c.priority} /></TableCell>
                  <TableCell className="text-sm">{c.ownerName ?? '—'}</TableCell>
                  <TableCell className="whitespace-nowrap text-sm text-muted-foreground">{day(c.createdAt)}</TableCell>
                  <TableCell className="whitespace-nowrap text-sm text-muted-foreground">{c.appliedAt ? day(c.appliedAt) : '—'}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </ListCard>
      )}

      <ChangeDialog open={creating} onClose={() => setCreating(false)} />
    </div>
  );
}

function Board({ stages, changes, editable, onOpen }: {
  stages: PlmStage[]; changes: ChangeRow[]; editable: boolean; onOpen: (id: string) => void;
}) {
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 6 } }));
  const [dragging, setDragging] = useState<ChangeRow | null>(null);
  const move = useMoveChangeStage();
  const byStage = useMemo(() => {
    const m = new Map<string, ChangeRow[]>();
    stages.forEach((s) => m.set(s.id, []));
    changes.forEach((c) => { const s = stageOf(c, stages); if (s && m.has(s)) m.get(s)!.push(c); });
    return m;
  }, [stages, changes]);

  const moveTo = (c: ChangeRow, stageId: string) => {
    if (stageId === stageOf(c, stages)) return;
    const target = stages.find((s) => s.id === stageId);
    move.mutate({ id: c.id, stageId }, {
      onSuccess: (eco) => notify.success(tx('{{n}} moved to {{s}}', { n: c.number, s: target?.name ?? '' })
        + (eco.status === 'approved' ? ` · ${tx('approved')}` : '')),
    });
  };
  const onDragStart = (e: DragStartEvent) => setDragging(changes.find((c) => c.id === e.active.id) ?? null);
  const onDragEnd = (e: DragEndEvent) => {
    setDragging(null);
    const c = changes.find((x) => x.id === e.active.id);
    const overId = e.over?.id as string | undefined;
    if (!c || !overId) return;
    const stageId = stages.some((s) => s.id === overId) ? overId : stageOf(changes.find((x) => x.id === overId) ?? c, stages);
    if (stageId) moveTo(c, stageId);
  };

  if (!stages.length) return <EmptyState icon={KanbanSquare} title={tx('No stages configured')} />;
  return (
    <DndContext sensors={sensors} collisionDetection={closestCorners} onDragStart={onDragStart} onDragEnd={onDragEnd}>
      <div className="flex gap-3 overflow-x-auto pb-4">
        {stages.map((s, i) => (
          <Column key={s.id} stage={s} final={i === stages.length - 1} changes={byStage.get(s.id) ?? []} editable={editable}
            stages={stages} onOpen={onOpen} onMove={moveTo} />
        ))}
      </div>
      <DragOverlay>{dragging && <Card change={dragging} overlay />}</DragOverlay>
    </DndContext>
  );
}

function Column({ stage, final, changes, editable, stages, onOpen, onMove }: {
  stage: PlmStage; final: boolean; changes: ChangeRow[]; editable: boolean; stages: PlmStage[];
  onOpen: (id: string) => void; onMove: (c: ChangeRow, stageId: string) => void;
}) {
  const { setNodeRef, isOver } = useDroppable({ id: stage.id, disabled: !editable });
  return (
    <div ref={setNodeRef} className={cn('flex w-72 shrink-0 flex-col rounded-xl border bg-muted/40 transition-colors', isOver && 'border-primary bg-primary/5')}>
      <div className="px-3 pb-2 pt-3">
        <div className="flex items-center gap-2">
          <span className={cn('h-2 w-2 rounded-full', final ? 'bg-emerald-500' : stage.requiresApproval ? 'bg-amber-500' : 'bg-sky-500')} />
          <h3 className="truncate text-sm font-semibold">{stage.name}</h3>
          <span className="rounded-full bg-background px-1.5 text-xs text-muted-foreground">{changes.length}</span>
          {stage.requiresApproval && <ShieldCheck className="ml-auto h-4 w-4 text-amber-600" aria-label={tx('Needs approval')} />}
        </div>
        {(stage.requiresApproval || final) && (
          <p className="mt-0.5 text-[11px] text-muted-foreground">
            {final ? tx('Approved, ready to apply') : (stage.approvers?.length
              ? tx('Approvers: {{names}}', { names: stage.approvers.map((a) => a.userName ?? '?').join(', ') })
              : tx('Any approver can sign off'))}
          </p>
        )}
      </div>
      <div className="flex min-h-[120px] flex-1 flex-col gap-2 px-2 pb-3">
        {changes.map((c) => <DraggableCard key={c.id} change={c} editable={editable} stages={stages} onOpen={onOpen} onMove={onMove} />)}
        {!changes.length && <p className="py-6 text-center text-xs text-muted-foreground">{tx('Nothing here')}</p>}
      </div>
    </div>
  );
}

function DraggableCard({ change, editable, stages, onOpen, onMove }: {
  change: ChangeRow; editable: boolean; stages: PlmStage[]; onOpen: (id: string) => void; onMove: (c: ChangeRow, stageId: string) => void;
}) {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({ id: change.id, disabled: !editable });
  const drop = useDroppable({ id: change.id, disabled: !editable });
  const current = stageOf(change, stages);
  return (
    <div
      ref={(n) => { setNodeRef(n); drop.setNodeRef(n); }}
      {...attributes}
      {...listeners}
      onClick={() => onOpen(change.id)}
      className={cn('relative', isDragging && 'opacity-30')}
    >
      <Card change={change} />
      {editable && (
        <div className="absolute right-1.5 top-1.5" onClick={(e) => e.stopPropagation()} onPointerDown={(e) => e.stopPropagation()}>
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <button type="button" className="rounded p-1 text-muted-foreground hover:bg-muted hover:text-foreground" aria-label={tx('Move')}>
                <MoreHorizontal className="h-4 w-4" />
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuLabel>{tx('Move to stage')}</DropdownMenuLabel>
              <DropdownMenuSeparator />
              {stages.map((s) => (
                <DropdownMenuItem key={s.id} disabled={s.id === current} onClick={() => onMove(change, s.id)}>
                  {s.name}{s.requiresApproval && <ShieldCheck className="ml-auto h-3.5 w-3.5 text-amber-600" />}
                </DropdownMenuItem>
              ))}
              <DropdownMenuSeparator />
              <DropdownMenuItem asChild><Link to={`/manufacturing/plm/changes/${change.id}`}>{tx('Open')}</Link></DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      )}
    </div>
  );
}

function Card({ change: c, overlay }: { change: ChangeRow; overlay?: boolean }) {
  const border = c.priority === 'urgent' ? 'border-l-rose-500' : c.priority === 'high' ? 'border-l-amber-500' : c.status === 'approved' ? 'border-l-emerald-500' : 'border-l-transparent';
  return (
    <div className={cn('cursor-pointer space-y-2 rounded-lg border border-l-4 bg-card p-2.5 pr-7 text-left shadow-sm transition hover:shadow-md', border, overlay && 'rotate-2 shadow-lg')}>
      <div className="flex items-center gap-1.5">
        <span className="font-mono text-[11px] text-muted-foreground">{c.number}</span>
        <ChangeTypeBadge type={c.type} />
        <PriorityPill priority={c.priority} hideNormal />
      </div>
      <p className="text-sm font-medium leading-snug">{c.title}</p>
      {c.product && <p className="truncate text-xs text-muted-foreground"><span className="font-mono">{c.product.code}</span> {c.product.name}</p>}
      <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
        {c.status === 'approved' && <ChangeStatusPill status={c.status} />} {c.status === 'approved' && c.applyQueued && <span className="ml-1 rounded-full bg-amber-100 px-1.5 py-0.5 text-[10px] font-medium text-amber-900 dark:bg-amber-500/15 dark:text-amber-200">{tx('Queued')}</span>}
        {c.newRevision && <RevisionBadge revision={c.newRevision} />}
        {c.effectivity === 'date' && c.effectiveDate && <span className="flex items-center gap-1"><CalendarClock className="h-3 w-3" />{day(String(c.effectiveDate))}</span>}
        {c.requestNumber && <span className="font-mono">{c.requestNumber}</span>}
        {c.ownerName && <span className="ml-auto flex items-center gap-1"><Avatar name={c.ownerName} className="h-5 w-5 text-[9px]" /></span>}
      </div>
    </div>
  );
}
