import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Calculator, CheckCircle2, ClipboardList, GitBranch, Pencil } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import {
  BASIS_LABEL, BLOCK_TYPE_LABEL, useActivateMixDesign, useMixDesign, useMixScale, useReviseMixDesign,
  type MixDesignDetail, type ScaleBasis,
} from '@/features/manufacturing/api-precast';
import { Field, Item, LoadError, MixStatusPill, OptionalSelect, ScalingSummary } from './precast-ui';
import { MixDesignFormDialog } from './MixDesignForm';
import { dims, num, pct, toOptions } from './precast-format';

/** Mix design detail (2.4): materials per batch, block spec, versions, activate / revise / edit, batch scaling calculator. */
export function MixDesignDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const { data: mix, isLoading, isError, refetch } = useMixDesign(id);
  const activate = useActivateMixDesign();
  const revise = useReviseMixDesign();
  const [editing, setEditing] = useState(false);

  if (isLoading) return <div className="space-y-3"><Skeleton className="h-12 w-1/2" /><Skeleton className="h-64 w-full" /></div>;
  if (isError || !mix) return <LoadError message={tx('Mix design not found')} onRetry={() => refetch()} />;
  const draft = mix.status === 'draft';

  const doActivate = async () => {
    try {
      await activate.mutateAsync(mix.id);
      notify.success(tx('Mix design activated'));
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not activate the mix design')));
    }
  };
  const doRevise = async () => {
    try {
      const next = await revise.mutateAsync({ id: mix.id });
      notify.success(tx('New draft version {{version}} created', { version: next.version }));
      navigate(`/manufacturing/precast/mix-designs/${next.id}`);
    } catch (err) {
      notify.error(apiMessage(err, tx('Could not revise the mix design')));
    }
  };
  const spec = mix.spec;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="space-y-1">
          <Link to="/manufacturing/precast/mix-designs" className="inline-flex items-center text-sm text-muted-foreground hover:underline">
            <ArrowLeft className="mr-1 h-4 w-4" /> {tx('Mix designs')}
          </Link>
          <h1 className="text-2xl font-bold tracking-tight">{mix.name}</h1>
          <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
            <span className="font-mono">{mix.bomCode}</span>
            <span>{tx('Version')} {mix.version}</span>
            <MixStatusPill status={mix.status} />
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          {draft && has(PERMISSIONS.bom.update) && <Button variant="outline" onClick={() => setEditing(true)}><Pencil className="mr-1 h-4 w-4" /> {tx('Edit')}</Button>}
          {draft && has(PERMISSIONS.bom.activate) && <Button onClick={doActivate} disabled={activate.isPending}><CheckCircle2 className="mr-1 h-4 w-4" /> {tx('Activate')}</Button>}
          {!draft && has(PERMISSIONS.bom.create) && <Button variant="outline" onClick={doRevise} disabled={revise.isPending}><GitBranch className="mr-1 h-4 w-4" /> {tx('Revise')}</Button>}
        </div>
      </div>

      <div className="grid gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader><CardTitle>{tx('Materials per batch')}</CardTitle></CardHeader>
          <CardContent className="space-y-4">
            <dl className="grid grid-cols-2 gap-3 sm:grid-cols-4">
              <Item label={tx('Block product')}>{mix.productName}</Item>
              <Item label={tx('Blocks per batch')}>{num(mix.unitsPerBatch, 0)}</Item>
              <Item label={tx('Expected yield %')}>{pct(mix.expectedYieldPct)}</Item>
              <Item label={tx('Cement per bag')}>{num(mix.cementPerBag, 2)}</Item>
              <Item label={tx('Batch volume (m³)')}>{num(mix.batchVolumeM3, 3)}</Item>
              <Item label={tx('Water/cement ratio')}>{num(mix.waterCementRatio, 3)}</Item>
              <Item label={tx('Minimum curing days')}>{num(mix.minCuringDays, 0)}</Item>
              <Item label={tx('Target maturity (°C·h)')}>{num(mix.targetMaturity, 0)}</Item>
              <Item label={tx('Default curing area')}>{mix.defaultCuringAreaName}</Item>
              <Item label={tx('Curing QC checkpoint')}>{mix.curingCheckpointCode}</Item>
            </dl>
            <div className="overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{tx('Material')}</TableHead>
                    <TableHead className="text-right">{tx('Qty per batch')}</TableHead>
                    <TableHead className="text-right">{tx('Scrap %')}</TableHead>
                    <TableHead>{tx('Notes')}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {mix.lines.map((l) => (
                    <TableRow key={l.id}>
                      <TableCell>
                        {l.productName}
                        {l.componentProductId === mix.cementProductId && <span className="ml-1 text-xs text-muted-foreground">({tx('cement')})</span>}
                        <div className="text-xs text-muted-foreground">{l.productSku ?? ''}</div>
                      </TableCell>
                      <TableCell className="text-right tabular-nums">{num(l.quantity, 3)}</TableCell>
                      <TableCell className="text-right tabular-nums">{pct(l.scrapPct)}</TableCell>
                      <TableCell className="text-sm text-muted-foreground">{l.notes ?? ''}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
            {mix.notes && <p className="text-sm text-muted-foreground">{mix.notes}</p>}
          </CardContent>
        </Card>

        <div className="space-y-4">
          <Card>
            <CardHeader><CardTitle>{tx('Block specification')}</CardTitle></CardHeader>
            <CardContent>
              {!spec ? (
                <p className="text-sm text-muted-foreground">
                  {tx('No block specification yet.')} <Link className="underline" to="/manufacturing/precast/setup">{tx('Set it up')}</Link>
                </p>
              ) : (
                <dl className="grid grid-cols-2 gap-3">
                  <Item label={tx('Block type')}>{BLOCK_TYPE_LABEL[spec.blockType] ?? spec.blockType}</Item>
                  <Item label={tx('Dimensions (mm)')}>{dims(spec)}</Item>
                  <Item label={tx('Strength class')}>{spec.strengthClass}</Item>
                  <Item label={tx('Strength (MPa)')}>{num(spec.strengthMpa, 1)}</Item>
                  <Item label={tx('Weight (kg)')}>{num(spec.weightKg, 2)}</Item>
                  <Item label={tx('Blocks per pallet')}>{num(spec.unitsPerPallet, 0)}</Item>
                  <Item label={tx('Blocks per press cycle')}>{num(spec.unitsPerCycle, 0)}</Item>
                  <Item label={tx('Second-grade product')}>{spec.secondGradeProductName}</Item>
                </dl>
              )}
            </CardContent>
          </Card>
          <Card>
            <CardHeader><CardTitle>{tx('Versions')}</CardTitle></CardHeader>
            <CardContent className="space-y-1">
              {mix.versions.map((v) => (
                <div key={v.id} className="flex items-center justify-between gap-2 text-sm">
                  {v.id === mix.id
                    ? <span className="font-medium">{tx('Version')} {v.version}</span>
                    : <Link className="text-primary underline" to={`/manufacturing/precast/mix-designs/${v.id}`}>{tx('Version')} {v.version}</Link>}
                  <span className="text-xs text-muted-foreground">{date(v.createdAt)}</span>
                  <MixStatusPill status={v.status} />
                </div>
              ))}
            </CardContent>
          </Card>
        </div>
      </div>

      <ScaleCalculator mix={mix} />
      {editing && <MixDesignFormDialog initial={mix} onClose={() => setEditing(false)} onSaved={() => refetch()} />}
    </div>
  );
}

function ScaleCalculator({ mix }: { mix: MixDesignDetail }) {
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const [basis, setBasis] = useState<ScaleBasis>('units');
  const [value, setValue] = useState('1000');
  const [wholeBatches, setWholeBatches] = useState(true);
  const [params, setParams] = useState<{ basis: ScaleBasis; value: number; wholeBatches: boolean } | null>(null);
  const scale = useMixScale(mix.id, params);
  const valid = value.trim() !== '' && Number(value) > 0;
  const run = () => valid && setParams({ basis, value: Number(value), wholeBatches });
  const createOrder = () => {
    const q = new URLSearchParams({ new: '1', mixDesignId: mix.id, basis, value, wholeBatches: String(wholeBatches) });
    navigate(`/manufacturing/precast/orders?${q.toString()}`);
  };
  return (
    <Card>
      <CardHeader><CardTitle className="flex items-center gap-2"><Calculator className="h-5 w-5" /> {tx('Batch scaling calculator')}</CardTitle></CardHeader>
      <CardContent className="space-y-4">
        <div className="flex flex-wrap items-end gap-3">
          <Field label={tx('Basis')} className="w-48">
            <OptionalSelect value={basis} onChange={(v) => setBasis((v || 'units') as ScaleBasis)} placeholder={tx('Basis')} options={toOptions(BASIS_LABEL)} />
          </Field>
          <Field label={tx('Value')} className="w-36" error={!valid ? tx('Enter a positive number') : undefined}>
            <Input inputMode="decimal" value={value} onChange={(e) => setValue(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && run()} />
          </Field>
          <label className="flex h-10 items-center gap-2 text-sm">
            <input type="checkbox" checked={wholeBatches} onChange={(e) => setWholeBatches(e.target.checked)} /> {tx('Whole batches')}
          </label>
          <Button onClick={run} disabled={!valid || scale.isFetching}>{tx('Calculate')}</Button>
          {mix.status === 'active' && has(PERMISSIONS.productionOrder.create) && (
            <Button variant="outline" onClick={createOrder} disabled={!valid}><ClipboardList className="mr-1 h-4 w-4" /> {tx('Create order from this')}</Button>
          )}
        </div>
        {scale.isError && <p className="text-sm text-destructive">{apiMessage(scale.error, tx('Could not scale the mix design'))}</p>}
        {scale.data && params && <ScalingSummary scaling={scale.data} />}
        {!params && mix.perBatch && (
          <div className="space-y-2">
            <p className="text-xs text-muted-foreground">{tx('One batch')}</p>
            <ScalingSummary scaling={mix.perBatch} />
          </div>
        )}
      </CardContent>
    </Card>
  );
}

export default MixDesignDetailPage;
