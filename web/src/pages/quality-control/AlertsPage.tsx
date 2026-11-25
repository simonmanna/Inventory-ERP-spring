import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import {
  DndContext, DragOverlay, PointerSensor, closestCorners, useDraggable, useDroppable, useSensor, useSensors,
  type DragEndEvent, type DragStartEvent,
} from '@dnd-kit/core';
import { BellRing, ChevronRight, KanbanSquare, List, Plus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { DataTable, type Column as TableColumn } from '@/components/data-table';
import { DataTablePagination, FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput, useListState } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import {
  PRIORITY_LABEL, useAlertKanban, useAlerts, useMoveAlert, useQcTeams, useTags, type QcAlert, type QcKanban,
} from '@/features/quality-control/api';
import { AlertDialog, PriorityStars, TagChips } from './alert-ui';

const FILTERS = { teamId: '', priority: '', tagId: '', view: 'kanban' };

/** Quality alerts (ADR-S087 §8): a kanban of stages (drag a card to move it) or a list. */
export function AlertsPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const canWrite = has(PERMISSIONS.qualityControl.alert);
  const list = useListState(FILTERS);
  const { teamId, priority, tagId, view } = list.filters;
  const [creating, setCreating] = useState<{ stageId?: string } | null>(null);
  const teams = useQcTeams();
  const tags = useTags();
  const filters = {
    search: list.search || undefined, teamId: teamId || undefined, priority: priority === '' ? undefined : Number(priority),
    tagId: tagId || undefined,
  };
  return (
    <div className="flex h-full flex-col gap-4">
      <ListPageHeader icon={BellRing} title={tx('Quality Alerts')}
        description={tx('Quality problems from failed checks, the shop floor or receipts, followed through to their fix.')}
        actions={
          <div className="flex gap-2">
            <div className="flex rounded-md border">
              <Button variant={view === 'kanban' ? 'secondary' : 'ghost'} size="sm" onClick={() => list.setFilter('view', 'kanban')} aria-label={tx('Kanban')}><KanbanSquare className="h-4 w-4" /></Button>
              <Button variant={view === 'list' ? 'secondary' : 'ghost'} size="sm" onClick={() => list.setFilter('view', 'list')} aria-label={tx('List')}><List className="h-4 w-4" /></Button>
            </div>
            {canWrite && <Button onClick={() => setCreating({})}><Plus className="mr-1.5 h-4 w-4" /> {tx('New alert')}</Button>}
          </div>
        } />
      <ListToolbar>
        <SearchInput value={list.searchInput} onChange={list.setSearchInput} placeholder={tx('Search number, title, batch, product…')} />
        <FilterSelect value={teamId} onChange={(v) => list.setFilter('teamId', v)} allLabel={tx('All teams')}
          options={(teams.data ?? []).map((t) => ({ value: t.id, label: t.name }))} />
        <FilterSelect value={priority} onChange={(v) => list.setFilter('priority', v)} allLabel={tx('Any priority')}
          options={PRIORITY_LABEL.map((l, i) => ({ value: String(i), label: tx(l) }))} />
        {!!tags.data?.length && (
          <FilterSelect value={tagId} onChange={(v) => list.setFilter('tagId', v)} allLabel={tx('Any tag')}
            options={tags.data.map((t) => ({ value: t.id, label: t.name }))} />
        )}
      </ListToolbar>
      {view === 'list' ? <AlertList filters={filters} list={list} /> : <Kanban filters={filters} canWrite={canWrite} onQuickCreate={(stageId) => setCreating({ stageId })} />}
      {creating && <AlertDialog stageId={creating.stageId} onClose={() => setCreating(null)} />}
    </div>
  );
}

function AlertList({ filters, list }: { filters: Record<string, unknown>; list: { page: number; pageSize: number; setPage: (n: number) => void; setPageSize: (n: number) => void } }) {
  const navigate = useNavigate();
  const { data, isLoading, isError } = useAlerts({ page: list.page, pageSize: list.pageSize, ...filters });
  const columns: TableColumn<QcAlert>[] = [
    { key: 'number', header: tx('Number'), render: (a) => <span className="font-medium text-primary">{a.alertNumber}</span> },
    { key: 'title', header: tx('Alert'), render: (a) => <div><div className="line-clamp-1 max-w-[18rem]">{a.title}</div><TagChips tags={a.tags} /></div> },
    { key: 'priority', header: tx('Priority'), render: (a) => <PriorityStars value={a.priority} /> },
    { key: 'product', header: tx('Product'), render: (a) => <div><div>{a.productName ?? '—'}</div><div className="text-xs text-muted-foreground">{a.batchNumber ?? ''}</div></div> },
    { key: 'team', header: tx('Team'), render: (a) => <div><div>{a.teamName ?? '—'}</div><div className="text-xs text-muted-foreground">{a.responsibleName || ''}</div></div> },
    { key: 'stage', header: tx('Stage'), render: (a) => <span className={cn(a.stageClosed && 'text-muted-foreground')}>{a.stageName}</span> },
    { key: 'ncr', header: tx('NCR'), render: (a) => a.ncrNumber ?? '—' },
    { key: 'created', header: tx('Raised'), render: (a) => <span className="whitespace-nowrap">{dateTime(a.createdAt)}</span> },
  ];
  return (
    <ListCard>
      <DataTable columns={columns} data={data?.data ?? []} loading={isLoading} getRowId={(a) => a.id}
        onRowClick={(a) => navigate(`/quality-control/alerts/${a.id}`)} cellClassName="py-2.5 px-4" headerRowClassName="h-10"
        emptyMessage={isError ? tx('Could not load the alerts.') : tx('No quality alerts.')} />
      {data?.meta && (
        <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total} totalPages={data.meta.totalPages}
          onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="alert" />
      )}
    </ListCard>
  );
}

function Kanban({ filters, canWrite, onQuickCreate }: { filters: Record<string, unknown>; canWrite: boolean; onQuickCreate: (stageId: string) => void }) {
  const { data, isLoading, isError } = useAlertKanban(filters);
  const qc = useQueryClient();
  const move = useMoveAlert();
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 6 } }));
  const [dragging, setDragging] = useState<QcAlert | null>(null);
  const all = data?.columns.flatMap((c) => c.alerts) ?? [];

  const onDragStart = (e: DragStartEvent) => setDragging(all.find((a) => a.id === String(e.active.id)) ?? null);
  const onDragEnd = (e: DragEndEvent) => {
    setDragging(null);
    const id = String(e.active.id);
    const over = e.over?.id ? String(e.over.id) : null;
    if (!over || !data) return;
    const stageId = over.startsWith('col:') ? over.slice(4) : data.columns.find((c) => c.alerts.some((a) => a.id === over))?.stage.id;
    const card = all.find((a) => a.id === id);
    if (!stageId || !card || card.stageId === stageId) return;
    // Optimistic: the card lands in its new column at once; an error restores the board.
    const key = ['quality-control', 'alerts', 'kanban', filters];
    const before = qc.getQueryData<QcKanban>(key);
    if (before) qc.setQueryData<QcKanban>(key, moveCard(before, card, stageId));
    move.mutate({ id, stageId }, {
      onError: (err) => {
        if (before) qc.setQueryData(key, before);
        notify.error(apiMessage(err, tx('Could not move the alert')));
      },
    });
  };

  if (isLoading) {
    return (
      <div className="flex gap-3 overflow-hidden">
        {[0, 1, 2, 3].map((i) => <div key={i} className="w-[280px] shrink-0 space-y-2 rounded-2xl bg-muted/30 p-2.5"><Skeleton className="h-5 w-32" />{[0, 1].map((j) => <Skeleton key={j} className="h-20 w-full rounded-xl" />)}</div>)}
      </div>
    );
  }
  if (isError || !data) return <p className="p-8 text-center text-muted-foreground">{tx('Could not load the alerts.')}</p>;
  return (
    <DndContext sensors={sensors} collisionDetection={closestCorners} onDragStart={onDragStart} onDragEnd={onDragEnd} onDragCancel={() => setDragging(null)}>
      <div className="flex min-h-0 flex-1 gap-3 overflow-x-auto pb-3">
        {data.columns.map((col) => <StageColumn key={col.stage.id} col={col} canWrite={canWrite} onQuickCreate={onQuickCreate} />)}
      </div>
      <DragOverlay dropAnimation={{ duration: 200, easing: 'cubic-bezier(0.23, 1, 0.32, 1)' }}>
        {dragging && <CardBody a={dragging} overlay />}
      </DragOverlay>
    </DndContext>
  );
}

function moveCard(board: QcKanban, card: QcAlert, stageId: string): QcKanban {
  return {
    columns: board.columns.map((c) => {
      const had = c.alerts.some((a) => a.id === card.id);
      let alerts = c.alerts.filter((a) => a.id !== card.id);
      let count = had ? c.count - 1 : c.count;
      if (c.stage.id === stageId) {
        alerts = [{ ...card, stageId, stageName: c.stage.name, stageClosed: c.stage.isClosed }, ...alerts];
        count += 1;
      }
      return { ...c, alerts, count };
    }),
  };
}

function StageColumn({ col, canWrite, onQuickCreate }: { col: QcKanban['columns'][number]; canWrite: boolean; onQuickCreate: (stageId: string) => void }) {
  const { setNodeRef, isOver } = useDroppable({ id: `col:${col.stage.id}` });
  const [folded, setFolded] = useState(col.stage.folded);
  if (folded) {
    return (
      <div ref={setNodeRef} onClick={() => setFolded(false)} title={tx('Unfold')}
        className={cn('flex w-11 shrink-0 cursor-pointer flex-col items-center gap-2 rounded-2xl border bg-muted/30 py-3 hover:bg-muted/60', isOver && 'ring-2 ring-primary/40')}>
        <ChevronRight className="h-4 w-4 text-muted-foreground" />
        <span className="text-xs font-semibold [writing-mode:vertical-rl]">{col.stage.name} · {col.count}</span>
      </div>
    );
  }
  return (
    <div ref={setNodeRef} className={cn('flex w-[280px] shrink-0 flex-col rounded-2xl bg-muted/30', isOver && 'bg-primary/[0.06] ring-2 ring-primary/40')}>
      <div className="flex items-center justify-between gap-2 px-3 pb-2 pt-3">
        <button className="flex min-w-0 items-center gap-2" onClick={() => setFolded(true)} title={tx('Fold')}>
          <span className="truncate text-sm font-semibold">{col.stage.name}</span>
          <span className="rounded-full bg-background px-1.5 py-0.5 text-[11px] font-medium tabular-nums text-muted-foreground shadow-sm">{col.count}</span>
          {col.stage.isClosed && <span className="text-[11px] text-muted-foreground">{tx('closed')}</span>}
        </button>
        {canWrite && (
          <button className="rounded-lg p-1 text-muted-foreground hover:bg-background hover:text-foreground" title={tx('Quick create')} onClick={() => onQuickCreate(col.stage.id)}>
            <Plus className="h-4 w-4" />
          </button>
        )}
      </div>
      <div className="flex min-h-[120px] flex-1 flex-col gap-2 overflow-y-auto px-2 pb-2">
        {col.alerts.map((a) => <Card key={a.id} a={a} canWrite={canWrite} />)}
        {col.alerts.length === 0 && (
          <div className={cn('flex flex-1 items-center justify-center rounded-xl border-2 border-dashed px-3 py-6 text-center text-xs text-muted-foreground', isOver ? 'border-primary/50 text-primary' : 'border-muted-foreground/15')}>
            {canWrite ? tx('Drop an alert here') : tx('Nothing in this stage')}
          </div>
        )}
      </div>
    </div>
  );
}

function Card({ a, canWrite }: { a: QcAlert; canWrite: boolean }) {
  const navigate = useNavigate();
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({ id: a.id, disabled: !canWrite });
  const { setNodeRef: dropRef } = useDroppable({ id: a.id });
  return (
    <div ref={(n) => { setNodeRef(n); dropRef(n); }} {...listeners} {...attributes}
      onClick={() => navigate(`/quality-control/alerts/${a.id}`)} className={cn(isDragging && 'opacity-30')}>
      <CardBody a={a} />
    </div>
  );
}

function CardBody({ a, overlay }: { a: QcAlert; overlay?: boolean }) {
  return (
    <div className={cn('cursor-pointer space-y-1.5 rounded-xl border bg-card p-3 shadow-sm',
      !overlay && 'hover:border-primary/30 hover:shadow-md', overlay && 'rotate-[2deg] cursor-grabbing shadow-2xl ring-1 ring-primary/30',
      a.priority >= 3 && 'border-l-4 border-l-destructive')}>
      <div className="flex items-start justify-between gap-2">
        <span className="line-clamp-2 text-sm font-medium">{a.title}</span>
        <PriorityStars value={a.priority} />
      </div>
      <div className="text-xs text-muted-foreground">{a.alertNumber}{a.productName ? ` · ${a.productName}` : ''}{a.batchNumber ? ` · ${a.batchNumber}` : ''}</div>
      <TagChips tags={a.tags} />
      <div className="flex items-center justify-between text-xs text-muted-foreground">
        <span>{a.responsibleName || a.teamName || tx('Unassigned')}</span>
        <span>{a.origin === 'auto' ? tx('From check') : ''}{a.ncrNumber ? ` · ${a.ncrNumber}` : ''}</span>
      </div>
    </div>
  );
}
