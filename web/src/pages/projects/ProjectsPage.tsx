import { useState } from 'react';
import { Link } from 'react-router-dom';
import { CalendarDays, Clock, FolderKanban, Lock, Milestone, Plus, Star, Users } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { ListPageHeader } from '@/components/list/list-page';
import { ListToolbar, SearchInput, FilterSelect } from '@/components/list/list-toolbar';
import { useAuthStore } from '@/stores/auth.store';
import { PERMISSIONS } from '@erp/shared';
import { useDebouncedValue } from '@/lib/use-debounced-value';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { num, usePmMutation, useProjectBoard, type ProjectCard } from '@/features/projects/api';
import { ProjectDialog } from '@/features/projects/project-dialog';
import {
  EmptyState, PROJECT_STATUS, ProgressBar, ProjectStatusPill, UpdateStatusPill, hours, pmColor, shortDate,
} from '@/features/projects/ui';

const FILTERS = [
  { value: 'all', label: tx('All projects') },
  { value: 'mine', label: tx('My projects') },
  { value: 'favorites', label: tx('Favourites') },
];

/** Projects board: one card per project the user can see, with progress, people and the next milestone. */
export function ProjectsPage() {
  const canManage = useAuthStore((s) => s.hasPermission)(PERMISSIONS.project.manage);
  const [filter, setFilter] = useState('all');
  const [status, setStatus] = useState('open');
  const [templates, setTemplates] = useState(false);
  const [search, setSearch] = useState('');
  const q = useDebouncedValue(search, 250);
  const [creating, setCreating] = useState(false);
  const { data = [], isLoading } = useProjectBoard({ filter, status, search: q, templates });

  return (
    <div className="space-y-4">
      <ListPageHeader
        title={templates ? tx('Project templates') : tx('Projects')}
        description={tx('Plan work, track tasks and time, and keep everyone on the same page.')}
        icon={FolderKanban}
        actions={canManage && (
          <Button onClick={() => setCreating(true)}><Plus className="mr-1 h-4 w-4" /> {tx('New project')}</Button>
        )}
      />
      <ListToolbar>
        <div className="flex rounded-lg border bg-muted/40 p-0.5">
          {FILTERS.map((f) => (
            <button
              key={f.value}
              type="button"
              onClick={() => setFilter(f.value)}
              className={cn('rounded-md px-3 py-1 text-sm', filter === f.value ? 'bg-background font-medium shadow-sm' : 'text-muted-foreground hover:text-foreground')}
            >
              {f.label}
            </button>
          ))}
        </div>
        <SearchInput value={search} onChange={setSearch} placeholder={tx('Search code, name or customer…')} />
        <FilterSelect
          value={status}
          onChange={setStatus}
          allLabel={tx('Any status')}
          options={[{ value: 'open', label: tx('Open') }, ...Object.entries(PROJECT_STATUS).map(([value, s]) => ({ value, label: s.label }))]}
        />
        <label className="ml-auto flex items-center gap-2 text-sm text-muted-foreground">
          <input type="checkbox" className="h-4 w-4 accent-primary" checked={templates} onChange={(e) => setTemplates(e.target.checked)} />
          {tx('Templates')}
        </label>
      </ListToolbar>

      {isLoading ? (
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {Array.from({ length: 6 }).map((_, i) => <Skeleton key={i} className="h-48 rounded-xl" />)}
        </div>
      ) : data.length === 0 ? (
        <EmptyState
          icon={FolderKanban}
          title={templates ? tx('No templates yet') : tx('No projects match')}
          hint={tx('Create a project, or tick "Save as a template" to build a reusable plan.')}
          action={canManage && <Button onClick={() => setCreating(true)}><Plus className="mr-1 h-4 w-4" />{tx('New project')}</Button>}
        />
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {data.map((p) => <ProjectTile key={p.id} p={p} />)}
        </div>
      )}
      <ProjectDialog open={creating} onClose={() => setCreating(false)} />
    </div>
  );
}

function ProjectTile({ p }: { p: ProjectCard }) {
  const fav = usePmMutation(() => ({ method: 'post', url: `/projects/${p.id}/favorite` }));
  const c = pmColor(p.color);
  const planned = num(p.plannedHours);
  const spent = num(p.effectiveHours);
  return (
    <div className={cn('group relative flex flex-col rounded-xl border border-l-4 bg-card p-4 shadow-sm transition hover:shadow-md', c.border)}>
      <button
        type="button"
        onClick={() => fav.mutate()}
        className={cn('absolute right-3 top-3', p.isFavorite ? 'text-amber-400' : 'text-muted-foreground/40 hover:text-amber-400')}
        aria-label={p.isFavorite ? tx('Remove from favourites') : tx('Add to favourites')}
      >
        <Star className={cn('h-4 w-4', p.isFavorite && 'fill-current')} />
      </button>
      <Link to={`/projects/${p.id}`} className="flex flex-1 flex-col gap-3">
        <div className="pr-6">
          <div className="flex items-center gap-2 text-xs text-muted-foreground">
            <span className="font-mono">{p.code}</span>
            {p.privacy === 'members' && <Lock className="h-3 w-3" aria-label={tx('Members only')} />}
            <ProjectStatusPill status={p.status} />
            <UpdateStatusPill status={p.lastUpdateStatus} />
          </div>
          <h3 className="mt-1 line-clamp-2 font-semibold leading-snug group-hover:text-primary">{p.name}</h3>
          {p.customerName && <p className="text-xs text-muted-foreground">{p.customerName}</p>}
        </div>

        <div>
          <div className="mb-1 flex justify-between text-xs text-muted-foreground">
            <span>{tx('{{done}} of {{total}} tasks done', { done: p.taskCount - p.openTaskCount, total: p.taskCount })}</span>
            <span className="font-medium text-foreground">{p.progress}%</span>
          </div>
          <ProgressBar value={p.progress} />
        </div>

        <div className="mt-auto flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
          {p.overdueTaskCount > 0 && <span className="font-medium text-rose-600">{tx('{{count}} overdue', { count: p.overdueTaskCount })}</span>}
          {p.myOpenTaskCount > 0 && <span className="font-medium text-primary">{tx('{{count}} for me', { count: p.myOpenTaskCount })}</span>}
          <span className="flex items-center gap-1"><Users className="h-3 w-3" />{p.memberCount}</span>
          {(planned > 0 || spent > 0) && (
            <span className={cn('flex items-center gap-1', planned > 0 && spent > planned && 'text-rose-600')}>
              <Clock className="h-3 w-3" />{planned > 0 ? `${hours(spent, false)}/${hours(planned)}` : hours(spent)}
            </span>
          )}
          {p.endDate && <span className="flex items-center gap-1"><CalendarDays className="h-3 w-3" />{shortDate(p.endDate)}</span>}
        </div>
        {p.nextMilestone && (
          <div className="flex items-center gap-1.5 rounded-md bg-muted/50 px-2 py-1 text-xs">
            <Milestone className="h-3 w-3 text-violet-500" />
            <span className="truncate">{p.nextMilestone.name}</span>
            {p.nextMilestone.deadline && <span className="ml-auto text-muted-foreground">{shortDate(p.nextMilestone.deadline)}</span>}
          </div>
        )}
        {p.managerName && <p className="text-xs text-muted-foreground">{tx('Manager')}: <span className="text-foreground">{p.managerName}</span></p>}
      </Link>
    </div>
  );
}
