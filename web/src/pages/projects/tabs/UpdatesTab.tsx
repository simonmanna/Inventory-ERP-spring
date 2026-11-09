import { useState } from 'react';
import { TrendingUp } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { relativeTime } from '@/lib/format';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { usePmMutation, useProjectUpdates, type UpdateStatus, type Workspace } from '@/features/projects/api';
import { Avatar, EmptyState, ProgressBar, UPDATE_STATUS, UpdateStatusPill } from '@/features/projects/ui';

export function UpdatesTab({ ws }: { ws: Workspace }) {
  const { data: updates = [] } = useProjectUpdates(ws.id);
  const last = updates[0];
  const [status, setStatus] = useState<UpdateStatus>(last?.status ?? 'on_track');
  const [progress, setProgress] = useState<number>(last?.progress ?? 0);
  const [summary, setSummary] = useState('');
  const post = usePmMutation(() => ({ method: 'post', url: `/projects/${ws.id}/updates`, body: { status, progress, summary } }), { success: tx('Status update posted') });

  return (
    <div className="grid gap-4 lg:grid-cols-[380px_1fr]">
      {ws.canEditTasks && (
        <form
          className="h-fit space-y-3 rounded-xl border bg-card p-4 shadow-sm"
          onSubmit={(e) => { e.preventDefault(); post.mutate(undefined, { onSuccess: () => setSummary('') }); }}
        >
          <h3 className="text-sm font-semibold">{tx('Post a status update')}</h3>
          <div className="flex flex-wrap gap-1.5">
            {(Object.keys(UPDATE_STATUS) as UpdateStatus[]).map((s) => (
              <button
                key={s}
                type="button"
                onClick={() => setStatus(s)}
                className={cn('rounded-full border px-2.5 py-1 text-xs', status === s ? 'border-primary bg-primary/10 font-medium' : 'text-muted-foreground hover:bg-muted')}
              >
                {UPDATE_STATUS[s].label}
              </button>
            ))}
          </div>
          <div>
            <div className="mb-1 flex justify-between text-xs text-muted-foreground"><span>{tx('Progress')}</span><span className="font-medium text-foreground">{progress}%</span></div>
            <input type="range" min={0} max={100} step={5} value={progress} onChange={(e) => setProgress(Number(e.target.value))} className="w-full accent-primary" />
          </div>
          <Textarea rows={5} value={summary} onChange={(e) => setSummary(e.target.value)} placeholder={tx('What happened, what is next, any risks or decisions needed?')} />
          <p className="text-xs text-muted-foreground">{tx('Task and hour figures are captured automatically with the update.')}</p>
          <Button type="submit" className="w-full" disabled={post.isPending}>{tx('Post update')}</Button>
        </form>
      )}
      <div className="space-y-3">
        {updates.length === 0 && <EmptyState icon={TrendingUp} title={tx('No status updates yet')} hint={tx('Regular updates tell stakeholders whether the project is on track.')} />}
        {updates.map((u) => (
          <article key={u.id} className="rounded-xl border bg-card p-4 shadow-sm">
            <header className="mb-2 flex flex-wrap items-center gap-2">
              <Avatar user={{ id: u.authorId ?? u.id, name: u.authorName }} />
              <span className="text-sm font-medium">{u.authorName ?? tx('Someone')}</span>
              <span className="text-xs text-muted-foreground">{relativeTime(u.createdAt)}</span>
              <span className="ml-auto"><UpdateStatusPill status={u.status} /></span>
            </header>
            <div className="mb-2 flex items-center gap-2"><ProgressBar value={u.progress} /><span className="text-xs tabular-nums">{u.progress}%</span></div>
            {u.summary && <p className="whitespace-pre-wrap text-sm">{u.summary}</p>}
          </article>
        ))}
      </div>
    </div>
  );
}
