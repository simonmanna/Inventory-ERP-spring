import { useState } from 'react';
import { CheckCircle2, Circle, Milestone as MilestoneIcon, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { usePmMutation, type Workspace } from '@/features/projects/api';
import { EmptyState, ProgressBar, shortDate } from '@/features/projects/ui';

export function MilestonesTab({ ws }: { ws: Workspace }) {
  const [form, setForm] = useState({ name: '', deadline: '', description: '' });
  const add = usePmMutation(() => ({ method: 'post', url: `/projects/${ws.id}/milestones`, body: { name: form.name.trim(), deadline: form.deadline || undefined, description: form.description || undefined } }), { success: tx('Milestone added') });
  const update = usePmMutation((v: { id: string; body: Record<string, unknown> }) => ({ method: 'patch', url: `/projects/milestones/${v.id}`, body: v.body }));
  const remove = usePmMutation((id: string) => ({ method: 'delete', url: `/projects/milestones/${id}` }));
  const edit = ws.canEditTasks;

  return (
    <div className="space-y-4">
      {edit && (
        <form
          className="flex flex-wrap gap-2 rounded-xl border bg-card p-3 shadow-sm"
          onSubmit={(e) => { e.preventDefault(); if (form.name.trim()) { add.mutate(); setForm({ name: '', deadline: '', description: '' }); } }}
        >
          <Input className="min-w-[200px] flex-1" placeholder={tx('Milestone name, e.g. Design signed off')} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          <Input type="date" className="w-40" value={form.deadline} onChange={(e) => setForm({ ...form, deadline: e.target.value })} />
          <Input className="min-w-[200px] flex-1" placeholder={tx('Description (optional)')} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
          <Button type="submit" disabled={!form.name.trim() || add.isPending}>{tx('Add milestone')}</Button>
        </form>
      )}
      {ws.milestones.length === 0 ? (
        <EmptyState icon={MilestoneIcon} title={tx('No milestones yet')} hint={tx('Milestones mark key dates. Link tasks to a milestone to track how close it is.')} />
      ) : (
        <ol className="relative space-y-3 border-l-2 border-muted pl-6">
          {ws.milestones.map((m) => (
            <li key={m.id} className="relative rounded-xl border bg-card p-4 shadow-sm">
              <span className={cn('absolute -left-[33px] top-5 flex h-4 w-4 items-center justify-center rounded-full ring-4 ring-background',
                m.isReached ? 'bg-emerald-500' : m.late ? 'bg-rose-500' : 'bg-violet-500')} />
              <div className="flex flex-wrap items-start gap-3">
                <button type="button" disabled={!edit} onClick={() => update.mutate({ id: m.id, body: { isReached: !m.isReached } })} aria-label={tx('Toggle reached')}>
                  {m.isReached ? <CheckCircle2 className="h-5 w-5 text-emerald-600" /> : <Circle className="h-5 w-5 text-muted-foreground hover:text-emerald-600" />}
                </button>
                <div className="min-w-0 flex-1">
                  <p className={cn('font-medium', m.isReached && 'text-muted-foreground line-through')}>{m.name}</p>
                  {m.description && <p className="text-sm text-muted-foreground">{m.description}</p>}
                  {m.taskCount > 0 && (
                    <div className="mt-2 max-w-sm">
                      <div className="mb-1 flex justify-between text-xs text-muted-foreground">
                        <span>{tx('{{done}} of {{total}} tasks done', { done: m.doneCount, total: m.taskCount })}</span>
                      </div>
                      <ProgressBar value={(m.doneCount / m.taskCount) * 100} tone="bg-violet-500" />
                    </div>
                  )}
                </div>
                <div className="text-right text-sm">
                  {edit ? (
                    <Input
                      type="date"
                      className={cn('h-8 w-40', m.late && 'border-rose-400 text-rose-600')}
                      defaultValue={m.deadline ?? ''}
                      onBlur={(e) => e.target.value !== (m.deadline ?? '') && update.mutate({ id: m.id, body: { deadline: e.target.value || null } })}
                    />
                  ) : <span className={cn(m.late && 'text-rose-600')}>{shortDate(m.deadline) || '—'}</span>}
                  {m.late && <p className="text-xs font-medium text-rose-600">{tx('Late')}</p>}
                  {m.isReached && m.reachedAt && <p className="text-xs text-emerald-600">{tx('Reached')} {shortDate(m.reachedAt)}</p>}
                </div>
                {edit && (
                  <button type="button" onClick={() => window.confirm(tx('Delete this milestone? Its tasks stay.')) && remove.mutate(m.id)} aria-label={tx('Delete')}>
                    <Trash2 className="h-4 w-4 text-muted-foreground hover:text-rose-600" />
                  </button>
                )}
              </div>
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}
