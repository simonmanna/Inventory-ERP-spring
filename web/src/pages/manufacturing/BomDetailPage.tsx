import { useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Boxes, Coins, GitBranch, History, Layers, Lock, Network, Package, Plus, Replace, Save, Trash2 } from 'lucide-react';
import { cn } from '@/lib/utils';
import { EmptyState, MfgCard, MfgPageHeader, MfgStatus, PageSkeleton, TonePill } from './mfg-ui';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { useAuthStore } from '@/stores/auth.store';
import { notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { useBom } from '@/features/manufacturing/api';
import { useProductOptions } from '@/features/manufacturing/options';
import { BomStageSetupCard } from './BomStageSetupCard';
import {
  useBomCost,
  useBomSubstitutes,
  useFreezeStandard,
  useReviseBom,
  useSaveSubstitutes,
  useStandardCosts,
  useWhereUsed,
} from '@/features/manufacturing/api-advanced';

const money = (s: unknown) => Number(s ?? 0).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 4 });
const qty = (s: unknown) => Number(s ?? 0).toLocaleString(undefined, { maximumFractionDigits: 4 });
const errMsg = (e: any) => {
  const m = e?.response?.data?.message ?? e?.message;
  return Array.isArray(m) ? m.join(', ') : m;
};

/** One BOM: cost roll-up, standard costs, component substitutes, where-used and revision under an ECO. */
export function BomDetailPage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const canUpdate = hasPermission(PERMISSIONS.bom.update);
  const canCreate = hasPermission(PERMISSIONS.bom.create);
  const { data: bom } = useBom(id);
  const { data: cost } = useBomCost(id);
  const { data: standards } = useStandardCosts(id);
  const { data: lines } = useBomSubstitutes(id);
  const { data: used } = useWhereUsed((bom as any)?.outputProductId);
  const { data: products } = useProductOptions();
  const freeze = useFreezeStandard();
  const revise = useReviseBom();
  const saveSubs = useSaveSubstitutes();
  const [reason, setReason] = useState('');

  const productName = useMemo(() => {
    const m = new Map((products ?? []).map((p) => [p.id, `${p.code} · ${p.name}`]));
    return (pid: string) => m.get(pid) ?? pid;
  }, [products]);

  if (!bom) return <div className="p-4 sm:p-6"><PageSkeleton /></div>;
  const b: any = bom;

  const doRevise = async () => {
    try {
      const r = await revise.mutateAsync({ bomId: id, title: `Revision of ${b.code}`, reason: reason || undefined });
      notify.success(tx('Draft version {{v}} created under {{eco}}.', { v: r.bom.version, eco: r.change.number }));
      navigate(`/manufacturing/boms/${r.bom.id}`);
    } catch (e: any) {
      notify.error(tx('Failed'), errMsg(e));
    }
  };

  const COST_PARTS = [
    { key: 'materialCost', label: tx('Material'), color: 'bg-sky-500' },
    { key: 'labourCost', label: tx('Labour / machine'), color: 'bg-violet-500' },
    { key: 'subcontractCost', label: tx('Subcontract'), color: 'bg-amber-500' },
    { key: 'overheadCost', label: tx('Overhead'), color: 'bg-emerald-500' },
  ] as const;
  const partTotal = COST_PARTS.reduce((t, p) => t + Number(cost?.[p.key] ?? 0), 0);
  const breakdownMax = Math.max(0, ...(cost?.breakdown ?? []).map((r: any) => Number(r.cost ?? 0)));
  const history = [
    ...(standards ?? []).map((st: any) => ({ key: st.id, at: st.computedAt as string, title: tx('Frozen'), version: st.bomVersion, value: money(st.unitCost), current: !!st.isCurrent })),
    ...(b.createdAt ? [{ key: 'created', at: b.createdAt as string, title: tx('Version created'), version: b.version, value: '', current: false }] : []),
  ].sort((x, y) => (x.at < y.at ? 1 : -1));

  return (
    <div className="space-y-5 p-4 sm:p-6">
      <MfgPageHeader icon={Layers}
        back={<Button asChild variant="ghost" size="icon" className="shrink-0"><Link to="/manufacturing/boms" aria-label={tx('Recipes')}><ArrowLeft className="h-4 w-4" /></Link></Button>}
        eyebrow={<span className="font-mono">{b.code}</span>}
        title={b.name}
        badges={<><MfgStatus status={b.status} kind="bom" /><TonePill tone="info" icon={GitBranch}>{tx('v{{v}}', { v: b.version })}</TonePill></>}
        subtitle={<>{productName(b.outputProductId)} · {qty(b.outputQuantity)} {tx('per run')}</>}
        actions={(canCreate || canUpdate) && (
          <>
            {canCreate && (
              <>
                <Input className="w-full sm:w-56" placeholder={tx('Reason for change')} value={reason} onChange={(e) => setReason(e.target.value)} aria-label={tx('Reason for change')} />
                <Button variant="outline" onClick={doRevise} disabled={revise.isPending}><GitBranch className="mr-2 h-4 w-4" /> {tx('Revise (ECO)')}</Button>
              </>
            )}
            {canUpdate && (
              <Button onClick={async () => {
                try {
                  await freeze.mutateAsync(id);
                  notify.success(tx('Standard cost frozen.'));
                } catch (e: any) {
                  notify.error(tx('Failed'), errMsg(e));
                }
              }} disabled={freeze.isPending}>
                <Lock className="mr-2 h-4 w-4" /> {tx('Freeze standard cost')}
              </Button>
            )}
          </>
        )} />

      <MfgCard icon={Coins} title={tx('Cost roll-up (one run)')}>
        <div className="grid gap-5 lg:grid-cols-[1fr_auto]">
          <div className="min-w-0">
            <div className="flex h-5 overflow-hidden rounded-full bg-muted" role="img"
              aria-label={COST_PARTS.map((p) => `${p.label} ${money(cost?.[p.key])}`).join(', ')}>
              {partTotal > 0 && COST_PARTS.map((p) => {
                const v = Number(cost?.[p.key] ?? 0);
                return v > 0 ? <div key={p.key} className={cn('h-full border-r-2 border-card last:border-r-0', p.color)} style={{ width: `${(v / partTotal) * 100}%` }} title={`${p.label}: ${money(v)}`} /> : null;
              })}
            </div>
            <dl className="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-4">
              {COST_PARTS.map((p) => {
                const v = Number(cost?.[p.key] ?? 0);
                return (
                  <div key={p.key} className="min-w-0">
                    <dt className="flex items-center gap-1.5 text-xs text-muted-foreground"><span className={cn('h-2.5 w-2.5 rounded-sm', p.color)} aria-hidden />{p.label}</dt>
                    <dd className="font-semibold tabular-nums">{money(v)}</dd>
                    <dd className="text-[11px] tabular-nums text-muted-foreground">{partTotal > 0 ? `${((v / partTotal) * 100).toFixed(0)}%` : '—'}</dd>
                  </div>
                );
              })}
            </dl>
          </div>
          <div className="rounded-xl bg-gradient-to-br from-primary to-primary/70 p-4 text-primary-foreground lg:min-w-48">
            <div className="text-xs uppercase tracking-wide opacity-80">{tx('Cost per unit')}</div>
            <div className="mt-1 text-3xl font-bold tabular-nums">{money(cost?.unitCost)}</div>
          </div>
        </div>
        {(cost?.breakdown ?? []).length > 0 && (
          <div className="mt-5 overflow-x-auto">
            <table className="w-full min-w-[620px] text-sm">
              <thead className="text-left text-xs uppercase tracking-wide text-muted-foreground">
                <tr>
                  <th className="pb-2 pr-3 font-semibold">{tx('Type')}</th>
                  <th className="px-3 pb-2 font-semibold">{tx('Item')}</th>
                  <th className="px-3 pb-2 text-right font-semibold">{tx('Qty / minutes')}</th>
                  <th className="px-3 pb-2 text-right font-semibold">{tx('Unit cost')}</th>
                  <th className="w-44 px-3 pb-2 text-right font-semibold">{tx('Cost')}</th>
                  <th className="pb-2 pl-3 font-semibold">{tx('Source')}</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {(cost?.breakdown ?? []).map((r: any, i: number) => (
                  <tr key={i}>
                    <td className="py-2 pr-3"><TonePill tone={r.kind === 'material' ? 'info' : r.kind === 'overhead' ? 'success' : r.kind === 'subcontract' ? 'warning' : 'progress'}>{r.kind}</TonePill></td>
                    <td className="px-3 py-2">{r.productId ? productName(r.productId) : r.operation}</td>
                    <td className="px-3 py-2 text-right tabular-nums">{qty(r.quantity ?? r.minutes)}</td>
                    <td className="px-3 py-2 text-right tabular-nums">{r.unitCost !== undefined ? money(r.unitCost) : '—'}</td>
                    <td className="px-3 py-2 text-right tabular-nums">
                      {money(r.cost)}
                      <div className="mt-1 h-1 overflow-hidden rounded-full bg-muted"><div className="ml-auto h-full rounded-full bg-primary/70" style={{ width: `${breakdownMax > 0 ? (Number(r.cost ?? 0) / breakdownMax) * 100 : 0}%` }} /></div>
                    </td>
                    <td className="py-2 pl-3 text-xs text-muted-foreground">{r.source ?? ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </MfgCard>

      <MfgCard icon={Network} title={tx('Components & substitutes')} description={tx('What one run consumes, with the products that may replace each component')}>
        <div className="flex items-center gap-3 rounded-xl border-2 border-primary/40 bg-primary/5 p-3">
          <span className="grid h-10 w-10 shrink-0 place-items-center rounded-lg bg-primary text-primary-foreground"><Package className="h-5 w-5" aria-hidden /></span>
          <div className="min-w-0">
            <div className="truncate font-semibold">{productName(b.outputProductId)}</div>
            <div className="text-xs text-muted-foreground">{qty(b.outputQuantity)} {tx('per run')}</div>
          </div>
        </div>
        {!(lines ?? []).length ? <EmptyState compact icon={Boxes} title={tx('No components.')} /> : (
          <ul className="ml-5 mt-0 border-l-2 border-border pl-0">
            {(lines ?? []).map((l: any) => (
              <li key={l.id} className="relative pl-6 pt-3 before:absolute before:left-0 before:top-8 before:h-0.5 before:w-5 before:bg-border">
                <SubstituteRow line={l} canEdit={canUpdate} products={products ?? []} productName={productName}
                  onSave={async (subs) => {
                    try {
                      await saveSubs.mutateAsync({ bomId: id, lineId: l.id, substitutes: subs });
                      notify.success(tx('Substitutes saved.'));
                    } catch (e: any) {
                      notify.error(tx('Failed'), errMsg(e));
                    }
                  }} />
              </li>
            ))}
          </ul>
        )}
      </MfgCard>

      <BomStageSetupCard bom={b} />

      <div className="grid gap-5 md:grid-cols-2">
        <MfgCard icon={History} title={tx('Standard cost history')}>
          {history.length === 0 ? <EmptyState compact icon={History} title={tx('No history yet.')} /> : (
            <ol className="relative space-y-3 pl-5 before:absolute before:inset-y-1 before:left-[6px] before:w-px before:bg-border">
              {history.map((h) => (
                <li key={h.key} className="relative">
                  <span className={cn('absolute -left-5 top-1 h-3.5 w-3.5 rounded-full ring-4 ring-card', h.current ? 'bg-emerald-500' : h.key === 'created' ? 'bg-slate-400' : 'bg-sky-500')} aria-hidden />
                  <div className="flex flex-wrap items-center gap-2 text-sm font-medium">
                    {h.title}
                    <TonePill tone="info">v{h.version}</TonePill>
                    {h.current && <Badge>{tx('current')}</Badge>}
                  </div>
                  <div className="flex justify-between gap-2 text-xs text-muted-foreground">
                    <span>{new Date(h.at).toLocaleString()}</span>
                    {h.value && <span className="font-semibold tabular-nums text-foreground">{tx('Per unit')} {h.value}</span>}
                  </div>
                </li>
              ))}
            </ol>
          )}
        </MfgCard>
        <MfgCard icon={GitBranch} title={tx('Where used')}>
          {!used?.length ? <EmptyState compact icon={GitBranch} title={tx('Not used in another active BOM.')} /> : (
            <ul className="space-y-2">
              {used.map((u: any, i: number) => (
                <li key={i}>
                  <button type="button" onClick={() => navigate(`/manufacturing/boms/${u.bomId}`)}
                    className="flex w-full items-center gap-3 rounded-xl border p-2.5 text-left transition-colors hover:bg-muted/60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                    style={{ marginLeft: `${Math.min(4, Math.max(0, Number(u.level) - 1)) * 12}px` }}>
                    <TonePill tone="neutral">{tx('Level')} {u.level}</TonePill>
                    <span className="font-mono text-xs">{u.code}</span>
                    <span className="min-w-0 truncate text-sm">{u.outputName}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </MfgCard>
      </div>
    </div>
  );
}

function SubstituteRow({ line, canEdit, products, productName, onSave }: {
  line: any;
  canEdit: boolean;
  products: Array<{ id: string; code: string; name: string }>;
  productName: (id: string) => string;
  onSave: (subs: Array<{ productId: string; ratio: number; priority: number }>) => Promise<void>;
}) {
  const [subs, setSubs] = useState<Array<{ productId: string; ratio: string }>>(
    (line.substitutes ?? []).map((s: any) => ({ productId: s.productId, ratio: String(Number(s.ratio)) })),
  );
  return (
    <div className="rounded-xl border bg-card p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex min-w-0 items-center gap-2 text-sm font-medium">
          <span className="grid h-7 w-7 shrink-0 place-items-center rounded-md bg-muted text-muted-foreground"><Boxes className="h-4 w-4" aria-hidden /></span>
          <span className="min-w-0 truncate">{productName(line.componentProductId)}</span>
          <span className="shrink-0 rounded-md bg-muted px-1.5 py-0.5 text-xs tabular-nums">× {qty(line.quantity)}</span>
          {Number(line.scrapPct) > 0 && <span className="shrink-0 text-xs text-amber-700 dark:text-amber-400">+{qty(line.scrapPct)}% {tx('scrap')}</span>}
        </div>
        {canEdit && (
          <Button size="sm" variant="ghost" onClick={() => setSubs((s) => [...s, { productId: '', ratio: '1' }])}>
            <Plus className="mr-1 h-3 w-3" /> {tx('Substitute')}
          </Button>
        )}
      </div>
      {subs.map((s, i) => (
        <div key={i} className="mt-2 flex flex-wrap items-center gap-2 border-l-2 border-dashed pl-4">
          <Replace className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden />
          <Select value={s.productId} onValueChange={(v) => setSubs((xs) => xs.map((x, j) => (j === i ? { ...x, productId: v } : x)))} disabled={!canEdit}>
            <SelectTrigger className="w-full sm:w-72"><SelectValue placeholder={tx('Substitute product…')} /></SelectTrigger>
            <SelectContent>{products.filter((p) => p.id !== line.componentProductId).map((p) => <SelectItem key={p.id} value={p.id}>{p.code} · {p.name}</SelectItem>)}</SelectContent>
          </Select>
          <Label className="text-xs">{tx('Ratio')}</Label>
          <Input className="w-24" type="number" min="0" step="any" value={s.ratio} disabled={!canEdit}
            onChange={(e) => setSubs((xs) => xs.map((x, j) => (j === i ? { ...x, ratio: e.target.value } : x)))} />
          {canEdit && <Button size="icon" variant="ghost" aria-label={tx('Remove')} onClick={() => setSubs((xs) => xs.filter((_, j) => j !== i))}><Trash2 className="h-4 w-4" /></Button>}
        </div>
      ))}
      {canEdit && (
        <div className="mt-2 pl-4">
          <Button size="sm" variant="outline" onClick={() => onSave(subs.filter((s) => s.productId).map((s, k) => ({ productId: s.productId, ratio: Number(s.ratio) || 1, priority: k })))}>
            <Save className="mr-1 h-3 w-3" /> {tx('Save substitutes')}
          </Button>
        </div>
      )}
    </div>
  );
}

export default BomDetailPage;
