import { useMemo, useState, type ReactNode } from 'react';
import { Check, ChevronDown, Flag, Search, X } from 'lucide-react';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { StatusPill, type Tone } from '@/components/list/list-page';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import type { Priority, ProjectStatus, UpdateStatus, UserRef, TagRef } from './api';

/** Colour index (0 = none) used by projects, tasks and tags. */
export const PM_COLORS = [
  { bg: 'bg-slate-300', text: 'text-slate-700', border: 'border-l-transparent' },
  { bg: 'bg-rose-500', text: 'text-rose-700', border: 'border-l-rose-500' },
  { bg: 'bg-orange-500', text: 'text-orange-700', border: 'border-l-orange-500' },
  { bg: 'bg-amber-400', text: 'text-amber-700', border: 'border-l-amber-400' },
  { bg: 'bg-lime-500', text: 'text-lime-700', border: 'border-l-lime-500' },
  { bg: 'bg-emerald-500', text: 'text-emerald-700', border: 'border-l-emerald-500' },
  { bg: 'bg-teal-500', text: 'text-teal-700', border: 'border-l-teal-500' },
  { bg: 'bg-sky-500', text: 'text-sky-700', border: 'border-l-sky-500' },
  { bg: 'bg-blue-600', text: 'text-blue-700', border: 'border-l-blue-600' },
  { bg: 'bg-violet-500', text: 'text-violet-700', border: 'border-l-violet-500' },
  { bg: 'bg-fuchsia-500', text: 'text-fuchsia-700', border: 'border-l-fuchsia-500' },
  { bg: 'bg-pink-400', text: 'text-pink-700', border: 'border-l-pink-400' },
];

export const pmColor = (i: number | null | undefined) => PM_COLORS[(i ?? 0) % PM_COLORS.length] ?? PM_COLORS[0];

export function ColorPicker({ value, onChange }: { value: number; onChange: (v: number) => void }) {
  return (
    <div className="flex flex-wrap gap-1.5">
      {PM_COLORS.map((c, i) => (
        <button
          key={i}
          type="button"
          title={i === 0 ? tx('No colour') : undefined}
          onClick={() => onChange(i)}
          className={cn('h-6 w-6 rounded-full ring-offset-2 ring-offset-background', c.bg, value === i && 'ring-2 ring-primary')}
        />
      ))}
    </div>
  );
}

export const selectClass = 'h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm';

// ---- status vocabularies ----------------------------------------------------------------------------------------------

export const PROJECT_STATUS: Record<ProjectStatus, { label: string; tone: Tone }> = {
  draft: { label: tx('Draft'), tone: 'neutral' },
  active: { label: tx('Active'), tone: 'info' },
  on_hold: { label: tx('On hold'), tone: 'warning' },
  completed: { label: tx('Completed'), tone: 'success' },
  closed: { label: tx('Closed'), tone: 'neutral' },
  cancelled: { label: tx('Cancelled'), tone: 'danger' },
};

export const UPDATE_STATUS: Record<UpdateStatus, { label: string; tone: Tone }> = {
  on_track: { label: tx('On track'), tone: 'success' },
  at_risk: { label: tx('At risk'), tone: 'warning' },
  off_track: { label: tx('Off track'), tone: 'danger' },
  on_hold: { label: tx('On hold'), tone: 'neutral' },
  done: { label: tx('Done'), tone: 'accent' },
};

export const PRIORITY: Record<Priority, { label: string; className: string }> = {
  low: { label: tx('Low'), className: 'text-slate-400' },
  normal: { label: tx('Normal'), className: 'text-sky-500' },
  high: { label: tx('High'), className: 'text-amber-500' },
  urgent: { label: tx('Urgent'), className: 'text-rose-600' },
};

export function ProjectStatusPill({ status }: { status: ProjectStatus }) {
  const s = PROJECT_STATUS[status] ?? { label: status, tone: 'neutral' as Tone };
  return <StatusPill tone={s.tone}>{s.label}</StatusPill>;
}

export function UpdateStatusPill({ status }: { status?: UpdateStatus | null }) {
  if (!status) return null;
  const s = UPDATE_STATUS[status];
  return <StatusPill tone={s.tone}>{s.label}</StatusPill>;
}

export function PriorityFlag({ priority, className }: { priority: Priority; className?: string }) {
  const p = PRIORITY[priority] ?? PRIORITY.normal;
  if (priority === 'normal') return null;
  return <Flag aria-label={p.label} className={cn('h-3.5 w-3.5 fill-current', p.className, className)} />;
}

// ---- people -----------------------------------------------------------------------------------------------------------

const AVATAR_TONES = ['bg-sky-600', 'bg-emerald-600', 'bg-violet-600', 'bg-amber-600', 'bg-rose-600', 'bg-teal-600', 'bg-indigo-600'];

function initials(name?: string | null) {
  const parts = (name ?? '?').trim().split(/\s+/).filter(Boolean);
  return ((parts[0]?.[0] ?? '?') + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase();
}

export function Avatar({ user, size = 'sm' }: { user: { id: string; name?: string | null }; size?: 'sm' | 'md' }) {
  const tone = AVATAR_TONES[[...user.id].reduce((a, c) => a + c.charCodeAt(0), 0) % AVATAR_TONES.length];
  return (
    <span
      title={user.name ?? undefined}
      className={cn(
        'inline-flex shrink-0 items-center justify-center rounded-full font-semibold text-white ring-2 ring-background',
        tone,
        size === 'sm' ? 'h-6 w-6 text-[10px]' : 'h-8 w-8 text-xs',
      )}
    >
      {initials(user.name)}
    </span>
  );
}

export function AvatarStack({ users, max = 3 }: { users: UserRef[]; max?: number }) {
  if (!users.length) return null;
  return (
    <span className="flex -space-x-1.5">
      {users.slice(0, max).map((u) => <Avatar key={u.id} user={u} />)}
      {users.length > max && (
        <span className="inline-flex h-6 w-6 items-center justify-center rounded-full bg-muted text-[10px] font-semibold ring-2 ring-background">
          +{users.length - max}
        </span>
      )}
    </span>
  );
}

export function TagChip({ tag, onRemove }: { tag: TagRef; onRemove?: () => void }) {
  const c = pmColor(tag.color);
  return (
    <span className="inline-flex items-center gap-1 rounded-full border bg-background px-2 py-0.5 text-[11px] font-medium">
      <span className={cn('h-2 w-2 rounded-full', c.bg)} />
      {tag.name}
      {onRemove && (
        <button type="button" onClick={onRemove} className="text-muted-foreground hover:text-foreground" aria-label={tx('Remove')}>
          <X className="h-3 w-3" />
        </button>
      )}
    </span>
  );
}

/** Pick several options from a searchable list (assignees, members, tags). */
export function MultiPicker<T extends { id: string; name: string }>({
  options, value, onChange, placeholder, renderOption, disabled, trigger,
}: {
  options: T[];
  value: string[];
  onChange: (ids: string[]) => void;
  placeholder: string;
  renderOption?: (o: T) => ReactNode;
  disabled?: boolean;
  trigger?: ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState('');
  const filtered = useMemo(
    () => options.filter((o) => o.name.toLowerCase().includes(q.trim().toLowerCase())),
    [options, q],
  );
  const selected = options.filter((o) => value.includes(o.id));
  const toggle = (id: string) => onChange(value.includes(id) ? value.filter((v) => v !== id) : [...value, id]);
  return (
    <Popover open={open} onOpenChange={(o) => { setOpen(o); if (!o) setQ(''); }}>
      <PopoverTrigger asChild disabled={disabled}>
        {trigger ?? (
          <button
            type="button"
            className="flex min-h-9 w-full items-center justify-between gap-2 rounded-md border border-input bg-background px-3 py-1 text-left text-sm disabled:opacity-60"
          >
            <span className={cn('truncate', !selected.length && 'text-muted-foreground')}>
              {selected.length ? selected.map((s) => s.name).join(', ') : placeholder}
            </span>
            <ChevronDown className="h-4 w-4 shrink-0 opacity-50" />
          </button>
        )}
      </PopoverTrigger>
      <PopoverContent className="w-72 p-0" align="start">
        <div className="flex items-center gap-2 border-b px-3">
          <Search className="h-4 w-4 text-muted-foreground" />
          <input
            autoFocus
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder={tx('Search…')}
            className="h-9 w-full bg-transparent text-sm outline-none"
          />
        </div>
        <div className="max-h-64 overflow-y-auto p-1">
          {filtered.length === 0 && <p className="px-2 py-3 text-center text-sm text-muted-foreground">{tx('No matches found')}</p>}
          {filtered.map((o) => (
            <button
              key={o.id}
              type="button"
              onClick={() => toggle(o.id)}
              className="flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-sm hover:bg-muted"
            >
              <span className={cn('flex h-4 w-4 items-center justify-center rounded border', value.includes(o.id) && 'border-primary bg-primary text-primary-foreground')}>
                {value.includes(o.id) && <Check className="h-3 w-3" />}
              </span>
              {renderOption ? renderOption(o) : <span className="truncate">{o.name}</span>}
            </button>
          ))}
        </div>
      </PopoverContent>
    </Popover>
  );
}

export function ProgressBar({ value, tone = 'bg-primary', className }: { value: number; tone?: string; className?: string }) {
  const v = Math.max(0, Math.min(100, value));
  return (
    <div className={cn('h-1.5 w-full overflow-hidden rounded-full bg-muted', className)}>
      <div className={cn('h-full rounded-full transition-all', tone)} style={{ width: `${v}%` }} />
    </div>
  );
}

export function StatCard({ label, value, hint, tone }: { label: string; value: ReactNode; hint?: ReactNode; tone?: 'danger' | 'warning' | 'success' }) {
  return (
    <div className="rounded-xl border bg-card p-4 shadow-sm">
      <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{label}</p>
      <p className={cn('mt-1 text-2xl font-semibold tabular-nums',
        tone === 'danger' && 'text-rose-600', tone === 'warning' && 'text-amber-600', tone === 'success' && 'text-emerald-600')}>
        {value}
      </p>
      {hint && <p className="mt-0.5 text-xs text-muted-foreground">{hint}</p>}
    </div>
  );
}

export function EmptyState({ icon: Icon, title, hint, action }: { icon: typeof Flag; title: string; hint?: string; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center gap-2 rounded-xl border border-dashed bg-card/50 px-6 py-14 text-center">
      <Icon className="h-8 w-8 text-muted-foreground" />
      <p className="font-medium">{title}</p>
      {hint && <p className="max-w-md text-sm text-muted-foreground">{hint}</p>}
      {action && <div className="mt-2">{action}</div>}
    </div>
  );
}

/** Hours with up to two decimals, no trailing zeros ("1.5 h"). */
export function hours(v: number | string | null | undefined, unit = true): string {
  const n = v == null || v === '' ? 0 : Number(v);
  const s = Number.isInteger(n) ? String(n) : n.toFixed(2).replace(/0+$/, '').replace(/\.$/, '');
  return unit ? `${s} h` : s;
}

/** Short day label of an ISO date ("Mon 3"). */
export function shortDay(iso: string): string {
  const d = new Date(`${iso}T00:00:00`);
  return d.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric' });
}

export function shortDate(iso?: string | null): string {
  if (!iso) return '';
  const d = new Date(iso.length === 10 ? `${iso}T00:00:00` : iso);
  return d.toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
}

export function todayIso(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

export function addDaysIso(iso: string, days: number): string {
  const d = new Date(`${iso}T00:00:00`);
  d.setDate(d.getDate() + days);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/** "1:05:09" from seconds. */
export function clock(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}
