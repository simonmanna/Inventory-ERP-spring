import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { ChevronDown, ChevronRight, GitFork } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card, CardContent } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { ListPageHeader } from '@/components/list';
import { apiMessage } from '@/lib/notify';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useTraceability, type TraceNode } from '@/features/quality/api';
import { Field, OptionalSelect, ProductPicker, ResultPill } from './quality-ui';
import { num } from './quality-format';

const KIND: Record<TraceNode['kind'], string> = {
  batch: tx('Batch'), goods_receipt: tx('Goods receipt'), supplier: tx('Supplier'), production_order: tx('Production order'),
  material: tx('Material (untracked)'), downgrade: tx('Downgrade'), receipt: tx('Receipt'), delivery: tx('Delivery'),
  customer: tx('Customer'), site: tx('Site'), issue: tx('Issue'),
};

const KIND_TONE: Record<TraceNode['kind'], string> = {
  batch: 'bg-primary/10 text-primary', goods_receipt: 'bg-sky-100 text-sky-800', supplier: 'bg-sky-50 text-sky-700',
  production_order: 'bg-violet-100 text-violet-800', material: 'bg-muted text-muted-foreground', downgrade: 'bg-amber-100 text-amber-800',
  receipt: 'bg-muted text-foreground', delivery: 'bg-emerald-100 text-emerald-800', customer: 'bg-emerald-50 text-emerald-700',
  site: 'bg-emerald-50 text-emerald-700', issue: 'bg-muted text-foreground',
};

/** Batch traceability (ADR-S013): backward to suppliers and inputs, forward to orders, deliveries and customers. */
export function TraceabilityPage() {
  const [params, setParams] = useSearchParams();
  const [productId, setProductId] = useState(params.get('productId') ?? '');
  const [batch, setBatch] = useState(params.get('batch') ?? '');
  const [direction, setDirection] = useState<'backward' | 'forward'>(params.get('direction') === 'forward' ? 'forward' : 'backward');
  const query = params.get('productId') && params.get('batch')
    ? { productId: params.get('productId') as string, batchNumber: params.get('batch') as string, direction: (params.get('direction') === 'forward' ? 'forward' : 'backward') as 'backward' | 'forward' }
    : null;
  const { data, isLoading, isError, error } = useTraceability(query);
  const run = () => setParams({ productId, batch: batch.trim(), direction });
  return (
    <div className="space-y-4">
      <ListPageHeader icon={GitFork} title={tx('Batch Traceability')}
        description={tx('Backward: where a batch came from (suppliers, raw material lots, production). Forward: where it went (production, stockpiles, deliveries, customers).')} />
      <Card>
        <CardContent className="grid grid-cols-1 items-end gap-3 pt-6 sm:grid-cols-[2fr_1fr_1fr_auto]">
          <Field label={tx('Product')}><ProductPicker value={productId} onChange={setProductId} /></Field>
          <Field label={tx('Batch number')}><Input value={batch} onChange={(e) => setBatch(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && run()} /></Field>
          <Field label={tx('Direction')}>
            <OptionalSelect value={direction} onChange={(v) => setDirection(v === 'forward' ? 'forward' : 'backward')} placeholder={tx('Direction')}
              options={[{ value: 'backward', label: tx('Backward (origin)') }, { value: 'forward', label: tx('Forward (destination)') }]} />
          </Field>
          <Button onClick={run} disabled={!productId || !batch.trim()}>{tx('Trace')}</Button>
        </CardContent>
      </Card>
      {query && isLoading && <Skeleton className="h-48 w-full" />}
      {query && isError && <p className="text-sm text-destructive">{apiMessage(error, tx('Could not trace the batch.'))}</p>}
      {data && (
        <Card>
          <CardContent className="overflow-x-auto pt-6">
            <TreeNode node={data.root} depth={0} />
          </CardContent>
        </Card>
      )}
      {!query && <p className="text-sm text-muted-foreground">{tx('Choose a product and a batch number to trace.')}</p>}
    </div>
  );
}

function TreeNode({ node, depth }: { node: TraceNode; depth: number }) {
  const [open, setOpen] = useState(depth < 3);
  const kids = node.children ?? [];
  const link = node.kind === 'production_order' && node.ref ? `/manufacturing/orders/${node.ref}`
    : node.kind === 'delivery' && node.ref ? `/fleet/trips/${node.ref}` : null;
  return (
    <div className={depth > 0 ? 'ml-4 border-l pl-3' : ''}>
      <div className="flex flex-wrap items-center gap-2 py-1 text-sm">
        {kids.length > 0
          ? <button type="button" aria-label={open ? tx('Collapse') : tx('Expand')} onClick={() => setOpen((o) => !o)} className="text-muted-foreground">{open ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}</button>
          : <span className="inline-block w-4" />}
        <span className={`rounded px-1.5 py-0.5 text-xs font-medium ${KIND_TONE[node.kind] ?? 'bg-muted'}`}>{KIND[node.kind] ?? node.kind}</span>
        {link ? <Link className="font-medium text-primary hover:underline" to={link}>{node.label}</Link> : <span className="font-medium">{node.label ?? '—'}</span>}
        {node.kind === 'batch' && node.productName && <span className="text-muted-foreground">{node.productName}</span>}
        {node.kind === 'material' && node.productName && node.productName !== node.label && <span className="text-muted-foreground">{node.productName}</span>}
        {node.quantity != null && <span className="tabular-nums text-muted-foreground">{num(node.quantity, 3)}</span>}
        {node.date && <span className="text-xs text-muted-foreground">{date(node.date)}</span>}
        {node.deliveryOrder && <span className="text-xs text-muted-foreground">{node.deliveryOrder}</span>}
        {(node.lots ?? []).map((l) => (
          <Link key={l.id} to={`/quality/lots/${l.id}`} className="inline-flex items-center gap-1 text-xs text-primary hover:underline">{l.lotNumber} <ResultPill result={l.result} /></Link>
        ))}
        {node.cycle && <span className="text-xs text-amber-700">{tx('(already shown)')}</span>}
        {node.truncated && <span className="text-xs text-muted-foreground">{tx('(depth limit)')}</span>}
      </div>
      {open && kids.map((c, i) => <TreeNode key={`${c.key}-${i}`} node={c} depth={depth + 1} />)}
    </div>
  );
}
