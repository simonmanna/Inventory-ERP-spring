import type { ReactNode } from 'react';
import { AtSign, CheckCheck, ChevronLeft, FileText, Hash, Inbox, Loader2, Search, Star, Users, X } from 'lucide-react';
import { useInbox, useMarkInboxRead, useMessageSearch, useStarred } from '@/features/communication/discuss-api';
import type { ConversationRef, DiscussMessage } from '@/features/communication/discuss-types';
import { AttachmentList, Avatar, MessageBody } from '@/features/communication/discuss-ui';
import { formatDateTime } from '@/features/communication/discuss-format';
import { tx } from '@/lib/i18n/tx';

interface OpenFn {
  (conversationId: string, messageId?: string): void;
}

function ConversationLabel({ c }: { c: ConversationRef }) {
  const Icon = c.contextType ? FileText : c.kind === 'channel' ? Hash : Users;
  return (
    <span className="flex items-center gap-1 truncate text-xs text-muted-foreground">
      <Icon className="h-3 w-3 shrink-0" />
      <span className="truncate">{c.name}</span>
    </span>
  );
}

function HitCard({
  conversation,
  message,
  meId,
  onOpen,
  unread,
  badge,
}: {
  conversation: ConversationRef;
  message: DiscussMessage;
  meId?: string;
  onOpen: OpenFn;
  unread?: boolean;
  badge?: ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={() => onOpen(conversation.id, message.id)}
      className={`flex w-full gap-3 rounded-lg border p-3 text-left hover:bg-accent/60 ${unread ? 'border-primary/40 bg-primary/5' : 'border-border'}`}
    >
      <Avatar id={message.author?.id ?? 'system'} name={message.author?.name ?? tx('System')} initials={message.author?.initials} />
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="text-sm font-semibold">{message.author?.name ?? tx('System')}</span>
          {badge}
          <span className="ml-auto shrink-0 text-[11px] text-muted-foreground">{formatDateTime(message.occurredAt)}</span>
        </div>
        <ConversationLabel c={conversation} />
        {message.body && (
          <div className="mt-1 line-clamp-4 whitespace-pre-wrap break-words text-sm">
            <MessageBody body={message.body} mentions={message.mentions} meId={meId} />
          </div>
        )}
        <AttachmentList attachments={message.attachments} />
      </div>
    </button>
  );
}

function ViewHeader({ icon, title, onBack, action }: { icon: ReactNode; title: string; onBack: () => void; action?: ReactNode }) {
  return (
    <header className="flex h-14 shrink-0 items-center gap-2 border-b border-border px-4">
      <button type="button" onClick={onBack} className="rounded p-1 hover:bg-accent md:hidden" aria-label={tx('Back')}>
        <ChevronLeft className="h-5 w-5" />
      </button>
      {icon}
      <div className="flex-1 font-semibold">{title}</div>
      {action}
    </header>
  );
}

export function InboxView({ meId, onOpen, onBack }: { meId?: string; onOpen: OpenFn; onBack: () => void }) {
  const { data, isLoading } = useInbox(true);
  const markRead = useMarkInboxRead();
  const items = data?.items ?? [];
  return (
    <section className="flex min-w-0 flex-1 flex-col">
      <ViewHeader
        icon={<Inbox className="h-4 w-4 text-muted-foreground" />}
        title={tx('Inbox')}
        onBack={onBack}
        action={
          (data?.unread ?? 0) > 0 && (
            <button
              type="button"
              onClick={() => markRead.mutate(undefined)}
              className="flex items-center gap-1 rounded-md border border-border px-2.5 py-1 text-xs hover:bg-accent"
            >
              <CheckCheck className="h-3.5 w-3.5" /> {tx('Mark all as read')}
            </button>
          )
        }
      />
      <div className="flex-1 space-y-2 overflow-y-auto p-4">
        {isLoading && <Loader2 className="mx-auto h-5 w-5 animate-spin text-muted-foreground" />}
        {!isLoading && items.length === 0 && (
          <div className="flex flex-col items-center gap-2 pt-16 text-center text-sm text-muted-foreground">
            <Inbox className="h-8 w-8 opacity-40" />
            {tx('Congratulations, your inbox is empty.')}
            <span className="text-xs">{tx('Mentions and messages on records you follow appear here.')}</span>
          </div>
        )}
        {items.map((it) => (
          <HitCard
            key={it.id}
            conversation={it.conversation}
            message={it.message}
            meId={meId}
            unread={!it.readAt}
            onOpen={(c, m) => {
              if (!it.readAt) markRead.mutate([it.id]);
              onOpen(c, m);
            }}
            badge={
              <span className="flex items-center gap-0.5 rounded bg-muted px-1.5 text-[10px] text-muted-foreground">
                {it.reason === 'mention' ? <AtSign className="h-3 w-3" /> : <Star className="h-3 w-3" />}
                {it.reason === 'mention' ? tx('Mention') : tx('Following')}
              </span>
            }
          />
        ))}
      </div>
    </section>
  );
}

export function StarredView({ meId, onOpen, onBack }: { meId?: string; onOpen: OpenFn; onBack: () => void }) {
  const { data, isLoading } = useStarred(true);
  return (
    <section className="flex min-w-0 flex-1 flex-col">
      <ViewHeader icon={<Star className="h-4 w-4 text-amber-500" />} title={tx('Starred')} onBack={onBack} />
      <div className="flex-1 space-y-2 overflow-y-auto p-4">
        {isLoading && <Loader2 className="mx-auto h-5 w-5 animate-spin text-muted-foreground" />}
        {!isLoading && (data ?? []).length === 0 && (
          <div className="flex flex-col items-center gap-2 pt-16 text-center text-sm text-muted-foreground">
            <Star className="h-8 w-8 opacity-40" />
            {tx('No starred messages. Star a message to keep it here.')}
          </div>
        )}
        {(data ?? []).map((h) => (
          <HitCard key={h.message.id} conversation={h.conversation} message={h.message} meId={meId} onOpen={onOpen} />
        ))}
      </div>
    </section>
  );
}

export function SearchView({
  query,
  conversation,
  meId,
  onOpen,
  onBack,
  onClearScope,
}: {
  query: string;
  conversation?: { id: string; name: string };
  meId?: string;
  onOpen: OpenFn;
  onBack: () => void;
  onClearScope: () => void;
}) {
  const { data, isLoading, isFetching } = useMessageSearch(query, conversation?.id);
  const tooShort = query.trim().length < 2;
  return (
    <section className="flex min-w-0 flex-1 flex-col">
      <ViewHeader
        icon={<Search className="h-4 w-4 text-muted-foreground" />}
        title={tx('Search results')}
        onBack={onBack}
        action={
          conversation && (
            <span className="flex items-center gap-1 rounded-full bg-muted px-2 py-0.5 text-xs">
              {tx('in {{name}}', { name: conversation.name })}
              <button type="button" onClick={onClearScope} aria-label={tx('Search everywhere')}>
                <X className="h-3 w-3" />
              </button>
            </span>
          )
        }
      />
      <div className="flex-1 space-y-2 overflow-y-auto p-4">
        {tooShort && <div className="pt-12 text-center text-sm text-muted-foreground">{tx('Type at least 2 characters to search messages.')}</div>}
        {!tooShort && (isLoading || isFetching) && <Loader2 className="mx-auto h-5 w-5 animate-spin text-muted-foreground" />}
        {!tooShort && !isLoading && (data ?? []).length === 0 && (
          <div className="pt-12 text-center text-sm text-muted-foreground">{tx('No messages match “{{q}}”.', { q: query.trim() })}</div>
        )}
        {(data ?? []).map((h) => (
          <HitCard key={h.message.id} conversation={h.conversation} message={h.message} meId={meId} onOpen={onOpen} />
        ))}
      </div>
    </section>
  );
}
