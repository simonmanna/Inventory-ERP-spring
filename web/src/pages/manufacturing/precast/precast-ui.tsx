import type { ReactNode } from 'react';
import { RefreshCw } from 'lucide-react';
import { StatusPill, type Tone } from '@/components/list';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { cn } from '@/lib/utils';
import { tx } from '@/lib/i18n/tx';
import { useScrapReasons } from '@/features/manufacturing/api-advanced';
import {
  BATCH_STATUS_LABEL, MIX_STATUS_LABEL, ORDER_STATUS_LABEL, QC_STATUS_LABEL, useCuringAreas,
  type CuringBatchStatus, type Scaling,
} from '@/features/manufacturing/api-precast';
import type { BomStatus, ProductionOrderStatus } from '@/features/manufacturing/types';
import type { QcStatus } from '@/features/manufacturing/api-stages';
import { Field, OptionalSelect } from '../../quality/quality-ui';
import { num } from './precast-format';

/** Shared bits of the precast screens (2.4): status pills, pickers, tiles, scaling summary. */

export { Field, Item, LocationPicker, OptionalSelect, ProductPicker } from '../../quality/quality-ui';

const MIX_TONE: Record<BomStatus, Tone> = { draft: 'warning', active: 'success', archived: 'neutral' };
const BATCH_TONE: Record<CuringBatchStatus, Tone> = { curing: 'info', cubed: 'accent', released: 'success', failed: 'danger', cancelled: 'neutral' };
const ORDER_TONE: Record<ProductionOrderStatus, Tone> = {
  draft: 'neutral', confirmed: 'info', in_progress: 'warning', qc_hold: 'accent', completed: 'success', cancelled: 'neutral',
};
const QC_TONE: Record<QcStatus, Tone> = { not_required: 'neutral', pending: 'warning', passed: 'success', failed: 'danger', curing: 'info' };

export const MixStatusPill = ({ status }: { status: BomStatus }) =>
  <StatusPill tone={MIX_TONE[status] ?? 'neutral'}>{MIX_STATUS_LABEL[status] ?? status}</StatusPill>;
export const BatchStatusPill = ({ status }: { status: CuringBatchStatus }) =>
  <StatusPill tone={BATCH_TONE[status] ?? 'neutral'}>{BATCH_STATUS_LABEL[status] ?? status}</StatusPill>;
export const OrderStatusPill = ({ status }: { status: ProductionOrderStatus }) =>
  <StatusPill tone={ORDER_TONE[status] ?? 'neutral'}>{ORDER_STATUS_LABEL[status] ?? status}</StatusPill>;
export const QcPill = ({ status }: { status: QcStatus | null | undefined }) =>
  !status || status === 'not_required' ? null : (
    <StatusPill tone={QC_TONE[status] ?? 'neutral'} dot={false}>{tx('QC')}: {QC_STATUS_LABEL[status] ?? status}</StatusPill>
  );

export function CuringAreaSelect({ value, onChange, noneLabel }: { value: string; onChange: (v: string) => void; noneLabel?: string }) {
  const areas = useCuringAreas(true);
  return (
    <OptionalSelect value={value} onChange={onChange} placeholder={tx('Choose a curing area')} noneLabel={noneLabel ?? tx('None')}
      options={(areas.data ?? []).map((a) => ({ value: a.id, label: `${a.code} · ${a.name}` }))} />
  );
}

/** Scrap reason: a pick-list when scrap reason codes exist (they are then enforced), free text otherwise. */
export function ScrapReasonField({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const { list } = useScrapReasons();
  const reasons = ((list.data ?? []) as Array<{ code: string; name: string; isActive?: boolean }>).filter((r) => r.isActive !== false);
  return (
    <Field label={tx('Scrap reason')}>
      {reasons.length ? (
        <OptionalSelect value={value} onChange={onChange} placeholder={tx('Choose a reason')} noneLabel={tx('None')}
          options={reasons.map((r) => ({ value: r.code, label: `${r.code} · ${r.name}` }))} />
      ) : (
        <Input value={value} onChange={(e) => onChange(e.target.value)} placeholder={tx('Reason code')} />
      )}
    </Field>
  );
}

export function Tile({ label, value, hint, strong }: { label: string; value: ReactNode; hint?: ReactNode; strong?: boolean }) {
  return (
    <Card className={cn(strong && 'border-primary/50 bg-primary/5')}>
      <CardContent className="p-4">
        <p className="text-xs text-muted-foreground">{label}</p>
        <p className={cn('font-semibold tabular-nums', strong ? 'text-2xl text-primary' : 'text-lg')}>{value}</p>
        {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
      </CardContent>
    </Card>
  );
}

export function LoadError({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="p-8 text-center text-muted-foreground">
      <p>{message}</p>
      <Button variant="link" onClick={onRetry}><RefreshCw className="mr-1 h-4 w-4" /> {tx('Retry')}</Button>
    </div>
  );
}

/** Batch scaling result: totals plus the material draw (cement in bags). */
export function ScalingSummary({ scaling }: { scaling: Scaling }) {
  return (
    <div className="space-y-3">
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <Tile label={tx('Batches')} value={num(scaling.batches, 2)} />
        <Tile label={tx('Blocks')} value={num(scaling.units, 0)} hint={`${tx('Expected good')}: ${num(scaling.expectedGood, 0)}`} />
        <Tile label={tx('Pallets')} value={num(scaling.pallets, 1)} hint={`${tx('Press cycles')}: ${num(scaling.cycles, 1)}`} />
        <Tile label={tx('Cement bags')} value={num(scaling.cementBagsToDraw ?? scaling.cementBags, 0)}
          hint={`${num(scaling.cementBags, 2)} · ${tx('Volume (m³)')}: ${num(scaling.volumeM3, 2)}`} />
      </div>
      <div className="overflow-x-auto">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{tx('Material')}</TableHead>
              <TableHead className="text-right">{tx('Per batch')}</TableHead>
              <TableHead className="text-right">{tx('Quantity')}</TableHead>
              <TableHead className="text-right">{tx('Bags')}</TableHead>
              <TableHead className="text-right">{tx('On hand')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {scaling.materials.map((m) => {
              const short = m.onHand != null && Number(m.onHand) < Number(m.quantity);
              return (
                <TableRow key={m.productId}>
                  <TableCell>{m.productName ?? m.productId}{m.cement && <span className="ml-1 text-xs text-muted-foreground">({tx('cement')})</span>}</TableCell>
                  <TableCell className="text-right tabular-nums">{num(m.qtyPerBatch, 3)}</TableCell>
                  <TableCell className="text-right tabular-nums">{num(m.quantity, 3)}</TableCell>
                  <TableCell className="text-right tabular-nums">{m.cement ? num(m.bags, 2) : '—'}</TableCell>
                  <TableCell className={cn('text-right tabular-nums', short && 'text-destructive')}>{num(m.onHand, 3)}</TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}
