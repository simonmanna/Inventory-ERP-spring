import { useState } from 'react';
import { Link } from 'react-router-dom';
import {
  AlertTriangle, Ban, CheckCircle2, ClipboardCheck, Coins, GitMerge, Lock, Microscope, PackageSearch, TrendingDown,
  TrendingUp, XCircle,
} from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { StatusPill } from '@/components/list/list-page';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useAuthStore } from '@/stores/auth.store';
import { num, type ChangeDetail, type KanbanState } from '@/features/manufacturing/api-plm';
import {
  COST_POLICY, PRODUCT_FIELDS, useChangeFinancial, useRebaseChange, useRecordFirstArticle, useSetKanbanState,
} from '@/features/manufacturing/api-plm-app';
import { money, useOrgCurrency } from '@/lib/format';
import { notify } from '@/lib/notify';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { EmptyState, Field, Panel } from './plm-ui';
import { ChangeTypeChip, FaiPill, KANBAN_STATES, KanbanDot, TagChips } from './plm-app-ui';
import { fieldValue, plain, signed } from './plm-format';

/** Change-order panels of the PLM application (ADR-S086). */

// ---- type, tags, kanban state -----------------------------------------------------------------------------------------

export function ChangeMeta({ eco }: { eco: ChangeDetail }) {
  const canChange = useAuthStore((s) => s.hasPermission)(PERMISSIONS.plm.change);
  const open = eco.status === 'draft' || eco.status === 'approved';
  const setState = useSetKanbanState();
  const [blocking, setBlocking] = useState(false);
  const [reason, setReason] = useState('');
  const state: KanbanState = eco.kanbanState ?? 'normal';
  const choose = (k: KanbanState) => {
    if (k === 'blocked') { setReason(eco.blockedReason ?? ''); setBlocking(true); return; }
    setState.mutate({ id: eco.id, kanbanState: k }, { onSuccess: () => notify.success(tx(KANBAN_STATES[k].label)) });
  };
  return (
    <>
      <ChangeTypeChip name={eco.changeType?.name ?? eco.changeTypeName} color={eco.changeType?.color ?? eco.changeTypeColor} />
      <TagChips tags={eco.tags} />
      {open && (canChange ? (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <button type="button" className="inline-flex items-center gap-1.5 rounded-md border bg-background px-1.5 py-0.5 text-[11px] hover:bg-muted">
              <KanbanDot state={state} />{tx(KANBAN_STATES[state].label)}
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start">
            <DropdownMenuLabel>{tx('Kanban state')}</DropdownMenuLabel>
            <DropdownMenuSeparator />
            {(Object.keys(KANBAN_STATES) as KanbanState[]).map((k) => (
              <DropdownMenuItem key={k} disabled={k === state && k !== 'blocked'} onClick={() => choose(k)}>
                <KanbanDot state={k} className="mr-2" />{tx(KANBAN_STATES[k].label)}
              </DropdownMenuItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>
      ) : (
        <span className="inline-flex items-center gap-1.5 text-[11px]"><KanbanDot state={state} />{tx(KANBAN_STATES[state].label)}</span>
      ))}
      <Dialog open={blocking} onOpenChange={(o) => !o && setBlocking(false)}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>{tx('Block {{n}}', { n: eco.number })}</DialogTitle>
            <DialogDescription>{tx('Everyone sees why on the board.')}</DialogDescription>
          </DialogHeader>
          <Field label={tx('Reason')}>
            <Textarea rows={3} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} placeholder={tx('e.g. waiting for the supplier drawing')} />
          </Field>
          <DialogFooter>
            <Button variant="outline" onClick={() => setBlocking(false)}>{tx('Cancel')}</Button>
            <Button disabled={setState.isPending} onClick={() => setState.mutate(
              { id: eco.id, kanbanState: 'blocked', blockedReason: reason.trim() || undefined },
              { onSuccess: () => { notify.success(tx('Blocked')); setBlocking(false); } },
            )}>{tx('Block')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}

export function BlockedBanner({ eco }: { eco: ChangeDetail }) {
  if (eco.kanbanState !== 'blocked' || !(eco.status === 'draft' || eco.status === 'approved')) return null;
  return (
    <div className="flex items-start gap-2 rounded-lg border border-rose-200 bg-rose-50 px-4 py-2 text-sm text-rose-800 dark:border-rose-500/30 dark:bg-rose-500/10 dark:text-rose-300">
      <Ban className="mt-0.5 h-4 w-4 shrink-0" /><span><b>{tx('Blocked')}</b>{eco.blockedReason && <>: {eco.blockedReason}</>}</span>
    </div>
  );
}

// ---- conflict / rebase -----------------------------------------------------------------------------------------------

/** The BOM this change was based on is no longer the active version: rebase carries the upstream edits in. */
export function ConflictBanner({ eco }: { eco: ChangeDetail }) {
  const canChange = useAuthStore((s) => s.hasPermission)(PERMISSIONS.plm.change);
  const rebase = useRebaseChange();
  if (!eco.conflict) return null;
  const run = () => {
    if (!confirm(tx('Rebase {{n}} onto {{c}} v{{v}}? Edits made upstream are carried into this change; where both sides changed the same value, this change keeps its own.',
      { n: eco.number, c: eco.conflict!.currentCode, v: eco.conflict!.currentVersion }))) return;
    rebase.mutate(eco.id, {
      onSuccess: (r) => {
        const conflicts = r.rebase?.conflicts?.length ?? 0;
        const carried = r.rebase?.carriedIn?.length ?? 0;
        notify.success(tx('Rebased onto {{c}} v{{v}}', { c: r.rebase.onto.code, v: r.rebase.onto.version }),
          [tx('{{n}} upstream edits carried in', { n: carried }),
            conflicts ? tx('{{n}} conflicts kept this change’s value — review them', { n: conflicts }) : null]
            .filter(Boolean).join(' · '));
      },
    });
  };
  return (
    <div className="flex flex-wrap items-center gap-3 rounded-lg border border-amber-300 bg-amber-50 px-4 py-2 text-sm text-amber-900 dark:border-amber-500/30 dark:bg-amber-500/10 dark:text-amber-200">
      <GitMerge className="h-4 w-4 shrink-0" />
      <span className="min-w-0 flex-1">{eco.conflict.message}</span>
      {canChange && <Button size="sm" variant="outline" onClick={run} disabled={rebase.isPending}><GitMerge className="mr-1.5 h-4 w-4" />{tx('Rebase')}</Button>}
    </div>
  );
}

// ---- financial impact ------------------------------------------------------------------------------------------------

export function FinancialTab({ eco }: { eco: ChangeDetail }) {
  const { data: f, isLoading } = useChangeFinancial(eco.id, eco.type === 'bom');
  const currency = useOrgCurrency();
  if (eco.type !== 'bom') {
    return <EmptyState compact icon={Coins} title={tx('No cost impact')} hint={tx('Only BOM changes change what the product costs to make.')} />;
  }
  if (isLoading || !f) return <Skeleton className="h-64 w-full rounded-xl" />;
  if (!f.applicable) return <EmptyState compact icon={Coins} title={tx('No cost impact')} />;
  const m = (v: unknown, d = 2) => (v == null ? '—' : money(num(v as string), currency, d));
  const delta = f.rollup?.deltaToCurrentCost;
  const result = f.result;
  return (
    <div className="space-y-4">
      {!f.costVisible && (
        <p className="flex items-center gap-2 rounded-md bg-muted px-3 py-2 text-sm text-muted-foreground">
          <Lock className="h-4 w-4" />{tx('Costs are shown to users who may read inventory valuation.')}
        </p>
      )}
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <Tile label={tx('Unit cost now')} value={m(f.currentUnitCost, 4)}
          hint={f.currentSource === 'standard' ? tx('active standard') : tx('cost price')} />
        <Tile label={tx('New version roll-up')} value={m(f.rollup?.newVersion, 4)}
          hint={f.rollup?.currentVersion != null ? tx('current version rolls up to {{c}}', { c: m(f.rollup.currentVersion, 4) }) : undefined} />
        <Tile label={tx('Change per unit')} value={delta == null ? '—' : signed(delta, 4)}
          hint={f.rollup?.deltaPct != null ? `${signed(f.rollup.deltaPct, 2)} %` : undefined}
          tone={delta == null || num(delta) === 0 ? undefined : num(delta) > 0 ? 'up' : 'down'} />
        <Tile label={tx('Stock on hand')} value={plain(f.onHand?.quantity, 3)} hint={f.onHand?.value != null ? m(f.onHand.value) : undefined} />
      </div>
      {f.rollup?.error && (
        <p className="flex items-center gap-2 rounded-md bg-amber-50 px-3 py-2 text-sm text-amber-900 dark:bg-amber-500/10 dark:text-amber-200">
          <AlertTriangle className="h-4 w-4" />{tx('The roll-up could not be computed')}: {f.rollup.error}
        </p>
      )}

      <Panel title={tx('Cost policy')} icon={Coins}>
        <div className="space-y-2 text-sm">
          <p><b>{tx(COST_POLICY[f.costPolicy]?.label ?? f.costPolicy)}</b> — <span className="text-muted-foreground">{tx(COST_POLICY[f.costPolicy]?.hint ?? '')}</span></p>
          {f.revaluation && f.revaluation.amount != null && num(f.revaluation.amount) !== 0 && (
            <p>
              {tx('Activating the new standard would revalue the stock on hand by')}{' '}
              <b className={num(f.revaluation.amount) > 0 ? 'text-emerald-700 dark:text-emerald-300' : 'text-rose-700 dark:text-rose-300'}>{signed(f.revaluation.amount)}</b>{' '}
              {tx('against')} <span className="font-mono text-xs">{f.revaluation.account.code}</span> {f.revaluation.account.name}.
            </p>
          )}
          {f.activateBlocker
            ? <p className="text-muted-foreground">{tx('Activation on apply')}: {f.activateBlocker}</p>
            : <p className="text-muted-foreground">{tx('This product can have its standard activated by the change.')}</p>}
        </div>
      </Panel>

      {result && (
        <Panel title={tx('Outcome of apply')} icon={result.status === 'active' ? CheckCircle2 : ClipboardCheck}>
          <dl className="grid grid-cols-[160px_1fr] gap-x-3 gap-y-1.5 text-sm">
            {result.version != null && <><dt className="text-muted-foreground">{tx('Standard cost')}</dt><dd>v{String(result.version)} · <StatusPill tone={result.status === 'active' ? 'success' : 'info'}>{result.status}</StatusPill></dd></>}
            {result.unitCost != null && <><dt className="text-muted-foreground">{tx('Unit cost')}</dt><dd>{m(result.unitCost, 4)}{result.previousUnitCost != null && <span className="text-muted-foreground"> ({tx('was')} {m(result.previousUnitCost, 4)})</span>}</dd></>}
            {result.revaluationAmount != null && <><dt className="text-muted-foreground">{tx('Revaluation')}</dt><dd>{signed(result.revaluationAmount)} · {plain(result.revaluationQuantity, 3)} {tx('units')}</dd></>}
            {result.note && <><dt className="text-muted-foreground">{tx('Note')}</dt><dd>{result.note}</dd></>}
          </dl>
          <div className="mt-3 flex flex-wrap gap-2">
            {f.standardCostId && <Button asChild size="sm" variant="outline"><Link to="/manufacturing/standard-costs">{tx('Standard costs')}</Link></Button>}
            {f.revaluationJournalId && <Button asChild size="sm" variant="outline"><Link to={`/journal-entries/${f.revaluationJournalId}`}>{tx('Revaluation journal')}</Link></Button>}
          </div>
        </Panel>
      )}
    </div>
  );
}

/** Stat tile: the value stays in text ink; a cost going up or down is told by an icon and a word, not colour alone. */
function Tile({ label, value, hint, tone }: { label: string; value: string; hint?: string; tone?: 'up' | 'down' }) {
  return (
    <div className="rounded-xl border bg-card p-3 shadow-sm">
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className="mt-1 text-lg font-semibold">{value}</p>
      <p className="flex items-center gap-1 text-[11px] text-muted-foreground">
        {tone === 'up' && <><TrendingUp className="h-3.5 w-3.5 text-rose-600 dark:text-rose-400" />{tx('costs more')}{hint && ' · '}</>}
        {tone === 'down' && <><TrendingDown className="h-3.5 w-3.5 text-emerald-600 dark:text-emerald-400" />{tx('costs less')}{hint && ' · '}</>}
        {hint}
      </p>
    </div>
  );
}

// ---- product data -----------------------------------------------------------------------------------------------------

export function ProductDataTab({ eco }: { eco: ChangeDetail }) {
  const rows = eco.productData ?? [];
  const applied = eco.status === 'applied';
  if (!rows.length) {
    return <EmptyState compact icon={PackageSearch} title={tx('No product data on this change')} hint={tx('A product change can set the name, description, barcode, weight, volume and image when applied.')} />;
  }
  const label = (f: string) => tx(PRODUCT_FIELDS.find((p) => p.field === f)?.label ?? f);
  return (
    <div className="overflow-hidden rounded-xl border bg-card shadow-sm [&_thead_tr]:bg-muted/40">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>{tx('Field')}</TableHead>
            <TableHead>{applied ? tx('Before') : tx('Now')}</TableHead>
            <TableHead>{applied ? tx('Set to') : tx('Proposed')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((r) => (
            <TableRow key={r.field}>
              <TableCell className="font-medium">{label(r.field)}</TableCell>
              <TableCell className={cn('max-w-[280px] break-words text-sm', !applied && r.changes && 'text-muted-foreground line-through')}>
                {fieldValue(applied ? r.before ?? r.current : r.current)}
              </TableCell>
              <TableCell className={cn('max-w-[280px] break-words text-sm', r.changes && !applied && 'font-medium text-emerald-700 dark:text-emerald-300')}>
                {fieldValue(r.proposed)}
                {!applied && !r.changes && <span className="ml-1 text-xs text-muted-foreground">({tx('already set')})</span>}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

// ---- first article ----------------------------------------------------------------------------------------------------

export function FirstArticlePanel({ eco }: { eco: ChangeDetail }) {
  const canDecide = useAuthStore((s) => s.hasPermission)(PERMISSIONS.plm.approve);
  const record = useRecordFirstArticle();
  const [result, setResult] = useState<'passed' | 'failed' | null>(null);
  const [note, setNote] = useState('');
  if (!eco.firstArticle) return null;
  const status = eco.faiStatus;
  return (
    <Panel title={tx('First-article inspection')} icon={Microscope}>
      <div className="space-y-2 text-sm">
        {status ? <FaiPill status={status} /> : <p className="text-muted-foreground">{tx('Starts when the change is applied.')}</p>}
        {status === 'pending' && <p className="text-muted-foreground">{tx('The first production order made on the new version is inspected when it completes.')}</p>}
        {status === 'open' && eco.faiLot && (
          <p>{tx('Inspection lot')} <Link to={`/quality/lots/${eco.faiLot.id}`} className="font-mono text-xs text-primary hover:underline">{eco.faiLot.lotNumber}</Link>{' '}
            <span className="text-muted-foreground">({eco.faiLot.status})</span></p>
        )}
        {(status === 'passed' || status === 'failed') && eco.faiLot && (
          <p className="text-muted-foreground">{tx('Lot')} <Link to={`/quality/lots/${eco.faiLot.id}`} className="font-mono text-xs text-primary hover:underline">{eco.faiLot.lotNumber}</Link></p>
        )}
        {eco.faiOrderId && <p className="text-muted-foreground">{tx('First article from')} <Link to={`/manufacturing/orders/${eco.faiOrderId}`} className="text-primary hover:underline">{tx('production order')}</Link></p>}
        {eco.faiNote && <p className="whitespace-pre-wrap text-muted-foreground">{eco.faiNote}</p>}
        {status === 'no_plan' && canDecide && (
          <div className="flex gap-2 pt-1">
            <Button size="sm" variant="outline" onClick={() => { setNote(''); setResult('passed'); }}><CheckCircle2 className="mr-1.5 h-4 w-4 text-emerald-600" />{tx('Passed')}</Button>
            <Button size="sm" variant="outline" onClick={() => { setNote(''); setResult('failed'); }}><XCircle className="mr-1.5 h-4 w-4 text-rose-600" />{tx('Failed')}</Button>
          </div>
        )}
      </div>
      <Dialog open={result != null} onOpenChange={(o) => !o && setResult(null)}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>{result === 'passed' ? tx('First article passed') : tx('First article failed')}</DialogTitle>
            <DialogDescription>{tx('Recorded on {{n}}.', { n: eco.number })}</DialogDescription>
          </DialogHeader>
          <Field label={tx('Note')}>
            <Textarea rows={3} maxLength={2000} value={note} onChange={(e) => setNote(e.target.value)} />
          </Field>
          <DialogFooter>
            <Button variant="outline" onClick={() => setResult(null)}>{tx('Cancel')}</Button>
            <Button disabled={record.isPending} onClick={() => result && record.mutate(
              { id: eco.id, result, note: note.trim() || undefined },
              { onSuccess: () => { notify.success(tx('Recorded')); setResult(null); } },
            )}>{tx('Record')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </Panel>
  );
}
