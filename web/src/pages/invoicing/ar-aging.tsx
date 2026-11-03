import { useMemo, useState } from 'react';
import { Download } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { DataTable, type Column } from '@/components/data-table';
import { money, date } from '@/lib/format';
import { exportCSV } from '@/lib/export-csv';
import { useArAging, type AgingRow } from '@/features/invoicing/api';
import { tx } from '@/lib/i18n/tx';

const BUCKETS: { key: keyof import('@/features/invoicing/api').AgingBuckets; label: string }[] = [
  { key: 'current', label: tx('Current') },
  { key: 'd1_30', label: tx('1-30 days') },
  { key: 'd31_60', label: tx('31-60 days') },
  { key: 'd61_90', label: tx('61-90 days') },
  { key: 'd90_plus', label: tx('90+ days') },
];

export function ArAgingPage() {
  const [asOf, setAsOf] = useState('');
  const [customer, setCustomer] = useState('');
  const { data, isLoading, isError, error } = useArAging(asOf || undefined);

  const rows = useMemo(() => {
    const all = data?.rows ?? [];
    const q = customer.trim().toLowerCase();
    return q ? all.filter((r) => (r.partnerName ?? '').toLowerCase().includes(q)) : all;
  }, [data, customer]);

  const columns: Column<AgingRow>[] = [
    { key: 'documentNumber', header: tx('Invoice') },
    { key: 'partnerName', header: tx('Customer') },
    { key: 'dueDate', header: tx('Due'), render: (r) => date(r.dueDate) },
    { key: 'daysOverdue', header: tx('Days overdue'), render: (r) => (r.daysOverdue > 0 ? r.daysOverdue : '-') },
    { key: 'residual', header: tx('Outstanding'), className: 'text-right', render: (r) => money(r.residual) },
  ];

  const onExport = () => {
    exportCSV(
      `ar-aging-${asOf || new Date().toISOString().slice(0, 10)}.csv`,
      [tx('Invoice'), tx('Customer'), tx('Due'), tx('Days overdue'), tx('Outstanding')],
      rows.map((r) => [r.documentNumber, r.partnerName ?? '', r.dueDate ?? '', String(r.daysOverdue), String(r.residual)]),
    );
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">{tx('Accounts Receivable')}</h1>
          <p className="text-sm text-muted-foreground">{tx('Outstanding customer balances by age (derived from open invoices).')}</p>
        </div>
        <Button variant="outline" size="sm" onClick={onExport} disabled={rows.length === 0}>
          <Download className="mr-2 h-4 w-4" />
          {tx('Export CSV')}
        </Button>
      </div>

      <div className="flex flex-wrap items-end gap-3 rounded-lg border bg-card p-3">
        <div className="space-y-1">
          <Label htmlFor="ar-asof" className="text-xs text-muted-foreground">{tx('As of')}</Label>
          <Input id="ar-asof" type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} className="h-9" />
        </div>
        <div className="space-y-1 min-w-[220px]">
          <Label htmlFor="ar-customer" className="text-xs text-muted-foreground">{tx('Customer')}</Label>
          <Input
            id="ar-customer"
            value={customer}
            onChange={(e) => setCustomer(e.target.value)}
            placeholder={tx('Filter by customer')}
            className="h-9"
          />
        </div>
      </div>

      {isError && (
        <div className="rounded-md border border-destructive/40 bg-destructive/5 p-3 text-sm text-destructive">
          {tx('Could not load the aging report: {{v1}}', { v1: (error as Error)?.message ?? '' })}
        </div>
      )}

      <div className="grid gap-4 sm:grid-cols-3 lg:grid-cols-5">
        {BUCKETS.map((b) => (
          <Card key={b.key}>
            <CardHeader className="pb-2">
              <CardDescription>{b.label}</CardDescription>
              <CardTitle className="text-xl">{money(data?.buckets[b.key])}</CardTitle>
            </CardHeader>
            <CardContent className="text-xs text-muted-foreground">&nbsp;</CardContent>
          </Card>
        ))}
      </div>

      <DataTable columns={columns} data={rows} loading={isLoading} getRowId={(r) => r.documentId} />
    </div>
  );
}
