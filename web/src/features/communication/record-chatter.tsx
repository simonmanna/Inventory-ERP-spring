import { useMemo, useState } from 'react';
import { Bell, BellOff, Loader2, MessageSquare, StickyNote, Users } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';
import {
  useDirectory,
  useFollowRecord,
  usePostRecordMessage,
  useRecordThread,
  useThreadMessages,
} from './discuss-api';
import { DiscussComposer } from './discuss-composer';
import { AttachmentList, Avatar, MessageBody } from './discuss-ui';
import { formatDateTime } from './discuss-format';

interface Props {
  /** Registered record type (ADR-S064 §4): SalesOrder, SalesQuotation, PurchaseOrder, Invoice, Expense. */
  contextType: string;
  contextId: string;
  className?: string;
}

/**
 * Odoo-style chatter for a business record: "Send message" (notifies followers) / "Log note" (internal), followers,
 * attachments and mentions. Hidden when the user cannot read conversations or the communication module is off.
 */
export function RecordChatter({ contextType, contextId, className = '' }: Props) {
  const canRead = useAuthStore((s) => s.permissions.includes(PERMISSIONS.communication.conversationRead));
  const canSend = useAuthStore((s) => s.permissions.includes(PERMISSIONS.communication.messageSend));
  const meId = useAuthStore((s) => s.user?.id);
  const thread = useRecordThread(contextType, contextId, canRead);
  const conversationId = thread.data?.conversationId ?? undefined;
  const messages = useThreadMessages(conversationId);
  const { data: directory } = useDirectory();
  const post = usePostRecordMessage(contextType, contextId);
  const follow = useFollowRecord(contextType, contextId);
  const [mode, setMode] = useState<'comment' | 'note' | null>(null);
  const [showFollowers, setShowFollowers] = useState(false);

  const list = useMemo(() => [...(messages.data?.messages ?? [])].reverse(), [messages.data]);

  if (!canRead || thread.isError) return null;

  const followers = thread.data?.followers ?? [];
  const following = !!thread.data?.following;

  return (
    <section className={`rounded-xl border border-border bg-card ${className}`}>
      <div className="flex flex-wrap items-center gap-2 border-b border-border px-4 py-2.5">
        {canSend && (
          <>
            <button
              type="button"
              onClick={() => setMode(mode === 'comment' ? null : 'comment')}
              className={`flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium ${
                mode === 'comment' ? 'bg-primary text-primary-foreground' : 'border border-border hover:bg-accent'
              }`}
            >
              <MessageSquare className="h-3.5 w-3.5" /> {tx('Send message')}
            </button>
            <button
              type="button"
              onClick={() => setMode(mode === 'note' ? null : 'note')}
              className={`flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium ${
                mode === 'note' ? 'bg-amber-500 text-white' : 'border border-border hover:bg-accent'
              }`}
            >
              <StickyNote className="h-3.5 w-3.5" /> {tx('Log note')}
            </button>
          </>
        )}
        <div className="ml-auto flex items-center gap-1">
          <div className="relative">
            <button
              type="button"
              onClick={() => setShowFollowers((s) => !s)}
              className="flex items-center gap-1 rounded-md px-2 py-1.5 text-xs text-muted-foreground hover:bg-accent"
              title={tx('Followers')}
            >
              <Users className="h-3.5 w-3.5" /> {followers.length}
            </button>
            {showFollowers && (
              <div className="absolute right-0 top-full z-20 mt-1 w-56 rounded-lg border border-border bg-popover p-2 shadow-lg">
                <div className="mb-1 px-1 text-xs font-semibold">{tx('Followers')}</div>
                {followers.length === 0 && <div className="px-1 text-xs text-muted-foreground">{tx('No followers yet.')}</div>}
                {followers.map((f) => (
                  <div key={f.userId} className="flex items-center gap-2 px-1 py-1 text-sm">
                    <Avatar id={f.userId} name={f.name} size="sm" />
                    <span className="truncate">{f.name}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
          <button
            type="button"
            disabled={follow.isPending || thread.isLoading}
            onClick={() => follow.mutate(!following)}
            className={`flex items-center gap-1 rounded-md px-2.5 py-1.5 text-xs font-medium ${
              following ? 'text-emerald-600 hover:bg-accent dark:text-emerald-400' : 'border border-border hover:bg-accent'
            }`}
          >
            {following ? <BellOff className="h-3.5 w-3.5" /> : <Bell className="h-3.5 w-3.5" />}
            {following ? tx('Following') : tx('Follow')}
          </button>
        </div>
      </div>

      {mode && (
        <div className={mode === 'note' ? 'bg-amber-50/40 dark:bg-amber-950/10' : ''}>
          <DiscussComposer
            key={mode}
            users={directory?.users ?? []}
            meId={meId}
            tone={mode === 'note' ? 'note' : 'default'}
            autoFocus
            placeholder={
              mode === 'note'
                ? tx('Log an internal note… (@ to mention someone)')
                : tx('Send a message to followers… (@ to mention)')
            }
            onSend={async (input) => {
              await post.mutateAsync({
                body: input.body,
                subtype: mode,
                mentionUserIds: input.mentionUserIds.length ? input.mentionUserIds : undefined,
                attachmentIds: input.attachmentIds.length ? input.attachmentIds : undefined,
              });
            }}
          />
        </div>
      )}

      <div className="max-h-[32rem] space-y-3 overflow-y-auto px-4 py-3">
        {(thread.isLoading || messages.isLoading) && <Loader2 className="mx-auto h-4 w-4 animate-spin text-muted-foreground" />}
        {!thread.isLoading && !messages.isLoading && list.length === 0 && (
          <div className="py-4 text-center text-xs text-muted-foreground">
            {tx('No messages yet. Send a message or log a note to start the history.')}
          </div>
        )}
        {list.map((m) => (
          <div key={m.id} className={`flex gap-3 ${m.subtype === 'note' ? 'rounded-lg bg-amber-50/60 p-2 dark:bg-amber-950/20' : ''}`}>
            <Avatar id={m.author?.id ?? 'system'} name={m.author?.name ?? tx('System')} initials={m.author?.initials} />
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-baseline gap-2">
                <span className="text-sm font-semibold">{m.author?.name ?? tx('System')}</span>
                <span className="text-[11px] text-muted-foreground">{formatDateTime(m.occurredAt)}</span>
                {m.subtype === 'note' && (
                  <span className="rounded bg-amber-400/25 px-1 text-[10px] font-medium text-amber-700 dark:text-amber-300">
                    {tx('Note')}
                  </span>
                )}
                {m.editedAt && <span className="text-[10px] text-muted-foreground">({tx('edited')})</span>}
              </div>
              {m.body && (
                <div className="whitespace-pre-wrap break-words text-sm">
                  <MessageBody body={m.body} mentions={m.mentions} meId={meId} />
                </div>
              )}
              <AttachmentList attachments={m.attachments} />
            </div>
          </div>
        ))}
        {messages.data?.hasMore && (
          <a
            href={`/communication?c=${encodeURIComponent(conversationId ?? '')}`}
            className="block text-center text-xs text-primary hover:underline"
          >
            {tx('Open the full history in Discuss')}
          </a>
        )}
      </div>
    </section>
  );
}
