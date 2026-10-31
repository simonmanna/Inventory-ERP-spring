import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Clock } from 'lucide-react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { DataTable, type Column } from '@/components/data-table';
import { ListPageHeader } from '@/components/list';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { money } from '@/lib/format';
import { useApAging, type ApAgingBucketKey, type ApAgingPartner } from '@/features/invoicing/api';
import { tx } from '@/lib/i18n/tx';

const BUCKETS: { key: ApAgingBucketKey; label: string }[] = [
  { key: 'current', label: tx('Current') },
  { key: 'b1_30', label: tx('1-30 days') },
  { key: 'b31_60', label: tx('31-60 days') },
  { key: 'b61_90', label: tx('61-90 days') },
  { key: 'b90p', label: tx('90+ days') },
];

/** Accounts payable aging by supplier, from `/reports/ap/aging`. */
export function ApAgingPage() {
  const [asOf, setAsOf] = useState('');
  const { data, isLoading, isError } = useApAging(asOf || undefined);

  const columns: Column<ApAgingPartner>[] = [
    {
      key: 'partnerName',
      header: tx('Supplier'),
      render: (r) => (
        <Link to={`/suppliers/${r.partnerId}/ledger`} className="font-medium text-primary hover:underline">
          {r.partnerName || '-'}
        </Link>
      ),
    },
    ...BUCKETS.map((b) => ({
      key: b.key,
      header: b.label,
      className: 'text-right',
      render: (r: ApAgingPartner) => money(r.buckets[b.key]),
    })),
    { key: 'total', header: tx('Outstanding'), className: 'text-right font-medium', render: (r) => money(r.total) },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader
        title={tx('Accounts Payable')}
        description={tx('Outstanding supplier balances by age (derived from posted vendor bills).')}
        icon={Clock}
        actions={
          <div className="flex items-center gap-2">
            <Label htmlFor="asOf" className="text-sm">{tx('As of')}</Label>
            <Input id="asOf" type="date" className="h-9 w-40" value={asOf} onChange={(e) => setAsOf(e.target.value)} />
          </div>
        }
      />

      <div className="grid gap-4 sm:grid-cols-3 lg:grid-cols-6">
        {BUCKETS.map((b) => (
          <Card key={b.key}>
            <CardHeader className="pb-2">
              <CardDescription>{b.label}</CardDescription>
              <CardTitle className="text-xl">{money(data?.buckets[b.key].amount)}</CardTitle>
            </CardHeader>
            <CardContent className="text-xs text-muted-foreground">&nbsp;</CardContent>
          </Card>
        ))}
        <Card>
          <CardHeader className="pb-2">
            <CardDescription>{tx('Total')}</CardDescription>
            <CardTitle className="text-xl">{money(data?.total)}</CardTitle>
          </CardHeader>
          <CardContent className="text-xs text-muted-foreground">&nbsp;</CardContent>
        </Card>
      </div>

      {isError && <p className="text-sm text-destructive">{tx('Could not load the aging report.')}</p>}

      <DataTable
        columns={columns}
        data={data?.partners ?? []}
        loading={isLoading}
        getRowId={(r) => r.partnerId}
        emptyMessage={tx('No outstanding supplier balances.')}
      />
    </div>
  );
}
