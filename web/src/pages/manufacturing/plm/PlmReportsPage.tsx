import { useState, type ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  AlertTriangle, BarChart3, CheckCircle2, Clock, GitMerge, Layers, Lock, Microscope, PackageX, ShoppingCart, XCircle,
} from 'lucide-react';
import { ListPageHeader } from '@/components/list/list-page';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { num, usePlmItems, type Num } from '@/features/manufacturing/api-plm';
import {
  useChangeReport, useObsoleteStock, useStockByRevision, type ReportGroup, type StockByRevisionRow,
} from '@/features/manufacturing/api-plm-app';
import { money, useOrgCurrency } from '@/lib/format';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { EmptyState, Field, LifecyclePill, Panel, PlmSubNav, RevisionBadge, TabBar } from './plm-ui';
import { ChangeTypeSelect } from './plm-app-ui';
import { plain } from './plm-format';
import { CHANGE_TYPE, PRIORITY, day, selectClass } from './vocab';
import type { ChangeType, Priority } from '@/features/manufacturing/api-plm';

type Tab = 'changes' | 'exposure' | 'revisions';

/**
 * PLM reports (ADR-S086): change-order analytics, the stock exposure of products being phased out or obsolete,
 * and the stock on hand by the revision it was built on.
 */
export function PlmReportsPage() {
  const [params, setParams] = useSearchParams();
  const tab = (params.get('tab') as Tab) || 'changes';
  const setTab = (t: Tab) => {
    const next = new URLSearchParams(params);
    if (t === 'changes') next.delete('tab'); else next.set('tab', t);
    setParams(next, { replace: true });
  };
  return (
    <div className="space-y-4">
      <ListPageHeader
        title={tx('PLM reports')}
        description={tx('How engineering changes flow, what retired products still tie up, and which revision your stock was built on.')}
        icon={BarChart3}
      />
      <PlmSubNav />
      <TabBar tabs={[
        { id: 'changes' as const, label: tx('Change analytics'), icon: BarChart3 },
        { id: 'exposure' as const, label: tx('Phase-out and obsolete stock'), icon: PackageX },
        { id: 'revisions' as const, label: tx('Stock by revision'), icon: Layers },
      ]} value={tab} onChange={setTab} />
      {tab === 'changes' && <ChangeAnalytics />}
      {tab === 'exposure' && <Exposure />}
      {tab === 'revisions' && <ByRevision />}
    </div>
  );
}

// ---- figures ----------------------------------------------------------------------------------------------------------

/** Stat tile: label, value in text ink (proportional figures), an optional note; status = icon + words. */
function Kpi({ label, value, note, icon: Icon, status }: {
  label: string; value: ReactNode; note?: ReactNode; icon?: typeof Clock; status?: 'warning' | 'good' | 'critical';
}) {
  return (
    <div className="rounded-xl border bg-card p-4 shadow-sm">
      <div className="flex items-center justify-between gap-2">
        <p className="text-xs font-medium text-muted-foreground">{label}</p>
        {Icon && <Icon className={cn('h-4 w-4 text-muted-foreground', status === 'warning' && 'text-amber-600 dark:text-amber-400',
          status === 'good' && 'text-emerald-600 dark:text-emerald-400', status === 'critical' && 'text-rose-600 dark:text-rose-400')} />}
      </div>
      <p className="mt-1 text-2xl font-semibold">{value}</p>
      {note && <p className="mt-0.5 text-xs text-muted-foreground">{note}</p>}
    </div>
  );
}

const compact = (v: Num | null | undefined) => (v == null ? '—'
  : new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 }).format(num(v)));

// ---- change analytics ----------------------------------------------------------------------------------------------------

const GROUPS: Array<{ id: ReportGroup; label: string }> = [
  { id: 'month', label: 'Month' }, { id: 'type', label: 'ECO type' }, { id: 'stage', label: 'Stage' },
  { id: 'owner', label: 'Owner' }, { id: 'priority', label: 'Priority' },
];

function ChangeAnalytics() {
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [changeTypeId, setChangeTypeId] = useState('');
  const [groupBy, setGroupBy] = useState<ReportGroup>('month');
  const currency = useOrgCurrency();
  const { data: r, isLoading, error } = useChangeReport({ from, to, changeTypeId, groupBy });
  const label = (key: string | null, text: string | null) => {
    if (groupBy === 'priority' && key) return PRIORITY[key as Priority]?.label ?? key;
    return text ?? key ?? tx('(none)');
  };
  const maxCreated = Math.max(1, ...(r?.rows ?? []).map((x) => num(x.created)));
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end gap-3">
        <Field label={tx('From')}><Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="w-40" /></Field>
        <Field label={tx('To')}><Input type="date" value={to} onChange={(e) => setTo(e.target.value)} className="w-40" /></Field>
        <Field label={tx('ECO type')}><ChangeTypeSelect value={changeTypeId} onChange={setChangeTypeId} allowAny={tx('All types')} className="w-48" /></Field>
        <Field label={tx('Group by')}>
          <select className={cn(selectClass, 'w-36')} value={groupBy} onChange={(e) => setGroupBy(e.target.value as ReportGroup)}>
            {GROUPS.map((g) => <option key={g.id} value={g.id}>{tx(g.label)}</option>)}
          </select>
        </Field>
        {r && <span className="pb-2 text-xs text-muted-foreground">{day(r.from)} – {day(r.to)}</span>}
      </div>
      {error ? <p className="text-sm text-rose-700 dark:text-rose-300">{String((error as { response?: { data?: { message?: string } } }).response?.data?.message ?? tx('The report could not be loaded.'))}</p>
        : isLoading || !r ? <Skeleton className="h-72 w-full rounded-xl" /> : (
          <>
            <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
              <Kpi label={tx('Changes created')} value={compact(r.created.created)}
                note={tx('{{o}} open · {{r}} cancelled', { o: num(r.created.open), r: num(r.created.rejected) })} />
              <Kpi label={tx('Changes applied')} value={compact(r.applied.applied)} icon={CheckCircle2}
                note={tx('{{n}} product data · {{s}} standard costs', { n: num(r.applied.productDataChanges), s: num(r.applied.standardsCreated) })} />
              <Kpi label={tx('Cycle time (days)')} value={r.applied.avgCycleDays == null ? '—' : plain(r.applied.avgCycleDays, 1)} icon={Clock}
                note={r.applied.p90CycleDays == null ? tx('created → applied, average') : tx('90 % within {{d}} days', { d: plain(r.applied.p90CycleDays, 1) })} />
              <Kpi label={tx('First-pass rate')} value={r.applied.firstPassRate == null ? '—' : `${plain(r.applied.firstPassRate, 1)} %`}
                note={tx('applied without a rejection or rebase')} />
              <Kpi label={tx('Time to approval (days)')} value={r.applied.avgApprovalDays == null ? '—' : plain(r.applied.avgApprovalDays, 1)} />
              <Kpi label={tx('Rebases')} value={compact(r.applied.rebases)} icon={GitMerge}
                note={tx('changes brought onto a newer BOM version')} />
              <Kpi label={tx('Blocked now')} value={compact(r.created.blocked)} icon={num(r.created.blocked) > 0 ? AlertTriangle : undefined}
                status={num(r.created.blocked) > 0 ? 'warning' : undefined} note={num(r.created.blocked) > 0 ? tx('needs attention') : tx('nothing blocked')} />
              {r.costVisible
                ? <Kpi label={tx('Stock revalued by changes')} value={money(num(r.applied.revaluationAmount), currency)}
                    note={tx('{{n}} revaluation journal(s)', { n: num(r.applied.revaluations) })} />
                : <Kpi label={tx('Stock revalued by changes')} value="—" icon={Lock} note={tx('needs inventory valuation access')} />}
            </div>

            <div className="grid gap-4 lg:grid-cols-[1fr_320px]">
              <Panel title={tx('Applied of created, by {{g}}', { g: tx(GROUPS.find((g) => g.id === groupBy)?.label ?? groupBy).toLowerCase() })} icon={BarChart3}>
                {!r.rows.length ? <p className="text-sm text-muted-foreground">{tx('No change orders were created in this period.')}</p> : (
                  <ul className="space-y-3">
                    {r.rows.map((x) => {
                      const created = num(x.created);
                      const applied = num(x.applied);
                      const name = label(x.key, x.label);
                      return (
                        <li key={x.key ?? 'none'} className="grid gap-1 sm:grid-cols-[180px_1fr_auto] sm:items-center sm:gap-3">
                          <span className="truncate text-sm font-medium" title={name}>{name}</span>
                          {/* meter: track = created (lighter step), fill = applied (accent) */}
                          <div className="h-2 rounded-full bg-primary/15" style={{ width: `${Math.max(8, (created / maxCreated) * 100)}%` }}
                            title={tx('{{name}}: {{a}} applied of {{c}} created', { name, a: applied, c: created })}>
                            <div className="h-2 rounded-full bg-primary" style={{ width: `${created ? (applied / created) * 100 : 0}%` }} />
                          </div>
                          <span className="whitespace-nowrap text-xs text-muted-foreground tabular-nums">
                            <b className="text-foreground">{applied}</b> / {created}
                            {num(x.open) > 0 && <> · {tx('{{n}} open', { n: num(x.open) })}</>}
                            {x.avgCycleDays != null && <> · {tx('{{d}} d', { d: plain(x.avgCycleDays, 1) })}</>}
                          </span>
                        </li>
                      );
                    })}
                  </ul>
                )}
                <p className="mt-3 flex items-center gap-3 text-[11px] text-muted-foreground">
                  <span className="flex items-center gap-1"><span className="h-2 w-4 rounded-full bg-primary" />{tx('applied')}</span>
                  <span className="flex items-center gap-1"><span className="h-2 w-4 rounded-full bg-primary/15" />{tx('created')}</span>
                </p>
              </Panel>
              <div className="space-y-4">
                <Panel title={tx('Created by kind')} icon={Layers}>
                  <dl className="grid grid-cols-[1fr_auto] gap-y-1.5 text-sm">
                    {Object.entries(r.byType).map(([k, v]) => (
                      <div key={k} className="contents"><dt>{CHANGE_TYPE[k as ChangeType]?.label ?? k}</dt><dd className="tabular-nums">{num(v)}</dd></div>
                    ))}
                    {!Object.keys(r.byType).length && <dt className="text-muted-foreground">—</dt>}
                  </dl>
                </Panel>
                <Panel title={tx('First articles of applied changes')} icon={Microscope}>
                  <dl className="grid grid-cols-[1fr_auto] gap-y-1.5 text-sm">
                    <dt className="flex items-center gap-1.5"><CheckCircle2 className="h-3.5 w-3.5 text-emerald-600" />{tx('Passed')}</dt><dd className="tabular-nums">{num(r.firstArticle.passed)}</dd>
                    <dt className="flex items-center gap-1.5"><XCircle className="h-3.5 w-3.5 text-rose-600" />{tx('Failed')}</dt><dd className="tabular-nums">{num(r.firstArticle.failed)}</dd>
                    <dt className="flex items-center gap-1.5"><Clock className="h-3.5 w-3.5 text-muted-foreground" />{tx('Waiting or being inspected')}</dt>
                    <dd className="tabular-nums">{num(r.firstArticle.pending) + num(r.firstArticle.open) + num(r.firstArticle.no_plan)}</dd>
                  </dl>
                </Panel>
              </div>
            </div>
          </>
        )}
    </div>
  );
}

// ---- phase-out / obsolete stock ------------------------------------------------------------------------------------

function Exposure() {
  const [state, setState] = useState('');
  const currency = useOrgCurrency();
  const { data: r, isLoading } = useObsoleteStock(state);
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end gap-3">
        <Field label={tx('Lifecycle state')}>
          <select className={cn(selectClass, 'w-48')} value={state} onChange={(e) => setState(e.target.value)}>
            <option value="">{tx('Phase-out and obsolete')}</option>
            <option value="phase_out">{tx('Being phased out')}</option>
            <option value="obsolete">{tx('Obsolete')}</option>
          </select>
        </Field>
      </div>
      {isLoading || !r ? <Skeleton className="h-72 w-full rounded-xl" /> : (
        <>
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            <Kpi label={tx('Products')} value={compact(r.totals.products)} note={tx('{{n}} with stock on hand', { n: num(r.totals.withStock) })} />
            {r.costVisible
              ? <Kpi label={tx('Stock value tied up')} value={money(num(r.totals.value), currency)} note={tx('{{q}} units on hand', { q: plain(r.totals.onHand, 0) })} />
              : <Kpi label={tx('Stock on hand')} value={plain(r.totals.onHand, 0)} icon={Lock} note={tx('values need inventory valuation access')} />}
            <Kpi label={tx('Still on open purchase orders')} value={compact(r.totals.stillPurchased)} icon={ShoppingCart}
              status={num(r.totals.stillPurchased) > 0 ? 'critical' : undefined}
              note={num(r.totals.stillPurchased) > 0 ? tx('cancel or reduce these orders') : tx('nothing incoming')} />
            <Kpi label={tx('Still in active BOMs')} value={compact(r.totals.usedInActiveBoms)} icon={Layers}
              status={num(r.totals.usedInActiveBoms) > 0 ? 'warning' : undefined}
              note={num(r.totals.usedInActiveBoms) > 0 ? tx('replace the component in those BOMs') : tx('no BOM uses them')} />
          </div>
          {!r.rows.length ? <EmptyState compact icon={PackageX} title={tx('No products are being phased out or obsolete')} /> : (
            <div className="overflow-x-auto rounded-xl border bg-card shadow-sm [&_thead_tr]:bg-muted/40">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{tx('Product')}</TableHead>
                    <TableHead>{tx('State')}</TableHead>
                    <TableHead className="text-right">{tx('On hand')}</TableHead>
                    {r.costVisible && <TableHead className="text-right">{tx('Value')}</TableHead>}
                    <TableHead className="text-right">{tx('Open purchase')}</TableHead>
                    <TableHead className="text-right">{tx('Open sales')}</TableHead>
                    <TableHead className="text-right">{tx('Active BOMs')}</TableHead>
                    <TableHead>{tx('Replacement')}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {r.rows.map((x) => (
                    <TableRow key={x.itemId}>
                      <TableCell>
                        <Link to={`/manufacturing/plm/products/${x.itemId}`} className="text-primary hover:underline"><span className="font-mono text-xs">{x.code}</span> {x.name}</Link>
                      </TableCell>
                      <TableCell>
                        <LifecyclePill state={x.lifecycleState} />
                        {x.since && <p className="text-[11px] text-muted-foreground">{tx('since {{d}}', { d: day(x.since) })}</p>}
                      </TableCell>
                      <TableCell className="text-right tabular-nums">{plain(x.onHand, 3)}</TableCell>
                      {r.costVisible && <TableCell className="text-right tabular-nums">{x.value == null ? '—' : money(num(x.value), currency)}</TableCell>}
                      <TableCell className="text-right tabular-nums">
                        {num(x.openPurchaseQty) > 0
                          ? <span className="inline-flex items-center gap-1"><AlertTriangle className="h-3.5 w-3.5 text-rose-600" aria-label={tx('still being bought')} />{plain(x.openPurchaseQty, 3)}
                            <span className="text-[11px] text-muted-foreground">({tx('{{n}} PO', { n: num(x.openPurchaseOrders) })})</span></span>
                          : '—'}
                      </TableCell>
                      <TableCell className="text-right tabular-nums">{num(x.openSalesQty) > 0 ? plain(x.openSalesQty, 3) : '—'}</TableCell>
                      <TableCell className="text-right tabular-nums">{num(x.usedInActiveBoms) > 0 ? num(x.usedInActiveBoms) : '—'}</TableCell>
                      <TableCell className="text-sm">{x.replacementCode ? <><span className="font-mono text-xs">{x.replacementCode}</span> {x.replacementName}</> : <span className="text-muted-foreground">{tx('none named')}</span>}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </>
      )}
    </div>
  );
}

// ---- stock by revision ---------------------------------------------------------------------------------------------

function ByRevision() {
  const [productId, setProductId] = useState('');
  const { data: items = [] } = usePlmItems();
  const { data, isLoading, error } = useStockByRevision(productId || undefined);
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end gap-3">
        <Field label={tx('Product')}>
          <select className={cn(selectClass, 'w-72')} value={productId} onChange={(e) => setProductId(e.target.value)}>
            <option value="">{tx('Every product with as-built orders')}</option>
            {items.map((i) => <option key={i.id} value={i.productId}>{i.productCode} · {i.productName}</option>)}
          </select>
        </Field>
        <p className="pb-2 text-xs text-muted-foreground">{tx('Batches made by a recorded order are exact; other stock is matched to the most recent orders first (an estimate).')}</p>
      </div>
      {error ? <EmptyState compact icon={Layers} title={tx('This product is not under lifecycle management')} />
        : isLoading || !data ? <Skeleton className="h-60 w-full rounded-xl" />
          : !data.rows.length ? <EmptyState compact icon={Layers} title={tx('No as-built production orders yet')} hint={tx('Revisions are recorded when production orders complete.')} />
            : <div className="space-y-3">{data.rows.map((r) => <RevisionStock key={r.productId} row={r} />)}</div>}
    </div>
  );
}

/** One product: a thin segmented bar of its stock by revision (current in the accent, older revisions grey). */
function RevisionStock({ row }: { row: StockByRevisionRow }) {
  const total = num(row.onHand);
  const parts = [
    ...row.revisions.map((v) => ({
      key: v.revision ?? '—', label: v.revision ? tx('Revision {{r}}', { r: v.revision }) : tx('No revision'), qty: num(v.quantity),
      current: v.status === 'current' || v.revision === row.currentRevision, estimated: num(v.estimated) > 0 && num(v.exact) === 0,
    })),
    ...(num(row.unknown) > 0 ? [{ key: 'unknown', label: tx('Unknown revision'), qty: num(row.unknown), current: false, estimated: false }] : []),
  ].filter((p) => p.qty > 0);
  const share = (q: number) => (total > 0 ? (q / total) * 100 : 0);
  const fill = (p: (typeof parts)[number], i: number) => (p.key === 'unknown' ? 'bg-slate-200 dark:bg-slate-700'
    : p.current ? 'bg-primary' : i % 2 ? 'bg-slate-400 dark:bg-slate-500' : 'bg-slate-500 dark:bg-slate-400');
  return (
    <div className="rounded-xl border bg-card p-4 shadow-sm">
      <div className="flex flex-wrap items-center gap-2">
        <Link to={`/manufacturing/plm/products/${row.itemId}?tab=versions`} className="font-medium text-primary hover:underline">
          <span className="font-mono text-xs">{row.code}</span> {row.name}
        </Link>
        <RevisionBadge revision={row.currentRevision} />
        <LifecyclePill state={row.lifecycleState} />
        <span className="ml-auto text-sm text-muted-foreground">{tx('{{q}} on hand', { q: plain(row.onHand, 3) })} · {tx('{{n}} orders built', { n: num(row.ordersBuilt) })}</span>
      </div>
      {total > 0 && parts.length > 0 ? (
        <>
          <div className="mt-3 flex h-2 w-full gap-0.5 overflow-hidden rounded-full" role="img"
            aria-label={parts.map((p) => `${p.label}: ${plain(p.qty, 3)} (${share(p.qty).toFixed(0)} %)`).join(', ')}>
            {parts.map((p, i) => (
              <div key={p.key} className={cn('h-full first:rounded-l-full last:rounded-r-full', fill(p, i))}
                style={{ width: `${share(p.qty)}%` }} title={`${p.label}: ${plain(p.qty, 3)} (${share(p.qty).toFixed(0)} %)`} />
            ))}
          </div>
          <Table className="mt-3">
            <TableHeader>
              <TableRow>
                <TableHead>{tx('Revision')}</TableHead>
                <TableHead className="text-right">{tx('Exact (batches)')}</TableHead>
                <TableHead className="text-right">{tx('Estimated')}</TableHead>
                <TableHead className="text-right">{tx('Quantity')}</TableHead>
                <TableHead className="text-right">{tx('Share')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {row.revisions.map((v, i) => (
                <TableRow key={v.revision ?? i}>
                  <TableCell>
                    <span className="flex items-center gap-2">
                      <span className={cn('h-2 w-3 rounded-full', v.status === 'current' || v.revision === row.currentRevision ? 'bg-primary'
                        : i % 2 ? 'bg-slate-400 dark:bg-slate-500' : 'bg-slate-500 dark:bg-slate-400')} />
                      {v.revision ? <RevisionBadge revision={v.revision} /> : '—'}
                      {(v.status === 'current' || v.revision === row.currentRevision) && <span className="text-xs text-muted-foreground">{tx('current')}</span>}
                    </span>
                  </TableCell>
                  <TableCell className="text-right tabular-nums">{plain(v.exact, 3)}</TableCell>
                  <TableCell className="text-right tabular-nums">{plain(v.estimated, 3)}</TableCell>
                  <TableCell className="text-right font-medium tabular-nums">{plain(v.quantity, 3)}</TableCell>
                  <TableCell className="text-right tabular-nums">{share(num(v.quantity)).toFixed(0)} %</TableCell>
                </TableRow>
              ))}
              {num(row.unknown) > 0 && (
                <TableRow>
                  <TableCell><span className="flex items-center gap-2"><span className="h-2 w-3 rounded-full bg-slate-200 dark:bg-slate-700" />{tx('Unknown revision')}</span>
                    <p className="text-[11px] text-muted-foreground">{tx('bought, or made before PLM recorded it')}</p></TableCell>
                  <TableCell className="text-right">—</TableCell>
                  <TableCell className="text-right">—</TableCell>
                  <TableCell className="text-right font-medium tabular-nums">{plain(row.unknown, 3)}</TableCell>
                  <TableCell className="text-right tabular-nums">{share(num(row.unknown)).toFixed(0)} %</TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </>
      ) : <p className="mt-2 text-sm text-muted-foreground">{tx('Nothing on hand.')}</p>}
    </div>
  );
}
