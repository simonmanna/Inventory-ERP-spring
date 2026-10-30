import { Link, useParams } from 'react-router-dom';
import { ArrowLeft, Printer } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useBatchLabels } from '@/features/manufacturing/api-precast';
import { LoadError } from './precast-ui';
import { dims, num } from './precast-format';

/** Pallet labels of a curing batch (2.4): one QR label per pallet, printable (the app shell hides itself in print). */
export function PalletLabelsPage() {
  const { id = '' } = useParams<{ id: string }>();
  const { data, isLoading, isError, refetch } = useBatchLabels(id);

  if (isLoading) return <div className="grid grid-cols-2 gap-3">{Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-48" />)}</div>;
  if (isError || !data) return <LoadError message={tx('Could not load the pallet labels.')} onRetry={() => refetch()} />;
  const { batch, spec, labels } = data;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2 print:hidden">
        <div className="space-y-1">
          <Link to={`/manufacturing/precast/curing/${batch.id}`} className="inline-flex items-center text-sm text-muted-foreground hover:underline">
            <ArrowLeft className="mr-1 h-4 w-4" /> {batch.code}
          </Link>
          <h1 className="text-2xl font-bold tracking-tight">{tx('Pallet labels')}</h1>
          <p className="text-sm text-muted-foreground">{tx('{{count}} labels', { count: labels.length })}</p>
        </div>
        <Button onClick={() => window.print()} disabled={!labels.length}><Printer className="mr-1 h-4 w-4" /> {tx('Print')}</Button>
      </div>
      {!labels.length ? <p className="text-sm text-muted-foreground">{tx('No pallets recorded on this batch.')}</p> : (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 print:grid-cols-2 print:gap-2">
          {labels.map((l) => (
            <div key={l.palletCode} className="flex break-inside-avoid gap-3 rounded-lg border-2 border-foreground/80 bg-white p-3 text-black">
              <img className="h-32 w-32 shrink-0" alt={l.palletCode} src={`data:image/svg+xml;utf8,${encodeURIComponent(l.qrSvg)}`} />
              <div className="min-w-0 flex-1 space-y-0.5 text-sm">
                <p className="text-base font-bold leading-tight">{batch.productName}</p>
                <p className="font-mono text-xs">{batch.productSku ?? ''}</p>
                <p className="font-mono font-semibold">{l.palletCode}</p>
                <p>{tx('Pallet {{n}} of {{total}}', { n: l.palletNo, total: l.pallets })} · <b>{num(l.quantity, 0)}</b> {tx('blocks')}</p>
                {batch.batchNumber && <p>{tx('Batch')}: {batch.batchNumber}</p>}
                {spec && <p>{[spec.strengthClass, dims(spec) !== '—' ? `${dims(spec)} mm` : null].filter(Boolean).join(' · ')}</p>}
                <p className="text-xs">{tx('Pressed')}: {date(batch.startedAt)} · {tx('Curing until')}: {batch.curingUntil ? date(batch.curingUntil) : '—'}</p>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export default PalletLabelsPage;
