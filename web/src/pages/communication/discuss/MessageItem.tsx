import { useState, type ReactNode } from 'react';
import { Check, Pencil, Pin, Reply, SmilePlus, Star, Trash2, X } from 'lucide-react';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import {
  useDeleteMessage,
  useEditMessage,
  useTogglePin,
  useToggleReaction,
  useToggleStar,
} from '@/features/communication/discuss-api';
import type { DiscussMessage } from '@/features/communication/discuss-types';
import { AttachmentList, Avatar, MessageBody } from '@/features/communication/discuss-ui';
import { EMOJIS, QUICK_REACTIONS, formatDateTime, formatTime } from '@/features/communication/discuss-format';
import { tx } from '@/lib/i18n/tx';

interface Props {
  msg: DiscussMessage;
  /** Same author within a few minutes as the previous message: no avatar/name row. */
  grouped: boolean;
  meId?: string;
  canModerate: boolean;
  canPost: boolean;
  highlighted?: boolean;
  onReply: (m: DiscussMessage) => void;
  onJumpTo?: (messageId: string) => void;
  nameOf: (userId: string) => string;
}

export function MessageItem({ msg, grouped, meId, canModerate, canPost, highlighted, onReply, onJumpTo, nameOf }: Props) {
  const mine = !!meId && msg.author?.id === meId;
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(msg.body);
  const [pickerOpen, setPickerOpen] = useState(false);
  const edit = useEditMessage();
  const del = useDeleteMessage();
  const react = useToggleReaction();
  const pin = useTogglePin();
  const star = useToggleStar();

  if (msg.subtype === 'notification' || msg.senderType === 'system') {
    return (
      <div id={`msg-${msg.id}`} className="flex justify-center py-1">
        <span className="rounded-full bg-muted px-3 py-0.5 text-xs text-muted-foreground">
          {msg.body} · {formatTime(msg.occurredAt)}
        </span>
      </div>
    );
  }

  const saveEdit = () => {
    const body = draft.trim();
    if (!body || body === msg.body) {
      setEditing(false);
      return;
    }
    edit.mutate({ id: msg.id, body }, { onSuccess: () => setEditing(false) });
  };

  const remove = () => {
    if (window.confirm(tx('Delete this message? This cannot be undone.'))) {
      del.mutate({ id: msg.id, conversationId: msg.conversationId });
    }
  };

  const authorName = msg.author?.name ?? tx('System');

  return (
    <div
      id={`msg-${msg.id}`}
      className={`group relative flex gap-3 rounded-md px-2 transition-colors hover:bg-muted/40 ${grouped ? 'py-0.5' : 'mt-2 pt-1.5 pb-0.5'} ${
        highlighted ? 'bg-amber-400/15 ring-1 ring-amber-400/40' : ''
      } ${msg.subtype === 'note' ? 'border-l-2 border-amber-400/70 bg-amber-50/30 dark:bg-amber-950/10' : ''}`}
    >
      <div className="w-8 shrink-0">
        {!grouped ? (
          <Avatar id={msg.author?.id ?? 'system'} name={authorName} initials={msg.author?.initials} />
        ) : (
          <span className="invisible block pt-0.5 text-right text-[10px] text-muted-foreground group-hover:visible">
            {formatTime(msg.occurredAt)}
          </span>
        )}
      </div>
      <div className="min-w-0 flex-1">
        {!grouped && (
          <div className="flex items-baseline gap-2">
            <span className="text-sm font-semibold">{authorName}</span>
            <span className="text-[11px] text-muted-foreground" title={formatDateTime(msg.occurredAt)}>
              {formatTime(msg.occurredAt)}
            </span>
            {msg.subtype === 'note' && (
              <span className="rounded bg-amber-400/20 px-1 text-[10px] font-medium text-amber-700 dark:text-amber-300">
                {tx('Note')}
              </span>
            )}
          </div>
        )}

        {msg.replyTo && (
          <button
            type="button"
            onClick={() => onJumpTo?.(msg.replyTo!.id)}
            className="mb-0.5 mt-0.5 flex max-w-full items-center gap-1.5 rounded border-l-2 border-primary/60 bg-muted/60 px-2 py-0.5 text-left text-xs text-muted-foreground hover:bg-muted"
          >
            <Reply className="h-3 w-3 shrink-0" />
            <span className="shrink-0 font-medium text-foreground/80">{msg.replyTo.authorName ?? tx('System')}</span>
            <span className="truncate">{msg.replyTo.body}</span>
          </button>
        )}

        {editing ? (
          <div className="mt-1">
            <textarea
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  saveEdit();
                }
                if (e.key === 'Escape') setEditing(false);
              }}
              autoFocus
              rows={Math.min(8, Math.max(2, draft.split('\n').length))}
              className="w-full resize-none rounded-md border border-input bg-background px-2 py-1.5 text-sm outline-none focus:ring-1 focus:ring-ring"
            />
            <div className="mt-1 flex items-center gap-2 text-xs text-muted-foreground">
              <button type="button" onClick={saveEdit} className="flex items-center gap-1 rounded bg-primary px-2 py-0.5 text-primary-foreground">
                <Check className="h-3 w-3" /> {tx('Save')}
              </button>
              <button type="button" onClick={() => setEditing(false)} className="flex items-center gap-1 rounded px-2 py-0.5 hover:bg-accent">
                <X className="h-3 w-3" /> {tx('Cancel')}
              </button>
              <span>{tx('Enter to save · Esc to cancel')}</span>
            </div>
          </div>
        ) : (
          msg.body && (
            <div className="whitespace-pre-wrap break-words text-sm leading-relaxed">
              <MessageBody body={msg.body} mentions={msg.mentions} meId={meId} />
              {msg.editedAt && (
                <span className="ml-1 text-[10px] text-muted-foreground" title={formatDateTime(msg.editedAt)}>
                  ({tx('edited')})
                </span>
              )}
            </div>
          )
        )}

        <AttachmentList attachments={msg.attachments} />

        {(msg.reactions.length > 0 || msg.pinnedAt || msg.starred) && (
          <div className="mt-1 flex flex-wrap items-center gap-1">
            {msg.reactions.map((r) => {
              const reacted = !!meId && r.userIds.includes(meId);
              return (
                <button
                  key={r.emoji}
                  type="button"
                  disabled={!canPost}
                  onClick={() => react.mutate({ id: msg.id, emoji: r.emoji })}
                  title={r.userIds.map(nameOf).join(', ')}
                  className={`flex items-center gap-1 rounded-full border px-1.5 py-0.5 text-xs ${
                    reacted ? 'border-primary/50 bg-primary/10' : 'border-border bg-background hover:bg-accent'
                  }`}
                >
                  <span>{r.emoji}</span>
                  <span className="text-muted-foreground">{r.count}</span>
                </button>
              );
            })}
            {msg.pinnedAt && (
              <span className="flex items-center gap-0.5 text-[10px] text-muted-foreground">
                <Pin className="h-3 w-3" /> {tx('Pinned')}
              </span>
            )}
            {msg.starred && <Star className="h-3 w-3 fill-amber-400 text-amber-400" />}
          </div>
        )}
      </div>

      {!editing && (
        <div className="absolute -top-3 right-2 z-10 hidden items-center gap-0.5 rounded-md border border-border bg-background p-0.5 shadow-sm group-hover:flex">
          {canPost && (
            <>
              {QUICK_REACTIONS.slice(0, 3).map((em) => (
                <ActionButton key={em} label={em} onClick={() => react.mutate({ id: msg.id, emoji: em })}>
                  <span className="text-sm leading-none">{em}</span>
                </ActionButton>
              ))}
              <Popover open={pickerOpen} onOpenChange={setPickerOpen}>
                <PopoverTrigger asChild>
                  <button type="button" className="rounded p-1 text-muted-foreground hover:bg-accent hover:text-foreground" title={tx('Add reaction')}>
                    <SmilePlus className="h-3.5 w-3.5" />
                  </button>
                </PopoverTrigger>
                <PopoverContent align="end" className="w-64 p-2">
                  <div className="grid grid-cols-8 gap-0.5">
                    {EMOJIS.map((em) => (
                      <button
                        key={em}
                        type="button"
                        className="rounded p-1 text-lg leading-none hover:bg-accent"
                        onClick={() => {
                          react.mutate({ id: msg.id, emoji: em });
                          setPickerOpen(false);
                        }}
                      >
                        {em}
                      </button>
                    ))}
                  </div>
                </PopoverContent>
              </Popover>
              <ActionButton label={tx('Reply')} onClick={() => onReply(msg)}>
                <Reply className="h-3.5 w-3.5" />
              </ActionButton>
            </>
          )}
          <ActionButton
            label={msg.starred ? tx('Unstar') : tx('Star')}
            onClick={() => star.mutate({ id: msg.id, conversationId: msg.conversationId })}
          >
            <Star className={`h-3.5 w-3.5 ${msg.starred ? 'fill-amber-400 text-amber-400' : ''}`} />
          </ActionButton>
          {canPost && (
            <ActionButton label={msg.pinnedAt ? tx('Unpin') : tx('Pin')} onClick={() => pin.mutate(msg.id)}>
              <Pin className={`h-3.5 w-3.5 ${msg.pinnedAt ? 'fill-current' : ''}`} />
            </ActionButton>
          )}
          {mine && canPost && (
            <ActionButton
              label={tx('Edit')}
              onClick={() => {
                setDraft(msg.body);
                setEditing(true);
              }}
            >
              <Pencil className="h-3.5 w-3.5" />
            </ActionButton>
          )}
          {(mine || canModerate) && (
            <ActionButton label={tx('Delete')} onClick={remove} danger>
              <Trash2 className="h-3.5 w-3.5" />
            </ActionButton>
          )}
        </div>
      )}
    </div>
  );
}

function ActionButton({
  label,
  onClick,
  children,
  danger,
}: {
  label: string;
  onClick: () => void;
  children: ReactNode;
  danger?: boolean;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      title={label}
      aria-label={label}
      className={`rounded p-1 text-muted-foreground hover:bg-accent ${danger ? 'hover:text-destructive' : 'hover:text-foreground'}`}
    >
      {children}
    </button>
  );
}
