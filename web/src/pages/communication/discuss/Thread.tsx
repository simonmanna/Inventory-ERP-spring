import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import {
  Archive,
  ArchiveRestore,
  Bell,
  BellOff,
  ChevronLeft,
  Hash,
  Loader2,
  Lock,
  LogOut,
  MoreVertical,
  Paperclip,
  Pencil,
  Pin,
  PinOff,
  Search,
  Users,
} from 'lucide-react';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import {
  fetchOlderMessages,
  useArchiveConversation,
  useConversationDetail,
  useConversationSettings,
  useJoinConversation,
  useLeaveConversation,
  useMarkThreadRead,
  usePinnedMessages,
  useSendDiscussMessage,
  useSharedAttachments,
  useThreadMessages,
} from '@/features/communication/discuss-api';
import { DiscussComposer } from '@/features/communication/discuss-composer';
import { seqNum, type ConversationDetail, type DirectoryUser, type DiscussMessage, type NotifyLevel } from '@/features/communication/discuss-types';
import { AttachmentList, Avatar, PresenceDot } from '@/features/communication/discuss-ui';
import { dayKey, dayLabel, formatDateTime } from '@/features/communication/discuss-format';
import { useIsOnline, useTypingNames } from '@/features/communication/sse';
import { tx } from '@/lib/i18n/tx';
import { MessageItem } from './MessageItem';
import { MemberPanel } from './MemberPanel';
import { EditConversationDialog } from './dialogs';

interface Props {
  conversationId: string;
  meId?: string;
  users: DirectoryUser[];
  highlightMessageId?: string;
  onBack: () => void;
  onSearchIn: (conversationId: string) => void;
  onLeft: () => void;
}

const GROUP_WINDOW_MS = 5 * 60_000;

export function Thread({ conversationId, meId, users, highlightMessageId, onBack, onSearchIn, onLeft }: Props) {
  const qc = useQueryClient();
  const { data: detail, error: detailError } = useConversationDetail(conversationId);
  const { data: page, isLoading } = useThreadMessages(conversationId);
  const send = useSendDiscussMessage();
  const markRead = useMarkThreadRead();
  const [replyTo, setReplyTo] = useState<DiscussMessage | null>(null);
  const [showMembers, setShowMembers] = useState(false);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const stickToBottom = useRef(true);
  const prevHeight = useRef<number | null>(null);
  const typing = useTypingNames(conversationId);

  const messages = useMemo(() => page?.messages ?? [], [page]);
  const lastMessage = messages[messages.length - 1];

  useEffect(() => {
    setReplyTo(null);
    stickToBottom.current = true;
  }, [conversationId]);

  // Mark read when the newest message changes while this thread is open and visible.
  const lastReadSent = useRef<string | null>(null);
  useEffect(() => {
    if (!lastMessage || document.visibilityState !== 'visible') return;
    const key = `${conversationId}:${lastMessage.id}`;
    if (lastReadSent.current === key) return;
    lastReadSent.current = key;
    markRead.mutate({ id: conversationId, messageId: lastMessage.id });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [conversationId, lastMessage?.id]);

  useEffect(() => {
    const onVisible = () => {
      if (document.visibilityState === 'visible' && lastMessage) {
        markRead.mutate({ id: conversationId, messageId: lastMessage.id });
      }
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => document.removeEventListener('visibilitychange', onVisible);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [conversationId, lastMessage?.id]);

  // Scroll: keep at bottom for new messages; keep position when older ones are prepended.
  useLayoutEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    if (prevHeight.current != null) {
      el.scrollTop = el.scrollHeight - prevHeight.current;
      prevHeight.current = null;
    } else if (stickToBottom.current) {
      el.scrollTop = el.scrollHeight;
    }
  }, [messages, typing.length]);

  // Deep link highlight.
  useEffect(() => {
    if (!highlightMessageId) return;
    const el = document.getElementById(`msg-${highlightMessageId}`);
    if (el) {
      stickToBottom.current = false;
      el.scrollIntoView({ block: 'center' });
    }
  }, [highlightMessageId, messages.length]);

  const onScroll = useCallback(async () => {
    const el = scrollRef.current;
    if (!el) return;
    stickToBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
    if (el.scrollTop < 60 && page?.hasMore && !loadingOlder) {
      setLoadingOlder(true);
      prevHeight.current = el.scrollHeight;
      try {
        await fetchOlderMessages(qc, conversationId);
      } catch {
        prevHeight.current = null;
      } finally {
        setLoadingOlder(false);
      }
    }
  }, [page?.hasMore, loadingOlder, qc, conversationId]);

  const jumpTo = (id: string) => {
    const el = document.getElementById(`msg-${id}`);
    if (el) {
      stickToBottom.current = false;
      el.scrollIntoView({ behavior: 'smooth', block: 'center' });
      el.classList.add('ring-2', 'ring-primary/40');
      window.setTimeout(() => el.classList.remove('ring-2', 'ring-primary/40'), 1500);
    }
  };

  const nameOf = useCallback(
    (userId: string) =>
      detail?.members.find((m) => m.userId === userId)?.name ?? users.find((u) => u.id === userId)?.name ?? '…',
    [detail, users],
  );

  if (detailError) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-2 p-6 text-center text-sm text-muted-foreground">
        <Lock className="h-6 w-6" />
        {tx('This conversation is not available to you.')}
        <button type="button" onClick={onBack} className="text-primary underline">
          {tx('Back')}
        </button>
      </div>
    );
  }

  const canPost = detail ? detail.canPost && !detail.archived : false;
  const isChat = detail?.kind === 'direct' || detail?.kind === 'group';
  const seenBy =
    isChat && lastMessage && detail
      ? detail.members
          .filter((m) => m.userId !== meId && m.userId !== lastMessage.author?.id)
          .filter((m) => seqNum(m.lastReadSeq) >= seqNum(lastMessage.seq))
          .map((m) => m.name)
      : [];

  return (
    <div className="flex min-w-0 flex-1">
      <section className="flex min-w-0 flex-1 flex-col">
        {detail ? (
          <ThreadHeader
            detail={detail}
            onBack={onBack}
            onToggleMembers={() => setShowMembers((s) => !s)}
            onSearchIn={() => onSearchIn(conversationId)}
            onJumpTo={jumpTo}
            onLeft={onLeft}
          />
        ) : (
          <div className="h-14 border-b border-border" />
        )}

        <div ref={scrollRef} onScroll={() => void onScroll()} className="flex-1 overflow-y-auto px-3 py-3 md:px-5">
          {(isLoading || loadingOlder) && (
            <div className="flex justify-center py-2 text-muted-foreground">
              <Loader2 className="h-4 w-4 animate-spin" />
            </div>
          )}
          {!isLoading && page && !page.hasMore && detail && <ConversationIntro detail={detail} />}
          {messages.map((m, i) => {
            const prev = messages[i - 1];
            const newDay = !prev || dayKey(prev.occurredAt) !== dayKey(m.occurredAt);
            const grouped =
              !newDay &&
              !!prev &&
              prev.author?.id === m.author?.id &&
              prev.subtype === m.subtype &&
              !m.replyTo &&
              new Date(m.occurredAt).getTime() - new Date(prev.occurredAt).getTime() < GROUP_WINDOW_MS;
            return (
              <div key={m.id}>
                {newDay && (
                  <div className="my-3 flex items-center gap-3 text-[11px] font-medium text-muted-foreground">
                    <div className="h-px flex-1 bg-border" />
                    {dayLabel(m.occurredAt)}
                    <div className="h-px flex-1 bg-border" />
                  </div>
                )}
                <MessageItem
                  msg={m}
                  grouped={grouped}
                  meId={meId}
                  canModerate={!!detail?.canManage}
                  canPost={canPost}
                  highlighted={m.id === highlightMessageId}
                  onReply={setReplyTo}
                  onJumpTo={jumpTo}
                  nameOf={nameOf}
                />
              </div>
            );
          })}
          {seenBy.length > 0 && (
            <div className="mt-1 pr-2 text-right text-[11px] text-muted-foreground">
              {detail?.kind === 'direct' ? tx('Seen') : tx('Seen by {{names}}', { names: seenBy.join(', ') })}
            </div>
          )}
        </div>

        <div className="h-5 px-5 text-xs italic text-muted-foreground">
          {typing.length === 1 && tx('{{name}} is typing…', { name: typing[0] })}
          {typing.length === 2 && tx('{{a}} and {{b}} are typing…', { a: typing[0], b: typing[1] })}
          {typing.length > 2 && tx('Several people are typing…')}
        </div>

        {detail && !detail.role && detail.kind === 'channel' && !detail.archived ? (
          <JoinBar conversationId={conversationId} />
        ) : (
        <DiscussComposer
          users={users}
          meId={meId}
          conversationId={conversationId}
          replyTo={replyTo ? { id: replyTo.id, authorName: replyTo.author?.name ?? null, body: replyTo.body } : null}
          onCancelReply={() => setReplyTo(null)}
          disabled={!canPost}
          disabledReason={
            detail?.archived
              ? tx('This conversation is archived.')
              : detail && !detail.canPost
                ? tx('Join this channel to post messages.')
                : undefined
          }
          autoFocus
          placeholder={
            detail
              ? detail.kind === 'channel'
                ? tx('Message #{{name}}', { name: detail.name })
                : tx('Message {{name}}', { name: detail.name })
              : undefined
          }
          onSend={async (input) => {
            stickToBottom.current = true;
            await send.mutateAsync({
              conversationId,
              body: input.body,
              mentionUserIds: input.mentionUserIds.length ? input.mentionUserIds : undefined,
              attachmentIds: input.attachmentIds.length ? input.attachmentIds : undefined,
              replyToMessageId: replyTo?.id,
            });
            setReplyTo(null);
          }}
        />
        )}
      </section>

      {showMembers && detail && (
        <MemberPanel detail={detail} users={users} meId={meId} onClose={() => setShowMembers(false)} />
      )}
    </div>
  );
}

function JoinBar({ conversationId }: { conversationId: string }) {
  const join = useJoinConversation();
  return (
    <div className="flex items-center justify-center gap-3 border-t border-border px-4 py-3 text-sm text-muted-foreground">
      {tx('You are viewing this channel.')}
      <button
        type="button"
        disabled={join.isPending}
        onClick={() => join.mutate(conversationId)}
        className="rounded-md bg-primary px-3 py-1.5 text-xs font-medium text-primary-foreground disabled:opacity-50"
      >
        {tx('Join channel')}
      </button>
    </div>
  );
}

function ConversationIntro({ detail }: { detail: ConversationDetail }) {
  return (
    <div className="mb-4 mt-2 px-2">
      <div className="text-lg font-semibold">
        {detail.kind === 'channel' ? `#${detail.name}` : detail.name}
      </div>
      <div className="text-sm text-muted-foreground">
        {detail.kind === 'channel'
          ? detail.description || tx('This is the start of the channel. Created {{date}}.', { date: formatDateTime(detail.createdAt) })
          : tx('This is the start of your conversation.')}
      </div>
    </div>
  );
}

function ThreadHeader({
  detail,
  onBack,
  onToggleMembers,
  onSearchIn,
  onJumpTo,
  onLeft,
}: {
  detail: ConversationDetail;
  onBack: () => void;
  onToggleMembers: () => void;
  onSearchIn: () => void;
  onJumpTo: (id: string) => void;
  onLeft: () => void;
}) {
  const settings = useConversationSettings();
  const leave = useLeaveConversation();
  const archive = useArchiveConversation();
  const [pinnedOpen, setPinnedOpen] = useState(false);
  const [filesOpen, setFilesOpen] = useState(false);
  const [editOpen, setEditOpen] = useState(false);
  const pinned = usePinnedMessages(detail.id, pinnedOpen);
  const files = useSharedAttachments(detail.id, filesOpen);
  const directOnline = useIsOnline(detail.directUserId);

  const isChannel = detail.kind === 'channel';
  const canRename = detail.canManage || detail.kind === 'group';
  const mute = (minutes: number) => settings.mutate({ id: detail.id, muteMinutes: minutes });

  return (
    <header className="flex h-14 shrink-0 items-center gap-2 border-b border-border px-3 md:px-4">
      <button type="button" onClick={onBack} className="rounded p-1 hover:bg-accent md:hidden" aria-label={tx('Back')}>
        <ChevronLeft className="h-5 w-5" />
      </button>
      <div className="flex min-w-0 flex-1 items-center gap-2">
        {isChannel ? (
          detail.visibility === 'private' ? <Lock className="h-4 w-4 shrink-0 text-muted-foreground" /> : <Hash className="h-4 w-4 shrink-0 text-muted-foreground" />
        ) : detail.kind === 'direct' && detail.directUserId ? (
          <Avatar id={detail.directUserId} name={detail.name} size="sm" online={directOnline} />
        ) : (
          <Users className="h-4 w-4 shrink-0 text-muted-foreground" />
        )}
        <div className="min-w-0">
          <div className="flex items-center gap-1.5 truncate font-semibold">
            {detail.name}
            {detail.archived && (
              <span className="rounded bg-muted px-1.5 text-[10px] font-medium text-muted-foreground">{tx('Archived')}</span>
            )}
            {detail.muted && <BellOff className="h-3.5 w-3.5 text-muted-foreground" />}
          </div>
          {detail.description ? (
            <div className="truncate text-xs text-muted-foreground">{detail.description}</div>
          ) : detail.kind === 'direct' ? (
            <div className="flex items-center gap-1 text-xs text-muted-foreground">
              <PresenceDot online={directOnline} className="border-0" /> {directOnline ? tx('Online') : tx('Offline')}
            </div>
          ) : null}
        </div>
      </div>

      <div className="flex items-center gap-0.5">
        <Popover open={pinnedOpen} onOpenChange={setPinnedOpen}>
          <PopoverTrigger asChild>
            <button type="button" className="relative rounded p-2 text-muted-foreground hover:bg-accent hover:text-foreground" title={tx('Pinned messages')}>
              <Pin className="h-4 w-4" />
              {detail.pinnedCount > 0 && (
                <span className="absolute right-0.5 top-0.5 rounded-full bg-primary px-1 text-[9px] text-primary-foreground">
                  {detail.pinnedCount}
                </span>
              )}
            </button>
          </PopoverTrigger>
          <PopoverContent align="end" className="max-h-96 w-80 overflow-y-auto p-2">
            <div className="mb-1 px-1 text-xs font-semibold">{tx('Pinned messages')}</div>
            {pinned.isLoading && <Loader2 className="mx-auto h-4 w-4 animate-spin" />}
            {pinned.data?.length === 0 && <div className="p-2 text-xs text-muted-foreground">{tx('Nothing pinned yet.')}</div>}
            {pinned.data?.map((m) => (
              <button
                key={m.id}
                type="button"
                onClick={() => {
                  setPinnedOpen(false);
                  onJumpTo(m.id);
                }}
                className="block w-full rounded px-2 py-1.5 text-left hover:bg-accent"
              >
                <div className="text-xs font-medium">{m.author?.name ?? tx('System')} · <span className="font-normal text-muted-foreground">{formatDateTime(m.occurredAt)}</span></div>
                <div className="line-clamp-2 text-xs text-muted-foreground">{m.body || tx('(attachment)')}</div>
              </button>
            ))}
          </PopoverContent>
        </Popover>
        <Popover open={filesOpen} onOpenChange={setFilesOpen}>
          <PopoverTrigger asChild>
            <button type="button" className="rounded p-2 text-muted-foreground hover:bg-accent hover:text-foreground" title={tx('Shared files')}>
              <Paperclip className="h-4 w-4" />
            </button>
          </PopoverTrigger>
          <PopoverContent align="end" className="max-h-96 w-80 overflow-y-auto p-2">
            <div className="mb-1 px-1 text-xs font-semibold">{tx('Shared files')}</div>
            {files.isLoading && <Loader2 className="mx-auto h-4 w-4 animate-spin" />}
            {files.data?.length === 0 && <div className="p-2 text-xs text-muted-foreground">{tx('No files shared yet.')}</div>}
            {files.data?.map((a) => (
              <div key={a.id} className="px-1 py-1">
                <AttachmentList attachments={[a]} />
                <div className="text-[10px] text-muted-foreground">{a.authorName} · {formatDateTime(a.occurredAt)}</div>
              </div>
            ))}
          </PopoverContent>
        </Popover>
        <button type="button" onClick={onSearchIn} className="rounded p-2 text-muted-foreground hover:bg-accent hover:text-foreground" title={tx('Search in conversation')}>
          <Search className="h-4 w-4" />
        </button>
        {detail.kind !== 'direct' && (
          <button type="button" onClick={onToggleMembers} className="flex items-center gap-1 rounded p-2 text-xs text-muted-foreground hover:bg-accent hover:text-foreground" title={tx('Members')}>
            <Users className="h-4 w-4" />
            <span>{detail.memberCount}</span>
          </button>
        )}
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <button type="button" className="rounded p-2 text-muted-foreground hover:bg-accent hover:text-foreground" aria-label={tx('Conversation settings')}>
              <MoreVertical className="h-4 w-4" />
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-60">
            {detail.role && (
              <>
                <DropdownMenuLabel className="text-xs">{tx('Notifications')}</DropdownMenuLabel>
                <DropdownMenuRadioGroup
                  value={detail.notifyLevel}
                  onValueChange={(v) => settings.mutate({ id: detail.id, notifyLevel: v as NotifyLevel })}
                >
                  <DropdownMenuRadioItem value="all">{tx('All messages')}</DropdownMenuRadioItem>
                  <DropdownMenuRadioItem value="mentions">{tx('Mentions only')}</DropdownMenuRadioItem>
                  <DropdownMenuRadioItem value="none">{tx('Nothing')}</DropdownMenuRadioItem>
                </DropdownMenuRadioGroup>
                <DropdownMenuSeparator />
                {detail.muted ? (
                  <DropdownMenuItem onClick={() => mute(0)}>
                    <Bell className="mr-2 h-4 w-4" /> {tx('Unmute')}
                  </DropdownMenuItem>
                ) : (
                  <>
                    <DropdownMenuItem onClick={() => mute(60)}>
                      <BellOff className="mr-2 h-4 w-4" /> {tx('Mute for 1 hour')}
                    </DropdownMenuItem>
                    <DropdownMenuItem onClick={() => mute(480)}>
                      <BellOff className="mr-2 h-4 w-4" /> {tx('Mute for 8 hours')}
                    </DropdownMenuItem>
                    <DropdownMenuItem onClick={() => mute(-1)}>
                      <BellOff className="mr-2 h-4 w-4" /> {tx('Mute until I unmute')}
                    </DropdownMenuItem>
                  </>
                )}
                <DropdownMenuItem onClick={() => settings.mutate({ id: detail.id, pinned: !detail.pinned })}>
                  {detail.pinned ? <PinOff className="mr-2 h-4 w-4" /> : <Pin className="mr-2 h-4 w-4" />}
                  {detail.pinned ? tx('Unpin from sidebar') : tx('Pin to sidebar')}
                </DropdownMenuItem>
              </>
            )}
            {canRename && (
              <>
                <DropdownMenuSeparator />
                <DropdownMenuItem onClick={() => setEditOpen(true)}>
                  <Pencil className="mr-2 h-4 w-4" /> {isChannel ? tx('Edit channel') : tx('Rename chat')}
                </DropdownMenuItem>
              </>
            )}
            {isChannel && detail.canManage && (
              <DropdownMenuItem
                onClick={() => {
                  const next = !detail.archived;
                  if (!next || window.confirm(tx('Archive this channel? It becomes read-only for everyone.'))) {
                    archive.mutate({ id: detail.id, archived: next });
                  }
                }}
              >
                {detail.archived ? <ArchiveRestore className="mr-2 h-4 w-4" /> : <Archive className="mr-2 h-4 w-4" />}
                {detail.archived ? tx('Unarchive channel') : tx('Archive channel')}
              </DropdownMenuItem>
            )}
            {detail.role && detail.kind !== 'direct' && (
              <>
                <DropdownMenuSeparator />
                <DropdownMenuItem
                  className="text-destructive focus:text-destructive"
                  onClick={() => {
                    if (window.confirm(tx('Leave this conversation?'))) {
                      leave.mutate(detail.id, { onSuccess: onLeft });
                    }
                  }}
                >
                  <LogOut className="mr-2 h-4 w-4" /> {tx('Leave')}
                </DropdownMenuItem>
              </>
            )}
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
      {editOpen && <EditConversationDialog detail={detail} onClose={() => setEditOpen(false)} />}
    </header>
  );
}
