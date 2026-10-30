import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ArrowLeft, Mountain } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { DataTable, type Column } from '@/components/data-table';
import { DataTablePagination, ListCard, ListPageHeader } from '@/components/list';
import { dateTime, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useStockpile, type StockpileView } from '@/features/quarry/api';
import { EmptyState, Section, StatTile, qty, tonnes } from './quarry-ui';

type Movement = StockpileView['movements']['data'][number];

/** ROM stockpile view: stock per product at average cost, and every inventory movement of its location. */
export function StockpileDetailPage() {
  const { id = '' } = useParams();
  const [page, setPage] = useState(1);
  const { data: k, isLoading, isError } = useStockpile(id, page);
  if (isLoading) return <Skeleton className="h-64" />;
  if (isError || !k) return <EmptyState>{tx('Could not load the stockpile.')}</EmptyState>;
  const columns: Column<Movement>[] = [
    { key: 'at', header: tx('When'), render: (m) => dateTime(m.createdAt) },
    { key: 'code', header: tx('Movement'), render: (m) => m.ledgerCode },
    { key: 'type', header: tx('Type'), render: (m) => m.type },
    { key: 'product', header: tx('Product'), render: (m) => m.productName },
    { key: 'qty', header: tx('Tonnes'), className: 'text-right', render: (m) => <span className={Number(m.quantityChange) < 0 ? 'text-rose-700' : ''}>{qty(m.quantityChange, 3)}</span> },
    { key: 'cost', header: tx('Unit cost'), className: 'text-right', render: (m) => money(m.unitCost) },
    { key: 'source', header: tx('Source'), render: (m) => m.extractionLogCode ?? m.notes ?? '—' },
  ];
  return (
    <div className="space-y-4">
      <Button variant="ghost" size="sm" asChild><Link to="/quarry/stockpiles"><ArrowLeft className="mr-1 h-4 w-4" /> {tx('Stockpiles')}</Link></Button>
      <ListPageHeader icon={Mountain} title={`${k.code} · ${k.name}`} description={`${k.siteCode ?? ''} · ${k.locationCode ?? ''}`} />
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
        <StatTile label={tx('On hand')} value={tonnes(k.onHandTonnes)} />
        <StatTile label={tx('Value')} value={money(k.onHandValue)} />
        <StatTile label={tx('Capacity')} value={tonnes(k.capacityT)} />
      </div>
      <Section title={tx('Stock by product')}>
        {k.stock.length === 0 ? <p className="text-sm text-muted-foreground">{tx('Empty.')}</p> : (
          <ul className="divide-y text-sm">
            {k.stock.map((s) => (
              <li key={s.productId} className="flex justify-between gap-2 py-2">
                <span>{s.productName} <span className="text-muted-foreground">{s.productCode}</span></span>
                <span className="tabular-nums">{tonnes(s.onHandTonnes)} · {money(s.averageCost)}/t · {money(s.value)}</span>
              </li>
            ))}
          </ul>
        )}
      </Section>
      <ListCard>
        <DataTable columns={columns} data={k.movements.data} getRowId={(m) => m.id} cellClassName="py-2 px-4" emptyMessage={tx('No movements yet.')} />
      </ListCard>
      <DataTablePagination page={k.movements.meta.page} pageSize={20} total={k.movements.meta.total} totalPages={k.movements.meta.totalPages}
        onPageChange={setPage} noun="movement" />
    </div>
  );
}
