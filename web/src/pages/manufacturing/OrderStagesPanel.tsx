import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { CheckCircle2, Coins, GitFork, Layers, PackageMinus, PackagePlus, ReceiptText, ShieldAlert, ShieldCheck, Trash2, Undo2, XCircle } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { cn } from '@/lib/utils';
import { EmptyState, MfgCard, MfgStatus, ProgressBar, statusMeta, TONE_BORDER, TONE_FILL, TonePill } from './mfg-ui';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { useAuthStore } from '@/stores/auth.store';
import { notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import { useProductOptions } from '@/features/manufacturing/options';
import {
  type OrderStage,
  type OrderTreeNode,
  type StagedOrder,
  useCreateChildOrders,
  useInitializeStages,
  useOrderStages,
  useOrderTree,
  useReverseStagePosting,
  useReverseStagedOrder,
  useScrapQcLot,
  useStageConversion,
  useStageIssue,
  useStageOutput,
  useStageQcDecision,
} from '@/features/manufacturing/api-stages';

const qty = (s: unknown) => Number(s ?? 0).toLocaleString(undefined, { maximumFractionDigits: 4 });
const money = (s: unknown) => Number(s ?? 0).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const errMsg = (e: any) => {
  const m = e?.response?.data?.message ?? e?.message;
  return Array.isArray(m) ? m.join(', ') : m;
};


type Dlg = { kind: 'issue' | 'output' | 'conversion'; stage: OrderStage } | null;

/**
 * ADR-S011 multi-stage panel of a production order: stage timeline with WIP, gate state and per-stage actions (issue,
 * output with partial completion / scrap / co-products, conversion cost, QC pass / fail), postings with LIFO reversal,
 * and the parent / child order tree.
 */
export function OrderStagesPanel({ orderId }: { orderId: string }) {
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const { data: staged, isLoading, isError } = useOrderStages(orderId);
  const { data: tree } = useOrderTree(orderId);
  const { data: products } = useProductOptions();
  const initialize = useInitializeStages();
  const children = useCreateChildOrders();
  const reverseOrder = useReverseStagedOrder();
  const reversePosting = useReverseStagePosting();
  const qc = useStageQcDecision();
  const scrapLot = useScrapQcLot();
  const [dlg, setDlg] = useState<Dlg>(null);

  const productName = useMemo(() => {
    const m = new Map((products ?? []).map((p) => [p.id, p.name]));
    return (id?: string | null) => (id ? m.get(id) ?? id.slice(0, 8) : '—');
  }, [products]);

  if (isLoading) return <Card><CardContent className="flex items-center gap-2 p-4 text-sm text-muted-foreground"><Layers className="h-4 w-4 animate-pulse" aria-hidden />{tx('Loading stages…')}</CardContent></Card>;
  if (isError || !staged) return <Card><CardContent className="p-4 text-sm text-destructive">{tx('Could not load the stages of this order.')}</CardContent></Card>;

  const canCreate = hasPermission(PERMISSIONS.productionOrder.create);
  const canIssue = hasPermission(PERMISSIONS.productionOrder.start);
  const canOutput = hasPermission(PERMISSIONS.productionOrder.complete);
  const canQc = hasPermission(PERMISSIONS.productionOrder.qc);
  const canReverse = hasPermission(PERMISSIONS.productionOrder.cancel);
  const open = ['confirmed', 'in_progress'].includes(staged.status);
  const notStarted = ['draft', 'confirmed'].includes(staged.status) && !staged.startedAt;

  const run = async (p: Promise<unknown>, ok: string) => {
    try {
      await p;
      notify.success(ok);
    } catch (e) {
      notify.error(tx('Action failed'), errMsg(e));
    }
  };

  const allPostings = staged.stages.flatMap((s) => s.postings).filter((p) => !p.reversedAt)
    .sort((a, b) => (a.postedAt < b.postedAt ? 1 : -1));
  const latest = allPostings[0];

  return (
    <div className="space-y-4">
      <MfgCard icon={Layers} title={tx('Stages')}
        description={staged.multiStage ? <>{tx('Work in progress')}: <span className="font-semibold text-foreground">{money(staged.wipValue)}</span> · {tx('{{done}} of {{total}} stages complete', { done: staged.stages.filter((s) => s.status === 'completed').length, total: staged.stages.length })}</> : undefined}
        actions={
          <>
            {!staged.multiStage && notStarted && canCreate && (
              <Button size="sm" variant="outline" disabled={initialize.isPending}
                onClick={() => run(initialize.mutateAsync(orderId), tx('Order now runs in stages.'))}>
                <Layers className="mr-1 h-3 w-3" /> {tx('Run in stages')}
              </Button>
            )}
            {notStarted && canCreate && (
              <Button size="sm" variant="outline" disabled={children.isPending}
                onClick={() => run(children.mutateAsync({ orderId, body: { netAgainstStock: true } }), tx('Child orders created.'))}>
                <GitFork className="mr-1 h-3 w-3" /> {tx('Create child orders')}
              </Button>
            )}
            {staged.multiStage && canReverse && allPostings.length > 0 && staged.status !== 'cancelled' && (
              <Button size="sm" variant="destructive" disabled={reverseOrder.isPending}
                onClick={() => run(reverseOrder.mutateAsync({ orderId, reason: 'reversed from UI' }), tx('All stage postings reversed; order cancelled.'))}>
                <Undo2 className="mr-1 h-3 w-3" /> {tx('Reverse all stages')}
              </Button>
            )}
          </>
        }>
        {!staged.multiStage ? (
          <EmptyState compact icon={Layers} title={tx('Single-step order')}
            description={tx('This order runs as one step. Configure stages on the routing (Recipes → stage set-up) and use "Run in stages" before it starts.')} />
        ) : (
          <ol className="relative flex flex-col gap-4 lg:flex-row lg:gap-0 lg:overflow-x-auto lg:pb-2">
            {staged.stages.map((s, i) => {
              const meta = statusMeta(s.status, 'stage');
              const done = s.status === 'completed';
              const processed = Number(s.qtyGood) + Number(s.qtyScrap);
              const yieldPct = processed > 0 ? (Number(s.qtyGood) / processed) * 100 : null;
              const progress = Number(s.plannedOutputQty) > 0 ? (Number(s.qtyGood) / Number(s.plannedOutputQty)) * 100 : null;
              const last = i === staged.stages.length - 1;
              return (
                <li key={s.id} className="relative flex min-w-0 lg:w-80 lg:shrink-0">
                  {!last && (
                    <>
                      <span className={cn('absolute left-5 top-12 -bottom-4 w-0.5 lg:hidden', done ? TONE_FILL.success : 'bg-border')} aria-hidden />
                      <span className={cn('absolute right-0 top-5 hidden h-0.5 w-6 lg:block', done ? TONE_FILL.success : 'bg-border')} aria-hidden />
                    </>
                  )}
                  <div className={cn('relative z-[1] flex w-full min-w-0 gap-3 lg:mr-6 lg:flex-col')}>
                    <span className={cn('grid h-10 w-10 shrink-0 place-items-center rounded-full text-sm font-bold ring-4 ring-card', done ? 'bg-emerald-500 text-white' : s.status === 'in_progress' ? 'bg-violet-500 text-white' : s.status === 'cancelled' ? 'bg-rose-500 text-white' : 'bg-muted text-muted-foreground')}>
                      {done ? <CheckCircle2 className="h-5 w-5" aria-label={meta.label()} /> : s.sequence}
                    </span>
                    <div className={cn('min-w-0 flex-1 rounded-xl border bg-card p-3 shadow-sm', s.status === 'in_progress' && 'border-violet-400/60 ring-1 ring-violet-400/30', s.blockedBy && 'border-rose-400/60')}>
                      <div className="flex items-start justify-between gap-2">
                        <div className="min-w-0">
                          <p className="text-[11px] uppercase tracking-wide text-muted-foreground">{tx('Stage')} {s.sequence}{s.isLast ? ` · ${tx('final')}` : ''}</p>
                          <p className="truncate font-semibold">{s.name}</p>
                          <p className="truncate text-xs text-muted-foreground">
                            {s.outputProductId ? productName(s.outputProductId) : tx('pass-through')} · {s.issueMode === 'manual' ? tx('manual issue') : tx('backflush')}
                          </p>
                        </div>
                        <div className="flex flex-col items-end gap-1">
                          <MfgStatus status={s.status} kind="stage" size="xs" />
                          {s.qcGate && <MfgStatus status={s.qcStatus} kind="qc" size="xs" label={`${tx('QC')} · ${statusMeta(s.qcStatus, 'qc').label()}`} />}
                        </div>
                      </div>
                      <ProgressBar className="mt-2" value={progress} showLabel tone={done ? 'success' : 'progress'} ariaLabel={tx('Progress')} />
                      <dl className="mt-2 grid grid-cols-3 gap-x-2 gap-y-1.5 text-xs">
                        <div><dt className="text-muted-foreground">{tx('Planned')}</dt><dd className="font-medium tabular-nums">{qty(s.plannedOutputQty)}</dd></div>
                        <div><dt className="text-muted-foreground">{tx('Good')}</dt><dd className="font-medium tabular-nums text-emerald-700 dark:text-emerald-400">{qty(s.qtyGood)}</dd></div>
                        <div><dt className="text-muted-foreground">{tx('Scrap')}</dt><dd className={cn('font-medium tabular-nums', Number(s.qtyScrap) > 0 && 'text-amber-700 dark:text-amber-400')}>{qty(s.qtyScrap)}</dd></div>
                        <div><dt className="text-muted-foreground">{tx('Input cost')}</dt><dd className="tabular-nums">{money(Number(s.materialCost) + Number(s.transferredInCost) + Number(s.conversionCost))}</dd></div>
                        <div><dt className="text-muted-foreground">{tx('Output cost')}</dt><dd className="tabular-nums">{money(s.outputCost)}</dd></div>
                        <div><dt className="text-muted-foreground">{tx('WIP')}</dt><dd className="font-semibold tabular-nums">{money(s.wipValue)}</dd></div>
                        <div><dt className="text-muted-foreground">{tx('Yield')}</dt><dd className="tabular-nums">{yieldPct == null ? '—' : `${yieldPct.toFixed(1)}%`}</dd></div>
                      </dl>
                      {Number(s.qtyQuarantined) > 0 && (
                        <p className="mt-2 flex items-center gap-1 rounded-md bg-amber-50 px-2 py-1 text-xs text-amber-800 dark:bg-amber-500/15 dark:text-amber-300"><ShieldAlert className="h-3 w-3" aria-hidden />{tx('In QC quarantine')}: {qty(s.qtyQuarantined)}</p>
                      )}
                      {!s.isLast && s.outputProductId && Number(s.intermediateAvailable) > 0 && (
                        <p className="mt-1 text-xs text-muted-foreground">{tx('Available to next stage')}: {qty(s.intermediateAvailable)}</p>
                      )}
                      {s.blockedBy && (
                        <p className="mt-2 flex items-start gap-1 rounded-md bg-rose-50 px-2 py-1 text-xs text-rose-700 dark:bg-rose-500/15 dark:text-rose-300"><ShieldAlert className="mt-0.5 h-3 w-3 shrink-0" aria-hidden /> {s.blockedBy}</p>
                      )}
                      {open && s.status !== 'completed' && !s.blockedBy && (
                        <div className="mt-3 flex flex-wrap gap-1">
                          {canIssue && <Button size="sm" variant="outline" onClick={() => setDlg({ kind: 'issue', stage: s })}><PackageMinus className="mr-1 h-3 w-3" /> {tx('Issue')}</Button>}
                          {canOutput && <Button size="sm" onClick={() => setDlg({ kind: 'output', stage: s })}><PackagePlus className="mr-1 h-3 w-3" /> {tx('Output')}</Button>}
                          {canOutput && <Button size="sm" variant="ghost" onClick={() => setDlg({ kind: 'conversion', stage: s })}><Coins className="mr-1 h-3 w-3" /> {tx('Cost')}</Button>}
                        </div>
                      )}
                      {s.qcGate && (s.qcLots?.length ?? 0) > 0 && (
                        <ul className="mt-3 space-y-1.5 border-t pt-2">
                          {(s.qcLots ?? []).map((lot) => {
                            const held = Number(lot.qtyHeld) > 0;
                            const decidable = canQc && held && ['in_progress', 'qc_hold', 'confirmed'].includes(staged.status);
                            const lotTone = statusMeta(lot.qcStatus, 'qc').tone;
                            return (
                              <li key={lot.postingId} className={cn('rounded-lg border-l-4 bg-muted/40 p-2 text-xs', TONE_BORDER[lotTone])}>
                                <div className="flex flex-wrap items-center gap-1.5">
                                  <span className="font-mono font-semibold">{lot.code}</span>
                                  {lot.batchNumber && <span className="text-muted-foreground">· {lot.batchNumber}</span>}
                                  <MfgStatus status={lot.qcStatus} kind="qc" size="xs" />
                                  <span className="text-muted-foreground">{tx('Held')}: <b className="tabular-nums text-foreground">{qty(lot.qtyHeld)}</b></span>
                                  {lot.qcStatus === 'curing' && lot.qcPassDueAt && (
                                    <span className="text-muted-foreground">· {tx('due')} {new Date(lot.qcPassDueAt).toLocaleString()}</span>
                                  )}
                                </div>
                                {decidable && (
                                  <div className="mt-1.5 flex flex-wrap gap-1">
                                    <Button size="sm" variant="outline" className="h-7 border-emerald-500/50 px-2 text-emerald-700 hover:bg-emerald-50 dark:text-emerald-300 dark:hover:bg-emerald-500/10" disabled={qc.isPending}
                                      onClick={() => run(qc.mutateAsync({ stageId: s.id, body: { result: 'passed', postingId: lot.postingId } }), tx('QC passed — lot released.'))}>
                                      <ShieldCheck className="mr-1 h-3 w-3" /> {tx('Pass')}
                                    </Button>
                                    {lot.qcStatus !== 'failed' && (
                                      <Button size="sm" variant="outline" className="h-7 border-rose-500/50 px-2 text-rose-700 hover:bg-rose-50 dark:text-rose-300 dark:hover:bg-rose-500/10" disabled={qc.isPending}
                                        onClick={() => run(qc.mutateAsync({ stageId: s.id, body: { result: 'failed', postingId: lot.postingId } }), tx('QC failed — lot stays in quarantine.'))}>
                                        <XCircle className="mr-1 h-3 w-3" /> {tx('Fail')}
                                      </Button>
                                    )}
                                    {lot.qcStatus === 'failed' && (
                                      <Button size="sm" variant="destructive" className="h-7 px-2" disabled={scrapLot.isPending}
                                        onClick={() => {
                                          const reason = window.prompt(tx('Why is this lot scrapped?'));
                                          if (!reason || !reason.trim()) return;
                                          run(scrapLot.mutateAsync({ postingId: lot.postingId, body: { reason: reason.trim() } }), tx('Lot scrapped from quarantine.'));
                                        }}>
                                        <Trash2 className="mr-1 h-3 w-3" /> {tx('Scrap')}
                                      </Button>
                                    )}
                                  </div>
                                )}
                              </li>
                            );
                          })}
                        </ul>
                      )}
                      {s.qcGate && canQc && ['pending', 'failed'].includes(s.qcStatus) && Number(s.qtyQuarantined) > 0 && ['in_progress', 'qc_hold', 'confirmed'].includes(staged.status) && (
                        <div className="mt-2 flex flex-wrap gap-1">
                          <Button size="sm" variant="outline" disabled={qc.isPending}
                            onClick={() => run(qc.mutateAsync({ stageId: s.id, body: { result: 'passed' } }), tx('QC passed — output released.'))}>
                            <ShieldCheck className="mr-1 h-3 w-3" /> {tx('Pass QC')}
                          </Button>
                          <Button size="sm" variant="ghost" disabled={qc.isPending}
                            onClick={() => run(qc.mutateAsync({ stageId: s.id, body: { result: 'failed' } }), tx('QC failed — output stays in quarantine.'))}>
                            <XCircle className="mr-1 h-3 w-3" /> {tx('Fail QC')}
                          </Button>
                        </div>
                      )}
                    </div>
                  </div>
                </li>
              );
            })}
          </ol>
        )}
      </MfgCard>

      {staged.multiStage && (
        <MfgCard icon={ReceiptText} title={tx('Stage postings')}>
          {!staged.stages.some((s) => s.postings.length) ? (
            <EmptyState compact icon={ReceiptText} title={tx('Nothing posted yet.')} />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[560px] text-sm">
                <thead className="text-left text-xs uppercase tracking-wide text-muted-foreground">
                  <tr>
                    <th className="pb-2 pr-3 font-semibold">{tx('Code')}</th>
                    <th className="px-3 pb-2 font-semibold">{tx('Kind')}</th>
                    <th className="px-3 pb-2 text-right font-semibold">{tx('Good')}</th>
                    <th className="px-3 pb-2 text-right font-semibold">{tx('Scrap')}</th>
                    <th className="px-3 pb-2 text-right font-semibold">{tx('Value')}</th>
                    <th className="px-3 pb-2 font-semibold">{tx('Status')}</th>
                    <th className="pb-2" />
                  </tr>
                </thead>
                <tbody className="divide-y">
                  {staged.stages.flatMap((s) => s.postings).map((p) => {
                    const value = p.lines.filter((l) => ['material', 'intermediate_in', 'conversion', 'transfer_in'].includes(l.role))
                      .reduce((a, l) => a + Number(l.value), 0);
                    return (
                      <tr key={p.id} className={cn(p.reversedAt && 'text-muted-foreground line-through decoration-muted-foreground/50')}>
                        <td className="py-2 pr-3 font-mono text-xs">{p.code}</td>
                        <td className="px-3 py-2"><TonePill tone={p.kind === 'output' ? 'info' : p.kind === 'qc_release' ? 'success' : p.kind === 'qc_scrap' ? 'danger' : 'neutral'}>{p.kind.replace('_', ' ')}{p.completesStage ? ` · ${tx('completes')}` : ''}</TonePill></td>
                        <td className="px-3 py-2 text-right tabular-nums">{qty(p.qtyGood)}</td>
                        <td className="px-3 py-2 text-right tabular-nums">{qty(p.qtyScrap)}</td>
                        <td className="px-3 py-2 text-right tabular-nums">{money(value)}</td>
                        <td className="px-3 py-2">{p.reversedAt ? <MfgStatus status="cancelled" label={tx('reversed')} size="xs" /> : <span className="inline-flex items-center gap-1 text-xs text-emerald-700 dark:text-emerald-400"><CheckCircle2 className="h-4 w-4" aria-hidden />{tx('Posted')}</span>}</td>
                        <td className="py-2 text-right">
                          {canReverse && latest?.id === p.id && (
                            <Button size="sm" variant="ghost" disabled={reversePosting.isPending}
                              onClick={() => run(reversePosting.mutateAsync({ postingId: p.id, reason: 'reversed from UI' }), tx('Posting reversed.'))}>
                              <Undo2 className="mr-1 h-3 w-3" /> {tx('Reverse')}
                            </Button>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </MfgCard>
      )}

      {tree && tree.children.length > 0 && (
        <MfgCard icon={GitFork} title={tx('Child orders')}
          description={<>{tx('Rolled-up status')}: {tree.rolledStatus.replace('_', ' ')} · {Number(tree.progressPct).toFixed(0)}%</>}>
          <ProgressBar className="mb-3" value={Number(tree.progressPct)} showLabel ariaLabel={tx('Progress')} />
          <TreeList nodes={tree.children} />
        </MfgCard>
      )}

      {dlg && <StageDialog dlg={dlg} order={staged} onClose={() => setDlg(null)} productName={productName} />}
    </div>
  );
}

function TreeList({ nodes }: { nodes: OrderTreeNode[] }) {
  return (
    <ul className="space-y-1.5 border-l-2 border-dashed pl-4">
      {nodes.map((n) => (
        <li key={n.id} className="relative before:absolute before:-left-4 before:top-4 before:h-0.5 before:w-3 before:bg-border">
          <div className="flex flex-wrap items-center gap-2 rounded-lg border bg-card px-3 py-2 text-sm">
            <Link className="font-mono text-xs font-semibold text-primary hover:underline" to={`/manufacturing/orders/${n.id}`}>{n.orderCode}</Link>
            <span className="min-w-0 truncate">{n.productName}</span>
            <span className="text-muted-foreground">× {qty(n.peg?.quantity ?? n.plannedQty)}</span>
            <MfgStatus status={n.rolledStatus} kind="order" size="xs" />
            <ProgressBar className="w-28" value={Number(n.progressPct)} showLabel size="xs" ariaLabel={tx('Progress')} />
          </div>
          {n.children.length > 0 && <div className="mt-1.5"><TreeList nodes={n.children} /></div>}
        </li>
      ))}
    </ul>
  );
}

function StageDialog({ dlg, order, onClose, productName }: {
  dlg: NonNullable<Dlg>;
  order: StagedOrder;
  onClose: () => void;
  productName: (id?: string | null) => string;
}) {
  const s = dlg.stage;
  const prev = order.stages.find((x) => x.sequence === s.sequence - 1);
  const issue = useStageIssue();
  const output = useStageOutput();
  const conversion = useStageConversion();
  const [lines, setLines] = useState<Record<string, string>>({});
  const [intermediate, setIntermediate] = useState('');
  const [good, setGood] = useState('');
  const [scrap, setScrap] = useState('');
  const [complete, setComplete] = useState(false);
  const [others, setOthers] = useState<Record<string, string>>({});
  const [amount, setAmount] = useState('');
  const [kind, setKind] = useState('labour');
  const [error, setError] = useState<string | null>(null);
  const coOutputs = s.outputs.filter((o) => o.outputRole && o.outputRole !== 'main');

  const submit = async () => {
    setError(null);
    try {
      if (dlg.kind === 'issue') {
        const body: Array<Record<string, unknown>> = Object.entries(lines).filter(([, v]) => Number(v) > 0)
          .map(([materialId, v]) => ({ materialId, quantity: Number(v) }));
        if (Number(intermediate) > 0 && prev?.outputProductId) body.push({ productId: prev.outputProductId, quantity: Number(intermediate) });
        if (!body.length) return setError(tx('Enter at least one quantity.'));
        await issue.mutateAsync({ stageId: s.id, body: { lines: body } });
        notify.success(tx('Issued into the stage.'));
      } else if (dlg.kind === 'output') {
        if (!(Number(good) >= 0) || (good === '' && !complete && !scrap)) return setError(tx('Enter the good quantity.'));
        const outs = coOutputs.filter((o) => others[o.id] !== undefined && others[o.id] !== '')
          .map((o) => ({ outputId: o.id, quantity: Number(others[o.id]) }));
        await output.mutateAsync({ stageId: s.id, body: { qtyGood: Number(good || 0), qtyScrap: scrap ? Number(scrap) : undefined, complete, outputs: outs.length ? outs : undefined } });
        notify.success(complete ? tx('Stage completed.') : tx('Output recorded.'));
      } else {
        if (!(Number(amount) > 0)) return setError(tx('Enter a positive amount.'));
        await conversion.mutateAsync({ stageId: s.id, body: { amount: Number(amount), kind } });
        notify.success(tx('Cost applied to the stage.'));
      }
      onClose();
    } catch (e) {
      setError(errMsg(e));
    }
  };

  const title = dlg.kind === 'issue' ? tx('Issue into stage') : dlg.kind === 'output' ? tx('Record stage output') : tx('Apply stage cost');
  const busy = issue.isPending || output.isPending || conversion.isPending;

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader><DialogTitle>{title} · {s.sequence}. {s.name}</DialogTitle></DialogHeader>
        <div className="space-y-3">
          {dlg.kind === 'issue' && (
            <>
              {s.materials.length === 0 && !prev?.outputProductId && <p className="text-sm text-muted-foreground">{tx('This stage has no materials to issue.')}</p>}
              {s.materials.map((m) => (
                <div key={m.id} className="grid grid-cols-[1fr_8rem] items-center gap-2">
                  <Label className="text-sm">{m.productName} <span className="text-xs text-muted-foreground">({qty(m.qtyConsumed)} / {qty(m.qtyPlanned)})</span></Label>
                  <Input type="number" min="0" step="any" value={lines[m.id] ?? ''} onChange={(e) => setLines((x) => ({ ...x, [m.id]: e.target.value }))} />
                </div>
              ))}
              {prev?.outputProductId && (
                <div className="grid grid-cols-[1fr_8rem] items-center gap-2">
                  <Label className="text-sm">{productName(prev.outputProductId)} <span className="text-xs text-muted-foreground">({tx('available')} {qty(prev.intermediateAvailable)})</span></Label>
                  <Input type="number" min="0" step="any" value={intermediate} onChange={(e) => setIntermediate(e.target.value)} />
                </div>
              )}
            </>
          )}
          {dlg.kind === 'output' && (
            <>
              <div className="grid grid-cols-2 gap-3">
                <div className="space-y-1">
                  <Label>{tx('Good quantity')} ({s.outputProductId ? productName(s.outputProductId) : tx('pass-through')})</Label>
                  <Input type="number" min="0" step="any" value={good} onChange={(e) => setGood(e.target.value)} placeholder={qty(Number(s.plannedOutputQty) - Number(s.qtyGood))} />
                </div>
                <div className="space-y-1">
                  <Label>{tx('Scrap quantity')}</Label>
                  <Input type="number" min="0" step="any" value={scrap} onChange={(e) => setScrap(e.target.value)} />
                </div>
              </div>
              {coOutputs.map((o) => (
                <div key={o.id} className="grid grid-cols-[1fr_8rem] items-center gap-2">
                  <Label className="text-sm">{o.productName} <Badge variant="outline">{(o.outputRole ?? '').replace('_', ' ')}</Badge></Label>
                  <Input type="number" min="0" step="any" value={others[o.id] ?? ''} placeholder={tx('auto')} onChange={(e) => setOthers((x) => ({ ...x, [o.id]: e.target.value }))} />
                </div>
              ))}
              <label className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={complete} onChange={(e) => setComplete(e.target.checked)} />
                {tx('This completes the stage (remaining WIP moves with this output)')}
              </label>
              {s.issueMode === 'backflush' && <p className="text-xs text-muted-foreground">{tx('Backflush: the planned share of the stage materials is issued automatically.')}</p>}
            </>
          )}
          {dlg.kind === 'conversion' && (
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1">
                <Label>{tx('Amount')}</Label>
                <Input type="number" min="0" step="any" value={amount} onChange={(e) => setAmount(e.target.value)} />
              </div>
              <div className="space-y-1">
                <Label>{tx('Kind')}</Label>
                <Select value={kind} onValueChange={setKind}>
                  <SelectTrigger><SelectValue /></SelectTrigger>
                  <SelectContent>
                    <SelectItem value="labour">{tx('Labour')}</SelectItem>
                    <SelectItem value="machine">{tx('Machine')}</SelectItem>
                    <SelectItem value="overhead">{tx('Overhead')}</SelectItem>
                    <SelectItem value="utility">{tx('Utility')}</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>
          )}
          {error && <p className="text-sm text-destructive">{error}</p>}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{tx('Cancel')}</Button>
          <Button onClick={submit} disabled={busy}>{tx('Post')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export default OrderStagesPanel;
