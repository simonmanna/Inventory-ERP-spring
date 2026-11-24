import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, Factory, GitCompare, History, Minus, Plus } from 'lucide-react';
import { StatusPill } from '@/components/list/list-page';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { num, type PlmItemDetail } from '@/features/manufacturing/api-plm';
import {
  useAsBuilt, useItemRevisions, useRevisionCompare, type FieldDiff, type RevisionCompare,
} from '@/features/manufacturing/api-plm-app';
import { money, useOrgCurrency } from '@/lib/format';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { EmptyState, Panel, RevisionBadge } from './plm-ui';
import { fieldValue, plain, signed } from './plm-format';
import { day, dayTime, human, selectClass } from './vocab';

/** Versions of a PLM product (ADR-S086): revision history with snapshots, compare two revisions, as-built orders. */
export function VersionsTab({ item }: { item: PlmItemDetail }) {
  const { data: revisions = [], isLoading } = useItemRevisions(item.id);
  const { data: built = [], isLoading: builtLoading } = useAsBuilt(item.id);
  const currency = useOrgCurrency();
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  useEffect(() => {
    if (revisions.length >= 2 && !from && !to) { setTo(revisions[0].id); setFrom(revisions[1].id); }
  }, [revisions, from, to]);
  const { data: diff, isFetching } = useRevisionCompare(from || undefined, to || undefined);

  if (isLoading) return <Skeleton className="h-64 w-full rounded-xl" />;
  if (!revisions.length) return <EmptyState compact icon={History} title={tx('No revisions recorded yet')} />;
  return (
    <div className="space-y-4">
      <div className="overflow-hidden rounded-xl border bg-card shadow-sm [&_thead_tr]:bg-muted/40">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{tx('Revision')}</TableHead>
              <TableHead>{tx('Effective')}</TableHead>
              <TableHead>{tx('Change')}</TableHead>
              <TableHead>{tx('BOM')}</TableHead>
              <TableHead>{tx('Routing')}</TableHead>
              <TableHead className="text-right">{tx('Unit cost')}</TableHead>
              <TableHead className="text-right">{tx('Orders built')}</TableHead>
              <TableHead>{tx('By')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {revisions.map((r) => (
              <TableRow key={r.id}>
                <TableCell>
                  <span className="flex items-center gap-1.5"><RevisionBadge revision={r.revision} />
                    {r.status === 'current' && <StatusPill tone="success">{tx('current')}</StatusPill>}</span>
                  {!r.recorded && <p className="text-[11px] text-muted-foreground">{tx('from history, no snapshot')}</p>}
                </TableCell>
                <TableCell className="whitespace-nowrap text-sm text-muted-foreground">{day(r.effectiveFrom)} → {r.effectiveTo ? day(r.effectiveTo) : tx('now')}</TableCell>
                <TableCell className="text-sm">
                  {r.changeId && r.changeNumber
                    ? <Link to={`/manufacturing/plm/changes/${r.changeId}`} className="font-mono text-xs text-primary hover:underline">{r.changeNumber}</Link>
                    : <span className="text-muted-foreground">{r.note ?? '—'}</span>}
                </TableCell>
                <TableCell className="text-sm">
                  {r.bom ? <Link to={`/manufacturing/boms/${r.bom.id}`} className="text-primary hover:underline"><span className="font-mono text-xs">{r.bom.code}</span> v{r.bom.version}</Link> : '—'}
                  {r.bom && <span className="text-xs text-muted-foreground"> · {tx('{{n}} lines', { n: r.bom.lines })}</span>}
                </TableCell>
                <TableCell className="text-sm">{r.routing ? <>{r.routing.name} <span className="text-xs text-muted-foreground">· {tx('{{n}} ops', { n: r.routing.operations })}</span></> : '—'}</TableCell>
                <TableCell className="text-right text-sm tabular-nums">
                  {r.unitCost != null ? money(num(r.unitCost), currency, 4) : '—'}
                  {r.costSource && <p className="text-[10px] text-muted-foreground">{r.costSource === 'standard' ? tx('standard') : tx('cost price')}</p>}
                </TableCell>
                <TableCell className="text-right text-sm tabular-nums">{num(r.ordersBuilt)}</TableCell>
                <TableCell className="text-sm text-muted-foreground">{r.createdByName ?? '—'}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>

      {revisions.length >= 2 && (
        <Panel title={tx('Compare revisions')} icon={GitCompare} actions={
          <div className="flex items-center gap-2">
            <select className={cn(selectClass, 'h-8 w-28')} value={from} onChange={(e) => setFrom(e.target.value)}>
              {revisions.map((r) => <option key={r.id} value={r.id}>{r.revision}</option>)}
            </select>
            <ArrowRight className="h-4 w-4 text-muted-foreground" />
            <select className={cn(selectClass, 'h-8 w-28')} value={to} onChange={(e) => setTo(e.target.value)}>
              {revisions.map((r) => <option key={r.id} value={r.id}>{r.revision}</option>)}
            </select>
          </div>
        }>
          {from === to ? <p className="text-sm text-muted-foreground">{tx('Pick two different revisions.')}</p>
            : isFetching && !diff ? <Skeleton className="h-32 w-full" />
              : diff ? <CompareView diff={diff} currency={currency} /> : null}
        </Panel>
      )}

      <Panel title={tx('As built')} icon={Factory}>
        {builtLoading ? <Skeleton className="h-24 w-full" /> : !built.length
          ? <p className="text-sm text-muted-foreground">{tx('No completed production orders recorded yet.')}</p> : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('Order')}</TableHead>
                  <TableHead>{tx('Revision')}</TableHead>
                  <TableHead>{tx('BOM')}</TableHead>
                  <TableHead>{tx('Batch')}</TableHead>
                  <TableHead className="text-right">{tx('Quantity')}</TableHead>
                  <TableHead>{tx('Completed')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {built.map((b) => (
                  <TableRow key={b.id}>
                    <TableCell><Link to={`/manufacturing/orders/${b.orderId}`} className="font-mono text-xs text-primary hover:underline">{b.orderCode}</Link></TableCell>
                    <TableCell>
                      {b.revision ? <RevisionBadge revision={b.revision} /> : '—'}
                      {b.matchedBy !== 'bom' && <span className="ml-1 text-[10px] text-muted-foreground">({b.matchedBy === 'date' ? tx('by date') : tx('unknown')})</span>}
                    </TableCell>
                    <TableCell className="text-sm">{b.bomVersion != null ? `v${b.bomVersion}` : '—'}</TableCell>
                    <TableCell className="font-mono text-xs">{b.batchNumber ?? '—'}</TableCell>
                    <TableCell className="text-right tabular-nums">{plain(b.quantity, 3)}</TableCell>
                    <TableCell className="text-sm text-muted-foreground">{dayTime(b.completedAt)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
      </Panel>
    </div>
  );
}

function CompareView({ diff, currency }: { diff: RevisionCompare; currency: string }) {
  const empty = !diff.product.length && !diff.bom.header.length && !diff.bom.added.length && !diff.bom.removed.length
    && !diff.bom.changed.length && !diff.routing.added.length && !diff.routing.removed.length && !diff.routing.changed.length
    && !diff.documents.added.length && !diff.documents.removed.length && !diff.documents.revised.length
    && (diff.cost.delta == null || num(diff.cost.delta) === 0);
  const sameBom = diff.bom.from?.id === diff.bom.to?.id;
  const bomLabel = useMemo(() => (b: { code: string; version: number } | null) => (b ? `${b.code} v${b.version}` : '—'), []);
  return (
    <div className="space-y-4 text-sm">
      {!diff.recorded && <p className="text-xs text-muted-foreground">{tx('One revision predates snapshots: only what was recorded can be compared.')}</p>}
      {empty && <p className="text-muted-foreground">{tx('Nothing changed between these revisions.')}</p>}
      {diff.product.length > 0 && <Section title={tx('Product data')}><FieldDiffs rows={diff.product} /></Section>}
      {(!sameBom || diff.bom.header.length > 0 || diff.bom.added.length > 0 || diff.bom.removed.length > 0 || diff.bom.changed.length > 0) && (
        <Section title={`${tx('BOM')} · ${bomLabel(diff.bom.from)} → ${bomLabel(diff.bom.to)}`}>
          <FieldDiffs rows={diff.bom.header} />
          <ul className="space-y-1">
            {diff.bom.added.map((l) => <li key={`a${l.componentProductId}`} className="flex items-center gap-2 text-emerald-700 dark:text-emerald-300"><Plus className="h-3.5 w-3.5" /><span className="font-mono text-xs">{l.componentCode}</span> {l.componentName} × {plain(l.quantity, 4)}</li>)}
            {diff.bom.removed.map((l) => <li key={`r${l.componentProductId}`} className="flex items-center gap-2 text-rose-700 dark:text-rose-300"><Minus className="h-3.5 w-3.5" /><span className="font-mono text-xs">{l.componentCode}</span> {l.componentName} × {plain(l.quantity, 4)}</li>)}
            {diff.bom.changed.map((l) => (
              <li key={`c${l.componentProductId}`}>
                <span className="font-mono text-xs">{l.componentCode}</span> {l.componentName}:{' '}
                {l.changes.map((c) => <span key={c.field} className="mr-2">{human(c.field)} {fieldValue(c.from)} → <b>{fieldValue(c.to)}</b></span>)}
              </li>
            ))}
          </ul>
        </Section>
      )}
      {(diff.routing.added.length > 0 || diff.routing.removed.length > 0 || diff.routing.changed.length > 0) && (
        <Section title={`${tx('Routing')} · ${diff.routing.from?.name ?? '—'} → ${diff.routing.to?.name ?? '—'}`}>
          <ul className="space-y-1">
            {diff.routing.added.map((o) => <li key={`a${o.sequence}`} className="text-emerald-700 dark:text-emerald-300">+ {o.sequence}. {o.name}</li>)}
            {diff.routing.removed.map((o) => <li key={`r${o.sequence}`} className="text-rose-700 dark:text-rose-300">− {o.sequence}. {o.name}</li>)}
            {diff.routing.changed.map((o) => (
              <li key={`c${o.sequence}`}>{o.sequence}. {o.name}: {o.changes.map((c) => <span key={c.field} className="mr-2">{human(c.field)} {fieldValue(c.from)} → <b>{fieldValue(c.to)}</b></span>)}</li>
            ))}
          </ul>
        </Section>
      )}
      {(diff.documents.added.length > 0 || diff.documents.removed.length > 0 || diff.documents.revised.length > 0) && (
        <Section title={tx('Documents')}>
          <ul className="space-y-1">
            {diff.documents.added.map((d) => <li key={`a${d.number}`} className="text-emerald-700 dark:text-emerald-300">+ {d.number} {d.title}</li>)}
            {diff.documents.removed.map((d) => <li key={`r${d.number}`} className="text-rose-700 dark:text-rose-300">− {d.number} {d.title}</li>)}
            {diff.documents.revised.map((d) => <li key={`v${d.number}`}>{d.number} {d.title}: v{String(d.from.version)} → <b>v{String(d.to.version)}</b></li>)}
          </ul>
        </Section>
      )}
      {diff.cost.delta != null && num(diff.cost.delta) !== 0 && (
        <Section title={tx('Unit cost')}>
          <p>{money(num(diff.cost.from), currency, 4)} → <b>{money(num(diff.cost.to), currency, 4)}</b>{' '}
            <span className={num(diff.cost.delta) > 0 ? 'text-rose-700 dark:text-rose-300' : 'text-emerald-700 dark:text-emerald-300'}>({signed(diff.cost.delta, 4)})</span></p>
        </Section>
      )}
    </div>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="space-y-1.5">
      <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{title}</p>
      {children}
    </div>
  );
}

function FieldDiffs({ rows }: { rows: FieldDiff[] }) {
  if (!rows.length) return null;
  return (
    <dl className="grid grid-cols-[140px_1fr] gap-x-3 gap-y-1">
      {rows.map((r) => (
        <div key={r.field} className="contents">
          <dt className="text-muted-foreground">{human(r.field)}</dt>
          <dd><span className="text-muted-foreground line-through">{fieldValue(r.from)}</span> → <b>{fieldValue(r.to)}</b></dd>
        </div>
      ))}
    </dl>
  );
}
