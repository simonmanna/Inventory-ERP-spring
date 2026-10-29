import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { toast } from 'sonner';
import { ChevronDown, Filter, Layers, Search, Star, Trash2, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { apiErrorMessage } from '@/lib/api-error';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import {
  GROUP_BY_LABEL, crmApi, useCrmLookups, useCrmMutation, useSavedFilters,
} from '@/features/crm/suite-api';

/** Filter keys understood by the opportunity / lead endpoints. */
export const FILTER_KEYS = [
  'q', 'mine', 'unassigned', 'status', 'ownerId', 'teamId', 'sourceId', 'tagId', 'stageId', 'lostReasonId', 'priority',
  'activityState', 'createdFrom', 'createdTo', 'closeFrom', 'closeTo', 'city', 'country', 'partnerId',
] as const;

export type FilterState = Partial<Record<(typeof FILTER_KEYS)[number], string>>;

/** URL-backed CRM filters (+ view / groupBy). */
export function useCrmFilters(defaults: FilterState = {}) {
  const [params, setParams] = useSearchParams();
  const touched = params.get('f') === '1';
  const filters = useMemo(() => {
    const out: FilterState = {};
    for (const k of FILTER_KEYS) {
      const v = params.get(k);
      if (v) out[k] = v;
    }
    return touched ? out : { ...defaults, ...out };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [params, touched]);
  const set = (patch: Record<string, string | null | undefined>, replaceAll = false) => {
    setParams((prev) => {
      const next = new URLSearchParams(replaceAll ? '' : prev);
      if (replaceAll) {
        for (const k of ['view', 'groupBy']) if (prev.get(k)) next.set(k, prev.get(k)!);
      } else if (!touched) {
        for (const [k, v] of Object.entries(defaults)) if (v && !next.get(k)) next.set(k, v);
      }
      next.set('f', '1');
      for (const [k, v] of Object.entries(patch)) {
        if (v === null || v === undefined || v === '') next.delete(k);
        else next.set(k, v);
      }
      next.delete('page');
      return next;
    }, { replace: true });
  };
  return {
    filters,
    view: params.get('view') ?? 'kanban',
    groupBy: params.get('groupBy') ?? '',
    page: Number(params.get('page') ?? 1) || 1,
    set,
    setPage: (p: number) => setParams((prev) => { const n = new URLSearchParams(prev); n.set('page', String(p)); return n; }, { replace: true }),
  };
}

const iso = (d: Date) => d.toISOString().slice(0, 10);
function rangeOf(kind: 'thisMonth' | 'lastMonth' | 'thisQuarter' | 'thisYear' | 'last30') {
  const now = new Date();
  const y = now.getFullYear();
  const m = now.getMonth();
  switch (kind) {
    case 'thisMonth': return [iso(new Date(y, m, 1)), iso(new Date(y, m + 1, 0))];
    case 'lastMonth': return [iso(new Date(y, m - 1, 1)), iso(new Date(y, m, 0))];
    case 'thisQuarter': { const q = Math.floor(m / 3) * 3; return [iso(new Date(y, q, 1)), iso(new Date(y, q + 3, 0))]; }
    case 'thisYear': return [iso(new Date(y, 0, 1)), iso(new Date(y, 11, 31))];
    default: return [iso(new Date(Date.now() - 29 * 86_400_000)), iso(now)];
  }
}

const RANGES: Array<{ key: 'thisMonth' | 'lastMonth' | 'thisQuarter' | 'thisYear' | 'last30'; label: string }> = [
  { key: 'last30', label: tx('Last 30 days') },
  { key: 'thisMonth', label: tx('This month') },
  { key: 'lastMonth', label: tx('Last month') },
  { key: 'thisQuarter', label: tx('This quarter') },
  { key: 'thisYear', label: tx('This year') },
];

/**
 * Odoo-style search bar: free text + facet chips, and a panel with Filters, Group By and Favorites (saved searches).
 */
export function CrmFilterBar({ scope, state, groupByOptions, showStatus = true }: {
  scope: 'pipeline' | 'leads';
  state: ReturnType<typeof useCrmFilters>;
  groupByOptions?: string[];
  showStatus?: boolean;
}) {
  const { filters, set, groupBy } = state;
  const { data: lk } = useCrmLookups();
  const saved = useSavedFilters(scope);
  const [text, setText] = useState(filters.q ?? '');
  const [favName, setFavName] = useState('');
  const [shared, setShared] = useState(false);
  const saveFav = useCrmMutation(crmApi.saveFilter);
  const delFav = useCrmMutation(crmApi.deleteFilter);

  const nameOf = (rows: Array<{ id: string; name: string }> | undefined, id?: string) => rows?.find((r) => r.id === id)?.name ?? id;
  const chips: Array<{ key: string; label: string; clear: Record<string, null> }> = [];
  if (filters.mine) chips.push({ key: 'mine', label: scope === 'leads' ? tx('My leads') : tx('My pipeline'), clear: { mine: null } });
  if (filters.unassigned) chips.push({ key: 'un', label: tx('Unassigned'), clear: { unassigned: null } });
  if (filters.status) chips.push({ key: 'st', label: filters.status.split(',').map((s) => s === 'open' ? tx('Open') : s === 'won' ? tx('Won') : tx('Lost')).join(' / '), clear: { status: null } });
  if (filters.ownerId) chips.push({ key: 'ow', label: tx('Salesperson') + ': ' + (lk?.users.find((u) => u.id === filters.ownerId)?.name ?? '…'), clear: { ownerId: null } });
  if (filters.teamId) chips.push({ key: 'tm', label: tx('Team') + ': ' + nameOf(lk?.teams, filters.teamId), clear: { teamId: null } });
  if (filters.sourceId) chips.push({ key: 'so', label: tx('Source') + ': ' + nameOf(lk?.sources, filters.sourceId), clear: { sourceId: null } });
  if (filters.tagId) chips.push({ key: 'tg', label: tx('Tag') + ': ' + nameOf(lk?.tags, filters.tagId), clear: { tagId: null } });
  if (filters.stageId) chips.push({ key: 'sg', label: tx('Stage') + ': ' + nameOf(lk?.stages, filters.stageId), clear: { stageId: null } });
  if (filters.lostReasonId) chips.push({ key: 'lr', label: tx('Lost reason') + ': ' + nameOf(lk?.lostReasons, filters.lostReasonId), clear: { lostReasonId: null } });
  if (filters.priority) chips.push({ key: 'pr', label: '★'.repeat(Number(filters.priority)) + '+', clear: { priority: null } });
  if (filters.activityState) chips.push({ key: 'ac', label: tx('Activities') + ': ' + filters.activityState, clear: { activityState: null } });
  if (filters.createdFrom || filters.createdTo) chips.push({ key: 'cr', label: tx('Created') + ` ${filters.createdFrom ?? '…'} → ${filters.createdTo ?? '…'}`, clear: { createdFrom: null, createdTo: null } });
  if (filters.closeFrom || filters.closeTo) chips.push({ key: 'cl', label: tx('Closing') + ` ${filters.closeFrom ?? '…'} → ${filters.closeTo ?? '…'}`, clear: { closeFrom: null, closeTo: null } });
  if (filters.city) chips.push({ key: 'ci', label: tx('City') + ': ' + filters.city, clear: { city: null } });
  if (filters.country) chips.push({ key: 'co', label: tx('Country') + ': ' + filters.country, clear: { country: null } });
  if (groupBy) chips.push({ key: 'gb', label: tx('Group by') + ': ' + (GROUP_BY_LABEL[groupBy] ?? groupBy), clear: { groupBy: null } as Record<string, null> });

  const toggle = (k: string, v: string) => set({ [k]: filters[k as keyof FilterState] === v ? null : v });
  const opt = 'flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-sm hover:bg-accent';
  const check = (on: boolean) => <span className={cn('h-3.5 w-3.5 shrink-0 rounded-sm border', on && 'border-primary bg-primary')} />;
  const sel = 'h-8 w-full rounded-md border bg-background px-2 text-sm';

  return (
    <div className="flex min-w-0 flex-1 items-center gap-1 rounded-lg border bg-background px-2 py-1 shadow-sm">
      <Search className="h-4 w-4 shrink-0 text-muted-foreground" />
      <div className="flex min-w-0 flex-1 flex-wrap items-center gap-1">
        {chips.map((c) => (
          <span key={c.key} className="inline-flex items-center gap-1 rounded bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">
            {c.key === 'gb' ? <Layers className="h-3 w-3" /> : <Filter className="h-3 w-3" />}
            {c.label}
            <button type="button" aria-label={tx('Remove')} onClick={() => set(c.clear)}><X className="h-3 w-3" /></button>
          </span>
        ))}
        <input
          className="h-7 min-w-[120px] flex-1 bg-transparent text-sm outline-none"
          placeholder={tx('Search…')}
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') set({ q: text.trim() || null }); if (e.key === 'Backspace' && !text && chips.length) set(chips[chips.length - 1].clear); }}
          onBlur={() => { if ((filters.q ?? '') !== text.trim()) set({ q: text.trim() || null }); }}
        />
      </div>
      <Popover>
        <PopoverTrigger asChild>
          <Button variant="ghost" size="sm" className="h-7 px-2" aria-label={tx('Filters')}><ChevronDown className="h-4 w-4" /></Button>
        </PopoverTrigger>
        <PopoverContent align="end" className="w-[min(92vw,760px)] p-0">
          <div className="grid gap-0 divide-y sm:grid-cols-3 sm:divide-x sm:divide-y-0">
            <div className="max-h-[70vh] space-y-1 overflow-y-auto p-3">
              <p className="mb-1 flex items-center gap-1.5 text-sm font-semibold"><Filter className="h-4 w-4 text-primary" />{tx('Filters')}</p>
              <button className={opt} onClick={() => toggle('mine', 'true')}>{check(!!filters.mine)}{scope === 'leads' ? tx('My leads') : tx('My pipeline')}</button>
              <button className={opt} onClick={() => toggle('unassigned', 'true')}>{check(!!filters.unassigned)}{tx('Unassigned')}</button>
              {showStatus && (<>
                <div className="my-1 border-t" />
                <button className={opt} onClick={() => toggle('status', 'open')}>{check(filters.status === 'open')}{tx('Ongoing')}</button>
                <button className={opt} onClick={() => toggle('status', 'won')}>{check(filters.status === 'won')}{tx('Won')}</button>
                <button className={opt} onClick={() => toggle('status', 'lost')}>{check(filters.status === 'lost')}{tx('Lost')}</button>
              </>)}
              <div className="my-1 border-t" />
              <p className="px-2 text-xs font-medium text-muted-foreground">{tx('Activities')}</p>
              {(['overdue', 'today', 'planned', 'none'] as const).map((a) => (
                <button key={a} className={opt} onClick={() => toggle('activityState', a)}>{check(filters.activityState === a)}
                  {a === 'overdue' ? tx('Late activities') : a === 'today' ? tx('Today activities') : a === 'planned' ? tx('Future activities') : tx('No activity')}</button>
              ))}
              <div className="my-1 border-t" />
              <p className="px-2 text-xs font-medium text-muted-foreground">{tx('Priority')}</p>
              <div className="flex gap-1 px-2">
                {[1, 2, 3].map((p) => (
                  <button key={p} onClick={() => toggle('priority', String(p))} className={cn('rounded border px-2 py-0.5 text-xs', filters.priority === String(p) && 'border-primary bg-primary/10')}>{'★'.repeat(p)}+</button>
                ))}
              </div>
              <div className="my-1 border-t" />
              <p className="px-2 text-xs font-medium text-muted-foreground">{tx('Creation date')}</p>
              <div className="flex flex-wrap gap-1 px-2">
                {RANGES.map((r) => (
                  <button key={r.key} className="rounded border px-2 py-0.5 text-xs hover:bg-accent" onClick={() => { const [a, b] = rangeOf(r.key); set({ createdFrom: a, createdTo: b }); }}>{r.label}</button>
                ))}
              </div>
              <p className="px-2 pt-1 text-xs font-medium text-muted-foreground">{tx('Expected closing')}</p>
              <div className="flex flex-wrap gap-1 px-2">
                {RANGES.filter((r) => r.key !== 'last30').map((r) => (
                  <button key={r.key} className="rounded border px-2 py-0.5 text-xs hover:bg-accent" onClick={() => { const [a, b] = rangeOf(r.key); set({ closeFrom: a, closeTo: b }); }}>{r.label}</button>
                ))}
              </div>
            </div>
            <div className="max-h-[70vh] space-y-2 overflow-y-auto p-3">
              <p className="mb-1 flex items-center gap-1.5 text-sm font-semibold"><Layers className="h-4 w-4 text-primary" />{tx('Group by')}</p>
              {(groupByOptions ?? []).map((g) => (
                <button key={g} className={opt} onClick={() => set({ groupBy: groupBy === g ? null : g, view: 'list' })}>{check(groupBy === g)}{GROUP_BY_LABEL[g] ?? g}</button>
              ))}
              <div className="my-1 border-t" />
              <p className="text-xs font-medium text-muted-foreground">{tx('Narrow down')}</p>
              <select className={sel} value={filters.ownerId ?? ''} onChange={(e) => set({ ownerId: e.target.value || null })}>
                <option value="">{tx('Any salesperson')}</option>
                {(lk?.users ?? []).map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}
              </select>
              <select className={sel} value={filters.teamId ?? ''} onChange={(e) => set({ teamId: e.target.value || null })}>
                <option value="">{tx('Any team')}</option>
                {(lk?.teams ?? []).map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}
              </select>
              <select className={sel} value={filters.sourceId ?? ''} onChange={(e) => set({ sourceId: e.target.value || null })}>
                <option value="">{tx('Any source')}</option>
                {(lk?.sources ?? []).map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}
              </select>
              <select className={sel} value={filters.tagId ?? ''} onChange={(e) => set({ tagId: e.target.value || null })}>
                <option value="">{tx('Any tag')}</option>
                {(lk?.tags ?? []).map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}
              </select>
              {scope === 'pipeline' && (
                <select className={sel} value={filters.lostReasonId ?? ''} onChange={(e) => set({ lostReasonId: e.target.value || null, status: e.target.value ? 'lost' : filters.status ?? null })}>
                  <option value="">{tx('Any lost reason')}</option>
                  {(lk?.lostReasons ?? []).map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}
                </select>
              )}
              <div className="grid grid-cols-2 gap-1">
                <Input className="h-8" placeholder={tx('City')} defaultValue={filters.city ?? ''} onBlur={(e) => set({ city: e.target.value || null })} />
                <Input className="h-8" placeholder={tx('Country')} defaultValue={filters.country ?? ''} onBlur={(e) => set({ country: e.target.value || null })} />
              </div>
            </div>
            <div className="max-h-[70vh] space-y-1 overflow-y-auto p-3">
              <p className="mb-1 flex items-center gap-1.5 text-sm font-semibold"><Star className="h-4 w-4 text-amber-500" />{tx('Favorites')}</p>
              {(saved.data ?? []).map((s) => (
                <div key={s.id} className="group flex items-center gap-1">
                  <button className={cn(opt, 'flex-1')} onClick={() => set({ ...s.filters }, true)}>{s.name}{s.shared && <span className="text-[10px] text-muted-foreground">({tx('shared')})</span>}</button>
                  <button className="rounded p-1 text-muted-foreground opacity-60 hover:bg-muted hover:opacity-100" aria-label={tx('Delete')}
                    onClick={() => delFav.mutate(s.id, { onError: (e) => toast.error(apiErrorMessage(e, tx('Only the owner can delete it'))) })}><Trash2 className="h-3.5 w-3.5" /></button>
                </div>
              ))}
              {(saved.data ?? []).length === 0 && <p className="px-2 text-xs text-muted-foreground">{tx('No saved searches yet.')}</p>}
              <div className="mt-2 space-y-1.5 border-t pt-2">
                <p className="text-xs font-medium text-muted-foreground">{tx('Save current search')}</p>
                <Input className="h-8" value={favName} onChange={(e) => setFavName(e.target.value)} placeholder={tx('Name')} />
                <label className="flex items-center gap-2 text-xs"><input type="checkbox" checked={shared} onChange={(e) => setShared(e.target.checked)} />{tx('Share with everyone')}</label>
                <Button size="sm" className="w-full" disabled={!favName.trim() || saveFav.isPending} onClick={() => {
                  const f: Record<string, string> = {};
                  for (const [k, v] of Object.entries(filters)) if (v) f[k] = v;
                  if (groupBy) f.groupBy = groupBy;
                  saveFav.mutate({ scope, name: favName.trim(), filters: f, shared }, {
                    onSuccess: () => { setFavName(''); toast.success(tx('Search saved')); },
                    onError: (e) => toast.error(apiErrorMessage(e, tx('Could not save'))),
                  });
                }}>{tx('Save')}</Button>
              </div>
            </div>
          </div>
        </PopoverContent>
      </Popover>
    </div>
  );
}
