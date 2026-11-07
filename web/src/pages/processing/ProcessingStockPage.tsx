import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRightCircle, GitBranch, PackageSearch } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Skeleton } from '@/components/ui/skeleton';
import { FilterSelect, ListCard, ListPageHeader, ListToolbar, SearchInput, StatusPill } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { STAGE_LABEL, useGenealogy, useLots, type Lot } from '@/features/processing/api';
import { EmptyState, GenealogyTree, qty, toOptions } from './processing-ui';

function TraceDialog({ lot, onClose }: { lot: Lot; onClose: () => void }) {
  const [direction, setDirection] = useState<'backward' | 'forward'>('backward');
  const trace = useGenealogy(lot.productId, lot.batchNumber, direction);
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[85vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader><DialogTitle>{tx('Trace lot {{lot}}', { lot: lot.batchNumber })}</DialogTitle></DialogHeader>
        <div className="inline-flex rounded-lg border p-0.5 text-sm" role="tablist">
          {(['backward', 'forward'] as const).map((d) => (
            <button key={d} type="button" role="tab" aria-selected={direction === d} className={`rounded-md px-3 py-1 ${direction === d ? 'bg-primary text-primary-foreground' : 'hover:bg-accent'}`} onClick={() => setDirection(d)}>
              {d === 'backward' ? tx('Where it came from') : tx('Where it went')}
            </button>
          ))}
        </div>
        {trace.isLoading ? <Skeleton className="h-40 w-full" /> : trace.data ? <GenealogyTree node={trace.data.root} /> : <EmptyState>{tx('Could not load the trace.')}</EmptyState>}
      </DialogContent>
    </Dialog>
  );
}

/** Stock at every stage, by lot: sell it as it is, or process it further. */
export function ProcessingStockPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const [searchInput, setSearchInput] = useState('');
  const [stage, setStage] = useState('');
  const { data, isLoading, isError } = useLots({ search: searchInput.trim() });
  const [tracing, setTracing] = useState<Lot | null>(null);
  const rows = useMemo(() => (data ?? []).filter((l) => !stage || l.stage === stage), [data, stage]);
  const groups = useMemo(() => {
    const m = new Map<string, Lot[]>();
    rows.forEach((l) => m.set(l.stage ?? 'other', [...(m.get(l.stage ?? 'other') ?? []), l]));
    const order = ['raw', 'semi_finished', 'finished', 'other'];
    return [...m.entries()].sort((a, b) => order.indexOf(a[0]) - order.indexOf(b[0]));
  }, [rows]);
  return (
    <div className="space-y-4">
      <ListPageHeader icon={PackageSearch} title={tx('Stock by Stage')} description={tx('Everything on hand, by lot. Any lot can be sold as it is, or processed further.')} />
      <ListToolbar>
        <SearchInput value={searchInput} onChange={setSearchInput} placeholder={tx('Search product or lot…')} />
        <FilterSelect value={stage} onChange={setStage} options={toOptions(STAGE_LABEL)} allLabel={tx('All stages')} />
      </ListToolbar>
      {isLoading ? <Skeleton className="h-64 w-full" /> : isError ? <EmptyState>{tx('Could not load the stock. Try again.')}</EmptyState> : groups.length === 0 ? <EmptyState>{tx('No lot-tracked stock found.')}</EmptyState> : groups.map(([key, lots]) => (
        <ListCard key={key}>
          <div className="flex items-baseline justify-between gap-3 border-b px-4 py-2.5">
            <h2 className="font-semibold">{STAGE_LABEL[key] ?? tx('Other')}</h2>
            <span className="text-sm text-muted-foreground">{money(lots.reduce((s, l) => s + Number(l.value), 0))}</span>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-left text-xs text-muted-foreground"><tr><th className="px-4 py-2 font-medium">{tx('Product')}</th><th className="px-2 font-medium">{tx('Lot')}</th><th className="px-2 font-medium">{tx('Location')}</th><th className="px-2 text-right font-medium">{tx('On hand')}</th><th className="px-2 text-right font-medium">{tx('Unit cost')}</th><th className="px-2 font-medium">{tx('Sale')}</th><th className="px-4" /></tr></thead>
              <tbody className="divide-y">
                {lots.map((l) => (
                  <tr key={l.id} className="hover:bg-muted/40">
                    <td className="px-4 py-2"><div className="font-medium">{l.productName}</div><div className="text-xs text-muted-foreground">{l.productCode}{l.categoryName ? ` · ${l.categoryName}` : ''}</div></td>
                    <td className="px-2 font-mono text-xs">{l.batchNumber}</td>
                    <td className="px-2">{l.locationName}</td>
                    <td className="whitespace-nowrap px-2 text-right">{qty(l.quantity)} {l.uomName ?? ''}{Number(l.reserved) > 0 && <div className="text-xs text-amber-700 dark:text-amber-400">{tx('{{qty}} reserved', { qty: qty(l.reserved) })}</div>}</td>
                    <td className="px-2 text-right">{money(l.unitCost)}</td>
                    <td className="px-2">{l.canBeSold ? <StatusPill tone="success">{tx('Can be sold')}</StatusPill> : <StatusPill tone="neutral">{tx('Not for sale')}</StatusPill>}</td>
                    <td className="whitespace-nowrap px-4 text-right">
                      <Button variant="ghost" size="sm" className="h-8" onClick={() => setTracing(l)}><GitBranch className="mr-1 h-3.5 w-3.5" /> {tx('Trace')}</Button>
                      {has(PERMISSIONS.processing.post) && <Button asChild variant="ghost" size="sm" className="h-8 text-primary"><Link to={`/manufacturing/processing/jobs/new?productId=${l.productId}&lot=${encodeURIComponent(l.batchNumber)}&locationId=${l.locationId}`}><ArrowRightCircle className="mr-1 h-3.5 w-3.5" /> {tx('Process further')}</Link></Button>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </ListCard>
      ))}
      {tracing && <TraceDialog lot={tracing} onClose={() => setTracing(null)} />}
    </div>
  );
}
