import { useMemo, type ReactNode } from 'react';
import { NavLink } from 'react-router-dom';
import { Check, ExternalLink, Paperclip, type LucideIcon } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { StatusPill } from '@/components/list/list-page';
import { Label } from '@/components/ui/label';
import { SearchableSelect } from '@/components/ui/searchable-select';
import { useAuthStore } from '@/stores/auth.store';
import { useProductOptions } from '@/features/manufacturing/options';
import {
  openPlmFile, useOrgUsers, type ChangeStatus, type ChangeType, type DocStatus, type LifecycleState, type Priority,
  type RequestStatus,
} from '@/features/manufacturing/api-plm';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import {
  CHANGE_STATUS, CHANGE_TYPE, DOC_STATUS, LIFECYCLE, LIFECYCLE_ORDER, PRIORITY, REQUEST_STATUS, selectClass,
} from './vocab';

// ---- pills -----------------------------------------------------------------------------------------------------------

export function LifecyclePill({ state }: { state?: LifecycleState | null }) {
  if (!state) return <span className="text-xs text-muted-foreground">—</span>;
  const s = LIFECYCLE[state] ?? { label: state, tone: 'neutral' as const };
  return <StatusPill tone={s.tone}>{s.label}</StatusPill>;
}

export function ChangeStatusPill({ status }: { status: ChangeStatus }) {
  const s = CHANGE_STATUS[status] ?? { label: status, tone: 'neutral' as const };
  return <StatusPill tone={s.tone}>{s.label}</StatusPill>;
}

export function RequestStatusPill({ status }: { status: RequestStatus }) {
  const s = REQUEST_STATUS[status] ?? { label: status, tone: 'neutral' as const };
  return <StatusPill tone={s.tone}>{s.label}</StatusPill>;
}

export function DocStatusPill({ status }: { status: DocStatus }) {
  const s = DOC_STATUS[status] ?? { label: status, tone: 'neutral' as const };
  return <StatusPill tone={s.tone}>{s.label}</StatusPill>;
}

export function PriorityPill({ priority, hideNormal }: { priority?: Priority | null; hideNormal?: boolean }) {
  if (!priority || (hideNormal && priority === 'normal')) return null;
  const p = PRIORITY[priority] ?? { label: priority, tone: 'neutral' as const };
  return <StatusPill tone={p.tone} dot={false}>{p.label}</StatusPill>;
}

export function ChangeTypeBadge({ type, compact }: { type: ChangeType; compact?: boolean }) {
  const t = CHANGE_TYPE[type];
  if (!t) return <span className="text-xs">{type}</span>;
  const Icon = t.icon;
  return (
    <span className={cn('inline-flex items-center gap-1 whitespace-nowrap rounded-md px-1.5 py-0.5 text-xs font-medium', t.className)} title={t.hint}>
      <Icon className="h-3 w-3" />
      {!compact && t.label}
    </span>
  );
}

export function RevisionBadge({ revision }: { revision?: string | null }) {
  if (!revision) return null;
  return (
    <span className="inline-flex items-center rounded border bg-muted/50 px-1.5 py-0.5 font-mono text-[11px] font-semibold">
      {tx('Rev')} {revision}
    </span>
  );
}

// ---- layout ----------------------------------------------------------------------------------------------------------

export function StatCard({ label, value, hint, icon: Icon, tone, onClick }: {
  label: string; value: ReactNode; hint?: ReactNode; icon?: LucideIcon; tone?: 'danger' | 'warning' | 'success'; onClick?: () => void;
}) {
  return (
    <div
      role={onClick ? 'button' : undefined}
      tabIndex={onClick ? 0 : undefined}
      onClick={onClick}
      onKeyDown={(e) => onClick && (e.key === 'Enter' || e.key === ' ') && onClick()}
      className={cn('rounded-xl border bg-card p-4 shadow-sm', onClick && 'cursor-pointer transition hover:border-primary/40 hover:shadow-md')}
    >
      <div className="flex items-center justify-between gap-2">
        <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{label}</p>
        {Icon && <Icon className="h-4 w-4 text-muted-foreground" />}
      </div>
      <p className={cn('mt-1 text-2xl font-semibold tabular-nums',
        tone === 'danger' && 'text-rose-600', tone === 'warning' && 'text-amber-600', tone === 'success' && 'text-emerald-600')}>
        {value}
      </p>
      {hint && <p className="mt-0.5 text-xs text-muted-foreground">{hint}</p>}
    </div>
  );
}

export function EmptyState({ icon: Icon, title, hint, action, compact }: {
  icon: LucideIcon; title: string; hint?: string; action?: ReactNode; compact?: boolean;
}) {
  return (
    <div className={cn('flex flex-col items-center gap-2 rounded-xl border border-dashed bg-card/50 px-6 text-center', compact ? 'py-8' : 'py-14')}>
      <Icon className="h-8 w-8 text-muted-foreground" />
      <p className="font-medium">{title}</p>
      {hint && <p className="max-w-md text-sm text-muted-foreground">{hint}</p>}
      {action && <div className="mt-2">{action}</div>}
    </div>
  );
}

export function Panel({ title, icon: Icon, actions, children, className, bodyClassName }: {
  title: ReactNode; icon?: LucideIcon; actions?: ReactNode; children: ReactNode; className?: string; bodyClassName?: string;
}) {
  return (
    <section className={cn('rounded-xl border bg-card shadow-sm', className)}>
      <header className="flex flex-wrap items-center justify-between gap-2 border-b px-4 py-3">
        <h2 className="flex items-center gap-2 text-sm font-semibold">
          {Icon && <Icon className="h-4 w-4 text-muted-foreground" />}
          {title}
        </h2>
        {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
      </header>
      <div className={cn('p-4', bodyClassName)}>{children}</div>
    </section>
  );
}

export function Field({ label, hint, children, className, required }: {
  label: string; hint?: string; children: ReactNode; className?: string; required?: boolean;
}) {
  return (
    <div className={cn('space-y-1.5', className)}>
      <Label>{label}{required && <span className="text-rose-600"> *</span>}</Label>
      {children}
      {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
    </div>
  );
}

export function TabBar<T extends string>({ tabs, value, onChange }: {
  tabs: Array<{ id: T; label: string; icon: LucideIcon; count?: number }>; value: T; onChange: (v: T) => void;
}) {
  return (
    <div className="flex gap-1 overflow-x-auto border-b">
      {tabs.map((t) => (
        <button
          key={t.id}
          type="button"
          onClick={() => onChange(t.id)}
          className={cn('-mb-px flex shrink-0 items-center gap-1.5 border-b-2 px-3 py-2 text-sm',
            value === t.id ? 'border-primary font-medium text-foreground' : 'border-transparent text-muted-foreground hover:text-foreground')}
        >
          <t.icon className="h-4 w-4" />
          {t.label}
          {t.count != null && t.count > 0 && <span className="rounded-full bg-muted px-1.5 text-[11px] text-muted-foreground">{t.count}</span>}
        </button>
      ))}
    </div>
  );
}

/** Link row between the PLM screens. */
export function PlmSubNav() {
  const has = useAuthStore((s) => s.hasPermission);
  const links = [
    { to: '/manufacturing/plm', label: tx('Overview'), end: true },
    { to: '/manufacturing/plm/products', label: tx('Products') },
    { to: '/manufacturing/plm/requests', label: tx('Change requests') },
    { to: '/manufacturing/plm/changes', label: tx('Change orders') },
    { to: '/manufacturing/plm/documents', label: tx('Documents') },
    ...(has(PERMISSIONS.plm.manage) ? [{ to: '/manufacturing/plm/settings', label: tx('Settings') }] : []),
  ];
  return (
    <nav className="flex gap-1 overflow-x-auto rounded-lg border bg-muted/40 p-0.5">
      {links.map((l) => (
        <NavLink
          key={l.to}
          to={l.to}
          end={l.end}
          className={({ isActive }) => cn('shrink-0 rounded-md px-3 py-1 text-sm',
            isActive ? 'bg-background font-medium shadow-sm' : 'text-muted-foreground hover:text-foreground')}
        >
          {l.label}
        </NavLink>
      ))}
    </nav>
  );
}

// ---- lifecycle stepper -----------------------------------------------------------------------------------------------

export function LifecycleStepper({ current, target, className }: {
  current: LifecycleState; target?: LifecycleState | null; className?: string;
}) {
  const at = LIFECYCLE_ORDER.indexOf(current);
  return (
    <ol className={cn('flex w-full items-center overflow-x-auto', className)}>
      {LIFECYCLE_ORDER.map((s, i) => {
        const meta = LIFECYCLE[s];
        const done = i < at;
        const active = i === at;
        const isTarget = target === s && !active;
        return (
          <li key={s} className="flex min-w-[96px] flex-1 items-center">
            <div className="flex flex-col items-center gap-1 px-1 text-center">
              <span
                title={meta.hint}
                className={cn('flex h-7 w-7 items-center justify-center rounded-full border-2 text-xs font-semibold transition',
                  active && cn(meta.bar, 'border-transparent text-white shadow ring-4 ring-primary/15'),
                  done && 'border-emerald-500 bg-emerald-50 text-emerald-600 dark:bg-emerald-500/15',
                  !active && !done && 'border-muted-foreground/30 text-muted-foreground',
                  isTarget && 'border-dashed border-primary text-primary')}
              >
                {done ? <Check className="h-3.5 w-3.5" /> : i + 1}
              </span>
              <span className={cn('whitespace-nowrap text-xs', active ? 'font-semibold text-foreground' : 'text-muted-foreground', isTarget && 'font-medium text-primary')}>
                {meta.label}
              </span>
            </div>
            {i < LIFECYCLE_ORDER.length - 1 && (
              <span className={cn('mb-5 h-0.5 flex-1 rounded', i < at ? 'bg-emerald-500' : 'bg-muted')} />
            )}
          </li>
        );
      })}
    </ol>
  );
}

// ---- people ----------------------------------------------------------------------------------------------------------

const AVATAR_TONES = ['bg-sky-600', 'bg-emerald-600', 'bg-violet-600', 'bg-amber-600', 'bg-rose-600', 'bg-teal-600', 'bg-indigo-600'];

export function Avatar({ name, className }: { name?: string | null; className?: string }) {
  const n = (name ?? '?').trim() || '?';
  const parts = n.split(/\s+/);
  const ini = ((parts[0]?.[0] ?? '?') + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase();
  const tone = AVATAR_TONES[[...n].reduce((a, c) => a + c.charCodeAt(0), 0) % AVATAR_TONES.length];
  return (
    <span title={name ?? undefined} className={cn('inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-[10px] font-semibold text-white', tone, className)}>
      {ini}
    </span>
  );
}

export function UserSelect({ value, onChange, placeholder, disabled, exclude = [] }: {
  value: string; onChange: (id: string) => void; placeholder?: string; disabled?: boolean; exclude?: string[];
}) {
  const { data: users = [] } = useOrgUsers();
  return (
    <select className={selectClass} value={value} disabled={disabled} onChange={(e) => onChange(e.target.value)}>
      <option value="">{placeholder ?? tx('Nobody')}</option>
      {users.filter((u) => (u.isActive || u.id === value) && !exclude.includes(u.id)).map((u) => (
        <option key={u.id} value={u.id}>{u.name}</option>
      ))}
    </select>
  );
}

// ---- products --------------------------------------------------------------------------------------------------------

export function ProductPicker({ value, onChange, exclude, placeholder, clearable, disabled }: {
  value: string; onChange: (id: string) => void; exclude?: Set<string>; placeholder?: string; clearable?: boolean; disabled?: boolean;
}) {
  const { data: products = [], isLoading } = useProductOptions();
  const options = useMemo(
    () => products
      .filter((p) => !exclude?.has(p.id) || p.id === value)
      .map((p) => ({ value: p.id, label: p.name, code: p.code })),
    [products, exclude, value],
  );
  return (
    <SearchableSelect
      value={value}
      onValueChange={onChange}
      options={options}
      loading={isLoading}
      disabled={disabled}
      placeholder={placeholder ?? tx('Select a product…')}
      searchPlaceholder={tx('Search code or name…')}
      clearLabel={clearable ? tx('No product') : undefined}
    />
  );
}

export function ProductLabel({ product, fallback }: { product?: { code: string; name: string } | null; fallback?: string | null }) {
  if (!product) return <span className="text-muted-foreground">{fallback ?? '—'}</span>;
  return (
    <span className="inline-flex min-w-0 items-baseline gap-1.5">
      <span className="font-mono text-xs text-muted-foreground">{product.code}</span>
      <span className="truncate">{product.name}</span>
    </span>
  );
}

// ---- files -----------------------------------------------------------------------------------------------------------

export function FileLink({ fileId, url, fileName }: { fileId?: string | null; url?: string | null; fileName?: string | null }) {
  if (fileId) {
    return (
      <button
        type="button"
        onClick={(e) => { e.stopPropagation(); void openPlmFile(fileId); }}
        className="inline-flex max-w-[220px] items-center gap-1 text-sm text-primary hover:underline"
        title={fileName ?? undefined}
      >
        <Paperclip className="h-3.5 w-3.5 shrink-0" />
        <span className="truncate">{fileName || tx('Open file')}</span>
      </button>
    );
  }
  if (url) {
    return (
      <a href={url} target="_blank" rel="noreferrer" onClick={(e) => e.stopPropagation()} className="inline-flex max-w-[220px] items-center gap-1 text-sm text-primary hover:underline">
        <ExternalLink className="h-3.5 w-3.5 shrink-0" />
        <span className="truncate">{fileName || url.replace(/^https?:\/\//, '')}</span>
      </a>
    );
  }
  return <span className="text-xs text-muted-foreground">—</span>;
}

// ---- multi-select ----------------------------------------------------------------------------------------------------

export function CheckList({ options, value, onChange, empty }: {
  options: Array<{ id: string; label: ReactNode; hint?: ReactNode }>; value: string[]; onChange: (ids: string[]) => void; empty: string;
}) {
  if (!options.length) return <p className="rounded-md border border-dashed px-3 py-4 text-center text-sm text-muted-foreground">{empty}</p>;
  const toggle = (id: string) => onChange(value.includes(id) ? value.filter((v) => v !== id) : [...value, id]);
  return (
    <div className="max-h-56 divide-y overflow-y-auto rounded-md border">
      {options.map((o) => (
        <label key={o.id} className="flex cursor-pointer items-start gap-2 px-3 py-2 text-sm hover:bg-muted/50">
          <input type="checkbox" className="mt-0.5 h-4 w-4 accent-primary" checked={value.includes(o.id)} onChange={() => toggle(o.id)} />
          <span className="min-w-0 flex-1">
            <span className="block truncate">{o.label}</span>
            {o.hint && <span className="block text-xs text-muted-foreground">{o.hint}</span>}
          </span>
        </label>
      ))}
    </div>
  );
}
