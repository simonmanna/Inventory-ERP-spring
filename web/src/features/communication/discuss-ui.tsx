import { Fragment, type ReactNode } from 'react';
import { FileText } from 'lucide-react';
import { resolveAssetUrl } from '@/lib/api';
import { tx } from '@/lib/i18n/tx';
import type { DiscussAttachment } from './discuss-types';
import { fileSize, initialsOf } from './discuss-format';

const PALETTE = [
  'bg-sky-500/15 text-sky-700 dark:text-sky-300',
  'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300',
  'bg-amber-500/15 text-amber-700 dark:text-amber-300',
  'bg-rose-500/15 text-rose-700 dark:text-rose-300',
  'bg-violet-500/15 text-violet-700 dark:text-violet-300',
  'bg-teal-500/15 text-teal-700 dark:text-teal-300',
  'bg-orange-500/15 text-orange-700 dark:text-orange-300',
  'bg-indigo-500/15 text-indigo-700 dark:text-indigo-300',
];

function hash(s: string): number {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0;
  return Math.abs(h);
}

export function Avatar({
  id,
  name,
  initials,
  size = 'md',
  online,
}: {
  id: string;
  name: string;
  initials?: string;
  size?: 'sm' | 'md' | 'lg';
  online?: boolean;
}) {
  const dim = size === 'sm' ? 'h-6 w-6 text-[10px]' : size === 'lg' ? 'h-10 w-10 text-sm' : 'h-8 w-8 text-xs';
  return (
    <span className="relative inline-flex shrink-0" title={name}>
      <span className={`flex items-center justify-center rounded-full font-semibold ${dim} ${PALETTE[hash(id) % PALETTE.length]}`}>
        {initials || initialsOf(name)}
      </span>
      {online !== undefined && <PresenceDot online={online} className="absolute -bottom-0.5 -right-0.5" />}
    </span>
  );
}

export function PresenceDot({ online, className = '' }: { online: boolean; className?: string }) {
  return (
    <span
      className={`inline-block h-2.5 w-2.5 rounded-full border-2 border-background ${online ? 'bg-emerald-500' : 'bg-muted-foreground/40'} ${className}`}
      aria-label={online ? tx('Online') : tx('Offline')}
    />
  );
}

const URL_RE = /(https?:\/\/[^\s<]+[^\s<.,;:!?)\]'"])/g;

function linkify(text: string, keyBase: string): ReactNode[] {
  const out: ReactNode[] = [];
  let last = 0;
  let i = 0;
  for (const m of text.matchAll(URL_RE)) {
    const idx = m.index ?? 0;
    if (idx > last) out.push(text.slice(last, idx));
    out.push(
      <a key={`${keyBase}-u${i++}`} href={m[0]} target="_blank" rel="noopener noreferrer" className="text-primary underline underline-offset-2">
        {m[0]}
      </a>,
    );
    last = idx + m[0].length;
  }
  if (last < text.length) out.push(text.slice(last));
  return out;
}

function escapeRe(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** Message text with `@Name` mentions highlighted (mine stronger) and URLs linked. Plain text only, never HTML. */
export function MessageBody({
  body,
  mentions,
  meId,
}: {
  body: string;
  mentions: { userId: string; name: string }[];
  meId?: string;
}) {
  if (mentions.length === 0) return <>{linkify(body, 'b')}</>;
  const names = [...mentions].sort((a, b) => b.name.length - a.name.length);
  const re = new RegExp(`@(${names.map((m) => escapeRe(m.name)).join('|')})`, 'g');
  const parts: ReactNode[] = [];
  let last = 0;
  let i = 0;
  for (const m of body.matchAll(re)) {
    const idx = m.index ?? 0;
    if (idx > last) parts.push(<Fragment key={`t${i}`}>{linkify(body.slice(last, idx), `t${i}`)}</Fragment>);
    const who = names.find((n) => n.name === m[1]);
    const mine = !!who && who.userId === meId;
    parts.push(
      <span
        key={`m${i}`}
        className={`rounded px-0.5 font-medium ${mine ? 'bg-amber-400/30 text-foreground' : 'bg-primary/10 text-primary'}`}
      >
        {m[0]}
      </span>,
    );
    last = idx + m[0].length;
    i++;
  }
  if (last < body.length) parts.push(<Fragment key="end">{linkify(body.slice(last), 'end')}</Fragment>);
  return <>{parts}</>;
}

export function AttachmentList({ attachments }: { attachments: DiscussAttachment[] }) {
  if (attachments.length === 0) return null;
  const images = attachments.filter((a) => a.kind === 'image');
  const files = attachments.filter((a) => a.kind !== 'image');
  return (
    <div className="mt-1.5 space-y-1.5">
      {images.length > 0 && (
        <div className="flex flex-wrap gap-2">
          {images.map((a) => {
            const src = resolveAssetUrl(`${a.url}${a.url.includes('?') ? '&' : '?'}inline=true`);
            return (
              <a key={a.id} href={src} target="_blank" rel="noopener noreferrer" title={a.filename}>
                <img
                  src={src}
                  alt={a.filename}
                  loading="lazy"
                  className="h-32 max-w-[16rem] rounded-lg border border-border object-cover hover:opacity-90"
                />
              </a>
            );
          })}
        </div>
      )}
      {files.map((a) => (
        <a
          key={a.id}
          href={resolveAssetUrl(a.url)}
          target="_blank"
          rel="noopener noreferrer"
          className="flex w-fit max-w-full items-center gap-2 rounded-lg border border-border bg-background px-2.5 py-1.5 text-xs hover:bg-accent"
        >
          <FileText className="h-4 w-4 shrink-0 text-muted-foreground" />
          <span className="truncate font-medium">{a.filename}</span>
          <span className="shrink-0 text-muted-foreground">{fileSize(a.byteSize)}</span>
        </a>
      ))}
    </div>
  );
}
