import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { Loader2, Paperclip, Reply, Send, Smile, X } from 'lucide-react';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { apiMessage, notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { sendTyping, uploadAttachment } from './discuss-api';
import type { DirectoryUser, UploadedAttachment } from './discuss-types';
import { Avatar } from './discuss-ui';
import { EMOJIS, fileSize } from './discuss-format';

export interface ComposerSendInput {
  body: string;
  mentionUserIds: string[];
  attachmentIds: string[];
}

interface PendingFile {
  key: string;
  name: string;
  size: number;
  uploaded?: UploadedAttachment;
  failed?: boolean;
}

interface Props {
  users: DirectoryUser[];
  onSend: (input: ComposerSendInput) => Promise<unknown>;
  placeholder?: string;
  /** When set, typing signals go to this conversation. */
  conversationId?: string;
  replyTo?: { id: string; authorName: string | null; body: string } | null;
  onCancelReply?: () => void;
  disabled?: boolean;
  disabledReason?: string;
  autoFocus?: boolean;
  /** Visual accent for log notes. */
  tone?: 'default' | 'note';
  meId?: string;
}

const MAX_ATTACHMENTS = 10;
const MENTION_RE = /(?:^|\s)@([^\s@]{0,30})$/;

/** Message composer: @mention autocomplete, emoji picker, attachments, reply banner, typing signal. */
export function DiscussComposer({
  users,
  onSend,
  placeholder,
  conversationId,
  replyTo,
  onCancelReply,
  disabled,
  disabledReason,
  autoFocus,
  tone = 'default',
  meId,
}: Props) {
  const [text, setText] = useState('');
  const [mentions, setMentions] = useState<{ id: string; name: string }[]>([]);
  const [files, setFiles] = useState<PendingFile[]>([]);
  const [query, setQuery] = useState<string | null>(null);
  const [highlight, setHighlight] = useState(0);
  const [sending, setSending] = useState(false);
  const [emojiOpen, setEmojiOpen] = useState(false);
  const ref = useRef<HTMLTextAreaElement>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const lastTyping = useRef(0);

  useEffect(() => {
    if (autoFocus) ref.current?.focus();
  }, [autoFocus, conversationId]);

  useEffect(() => {
    if (replyTo) ref.current?.focus();
  }, [replyTo]);

  // Reset the draft when switching conversations.
  useEffect(() => {
    setText('');
    setMentions([]);
    setFiles([]);
    setQuery(null);
  }, [conversationId]);

  // Auto-grow up to ~8 lines.
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, 200)}px`;
  }, [text]);

  const candidates = useMemo(() => {
    if (query === null) return [];
    const q = query.toLowerCase();
    return users
      .filter((u) => u.id !== meId)
      .filter((u) => u.name.toLowerCase().includes(q) || (u.email ?? '').toLowerCase().startsWith(q))
      .slice(0, 8);
  }, [query, users, meId]);

  const stopTyping = () => {
    if (conversationId && lastTyping.current > 0) {
      sendTyping(conversationId, false);
      lastTyping.current = 0;
    }
  };

  const onChange = (value: string) => {
    setText(value);
    const el = ref.current;
    const caret = el ? el.selectionStart : value.length;
    const m = MENTION_RE.exec(value.slice(0, caret));
    setQuery(m ? m[1] : null);
    setHighlight(0);
    if (conversationId && value.trim() && Date.now() - lastTyping.current > 3000) {
      sendTyping(conversationId, true);
      lastTyping.current = Date.now();
    }
  };

  const insertAtCaret = (insert: string, replaceBack = 0) => {
    const el = ref.current;
    const caret = el ? el.selectionStart : text.length;
    const before = text.slice(0, caret - replaceBack);
    const after = text.slice(caret);
    const next = before + insert + after;
    setText(next);
    requestAnimationFrame(() => {
      if (!el) return;
      el.focus();
      const pos = before.length + insert.length;
      el.setSelectionRange(pos, pos);
    });
  };

  const pickMention = (u: DirectoryUser) => {
    insertAtCaret(`@${u.name} `, (query ?? '').length + 1);
    setMentions((prev) => (prev.some((p) => p.id === u.id) ? prev : [...prev, { id: u.id, name: u.name }]));
    setQuery(null);
  };

  const addFiles = async (list: FileList | null) => {
    if (!list) return;
    const room = MAX_ATTACHMENTS - files.filter((f) => !f.failed).length;
    const picked = Array.from(list);
    if (picked.length > room) notify.warning(tx('A message can have at most {{max}} attachments.', { max: MAX_ATTACHMENTS }));
    for (const file of picked.slice(0, Math.max(0, room))) {
      const key = `${file.name}-${file.size}-${Math.random().toString(36).slice(2)}`;
      setFiles((prev) => [...prev, { key, name: file.name, size: file.size }]);
      try {
        const uploaded = await uploadAttachment(file);
        setFiles((prev) => prev.map((f) => (f.key === key ? { ...f, uploaded } : f)));
      } catch (e) {
        notify.error(apiMessage(e, tx('Upload failed')), file.name);
        setFiles((prev) => prev.map((f) => (f.key === key ? { ...f, failed: true } : f)));
      }
    }
    if (fileInput.current) fileInput.current.value = '';
  };

  const uploading = files.some((f) => !f.uploaded && !f.failed);
  const ready = files.filter((f) => f.uploaded);
  const canSend = !disabled && !sending && !uploading && (text.trim().length > 0 || ready.length > 0);

  const submit = async () => {
    if (!canSend) return;
    const body = text.trim();
    const mentionUserIds = mentions.filter((m) => body.includes(`@${m.name}`)).map((m) => m.id);
    setSending(true);
    try {
      await onSend({ body, mentionUserIds, attachmentIds: ready.map((f) => f.uploaded!.id) });
      setText('');
      setMentions([]);
      setFiles([]);
      stopTyping();
    } catch {
      /* the mutation already showed the error; keep the draft */
    } finally {
      setSending(false);
      ref.current?.focus();
    }
  };

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (candidates.length > 0) {
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setHighlight((h) => (h + 1) % candidates.length);
        return;
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault();
        setHighlight((h) => (h - 1 + candidates.length) % candidates.length);
        return;
      }
      if (e.key === 'Enter' || e.key === 'Tab') {
        e.preventDefault();
        pickMention(candidates[highlight]);
        return;
      }
      if (e.key === 'Escape') {
        setQuery(null);
        return;
      }
    }
    if (e.key === 'Escape' && replyTo && onCancelReply) {
      onCancelReply();
      return;
    }
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      void submit();
    }
  };

  if (disabled && disabledReason) {
    return (
      <div className="border-t border-border px-4 py-3 text-center text-sm text-muted-foreground">{disabledReason}</div>
    );
  }

  return (
    <div className="relative border-t border-border p-3">
      {replyTo && (
        <div className="mb-2 flex items-start gap-2 rounded-md border-l-2 border-primary bg-muted/60 px-2 py-1.5 text-xs">
          <Reply className="mt-0.5 h-3.5 w-3.5 shrink-0 text-muted-foreground" />
          <div className="min-w-0 flex-1">
            <div className="font-medium">{tx('Replying to {{name}}', { name: replyTo.authorName ?? tx('System') })}</div>
            <div className="truncate text-muted-foreground">{replyTo.body}</div>
          </div>
          <button type="button" onClick={onCancelReply} aria-label={tx('Cancel reply')} className="rounded p-0.5 hover:bg-accent">
            <X className="h-3.5 w-3.5" />
          </button>
        </div>
      )}

      {files.length > 0 && (
        <div className="mb-2 flex flex-wrap gap-1.5">
          {files.map((f) => (
            <span
              key={f.key}
              className={`flex max-w-[14rem] items-center gap-1.5 rounded-md border px-2 py-1 text-xs ${
                f.failed ? 'border-destructive/50 text-destructive' : 'border-border bg-muted/50'
              }`}
            >
              {!f.uploaded && !f.failed && <Loader2 className="h-3 w-3 animate-spin" />}
              <span className="truncate">{f.name}</span>
              <span className="shrink-0 text-muted-foreground">{fileSize(f.size)}</span>
              <button
                type="button"
                onClick={() => setFiles((prev) => prev.filter((p) => p.key !== f.key))}
                aria-label={tx('Remove attachment')}
                className="rounded hover:bg-accent"
              >
                <X className="h-3 w-3" />
              </button>
            </span>
          ))}
        </div>
      )}

      {candidates.length > 0 && (
        <div className="absolute bottom-full left-3 z-20 mb-1 w-72 overflow-hidden rounded-lg border border-border bg-popover shadow-lg">
          {candidates.map((u, i) => (
            <button
              key={u.id}
              type="button"
              onMouseDown={(e) => {
                e.preventDefault();
                pickMention(u);
              }}
              className={`flex w-full items-center gap-2 px-3 py-1.5 text-left text-sm ${i === highlight ? 'bg-accent' : ''}`}
            >
              <Avatar id={u.id} name={u.name} initials={u.initials} size="sm" />
              <span className="truncate">{u.name}</span>
              {u.departmentName && <span className="ml-auto truncate text-xs text-muted-foreground">{u.departmentName}</span>}
            </button>
          ))}
        </div>
      )}

      <div
        className={`flex items-end gap-1 rounded-lg border bg-background px-1.5 py-1 focus-within:ring-1 focus-within:ring-ring ${
          tone === 'note' ? 'border-amber-400/60 bg-amber-50/40 dark:bg-amber-950/10' : 'border-input'
        }`}
      >
        <button
          type="button"
          onClick={() => fileInput.current?.click()}
          className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground"
          aria-label={tx('Attach files')}
          title={tx('Attach files')}
        >
          <Paperclip className="h-4 w-4" />
        </button>
        <input ref={fileInput} type="file" multiple className="hidden" onChange={(e) => void addFiles(e.target.files)} />
        <textarea
          ref={ref}
          value={text}
          onChange={(e) => onChange(e.target.value)}
          onKeyDown={onKeyDown}
          onBlur={() => {
            stopTyping();
            setTimeout(() => setQuery(null), 150);
          }}
          onPaste={(e) => {
            if (e.clipboardData.files.length > 0) {
              e.preventDefault();
              void addFiles(e.clipboardData.files);
            }
          }}
          rows={1}
          placeholder={placeholder ?? tx('Write a message… (@ to mention, Enter to send)')}
          className="max-h-[200px] min-h-[2rem] flex-1 resize-none bg-transparent px-1 py-1.5 text-sm outline-none"
        />
        <Popover open={emojiOpen} onOpenChange={setEmojiOpen}>
          <PopoverTrigger asChild>
            <button
              type="button"
              className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground"
              aria-label={tx('Insert emoji')}
            >
              <Smile className="h-4 w-4" />
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
                    insertAtCaret(em);
                    setEmojiOpen(false);
                  }}
                >
                  {em}
                </button>
              ))}
            </div>
          </PopoverContent>
        </Popover>
        <button
          type="button"
          onClick={() => void submit()}
          disabled={!canSend}
          className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-primary text-primary-foreground disabled:opacity-40"
          aria-label={tx('Send')}
        >
          {sending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
        </button>
      </div>
    </div>
  );
}
