import { useNavigate } from 'react-router-dom';
import { Mountain } from 'lucide-react';
import { ListPageHeader, StatusPill } from '@/components/list';
import { Skeleton } from '@/components/ui/skeleton';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useStockpiles } from '@/features/quarry/api';
import { EmptyState, tonnes } from './quarry-ui';

/** ROM and product stockpiles with on-hand tonnes and value (inventory locations). */
export function StockpilesPage() {
  const navigate = useNavigate();
  const { data, isLoading, isError } = useStockpiles();
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Mountain} title={tx('ROM Stockpiles')} description={tx('Run-of-mine stone in stock at its extraction cost, ready for crushing.')} />
      {isLoading && <Skeleton className="h-32" />}
      {isError && <p className="text-sm text-destructive">{tx('Could not load the stockpiles. Try again.')}</p>}
      {!isLoading && (data ?? []).length === 0 && <EmptyState>{tx('No stockpiles yet: add one on a quarry site.')}</EmptyState>}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {(data ?? []).map((k) => (
          <button key={k.id} type="button" onClick={() => navigate(`/quarry/stockpiles/${k.id}`)}
            className="space-y-2 rounded-xl border bg-card p-4 text-left shadow-sm hover:border-primary/50">
            <div className="flex items-start justify-between gap-2">
              <div>
                <div className="font-semibold">{k.code} · {k.name}</div>
                <div className="text-xs text-muted-foreground">{k.siteCode} · {k.locationCode}</div>
              </div>
              <StatusPill tone={k.kind === 'rom' ? 'info' : 'accent'} dot={false}>{k.kind === 'rom' ? tx('ROM') : tx('Product')}</StatusPill>
            </div>
            <div className="text-2xl font-semibold tabular-nums">{tonnes(k.onHandTonnes)}</div>
            <div className="text-xs text-muted-foreground">{tx('Value')} {money(k.onHandValue)}{k.capacityT ? ` · ${tx('Capacity')} ${tonnes(k.capacityT)}` : ''}</div>
          </button>
        ))}
      </div>
    </div>
  );
}
