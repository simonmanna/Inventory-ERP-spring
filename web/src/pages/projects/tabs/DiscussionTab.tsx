import { useState } from 'react';
import { Circle, MessageSquare } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { relativeTime } from '@/lib/format';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { usePmMutation, useProjectMessages, type Workspace } from '@/features/projects/api';
import { Avatar, EmptyState } from '@/features/projects/ui';

export function DiscussionTab({ ws, onOpenTask }: { ws: Workspace; onOpenTask: (id: string) => void }) {
  const [includeTasks, setIncludeTasks] = useState(false);
  const { data: messages = [] } = useProjectMessages(ws.id, includeTasks);
  const [body, setBody] = useState('');
  const [kind, setKind] = useState<'comment' | 'note'>('comment');
  const post = usePmMutation(() => ({ method: 'post', url: `/projects/${ws.id}/messages`, body: { kind, body: body.trim() } }));
  return (
    <div className="mx-auto max-w-3xl space-y-4">
      {ws.canEditTasks && (
        <form
          className="space-y-2 rounded-xl border bg-card p-4 shadow-sm"
          onSubmit={(e) => { e.preventDefault(); if (body.trim()) post.mutate(undefined, { onSuccess: () => setBody('') }); }}
        >
          <div className="flex gap-1 text-xs">
            <button type="button" onClick={() => setKind('comment')} className={cn('rounded px-2 py-1', kind === 'comment' ? 'bg-primary text-primary-foreground' : 'hover:bg-muted')}>{tx('Message the team')}</button>
            <button type="button" onClick={() => setKind('note')} className={cn('rounded px-2 py-1', kind === 'note' ? 'bg-amber-500 text-white' : 'hover:bg-muted')}>{tx('Log note')}</button>
          </div>
          <Textarea rows={3} value={body} onChange={(e) => setBody(e.target.value)} placeholder={tx('Share news, ask a question, record a decision…')} />
          <div className="flex justify-end"><Button type="submit" size="sm" disabled={!body.trim() || post.isPending}>{tx('Post')}</Button></div>
        </form>
      )}
      <label className="flex items-center gap-2 text-sm text-muted-foreground">
        <input type="checkbox" className="h-4 w-4 accent-primary" checked={includeTasks} onChange={(e) => setIncludeTasks(e.target.checked)} />
        {tx('Include task activity')}
      </label>
      {messages.length === 0 ? <EmptyState icon={MessageSquare} title={tx('No messages yet')} /> : (
        <ol className="space-y-4">
          {messages.map((m) => (
            <li key={m.id} className="flex gap-3">
              {m.kind === 'system'
                ? <span className="mt-1 flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-muted"><Circle className="h-2 w-2 fill-current text-muted-foreground" /></span>
                : <Avatar size="md" user={{ id: m.authorId ?? m.id, name: m.authorName }} />}
              <div className="min-w-0 flex-1">
                <p className="text-xs text-muted-foreground">
                  <span className="font-medium text-foreground">{m.authorName ?? tx('System')}</span> · {relativeTime(m.createdAt)}
                  {m.kind === 'note' && <span className="ml-1 rounded bg-amber-100 px-1 text-amber-800 dark:bg-amber-500/20 dark:text-amber-200">{tx('note')}</span>}
                  {m.taskId && (
                    <button type="button" className="ml-1 text-primary hover:underline" onClick={() => onOpenTask(m.taskId!)}>
                      {m.taskNumber} {m.taskTitle}
                    </button>
                  )}
                </p>
                <p className={cn('whitespace-pre-wrap text-sm', m.kind === 'system' && 'text-muted-foreground')}>{m.body}</p>
              </div>
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}
