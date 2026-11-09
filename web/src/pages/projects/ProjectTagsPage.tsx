import { useState } from 'react';
import { Tags, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { ListPageHeader } from '@/components/list/list-page';
import { useAuthStore } from '@/stores/auth.store';
import { PERMISSIONS } from '@erp/shared';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { usePmMutation, useTags } from '@/features/projects/api';
import { EmptyState, PM_COLORS, pmColor } from '@/features/projects/ui';

/** Shared task tags (used across all projects). */
export function ProjectTagsPage() {
  const can = useAuthStore((s) => s.hasPermission);
  const canWrite = can(PERMISSIONS.projectTask.write);
  const canDelete = can(PERMISSIONS.project.manage);
  const { data: tags = [] } = useTags();
  const [name, setName] = useState('');
  const [color, setColor] = useState(7);
  const create = usePmMutation(() => ({ method: 'post', url: '/project-tags', body: { name: name.trim(), color } }), { success: tx('Tag created') });
  const update = usePmMutation((v: { id: string; name?: string; color?: number }) => ({ method: 'patch', url: `/project-tags/${v.id}`, body: { name: v.name, color: v.color } }));
  const remove = usePmMutation((id: string) => ({ method: 'delete', url: `/project-tags/${id}` }));
  return (
    <div className="max-w-3xl space-y-4">
      <ListPageHeader title={tx('Task tags')} description={tx('Labels to categorise tasks across every project.')} icon={Tags} />
      {canWrite && (
        <form className="flex flex-wrap items-center gap-2 rounded-xl border bg-card p-3 shadow-sm" onSubmit={(e) => { e.preventDefault(); if (name.trim()) create.mutate(undefined, { onSuccess: () => setName('') }); }}>
          <Input className="w-56" placeholder={tx('New tag, e.g. Design')} value={name} onChange={(e) => setName(e.target.value)} />
          <div className="flex gap-1">
            {PM_COLORS.slice(1).map((c, i) => (
              <button key={i} type="button" onClick={() => setColor(i + 1)} className={cn('h-5 w-5 rounded-full ring-offset-2', c.bg, color === i + 1 && 'ring-2 ring-primary')} />
            ))}
          </div>
          <Button type="submit" disabled={!name.trim()}>{tx('Add tag')}</Button>
        </form>
      )}
      {tags.length === 0 ? <EmptyState icon={Tags} title={tx('No tags yet')} /> : (
        <div className="divide-y rounded-xl border bg-card shadow-sm">
          {tags.map((t) => (
            <div key={t.id} className="flex items-center gap-3 px-3 py-2">
              <button
                type="button"
                disabled={!canWrite}
                title={tx('Change colour')}
                onClick={() => update.mutate({ id: t.id, color: (t.color % (PM_COLORS.length - 1)) + 1 })}
                className={cn('h-4 w-4 rounded-full', pmColor(t.color).bg)}
              />
              <Input
                className="h-8 max-w-xs border-transparent shadow-none hover:border-input"
                defaultValue={t.name}
                key={`${t.id}-${t.name}`}
                disabled={!canWrite}
                onBlur={(e) => e.target.value.trim() && e.target.value !== t.name && update.mutate({ id: t.id, name: e.target.value.trim() })}
              />
              <span className="ml-auto text-xs text-muted-foreground">{tx('{{count}} tasks', { count: t.taskCount })}</span>
              {canDelete && (
                <button type="button" onClick={() => window.confirm(tx('Delete this tag? It is removed from every task.')) && remove.mutate(t.id)} aria-label={tx('Delete')}>
                  <Trash2 className="h-4 w-4 text-muted-foreground hover:text-rose-600" />
                </button>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
