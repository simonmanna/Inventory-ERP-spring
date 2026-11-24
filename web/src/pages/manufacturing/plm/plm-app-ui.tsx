import { useState } from 'react';
import { Check, Plus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { StatusPill } from '@/components/list/list-page';
import { Input } from '@/components/ui/input';
import { useAuthStore } from '@/stores/auth.store';
import type { FaiStatus, KanbanState, TagRef } from '@/features/manufacturing/api-plm';
import { FAI_STATUS, useChangeTypes, useCreateTag, usePlmTags } from '@/features/manufacturing/api-plm-app';
import { notify } from '@/lib/notify';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { selectClass } from './vocab';

/** Small shared pieces of the PLM application screens (ADR-S086). */

export function ColorDot({ color, className }: { color?: string | null; className?: string }) {
  return (
    <span className={cn('inline-block h-2.5 w-2.5 shrink-0 rounded-full ring-1 ring-black/10', className)}
      style={{ backgroundColor: color || '#94a3b8' }} />
  );
}

/** ECO type label with its colour. */
export function ChangeTypeChip({ name, color, className }: { name?: string | null; color?: string | null; className?: string }) {
  if (!name) return null;
  return (
    <span className={cn('inline-flex items-center gap-1.5 rounded-md border bg-background px-1.5 py-0.5 text-[11px] font-medium', className)}>
      <ColorDot color={color} className="h-2 w-2" />{name}
    </span>
  );
}

export function TagChips({ tags, max, className }: { tags?: TagRef[]; max?: number; className?: string }) {
  if (!tags?.length) return null;
  const shown = max ? tags.slice(0, max) : tags;
  return (
    <span className={cn('inline-flex flex-wrap items-center gap-1', className)}>
      {shown.map((t) => (
        <span key={t.id} className="inline-flex items-center gap-1 rounded-full px-1.5 py-0.5 text-[10px] font-medium"
          style={{ backgroundColor: `${t.color || '#94a3b8'}26`, color: 'inherit' }}>
          <ColorDot color={t.color} className="h-1.5 w-1.5" />{t.name}
        </span>
      ))}
      {max && tags.length > max && <span className="text-[10px] text-muted-foreground">+{tags.length - max}</span>}
    </span>
  );
}

const KANBAN: Record<KanbanState, { label: string; dot: string }> = {
  normal: { label: 'In progress', dot: 'bg-slate-300 dark:bg-slate-600' },
  blocked: { label: 'Blocked', dot: 'bg-rose-500' },
  ready: { label: 'Ready for the next stage', dot: 'bg-emerald-500' },
};

export function KanbanDot({ state, reason, className }: { state?: KanbanState | null; reason?: string | null; className?: string }) {
  const k = KANBAN[state ?? 'normal'];
  return (
    <span title={tx(k.label) + (reason ? `: ${reason}` : '')} className={cn('inline-block h-2.5 w-2.5 shrink-0 rounded-full', k.dot, className)} />
  );
}

export const KANBAN_STATES = KANBAN;

export function FaiPill({ status }: { status?: FaiStatus | null }) {
  if (!status) return null;
  const meta = FAI_STATUS[status];
  return <StatusPill tone={meta?.tone ?? 'neutral'}>{tx('First article')}: {tx(meta?.label ?? status)}</StatusPill>;
}

/** ECO type picker (the default type first). */
export function ChangeTypeSelect({ value, onChange, allowAny, className }: {
  value: string; onChange: (v: string) => void; allowAny?: string; className?: string;
}) {
  const { data: types = [] } = useChangeTypes();
  return (
    <select className={cn(selectClass, className)} value={value} onChange={(e) => onChange(e.target.value)}>
      {allowAny != null ? <option value="">{allowAny}</option> : <option value="">{tx('Default type')}</option>}
      {types.map((t) => <option key={t.id} value={t.id}>{t.name}{t.isDefault ? ` (${tx('default')})` : ''}</option>)}
    </select>
  );
}

/** Pick tags (toggle chips); users who may change ECOs can add a new tag on the spot. */
export function TagPicker({ value, onChange }: { value: string[]; onChange: (ids: string[]) => void }) {
  const { data: tags = [] } = usePlmTags();
  const canCreate = useAuthStore((s) => s.hasPermission)(PERMISSIONS.plm.change);
  const create = useCreateTag();
  const [name, setName] = useState('');
  const toggle = (id: string) => onChange(value.includes(id) ? value.filter((x) => x !== id) : [...value, id]);
  const add = () => {
    const n = name.trim();
    if (!n) return;
    create.mutate({ name: n }, {
      onSuccess: (t) => { onChange([...value, t.id]); setName(''); notify.success(tx('Tag {{n}} added', { n: t.name })); },
    });
  };
  return (
    <div className="space-y-2">
      <div className="flex flex-wrap gap-1.5">
        {tags.map((t) => {
          const on = value.includes(t.id);
          return (
            <button key={t.id} type="button" onClick={() => toggle(t.id)}
              className={cn('inline-flex items-center gap-1.5 rounded-full border px-2 py-0.5 text-xs transition',
                on ? 'border-primary bg-primary/10 font-medium' : 'hover:bg-muted')}>
              {on ? <Check className="h-3 w-3" /> : <ColorDot color={t.color} className="h-2 w-2" />}{t.name}
            </button>
          );
        })}
        {!tags.length && <span className="text-xs text-muted-foreground">{tx('No tags yet.')}</span>}
      </div>
      {canCreate && (
        <div className="flex max-w-xs gap-1.5">
          <Input value={name} maxLength={40} onChange={(e) => setName(e.target.value)} placeholder={tx('New tag…')} className="h-8 text-sm"
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); add(); } }} />
          <button type="button" onClick={add} disabled={!name.trim() || create.isPending}
            className="inline-flex h-8 items-center gap-1 rounded-md border px-2 text-xs hover:bg-muted disabled:opacity-50">
            <Plus className="h-3.5 w-3.5" />{tx('Add')}
          </button>
        </div>
      )}
    </div>
  );
}
