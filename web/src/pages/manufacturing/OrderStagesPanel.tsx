import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { CheckCircle2, Coins, GitFork, Layers, PackageMinus, PackagePlus, ShieldAlert, ShieldCheck, Undo2, XCircle } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
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

const STAGE_VARIANT: Record<string, 'default' | 'secondary' | 'outline' | 'destructive'> = {
  pending: 'secondary',
  in_progress: 'default',
  completed: 'outline',
  cancelled: 'destructive',
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
  const [dlg, setDlg] = useState<Dlg>(null);

  const productName = useMemo(() => {
    const m = new Map((products ?? []).map((p) => [p.id, p.name]));
    return (id?: string | null) => (id ? m.get(id) ?? id.slice(0, 8) : '—');
  }, [products]);

  if (isLoading) return <Card><CardContent className="p-4 text-sm text-muted-foreground">{tx('Loading stages…')}</CardContent></Card>;
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
      <Card>
        <CardHeader className="flex-row flex-wrap items-center justify-between gap-2">
          <CardTitle className="flex items-center gap-2"><Layers className="h-4 w-4" /> {tx('Stages')}</CardTitle>
          <div className="flex flex-wrap gap-2">
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
          </div>
        </CardHeader>
        <CardContent>
          {!staged.multiStage ? (
            <p className="text-sm text-muted-foreground">
              {tx('This order runs as one step. Configure stages on the routing (Recipes → stage set-up) and use "Run in stages" before it starts.')}
            </p>
          ) : (
            <>
              <p className="mb-3 text-sm text-muted-foreground">{tx('Work in progress')}: <span className="font-medium text-foreground">{money(staged.wipValue)}</span></p>
              <ol className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
                {staged.stages.map((s) => (
                  <li key={s.id} className="rounded-lg border p-3">
                    <div className="flex items-start justify-between gap-2">
                      <div>
                        <p className="text-xs text-muted-foreground">{tx('Stage')} {s.sequence}{s.isLast ? ` · ${tx('final')}` : ''}</p>
                        <p className="font-medium">{s.name}</p>
                        <p className="text-xs text-muted-foreground">
                          {s.outputProductId ? productName(s.outputProductId) : tx('pass-through')} · {s.issueMode === 'manual' ? tx('manual issue') : tx('backflush')}
                        </p>
                      </div>
                      <div className="flex flex-col items-end gap-1">
                        <Badge variant={STAGE_VARIANT[s.status]}>{s.status.replace('_', ' ')}</Badge>
                        {s.qcGate && (
                          <Badge variant={s.qcStatus === 'passed' ? 'outline' : s.qcStatus === 'failed' ? 'destructive' : 'secondary'}>
                            {tx('QC')} {s.qcStatus.replace('_', ' ')}
                          </Badge>
                        )}
                      </div>
                    </div>
                    <dl className="mt-2 grid grid-cols-3 gap-1 text-xs">
                      <div><dt className="text-muted-foreground">{tx('Planned')}</dt><dd>{qty(s.plannedOutputQty)}</dd></div>
                      <div><dt className="text-muted-foreground">{tx('Good')}</dt><dd>{qty(s.qtyGood)}</dd></div>
                      <div><dt className="text-muted-foreground">{tx('Scrap')}</dt><dd>{qty(s.qtyScrap)}</dd></div>
                      <div><dt className="text-muted-foreground">{tx('Input cost')}</dt><dd>{money(Number(s.materialCost) + Number(s.transferredInCost) + Number(s.conversionCost))}</dd></div>
                      <div><dt className="text-muted-foreground">{tx('Output cost')}</dt><dd>{money(s.outputCost)}</dd></div>
                      <div><dt className="text-muted-foreground">{tx('WIP')}</dt><dd className="font-medium">{money(s.wipValue)}</dd></div>
                    </dl>
                    {Number(s.qtyQuarantined) > 0 && (
                      <p className="mt-2 text-xs text-amber-600">{tx('In QC quarantine')}: {qty(s.qtyQuarantined)}</p>
                    )}
                    {!s.isLast && s.outputProductId && Number(s.intermediateAvailable) > 0 && (
                      <p className="mt-1 text-xs text-muted-foreground">{tx('Available to next stage')}: {qty(s.intermediateAvailable)}</p>
                    )}
                    {s.blockedBy && (
                      <p className="mt-2 flex items-start gap-1 text-xs text-destructive"><ShieldAlert className="mt-0.5 h-3 w-3 shrink-0" /> {s.blockedBy}</p>
                    )}
                    {open && s.status !== 'completed' && !s.blockedBy && (
                      <div className="mt-3 flex flex-wrap gap-1">
                        {canIssue && <Button size="sm" variant="outline" onClick={() => setDlg({ kind: 'issue', stage: s })}><PackageMinus className="mr-1 h-3 w-3" /> {tx('Issue')}</Button>}
                        {canOutput && <Button size="sm" onClick={() => setDlg({ kind: 'output', stage: s })}><PackagePlus className="mr-1 h-3 w-3" /> {tx('Output')}</Button>}
                        {canOutput && <Button size="sm" variant="ghost" onClick={() => setDlg({ kind: 'conversion', stage: s })}><Coins className="mr-1 h-3 w-3" /> {tx('Cost')}</Button>}
                      </div>
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
                  </li>
                ))}
              </ol>
            </>
          )}
        </CardContent>
      </Card>

      {staged.multiStage && (
        <Card>
          <CardHeader><CardTitle>{tx('Stage postings')}</CardTitle></CardHeader>
          <CardContent className="overflow-x-auto">
            {!staged.stages.some((s) => s.postings.length) ? (
              <p className="text-sm text-muted-foreground">{tx('Nothing posted yet.')}</p>
            ) : (
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{tx('Code')}</TableHead>
                    <TableHead>{tx('Kind')}</TableHead>
                    <TableHead className="text-right">{tx('Good')}</TableHead>
                    <TableHead className="text-right">{tx('Scrap')}</TableHead>
                    <TableHead className="text-right">{tx('Value')}</TableHead>
                    <TableHead>{tx('Status')}</TableHead>
                    <TableHead />
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {staged.stages.flatMap((s) => s.postings).map((p) => {
                    const value = p.lines.filter((l) => ['material', 'intermediate_in', 'conversion', 'transfer_in'].includes(l.role))
                      .reduce((a, l) => a + Number(l.value), 0);
                    return (
                      <TableRow key={p.id}>
                        <TableCell className="font-mono text-xs">{p.code}</TableCell>
                        <TableCell><Badge variant="outline">{p.kind.replace('_', ' ')}{p.completesStage ? ` · ${tx('completes')}` : ''}</Badge></TableCell>
                        <TableCell className="text-right">{qty(p.qtyGood)}</TableCell>
                        <TableCell className="text-right">{qty(p.qtyScrap)}</TableCell>
                        <TableCell className="text-right">{money(value)}</TableCell>
                        <TableCell>{p.reversedAt ? <Badge variant="destructive">{tx('reversed')}</Badge> : <CheckCircle2 className="h-4 w-4 text-green-600" />}</TableCell>
                        <TableCell className="text-right">
                          {canReverse && latest?.id === p.id && (
                            <Button size="sm" variant="ghost" disabled={reversePosting.isPending}
                              onClick={() => run(reversePosting.mutateAsync({ postingId: p.id, reason: 'reversed from UI' }), tx('Posting reversed.'))}>
                              <Undo2 className="mr-1 h-3 w-3" /> {tx('Reverse')}
                            </Button>
                          )}
                        </TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
            )}
          </CardContent>
        </Card>
      )}

      {tree && tree.children.length > 0 && (
        <Card>
          <CardHeader><CardTitle className="flex items-center gap-2"><GitFork className="h-4 w-4" /> {tx('Child orders')}</CardTitle></CardHeader>
          <CardContent>
            <p className="mb-2 text-sm text-muted-foreground">
              {tx('Rolled-up status')}: <Badge variant="outline">{tree.rolledStatus.replace('_', ' ')}</Badge> · {Number(tree.progressPct).toFixed(0)}%
            </p>
            <TreeList nodes={tree.children} />
          </CardContent>
        </Card>
      )}

      {dlg && <StageDialog dlg={dlg} order={staged} onClose={() => setDlg(null)} productName={productName} />}
    </div>
  );
}

function TreeList({ nodes }: { nodes: OrderTreeNode[] }) {
  return (
    <ul className="space-y-1 border-l pl-3">
      {nodes.map((n) => (
        <li key={n.id}>
          <div className="flex flex-wrap items-center gap-2 text-sm">
            <Link className="font-mono text-xs underline" to={`/manufacturing/orders/${n.id}`}>{n.orderCode}</Link>
            <span>{n.productName}</span>
            <span className="text-muted-foreground">× {qty(n.peg?.quantity ?? n.plannedQty)}</span>
            <Badge variant="outline">{n.rolledStatus.replace('_', ' ')}</Badge>
            <span className="text-xs text-muted-foreground">{Number(n.progressPct).toFixed(0)}%</span>
          </div>
          {n.children.length > 0 && <TreeList nodes={n.children} />}
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
